package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.SessionEvent
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
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
 *
 * A reminder can be listed and called off by the number `timer_set` answered.
 * Without that, an agent told to stop something it had re-armed every time it
 * rang had no way to - it could only promise not to re-arm when the pending one
 * came, and the person heard it ring once more. A reminder already read is not
 * there to call off: it happened.
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
                    "if you are still working, or by being started again if you have finished. Use it for " +
                    "something to do at a time - follow up at nine, look at a report in an hour - without " +
                    "stopping now. To wait until something one of your tools can see changes - a build " +
                    "finishing, a reply arriving - set a watcher with ${io.mszymanski.orknux.server.watcher.WatcherTools.SET} " +
                    "instead: it checks for you and tells you when it happens. Up to " +
                    "${settings.agentSleepSeconds()} seconds ahead. Answers the reminder's id, which " +
                    "$CANCEL takes to call it off.",
                parameters = listOf(
                    ToolParameterSpec(SECONDS, "How many seconds from now to be reminded.", required = true),
                    ToolParameterSpec(NOTE, "What to remind yourself of, in your own words.", required = true),
                ),
            ),
            ToolSpec(
                name = LIST,
                description = "Lists the reminders you have set that have not come up yet, soonest first, each " +
                    "with its id, its note and how many seconds until it is due.",
                parameters = emptyList(),
            ),
            ToolSpec(
                name = CANCEL,
                description = "Calls off one of your reminders before it comes up, by the id $SET or $LIST gave. " +
                    "Use it when what you set the reminder for is no longer wanted - the person said stop, or " +
                    "the thing it was for is done. One that has already come up cannot be called off.",
                parameters = listOf(
                    ToolParameterSpec(ID, "The reminder's id.", true, mapOf("type" to "integer")),
                ),
            ),
        )

        override fun handles(name: String): Boolean = name in NAMES

        override fun run(call: ToolCall): String {
            val asked = runCatching { mapper.readTree(call.arguments) }.getOrNull()
            return when (call.name) {
                LIST -> list()
                CANCEL -> cancel(asked)
                else -> set(asked)
            }
        }

        private fun set(asked: JsonNode?): String {
            val seconds = asked?.path(SECONDS)?.let(::number)
            val note = asked?.path(NOTE)?.takeIf { it.isTextual }?.stringValue()?.trim().orEmpty()
            val longest = settings.agentSleepSeconds().toLong()

            if (seconds == null || seconds <= 0) return refusal("$SECONDS must be a number of seconds greater than zero.")
            if (seconds > longest) return refusal("A reminder can be at most $longest seconds ahead on this installation.")
            if (note.isEmpty()) return refusal("Say in $NOTE what the reminder is for; you will read it when it is up.")

            val due = OffsetDateTime.now().plusSeconds(seconds)
            val id = inbox.posted(session, SessionEventKind.TIMER, "$RANG$seconds$IS_UP$note", due)
            return mapper.writeValueAsString(
                linkedMapOf(
                    "set" to true, "id" to id, "dueInSeconds" to seconds,
                    "note" to "Carry on; you will be told when it is up. $CANCEL $id calls it off.",
                ),
            )
        }

        private fun list(): String {
            val now = OffsetDateTime.now()
            val waiting = inbox.waiting(session, SessionEventKind.TIMER).map { one ->
                linkedMapOf(
                    "id" to one.id,
                    "note" to noteOf(one),
                    "dueInSeconds" to Duration.between(now, one.dueAt).seconds.coerceAtLeast(0),
                    "dueAt" to one.dueAt.toString(),
                )
            }
            return mapper.writeValueAsString(linkedMapOf("timers" to waiting, "count" to waiting.size))
        }

        private fun cancel(asked: JsonNode?): String {
            val id = asked?.path(ID)?.let(::number)
                ?: return refusal("$ID must be the number $SET or $LIST answered.")
            return if (inbox.withdraw(session, id)) {
                mapper.writeValueAsString(linkedMapOf("cancelled" to true, "id" to id, "note" to "It will not come up."))
            } else {
                refusal("No reminder $id of yours is waiting; it has come up already, was called off, or is not yours. $LIST shows what is.")
            }
        }

        private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))
    }

    companion object {
        const val SET = "timer_set"
        const val LIST = "timer_list"
        const val CANCEL = "timer_cancel"
        const val SECONDS = "seconds"
        const val NOTE = "note"
        const val ID = "id"
        val NAMES = setOf(SET, LIST, CANCEL)

        /** How a reminder's body reads when it comes up; the note is what follows [IS_UP]. */
        private const val RANG = "The reminder you set "
        private const val IS_UP = " seconds ago is up: "

        /**
         * The note a reminder was set with, for the list: what follows the stock
         * opening, or the whole body for a timer something else posted - a chat
         * widget's wake says what it is on its own.
         */
        fun noteOf(event: SessionEvent): String =
            if (event.body.startsWith(RANG) && event.body.contains(IS_UP)) event.body.substringAfter(IS_UP) else event.body

        private fun number(node: JsonNode): Long? = when {
            node.isNumber -> node.asLong()
            node.isTextual -> node.stringValue().trim().toLongOrNull()
            else -> null
        }
    }
}
