package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * An agent writing something down for itself, part-way through. Issue #371.
 *
 * ### What it is for
 *
 * An agent that ends its turn with a wake-up is handed the note it left, which
 * covers the moment it parks. What it had no way to do is write something down
 * while it is still working: what the first six steps of a long job found, the
 * thing it must not forget to do at the end, the reason it ruled an approach
 * out.
 *
 * ### Why the transcript is not that
 *
 * Because the transcript is trimmed. What comes back into a turn is a share of
 * the model's context window - `memoryShare` decides how much - so what an agent
 * said twenty turns ago is exactly what is gone by the time it matters, and what
 * survives is whatever happened to be recent. A note is the thing that must not
 * fall out, so it is kept apart and handed back whole.
 *
 * ### Why it is the session rather than the step
 *
 * That is the span an agent thinks across. A workflow node keyed to a session
 * shares it with every other node computing the same key, and a chat is one
 * conversation over days. A note on the step would be lost to the next node in
 * the same job, which is most of what somebody would write one for.
 *
 * Which is also why it is not offered where there is no session: a tool that
 * takes a note and has nowhere to put it is worse than no tool, because the
 * agent goes on believing it was kept. The same rule the rest of this package
 * follows - nothing is offered that can only be refused.
 *
 * ### Not the workspace's memory
 *
 * [io.mszymanski.orknux.server.memory.MemoryTool] is what the workspace knows,
 * written for everybody and read by every agent granted the catalogue. This is
 * one agent's working memory in one conversation: nobody else reads it, it is
 * not curated, and it goes when the conversation does. An agent that writes
 * "the customer is on the enterprise plan" wants memory; one that writes "steps
 * 1-6 done, the failing one is the third" wants this.
 */
@Service
class NoteTools(
    private val sessions: LlmSessionRecorder,
    private val mapper: ObjectMapper,
    private val settings: InstallationSettings,
) {

    /**
     * The shed for one turn, or null where there is nowhere to keep a note.
     *
     * @param session the conversation this turn is recorded in. Null for a node
     *   that keeps none, and then there is no tool.
     * @param writtenBy which agent is writing, since a session can be shared.
     */
    fun shed(session: Long?, writtenBy: String): ToolShed? =
        if (session == null) null else Shed(session, writtenBy)

    /**
     * What has been written down here, as it is put back into a turn.
     *
     * Empty where nothing has been, which is most turns - and an empty string
     * rather than a heading with nothing under it, so a prompt does not carry a
     * section that says only that there is no section.
     */
    fun recalled(session: Long?): String {
        val held = sessions.notesOf(session)
        if (held.isEmpty()) return ""

        return buildString {
            appendLine("You have written these down for yourself in this conversation, oldest first.")
            appendLine("They are yours and nobody else reads them; write another with $NOTE.")
            held.forEach { appendLine("\n- ${it.note}") }
        }
    }

    private inner class Shed(private val session: Long, private val writtenBy: String) : ToolShed {

        /** Read once per turn, so the limit the model is told is the one it is held to. */
        private val longest = settings.noteMaxCharacters()
        private val most = settings.noteMaxCount()

        override fun specs(): List<ToolSpec> = listOf(
            ToolSpec(
                name = NOTE,
                description = "Writes something down for yourself, kept for the whole of this " +
                    "conversation and put back to you at the start of every later turn. Use it for " +
                    "what you would otherwise have to remember across a long job: what you have " +
                    "already done, what you found, what you must not forget to do at the end. What " +
                    "you say in an answer is not kept this way - the conversation is trimmed to fit, " +
                    "and a note is not. Nobody else reads these.",
                parameters = listOf(
                    ToolParameterSpec(
                        name = NOTE_TEXT,
                        description = "The note, at most $longest characters. Write what you would " +
                            "need to read to pick this up again, not a summary of the conversation.",
                        required = true,
                    ),
                ),
            ),
        )

        override fun handles(name: String): Boolean = name == NOTE

        override fun run(call: ToolCall): String {
            val said = argument(call).orEmpty().trim()
            if (said.isEmpty()) return refusal("There is nothing in that note: say what to write down.")
            if (said.length > longest) {
                return refusal(
                    "That note is ${said.length} characters, and one is kept up to $longest; say it in " +
                        "fewer. " +
                        "A note is read back on every turn, so a long one is paid for on every turn.",
                )
            }

            /*
             * Bounded, and refused rather than dropped. Every note is handed back
             * whole on every later turn, so an agent writing one per round would
             * quietly turn its own context window into a diary. Told it is full,
             * it can decide what matters; one whose notes vanished would go on
             * believing they were kept.
             */
            if (sessions.noteCount(session) >= most) {
                return refusal(
                    "You have already written $most notes in this conversation, which is as many as " +
                        "are kept. Work with the ones you have.",
                )
            }

            val held = sessions.noteTaken(session, writtenBy, said)
            return mapper.writeValueAsString(mapOf("written" to true, "notes" to held))
        }

        private fun argument(call: ToolCall): String? = runCatching {
            mapper.readTree(call.arguments).path(NOTE_TEXT).takeIf { it.isTextual }?.stringValue()
        }.getOrNull()

        private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))
    }

    companion object {

        const val NOTE = "note_to_self"
        const val NOTE_TEXT = "note"
    }
}
