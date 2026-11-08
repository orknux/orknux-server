package io.mszymanski.orknux.server.claude

import io.mszymanski.orknux.connector.model.Hangup
import io.mszymanski.orknux.server.chat.ANSWERED_BY_CLAUDE_CODE
import io.mszymanski.orknux.server.chat.ChatGeneration
import io.mszymanski.orknux.server.chat.ChatGenerations
import io.mszymanski.orknux.server.chat.ChatService
import io.mszymanski.orknux.server.chat.ChatSession
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.device.DeviceKind
import io.mszymanski.orknux.server.device.DeviceLinks
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Why "My Claude Code" is or is not offered here, for the picker and the corner. */
data class ClaudeCodeAvailability(val available: Boolean, val reason: String?)

/** A tool-use approval Claude Code is waiting on, as the channel relayed it. */
data class ClaudeCodePermission(val requestId: String, val toolName: String, val description: String, val inputPreview: String)

/**
 * "My Claude Code": a chat answered by the person's own Claude Code session
 * rather than by a model of this installation's.
 *
 * Orknux carries text and nothing else. The person starts Claude Code
 * themselves, with the Orknux channel plugin; the plugin asks this server for
 * their messages ([DeviceKind.CLAUDE]), pushes each into the session, and posts
 * Claude's replies back here. Every model call is Claude Code's own, under its
 * own sign-in - this server never holds, forwards or uses a Claude credential,
 * which is the line Anthropic's terms draw for a third party.
 *
 * And it is each person's own: delivered only to the session of the person
 * whose chat it is, and never a model an agent, a workflow, a trigger or a
 * schedule can run on - those run for whoever set them off, which is a product using
 * somebody's plan, and they stay on the installation's own providers.
 */
