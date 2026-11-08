package io.mszymanski.orknux.server.claude

import io.mszymanski.orknux.connector.model.Hangup
import io.mszymanski.orknux.server.chat.ANSWERED_BY_CLAUDE_CODE
import io.mszymanski.orknux.server.chat.ChatService
import io.mszymanski.orknux.server.chat.ChatSession
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.device.DeviceKind
import io.mszymanski.orknux.server.device.DeviceLinks
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "My Claude Code": a chat answered by the person's own Claude Code session
 * through the Orknux channel plugin, driven here by a fake plugin polling the
 * real door with a real token. What the plugin does inside Claude Code is the
 * plugin's to test; this is the server's half - the message delivered, the
 * reply back into the turn or into the chat, an approval asked and answered,
 * and nobody else's reply accepted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClaudeCodeChatsTest(
    @LocalServerPort val port: Int,
    @Autowired val users: AppUserRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val chats: ChatSessionRepository,
    @Autowired val service: ChatService,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val bridge: ClaudeCodeChats,
    @Autowired val links: DeviceLinks,
    @Autowired val mapper: ObjectMapper,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private var workspaceId = 0L
    private lateinit var user: String
    private lateinit var token: String
    private val running = AtomicBoolean(false)
    private var plugin: Thread? = null
    private val received = CopyOnWriteArrayList<Map<String, Any?>>()

    @BeforeEach
    fun setUp() {
        bridge.forceAvailable = true
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "claude-" + System.nanoTime())).id)
        user = "claude-user-" + System.nanoTime()
        token = mint(user)
    }

    @AfterEach
    fun tearDown() {
        bridge.forceAvailable = null
        running.set(false)
        plugin?.join(10_000)
    }

    private fun mint(name: String): String {
        val person = users.save(AppUser(username = name, displayName = name, type = UserType.INTERNAL))
        return internal.mint(person, "claude-" + System.nanoTime()).second
    }

    private fun chat(owner: String = user): ChatSession {
        val made = chats.save(ChatSession(workspaceId = workspaceId, conversationId = UUID.randomUUID().toString(), title = "New chat", userId = owner))
        return service.chooseClaudeCode(requireNotNull(made.id))
    }

    private fun post(path: String, body: Any, bearer: String = token) =
        client.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
            .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body)).retrieve().toBodilessEntity()

    /** Polls as the plugin does, handing each command to [onCommand]; returns once the session counts as connected. */
    private fun connect(onCommand: (Map<String, Any?>) -> Unit = {}) {
        running.set(true)
        plugin = Thread {
            while (running.get()) {
                val asked = client.get().uri("/api/claude/next?wait=1&client=ClaudeCode&version=0.1.0")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token").retrieve().toEntity(String::class.java)
                if (asked.statusCode != HttpStatus.OK) continue
                @Suppress("UNCHECKED_CAST")
                val command = mapper.readValue(asked.body, Map::class.java) as Map<String, Any?>
                received += command
                onCommand(command)
            }
        }.apply { isDaemon = true; start() }
        val until = System.currentTimeMillis() + 5_000
        while (links.presence(user, DeviceKind.CLAUDE) == null) {
            check(System.currentTimeMillis() < until) { "The plugin never connected." }
            Thread.sleep(20)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun argumentsOf(command: Map<String, Any?>) = command["arguments"] as Map<String, Any?>

    @Test
    fun `a message reaches the person's Claude Code and its reply is the answer`() {
        val chat = chat()
        connect { command ->
            val args = argumentsOf(command)
            post("/api/claude/replies", mapOf("chat_id" to args["chat_id"], "text" to "Done: ${args["text"]}"))
        }

        val answer = bridge.ask(chat, "list the files", Hangup())

        assertThat(answer).isEqualTo("Done: list the files")
        val delivered = received.single()
        assertThat(delivered["action"]).isEqualTo("message")
        assertThat(argumentsOf(delivered)["chat_id"]).isEqualTo(chat.id.toString())
    }

    @Test
    fun `a reply with no turn waiting is written into the chat`() {
        val chat = chat()
        connect()
        val before = history.findByConversationId(chat.conversationId).size

        val taken = post("/api/claude/replies", mapOf("chat_id" to chat.id.toString(), "text" to "Finished the long job."))

        assertThat(taken.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        val thread = history.findByConversationId(chat.conversationId)
        assertThat(thread).hasSize(before + 1)
        assertThat(thread.last().text).isEqualTo("Finished the long job.")
    }

    @Test
    fun `an approval Claude Code wants is asked in the chat, and the answer goes back to Claude Code only`() {
        val chat = chat()
        connect { command ->
            if (command["action"] == "message") {
                post(
                    "/api/claude/permissions",
                    mapOf("request_id" to "abcde", "tool_name" to "Bash", "description" to "List files", "input_preview" to "{\"command\":\"ls\"}"),
                )
            }
        }

        val asked = bridge.ask(chat, "list the files", Hangup())
        assertThat(asked).contains("Bash").contains("yes abcde").contains("no abcde")

        val verdict = bridge.ask(chat, "Yes ABCDE", Hangup())
        assertThat(verdict).startsWith("Allowed")
        val until = System.currentTimeMillis() + 5_000
        while (received.none { it["action"] == "permission" } && System.currentTimeMillis() < until) Thread.sleep(20)
        val sent = received.single { it["action"] == "permission" }
        assertThat(argumentsOf(sent)).containsEntry("request_id", "abcde").containsEntry("behavior", "allow")
        // The verdict is never a message to Claude.
        assertThat(received.count { it["action"] == "message" }).isEqualTo(1)
    }

    @Test
    fun `with no session connected the turn says how to start one`() {
        assertThat(bridge.ask(chat(), "hello", Hangup())).isEqualTo(ClaudeCodeChats.NOT_CONNECTED)
    }

    @Test
    fun `stop ends the wait, and the reply still comes later`() {
        val chat = chat()
        connect()
        val hangup = Hangup()
        val waiting = CompletableFuture.supplyAsync { bridge.ask(chat, "think hard", hangup) }
        Thread.sleep(300)
        hangup.hangUp()

        assertThat(waiting.get(5, TimeUnit.SECONDS)).startsWith("Stopped waiting")
    }

    @Test
    fun `nobody else's reply is taken, and a chat that is not Claude Code's takes none`() {
        val chat = chat()
        val stranger = mint("stranger-" + System.nanoTime())

        assertThat(post("/api/claude/replies", mapOf("chat_id" to chat.id.toString(), "text" to "hi"), stranger).statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
        val plain = chats.save(ChatSession(workspaceId = workspaceId, conversationId = UUID.randomUUID().toString(), title = "x", userId = user))
        assertThat(post("/api/claude/replies", mapOf("chat_id" to plain.id.toString(), "text" to "hi")).statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `it is offered on any server, and a refusal where it is switched off is in words`() {
        bridge.forceAvailable = null
        assertThat(bridge.availability().available).isTrue()

        bridge.forceAvailable = false
        val made = chats.save(ChatSession(workspaceId = workspaceId, conversationId = UUID.randomUUID().toString(), title = "x", userId = user))
        assertThatThrownBy { service.chooseClaudeCode(requireNotNull(made.id)) }.isInstanceOf(ClaudeCodeUnavailableException::class.java)
        assertThat(chats.findById(requireNotNull(made.id)).get().answeredBy).isNotEqualTo(ANSWERED_BY_CLAUDE_CODE)
    }

    @Test
    fun `a workspace with no agent starts its chats on My Claude Code, where it is connected`() {
        connect()
        val started = service.start(workspaceId, user, "New chat", null)

        assertThat(started.answeredBy).isEqualTo(ANSWERED_BY_CLAUDE_CODE)
        assertThat(started.agentId).isNull()
    }

    @Test
    fun `an editor's assistant asks Claude Code about the page, and the reply is the answer`() {
        connect { command ->
            val args = argumentsOf(command)
            post("/api/claude/replies", mapOf("chat_id" to args["chat_id"], "text" to "Renamed the box."))
        }

        val answer = bridge.askAbout(user, workspaceId, "the diagram Plans", "/workspace/$workspaceId/diagrams/3", "rename the box")

        assertThat(answer).isEqualTo("Renamed the box.")
        val args = argumentsOf(received.single())
        assertThat(args["chat_id"] as String).startsWith("page-")
        assertThat(args["workspace_id"]).isEqualTo(workspaceId.toString())
        assertThat(args["text"] as String).contains("rename the box").contains("/workspace/$workspaceId/diagrams/3").contains("orknux_call")
    }

    @Test
    fun `a reply to somebody else's page turn is not taken`() {
        val stranger = mint("page-stranger-" + System.nanoTime())
        connect { command ->
            val place = argumentsOf(command)["chat_id"]
            assertThat(post("/api/claude/replies", mapOf("chat_id" to place, "text" to "mine now"), stranger).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            post("/api/claude/replies", mapOf("chat_id" to place, "text" to "the real answer"))
        }

        assertThat(bridge.askAbout(user, workspaceId, null, null, "hello")).isEqualTo("the real answer")
    }

    @Test
    fun `a message Claude Code never picks up is taken back, and the chat says so rather than still working`() {
        val chat = chat()
        // Connected - it asked a moment ago - but asking no more: busy, or its connection gone.
        client.get().uri("/api/claude/next?wait=0&client=ClaudeCode&version=0.1.0")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").retrieve().toBodilessEntity()
        assertThat(links.presence(user, DeviceKind.CLAUDE)).isNotNull

        val hangup = Hangup()
        val waiting = CompletableFuture.supplyAsync { bridge.ask(chat, "pull the extension", hangup) }
        Thread.sleep(300)
        hangup.hangUp()

        assertThat(waiting.get(5, TimeUnit.SECONDS)).contains(ClaudeCodeChats.NOT_TAKEN_STOPPED)
        val later = client.get().uri("/api/claude/next?wait=0&client=ClaudeCode&version=0.1.0")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").retrieve().toEntity(String::class.java)
        assertThat(later.body.orEmpty()).describedAs("nothing it never took arrives later, unasked").doesNotContain("pull the extension")
    }

    @Test
    fun `a message Claude Code did take is not taken back, and Stop says only that it stopped`() {
        val chat = chat()
        connect()
        val hangup = Hangup()
        val waiting = CompletableFuture.supplyAsync { bridge.ask(chat, "think hard", hangup) }
        val until = System.currentTimeMillis() + 5_000
        while (received.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        hangup.hangUp()

        assertThat(waiting.get(5, TimeUnit.SECONDS)).startsWith("Stopped waiting").doesNotContain(ClaudeCodeChats.NOT_TAKEN_STOPPED)
    }

    @Test
    fun `the queue gives back what was never taken, and only that`() {
        client.get().uri("/api/claude/next?wait=0&client=ClaudeCode&version=0.1.0")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").retrieve().toBodilessEntity()
        val left = requireNotNull(links.leave(user, DeviceKind.CLAUDE, "message", mapper.valueToTree(mapOf("text" to "x"))))
        assertThat(links.withdraw(user, DeviceKind.CLAUDE, left)).isTrue()
        assertThat(links.withdraw(user, DeviceKind.CLAUDE, left)).describedAs("gone once taken back").isFalse()
    }
}
