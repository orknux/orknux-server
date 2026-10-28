package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.mcp.ManualTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.workspace.ModelNotChatException
import io.mszymanski.orknux.server.workspace.ModelNotFoundForWorkspaceException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The conversation down the right of the function editor: somebody asks, a
 * chat model of their choosing reads the code and offers a change.
 *
 * Built as the Design editors' chats are - the editor keeps the conversation
 * and sends it up whole, with the code as it stands in the editor, the model is
 * the one picked beside the box, and each round is written into an LLM session
 * through [LlmSessionRecorder]. It saves nothing. A change is offered through
 * [SUGGEST], which belongs to this loop rather than to [OrknuxTools]: it is the
 * model handing the editor a rewrite of the code it was shown, saved or not,
 * and the editor puts it beside the code for Accept and Reject - so a function
 * nobody has saved yet can be written with help from its first line.
 *
 * Besides that it may read: the workspace's functions, to call or copy from
 * one, and the manual, for what the sandbox's `orknux` door offers. Nothing it
 * is offered writes anything.
 */
@Service
class FunctionChat(
    private val models: ModelChatClient,
    private val catalogue: ModelService,
    /** Lazy: the surface reaches agents, which reach functions. */
    @param:Lazy private val orknux: OrknuxTools,
    private val sessions: LlmSessionRecorder,
    private val settings: InstallationSettings,
    private val mapper: ObjectMapper,
    private val errors: io.mszymanski.orknux.server.errors.ErrorRecorder,
) {

    /** What the model said, and the rewrite it offered last, if it offered one. */
    data class Answer(val answer: String, val suggestion: Suggestion?)

    data class Suggestion(val code: String, val note: String?)

    /**
     * @param name what the function is called in the editor, which may not be saved yet.
     * @param code the code as it stands in the editor, which is what a suggestion is shown against.
     */
    fun answer(workspaceId: Long, functionId: Long?, name: String, code: String, modelId: Long, said: List<ChatTurn>, asker: String): Answer {
        val model = catalogue.model(modelId) ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.CHAT) throw ModelNotChatException(model.name)

        val scope = OrknuxScope(workspaceId = workspaceId)
        val offered = offered(scope)
        val conversation = mutableListOf(ChatTurn(role = "system", content = briefing(name, functionId, code, offered)))
        conversation += said

        val session = runCatching { sessions.open(workspaceId, SESSION_PREFIX, "${functionId ?: "new"}-$asker") }
            .onFailure { log.warn("The function chat could not open a session for {}", asker, it) }
            .getOrNull()
        session?.let { held -> said.lastOrNull { it.role == "user" }?.let { sessions.userSaid(held, asker, it.content) } }

        var offering: Suggestion? = null
        val rounds = settings.chatMaxRounds()
        repeat(rounds) { round ->
            // The last round is asked without tools, so it has to say what it did.
            val last = round == rounds - 1
            when (val answer = models.complete(modelId, conversation, if (last) emptyList() else offered)) {
                is ChatCompletion.Failed -> {
                    session?.let { sessions.note(it, "The model could not answer: ${answer.reason}") }
                    errors.record(workspaceId, io.mszymanski.orknux.server.errors.ErrorSource.MODEL, model.name, answer.reason, sessionId = session)
                    throw FunctionAnswerFailedException(answer.reason)
                }

                is ChatCompletion.Answered -> {
                    session?.let { sessions.agentSaid(it, SPEAKER, answer.content) }
                    return Answer(answer.content, offering)
                }

                is ChatCompletion.CalledTools -> {
                    conversation += answer.turn
                    answer.calls.forEach { call ->
                        val line = session?.let { sessions.toolCalled(it, call.name, call.arguments) }
                        val got = when {
                            call.name == SUGGEST -> {
                                val asked = runCatching { mapper.readTree(call.arguments.ifBlank { "{}" }) }.getOrNull()
                                val rewritten = asked?.path("code")?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() }
                                if (rewritten == null) {
                                    """{"error":"Send the whole new code as code."}"""
                                } else {
                                    // The last one wins: a model that offers two rewrites in one turn has changed its mind.
                                    offering = Suggestion(rewritten, asked.path("note").takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() })
                                    """{"shown":true,"waiting":"The person sees it beside the code and will accept or reject it. You will be told which."}"""
                                }
                            }
                            // Only what it was offered, whatever the model asked for.
                            offered.any { it.name == call.name } -> orknux.run(scope, call.name, call.arguments)
                            else -> """{"error":"There is no tool called ${call.name} here"}"""
                        }
                        sessions.toolReturned(line, got)
                        conversation += ChatTurn(role = "user", content = got, respondingTo = call.id)
                    }
                }
            }
        }
        session?.let { sessions.note(it, "The model asked for a tool when it was offered none.") }
        throw FunctionAnswerFailedException("it asked for a tool when it was offered none")
    }

    /** The rewrite, and what it may read: read off what [OrknuxTools] declares, so a renamed tool cannot be offered by its old name. */
    fun offered(scope: OrknuxScope): List<ToolSpec> =
        listOf(suggest()) + orknux.specs(scope).filter { it.name in READING }

    /**
     * What the model is told first: the function as the editor holds it, and
     * how a change is offered. Every tool it names is read off a declaration it
     * is being offered.
     */
    fun briefing(name: String, functionId: Long?, code: String, offered: List<ToolSpec>): String {
        val named = offered.map { it.name }.toSet()
        fun tool(name: String) = if (name in named) "`$name`" else error("$name is not offered")
        return buildString {
            append(
                "You help somebody write a function in the Orknux function editor: TypeScript, compiled to JavaScript " +
                    "and run in a sandbox with no host access, no file system and no network of its own - what it " +
                    "reaches, it reaches through the `orknux` object it is handed. ",
            )
            append("The function is ").append(name.ifBlank { "not named yet" })
            append(if (functionId == null) ", and it has not been saved yet. " else " (id $functionId). ")
            append(
                "Its parameters are the parameters its declaration takes, annotated with their types; workspace " +
                    "variables it is handed come last. ",
            )
            append(
                "To change the code, call ${tool(SUGGEST)} with the whole new code - never a fragment. It is shown " +
                    "beside the code for the person to accept or reject; nothing is saved until they accept. Do not " +
                    "paste the code into your answer as well. ",
            )
            append(
                "Read another of the workspace's functions with ${tool("orknux_function")} and list them with " +
                    "${tool("orknux_functions")}; look up what `orknux` offers in the manual with " +
                    "${tool(ManualTools.SEARCH)} and read what it finds with ${tool(ManualTools.SECTION)}. ",
            )
            append("Answer briefly: say what you changed or found in a sentence or two. ")
            append("The person already has this function open beside you, so never link to it.\n\n")
            append("The code as it stands in the editor:\n```typescript\n").append(code.take(CODE_MOST)).append("\n```")
        }
    }

    private fun suggest() = ToolSpec(
        name = SUGGEST,
        description = "Offers a rewrite of the function's code, shown beside what is in the editor for the person to " +
            "accept or reject. It saves nothing. Send the whole function, not a fragment. Its parameter list comes " +
            "from the declaration you write, so adding, removing or renaming a parameter is done by writing it there.",
        parameters = listOf(
            ToolParameterSpec("code", "The complete new source, TypeScript, every parameter annotated with its type.", required = true),
            ToolParameterSpec("note", "One line on what this changes and why.", required = false),
        ),
        summary = "Offers a rewrite of the open function's code",
    )

    companion object {
        /** The rewrite, offered to the editor this loop answers. */
        const val SUGGEST = "suggest_code"

        /** What it may read besides. */
        val READING = setOf("orknux_functions", "orknux_function", ManualTools.SEARCH, ManualTools.SECTION)

        /** How much of the code goes into the briefing; a function longer than this is one nobody should be shown whole anyway. */
        const val CODE_MOST = 60_000

        private const val SESSION_PREFIX = "function"
        private const val SPEAKER = "Function chat"

        private val log = LoggerFactory.getLogger(FunctionChat::class.java)
    }
}

class FunctionAnswerFailedException(val reason: String) :
    RuntimeException("The model could not answer: $reason"), Refusal {
    override val arguments get() = mapOf("reason" to reason)
}
