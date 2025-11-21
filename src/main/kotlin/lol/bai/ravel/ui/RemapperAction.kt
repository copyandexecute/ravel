package lol.bai.ravel.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.currentThreadCoroutineScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.withModalProgress
import com.intellij.platform.util.progress.ProgressReporter
import com.intellij.platform.util.progress.reportProgress
import kotlinx.coroutines.launch
import lol.bai.ravel.mapping.MappingTree
import lol.bai.ravel.mapping.MioClassMapping
import lol.bai.ravel.mapping.MioMappingConfig
import lol.bai.ravel.remapper.RemapperExtension
import lol.bai.ravel.util.listMultiMap

data class RemapperModel(
    val mappings: MutableList<MioMappingConfig> = arrayListOf(),
    val modules: MutableSet<Module> = linkedSetOf(),
)

class RemapperAction : AnAction() {

    private val logger = thisLogger()

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    @Suppress("UnstableApiUsage")
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val model = RemapperModel()
        val ok = RemapperDialog(project, model).showAndGet()

        if (ok && model.mappings.isNotEmpty() && model.modules.isNotEmpty()) {
            currentThreadCoroutineScope().launch { remap(project, model) }
        }
    }

    suspend fun <T> run(project: Project, title: String, size: Int, block: suspend (ProgressReporter) -> T): T {
        return withModalProgress(ModalTaskOwner.project(project), title, TaskCancellation.nonCancellable()) {
            reportProgress(size) { reporter ->
                block(reporter)
            }
        }
    }

    suspend fun remap(project: Project, model: RemapperModel) = run(project, B("dialog.remapper.title"), 2) { rootProcess ->
        val time = System.currentTimeMillis()

        val mTree = rootProcess.indeterminateStep(B("progress.readingMappings")) {
            val mTree = MappingTree()
            model.mappings.first().tree.classes.forEach {
                mTree.putClass(MioClassMapping(model.mappings, it))
            }
            mTree
        }

        val files = rootProcess.indeterminateStep(B("progress.fileTraverse")) {
            val files = arrayListOf<Pair<VirtualFile, Module>>()
            for (module in model.modules) {
                for (root in module.rootManager.sourceRoots) {
                    VfsUtil.iterateChildrenRecursively(root, null) {
                        if (it.isFile) files.add(it to module)
                        true
                    }
                }
            }
            files
        }

        val fileWriters = rootProcess.itemStep(B("progress.resolving", files.size)) {
            val fileCount = files.size
            var fileIndex = 1
            reportProgress(fileCount) { fileProcess ->
                val fileWriters = listMultiMap<VirtualFile, () -> Unit>()
                for ((vf, module) in files) fileProcess.itemStep("${vf.path} (${fileIndex}/${fileCount})") i@{
                    fileIndex++
                    if (!vf.isFile) return@i

                    for (remapper in RemapperExtension.createInstances()) readActionBlocking r@{
                        if (!remapper.regex.matches(vf.name)) return@r

                        val scope = module.getModuleWithDependenciesAndLibrariesScope(true)
                        val valid = remapper.init(project, scope, mTree, vf) { writer -> fileWriters.put(vf, writer) }
                        if (!valid) return@r

                        try {
                            remapper.stages().forEach { it.run() }
                        } catch (e: Exception) {
                            fileWriters.put(vf) { remapper.fileComment("TODO(Ravel): Failed to fully resolve file: ${e.message}") }
                            logger.error("Failed to fully resolve ${vf.path}", e)
                        }
                    }
                }
                fileWriters
            }
        }

        logger.warn("Mapping resolved in ${System.currentTimeMillis() - time}ms")

        val fileCount = fileWriters.size
        var fileIndex = 1
        rootProcess.itemStep(B("progress.remapping", fileCount)) {
            reportProgress(fileWriters.values.sumOf { it.size }) { writerProcess ->
                @Suppress("UnstableApiUsage")
                fileWriters.forEach { (vf, writers) ->
                    writers.forEach { writer ->
                        try {
                            writerProcess.itemStep("${vf.path} (${fileIndex}/${fileCount})") {
                                writeCommandAction(project, "Ravel Writer") {
                                    writer.invoke()
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to write ${vf.path}", e)
                        }
                    }
                    fileIndex++
                }
            }
        }

        logger.warn("Remap finished in ${System.currentTimeMillis() - time}ms")
    }

}
