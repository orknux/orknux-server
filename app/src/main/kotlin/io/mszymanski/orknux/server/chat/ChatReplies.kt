package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.Brief
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Replies the person might send next, suggested under the latest answer in a
 * chat and sent with one click.
 *
 * Asked of the workspace's completion model, as a sentence completion is, with
 * thinking off and the installation's own time limit; the chat's model is
 * never asked, because it is the one whose answer is being replied to and may
 * be an agent that thinks for minutes. What comes back is a model's text and
 * is treated as untrusted: each line becomes one plain-text suggestion,
 * stripped of list marks and quotation marks and cut to [MAX_CHARS], and the
 * interface draws them as text, never as markdown.
 *
 * Kept on the chat's row against a digest of the answer they were made for,
 * so a reload, a second tab or a return to the chat shows the same chips
 * without asking again; a new answer has a new digest and is asked about
 * afresh. Nothing is written into the chat's history.
 */
@Service
class ChatReplies(
    private val workspaces: WorkspaceRepository,
    private val models: ModelChatClient,
    private val history: ChatMemoryRepository,
    private val settings: InstallationSettings,
    private val sessions: ChatSessionRepository,
    private val asking: LatestAsk,
) {

    /**
     * The suggested replies to the answer [session] ends on, or none: no
     * completion model, none asked for, the chat not ending on an answer, or a
     * model that said nothing usable in time.
     */
    fun suggestions(session: ChatSession): List<String> {
        val count = settings.chatReplySuggestions()
        if (count == 0) return emptyList()
        val workspace = workspaces.findByIdOrNull(session.workspaceId) ?: return emptyList()
        val modelId = workspace.completionModelId ?: return emptyList()

        val said = history.findByConversationId(session.conversationId)
            .filter { it.messageType.name == "USER" || it.messageType.name == "ASSISTANT" }
        val last = said.lastOrNull() ?: return emptyList()
        // Only under an answer: a chat ending on the person's own message is waiting for one.
        if (last.messageType.name != "ASSISTANT" || last.text.isNullOrBlank()) return emptyList()
        val answer = digest("${said.size}\n${last.text}")

        val id = session.id ?: return emptyList()
        if (session.replySuggestionsFor == answer) return lines(session.replySuggestions.orEmpty()).take(count)

        val key = "replies:$id"
        val hangup = asking.begin(key)
        CompletableFuture.delayedExecutor(settings.chatReplySuggestionsTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
            .execute(hangup::hangUp)
        val reply = try {
            models.complete(
                modelId,
                listOf(
                    ChatTurn(role = "system", content = instruction(count)),
                    ChatTurn(role = "user", content = context(session)),
                ),
                hangup = hangup,
                brief = Brief(maxOutput = settings.chatReplySuggestionsMaxTokens(), leastReasoning = true, noThinking = true),
            )
        } finally {
            asking.end(key, hangup)
        }
        val offered = when (reply) {
            is ChatCompletion.Answered -> cleaned(reply.content).take(count)
            is ChatCompletion.CalledTools -> emptyList()
            is ChatCompletion.Failed -> {
                if (!hangup.hungUp) log.debug("No suggested replies for chat {}: {}", id, reply.reason)
                // Not kept: a model that was slow this once may answer the next time it is asked.
                return emptyList()
            }
        }
        sessions.keepReplySuggestions(id, offered.joinToString("\n"), answer)
        return offered
    }

    private fun context(session: ChatSession): String {
        val recent = recentTurns(history, session, settings.chatCompletionContextTurns().coerceAtLeast(1))
        return buildString {
            append("The conversation so far:\n")
            recent.forEach { (who, said) -> append(who).append(": ").append(said).append("\n\n") }
            append("Suggest what the person might reply to the assistant's last message.")
        }
    }

    /**
     * One suggestion per line of [said], as plain text: list marks, numbering
     * and quotation marks taken off, blank lines and repeats dropped, each cut
     * to [MAX_CHARS] at a word where it can be.
     */
    internal fun cleaned(said: String): List<String> =
        lines(said.replace("\r", ""))
            .map { line ->
                line.trim()
                    .replace(LIST_MARK, "")
                    .trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("“", "”")
                    .removeSurrounding("'")
                    .trim()
            }
            .map { line -> line.filter { !it.isISOControl() } }
            .filter { it.isNotBlank() }
            .map { line -> if (line.length <= MAX_CHARS) line else line.take(MAX_CHARS).substringBeforeLast(' ').ifBlank { line.take(MAX_CHARS) } + "…" }
            .distinct()

    private fun lines(text: String): List<String> = text.split('\n').filter { it.isNotBlank() }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun instruction(count: Int): String =
        "You suggest short replies a person might send next in a chat with an assistant. Write exactly $count " +
            "different replies, one per line, each a single short sentence in the person's own voice and in the " +
            "language of the conversation: a follow-up question, a request to go further, or a decision the " +
            "assistant asked for. No numbering, no quotation marks, no explanations - only the replies."

    private companion object {
        val log = LoggerFactory.getLogger(ChatReplies::class.java)

        /**
         * The longest one suggestion may be, in characters. A format rather than
         * a knob: it is what fits one chip on one line under an answer, and a
         * suggestion longer than that is a model writing paragraphs, not a reply.
         */
        const val MAX_CHARS = 120

        /** A bullet or a number at the start of a line: "- ", "* ", "1. ", "2) ". */
        val LIST_MARK = Regex("""^(?:[-*•]\s+|\d+[.)]\s+)""")
    }
}
