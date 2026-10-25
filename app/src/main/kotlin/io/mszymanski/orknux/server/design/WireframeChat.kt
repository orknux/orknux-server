package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.mcp.WireframeTools
import io.mszymanski.orknux.server.workspace.ModelNotChatException
import io.mszymanski.orknux.server.workspace.ModelNotFoundForWorkspaceException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

/**
 * The conversation down the right of the wireframe editor: somebody asks, a
 * chat model of their choosing draws. Issue #655.
 *
 * Built as the quick chat is - the editor keeps the conversation and sends it
 * up whole, the model is the one picked beside the box, and each round is
 * written into an LLM session through [LlmSessionRecorder] so what it did can
 * be read afterwards. What it is offered is the orknux_* wireframe tools and
 * nothing else, called through [OrknuxTools.run] - the same door an agent with
 * Orknux access and an MCP client use, so a drawing made here is saved, checked
 * and audited exactly as theirs are, under the name of whoever asked.
 */
@Service
class WireframeChat(
    private val models: ModelChatClient,
    private val catalogue: ModelService,
    /** Lazy: the tools save through WireframeAPI, which holds this. */
    @param:Lazy private val orknux: OrknuxTools,
    private val sessions: LlmSessionRecorder,
    private val settings: InstallationSettings,
    private val errors: io.mszymanski.orknux.server.errors.ErrorRecorder,
) {

    /**
     * What the model finally said. Its changes are already saved by then; a
     * model that could not answer is refused with its reason, after whatever
     * it drew before failing has been kept.
     */
    fun answer(wireframe: WireframeView, modelId: Long, said: List<ChatTurn>, asker: String): String {
        val model = catalogue.model(modelId) ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.workspaceId != wireframe.workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.CHAT) throw ModelNotChatException(model.name)

        val scope = OrknuxScope(workspaceId = wireframe.workspaceId, mayWrite = true)
        val offered = offered(scope)
        val conversation = mutableListOf(ChatTurn(role = "system", content = briefing(wireframe, offered)))
        conversation += said

        val session = runCatching { sessions.open(wireframe.workspaceId, SESSION_PREFIX, "${wireframe.id}-$asker") }
            .onFailure { log.warn("The wireframe chat could not open a session for {}", asker, it) }
            .getOrNull()
        session?.let { held -> said.lastOrNull { it.role == "user" }?.let { sessions.userSaid(held, asker, it.content) } }

        val rounds = settings.chatMaxRounds()
        repeat(rounds) { round ->
            // The last round is asked without tools, so it has to say what it did.
            val last = round == rounds - 1
            when (val answer = models.complete(modelId, conversation, if (last) emptyList() else offered)) {
                is ChatCompletion.Failed -> {
                    session?.let { sessions.note(it, "The model could not answer: ${answer.reason}") }
                    errors.record(
                        wireframe.workspaceId,
                        io.mszymanski.orknux.server.errors.ErrorSource.MODEL,
                        model.name,
                        answer.reason,
                        sessionId = session,
                    )
                    throw WireframeAnswerFailedException(answer.reason)
                }

                is ChatCompletion.Answered -> {
                    session?.let { sessions.agentSaid(it, SPEAKER, answer.content) }
                    return answer.content
                }

                is ChatCompletion.CalledTools -> {
                    conversation += answer.turn
                    answer.calls.forEach { call ->
                        val line = session?.let { sessions.toolCalled(it, call.name, call.arguments) }
                        // Only the wireframe tools, whatever the model asked for: they are all it was offered.
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
        throw WireframeAnswerFailedException("it asked for a tool when it was offered none")
    }

    /** The wireframe tools, read off what [OrknuxTools] declares for this scope. */
    fun offered(scope: OrknuxScope): List<ToolSpec> = orknux.specs(scope).filter { it.name in WireframeTools.DRAWING }

    /**
     * What the model is told first. Every tool it names is read off a
     * declaration it is being offered, so a renamed tool cannot leave the
     * briefing pointing at one that is not there.
     */
    fun briefing(wireframe: WireframeView, offered: List<ToolSpec>): String {
        val named = offered.map { it.name }.toSet()
        fun tool(name: String) = if (name in named) "`$name`" else error("$name is not offered")
        return buildString {
            append(
                "You draw wireframes in the Orknux Design editor: low-fidelity, greyscale screens, like a " +
                    "designer's first sketch in Figma - colours, gradients, borders and shadows only where you are asked " +
                    "for them, through the style props the tools describe. You are working on the wireframe \"${wireframe.name}\" " +
                    "(id ${wireframe.id}). The person sees it beside this conversation, and every change you save " +
                    "appears on it as soon as you answer, where they can go on editing it by hand. ",
            )
            append(
                "Read it with ${tool(WireframeTools.READ)} before changing it. Add, move, resize, re-word or remove " +
                    "elements with ${tool(WireframeTools.CHANGE)}; replace the whole drawing with " +
                    "${tool(WireframeTools.DRAW)} only when you are asked for something new. ",
            )
            append(
                "Coordinates are whole pixels from the top left. A frame is one screen: 390 by 844 for a phone, " +
                    "1440 by 900 for a desktop, 768 by 1024 for a tablet. Put a screen's elements inside its frame, " +
                    "and put frames side by side 80 pixels apart. Line things up on an 8-pixel grid, leave 16 to 24 " +
                    "pixels of margin, and use real, short words - labels a product would have, not lorem ipsum. ",
            )
            append("When you have drawn, say in one or two sentences what you did. Do not describe the coordinates. ")
            // The tools answer its url, and the model pasted it under the wireframe the person was looking at.
            append("The person already has this wireframe open beside you, so never link to it or give its address, even though the tools answer one.")
        }
    }

    private companion object {
        /** What the editor's sessions are filed under. */
        const val SESSION_PREFIX = "wireframe"

        /** The name every answer this loop writes stands under. */
        const val SPEAKER = "Wireframe chat"

        val log = LoggerFactory.getLogger(WireframeChat::class.java)
    }
}
