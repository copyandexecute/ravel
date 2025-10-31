package lol.bai.ravel

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.tree.MemoryMappingTree
import java.nio.file.Path

data class Mapping(
    val tree: MemoryMappingTree,
    val source: String,
    val dest: String,
    val path: Path,
) {
    override fun toString() = "$source -> $dest ($path)"
}

object MappingNsVisitor : MappingVisitor {
    lateinit var src: String
    lateinit var dst: List<String>

    override fun visitNamespaces(srcNamespace: String, dstNamespaces: List<String>) {
        src = srcNamespace
        dst = dstNamespaces
    }

    override fun visitContent() = false

    // @formatter:off
    private fun wtf(): Nothing = throw UnsupportedOperationException()
    override fun visitClass(srcName: String) = wtf()
    override fun visitField(srcName: String?, srcDesc: String?) = wtf()
    override fun visitMethod(srcName: String?, srcDesc: String?) = wtf()
    override fun visitMethodArg(argPosition: Int, lvIndex: Int, srcName: String?) = wtf()
    override fun visitMethodVar(lvtRowIndex: Int, lvIndex: Int, startOpIdx: Int, endOpIdx: Int, srcName: String?) = wtf()
    override fun visitDstName(targetKind: MappedElementKind?, namespace: Int, name: String?) = wtf()
    override fun visitComment(targetKind: MappedElementKind?, comment: String?) = wtf()
    // @formatter:on
}
