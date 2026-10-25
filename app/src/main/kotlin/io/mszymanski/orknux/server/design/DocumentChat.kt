package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelView
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
    fun answer(document: DesignDocumentView, modelId: Long, said: List<ChatTurn>, asker: String, readOnly: Boolean = false): String {
        val model = chatModel(document, modelId)

        val session = session(document, asker)
        // Read-only twice over: a scope that may not write, and only the reading tools out of what it declares.
        // One turn: what it saves keeps one version of the document between them, the state before it.
        val turn = java.util.UUID.randomUUID().toString()
        val scope = OrknuxScope(workspaceId = document.workspaceId, mayWrite = !readOnly, session = session, documentTurn = turn)
        val offered = offered(scope, readOnly)
        val conversation = mutableListOf(ChatTurn(role = "system", content = if (readOnly) reading(document, offered) else briefing(document, offered)))
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

    /** The document tools for writing - or for reading alone - read off what [OrknuxTools] declares for this scope. */
    fun offered(scope: OrknuxScope, readOnly: Boolean = false): List<ToolSpec> =
        orknux.specs(scope).filter { it.name in (if (readOnly) DocumentTools.READING else DocumentTools.EDITING) }

    /**
     * A selection put into another language: one call, offered no tools, answered with the translation
     * alone. The words around it are sent so that a word with two meanings is translated as the
     * document means it, and the model is told they are not to be translated.
     */
    fun translate(document: DesignDocumentView, modelId: Long, text: String, before: String, after: String, language: String, asker: String): String {
        val model = chatModel(document, modelId)
        val into = TranslationLanguages.NAMES[language] ?: throw DocumentTranslationLanguageException(language)
        val session = session(document, asker)
        val asked = buildString {
            if (before.isNotBlank()) append("The text just before it, for context only:\n<before>\n").append(before).append("\n</before>\n\n")
            if (after.isNotBlank()) append("The text just after it, for context only:\n<after>\n").append(after).append("\n</after>\n\n")
            append("Translate into $into:\n<text>\n").append(text).append("\n</text>")
        }
        val turns = listOf(
            ChatTurn(
                role = "system",
                content = "You translate a passage of the document \"${document.name}\" into $into. Answer with the translation " +
                    "of the text between <text> and </text> and nothing else: no quotes, no tags, no notes, no explanation. " +
                    "Keep its meaning, tone, punctuation and line breaks; leave names, code and numbers as they are. The text " +
                    "around it is there so you understand what it means and is not translated.",
            ),
            ChatTurn(role = "user", content = asked),
        )
        session?.let { sessions.userSaid(it, asker, "Translate into $into: $text") }
        when (val answer = models.complete(modelId, turns)) {
            is ChatCompletion.Failed -> {
                session?.let { sessions.note(it, "The model could not answer: ${answer.reason}") }
                errors.record(document.workspaceId, io.mszymanski.orknux.server.errors.ErrorSource.MODEL, model.name, answer.reason, sessionId = session)
                throw DocumentAnswerFailedException(answer.reason)
            }
            is ChatCompletion.Answered -> {
                session?.let { sessions.agentSaid(it, SPEAKER, answer.content) }
                val translated = unwrapped(answer.content, text)
                if (translated.isBlank()) throw DocumentAnswerFailedException("it answered with no translation")
                return translated
            }
            is ChatCompletion.CalledTools -> throw DocumentAnswerFailedException("it asked for a tool when it was offered none")
        }
    }

    /**
     * What a model said, less the wrapping it was told not to add and adds anyway: the tags it was
     * shown, a fence, or quotes round the whole - each taken off only where the original had none.
     */
    fun unwrapped(said: String, original: String): String {
        var text = said.trim()
        text = text.removePrefix("<text>").removeSuffix("</text>").trim()
        if (text.startsWith("```") && text.endsWith("```") && !original.trim().startsWith("```")) {
            text = text.removePrefix("```").removeSuffix("```").substringAfter('\n', text.removePrefix("```").removeSuffix("```")).trim()
        }
        val quotes = listOf("\"" to "\"", "\u201c" to "\u201d", "\u201e" to "\u201d", "\u00ab" to "\u00bb", "'" to "'")
        quotes.firstOrNull { (open, close) -> text.length > 1 && text.startsWith(open) && text.endsWith(close) && !original.trim().startsWith(open) }
            ?.let { (open, close) -> text = text.removePrefix(open).removeSuffix(close).trim() }
        return text
    }

    /** What the model is told when it is asked about the document and may not change it. */
    fun reading(document: DesignDocumentView, offered: List<ToolSpec>): String {
        val named = offered.map { it.name }.toSet()
        fun tool(name: String) = if (name in named) "`$name`" else error("$name is not offered")
        return buildString {
            append(
                "You answer questions about the document \"${document.name}\" (id ${document.id}) in the Orknux Design editor, " +
                    "which the person has open beside this conversation. This time you only read it: nothing you say is " +
                    "written into the document, and you have no tool that could change it. ",
            )
            append(
                "Read it with ${tool(DocumentTools.READ)} and find words in it with ${tool(DocumentTools.FIND)} when you need " +
                    "more than you were given - to explain a passage as it is used where it stands, or to summarise all of it. ",
            )
            append("Answer in the language the person writes in, plainly and briefly, as Markdown.")
        }
    }

    private fun chatModel(document: DesignDocumentView, modelId: Long): LlmModelView {
        val model = catalogue.model(modelId) ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.workspaceId != document.workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.CHAT) throw ModelNotChatException(model.name)
        return model
    }

    private fun session(document: DesignDocumentView, asker: String): Long? =
        runCatching { sessions.open(document.workspaceId, SESSION_PREFIX, "${document.id}-$asker") }
            .onFailure { log.warn("The document chat could not open a session for {}", asker, it) }
            .getOrNull()

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
            append("When you have written, say in one or two sentences what you did. Do not repeat the text. ")
            // The tools answer each document's url, which is right for an agent elsewhere and was pasted into this chat
            // as "You can see it here: ..." under the document the person was looking at.
            append(
                "The person already has this document open beside you, so never link to it or give its address, " +
                    "even though the tools answer one.",
            )
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
