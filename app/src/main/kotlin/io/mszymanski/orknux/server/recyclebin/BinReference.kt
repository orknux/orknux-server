package io.mszymanski.orknux.server.recyclebin

import tools.jackson.databind.JsonNode

/**
 * Something a binned object pointed at, as its snapshot keeps it: the id it had,
 * and the name it went by.
 *
 * Both, because an id alone does not survive the bin. Whatever is put back gets
 * a new one, so an action deleted and then its function, both restored, would
 * have the action naming an id nothing answers to - and on the columns that are
 * foreign keys, a restore that fails as an internal error. So a restore looks by
 * id first, which is right whenever the thing was never deleted, and by name
 * second, which is how something that has itself been through the bin is found
 * again.
 */
data class BinReference(val id: Long?, val name: String?) {

    /**
     * What this points at now, or null where it pointed at nothing.
     *
     * [byId] and [byName] answer only with what the workspace may use - each
     * door knows whose a function or a connection is - and where neither does,
     * the restore is refused with [RecycleBinReferenceGoneException], naming
     * [what] is missing ("the function") and the object that needs it.
     */
    fun <T : Any> resolve(
        kind: String,
        owner: String,
        what: String,
        byId: (Long) -> T?,
        byName: (String) -> T?,
    ): T? {
        if (id == null) return null
        byId(id)?.let { return it }
        name?.let(byName)?.let { return it }
        throw RecycleBinReferenceGoneException(kind, owner, what, name)
    }

    companion object {

        /** The reference a snapshot holds as `<field>Id` and `<field>Name`. */
        fun at(held: JsonNode, field: String): BinReference =
            BinReference(held.longAt("${field}Id"), held.textAt("${field}Name"))

        /** One held as an object of its own, `{ "id": …, "name": … }`, in a list of them. */
        fun of(held: JsonNode): BinReference = BinReference(held.longAt("id"), held.textAt("name"))
    }
}

/*
 * Reading a snapshot, a field at a time. A value that is missing or of the wrong
 * kind reads as null rather than as a failure: a snapshot outlives the release
 * that wrote it, and a field a later one adds is simply not in an older entry.
 */

fun JsonNode.textAt(name: String): String? = path(name).takeIf { it.isString }?.stringValue()

fun JsonNode.longAt(name: String): Long? = path(name).takeIf { it.isNumber }?.asLong()

fun JsonNode.flagAt(name: String): Boolean? = path(name).takeIf { it.isBoolean }?.asBoolean()

inline fun <reified E : Enum<E>> JsonNode.enumAt(name: String): E? =
    textAt(name)?.let { said -> enumValues<E>().firstOrNull { it.name == said } }
