package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * The session page's Timers panel: the reminders an agent set with `timer_set`,
 * read back through the GraphQL the browser calls, waiting and rung alike.
 * Set through the agent's own tool, so what is read back is what an agent
 * actually leaves behind.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionTimerAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val timers: TimerTools,
    @Autowired val inbox: SessionInbox,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var session: Long = 0

    @BeforeEach
    fun make() {
        val workspaceId = requireNotNull(
            (workspaces.findByName("timer-api") ?: workspaces.save(Workspace(name = "timer-api"))).id,
        )
        session = recorder.open(workspaceId, "test", "timer-api-${System.nanoTime()}")
    }

    @Test
    fun `a session lists its timers newest first, waiting and rung, by the note it was set with`() {
        inbox.post(session, SessionEventKind.TIMER, "The reminder you set 1 seconds ago is up: say hi", OffsetDateTime.now().minusSeconds(1))
        inbox.take(session)
        val answer = requireNotNull(timers.shed(session)).run(ToolCall("t", TimerTools.SET, """{"seconds":600,"note":"check the build"}"""))
        assertThat(answer).doesNotContain("\"error\"")

        graphQlTester
            .document("query { sessionTimers(sessionId: $session) { id note createdAt dueAt rangAt } }")
            .execute()
            .path("sessionTimers[*].note").entityList(String::class.java).containsExactly("check the build", "say hi")
            .path("sessionTimers[0].rangAt").valueIsNull()
            .path("sessionTimers[1].rangAt").hasValue()
    }

    @Test
    fun `a session that set none has an empty list, and an unknown one is refused`() {
        graphQlTester
            .document("query { sessionTimers(sessionId: $session) { id } }")
            .execute()
            .path("sessionTimers").entityList(Any::class.java).hasSize(0)

        graphQlTester
            .document("query { sessionTimers(sessionId: 987654321) { id } }")
            .execute()
            .errors().expect { it.message?.contains("987654321") == true }
    }
}
