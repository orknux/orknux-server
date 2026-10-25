package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.errors.ErrorRecorder
import io.mszymanski.orknux.server.errors.ErrorSource
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.mcp.DiagramTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.workspace.ModelNotChatException
import io.mszymanski.orknux.server.workspace.ModelNotFoundForWorkspaceException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

/**
 * The conversation down the right of the diagram editor: somebody asks, a chat
 * model of their choosing changes the diagram. Built as the wireframe editor's
 * chat is - the editor keeps the conversation and sends it up whole, each round
 * is written into an LLM session, and what the model is offered is the diagram
 * tools that work on one diagram, read off what [OrknuxTools] declares.
 *
 * Its calls go to [DiagramTools] with `personAsking`: a diagram set to Read only
 * for agents is about agents working on their own, and here a person is at the
 * editor asking for the change, so it is made.
 */
@Service
class DiagramChat(
    private val models: ModelChatClient,
    private val catalogue: ModelService,
    /** Lazy: the tools save through DiagramAPI, which holds this. */
    @param:Lazy private val orknux: OrknuxTools,
    @param:Lazy private val tools: DiagramTools,
    private val sessions: LlmSessionRecorder,
    private val settings: InstallationSettings,
    private val errors: ErrorRecorder,
) {

    fun answer(diagram: DiagramView, modelId: Long, said: List<ChatTurn>, asker: String): String {
        val model = catalogue.model(modelId) ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.workspaceId != diagram.workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.CHAT) throw ModelNotChatException(model.name)

        val scope = OrknuxScope(workspaceId = diagram.workspaceId, mayWrite = true)
        val offered = offered(scope)
        val conversation = mutableListOf(ChatTurn(role = "system", content = briefing(diagram, offered)))
        conversation += said

        val session = runCatching { sessions.open(diagram.workspaceId, SESSION_PREFIX, "${diagram.id}-$asker") }
            .onFailure { log.warn("The diagram chat could not open a session for {}", asker, it) }
            .getOrNull()
        session?.let { held -> said.lastOrNull { it.role == "user" }?.let { sessions.userSaid(held, asker, it.content) } }

        val rounds = settings.chatMaxRounds()
        repeat(rounds) { round ->
            val last = round == rounds - 1
            when (val answer = models.complete(modelId, conversation, if (last) emptyList() else offered)) {
                is ChatCompletion.Failed -> {
                    session?.let { sessions.note(it, "The model could not answer: ${answer.reason}") }
                    errors.record(diagram.workspaceId, ErrorSource.MODEL, model.name, answer.reason, sessionId = session)
                    throw DiagramAnswerFailedException(answer.reason)
                }

                is ChatCompletion.Answered -> {
                    session?.let { sessions.agentSaid(it, SPEAKER, answer.content) }
                    return answer.content
                }

                is ChatCompletion.CalledTools -> {
                    conversation += answer.turn
                    answer.calls.forEach { call ->
                        val line = session?.let { sessions.toolCalled(it, call.name, call.arguments) }
                        val got = if (offered.any { it.name == call.name }) {
                            tools.run(scope, call.name, call.arguments, personAsking = true)
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
        throw DiagramAnswerFailedException("it asked for a tool when it was offered none")
    }

    /** The diagram tools that work on one diagram, read off what [OrknuxTools] declares for this scope. */
    fun offered(scope: OrknuxScope): List<ToolSpec> = orknux.specs(scope).filter { it.name in DiagramTools.EDITING }

    /** What the model is told first; every tool it names is read off a declaration it is offered. */
    fun briefing(diagram: DiagramView, offered: List<ToolSpec>): String {
        val named = offered.map { it.name }.toSet()
        fun tool(name: String) = if (name in named) "`$name`" else error("$name is not offered")
        return buildString {
            append(
                "You work on diagrams in the Orknux diagram editor: boxes for systems, people, steps or ideas, lines " +
                    "for how they connect, and notes on each box for what its label cannot say. You are working on the " +
                    "diagram \"${diagram.name}\" (id ${diagram.id}). The person sees it beside this conversation, and " +
                    "every change you save appears on it as soon as you answer, where they can go on editing it by hand. ",
            )
            append(
                "Read it with ${tool(DiagramTools.READ)} before changing it, and a node's whole notes with " +
                    "${tool(DiagramTools.NODE)}. Add, move, relabel, recolour, connect or remove things with " +
                    "${tool(DiagramTools.CHANGE)}, in one call where you can. ",
            )
            append(
                "Lay it out on a grid: nodes about 160 by 60, 80 pixels apart, flowing left to right or top to bottom, " +
                    "and a group drawn around the nodes it holds. Keep labels to a few words and put the detail in notes, " +
                    "${diagram.noteCharactersMost} characters at most per node. ",
            )
            append("When you have changed it, say in one or two sentences what you did. Do not describe the coordinates.")
        }
    }

    private companion object {
        const val SESSION_PREFIX = "diagram"
        const val SPEAKER = "Diagram chat"
        val log = LoggerFactory.getLogger(DiagramChat::class.java)
    }
}
