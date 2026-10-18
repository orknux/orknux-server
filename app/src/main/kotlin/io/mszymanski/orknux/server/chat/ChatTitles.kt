package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Naming a chat from what was said in it.
 *
 * "New chat" is the right name for an empty one and a useless name for a list of
 * them, so the first exchange earns a real one. The work is done by the
 * workspace's companion model rather than the model being talked to: it is not
 * part of the conversation, and it should be able to be something cheap.
 *
 * A workspace with no companion model names it with the chat's own model, which
 * is the one model every chat is sure to have: left unnamed, a list of chats read
 * "New chat" down the whole of it. Nothing
 * about this is important enough to fail a chat over — a title that did not
 * arrive is a title, and the person can rename it.
 */
@Service
class ChatTitles(
    private val sessions: ChatSessionRepository,
    private val workspaces: WorkspaceRepository,
    private val models: ModelChatClient,
    private val history: org.springframework.ai.chat.memory.ChatMemoryRepository,
    private val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
    transactions: PlatformTransactionManager,
) {

    private val writing = TransactionTemplate(transactions)

    /**
     * The chats a model is being asked to name right now. Without it every turn
     * that finished while a slow model was still naming the chat found it still
     * called New chat and asked again - short voice turns put "Naming this
     * chat…" back on the page turn after turn, and the last answer to come back
     * won, describing whichever turn asked last.
     */
    private val naming: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Renames a chat still carrying its default name, from the exchange just had.
     *
     * Only the untouched default is replaced: a chat somebody named is named,
     * and having it renamed underneath them would be worse than "New chat" ever
     * was.
     *
     * The model is asked outside any transaction, and only the rename is
     * written in one. This was a single @Transactional method, harmless while
     * only a companion model was asked; once a chat's own model names it, that
     * held the database's one writer on SQLite for as long as the model took to
     * answer - a local model thinking for a minute stalled every other write,
     * and a suite whose stub had gone away saw commits fail in the next class.
     * The title is checked again when it is written, so a rename made while the
     * model was answering stands.
     */
    /**
     * Returns why the model could not name the chat, for the page to say so
     * for a moment; null when it named it, or when there was nothing to name.
     */
    fun nameFrom(sessionId: Long, question: String, answer: String): String? {
        // One naming of a chat at a time; a turn finishing meanwhile neither asks again nor counts.
        if (!naming.add(sessionId)) return null
        try {
            return nameWith(sessionId, question, answer)
        } finally {
            naming.remove(sessionId)
        }
    }

    /**
     * Whether [nameFrom] would ask a model for this chat's title: it is not
     * being named already, there is a model to ask, and it is still the default
     * - or a title a model gave that this exchange makes due again.
     */
    fun wouldName(sessionId: Long): Boolean {
        if (sessionId in naming) return false
        val session = sessions.findByIdOrNull(sessionId) ?: return false
        if (session.title != DEFAULT_TITLE && !dueAgain(session)) return false
        val workspace = workspaces.findByIdOrNull(session.workspaceId) ?: return false
        return (workspace.companionModelId ?: session.modelId) != null
    }

    /** A title a model gave, and the exchange finishing now is the one that names it again. */
    private fun dueAgain(session: ChatSession): Boolean {
        val every = settings.chatRetitleEvery()
        return session.titleGenerated && every > 0 && session.turnsSinceTitle + 1 >= every
    }

    /** What the chat is called now. */
    fun titleOf(sessionId: Long): String? = sessions.findByIdOrNull(sessionId)?.title

    private fun nameWith(sessionId: Long, question: String, answer: String): String? {
        val session = sessions.findByIdOrNull(sessionId) ?: return null
        if (session.title != DEFAULT_TITLE) {
            if (dueAgain(session)) return renameFromHistory(session)
            // Not due: the exchange is counted, and a title somebody typed is never counted towards anything.
            if (session.titleGenerated) {
                writing.executeWithoutResult {
                    sessions.findByIdOrNull(sessionId)?.let { held ->
                        held.turnsSinceTitle += 1
                        sessions.save(held)
                    }
                }
            }
            return null
        }

        val workspace = workspaces.findByIdOrNull(session.workspaceId) ?: return null
        val companion = workspace.companionModelId ?: session.modelId ?: return null
        val failed = arrayOfNulls<String>(1)

        /*
         * From the question's own words where the model gave nothing usable. A
         * reasoning model asked for six words often thinks and answers with
         * nothing, which left every chat on an installation called New chat
         * with not a line in the log.
         */
        val suggested = ask(companion, question, answer) { failed[0] = it }
            ?: fromWords(question).also {
                log.info("Chat {} named from its first message: its model gave no usable title", sessionId)
                if (failed[0] == null) failed[0] = "The model gave no title"
            }
            ?: return failed[0]
        writing.executeWithoutResult {
            val held = sessions.findByIdOrNull(sessionId) ?: return@executeWithoutResult
            if (held.title == DEFAULT_TITLE) {
                held.title = suggested
                held.titleGenerated = true
                held.turnsSinceTitle = 0
                sessions.save(held)
            }
        }
        return failed[0]
    }

    /**
     * Names a chat again from what it has become: its latest messages rather
     * than the one exchange, since the point is that it has moved on. Kept as it
     * was where the model gives nothing - the old title is still a title - and
     * where somebody renamed it while the model was answering.
     */
    private fun renameFromHistory(session: ChatSession): String? {
        val sessionId = requireNotNull(session.id)
        val workspace = workspaces.findByIdOrNull(session.workspaceId) ?: return null
        val companion = workspace.companionModelId ?: session.modelId ?: return null
        val recent = history.findByConversationId(session.conversationId)
            .filter { it.messageType.name == "USER" || it.messageType.name == "ASSISTANT" }
            .mapNotNull { message -> message.text?.takeIf { it.isNotBlank() }?.let { message.messageType.name to it } }
            .takeLast(RECENT_MESSAGES)
        val question = recent.filter { it.first == "USER" }.joinToString("\n") { it.second.take(ANSWER_SAMPLE) }
        val answer = recent.filter { it.first == "ASSISTANT" }.joinToString("\n") { it.second.take(ANSWER_SAMPLE) }
        val failed = arrayOfNulls<String>(1)
        val suggested = ask(companion, question, answer) { failed[0] = it }
        val before = session.title
        writing.executeWithoutResult {
            val held = sessions.findByIdOrNull(sessionId) ?: return@executeWithoutResult
            if (held.title == before && held.titleGenerated) {
                if (suggested != null) held.title = suggested
                held.turnsSinceTitle = 0
                sessions.save(held)
            }
        }
        if (suggested != null) log.info("Chat {} named again after {} exchanges", sessionId, settings.chatRetitleEvery())
        return failed[0]
    }

    /**
     * What the companion model made of it, or null when it could not be used.
     *
     * The answer is trimmed hard: models like to reply "Sure! Here is a title:
     * …", and quotation marks around a title are a habit rather than a request.
     * Anything that comes back long is cut rather than refused — a truncated
     * title beats none.
     */
    private fun ask(modelId: Long, question: String, answer: String, failed: (String) -> Unit): String? {
        val turns = listOf(
            ChatTurn(
                role = "system",
                content = "You name conversations. Reply with a title of at most six words for the exchange " +
                    "that follows. No quotation marks, no punctuation at the end, no preamble — the title only.",
            ),
            ChatTurn(role = "user", content = "Message: $question\n\nReply: ${answer.take(ANSWER_SAMPLE)}"),
        )

        return when (val completion = models.complete(modelId, turns)) {
            // Naming a chat is one round with no tools offered, so this cannot
            // happen; the compiler wants it said.
            is ChatCompletion.CalledTools -> null
            is ChatCompletion.Failed -> {
                // Worth knowing about, not worth failing a chat over.
                log.warn("Could not name chat with the companion model: {}", completion.reason)
                failed(completion.reason)
                null
            }
            is ChatCompletion.Answered -> clean(completion.content)
        }
    }

    /** "can you check what the biggest files are on michjak-server" -> "can you check what the biggest". */
    private fun fromWords(question: String): String? =
        question.trim().lineSequence().firstOrNull { it.isNotBlank() }
            ?.split(Regex("\\s+"))?.take(FALLBACK_WORDS)?.joinToString(" ")
            ?.trimEnd('.', ',', '!', '?', ':', ';', ' ')
            ?.take(TITLE_LENGTH)
            ?.ifBlank { null }

    /** The first line, without the decoration models add to it. */
    private fun clean(said: String): String? {
        val line = said.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        return line
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trimEnd('.', '!', ' ')
            .take(TITLE_LENGTH)
            .ifBlank { null }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ChatTitles::class.java)

        /** What a chat is called before it has been anything. */
        /** How many of the question's words name a chat its model could not. */
        const val FALLBACK_WORDS = 6

        const val DEFAULT_TITLE = "New chat"

        /** Enough of the answer to name it by; the whole of it is not needed. */
        const val ANSWER_SAMPLE = 500

        /** How many of the latest messages a chat is named again from. */
        const val RECENT_MESSAGES = 8

        /** Matches the column. */
        const val TITLE_LENGTH = 200
    }
}
