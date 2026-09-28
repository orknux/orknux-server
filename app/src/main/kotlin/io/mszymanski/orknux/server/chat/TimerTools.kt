package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * A reminder an agent sets for itself, and carries on.
 *
 * Not a wait. `finish_answer` with a wake-up ends the turn and comes back; this
 * ends nothing - the agent goes on with what it was doing, and when the time is
 * up it is told, with the note it left. Mid-turn that is the next thing it reads
 * between two steps; after the turn it wakes the session, the way an agent it
 * asked answering does. See [SessionInbox].
 *
 * Bounded by the installation's longest wait, which is the same question asked
 * of a different tool: how far ahead an agent may commit this installation to
 * coming back. And by how many may be set at once, the number of waits, because
 * a model that sets a timer every round is a model that is looping.
 */
@Service
class TimerTools(
    private val inbox: SessionInbox,
    private val settings: InstallationSettings,
    private val mapper: ObjectMapper,
) {

    /** The shed for one turn, or null with no session - there is nowhere to deliver to. */
    fun shed(session: Long?): ToolShed? = session?.let { Shed(it) }

    private inner class Shed(private val session: Long) : ToolShed {

        override fun specs(): List<ToolSpec> = listOf(
            ToolSpec(
                name = SET,
                description = "Sets a reminder for yourself and returns at once, so you carry on with what you " +
                    "are doing. When the time is up you are told, with the note you left: between two steps " +
                    "if you are still working, or by being started again if you have finished. Use it when " +
                    "you need to check something later - whether a build finished, whether somebody replied - " +
                    "without stopping now. Up to ${settings.agentSleepSeconds()} seconds ahead.",
                parameters = listOf(
                    ToolParameterSpec(SECONDS, "How many seconds from now to be reminded.", required = true),
                    ToolParameterSpec(NOTE, "What to remind yourself of, in your own words.", required = true),
                ),
            ),
        )

        override fun handles(name: String): Boolean = name == SET

        override fun run(call: ToolCall): String {
            val asked = runCatching { mapper.readTree(call.arguments) }.getOrNull()
            val seconds = asked?.path(SECONDS)?.let { node ->
                when {
                    node.isNumber -> node.asLong()
                    node.isTextual -> node.stringValue().trim().toLongOrNull()
                    else -> null
                }
            }
            val note = asked?.path(NOTE)?.takeIf { it.isTextual }?.stringValue()?.trim().orEmpty()
            val longest = settings.agentSleepSeconds().toLong()

            if (seconds == null || seconds <= 0) return refusal("$SECONDS must be a number of seconds greater than zero.")
            if (seconds > longest) return refusal("A reminder can be at most $longest seconds ahead on this installation.")
            if (note.isEmpty()) return refusal("Say in $NOTE what the reminder is for; you will read it when it is up.")

            val due = OffsetDateTime.now().plusSeconds(seconds)
            inbox.post(session, SessionEventKind.TIMER, "The reminder you set $seconds seconds ago is up: $note", due)
            return mapper.writeValueAsString(
                linkedMapOf("set" to true, "dueInSeconds" to seconds, "note" to "Carry on; you will be told when it is up."),
            )
        }

        private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))
    }

    companion object {
        const val SET = "timer_set"
        const val SECONDS = "seconds"
        const val NOTE = "note"
    }
}
