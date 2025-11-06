package lol.bai.ravel.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.FileColorManager
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.util.ui.JBUI
import lol.bai.ravel.mapping.MappingNsVisitor
import lol.bai.ravel.mapping.MioMappingConfig
import lol.bai.ravel.ui.RemapperDialog.ModuleList
import lol.bai.ravel.util.A
import lol.bai.ravel.util.B
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.jetbrains.annotations.NonNls
import javax.swing.JLabel
import javax.swing.JList
import com.intellij.ui.dsl.builder.panel as rootPanel

private val modelDataKey = DataKey.create<RemapperModel>("RemapperDialogModel")
private val modulesLabelKey = DataKey.create<JLabel>("RemapperDialogModulesLabel")
private val modulesListKey = DataKey.create<ModuleList>("RemapperDialogModulesList")

class RemapperDialog(
    val project: Project,
    val model: RemapperModel
) : DialogWrapper(project), DataProvider {

    val fileColor = FileColorManager.getInstance(project)!!

    val modulesLabel = JLabel(B("dialog.remapper.modules", 0, 0))
    lateinit var moduleList: ModuleList

    init {
        title = B("dialog.remapper.title")
        init()
    }

    override fun getData(dataId: @NonNls String): Any? {
        return when (dataId) {
            modelDataKey.name -> model
            modulesLabelKey.name -> modulesLabel
            modulesListKey.name -> moduleList
            else -> null
        }
    }

    override fun createCenterPanel() = rootPanel {
        val mappingsModel = CollectionListModel(model.mappings, true)
        val mappingsList = JBList<MioMappingConfig>().apply {
            model = mappingsModel
            setEmptyText(B("dialog.remapper.empty"))
        }
        val steps = ToolbarDecorator
            .createDecorator(mappingsList)
            .setAddAction {
                val fileDesc = FileChooserDescriptorFactory.singleFileOrDir()
                val file = FileChooser.chooseFile(fileDesc, project, null) ?: return@setAddAction
                val path = file.toNioPath()
                val format = MappingReader.detectFormat(path)

                if (format == null) {
                    Messages.showErrorDialog(project, B("dialog.mapping.unknownFormat"), B.error)
                    return@setAddAction
                }

                MappingReader.read(path, format, MappingNsVisitor)
                val namespaces = arrayListOf(MappingNsVisitor.src)
                namespaces.addAll(MappingNsVisitor.dst)

                var srcNs = MappingNsVisitor.src
                var dstNs = MappingNsVisitor.dst.first()

                val ok = DialogBuilder(project)
                    .title(B("dialog.mapping.title"))
                    .centerPanel(rootPanel {
                        row(B("dialog.mapping.format")) { label(format.name) }
                        row(B("dialog.mapping.srcNs")) {
                            comboBox(namespaces).bindItem({ srcNs }, { srcNs = it ?: srcNs })
                        }
                        row(B("dialog.mapping.dstNs")) {
                            comboBox(namespaces).bindItem({ dstNs }, { dstNs = it ?: dstNs })
                        }
                    })
                    .showAndGet()

                if (ok) {
                    val mapping = MemoryMappingTree()
                    val visitor =
                        if (srcNs == MappingNsVisitor.src) mapping
                        else MappingSourceNsSwitch(mapping, srcNs)

                    MappingReader.read(path, format, visitor)
                    mappingsModel.add(MioMappingConfig(mapping, srcNs, dstNs, path))
                }
            }
            .setPreferredSize(JBUI.size(300, 500))
            .createPanel()

        val moduleModel = CollectionListModel<ModuleEntry>()
        ModuleManager.getInstance(project).modules.sortedBy { it.name }.forEach { module ->
            if (module.rootManager.sourceRoots.isEmpty()) return@forEach
            moduleModel.add(ModuleEntry(module, false))
        }
        moduleList = ModuleList(moduleModel)
        val modules = ToolbarDecorator
            .createDecorator(moduleList)
            .disableAddAction()
            .disableRemoveAction()
            .disableUpDownActions()
            .addExtraAction(A<MarkAllAction>())
            .addExtraAction(A<MarkAction>())
            .addExtraAction(A<UnMarkAction>())
            .setPreferredSize(JBUI.size(400, 500))
            .createPanel()

        row {
            cell(steps).label(B("dialog.remapper.mappings"), LabelPosition.TOP)
            cell(modules).label(modulesLabel, LabelPosition.TOP)
        }
    }

    inner class ModuleList(
        val model: CollectionListModel<ModuleEntry>
    ) : JBList<ModuleEntry>() {
        init {
            super.model = model
            cellRenderer = ModuleCellRenderer()
        }
    }

    data class ModuleEntry(
        val module: Module,
        var selected: Boolean
    )

    inner class ModuleCellRenderer : ColoredListCellRenderer<ModuleEntry>() {
        override fun customizeCellRenderer(list: JList<out ModuleEntry>, value: ModuleEntry, index: Int, selected: Boolean, hasFocus: Boolean) {
            append(value.module.name)
            icon = ModuleType.get(value.module).icon
            if (value.selected) background = fileColor.getColor("Green")
            if (selected) background = JBUI.CurrentTheme.List.background(true, hasFocus)
        }
    }

    abstract class ModuleAction : AnAction() {
        abstract fun act(model: RemapperModel, modules: ModuleList)

        override fun actionPerformed(e: AnActionEvent) {
            val model = e.getData(modelDataKey) ?: return
            val modules = e.getData(modulesListKey) ?: return
            val modulesLabel = e.getData(modulesLabelKey) ?: return

            act(model, modules)
            modulesLabel.text = B("dialog.remapper.modules", model.modules.size, modules.model.size)
        }
    }

    class MarkAllAction : ModuleAction() {
        override fun act(model: RemapperModel, modules: ModuleList) {
            modules.model.items.forEach {
                model.modules.add(it.module)
                it.selected = true
            }
            modules.repaint()
        }
    }

    class MarkAction : ModuleAction() {
        override fun act(model: RemapperModel, modules: ModuleList) {
            modules.selectedValuesList.forEach {
                model.modules.add(it.module)
                it.selected = true
            }
        }
    }

    class UnMarkAction : ModuleAction() {
        override fun act(model: RemapperModel, modules: ModuleList) {
            modules.selectedValuesList.forEach {
                model.modules.remove(it.module)
                it.selected = false
            }
        }
    }

}
