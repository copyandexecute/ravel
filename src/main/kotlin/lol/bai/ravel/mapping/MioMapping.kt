package lol.bai.ravel.mapping

import java.nio.file.Path

typealias MappingTreeImpl = net.fabricmc.mappingio.tree.MemoryMappingTree
typealias ClassMappingImpl = net.fabricmc.mappingio.tree.MappingTree.ClassMapping
typealias FieldMappingImpl = net.fabricmc.mappingio.tree.MappingTree.FieldMapping
typealias MethodMappingImpl = net.fabricmc.mappingio.tree.MappingTree.MethodMapping

class MioMappingConfig(
    val tree: MappingTreeImpl,
    val source: String,
    val dest: String,
    val path: Path,
) {
    override fun toString() = "$source -> $dest ($path)"
}

class MioClassMapping(
    private val configs: List<MioMappingConfig>,
    private val mio: ClassMappingImpl
) : ClassMapping() {

    override val oldName by lazy { mio.srcName!! }
    override val newName by lazy l@{
        var className = mio.srcName

        for (config in configs) {
            val mClass = config.tree.getClass(className) ?: return@l null
            className = mClass.getName(config.dest)
        }

        if (className == mio.srcName) null else className
    }

    override fun getAllFieldsImpl() = mio.fields.map { MioFieldMapping(configs, it) }
    override fun getFieldImpl(name: String): FieldMapping? {
        val mField = mio.getField(name, null) ?: return null
        return MioFieldMapping(configs, mField)
    }

    override fun getAllMethodImpl() = mio.methods.map { MioMethodMapping(configs, it) }
    override fun getMethodImpl(name: String, desc: String): MethodMapping? {
        val mMethod = mio.getMethod(name, desc) ?: return null
        return MioMethodMapping(configs, mMethod)
    }

}

class MioFieldMapping(
    private val configs: List<MioMappingConfig>,
    private val mio: FieldMappingImpl
) : FieldMapping() {

    override val oldName by lazy { mio.srcName!! }
    override val newName by lazy l@{
        var className = mio.owner.srcName
        var fieldName = mio.srcName

        for (config in configs) {
            val mClass = config.tree.getClass(className) ?: return@l null
            val mField = mClass.getField(fieldName, null) ?: return@l null
            className = mClass.getName(config.dest)
            fieldName = mField.getName(config.dest)
        }

        if (fieldName == oldName) null else fieldName
    }

}

class MioMethodMapping(
    private val configs: List<MioMappingConfig>,
    private val mio: MethodMappingImpl
) : MethodMapping() {

    override val oldName by lazy { mio.srcName!! }
    override val oldDesc by lazy { mio.srcDesc!! }
    override val newName by lazy l@{
        var className = mio.owner.srcName
        var methodName = mio.srcName
        var methodDesc = mio.srcDesc

        for (config in configs) {
            val mClass = config.tree.getClass(className) ?: return@l null
            val mMethod = mClass.getMethod(methodName, methodDesc) ?: return@l null
            className = mClass.getName(config.dest)
            methodName = mMethod.getName(config.dest)
            methodDesc = mMethod.getDesc(config.dest)
        }

        if (methodName == oldName) null else methodName
    }

}
