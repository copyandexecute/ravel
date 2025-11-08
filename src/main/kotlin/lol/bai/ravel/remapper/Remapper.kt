package lol.bai.ravel.remapper

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.RequiredElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.xmlb.annotations.Attribute
import lol.bai.ravel.mapping.MappingTree

private val EP = ExtensionPointName<RemapperBean>("lol.bai.ravel.remapper")

abstract class Remapper(
    val regex: Regex
) {
    companion object {
        fun createInstances(): List<Remapper> {
            val result = mutableListOf<Remapper>()
            EP.forEachExtensionSafe {
                result.add(Class.forName(it.implementation).getConstructor().newInstance() as Remapper)
            }
            return result
        }
    }

    protected lateinit var project: Project
    protected lateinit var scope: GlobalSearchScope
    protected lateinit var mTree: MappingTree
    protected lateinit var file: VirtualFile
    protected lateinit var write: Writer

    protected abstract fun init(): Boolean
    fun init(project: Project, scope: GlobalSearchScope, mTree: MappingTree, file: VirtualFile, write: Writer): Boolean {
        this.project = project
        this.scope = scope
        this.mTree = mTree
        this.file = file
        this.write = write
        return init()
    }

    abstract fun fileComment(comment: String)
    abstract fun remap()

}

class RemapperBean {

    @field:Attribute
    @field:RequiredElement
    lateinit var implementation: String

}
