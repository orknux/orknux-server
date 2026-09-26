package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.llm.LlmSessionStore
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * An agent planning its work before it starts, over one session. Issue #405.
 *
 * ### What it is for
 *
 * A to-do list is where an agent lays a job out in the order it means to do it,
 * and then works down it: five things to build, in sequence, each ticked off as
 * it lands. It is the plan an agent that cannot hold the whole job in one turn
 * keeps outside its head, so a long piece of work does not drift as the
 * transcript trims away what it set out to do.
 *
 * Five verbs, and no more: add an item, list what is there, reorder them, note
 * something against one, complete it. This shipped as a plugin an installation
 * had to load; it is a general "plan work before starting it" capability that
 * belongs beside the other built-in agent tools, so it is one of them now, and
 * an agent that used the plugin's verbs sees the same tools with the same names.
 *
 * ### Why the session, and why the session store
 *
 * A job is the span an agent plans across, and a session is the job: a workflow
 * node keyed to a session shares the list with every node computing the same
 * key, and a task is one session from start to finish. So the list lives in the
 * session store, keyed by the session, the way [NoteTools] keeps a note - it
 * lasts exactly as long as the session and goes when it goes, and nobody outside
 * the session reads it.
 *
 * It is not offered where there is no session, for the reason nothing in this
 * package is: a tool that takes a plan and has nowhere to keep it is worse than
 * no tool, because the agent goes on believing the plan was kept.
 *
 * ### Not a note, and not the workspace's memory
 *
 * A note ([NoteTools]) is a line an agent writes to remember a finding; a
 * to-do item is a thing it means to do and will tick off. Memory
 * ([io.mszymanski.orknux.server.memory.MemoryTool]) is what the workspace knows,
 * for everybody and kept; a to-do list is one agent's plan for one job, and
 * gone when the job is.
 */
