package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.llm.SessionFrames

/**
 * The rules [AgentConversation] keeps a session's frame by - its system turn
 * and tool array, sent byte for byte the same from turn to turn - written apart
 * so they can be read, and tested, without a round. See [SessionFrames] for why.
 */
internal object ConversationFrame {

    private const val SYSTEM = "system"

    /** The system turn's text, or null where there is none. */
    fun systemOf(turns: List<ChatTurn>): String? = turns.firstOrNull { it.role == SYSTEM }?.content

    /** [turns] with [system] as their system turn, put first where there was none and taken out where it is null. */
    fun withSystem(turns: List<ChatTurn>, system: String?): List<ChatTurn> {
        val at = turns.indexOfFirst { it.role == SYSTEM }
        return when {
            at >= 0 && system != null -> turns.mapIndexed { index, turn -> if (index == at) turn.copy(content = system) else turn }
            at >= 0 -> turns.filterIndexed { index, _ -> index != at }
            system != null -> listOf(ChatTurn(role = SYSTEM, content = system)) + turns
            else -> turns
        }
    }

    /**
     * [frame] with the tools loaded in earlier turns joined to its array: the
     * array as it was, then each tool in [found] that it does not carry and the
     * agent still holds, in the order they were loaded, and the system turn's
     * marks for them changed to loaded. [frame] itself where there is none.
     *
     * Only where the provider joins loaded tools ([LoadedTools.JOIN]); where
     * it does not, a loaded tool is called through [ToolRun] until the frame
     * is made again anyway, which puts it in the array.
     *
     * Asked at the start of a turn and never inside one, so every round of a
     * turn sends the array it started with and keeps the prompt's cache. The
     * array grows only at the end and only by what was loaded since, so a turn
     * that loaded nothing sends the bytes the one before it did.
     *
     * The cost is one: the turn after a load re-reads the whole prompt, as any
     * change to the array makes a local server do. It is paid because a tool
     * left out of the array cannot be called by its own name on llama.cpp,
     * whose tool-call grammar admits only the names the request declares - a
     * model calling a loaded tool by name got its nearest neighbour instead,
     * scratchpad_keep for scratchpad_write, five times in session 900. Once a
     * tool and not once per round, and only for a tool the model went looking for.
     *
     * @param found what was loaded with tool_load in this conversation, in the order it was.
     * @param held everything the agent holds now: a tool loaded and since taken away is not added.
     * @param cap the most tools one request to the provider takes, or null for no limit. What
     *   does not fit stays outside the array and is called through [ToolRun], as before.
     */
    fun joined(frame: SessionFrames.Frame, found: Collection<String>, held: List<ToolSpec>, cap: Int?): SessionFrames.Frame {
        val carried = frame.tools.map { it.name }.toSet()
        val byName = held.associateBy { it.name }
        val room = ((cap ?: Int.MAX_VALUE) - frame.tools.size).coerceAtLeast(0)
        val joining = found.distinct().filter { it !in carried }.mapNotNull { byName[it] }.take(room)
        if (joining.isEmpty()) return frame
        val tools = frame.tools + joining
        val system = BuiltInTools.listed(frame.system, emptyList(), tools.map { it.name }.toSet(), frame.hunting, LoadedTools.JOIN)
        return frame.copy(tools = tools, system = system)
    }

