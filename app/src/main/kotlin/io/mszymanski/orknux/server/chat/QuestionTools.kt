package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.interaction.InteractionHandler
import io.mszymanski.orknux.server.interaction.InteractionOption
import io.mszymanski.orknux.server.interaction.InteractionOptions
import io.mszymanski.orknux.server.interaction.InteractionRequest
import io.mszymanski.orknux.server.interaction.Interactions
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * An agent putting a question to the person, with the answers it can think of.
 *
 * Built on the conversation's requests rather than beside them, which is what
 * they are for: the question is drawn as a card with a button per option - each
 * with a line saying what it means - several of them to tick where the agent
 * says so, and a box for the person's own words, because the answer the agent
 * did not think of is the one most worth having. The answer comes back through
 * the session's inbox, as every answer that arrives after the turn that asked
 * does, and wakes the conversation if it has gone quiet.
 *
 * Not in a task: a task asks with its own permission tool, which stops the task
 * until whoever started it answers.
 */
@Service
class QuestionTools(
    private val interactions: ObjectProvider<Interactions>,
    private val inbox: SessionInbox,
    private val mapper: ObjectMapper,
    private val tasks: io.mszymanski.orknux.server.task.TaskRepository,
) {

    fun offered(agent: Agent, session: Long?): Boolean =
        BuiltInTools.granted(agent, ASK) && (session == null || tasks.findFirstBySessionId(session) == null)

    fun handles(name: String): Boolean = name == ASK

    fun run(agent: Agent, arguments: String, sessionId: Long?): String {
        fun refuse(said: String) = mapper.writeValueAsString(mapOf("error" to said))
        val conversation = sessionId ?: return refuse("There is no conversation to ask in, so there is nobody to ask")
        if (!offered(agent, sessionId)) {
            return refuse("In a task, ask with ${io.mszymanski.orknux.server.task.TaskTools.PERMISSION} instead")
        }
        val args = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return refuse("The arguments were not a JSON object")
        val question = args.path(QUESTION).takeIf { it.isString }?.stringValue()?.trim()?.ifEmpty { null }
            ?: return refuse("Write the $QUESTION to ask")
        // An array, or the text of one: every parameter can reach a model typed as a string.
        val given = args.path(OPTIONS).let { node ->
            if (node.isString) runCatching { mapper.readTree(node.stringValue()) }.getOrNull() ?: node else node
        }
        val options = if (given.isArray) {
            given.values().mapNotNull { one ->
                when {
                    one.isString -> one.stringValue().trim().ifEmpty { null }?.let { it to null }
                    one.isObject -> one.path(LABEL).takeIf { it.isString }?.stringValue()?.trim()?.ifEmpty { null }
                        ?.let { it to one.path(DESCRIPTION).takeIf { d -> d.isString }?.stringValue()?.trim()?.ifEmpty { null } }
                    else -> null
                }
            }
        } else {
            emptyList()
        }
        if (options.size < FEWEST_OPTIONS || options.size > MOST_OPTIONS) {
            return refuse("Give between $FEWEST_OPTIONS and $MOST_OPTIONS $OPTIONS, each with a $LABEL and a $DESCRIPTION")
        }
        if (options.map { it.first.lowercase() }.toSet().size != options.size) return refuse("Give each option a different $LABEL")
        val multiple = args.path(MULTIPLE).let { it.isBoolean && it.asBoolean() }

        val asked = interactions.getObject().ask(
            workspaceId = agent.workspaceId,
            llmSessionId = conversation,
            kind = KIND,
            subjectId = agent.id,
            askedBy = agent.name,
            question = question,
            options = options.mapIndexed { at, (label, description) -> InteractionOption((at + 1).toString(), label, null, description) },
            multiple = multiple,
            allowsText = true,
        )
        return mapper.writeValueAsString(
            mapOf(
                "asked" to asked.id,
                "status" to "pending",
                "note" to "The person has been shown the question with " +
                    (if (multiple) "the options to tick" else "an option to choose") +
                    " and a box to answer in their own words. Their answer reaches you later; end your turn now, " +
                    "without repeating the question or the options.",
            ),
        )
    }

    /** What the agent is told, once the person has answered. */
    fun answered(request: InteractionRequest, options: List<String>, text: String?, by: String): String {
        val labels = InteractionOptions.decode(request.options).associate { it.id to it.label }
        val chosen = options.mapNotNull { labels[it] }
        val said = buildString {
            append("$by answered your question \"${request.question}\": ")
            if (chosen.isNotEmpty()) append(chosen.joinToString(", ") { "\"$it\"" })
            if (text != null) {
                if (chosen.isNotEmpty()) append(", and wrote: ") else append("in their own words: ")
                append("\"$text\"")
            }
            append(".")
        }
        inbox.post(request.llmSessionId, SessionEventKind.ANSWER, said)
        return (chosen + listOfNotNull(text?.let { "\"$it\"" })).joinToString(", ")
    }

    companion object {
        const val ASK = "ask_person"
        const val KIND = "agent.question"

        const val QUESTION = "question"
        const val OPTIONS = "options"
        const val LABEL = "label"
        const val DESCRIPTION = "description"
        const val MULTIPLE = "multiple"

        /** Two, or it is not a choice; eight, or it is not a menu anybody reads. */
        private const val FEWEST_OPTIONS = 2
        private const val MOST_OPTIONS = 8

        val SPEC = ToolSpec(
            name = ASK,
            description = "Asks the person a question in this conversation, with the answers you can think of " +
                "drawn as options to click. Each option has a short $LABEL and a $DESCRIPTION of what choosing " +
                "it means. The person can also answer in their own words. Use it when you need a decision from " +
                "them that changes what you do next - which of several ways to go, what to include - rather than " +
                "listing the choices in your answer. Set $MULTIPLE when several may be chosen at once. The " +
                "answer reaches you later: after asking, end your turn.",
            parameters = listOf(
                ToolParameterSpec(name = QUESTION, description = "The question, in one sentence ending with a question mark.", required = true),
                ToolParameterSpec(
                    name = OPTIONS,
                    description = "$FEWEST_OPTIONS to $MOST_OPTIONS answers, each a $LABEL of a few words and a " +
                        "$DESCRIPTION of one line. Do not add an Other option: the person can always write their own.",
                    required = true,
                    schema = mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                LABEL to mapOf("type" to "string"),
                                DESCRIPTION to mapOf("type" to "string"),
                            ),
                            "required" to listOf(LABEL),
                        ),
                    ),
                ),
                ToolParameterSpec(
                    name = MULTIPLE,
                    description = "Whether the person may choose more than one. False if left out.",
                    schema = mapOf("type" to "boolean"),
                ),
            ),
            summary = "ask the person a question with options to choose from",
        )
    }
}

@Component
class PersonQuestions(private val questions: QuestionTools) : InteractionHandler {
    override val kind = QuestionTools.KIND

    override fun answered(request: InteractionRequest, option: String, by: String): String =
        questions.answered(request, listOf(option), null, by)

    override fun answered(request: InteractionRequest, options: List<String>, text: String?, by: String): String =
        questions.answered(request, options, text, by)
}
