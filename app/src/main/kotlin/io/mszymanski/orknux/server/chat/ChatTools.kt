package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.workflow.DrawToolParameters
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The one thing an agent answering a chat can make.
 *
 * Issue #294. A chat agent asked to draw the architecture it had just described
 * wrote about a picture, because there was no tool for it: the two doors that
 * produced a picture were a button somebody pressed and `task_draw_picture`,
 * offered only inside a task. Neither was a model deciding to. This is the
 * third, and it is the one that lets the request be made where it was actually
 * made — in the conversation, in a sentence, without leaving it to press a
 * button and retype the description.
 *
 * ### Why it is `chat_draw_picture` and not `draw_picture`
 *
 * `TaskTools` names its equivalent `task_draw_picture` for a reason it states,
 * and the reason is not tidiness: **what a drawing produces belongs to whoever
 * will look for it, and only the loop lending the tool knows where that is.** A
 * task files against the task and shows the picture with its outcome; a chat
 * files against the chat and writes the picture into the thread, because that is
 * where the person who asked will be looking. The two are the same drawing and
 * different filing, so they are two tools with two names rather than one tool
 * that guesses.
 *
 * A bare `draw_picture` would say neither, and it would say something worse
 * besides: a name with no owner in it reads as a capability the agent carries
 * about with it — something granted, like a skill or an MCP server — when this
 * is lent for one round by whatever is running it, and gone the moment the round
 * ends. [ToolShed] is the whole distinction, and the name is where it is
 * visible to the model.
 *
 * ### When it is offered
 *
 * Only where the workspace has chosen a model that draws, which is
 * [ChatPictures.offered]. Not a per-agent grant and not a setting of its own: a
 * workspace with nothing to draw with has nothing to draw with, and offering a
 * tool that can only refuse spends a turn teaching a model what the workspace
 * already said. That is the rule `AgentTools` states for every tool here.
 */
