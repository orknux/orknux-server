package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.chat.TimerTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.boot.test.context.TestConfiguration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What arrives at a session, and how it is read.
 *
 * An event is read once, in the order it came due, and written into the
 * transcript as said to the agent; one not yet due is not read and not
 * announced; the sweep announces a reminder once, as it comes due, and not again
 * every tick after. And `timer_set` refuses what it cannot keep.
 */
@SpringBootTest
@Import(SessionInboxTest.Heard::class)
class SessionInboxTest(
    @Autowired val inbox: SessionInbox,
    @Autowired val events: SessionEventRepository,
    @Autowired val sweeper: SessionDueSweeper,
    @Autowired val timers: TimerTools,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val heard: CopyOnWriteArrayList<Long>,
) {

    /** Every session announced as due, in order. */
    @TestConfiguration
    class Heard {
        @Bean
        fun heard() = CopyOnWriteArrayList<Long>()

        @Bean
        fun listener(heard: CopyOnWriteArrayList<Long>) = Listener(heard)
    }

    class Listener(private val heard: CopyOnWriteArrayList<Long>) {
        @org.springframework.context.event.EventListener
        fun due(event: SessionEventDue) {
            heard += event.sessionId
        }
    }

    private var session: Long = 0

    @BeforeEach
    fun reset() {
        events.deleteAll()
        lines.deleteAll()
        sessions.deleteAll()
        workspaces.deleteAll()
        heard.clear()
        val workspace = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        session = recorder.open(workspace, "node", "one")
        sweeper.sweep() // Forget whatever an earlier class left due.
        heard.clear()
    }

    @Test
    fun `an answer is read once, in order, and written down as said to the agent`() {
        inbox.post(session, SessionEventKind.ANSWER, "first")
        inbox.post(session, SessionEventKind.ANSWER, "second")
        assertThat(heard).containsExactly(session, session)

        assertThat(inbox.take(session)).containsExactly("first", "second")
        assertThat(inbox.take(session)).isEmpty()
        assertThat(inbox.pending(session)).isFalse()
        assertThat(lines.findAll().filter { it.sessionId == session }.map { it.content })
            .contains("first", "second")
    }

    @Test
    fun `a reminder not yet due is pending, not read, and not announced`() {
        val later = OffsetDateTime.now().plusMinutes(10)
        inbox.post(session, SessionEventKind.TIMER, "check the build", later)

        assertThat(heard).isEmpty()
        assertThat(inbox.take(session)).isEmpty()
        assertThat(inbox.pending(session)).isTrue()
        assertThat(requireNotNull(inbox.nextDue(session)).toInstant()).isCloseTo(later.toInstant(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS))
    }

    @Test
    fun `the sweep announces a reminder once, as it comes due`() {
        events.save(
            SessionEvent(
                sessionId = session, kind = SessionEventKind.TIMER, body = "check the build",
                dueAt = OffsetDateTime.now().plusSeconds(1),
            ),
        )
        assertThat(sweeper.sweep()).isZero()
        Thread.sleep(1200)

        assertThat(sweeper.sweep()).isEqualTo(1)
        assertThat(heard).containsExactly(session)
        // Not again: it was announced as it crossed, and is still unread.
        assertThat(sweeper.sweep()).isZero()
        assertThat(inbox.take(session)).containsExactly("check the build")
    }

    @Test
    fun `timer_set posts a reminder, and refuses what it cannot keep`() {
        val shed = requireNotNull(timers.shed(session))
        val set = shed.run(ToolCall("1", TimerTools.SET, """{"seconds":30,"note":"see if CI went green"}"""))
        assertThat(set).contains("\"set\":true")
        assertThat(inbox.pending(session)).isTrue()
        assertThat(events.findAll().single().body).contains("see if CI went green")

        assertThat(shed.run(ToolCall("2", TimerTools.SET, """{"seconds":0,"note":"x"}"""))).contains("greater than zero")
        val tooFar = settings.agentSleepSeconds() + 1
        assertThat(shed.run(ToolCall("3", TimerTools.SET, """{"seconds":$tooFar,"note":"x"}"""))).contains("at most")
        assertThat(shed.run(ToolCall("4", TimerTools.SET, """{"seconds":5}"""))).contains("what the reminder is for")
        // No session, nowhere to deliver: not offered at all.
        assertThat(timers.shed(null)).isNull()
    }
}