    /**
     * What has changed since [frame] last said so, as one message for the
     * model, and the frame with that said. Null where nothing has.
     *
     * Four things, because they are what a new frame would have shown it: the
     * tools it holds now that the array does not carry, with how each is called
     * - whole, or by name past [whole] where the frame left things to be
     * found; the ones whose declaration moved since it was told of them, whole;
     * the ones it no longer holds; and the lines of what it
     * would be told now that differ from what it was told, the list of tools
     * left out because the first two say that better.
     *
     * @param fresh the system turn the agent would be sent if it were framed now.
     * @param held everything the agent holds this turn, its own and lent.
     * @param found what it has loaded with tool_load in this conversation.
     * @param whole how many arrivals are described whole where the frame left
     *   things to be found: as many as one tool_load loads, those it loaded
     *   first. Past that a long list of schemas is the cost finding exists to
     *   avoid, and the rest are named.
     */
    fun changes(
        frame: SessionFrames.Frame,
        fresh: String?,
        held: List<ToolSpec>,
        found: Set<String>,
        whole: Int,
    ): Pair<String?, SessionFrames.Frame> {
        val carried = frame.tools.map { it.name }.toSet()
        val holding = held.associate { it.name to SessionFrames.shapeOf(it) }
        val seen = frame.seenTools
        val arrived = held.filter { it.name !in seen && it.name !in carried }
            .sortedBy { if (it.name in found) 0 else 1 }
        val described = if (!frame.hunting) arrived.map { it.name }.toSet() else arrived.take(whole).map { it.name }.toSet()
        val back = held.filter { it.name !in seen && it.name in carried }.map { it.name }
        val gone = (seen.keys - holding.keys).sorted()
        // Held before and since, and declared differently now: a plugin updated, the drawing model changed.
        val reshaped = held.filter { seen[it.name] != null && seen[it.name] != holding[it.name] }
        val parts = mutableListOf<String>()
        if (arrived.isNotEmpty()) {
            parts += buildString {
                append("These tools are yours now. They are not in your list of tools, which stays as it was for ")
                append("this conversation: call each with ").append(ToolRun.NAME)
                append(", giving its name and its arguments.")
                arrived.forEach { spec ->
                    append("\n\n")
                    if (spec.name in described) {
                        append(ToolRun.described(spec))
                    } else {
                        val said = spec.summary?.trim()?.ifEmpty { null } ?: spec.description.trim().substringBefore(". ")
                        append("- ").append(spec.name).append(": ").append(said)
                        append(" (call ").append(ToolSearchTools.FIND).append(" with its name for how it is called)")
                    }
                }
            }
        }
        if (reshaped.isNotEmpty()) {
            parts += buildString {
                append("These tools have changed since your list of tools was written. Call them as they are now - ")
                append("what follows holds over what your list says - with ").append(ToolRun.NAME)
                append(" or by name:")
                reshaped.forEach { append("\n\n").append(ToolRun.described(it)) }
            }
        }
        if (back.isNotEmpty()) {
            parts += "These are yours again, and in your list of tools: " + back.sorted().joinToString(", ") + "."
        }
        if (gone.isNotEmpty()) {
            parts += "These are no longer yours, whatever your list of tools says, and a call to one is refused: " +
                gone.joinToString(", ") + "."
        }
        val said = lines(frame.seenSystem)
        val now = lines(fresh)
        val dropped = said.filter { it !in now.toSet() }
        val added = now.filter { it !in said.toSet() }
        if (dropped.isNotEmpty() || added.isNotEmpty()) {
            parts += buildString {
                append("What you were told at the start of this conversation has changed since. Where it ")
                append("disagrees with what follows, what follows holds.")
                if (dropped.isNotEmpty()) append("\n\nNo longer so:\n").append(dropped.joinToString("\n"))
                if (added.isNotEmpty()) append("\n\nNow so:\n").append(added.joinToString("\n"))
            }
        }
        if (parts.isEmpty() && frame.seenSystem == fresh && seen == holding) return null to frame
        val updated = frame.copy(seenSystem = fresh, seenTools = holding)
        return (if (parts.isEmpty()) null else parts.joinToString("\n\n")) to updated
    }

    /**
     * What a grant accepted in the middle of a turn makes usable in it, as one
     * message, or null where it brought no tool: [arrived] are the tools the
     * agent holds now that it did not at the start of the turn and the array
     * does not carry. Called through [ToolRun] - the array does not move
     * inside a turn - and described whole, up to [whole] where the frame left
     * things to be found, the rest by name with where to read how. The next
     * turn says them again, as [changes] says any grant.
     */
    fun grantedNow(arrived: List<ToolSpec>, hunting: Boolean, whole: Int): String? {
        if (arrived.isEmpty()) return null
        val described = if (!hunting) arrived else arrived.take(whole)
        return buildString {
            append("Granted to you during this turn, and yours to call now, in this turn: ")
            append(arrived.joinToString(", ") { it.name }).append(". ")
            append("They are not in your list of tools, which stays as it was until your next turn, so call each ")
            append("with ").append(ToolRun.NAME).append(" - as ").append(ToolRun.NAME).append(" with {\"")
            append(ToolRun.TOOL).append("\": \"").append(arrived.first().name).append("\", \"")
            append(ToolRun.ARGUMENTS).append("\": {...its arguments...}}.")
            arrived.forEach { spec ->
                append("\n\n")
                if (spec in described) {
                    append(ToolRun.described(spec))
                } else {
                    val said = spec.summary?.trim()?.ifEmpty { null } ?: spec.description.trim().substringBefore(". ")
                    append("- ").append(spec.name).append(": ").append(said)
                    append(" (call ").append(ToolSearchTools.FIND).append(" with its name for how it is called)")
                }
            }
        }
    }

    /** The lines of a system turn worth comparing: not blank, and not the list of tools. */
    private fun lines(text: String?): List<String> =
        text.orEmpty().lines().map { it.trimEnd() }.filter { it.isNotBlank() && !BuiltInTools.isListEntry(it) }
}
