package io.mszymanski.orknux.server.claude

import io.mszymanski.orknux.server.device.DeviceKind
import io.mszymanski.orknux.server.device.DeviceLinks
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/** Whether "My Claude Code" is offered here, and whether the person's session is connected now. */
data class ClaudeCodeChatStatus(val available: Boolean, val reason: String?, val connected: Boolean)

/**
 * What the Orknux channel plugin posts back: Claude's replies, and the
 * approvals Claude Code wants. It takes its messages through the shared door,
 * `/api/claude/next` ([io.mszymanski.orknux.server.device.DeviceAPI]); these two
 * are its own because nothing is waiting on a command id - a reply comes when
 * Claude writes one.
 *
 * Both are the signed-in person's: a reply to a chat that is not theirs, or not
 * a Claude Code chat, is refused as not found.
 */
@RestController
class ClaudeCodeAPI(private val bridge: ClaudeCodeChats) {

    /** `{"chat_id": "12", "text": "..."}` from the reply tool. */
    @PostMapping("/api/claude/replies")
    fun reply(@RequestBody body: JsonNode): ResponseEntity<Void> {
        val place = body.path("chat_id").asString("").trim().ifEmpty { return ResponseEntity.badRequest().build() }
        val text = body.path("text").asString("").take(TEXT_LENGTH)
        val taken = bridge.reply(currentUser(), place, text)
        return ResponseEntity.status(if (taken) HttpStatus.NO_CONTENT else HttpStatus.NOT_FOUND).build()
    }

    /** `{"request_id", "tool_name", "description", "input_preview"}`, as Claude Code relayed them to the channel. */
    @PostMapping("/api/claude/permissions")
    fun permission(@RequestBody body: JsonNode): ResponseEntity<Void> {
        val id = body.path("request_id").asString("")
        if (!ClaudeCodeChats.VERDICT.matches("yes $id")) return ResponseEntity.badRequest().build()
        val request = ClaudeCodePermission(
            requestId = id,
            toolName = body.path("tool_name").asString("").take(200),
            description = body.path("description").asString("").take(2_000),
            inputPreview = body.path("input_preview").asString("").take(4_000),
        )
        val shown = bridge.permission(currentUser(), request)
        return ResponseEntity.status(if (shown) HttpStatus.NO_CONTENT else HttpStatus.NOT_FOUND).build()
    }

    private fun currentUser(): String =
        requireNotNull(SecurityContextHolder.getContext().authentication?.name) { "Nobody is signed in." }

    private companion object {
        const val TEXT_LENGTH = 100_000
    }
}

/** One turn of an editor's assistant conversation, as the page holds it. */
data class ClaudeCodeTurnInput(val role: String, val content: String)

data class ClaudeCodeAnswer(val answer: String)

/** The status the chat page and the corner ask for, and the door the editors' assistants ask through. */
@Controller
class ClaudeCodeStatusAPI(
    private val bridge: ClaudeCodeChats,
    private val links: DeviceLinks,
    private val access: io.mszymanski.orknux.server.security.WorkspaceAccess,
) {

    /**
     * An editor's assistant - a document's, a diagram's, a wireframe's, a
     * sheet's - answered by the person's own Claude Code. The newest question
     * goes, with the page it was asked on; Claude Code changes the item through
     * the Orknux tools the plugin brings, and the page reloads it afterwards.
     */
    @org.springframework.graphql.data.method.annotation.MutationMapping
    fun askClaudeCode(
        @org.springframework.graphql.data.method.annotation.Argument workspaceId: Long,
        @org.springframework.graphql.data.method.annotation.Argument label: String?,
        @org.springframework.graphql.data.method.annotation.Argument path: String?,
        @org.springframework.graphql.data.method.annotation.Argument messages: List<ClaudeCodeTurnInput>,
    ): ClaudeCodeAnswer {
        access.requireVisible(workspaceId)
        val person = requireNotNull(SecurityContextHolder.getContext().authentication?.name) { "Nobody is signed in." }
        val question = messages.lastOrNull { it.role != "assistant" && it.content.isNotBlank() }?.content
            ?: throw io.mszymanski.orknux.server.chat.ChatMessageEmptyException()
        return ClaudeCodeAnswer(bridge.askAbout(person, workspaceId, label, path, question))
    }

    @QueryMapping
    fun claudeCodeChat(): ClaudeCodeChatStatus {
        val availability = bridge.availability()
        val user = SecurityContextHolder.getContext().authentication?.name
        val connected = user != null && links.presence(user, DeviceKind.CLAUDE) != null
        return ClaudeCodeChatStatus(availability.available, availability.reason, connected)
    }
}
