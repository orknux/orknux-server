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

    /** The lines of a system turn worth comparing: not blank, and not the list of tools. */
    private fun lines(text: String?): List<String> =
        text.orEmpty().lines().map { it.trimEnd() }.filter { it.isNotBlank() && !BuiltInTools.isListEntry(it) }
}
