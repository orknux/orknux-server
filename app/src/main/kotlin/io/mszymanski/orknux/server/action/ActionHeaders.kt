package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * One header an HTTP request action sends, and where its value comes from.
 *
 * Exactly one of [literal] and [variableId] is set, the way a plugin parameter's
 * two are. A literal is text somebody typed and is stored as typed, in the clear,
 * because the form shows it back to them; [variableId] is for everything that
 * should not be stored that way, and it names the variable rather than copying
 * what the variable holds. That is the whole point of the reference: a bearer
 * token stays in the variables screen, encrypted at rest and revealed only as an
 * audited act, and the action holds a number pointing at it.
 */
data class ActionHeaderRow(
    val name: String,
    val literal: String? = null,
    val variableId: Long? = null,
)

/**
 * One header as a screen sees it: what it is called, and what it reads.
 *
 * [variableName] carries only the name of the variable a reference points at -
 * never what it holds, which is the same rule `PluginParameterSettingView`
 * follows and for the same reason. A form that could read a workspace's secrets
 * by asking for an action would be a way around the variables screen.
 */
data class ActionHeaderView(
    val name: String,
    val value: String?,
    val variableId: String?,
    val variableName: String?,
)

/**
 * What an HTTP request action's headers are written as, and what they come to.
 *
 * The headers live where they always have - one text column on the action,
 * holding JSON - and the grammar of that JSON is what widened. Two shapes are
 * readable and only one is ever written:
 *
 *  - **An object** is what every action saved before references existed holds:
 *    `{"Authorization": "Bearer …"}`, one literal per key. It is read as rows in
 *    the order the keys were written and is never written back in that shape, so
 *    an action people already have goes on sending exactly what it sent until
 *    somebody saves the form, and sends exactly the same thing afterwards.
 *
 *  - **An array** is the shape this writes: `[{"name": …, "value": …}, {"name":
 *    …, "variableId": "12"}]`. Ordered, so two headers may share a name the way
 *    HTTP allows, and each row says which of the two sources it is.
 *
 * Anything else - a truncated blob, a smart quote, a fragment somebody pasted -
 * is left in the column exactly as it stands and read as nothing. That is what
 * the runner already did with unreadable JSON, so an action that has been
 * quietly sending no headers goes on quietly sending no headers rather than
 * starting to fail; the form is what says so, where somebody can fix it.
 *
 * Nothing here logs a resolved value. A header is as likely to be an
 * authorization as an accept, and the two are indistinguishable by the time they
 * are here.
 */