@Service
class TodoTools(
    private val store: LlmSessionStore,
    private val mapper: ObjectMapper,
) {

    /** The shed for one turn, or null where there is no session to keep a list in. */
    fun shed(session: Long?): ToolShed? = if (session == null) null else Shed(session)

    /**
     * The list as it is put back into a turn, open items first so the plan is in
     * front of the model without its having to ask.
     *
     * Empty where nothing is on it, which is most turns - and an empty string
     * rather than a heading over nothing, so a prompt carries no section that
     * says only that there is no section. Completed items are a count rather
     * than a list: what is done is done, and re-reading it every turn is paid
     * for every turn.
     */
    fun recalled(session: Long?): String {
        if (session == null) return ""
        val held = load(session)
        val open = held.items.filterNot { it.done }
        if (open.isEmpty() && held.items.isEmpty()) return ""

        return buildString {
            appendLine("Your to-do list for this job, in order. It is yours; add with $ADD, tick off with $COMPLETE.")
            open.forEach { item ->
                append("\n- [ ] (${item.id}) ${item.text}")
                item.note?.let { append(" — $it") }
            }
            val done = held.items.count { it.done }
            if (done > 0) append("\n(${done} already done)")
        }
    }

    private inner class Shed(private val session: Long) : ToolShed {

        override fun specs(): List<ToolSpec> = listOf(
            ToolSpec(
                name = ADD,
                description = "Adds an item to your to-do list for this job. Use the list to plan work you " +
                    "cannot finish in one turn: lay the steps out, then work down them. The item is added at " +
                    "the end and given a number you use to note or complete it later.",
                parameters = listOf(
                    ToolParameterSpec(TEXT, "The thing to do, in a line.", required = true),
                ),
            ),
            ToolSpec(
                name = LIST,
                description = "Lists your to-do items for this job - their numbers, text, any note, and which " +
                    "are done. Read it before assuming what you have planned or finished.",
                parameters = emptyList(),
            ),
            ToolSpec(
                name = REORDER,
                description = "Puts your to-do items in a new order. Give $ORDER as the item numbers in the " +
                    "order you want them; any you leave out keep their order after the ones you named.",
                parameters = listOf(
                    ToolParameterSpec(ORDER, "The item numbers, as a list, in the order you want them.", required = true),
                ),
            ),
            ToolSpec(
                name = NOTE,
                description = "Notes something against a to-do item - what you found while doing it, why it is " +
                    "blocked, what is left. Replaces any note already on it.",
                parameters = listOf(
                    ToolParameterSpec(ID, "The number of the item to note against.", required = true),
                    ToolParameterSpec(NOTE_TEXT, "The note, in a line. Empty to clear it.", required = true),
                ),
            ),
            ToolSpec(
                name = COMPLETE,
                description = "Marks a to-do item done, so what is left to do is clear. Give its number.",
                parameters = listOf(
                    ToolParameterSpec(ID, "The number of the item to complete.", required = true),
                ),
            ),
        )

        override fun handles(name: String): Boolean = name in NAMES

        override fun run(call: ToolCall): String {
            val args = runCatching { mapper.readTree(call.arguments) }.getOrNull() ?: mapper.createObjectNode()
            return when (call.name) {
                ADD -> added(args)
                LIST -> listed()
                REORDER -> reordered(args)
                NOTE -> noted(args)
                COMPLETE -> completed(args)
                else -> refusal("There is no to-do tool called ${call.name}.")
            }
        }

        private fun added(args: JsonNode): String {
            val text = text(args, TEXT) ?: return refusal("Say what to add to the list.")
            if (text.length > LONGEST) {
                return refusal("That item is too long; say it in under $LONGEST characters.")
            }
            val held = load(session)
            if (held.items.size >= MOST) {
                return refusal(
                    "Your to-do list already holds $MOST items, which is as many as it keeps. " +
                        "Complete some, or work with the ones you have.",
                )
            }
            val id = held.seq + 1
            val next = held.copy(seq = id, items = held.items + TodoItem(id = id, text = text))
            return save(next) ?: listing(next, added = id)
        }

        private fun listed(): String = listing(load(session))

        private fun reordered(args: JsonNode): String {
            val wanted = ids(args, ORDER) ?: return refusal("Say the item numbers in the order you want them.")
            val held = load(session)
            val byId = held.items.associateBy { it.id }
            val unknown = wanted.filterNot { it in byId }
            if (unknown.isNotEmpty()) return refusal("There is no item numbered ${unknown.first()}.")

            // The named ones first, in the order given; then whatever was left,
            // in the order it already had. A partial order is a nudge, not a
            // demand to account for every item.
            val front = wanted.distinct().mapNotNull { byId[it] }
            val named = front.map { it.id }.toSet()
            val rest = held.items.filterNot { it.id in named }
            val next = held.copy(items = front + rest)
            return save(next) ?: listing(next)
        }

        private fun noted(args: JsonNode): String {
            val id = id(args) ?: return refusal("Say the number of the item to note against.")
            val note = text(args, NOTE_TEXT).orEmpty()
            if (note.length > LONGEST) return refusal("That note is too long; say it in under $LONGEST characters.")
            val held = load(session)
            if (held.items.none { it.id == id }) return refusal("There is no item numbered $id.")
            val next = held.copy(
                items = held.items.map { if (it.id == id) it.copy(note = note.ifEmpty { null }) else it },
            )
            return save(next) ?: listing(next)
        }

        private fun completed(args: JsonNode): String {
            val id = id(args) ?: return refusal("Say the number of the item to complete.")
            val held = load(session)
            if (held.items.none { it.id == id }) return refusal("There is no item numbered $id.")
            val next = held.copy(items = held.items.map { if (it.id == id) it.copy(done = true) else it })
            return save(next) ?: listing(next)
        }

        private fun listing(held: TodoList, added: Int? = null): String = mapper.writeValueAsString(
            buildMap<String, Any> {
                added?.let { put("added", it) }
                put(
                    "todos",
                    held.items.map {
                        buildMap<String, Any> {
                            put("id", it.id)
                            put("text", it.text)
                            it.note?.let { note -> put("note", note) }
                            put("done", it.done)
                        }
                    },
                )
            },
        )

        /** Saves the list, or a refusal in the store's own words where it would not take it. */
        private fun save(held: TodoList): String? =
            store.put(session, KEY, mapper.writeValueAsString(held.toStore()))?.let { refusal(it) }
    }

    /*
     * Read and written as maps rather than bound to the data class directly: the
     * store keeps JSON, and the round-trip goes through the shapes the rest of
     * the server serialises - a map out, a map back - rather than constructor
     * binding, which is not what the mapper here is set up for.
     */
    private fun load(session: Long): TodoList {
        val held = store.get(session, KEY) ?: return TodoList()
        return runCatching { fromStore(mapper.readValue(held, Map::class.java)) }.getOrDefault(TodoList())
    }

    private fun TodoList.toStore(): Map<String, Any> = mapOf(
        "seq" to seq,
        "items" to items.map { item ->
            buildMap<String, Any> {
                put("id", item.id)
                put("text", item.text)
                item.note?.let { put("note", it) }
                put("done", item.done)
            }
        },
    )

    @Suppress("UNCHECKED_CAST")
    private fun fromStore(held: Map<*, *>): TodoList {
        val items = (held["items"] as? List<Map<*, *>>).orEmpty().map {
            TodoItem(
                id = (it["id"] as? Number)?.toInt() ?: 0,
                text = it["text"] as? String ?: "",
                note = it["note"] as? String,
                done = it["done"] as? Boolean ?: false,
            )
        }
        return TodoList(seq = (held["seq"] as? Number)?.toInt() ?: items.maxOfOrNull { it.id } ?: 0, items = items)
    }

    private fun text(node: JsonNode, field: String): String? =
        node.path(field).takeIf { it.isTextual }?.stringValue()?.trim()?.takeIf { it.isNotEmpty() }

    private fun id(node: JsonNode): Int? = intOf(node.path(ID))

    private fun ids(node: JsonNode, field: String): List<Int>? {
        val at = node.path(field)
        val list = when {
            at.isArray -> at.mapNotNull { intOf(it) }
            // A model that sends the numbers as one string - "3, 1, 2" - rather
            // than a JSON array is understood too.
            at.isTextual -> at.stringValue().split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
            else -> return null
        }
        return list.takeIf { it.isNotEmpty() }
    }

    private fun intOf(node: JsonNode): Int? =
        when {
            node.isNumber -> node.asInt()
            node.isTextual -> node.stringValue().trim().toIntOrNull()
            else -> null
        }

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    /** One item on the list. Serialised into the session store as part of [TodoList]. */
    data class TodoItem(
        val id: Int = 0,
        val text: String = "",
        val note: String? = null,
        val done: Boolean = false,
    )

    /** The whole list, and the highest number handed out, so numbers do not repeat. */
    data class TodoList(
        val seq: Int = 0,
        val items: List<TodoItem> = emptyList(),
    )

    companion object {
        const val ADD = "todo_add"
        const val LIST = "todo_list"
        const val REORDER = "todo_reorder"
        const val NOTE = "todo_note"
        const val COMPLETE = "todo_complete"

        val NAMES = setOf(ADD, LIST, REORDER, NOTE, COMPLETE)

        const val TEXT = "text"
        const val ID = "id"
        const val ORDER = "order"
        const val NOTE_TEXT = "note"

        /** Where the list lives in the session store. Distinctive, so it is plainly not an agent's own key. */
        const val KEY = "__orknux_todo__"

        /**
         * How many items one list keeps.
         *
         * Fifty. A plan is a handful of steps; a list long enough to need paging
         * is not being worked down, it is being written instead of the work.
         */
        const val MOST = 50

        /** As long as one item or one note may be. */
        const val LONGEST = 300
    }
}
