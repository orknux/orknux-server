package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
import java.time.OffsetDateTime

/**
 * An asked agent's turn is asked again by the retry policy of the step it was
 * asked from.
 *
 * Reported from a Slack thread's subagent: "Summarizer could not answer: The
 * provider stopped part way: Your requests to gpt-5.6-luna ... have exceeded"
 * - Azure's rate limit inside a stream - and nothing asked again, though the
 * node had retries. They were the node's, and what failed was a turn the
 * node's agent had asked for.
 */
@SpringBootTest
class AskRetryTest(
    @Autowired val runTools: AgentRunTools,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val lines: LlmSessionEventRepository,
) {

    private val rateLimited = ChatCompletion.Failed("The provider stopped part way: rate limit", permanent = false)
    private val answered = ChatCompletion.Answered("Summary ready.", 10)

    /** A workflow step in [session] with [attempts] on its policy. */
    private fun stepIn(session: Long, attempts: Int?) {
        val workspace = requireNotNull(workspaces.findAll().first().id)
        val run = requireNotNull(
            executions.save(
                WorkflowExecution(
                    workspaceId = workspace, workflowId = 1, workflowName = "Answer", status = ExecutionStatus.RUNNING,
                    trigger = ExecutionTrigger.API, startedAt = OffsetDateTime.now(), input = "{}",
                ),
            ).id,
        )
        steps.save(
            ExecutionStep(
                executionId = run, nodeKey = "think", kind = NodeKind.AGENT, name = "think", order = 0,
                x = 0.0, y = 0.0, status = StepStatus.RUNNING, startedAt = OffsetDateTime.now(),
                retryAttempts = attempts, retryBackoffSeconds = 30,
            ).apply { sessionId = session },
        )
    }

    private fun sessions(): Pair<Long, Long> {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "ask-retry-${System.nanoTime()}")).id)
        val parent = recorder.open(workspace, "slack_thread", "t-${System.nanoTime()}")
        return parent to recorder.openUnder(parent, "Summarize")
    }

    /** Runs [answers] in order, one per attempt, without really waiting. */
    private fun ask(parent: Long, child: Long, vararg answers: ChatCompletion): Pair<ChatCompletion, List<Duration>> {
        val queue = ArrayDeque(answers.toList())
        val waited = mutableListOf<Duration>()
        val said = runTools.retrying(
            parent, child,
            first = { queue.removeFirst() },
            again = { queue.removeFirst() },
            pause = { waited += it },
        )
        return said to waited
    }

    @Test
    fun `a rate-limited subagent is asked again by the node's policy, and the wait is written in its session`() {
        val (parent, child) = sessions()
        stepIn(parent, attempts = 3)

        val (said, waited) = ask(parent, child, rateLimited, answered)

        assertThat(said).isEqualTo(answered)
        assertThat(waited).containsExactly(Duration.ofSeconds(30))
        assertThat(lines.findAll().filter { it.sessionId == child }.mapNotNull { it.content })
            .anyMatch { "attempt 2 of 3" in it && "rate limit" in it }
    }

    @Test
    fun `it gives up after the policy's attempts`() {
        val (parent, child) = sessions()
        stepIn(parent, attempts = 2)

        val (said, waited) = ask(parent, child, rateLimited, rateLimited, answered)

        assertThat(said).isEqualTo(rateLimited)
        assertThat(waited).hasSize(1)
    }

    @Test
    fun `a permanent failure is never asked again`() {
        val (parent, child) = sessions()
        stepIn(parent, attempts = 3)
        val refused = ChatCompletion.Failed("400: bad request", permanent = true)

        assertThat(ask(parent, child, refused, answered)).isEqualTo(refused to emptyList<Duration>())
    }

    /** A chat or a task has no node and no policy: asked once, as its own turn is. */
    @Test
    fun `an ask from a conversation with no step is asked once`() {
        val (parent, child) = sessions()

        assertThat(ask(parent, child, rateLimited, answered).first).isEqualTo(rateLimited)
    }

    /** A step with no retries set is the same: nothing to retry by. */
    @Test
    fun `an ask from a step with no retries is asked once`() {
        val (parent, child) = sessions()
        stepIn(parent, attempts = null)

        assertThat(ask(parent, child, rateLimited, answered).first).isEqualTo(rateLimited)
    }
}
