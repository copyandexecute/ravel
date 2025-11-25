package lol.bai.ravel.mapping

class BasicClassMapping(
    override val oldName: String,
    override val newName: String?
) : MutableClassMapping()

class BasicFieldMapping(
    override val oldName: String,
    override val newName: String?
): FieldMapping()

class BasicMethodMapping(
    override val oldName: String,
    override val oldDesc: String,
    override val newName: String?
) : MethodMapping()
