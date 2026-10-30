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
import java.util.concurrent.ConcurrentHashMap
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
    private val completionModels: CompletionModels,
    private val workspaces: WorkspaceRepository,
    private val models: ModelChatClient,
    private val history: ChatMemoryRepository,
    private val settings: InstallationSettings,
    private val asking: LatestAsk,
) {

    /**
     * The words that would follow [typed] in [session], or null where there
     * are none to offer: no completion model chosen, nothing typed, a model
     * that did not answer usefully within the installation's time limit, or a
     * newer request for the same chat that took its place.
     */
    fun continuation(session: ChatSession, typed: String): String? {
        if (typed.isBlank()) return null
        return continuation(session.workspaceId, "completion:${session.id}", INSTRUCTION, context(session, typed), typed)
    }

    /**
     * The words that would follow the caret in a document being written: the
     * document editor's inline suggestion, asked of the same completion model,
     * under the same time limit and token cap, and hung up by a newer request
     * for the same document as a chat's is.
     *
     * @param before the text before the caret, as far back as the editor sends.
     * @param after the text after it, so a suggestion does not repeat what follows.
     * @param page which page of the document the caret is on, from 1.
     */
    fun inDocument(workspaceId: Long, documentId: Long, title: String, page: Int?, before: String, after: String): String? {
        if (before.isBlank()) return null
        val context = buildString {
            append("The document is called \"").append(title).append('"')
            if (page != null) append(", and the writer is on page ").append(page)
            append(".\n\nThe text before the caret:\n").append(before)
            if (after.isNotBlank()) append("\n\nThe text after the caret, which must not be repeated:\n").append(after)
        }
        return continuation(workspaceId, "document-completion:$documentId", DOCUMENT_INSTRUCTION, context, before)
    }

    /**
     * What would follow the caret in a function's code: the function editor's
     * inline suggestion, asked of the same completion model under the same
     * time limit and token cap, and hung up by a newer request for the same
     * function as a document's is. Unlike prose it may run to a few lines -
     * the rest of a statement, or a short block - since that is what code
     * completion is for.
     *
     * @param key what a newer request replaces this one under: the function, or the person writing a new one.
     */
    fun inCode(workspaceId: Long, key: String, name: String, before: String, after: String): String? {
        if (before.isBlank()) return null
        val context = buildString {
            append("The function is called ").append(name.ifBlank { "(not named yet)" })
            append(" and is TypeScript, run in the Orknux sandbox.\n\nThe code before the caret:\n").append(before)
            if (after.isNotBlank()) append("\n\nThe code after the caret, which must not be repeated:\n").append(after)
        }
        val said = ask(workspaceId, "function-completion:$key", CODE_INSTRUCTION, context) ?: return null
        return codeCleaned(before, said)
    }

    /**
     * One ask of the workspace's completion model: [instruction] and
     * [context], answered with the words that follow [typed], cleaned. A newer
     * ask under the same [key] hangs this one up.
     */
    private fun continuation(workspaceId: Long, key: String, instruction: String, context: String, typed: String): String? =
        ask(workspaceId, key, instruction, context)?.let { cleaned(typed, it) }

    /** The model's answer as it said it, or null where there is none to use. */
    private fun ask(workspaceId: Long, key: String, instruction: String, context: String): String? {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: return null
        val modelId = completionModels.of(workspace) ?: return null

        val turns = listOf(
            ChatTurn(role = "system", content = instruction),
            ChatTurn(role = "user", content = context),
        )
        /*
         * A newer request for this chat hangs this one up. The composer drops
         * an answer for text that is no longer there by aborting its fetch, and
         * that abort never reaches the model: the call ran on to its answer or
         * its time limit regardless. On a model that serves one request at a
         * time - llama.cpp's default - every abandoned call was a call the next
         * one queued behind, so a person typing in bursts built a queue of
         * calls nobody would read, and the one they were waiting for ran out of
         * time behind it. That was the completion that "stopped for a while".
         */
        val hangup = asking.begin(key)
        /*
         * Given up on after the installation's limit rather than the model's
         * own, which is set for answers that may think for minutes. Hanging up
         * cancels the request underneath, so a slow model is not left
         * generating for nobody.
         */
        CompletableFuture.delayedExecutor(settings.chatCompletionTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
            .execute(hangup::hangUp)
        val answer = try {
            models.complete(
                modelId,
                turns,
                hangup = hangup,
                // No thinking at all where the provider can be told so: a few words are wanted, now.
                brief = Brief(maxOutput = settings.chatCompletionMaxTokens(), leastReasoning = true, noThinking = true),
            )
        } finally {
            asking.end(key, hangup)
        }
        return when (answer) {
            is ChatCompletion.Answered -> answer.content
            is ChatCompletion.CalledTools -> null
            is ChatCompletion.Failed -> {
                if (!hangup.hungUp) log.debug("No completion for {}: {}", key, answer.reason)
                null
            }
        }
    }

    /** The last few messages of the chat, and the message being written. */
    private fun context(session: ChatSession, typed: String): String {
        val recent = recentTurns(history, session, settings.chatCompletionContextTurns())
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
     * Models asked to continue often repeat what they were given first - all
     * of it, or only its last few words - or put the suggestion in quotation
     * marks; all of it is taken off. A repeat the token limit cut short, which
     * holds no new words at all, is no suggestion rather than the typed text
     * shown a second time. A suggestion is shown inline after the caret, so it
     * stops at the first line break that follows some words: a model that
     * begins with a line break still has its words read. Leading space is the
     * model's own, since only it knows whether the typed text ended mid-word -
     * except that after typed whitespace a second space would be one too many.
     */
    internal fun cleaned(typed: String, said: String): String? {
        var text = said.replace("\r", "")
        // A line break before any words stands for the space between them and what was typed.
        val opening = text.takeWhile { it.isWhitespace() }
        if (opening.contains('\n')) text = " " + text.trimStart()
        val typedTrimmed = typed.trim()
        val head = text.trimStart()
        if (typedTrimmed.isNotEmpty()) {
            when {
                head.startsWith(typedTrimmed) -> text = head.removePrefix(typedTrimmed)
                // The repeat ran out of tokens before it reached anything new.
                typedTrimmed.startsWith(head.trimEnd()) && head.trim().any { it.isWhitespace() } -> return null
                else -> repeatedTail(typedTrimmed, head)?.let { tail -> text = head.removePrefix(tail) }
            }
        }
        text = text.substringBefore('\n')
        val leading = text.takeWhile { it == ' ' || it == '\t' }
        var body = text.trim().removeSurrounding("\"").removeSurrounding("“", "”")
        if (body.isEmpty()) return null
        if (typed.last().isWhitespace()) return body
        if (leading.isNotEmpty()) body = " $body"
        return body
    }

    /**
     * Only the new code, at most [CODE_LINES] lines of it.
     *
     * A model asked to continue code wraps it in a fence as often as not, and
     * repeats the line the caret is on; both are taken off. Unlike prose its
     * leading whitespace and line breaks are kept, since they are indentation
     * and they are the code. Nothing but whitespace is no suggestion.
     */
    internal fun codeCleaned(before: String, said: String): String? {
        var text = said.replace("\r", "")
        val fenced = Regex("^\\s*```[A-Za-z0-9_+-]*\\n?([\\s\\S]*?)\\n?```\\s*$").matchEntire(text)
        if (fenced != null) text = fenced.groupValues[1]
        val line = before.substringAfterLast('\n')
        if (line.isNotBlank()) {
            val head = text.trimStart(' ', '\t')
            if (head.startsWith(line.trimStart())) text = head.removePrefix(line.trimStart())
        }
        val lines = text.split('\n')
        text = lines.take(CODE_LINES).joinToString("\n").trimEnd()
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * The longest end of [typed] that [said] starts with, where that end is
     * whole words and more than one of them; null where there is none. A
     * single word is left alone: "to" ending the message and "to the station"
     * beginning the answer is as likely a word the person wants twice as a
     * repeat.
     */
    private fun repeatedTail(typed: String, said: String): String? {
        for (start in typed.indices) {
            if (start > 0 && !typed[start - 1].isWhitespace()) continue
            val tail = typed.substring(start)
            if (tail.none { it.isWhitespace() }) return null
            if (said.startsWith(tail) && (said.length == tail.length || !said[tail.length].isLetterOrDigit())) return tail
        }
        return null
    }

    private companion object {
        val log = LoggerFactory.getLogger(ChatSentences::class.java)

        /*
         * "Stop at the end of their sentence" alone made a model answer nothing
         * once a suggestion had been taken: the taken words end the sentence,
         * and "if nothing sensible follows, reply with nothing" did the rest. So
         * a finished sentence is answered with the next one.
         */
        const val INSTRUCTION =
            "You suggest how a message somebody is typing goes on. Reply with only the words that come next, " +
                "continuing their message in their own voice and language - never repeat what they have typed, " +
                "never answer it. Finish the sentence or clause they are in; if what they typed already ends a " +
                "sentence, suggest the short sentence they would most likely write next. If they stopped in the " +
                "middle of a word, begin with the rest of that word; otherwise begin with a space. Reply with " +
                "nothing only when nothing sensible could follow."

        /** How much code one suggestion may be: the rest of a statement or a short block, never a whole function. */
        const val CODE_LINES = 8

        /** The same ask, of somebody writing a function's code. */
        const val CODE_INSTRUCTION =
            "You complete code at the caret, as an editor's inline suggestion. Reply with only the code that comes " +
                "next - the rest of the line, or at most a few lines that finish the statement or block being written - " +
                "never repeat the code before the caret, never repeat the code after it, never explain, no Markdown " +
                "fences. Keep the indentation and style of the code around it. If they stopped in the middle of a " +
                "word, begin with the rest of that word. Reply with nothing when nothing sensible could follow."

        /** The same ask, of somebody writing a document rather than a message. */
        const val DOCUMENT_INSTRUCTION =
            "You suggest how a document somebody is writing goes on at the caret. Reply with only the words that come " +
                "next, in the writer's own voice, language and register - never repeat the text before the caret, never " +
                "repeat the text after it, never comment on it. Finish the sentence they are in; if it is finished, " +
                "suggest the short sentence they would most likely write next. If they stopped in the middle of a word, " +
                "begin with the rest of that word; otherwise begin with a space. Reply with nothing only when nothing " +
                "sensible could follow."
    }
}

/**
 * The chat's latest messages, as who said them and what, at most [keep] of
 * them and each cut to a sample - what both the completion and the suggested
 * replies show the completion model.
 */
internal fun recentTurns(history: ChatMemoryRepository, session: ChatSession, keep: Int): List<Pair<String, String>> {
    if (keep == 0) return emptyList()
    return history.findByConversationId(session.conversationId)
        .filter { it.messageType.name == "USER" || it.messageType.name == "ASSISTANT" }
        .mapNotNull { message ->
            message.text?.takeIf { it.isNotBlank() }?.let {
                (if (message.messageType.name == "USER") "Person" else "Assistant") to it.take(MESSAGE_SAMPLE)
            }
        }
        .takeLast(keep)
}

/** Enough of one earlier message to know what it was about. */
private const val MESSAGE_SAMPLE = 600

/**
 * The one request in flight for each thing asked, so a newer one can hang up
 * the one it replaced.
 *
 * Keyed by what is asked - a chat's completion, a chat's replies - rather than
 * by who asks, because a chat has one owner and one composer, and a second
 * browser tab on it is still the same person typing.
 */
@Service
class LatestAsk {

    private val inFlight = ConcurrentHashMap<String, Hangup>()

    /** A hangup for a new request under [key], hanging up the one it replaces. */
    fun begin(key: String): Hangup {
        val fresh = Hangup()
        inFlight.put(key, fresh)?.hangUp()
        return fresh
    }

    /** The request under [key] has ended; forgotten unless a newer one has taken its place already. */
    fun end(key: String, hangup: Hangup) {
        inFlight.remove(key, hangup)
    }
}
