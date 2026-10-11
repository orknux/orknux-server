package io.mszymanski.orknux.server.widget

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.chat.ChatSession
import io.mszymanski.orknux.server.chat.RoundWatch
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The widget tools a chat's agent is lent: timers and stopwatches it puts above
 * the person's composer and keeps up to date.
 *
 * Lent with the chat's own shed, so only a chat has them - a widget belongs to
 * a chat, and a workflow step has no composer to draw one over. The same list
 * every round, whatever the chat holds, so the tools a local model's server has
 * cached at the top of its prompt never move.
 */
@Service
class WidgetTools(
    private val mapper: ObjectMapper,
    private val widgets: Widgets,
    private val settings: InstallationSettings,
    private val agents: AgentRepository,
) {

    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            LIST,
            "Lists the widgets above the person's message box in this chat - timers and stopwatches - with " +
                "each one's id and how it stands now. The person can pause, reset or remove them too, so look " +
                "here before you act on one.",
            emptyList(),
        ),
        ToolSpec(
            TIMER_START,
            "Puts a countdown timer above the person's message box, running from now, for the person to see. " +
                "With wake true you are told when it reaches nought - between two steps if you are working, or " +
                "by being started again if you are not.",
            listOf(
                ToolParameterSpec("label", "What it is timing, in a few words; shown on the timer.", required = true),
                ToolParameterSpec("seconds", "How long it runs, in seconds.", true, mapOf("type" to "integer")),
                ToolParameterSpec("wake", "true to be told when it ends; false by default.", false, mapOf("type" to "boolean")),
            ),
        ),
        ToolSpec(
            STOPWATCH_START,
            "Puts a stopwatch above the person's message box, counting up from now.",
            listOf(ToolParameterSpec("label", "What it is timing, in a few words; shown on the stopwatch.", required = true)),
        ),
        ToolSpec(PAUSE_TOOL, "Pauses a running timer or stopwatch; it keeps the time it had.", listOf(ID)),
        ToolSpec(RESUME_TOOL, "Starts a paused timer or stopwatch again from where it stopped.", listOf(ID)),
        ToolSpec(RESET_TOOL, "Puts a timer back to its full length, or a stopwatch back to nought and no laps, stopped.", listOf(ID)),
        ToolSpec(LAP_TOOL, "Records a lap on a stopwatch: the time it shows now, added to its list of laps.", listOf(ID)),
        ToolSpec(REMOVE_TOOL, "Takes a widget away from above the person's message box.", listOf(ID)),
    )

    fun handles(name: String): Boolean = name in NAMES

    /** One call. Never throws: a refusal is something the model is told and can act on. */
    fun run(chat: ChatSession, call: ToolCall, changed: (List<WidgetView>) -> Unit): String {
        val args = runCatching { mapper.readTree(call.arguments) }.getOrNull() ?: mapper.createObjectNode()
        return try {
            val chatId = requireNotNull(chat.id)
            val answer: Any = when (call.name) {
                LIST -> mapOf("widgets" to widgets.list(chatId), "most" to settings.widgetsPerChat())
                TIMER_START -> {
                    val seconds = number(args, "seconds") ?: throw WidgetRefusedException("Say for how many seconds, as a number")
                    widgets.startTimer(chat, text(args, "label"), seconds, args.path("wake").asBoolean(false), who(chat))
                }
                STOPWATCH_START -> widgets.startStopwatch(chat, text(args, "label"), who(chat))
                PAUSE_TOOL -> widgets.act(chat, id(args), PAUSE)
                RESUME_TOOL -> widgets.act(chat, id(args), RESUME)
                RESET_TOOL -> widgets.act(chat, id(args), RESET)
                LAP_TOOL -> widgets.act(chat, id(args), LAP)
                REMOVE_TOOL -> mapOf("removed" to widgets.remove(chat, id(args)))
                else -> throw WidgetRefusedException("There is no tool called ${call.name}")
            }
            if (call.name != LIST) changed(widgets.list(chatId))
            mapper.writeValueAsString(answer)
        } catch (refused: WidgetRefusedException) {
            mapper.writeValueAsString(mapOf("error" to refused.message))
        } catch (missing: ChatWidgetNotFoundException) {
            mapper.writeValueAsString(mapOf("error" to "${missing.message}. $LIST lists the ones it has."))
        }
    }

    private fun who(chat: ChatSession): String = chat.agentId?.let { agents.findByIdOrNull(it)?.name } ?: "agent"

    private fun text(args: JsonNode, name: String): String = args.path(name).takeIf { it.isString }?.stringValue().orEmpty()

    private fun number(args: JsonNode, name: String): Long? = args.path(name).let { node ->
        when {
            node.isNumber -> node.asLong()
            node.isString -> node.stringValue().trim().toLongOrNull()
            else -> null
        }
    }

    private fun id(args: JsonNode): Long = number(args, "id") ?: throw WidgetRefusedException("Say which widget, by the id $LIST gives")

    companion object {
        const val LIST = "widget_list"
        const val TIMER_START = "widget_timer_start"
        const val STOPWATCH_START = "widget_stopwatch_start"
        const val PAUSE_TOOL = "widget_pause"
        const val RESUME_TOOL = "widget_resume"
        const val RESET_TOOL = "widget_reset"
        const val LAP_TOOL = "widget_lap"
        const val REMOVE_TOOL = "widget_remove"

        val NAMES = setOf(LIST, TIMER_START, STOPWATCH_START, PAUSE_TOOL, RESUME_TOOL, RESET_TOOL, LAP_TOOL, REMOVE_TOOL)

        private val ID = ToolParameterSpec("id", "The widget's id, from $LIST or the call that made it.", true, mapOf("type" to "integer"))
    }
}
