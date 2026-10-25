package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.mcp.DocumentTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.workspace.ModelNotChatException
import io.mszymanski.orknux.server.workspace.ModelNotFoundForWorkspaceException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

/**
 * The conversation down the right of the document editor: somebody asks, a
 * chat model of their choosing writes.
 *
 * Built as the wireframe editor's chat is ([WireframeChat]): the editor keeps
 * the conversation and sends it up whole, each round is written into an LLM
 * session, and what the model is offered is the orknux_* document tools for
 * writing - called through [OrknuxTools.run], the door an agent with Orknux
 * access and an MCP client use, so what it writes is saved, checked and
 * audited as theirs is, under the name of whoever asked. The tools run in an
 * LLM session, so a picture a model is asked to put in can be one it holds.
 */
@Service
class DocumentChat(
    private val models: ModelChatClient,
    private val catalogue: ModelService,
    /** Lazy: the tools save through DocumentAPI, which holds this. */
    @param:Lazy private val orknux: OrknuxTools,
    private val sessions: LlmSessionRecorder,
    private val settings: InstallationSettings,
    private val errors: io.mszymanski.orknux.server.errors.ErrorRecorder,
) {

    /**
     * What the model finally said. Its changes are already saved by then; a
     * model that could not answer is refused with its reason, after whatever it
     * wrote before failing has been kept.
     */
    fun answer(document: DesignDocumentView, modelId: Long, said: List<ChatTurn>, asker: String): String {
        val model = catalogue.model(modelId) ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.workspaceId != document.workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.CHAT) throw ModelNotChatException(model.name)

        val session = runCatching { sessions.open(document.workspaceId, SESSION_PREFIX, "${document.id}-$asker") }
            .onFailure { log.warn("The document chat could not open a session for {}", asker, it) }
            .getOrNull()
        val scope = OrknuxScope(workspaceId = document.workspaceId, mayWrite = true, session = session)
        val offered = offered(scope)
        val conversation = mutableListOf(ChatTurn(role = "system", content = briefing(document, offered)))
        conversation += said
        session?.let { held -> said.lastOrNull { it.role == "user" }?.let { sessions.userSaid(held, asker, it.content) } }

        val rounds = settings.chatMaxRounds()
        repeat(rounds) { round ->
            // The last round is asked without tools, so it has to say what it did.
            val last = round == rounds - 1
            when (val answer = models.complete(modelId, conversation, if (last) emptyList() else offered)) {
                is ChatCompletion.Failed -> {
                    session?.let { sessions.note(it, "The model could not answer: ${answer.reason}") }
                    errors.record(document.workspaceId, io.mszymanski.orknux.server.errors.ErrorSource.MODEL, model.name, answer.reason, sessionId = session)
                    throw DocumentAnswerFailedException(answer.reason)
                }

                is ChatCompletion.Answered -> {
                    session?.let { sessions.agentSaid(it, SPEAKER, answer.content) }
                    return answer.content
                }

                is ChatCompletion.CalledTools -> {
                    conversation += answer.turn
                    answer.calls.forEach { call ->
                        val line = session?.let { sessions.toolCalled(it, call.name, call.arguments) }
                        // Only the document tools, whatever the model asked for: they are all it was offered.
                        val got = if (offered.any { it.name == call.name }) {
                            orknux.run(scope, call.name, call.arguments)
                        } else {
                            """{"error":"There is no tool called ${call.name} here"}"""
                        }
                        sessions.toolReturned(line, got)
                        conversation += ChatTurn(role = "user", content = got, respondingTo = call.id)
                    }
                }
            }
        }
        session?.let { sessions.note(it, "The model asked for a tool when it was offered none.") }
        throw DocumentAnswerFailedException("it asked for a tool when it was offered none")
    }

    /** The document tools for writing, read off what [OrknuxTools] declares for this scope. */
    fun offered(scope: OrknuxScope): List<ToolSpec> = orknux.specs(scope).filter { it.name in DocumentTools.EDITING }

    /**
     * What the model is told first. Every tool it names is read off a
     * declaration it is being offered, so a renamed tool cannot leave the
     * briefing pointing at one that is not there.
     */
    fun briefing(document: DesignDocumentView, offered: List<ToolSpec>): String {
        val named = offered.map { it.name }.toSet()
        fun tool(name: String) = if (name in named) "`$name`" else error("$name is not offered")
        return buildString {
            append(
                "You write documents in the Orknux Design editor, which shows them as ${document.pageSize} pages like a " +
                    "word processor. You are working on the document \"${document.name}\" (id ${document.id}). The person " +
                    "sees it beside this conversation, and every change you save appears in it as soon as you save it, " +
                    "where they can go on editing it by hand and undo what you did. ",
            )
            append(
                "Read it with ${tool(DocumentTools.READ)} before changing it: it answers the document as Markdown and an " +
                    "outline numbering its top-level blocks; find words in it with ${tool(DocumentTools.FIND)}. Put new text in with ${tool(DocumentTools.INSERT)} or " +
                    "${tool(DocumentTools.APPEND)}, rewrite blocks with ${tool(DocumentTools.WRITE)} - the whole document " +
                    "only when you are asked for something new - and put pictures in with ${tool(DocumentTools.ADD_IMAGE)}. ",
            )
            append(
                "Write well-structured prose with headings, lists and tables where they help, in the language the " +
                    "person writes in. Start a new page with a line holding only <!-- pagebreak --> where a section " +
                    "should begin on one. ",
            )
            append("When you have written, say in one or two sentences what you did. Do not repeat the text.")
        }
    }

    private companion object {
        /** What the editor's sessions are filed under. */
        const val SESSION_PREFIX = "document"

        /** The name every answer this loop writes stands under. */
        const val SPEAKER = "Document chat"

        val log = LoggerFactory.getLogger(DocumentChat::class.java)
    }
}
