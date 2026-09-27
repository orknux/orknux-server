package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.chat.AgentRoundHalted
import io.mszymanski.orknux.server.chat.ToolShed
import io.mszymanski.orknux.server.workflow.DrawToolParameters
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The things an agent can say to the task it is working on, and the one thing it
 * can make.
 *
 * They are tools rather than a convention about what the model writes, because a
 * tool call is the only channel a model has for saying something structured. "I
 * have finished" written in prose is a sentence somebody has to parse and will
 * eventually parse wrongly; `task_done` is a fact.
 *
 * Two of them park the task, and they park it the same way on purpose: a
 * question about how to deliver something and a request for permission are one
 * mechanism seen from two angles. Stop, write down what is being asked, tell
 * whoever should hear about it, and come back with exactly what they said.
 *
 * **`task_draw_picture` is the odd one, and it is here rather than on the agent
 * for one reason: what it produces belongs to this task.** Everything else in
 * this application that draws is a door somebody pressed - the chat's button
 * files its picture on the chat, because that is where a person will look for
 * it. A task has no chat and nobody watching, so the picture is filed against
 * the task and shown with its outcome, and knowing which task that is is
 * something only the loop lending the shed can say. It is offered only to a task
 * that could actually use it: see [TaskPictures.offered].
 */