@Component
class ActionHeaders(
    private val variables: WorkspaceVariableRepository,
    @param:org.springframework.context.annotation.Lazy private val sharing: io.mszymanski.orknux.server.sharing.WorkspaceSharing,
    private val mapper: ObjectMapper,
) {

    /**
     * The rows the written JSON comes to, or null when it is not readable as any.
     *
     * Null and empty are different answers and both callers care which: empty is
     * an action that sends no headers, null is an action whose headers nobody can
     * read. Blank text is empty, not null - a column that was never filled in is
     * not a broken one.
     */
    fun rowsOf(written: String?): List<ActionHeaderRow>? {
        val text = written?.trim()?.ifEmpty { null } ?: return emptyList()
        val tree = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
        return when {
            tree.isArray -> rowsInArray(tree)
            // The old shape. Jackson keeps insertion order, so the rows come out
            // in the order they were typed rather than sorted under somebody.
            tree.isObject -> tree.properties().map { (name, value) ->
                ActionHeaderRow(name = name, literal = value.asString())
            }

            else -> null
        }
    }

    /** The rows as the column holds them, or null when there are none to hold. */
    fun write(rows: List<ActionHeaderRow>): String? {
        val kept = rows.filter { it.name.isNotBlank() }
        if (kept.isEmpty()) return null

        val array = mapper.createArrayNode()
        kept.forEach { row ->
            array.addObject().apply {
                put("name", row.name)
                if (row.variableId != null) {
                    put("variableId", row.variableId.toString())
                } else {
                    put("value", row.literal.orEmpty())
                }
            }
        }
        return mapper.writeValueAsString(array)
    }

    /**
     * The rows a form sent, checked and tidied into what may be stored.
     *
     * A row with no name is dropped rather than refused: an empty row is what the
     * editor leaves behind when somebody presses Add and then changes their mind,
     * and every other row builder in this interface drops those on save too.
     */
    fun checked(workspaceId: Long, rows: List<ActionHeaderRow>): List<ActionHeaderRow> = rows
        .map { row -> row.copy(name = row.name.trim()) }
        .filter { it.name.isNotEmpty() }
        .map { row ->
            if (row.literal != null && row.variableId != null) throw ActionHeaderAmbiguousException(row.name)
            if (row.literal == null && row.variableId == null) throw ActionHeaderEmptyException(row.name)

            row.variableId?.let { id ->
                val variable = variables.findByIdOrNull(id) ?: throw ActionHeaderVariableElsewhereException(row.name)
                if (!sharing.usable(workspaceId, variable)) throw ActionHeaderVariableElsewhereException(row.name)
            }
            row
        }

    /** One action's headers as a screen may see them: names throughout, values only where they were typed. */
    fun viewOf(action: WorkflowAction): List<ActionHeaderView> = rowsOf(action.headers).orEmpty().map { row ->
        val variable = row.variableId?.let { variables.findByIdOrNull(it) }
        ActionHeaderView(
            name = row.name,
            value = if (row.variableId == null) row.literal else null,
            variableId = row.variableId?.toString(),
            // The name only. What it holds is read on the variables screen, where
            // reading it is recorded as something somebody did.
            variableName = variable?.takeIf { sharing.usable(action.workspaceId, it) }?.name,
        )
    }

    /** Whether this action's headers read that variable, which is what holds it in place. */
    fun reads(action: WorkflowAction, variableId: Long): Boolean =
        rowsOf(action.headers).orEmpty().any { it.variableId == variableId }

    /**
     * The headers to send, with every reference read now rather than when it was
     * saved.
     *
     * Reading at run time is the point: rotating a token means changing it in one
     * place, and the action never holds a copy of it to go stale or to be read off
     * a screen.
     */
    fun sentBy(action: WorkflowAction): Map<String, String> {
        val rows = rowsOf(action.headers)
        if (rows == null) {
            // Named, not valued - and not a failure. An action whose headers have
            // been unreadable has been sending none of them all along, and the
            // call that has been working is not one to start failing now.
            log.warn("The headers on the action {} cannot be read, so none are sent", action.name)
            return emptyMap()
        }
        return rows.associate { row -> row.name to valueFor(action, row) }
    }

    /**
     * What one row comes to. Names in every failure, never what was read.
     *
     * A reference that resolves to nothing stops the step rather than sending the
     * header empty: a request that quietly loses its Authorization gets a 401 that
     * says nothing about why, and the reason is a piece of configuration, which
     * does not appear because something was tried a second time.
     */
    private fun valueFor(action: WorkflowAction, row: ActionHeaderRow): String {
        val variableId = row.variableId ?: return row.literal.orEmpty()

        val variable = variables.findByIdOrNull(variableId)
            ?: throw ActionHeaderUnresolvedException("${row.name} reads a variable that no longer exists")
        if (!sharing.usable(action.workspaceId, variable)) {
            throw ActionHeaderUnresolvedException(
                "${row.name} reads a variable this workspace does not hold, which is what an imported action " +
                    "looks like until its references are set again",
            )
        }
        return variable.value?.ifEmpty { null }
            ?: throw ActionHeaderUnresolvedException("${row.name} reads ${variable.name}, which holds nothing")
    }

    private fun rowsInArray(tree: JsonNode): List<ActionHeaderRow>? {
        val rows = mutableListOf<ActionHeaderRow>()
        tree.values().forEach { entry ->
            if (!entry.isObject) return null
            val name = entry.path("name").takeIf { it.isString }?.stringValue()?.trim().orEmpty()
            if (name.isEmpty()) return null

            val held = entry.path("variableId")
            val variableId = if (held.isMissingNode || held.isNull) null else held.asString().trim().toLongOrNull()
            val value = entry.path("value").takeIf { it.isString }?.stringValue()

            // One source per row, the same rule the column would carry if this
            // were a table with a check constraint on it.
            if ((value == null) == (variableId == null)) return null
            rows += ActionHeaderRow(name = name, literal = value, variableId = variableId)
        }
        return rows
    }

    private companion object {
        val log = LoggerFactory.getLogger(ActionHeaders::class.java)
    }
}

class ActionHeaderAmbiguousException(val name: String) : RuntimeException(
    "The header \"$name\" was given both a value and a variable to read. It is one or the other.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class ActionHeaderEmptyException(val name: String) : RuntimeException(
    "The header \"$name\" was given neither a value nor a variable. Remove the row instead if that is what you meant.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class ActionHeaderVariableElsewhereException(val name: String) : RuntimeException(
    "That variable belongs to another workspace, so the header \"$name\" cannot read it.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/** Raised while a step runs; the runner turns it into a permanent failure. */
class ActionHeaderUnresolvedException(message: String) : RuntimeException(message)