@Service
class ClaudeCodeChats(
    private val links: DeviceLinks,
    private val chats: ChatSessionRepository,
    private val service: ChatService,
    private val generations: ChatGenerations,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
    private val web: io.mszymanski.orknux.server.security.WebProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * A turn waiting for its reply, by where it was asked from: a chat's id, or
     * "page-<uuid>" for Quick Chat and an editor's assistant. Completed by a
     * reply, or by an approval Claude Code wants. Held with whose it is.
     */
    private val waiting = ConcurrentHashMap<String, Pair<String, CompletableFuture<String>>>()

    /** Approvals Claude Code is waiting on, by request id: whose, and where it was asked from. */
    private val permissions = ConcurrentHashMap<String, Pair<String, String>>()

    /** Where each person last wrote to their Claude Code from, which is where an approval request is shown. */
    private val lastPlace = ConcurrentHashMap<String, String>()

    /** Set by a test, which runs on neither the desktop app nor a one-person installation. */
    internal var forceAvailable: Boolean? = null

    /**
     * Whether My Claude Code is offered: to everybody signed in, on every
     * server and in every client. What keeps it within Anthropic's terms is not
     * where it is offered but how it works - each message reaches only the
     * Claude Code of the person whose chat it is, signed in as them, and Orknux
     * never holds or uses a Claude credential - so nobody can use anybody
     * else's plan. A test may switch it off to check the refusal.
     */
    fun availability(): ClaudeCodeAvailability {
        forceAvailable?.let { return ClaudeCodeAvailability(it, if (it) null else UNAVAILABLE) }
        return ClaudeCodeAvailability(true, null)
    }

    fun isClaudeCode(chat: ChatSession): Boolean = chat.answeredBy == ANSWERED_BY_CLAUDE_CODE

    /** Whether [user]'s Claude Code is connected now. */
    fun connected(user: String): Boolean = links.presence(user, DeviceKind.CLAUDE) != null

    /**
     * One turn of a chat: [text] said to the person's Claude Code, and what came back.
     *
     * Waits for the session's reply, or for an approval it asks for, or for
     * Stop. A session that takes longer than [Tunable.CLAUDE_CODE_TURN_SECONDS]
     * ends the turn with a sentence saying so; its reply still arrives, as a
     * message of its own ([reply]). Answers in English: the sentences here are
     * Orknux's own, and the replies are whatever language Claude wrote.
     */
    fun ask(chat: ChatSession, text: String, hangup: Hangup): String {
        val id = requireNotNull(chat.id)
        return turn(
            chat.userId, id.toString(), text, hangup,
            mapOf("chat_title" to chat.title, "workspace_id" to chat.workspaceId.toString(), "from" to "chat"),
            stopped = "Stopped waiting. Claude Code may still answer; its reply will appear here.",
            late = STILL_WORKING,
        )
    }

    /**
     * One turn from Quick Chat or an editor's assistant: [text] said to the
     * person's Claude Code with where they were - [label] at [path] in
     * workspace [workspaceId] - so it can read and change what they are looking
     * at through the Orknux tools the plugin brings. Nothing is kept on this
     * side: those panels keep their conversation in the page, and a reply that
     * comes after the wait has nowhere to go, which the answer says.
     */
    fun askAbout(user: String, workspaceId: Long, label: String?, path: String?, text: String, hangup: Hangup = Hangup()): String {
        if (!availability().available) throw ClaudeCodeUnavailableException()
        val place = "page-" + UUID.randomUUID()
        val address = path?.takeIf { it.startsWith("/") }?.let { base() + it }
        val context = buildString {
            append(text.trim())
            append("\n\n(Sent from ").append(label?.takeIf { it.isNotBlank() } ?: "a page").append(" in Orknux")
            address?.let { append(", ").append(it) }
            append(". Workspace ").append(workspaceId)
            append(": read and change what this is about with the plugin's orknux_tools and orknux_call, passing that workspace_id. ")
            append("The page reloads after your reply, so make the change itself rather than describing it.)")
        }
        return turn(
            user, place, text, hangup,
            mapOf("workspace_id" to workspaceId.toString(), "from" to "page", "page" to (address ?: "")),
            stopped = "Stopped waiting.",
            late = "Claude Code is still working on this, and this panel cannot wait any longer. Ask in a chat with My Claude Code for long work.",
            content = context,
        )
    }

    /** Sends one message and waits for what answers it; a verdict on an open approval is sent as a verdict instead. */
    private fun turn(
        user: String,
        place: String,
        text: String,
        hangup: Hangup,
        meta: Map<String, String>,
        stopped: String,
        late: String,
        content: String = text,
    ): String {
        lastPlace[user] = place

        // "yes abcde" to an approval of this person's that is open: a verdict for Claude Code, never a message to Claude.
        VERDICT.matchEntire(text.trim())?.let { found ->
            val requestId = found.groupValues[2].lowercase()
            if (permissions[requestId]?.first == user) {
                val allow = found.groupValues[1].lowercase().startsWith("y")
                val sent = links.post(user, DeviceKind.CLAUDE, "permission", mapper.valueToTree(mapOf("request_id" to requestId, "behavior" to if (allow) "allow" else "deny")))
                if (!sent) return NOT_CONNECTED
                permissions.remove(requestId)
                return if (allow) "Allowed. Claude Code carries on." else "Denied. Claude Code was told no."
            }
        }

        val turn = CompletableFuture<String>()
        waiting.put(place, user to turn)?.second?.complete(STILL_WORKING)
        val sent = links.leave(user, DeviceKind.CLAUDE, "message", mapper.valueToTree(meta + ("chat_id" to place) + ("text" to content)))
        if (sent == null) {
            waiting.remove(place)
            return NOT_CONNECTED
        }
        // Whether it ever reached Claude Code decides what the end of the wait says: "still working" over a
        // message nobody took was a promise of an answer that could never come, and the chat kept it for ever.
        fun neverTaken() = links.withdraw(user, DeviceKind.CLAUDE, sent)
        val until = System.currentTimeMillis() + tuning[Tunable.CLAUDE_CODE_TURN_SECONDS] * 1000L
        try {
            while (true) {
                if (hangup.hungUp) return if (neverTaken()) "$stopped $NOT_TAKEN_STOPPED" else stopped
                val left = until - System.currentTimeMillis()
                if (left <= 0) return if (neverTaken()) NOT_TAKEN else late
                try {
                    return turn.get(minOf(left, POLL_MILLIS), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Round again, to notice Stop.
                }
            }
        } finally {
            waiting.remove(place, user to turn)
        }
    }

    /**
     * What Claude wrote back through the channel's reply tool, for [place]:
     * into the turn waiting for it, or - for a chat with none waiting - into
     * the chat as a message of its own, followable by a page open on it. False
     * where the place is not this person's.
     */
    fun reply(user: String, place: String, text: String): Boolean {
        val said = text.trim()
        val waiter = waiting[place]
        if (waiter != null) {
            if (waiter.first != user) return false
            if (said.isNotEmpty()) waiter.second.complete(said)
            return true
        }
        val chatId = place.toLongOrNull() ?: return false
        val chat = chats.findById(chatId).orElse(null) ?: return false
        if (chat.userId != user || !isClaudeCode(chat)) return false
        if (said.isNotEmpty()) arrived(chatId, said)
        return true
    }

    /**
     * An approval Claude Code wants, relayed by the channel. Shown where the
     * person last wrote from, with the words that answer it.
     */
    fun permission(user: String, request: ClaudeCodePermission): Boolean {
        val place = lastPlace[user] ?: return false
        val said = buildString {
            append("Claude Code wants to use **").append(request.toolName).append("**: ").append(request.description)
            if (request.inputPreview.isNotBlank()) append("\n\n```\n").append(request.inputPreview.take(PREVIEW_LENGTH)).append("\n```")
            append("\n\nAnswer **yes ").append(request.requestId).append("** to allow it, or **no ").append(request.requestId).append("** to refuse.")
        }
        val waiter = waiting[place]
        if (waiter != null && waiter.first == user) {
            permissions[request.requestId] = user to place
            waiter.second.complete(said)
            return true
        }
        val chatId = place.toLongOrNull() ?: return false
        val chat = chats.findById(chatId).orElse(null) ?: return false
        if (chat.userId != user || !isClaudeCode(chat)) return false
        permissions[request.requestId] = user to place
        arrived(chatId, said)
        return true
    }

    private fun base() = web.baseUrl.trimEnd('/')

    /** A message from Claude Code with no turn waiting for it, written into the chat and shown to a page open on it. */
    private fun arrived(chatId: Long, text: String) {
        try {
            service.finishSend(chatId, text)
            val generation = ChatGeneration(woken = true)
            generations.register(chatId, generation)
            try {
                generation.post("chunk", mapOf("text" to text))
                generation.post("answered", emptyMap<String, Any>())
            } finally {
                generations.release(chatId, generation)
            }
        } catch (failure: Exception) {
            log.warn("A reply from Claude Code could not be written into chat {}: {}", chatId, failure.message)
        }
    }

    companion object {
        /** "yes abcde", "n abcde": Claude Code's request ids are five letters without l. */
        val VERDICT = Regex("""^\s*(y|yes|n|no)\s+([a-km-z]{5})\s*$""", RegexOption.IGNORE_CASE)

        private const val POLL_MILLIS = 500L
        private const val PREVIEW_LENGTH = 1_500

        const val UNAVAILABLE = "My Claude Code is switched off on this server."

        const val NOT_CONNECTED =
            "Your Claude Code isn't connected. Start Claude Code with the Orknux channel plugin - its README " +
                "has the command - and send this again."

        const val STILL_WORKING = "Claude Code is still working on this. Its answer will appear here when it is ready."

        /** The end of the wait for a message Claude Code never picked up: it was taken back, and saying so is the only true thing to say. */
        const val NOT_TAKEN = "Your Claude Code did not pick this message up, so it was taken back and not sent. " +
            "It may be busy with something else, or have lost its connection. Send it again."

        /** Added to the Stop wording when the message had not reached Claude Code yet. */
        const val NOT_TAKEN_STOPPED = "It had not reached Claude Code yet, so it was not sent."
    }
}

class ClaudeCodeUnavailableException : RuntimeException(ClaudeCodeChats.UNAVAILABLE)
