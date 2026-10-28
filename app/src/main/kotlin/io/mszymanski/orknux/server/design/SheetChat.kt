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
import io.mszymanski.orknux.server.mcp.SheetTools
import io.mszymanski.orknux.server.workspace.ModelNotChatException
import io.mszymanski.orknux.server.workspace.ModelNotFoundForWorkspaceException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

/**
 * The conversation down the right of the sheet editor: somebody asks, a chat
 * model of their choosing fills the sheet in.
 *
 * Built as the wireframe editor's is - the editor keeps the conversation and
 * sends it up whole, the model is the one picked beside the box, and each round
 * is written into an LLM session through [LlmSessionRecorder] so what it did can
 * be read afterwards. What it is offered is the orknux_* sheet tools that work
 * in the open sheet and nothing else, called through [OrknuxTools.run] - the
 * same door an agent with Orknux access and an MCP client use, so what is
 * written here is saved, checked and audited exactly as theirs is, under the
 * name of whoever asked.
 */
@Service
class SheetChat(
    private val models: ModelChatClient,
    private val catalogue: ModelService,
    /** Lazy: the tools save through SheetAPI, which holds this. */
    @param:Lazy private val orknux: OrknuxTools,
    private val sessions: LlmSessionRecorder,
    private val settings: InstallationSettings,
    private val errors: io.mszymanski.orknux.server.errors.ErrorRecorder,
) {

    /**
     * What the model finally said. Its changes are already saved by then; a
     * model that could not answer is refused with its reason, after whatever
     * it wrote before failing has been kept.
     */
    fun answer(sheet: SheetView, modelId: Long, said: List<ChatTurn>, asker: String): String {
        val model = catalogue.model(modelId) ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.workspaceId != sheet.workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.CHAT) throw ModelNotChatException(model.name)

        val scope = OrknuxScope(workspaceId = sheet.workspaceId, mayWrite = true)
        val offered = offered(scope)
        val conversation = mutableListOf(ChatTurn(role = "system", content = briefing(sheet, offered)))
        conversation += said

        val session = runCatching { sessions.open(sheet.workspaceId, SESSION_PREFIX, "${sheet.id}-$asker") }
            .onFailure { log.warn("The sheet chat could not open a session for {}", asker, it) }
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
                        sheet.workspaceId,
                        io.mszymanski.orknux.server.errors.ErrorSource.MODEL,
                        model.name,
                        answer.reason,
                        sessionId = session,
                    )
                    throw SheetAnswerFailedException(answer.reason)
                }

                is ChatCompletion.Answered -> {
                    session?.let { sessions.agentSaid(it, SPEAKER, answer.content) }
                    return answer.content
                }

                is ChatCompletion.CalledTools -> {
                    conversation += answer.turn
                    answer.calls.forEach { call ->
                        val line = session?.let { sessions.toolCalled(it, call.name, call.arguments) }
                        // Only the sheet tools, whatever the model asked for: they are all it was offered.
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
        throw SheetAnswerFailedException("it asked for a tool when it was offered none")
    }

    /** The sheet tools, read off what [OrknuxTools] declares for this scope. */
    fun offered(scope: OrknuxScope): List<ToolSpec> = orknux.specs(scope).filter { it.name in SheetTools.EDITING }

    /**
     * What the model is told first. Every tool it names is read off a
     * declaration it is being offered, so a renamed tool cannot leave the
     * briefing pointing at one that is not there.
     */
    fun briefing(sheet: SheetView, offered: List<ToolSpec>): String {
        val named = offered.map { it.name }.toSet()
        fun tool(name: String) = if (name in named) "`$name`" else error("$name is not offered")
        return buildString {
            append(
                "You work in spreadsheets in the Orknux Design editor. You are working on the sheet \"${sheet.name}\" " +
                    "(id ${sheet.id}). The person sees it beside this conversation, and every change you save appears " +
                    "on it as soon as you answer, where they can go on editing it by hand. ",
            )
            append(
                "Read it with ${tool(SheetTools.READ)} before changing it. Write cells with ${tool(SheetTools.WRITE)} - " +
                    "a table in one call, as rows - and add, rename or remove tabs and set column widths with " +
                    "${tool(SheetTools.TABS_TOOL)}. ",
            )
            append(
                "Put a header in the first row of a table, and work things out with formulas rather than typing in " +
                    "what you calculated, so the sheet stays right when somebody changes a number. Check what each " +
                    "formula came to in the tool's answer, and fix any that came to an error. ",
            )
            append("When you have written, say in one or two sentences what you did. Do not list the cells. ")
            // The tools answer its url, and the model pasted it under the sheet the person was looking at.
            append("The person already has this sheet open beside you, so never link to it or give its address, even though the tools answer one.")
        }
    }

    private companion object {
        /** What the editor's sessions are filed under. */
        const val SESSION_PREFIX = "sheet"

        /** The name every answer this loop writes stands under. */
        const val SPEAKER = "Sheet chat"

        val log = LoggerFactory.getLogger(SheetChat::class.java)
    }
}
