package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.ParameterizedTypeReference
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * A session read back to the runs that wrote it. Issue #420.
 *
 * A step records which session its agent talked into; this reads that the other
 * way round, so a session's page can open the run that produced it. The one
 * behaviour worth pinning down is that "several runs, one session" is real - two
 * runs that computed the same key are two links - and its opposite, that a
 * session nothing wrote into (a chat) draws no control at all.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionExecutionsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        steps.deleteAll()
        executions.deleteAll()
        events.deleteAll()
        sessions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /**
     * The ordinary case: one run wrote into the session, and the page finds it.
     *
     * The link carries what tells one run from another - the workflow's name,
     * when it started, where it got to - and the id to open it.
     */
    @Test
    fun `a session names the run whose step wrote into it`() {
        val session = recorder.open(workspaceId, "issue", "42")
        val run = run("Incident Response", ExecutionStatus.COMPLETED, OffsetDateTime.now())
        step(run, session)

        val links = links(session)
        assertThat(links).hasSize(1)
        assertThat(links.single()["id"]).isEqualTo(run.toString())
        assertThat(links.single()["workflowName"]).isEqualTo("Incident Response")
        assertThat(links.single()["status"]).isEqualTo("COMPLETED")
    }

    /**
     * The case the feature exists for: several runs computed one session key, so
     * the session lists all of them, newest first.
     */
    @Test
    fun `several runs that wrote into one session are all listed, newest first`() {
        val session = recorder.open(workspaceId, "issue", "42")
        val older = run("First pass", ExecutionStatus.COMPLETED, OffsetDateTime.now().minusHours(2))
        val newer = run("Second pass", ExecutionStatus.FAILED, OffsetDateTime.now())
        step(older, session)
        step(newer, session)

        assertThat(links(session).map { it["id"] }).containsExactly(newer.toString(), older.toString())
    }

    /**
     * A run that touched the session in more than one step is still one link,
     * not one per step.
     */
    @Test
    fun `a run that wrote in two steps is listed once`() {
        val session = recorder.open(workspaceId, "issue", "42")
        val run = run("Incident Response", ExecutionStatus.COMPLETED, OffsetDateTime.now())
        step(run, session, nodeKey = "first")
        step(run, session, nodeKey = "second")

        assertThat(links(session)).hasSize(1)
    }

    /**
     * A session nothing wrote into - a chat, say - lists no runs, so the page
     * draws no control rather than an empty one.
     */
    @Test
    fun `a session no run wrote into lists nothing`() {
        val chat = recorder.open(workspaceId, null, "chat-7")
        // A run in the workspace, but its step names a different session.
        val other = recorder.open(workspaceId, "issue", "42")
        step(run("Elsewhere", ExecutionStatus.COMPLETED, OffsetDateTime.now()), other)

        assertThat(links(chat)).isEmpty()
    }

    private fun run(name: String, status: ExecutionStatus, startedAt: OffsetDateTime): Long =
        requireNotNull(
            executions.save(
                WorkflowExecution(
                    workspaceId = workspaceId,
                    workflowId = 1,
                    workflowName = name,
                    status = status,
                    trigger = ExecutionTrigger.WEBHOOK,
                    startedAt = startedAt,
                ),
            ).id,
        )

    private fun step(executionId: Long, sessionId: Long, nodeKey: String = "think") {
        steps.save(
            ExecutionStep(
                executionId = executionId,
                nodeKey = nodeKey,
                kind = NodeKind.AGENT,
                name = "Ask reviewer",
                x = 0.0,
                y = 0.0,
                order = 0,
                sessionId = sessionId,
            ),
        )
    }

    private fun links(sessionId: Long): List<Map<String, Any?>> =
        graphQlTester.document(
            """{ sessionExecutions(sessionId: $sessionId) { id workflowName startedAt status } }""",
        ).execute()
            .path("sessionExecutions")
            .entity(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
            .get()
}
