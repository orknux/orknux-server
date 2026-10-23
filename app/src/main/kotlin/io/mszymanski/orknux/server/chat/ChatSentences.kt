package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.Brief
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.Hangup
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * How the sentence somebody is typing in a chat might go on. Issue #649.
 *
 * The composer asks on a pause in the typing and shows the answer after the
 * caret for Tab to take. Asked of the workspace's completion model, which is
 * nobody's conversation: what it suggests is never written into the chat, its
 * session or its history - only what the person then sends is, and by then it
 * is their words. The call is counted with every other model call, by
 * [ModelChatClient], so it is on the usage reports like the rest.
 *
 * Nothing about it may fail anything. A model that is slow, refuses, or says
 * something unusable is a suggestion that is not shown; the person is typing
 * and has not asked for anything.
 */
@Service
class ChatSentences(
    private val workspaces: WorkspaceRepository,
    private val models: ModelChatClient,
    private val history: ChatMemoryRepository,
    private val settings: InstallationSettings,
) {

    /**
     * The words that would follow [typed] in [session], or null where there
     * are none to offer: no completion model chosen, nothing typed, or a model
     * that did not answer usefully within the installation's time limit.
     */
    fun continuation(session: ChatSession, typed: String): String? {
        if (typed.isBlank()) return null
        val workspace = workspaces.findByIdOrNull(session.workspaceId) ?: return null
        val modelId = workspace.completionModelId ?: return null

        val turns = listOf(
            ChatTurn(role = "system", content = INSTRUCTION),
            ChatTurn(role = "user", content = context(session, typed)),
        )
        /*
         * Given up on after the installation's limit rather than the model's
         * own, which is set for answers that may think for minutes. Hanging up
         * cancels the request underneath, so a slow model is not left
         * generating for nobody.
         */
        val hangup = Hangup()
        CompletableFuture.delayedExecutor(settings.chatCompletionTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
            .execute(hangup::hangUp)
        val answer = models.complete(
            modelId,
            turns,
            hangup = hangup,
            brief = Brief(maxOutput = settings.chatCompletionMaxTokens(), leastReasoning = true),
        )
        return when (answer) {
            is ChatCompletion.Answered -> cleaned(typed, answer.content)
            is ChatCompletion.CalledTools -> null
            is ChatCompletion.Failed -> {
                if (!hangup.hungUp) log.debug("No completion for chat {}: {}", session.id, answer.reason)
                null
            }
        }
    }

    /** The last few messages of the chat, and the message being written. */
    private fun context(session: ChatSession, typed: String): String {
        val keep = settings.chatCompletionContextTurns()
        val recent = if (keep == 0) {
            emptyList()
        } else {
            history.findByConversationId(session.conversationId)
                .filter { it.messageType.name == "USER" || it.messageType.name == "ASSISTANT" }
                .mapNotNull { message ->
                    message.text?.takeIf { it.isNotBlank() }?.let {
                        (if (message.messageType.name == "USER") "Person" else "Assistant") to it.take(MESSAGE_SAMPLE)
                    }
                }
                .takeLast(keep)
        }
        return buildString {
            if (recent.isNotEmpty()) {
                append("The conversation so far:\n")
                recent.forEach { (who, said) -> append(who).append(": ").append(said).append("\n\n") }
            }
            append("The person's next message, as far as they have typed it:\n")
            append(typed)
        }
    }

    /**
     * Only the new words, on one line.
     *
     * Models asked to continue often repeat what they were given first, or put
     * the suggestion in quotation marks; both are taken off. A suggestion is
     * shown inline after the caret, so it stops at the first line break.
     * Leading space is the model's own, since only it knows whether the typed
     * text ended mid-word - except that after typed whitespace a second space
     * would be one too many.
     */
    internal fun cleaned(typed: String, said: String): String? {
        var text = said.replace("\r", "")
        val typedTrimmed = typed.trim()
        if (typedTrimmed.isNotEmpty() && text.trimStart().startsWith(typedTrimmed)) {
            text = text.trimStart().removePrefix(typedTrimmed)
        }
        text = text.substringBefore('\n')
        val leading = text.takeWhile { it == ' ' || it == '\t' }
        var body = text.trim().removeSurrounding("\"").removeSurrounding("“", "”")
        if (body.isEmpty()) return null
        if (typed.last().isWhitespace()) return body
        if (leading.isNotEmpty()) body = " $body"
        return body
    }

    private companion object {
        val log = LoggerFactory.getLogger(ChatSentences::class.java)

        /** Enough of one earlier message to know what it was about. */
        const val MESSAGE_SAMPLE = 600

        const val INSTRUCTION =
            "You suggest how a message somebody is typing goes on. Reply with only the words that come next, " +
                "continuing their message in their own voice and language - never repeat what they have typed, " +
                "never answer it, and stop at the end of their sentence. If they stopped in the middle of a word, " +
                "begin with the rest of that word; otherwise begin with a space. If nothing sensible follows, reply " +
                "with nothing."
    }
}