@Service
class TaskTools(
    private val mapper: ObjectMapper,
    private val pictures: TaskPictures,
    /**
     * Where a drawn picture's bytes go so that something else can send them:
     * the session's own store, which every tool that uploads bytes reads from.
     */
    private val scratch: io.mszymanski.orknux.workflow.script.SessionScratch,
    /**
     * The width, height and quality the drawing takes, as the workspace's
     * model takes them. See [DrawToolParameters] for why the list is the
     * model's and why a size arrives as two numbers.
     */
    private val parameters: DrawToolParameters,
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * The shed for one task's round.
     *
     * It writes nothing about the task. What a park has to record is written by
     * the loop that catches [TaskParked], because the loop is what owns the
     * task's row and its transaction — a tool that wrote its own would be
     * writing from inside a model call that holds none. A drawn picture is the
     * exception and [TaskPictures.draw] says why: it is bytes that have already
     * been paid for, and deferring them to the end of a round that may still
     * throw is losing them.
     */
    fun shed(
        task: Task,
        /**
         * Whether this task's agent may ask for a picture's address as well as
         * draw one. On for every agent until somebody turns it off; see
         * [io.mszymanski.orknux.server.agent.Agent.pictureLinkAccess].
         */
        mayLink: Boolean = true,
    ): ToolShed = Shed(task, mayLink)

    private inner class Shed(private val task: Task, private val mayLink: Boolean) : ToolShed {

        /**
         * What this task is offered, which is not always all of them.
         *
         * `AgentTools` states the rule and it holds here: a model is only ever
         * offered tools that will run. An installation with attachments off or
         * a workspace that has chosen no image model has nothing to draw with,
         * and offering the tool anyway spends a turn teaching the model that.
         */
        override fun specs(): List<ToolSpec> =
            when {
                !pictures.offered(task) -> SPECS
                // The drawing as this workspace's model takes it, read every
                // turn: the workspace can change its model between two of them.
                mayLink -> SPECS + drawing() + LINKING
                else -> SPECS + drawing()
            }

        private fun drawing(): ToolSpec = parameters.offering(DRAWING, pictures.modelFor(task))

        override fun handles(name: String): Boolean =
            name in NAMES && (mayLink || name != LINK)

        override fun run(call: ToolCall): String = when (call.name) {
            /*
             * An address for a picture, asked for when there is a use for one.
             *
             * The drawing answers with a key and never a link: a key is what a
             * tool that uploads a file takes, and a link handed over unasked is
             * a link pasted into a chat that cannot resolve it. What that left
             * out is the agent that wants the picture *inside* what it writes -
             * a report with the diagram at the point it is being discussed -
             * which has to name it somehow. This is that door, and being a
             * door is the whole of the difference: it is asked for.
             */
            LINK -> linkFor(argument(call, "key"))

            DRAW -> {
                val description = argument(call, "description")?.trim()
                // The size and quality, held to what the model takes before the
                // provider is asked; a refusal names what it does take, where
                // the provider's would be a 400 about a field name.
                val asked = parameters.asked(pictures.modelFor(task), call.arguments)
                if (description.isNullOrBlank()) {
                    refuse("Say what the picture should be of: task_draw_picture takes a description.")
                } else {
                    when (asked) {
                        is DrawToolParameters.Asked.Refused -> refuse(asked.reason)
                        is DrawToolParameters.Asked.Options -> drawn(description, asked.options)
                    }
                }
            }

            DONE -> throw TaskFinished(argument(call, "summary").orEmpty().ifBlank { "The work is finished." })

            ASK -> {
                val question = argument(call, "question")
                if (question.isNullOrBlank()) {
                    refuse("Say what you want to know: task_ask takes a question.")
                } else {
                    park(TaskRequestKind.QUESTION, null, null, question)
                }
            }

            PERMISSION -> {
                val asked = argument(call, "capability")
                val capability = TaskCapability.entries.firstOrNull { it.name.equals(asked?.trim(), true) }
                val name = argument(call, "name")?.trim()
                val why = argument(call, "why")?.trim()
                when {
                    capability == null -> refuse(
                        "There is nothing called $asked to ask for. It is one of: " +
                            TaskCapability.entries.joinToString { it.name.lowercase() } + ".",
                    )

                    capability.named && name.isNullOrBlank() -> refuse(
                        "Asking for ${capability.name.lowercase()} means naming one; pass its name.",
                    )

                    why.isNullOrBlank() -> refuse(
                        "Say why you need it. Somebody has to read that before deciding.",
                    )

                    else -> park(TaskRequestKind.PERMISSION, capability, name.takeIf { capability.named }, why)
                }
            }

            else -> refuse("There is no tool called ${call.name}")
        }

        /**
         * The drawing itself, and what the model is told about it.
         *
         * The markdown goes back to the model, and the sentence beside it says
         * it does not have to be used. The picture is already filed and will be
         * shown under the outcome whatever the model does next - see
         * [TaskPictures.outcomeOf] - so this is an offer of where to *place* it
         * rather than the only way it will be seen. Handing over a link and
         * depending on the model to repeat it would be a picture lost every
         * time one forgot.
         */
        private fun drawn(description: String, options: ImageOptions): String =
            when (val drawn = pictures.draw(task, description, options)) {
                is Drawing.Refused -> refuse(drawn.reason)
                is Drawing.Drawn -> {
                    /*
                     * A key first, because a key is the thing that can be
                     * *delivered*.
                     *
                     * The bytes go into this task's session store, which is
                     * what every tool that uploads a file reads from - a
                     * content key rather than the bytes themselves. Which tools
                     * those are depends on the plugins an installation loaded,
                     * so this does not name one. Handed only markdown, a model
                     * that wanted to show somebody a picture pasted a link, and
                     * a chat client with no document to resolve it against
                     * printed the construction instead.
                     */
                    val key = keyFor(drawn)
                    mapper.writeValueAsString(
                        buildMap {
                            put("drawn", true)
                            if (key != null) put("key", key)
                            /*
                             * The markdown a task's outcome carries is composed
                             * by `TaskPictures.outcomeOf` from the row, so the
                             * model never needed it - and handed a link with no
                             * other way to deliver anything, it pasted one into
                             * a chat that printed the construction.
                             *
                             * The note has to say what `key` is *not*, as well
                             * as what it is. Told to place a picture and given
                             * one string, a model wrote `![a tower](picture.18)`
                             * - the store key as a URL - and the outcome drew
                             * "This picture is gone" four times.
                             */
                            put("note", noteFor(key))
                        },
                    )
                }
            }

        /**
         * The bytes, where something else can reach them by name, or null.
         *
         * Null for a task with no session and null where the store refused
         * them - said rather than hidden, because an answer with no key is a
         * picture the model cannot hand over, and it should not promise one.
         */
        private fun keyFor(drawn: Drawing.Drawn): String? {
            val session = task.sessionId ?: return null
            val key = "picture." + requireNotNull(drawn.picture.id)

            // A JSON-encoded *string*: the sandbox parses what it reads, and the
            // upload doors require what comes out to be a string of base64.
            val refused = scratch.put(session, key, mapper.writeValueAsString(drawn.base64))
            if (refused != null) {
                log.info("A task's picture was not put in session {}'s store: {}", session, refused)
                return null
            }
            return key
        }

        /** What to say about a picture that can be handed over, and one that cannot. */
        private fun noteFor(key: String?): String = if (key != null) {
            "The picture is drawn and its bytes are in this task's session store under `key`. Two " +
                "things to do with that key: pass it to whichever of your " +
                "tools sends or uploads a file, to put the picture in front of somebody; or pass it " +
                "to task_picture_link, which answers with markdown for placing the picture at a " +
                "point in what you write. The key is not an address. The picture is also shown with " +
                "this task's outcome, so it does not have to be placed to be seen." +
                // Where the rest is written down, found by the word this answer carries. Issue #558.
                " For anything else - an HTML page, a PDF, an archive - call skill_search with contentKey: " +
                "the skills say how a key is used in each."
        } else {
            "The picture is drawn and shown with this task's outcome. Its bytes could not be left " +
                "anywhere this session can reach, so nothing here can upload it - say where it is " +
                "rather than promising to send it."
        }

        /**
         * Markdown for one of this task's pictures, or a refusal.
         *
         * The key is `picture.<id>` - the store key the drawing answered with -
         * so the id is read back out of it; a bare number is the same id and is
         * taken too, because refusing it would be a round trip spent on
         * punctuation. Only this task's own pictures: a key is a row id, and a
         * model that guessed a number is told there is nothing under it rather
         * than handed somebody else's work.
         */
        private fun linkFor(key: String?): String {
            val id = key?.trim()?.removePrefix("picture.")?.toLongOrNull()
            val picture = id?.let { wanted -> pictures.of(requireNotNull(task.id)).firstOrNull { it.id == wanted } }
                ?: return refuse(
                    "No picture of this task has that key. Use the key $DRAW answered with, exactly as it " +
                        "was given.",
                )

            return mapper.writeValueAsString(
                mapOf(
                    "markdown" to pictures.linkTo(picture),
                    "note" to "Put the markdown where the picture belongs in what you are writing. Every " +
                        "picture is shown with this task's outcome anyway, so one you do not place is " +
                        "still seen - and a summary that places one is not given a second copy underneath.",
                ),
            )
        }

        private fun park(
            kind: TaskRequestKind,
            capability: TaskCapability?,
            subject: String?,
            asks: String,
        ): String = throw TaskParked(kind, capability, subject, asks)

        private fun refuse(why: String): String = mapper.writeValueAsString(mapOf("error" to why))

        private fun argument(call: ToolCall, name: String): String? = runCatching {
            mapper.readTree(call.arguments).path(name).stringValue()
        }.getOrNull()
    }

    private companion object {
        const val DONE = "task_done"
        const val ASK = "task_ask"
        const val PERMISSION = "task_request_permission"
        const val DRAW = "task_draw_picture"

        /*
         * Every name the shed answers to, including one it does not always
         * offer. A model that was offered the drawing tool on an earlier turn -
         * before somebody turned attachments off, say - and calls it now must
         * be told why it will not run, and a name the shed disowns falls through
         * to the agent's own tools and comes back as "there is no tool called
         * task_draw_picture", which is not what happened.
         */
        const val LINK = "task_picture_link"

        val NAMES = setOf(DONE, ASK, PERMISSION, DRAW, LINK)

        /**
         * What an agent calls when it wants the picture *in* what it writes.
         *
         * Separate from the drawing on purpose. The drawing answers with a key
         * because a key is the thing that can be delivered; a link is only
         * useful for placing one, so it is asked for by whoever has somewhere
         * to place it.
         */
        val LINKING = ToolSpec(
            name = LINK,
            description = "Answers with markdown for a picture you have drawn, so you can put it at a " +
                "particular point in what you write. Pass the key task_draw_picture gave you. Ask for it " +
                "only when the picture belongs at a place in your text: every picture is shown with the " +
                "task's outcome anyway, and to send one to somebody you pass the key to a tool that " +
                "uploads a file rather than writing a link.",
            parameters = listOf(
                ToolParameterSpec(
                    name = "key",
                    description = "The key task_draw_picture answered with, such as picture.22.",
                    required = true,
                ),
            ),
        )

        val SPECS = listOf(
            ToolSpec(
                name = DONE,
                description =
                    "Call this when the task is finished and there is nothing further to do. Say in the summary " +
                        "what you did and where the result is - that summary is what whoever asked for this reads, " +
                        "and it is the last thing you will say. Do not call it to report progress: anything you " +
                        "write without calling a tool is recorded as progress and you will be asked to carry on.",
                parameters = listOf(
                    ToolParameterSpec("summary", "What you did, and where the result is.", required = true),
                ),
            ),
            ToolSpec(
                name = ASK,
                description =
                    "Stops and asks whoever is responsible for this task a question, then waits for the answer. " +
                        "Use it when you cannot sensibly go on without knowing something - most often when the " +
                        "prompt does not say how the thing you are producing should be delivered. It may be hours " +
                        "before you are answered, and you will be told what they said. Ask one clear question; " +
                        "guessing is worse than waiting, and asking about something the prompt already answers is " +
                        "worse than either.",
                parameters = listOf(
                    ToolParameterSpec("question", "What you need to know, in one question.", required = true),
                ),
            ),
            ToolSpec(
                name = PERMISSION,
                description =
                    "Stops and asks for something you have not been given, then waits for a decision. " +
                        "`capability` is one of: orknux (asking this application about itself and starting " +
                        "workflows), shells (opening a session on one of this installation's machines and running " +
                        "commands), tool (one of this workspace's own tools), mcp_server, skill_catalog, " +
                        "memory_catalog. The last four name one thing, which goes in `name`. Say in `why` what you " +
                        "need it for: a person reads that and decides, and they are deciding for this task only.",
                parameters = listOf(
                    ToolParameterSpec(
                        "capability",
                        "orknux, shells, tool, mcp_server, skill_catalog or memory_catalog.",
                        required = true,
                    ),
                    ToolParameterSpec("name", "Which one, for the four that name something.", required = false),
                    ToolParameterSpec("why", "What you need it for.", required = true),
                ),
            ),
        )

        /**
         * The fourth, offered only where there is something to draw with.
         *
         * The description spends most of its words on when *not* to call it,
         * because the failure this feature invites is a model that illustrates
         * a report nobody asked to have illustrated - one picture is tens of
         * seconds and real money, and an agent working alone has nobody to stop
         * it. It also says what happens to the picture afterwards: a model that
         * believes the result vanishes unless it repeats the link will repeat
         * it, and the outcome will show the picture twice.
         *
         * One fixed parameter. The width, height and quality are added per
         * turn by [DrawToolParameters.offering], because which of them there
         * are and what they may be is the workspace's model's to say.
         */
        val DRAWING = ToolSpec(
            name = DRAW,
            description =
                "Draws a picture from a description, using the model this workspace draws with, and keeps it " +
                    "with the task. Call it when a picture is what was asked for - an illustration, a diagram, " +
                    "a mock-up, an image to go with something you are producing - and not to decorate an answer " +
                    "that is prose: each one takes time and costs money. Describe what should be in the picture " +
                    "rather than instructing a model, since the description is sent to a drawing model and not " +
                    "to you. Everything you draw is shown with the task's outcome whether or not you mention " +
                    "it, so you never have to place it. What comes back is `key`: pass it to a tool that " +
                    "sends or uploads a file to put the picture in front of somebody, or to " +
                    "task_picture_link to get markdown for putting the picture at a particular point in " +
                    "what you write. The key is not an address and there is nothing to guess: those two " +
                    "tools are what it is for. Width and height, and quality where offered, are optional: " +
                    "leave them out for the model's defaults.",
            parameters = listOf(
                ToolParameterSpec("description", "What the picture should be of.", required = true),
            ),
        )
    }
}

/**
 * The agent said it had finished.
 *
 * @param summary what it wants whoever asked to read, which becomes the task's
 *   outcome.
 */
class TaskFinished(val summary: String) : AgentRoundHalted(summary)

/**
 * The agent stopped to ask for something.
 *
 * Carries the whole of what was asked, because the loop that catches it is what
 * writes it down: the round it came out of holds no transaction, and a request
 * written from inside a model call would be one written before anybody knew
 * whether the round survived.
 */
class TaskParked(
    val kind: TaskRequestKind,
    val capability: TaskCapability?,
    val subject: String?,
    val asks: String,
) : AgentRoundHalted(
    when (kind) {
        TaskRequestKind.QUESTION -> "Waiting for an answer: $asks"
        TaskRequestKind.PERMISSION -> "Waiting for permission: ${capability?.name?.lowercase()}" +
            (subject?.let { " $it" } ?: "") + " - $asks"
    },
)