@Service
class ChatTools(
    private val mapper: ObjectMapper,
    private val pictures: ChatPictures,
    /**
     * The width, height and quality the drawing takes, as the workspace's
     * model takes them. See [DrawToolParameters] for why the list is the
     * model's and why a size arrives as two numbers.
     */
    private val parameters: DrawToolParameters,
    /** Timers and stopwatches above the composer; see [io.mszymanski.orknux.server.widget.WidgetTools]. */
    private val widgets: io.mszymanski.orknux.server.widget.WidgetTools,
) {

    /**
     * The shed for one chat's round.
     *
     * Held by the chat rather than by an id, because everything the drawing has
     * to be filed against is on the row already — which chat, whose, and in
     * which workspace — and reading it again inside the round would be a second
     * read of a row the door had in its hand.
     */
    fun shed(chat: ChatSession, watch: RoundWatch = object : RoundWatch {}): ToolShed = Shed(chat, watch)

    private inner class Shed(private val chat: ChatSession, private val watch: RoundWatch) : ToolShed {

        /**
         * The drawing as this workspace's model takes it, read every send:
         * what is offered is decided per round already, and the model the
         * workspace chose can change between two of them.
         */
        override fun specs(): List<ToolSpec> =
            (if (pictures.offered(chat)) listOf(parameters.offering(DRAWING, pictures.modelFor(chat))) else emptyList()) +
                widgets.specs()

        override fun handles(name: String): Boolean = name == DRAW || widgets.handles(name)

        /**
         * The pictures of something that may be drawn again in this turn, by
         * what they are of, the latest of each. Chat 979: an agent created a
         * wireframe and changed it in the same turn, and both pictures went
         * into the chat - the mockup twice, the first already out of date.
         *
         * Held to the end of the turn rather than filed and then replaced,
         * because the page draws each picture the moment it is told of one and
         * has no way to take one back: a replacement would be a second picture
         * on the screen until a reload. Filed at the end there is one, and it
         * still lands above the answer, live and in the thread.
         */
        private val held = linkedMapOf<String, Pair<String, AgentTools.Companion.Picture>>()

        /** Into the thread, where the person who asked is looking, and onto the screen of whoever is watching. */
        override fun pictured(tool: String, picture: AgentTools.Companion.Picture): String? {
            val key = picture.key
            if (key != null && pictures.accepts()) {
                held.remove(key)
                held[key] = tool to picture
                return "The picture goes into this conversation above your answer when you finish, where the person " +
                    "sees it - one for each thing drawn, as it is last. Do not repeat it; say what you drew and carry on."
            }
            return if (file(tool, picture)) {
                "The picture is already in this conversation, above your answer, where the person sees it. " +
                    "Do not repeat it; say what you drew and carry on."
            } else {
                null
            }
        }

        override fun settle() {
            val due = held.values.toList()
            held.clear()
            due.forEach { (tool, picture) -> file(tool, picture) }
        }

        private fun file(tool: String, picture: AgentTools.Companion.Picture): Boolean =
            when (val filed = pictures.show(chat, tool, picture)) {
                is ChatDrawing.Refused -> false
                is ChatDrawing.Drawn -> {
                    watch.drew(filed.said)
                    watch.filed(filed.attachmentId, filed.filename, filed.contentType, filed.sizeBytes)
                    true
                }
            }

        /**
         * A refusal is an answer, never an ending.
         *
         * Nothing here throws [AgentRoundHalted]. A description a provider would
         * not draw is something the agent can rewrite, and a chat killed because
         * one picture could not be drawn would throw away the answer somebody
         * was waiting for — which is the same argument `TaskTools` makes for
         * answering a failed drawing with a sentence rather than parking.
         */
        override fun run(call: ToolCall): String = when (call.name) {
            in io.mszymanski.orknux.server.widget.WidgetTools.NAMES -> widgets.run(chat, call) { now -> watch.widgets(now) }
            DRAW -> {
                val description = argument(call, "description")?.trim()
                // The size and quality, held to what the model takes before the
                // provider is asked; a refusal names what it does take, where
                // the provider's would be a 400 about a field name.
                val asked = parameters.asked(pictures.modelFor(chat), call.arguments)
                if (description.isNullOrBlank()) {
                    refuse("Say what the picture should be of: $DRAW takes a description.")
                } else {
                    when (asked) {
                        is DrawToolParameters.Asked.Refused -> refuse(asked.reason)
                        is DrawToolParameters.Asked.Options -> drawn(description, asked.options)
                    }
                }
            }

            else -> refuse("There is no tool called ${call.name}")
        }

        /**
         * The drawing itself, and what the model is told about it.
         *
         * Split out of [run] because a `when` over the call's name, the
         * description and the size is three questions, and the answer to the
         * last of them is a picture with a paragraph of its own to say.
         */
        private fun drawn(description: String, options: ImageOptions): String =
            when (val drawn = pictures.draw(chat, description, options)) {
                is ChatDrawing.Refused -> refuse(drawn.reason)
                /*
                 * The markdown goes back, and the sentence beside it says
                 * not to repeat it. The picture is already in the thread
                 * - see [ChatPictures.draw] for why it goes in there and
                 * then rather than being left to the model - so this is
                 * the model being told what happened, not being handed
                 * the only copy of it. A model that believed the picture
                 * vanished unless it pasted the link would paste it, and
                 * the chat would show the picture twice.
                 */
                is ChatDrawing.Drawn -> {
                    // The picture is in the thread already; this is what
                    // puts it on the screen of whoever is watching the
                    // round, rather than at their next reload.
                    watch.drew(drawn.said)
                    watch.filed(drawn.attachmentId, drawn.filename, drawn.contentType, drawn.sizeBytes)
                    mapper.writeValueAsString(
                        mapOf(
                            "drawn" to true,
                            "markdown" to drawn.said,
                            "note" to "The picture is already in this conversation, above your answer. " +
                                "Do not repeat the markdown; say what you drew and why, and carry on.",
                        ),
                    )
                }
            }

        private fun refuse(why: String): String = mapper.writeValueAsString(mapOf("error" to why))

        private fun argument(call: ToolCall, name: String): String? = runCatching {
            mapper.readTree(call.arguments).path(name).stringValue()
        }.getOrNull()
    }

    private companion object {
        const val DRAW = "chat_draw_picture"

        /**
         * Offered only where there is something to draw with.
         *
         * The description spends most of its words on when *not* to call it, for
         * the reason `TaskTools` gives: the failure this invites is a model that
         * illustrates an answer nobody asked to have illustrated, and one picture
         * is tens of seconds and real money. It also says what happens to the
         * picture afterwards, because a model that believes the result vanishes
         * unless it repeats the link will repeat it.
         *
         * One fixed parameter. The width, height and quality are added per
         * round by [DrawToolParameters.offering], because which of them there
         * are and what they may be is the workspace's model's to say.
         */
        val DRAWING = ToolSpec(
            name = DRAW,
            description =
                "Draws a picture from a description, using the model this workspace draws with, and puts it in " +
                    "this conversation. Call it when a picture is what was asked for - a diagram, an " +
                    "illustration, a mock-up - and not to decorate an answer that is prose: each one takes time " +
                    "and costs money. Describe what should be in the picture rather than instructing a model, " +
                    "since the description is sent to a drawing model and not to you. The picture appears in the " +
                    "conversation as soon as it is drawn, above whatever you say next, so do not paste the " +
                    "markdown into your answer - say what you drew. Width and height, and quality where " +
                    "offered, are optional: leave them out for the model's defaults.",
            parameters = listOf(
                ToolParameterSpec("description", "What the picture should be of.", required = true),
            ),
        )
    }
}
