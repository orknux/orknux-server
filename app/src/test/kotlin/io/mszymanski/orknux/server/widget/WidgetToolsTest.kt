package io.mszymanski.orknux.server.widget

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.chat.ChatSession
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.chat.ChatTools
import io.mszymanski.orknux.server.chat.RoundWatch
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * A chat's widgets, as its agent makes and changes them with the widget tools
 * and as the person reaches them from the page.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WidgetToolsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chatTools: ChatTools,
    @Autowired val widgets: Widgets,
    @Autowired val rows: ChatWidgetRepository,
    @Autowired val chats: ChatSessionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: SessionEventRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId = 0L
    private lateinit var chat: ChatSession

    /** A clock the test moves: widgets keep moments, so time passing is the clock moving. */
    private var instant: Instant = Instant.parse("2026-10-11T12:00:00Z")

    @BeforeEach
    fun seed() {
        clean()
        widgets.clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = this@WidgetToolsTest.instant
        }
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "widgets")).id)
        chat = chatFor("alice")
    }

    @AfterEach
    fun clean() {
        widgets.clock = Clock.systemDefaultZone()
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("chat.widget") })
        rows.deleteAll()
        events.deleteAll()
        chats.deleteAll()
        sessions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun chatFor(user: String): ChatSession {
        val session = recorder.open(workspaceId, "chat", UUID.randomUUID().toString())
        return chats.save(
            ChatSession(workspaceId = workspaceId, conversationId = UUID.randomUUID().toString(), title = "Widgets", userId = user)
                .apply { llmSessionId = session },
        )
    }

    private val seen = mutableListOf<List<WidgetView>>()

    private fun call(name: String, arguments: Map<String, Any?> = emptyMap(), on: ChatSession = chat): JsonNode {
        val watch = object : RoundWatch {
            override fun widgets(widgets: List<WidgetView>) {
                seen += widgets
            }
        }
        return mapper.readTree(chatTools.shed(on, watch).run(ToolCall("call", name, mapper.writeValueAsString(arguments))))
    }

    private fun pass(seconds: Long) {
        instant = instant.plus(Duration.ofSeconds(seconds))
    }

    @Test
    fun `a chat lends the same widget tools every round, whatever it holds`() {
        val before = chatTools.shed(chat).specs().map { it.name }.filter { it.startsWith("widget_") }
        call(WidgetTools.TIMER_START, mapOf("label" to "tea", "seconds" to 180))
        val after = chatTools.shed(chat).specs().map { it.name }.filter { it.startsWith("widget_") }

        assertThat(before).containsExactlyInAnyOrderElementsOf(WidgetTools.NAMES)
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `a timer counts down from its start, pauses, resumes and resets`() {
        val made = call(WidgetTools.TIMER_START, mapOf("label" to "tea", "seconds" to 180))
        val id = made.path("id").asLong()
        assertThat(made.path("remainingSeconds").asLong()).isEqualTo(180)
        assertThat(made.path("running").asBoolean()).isTrue()

        pass(60)
        assertThat(call(WidgetTools.LIST).path("widgets")[0].path("remainingSeconds").asLong()).isEqualTo(120)

        val paused = call(WidgetTools.PAUSE_TOOL, mapOf("id" to id))
        assertThat(paused.path("running").asBoolean()).isFalse()
        pass(500)
        assertThat(call(WidgetTools.LIST).path("widgets")[0].path("remainingSeconds").asLong()).isEqualTo(120)

        call(WidgetTools.RESUME_TOOL, mapOf("id" to id))
        pass(120)
        val ended = call(WidgetTools.LIST).path("widgets")[0]
        assertThat(ended.path("remainingSeconds").asLong()).isZero()
        assertThat(ended.path("done").asBoolean()).isTrue()

        val reset = call(WidgetTools.RESET_TOOL, mapOf("id" to id))
        assertThat(reset.path("remainingSeconds").asLong()).isEqualTo(180)
        assertThat(reset.path("running").asBoolean()).isFalse()
        // Every change is told to whoever is watching the round, as the whole list.
        assertThat(seen).isNotEmpty
        assertThat(seen.last().single().id).isEqualTo(id)
    }

    @Test
    fun `a stopwatch counts up, keeps its laps, pauses and resets`() {
        val id = call(WidgetTools.STOPWATCH_START, mapOf("label" to "build")).path("id").asLong()
        pass(30)
        call(WidgetTools.LAP_TOOL, mapOf("id" to id))
        pass(15)
        call(WidgetTools.PAUSE_TOOL, mapOf("id" to id))
        pass(100)
        val held = call(WidgetTools.LIST).path("widgets")[0]
        assertThat(held.path("elapsedMillis").asLong()).isEqualTo(45_000)
        assertThat(held.path("laps").size()).isEqualTo(1)
        assertThat(held.path("laps")[0].asLong()).isEqualTo(30_000L)

        val reset = call(WidgetTools.RESET_TOOL, mapOf("id" to id))
        assertThat(reset.path("elapsedMillis").asLong()).isZero()
        assertThat(reset.path("laps").size()).isZero()
        assertThat(call(WidgetTools.LAP_TOOL, mapOf("id" to call(WidgetTools.TIMER_START, mapOf("label" to "t", "seconds" to 60)).path("id").asLong())).path("error").stringValue())
            .contains("timer")
    }

    @Test
    fun `a timer the agent asked to be woken by leaves an inbox entry for when it ends, called off if it stops first`() {
        val id = call(WidgetTools.TIMER_START, mapOf("label" to "tea", "seconds" to 180, "wake" to true)).path("id").asLong()
        val left = events.findAll().filter { it.sessionId == chat.llmSessionId && it.kind == SessionEventKind.TIMER }
        assertThat(left).hasSize(1)
        assertThat(left.single().body).isEqualTo("Timer 'tea' finished.")
        assertThat(left.single().dueAt.toInstant()).isEqualTo(instant.plusSeconds(180))

        call(WidgetTools.PAUSE_TOOL, mapOf("id" to id))
        assertThat(events.findAll().filter { it.kind == SessionEventKind.TIMER }).isEmpty()

        call(WidgetTools.RESUME_TOOL, mapOf("id" to id))
        assertThat(events.findAll().filter { it.kind == SessionEventKind.TIMER }).hasSize(1)
        call(WidgetTools.REMOVE_TOOL, mapOf("id" to id))
        assertThat(events.findAll().filter { it.kind == SessionEventKind.TIMER }).isEmpty()
        assertThat(rows.findAll()).isEmpty()
    }

    @Test
    fun `the limits are settings, and a call past them is refused`() {
        settings.setWidgetsPerChat(2, "alice")
        settings.setWidgetTimerMaxSeconds(600, "alice")
        call(WidgetTools.STOPWATCH_START, mapOf("label" to "a"))
        call(WidgetTools.STOPWATCH_START, mapOf("label" to "b"))

        assertThat(call(WidgetTools.STOPWATCH_START, mapOf("label" to "c")).path("error").stringValue()).contains("2 widgets")
        rows.deleteAll()
        assertThat(call(WidgetTools.TIMER_START, mapOf("label" to "long", "seconds" to 601)).path("error").stringValue()).contains("600 seconds")
        assertThat(call(WidgetTools.TIMER_START, mapOf("label" to "", "seconds" to 60)).path("error").stringValue()).contains("label")
    }

    @Test
    fun `a widget in another chat is not this chat's to touch`() {
        val other = chatFor("alice")
        val id = call(WidgetTools.STOPWATCH_START, mapOf("label" to "theirs"), on = other).path("id").asLong()

        assertThat(call(WidgetTools.PAUSE_TOOL, mapOf("id" to id)).path("error").stringValue()).contains("no widget $id")
        assertThat(call(WidgetTools.LIST).path("widgets").size()).isZero()
    }

    @Test
    fun `the person reads and changes the widgets of their own chat, and no one else's`() {
        val id = call(WidgetTools.TIMER_START, mapOf("label" to "tea", "seconds" to 180)).path("id").asLong()

        graphQlTester.document("{ chatWidgets(chatId: ${chat.id}) { id label kind running remainingSeconds } }").execute()
            .path("chatWidgets[0].label").entity(String::class.java).isEqualTo("tea")
        graphQlTester.document("""mutation { actOnChatWidget(id: $id, action: "pause") { running } }""").execute()
            .path("actOnChatWidget.running").entity(Boolean::class.java).isEqualTo(false)
        assertThat(audit.findAll().map { it.message }).anyMatch { it.contains("Widget 'tea'") && it.contains("pause") }

        graphQlTester.document("mutation { removeChatWidget(id: $id) }").execute()
            .path("removeChatWidget").entity(Boolean::class.java).isEqualTo(true)
        assertThat(rows.findAll()).isEmpty()

        val bob = chatFor("bob")
        val theirs = call(WidgetTools.STOPWATCH_START, mapOf("label" to "bob's"), on = bob).path("id").asLong()
        graphQlTester.document("""mutation { actOnChatWidget(id: $theirs, action: "pause") { running } }""").execute()
            .errors().satisfy { errors -> assertThat(errors.single().extensions["code"]).isEqualTo("ChatWidgetNotFound") }
    }

    @Test
    fun `deleting a chat takes its widgets with it`() {
        call(WidgetTools.STOPWATCH_START, mapOf("label" to "gone soon"))
        assertThat(rows.findAll()).hasSize(1)

        chats.delete(chat)

        assertThat(rows.findAll()).isEmpty()
    }
}
