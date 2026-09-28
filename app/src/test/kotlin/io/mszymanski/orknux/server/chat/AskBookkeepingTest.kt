package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
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
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * What session 554 showed getting wrong about asking other agents.
 *
 * - A five-minute backoff ended after twenty seconds: an answer read mid-turn
 *   also woke the run, and the wake it left cut the next wait short. Only a run
 *   parked on the session - a WAITING step writing into it - is woken now.
 * - `agent_wait` took `"seconds": "300"` for its thirty-second default.
 * - `agent_asks` called every asked agent "an agent", and after a restart
 *   reported failed asks as answered with their half-finished first line.
 */
@SpringBootTest
class AskBookkeepingTest(
    @Autowired val runTools: AgentRunTools,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val sessions: io.mszymanski.orknux.server.llm.LlmSessionRepository,
) {

    @Test
    fun `only a step waiting on the session is one something arriving wakes`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "wake-${System.nanoTime()}")).id)
        val session = recorder.open(workspace, "node", "one")
        val run = requireNotNull(
            executions.save(
                WorkflowExecution(
                    workspaceId = workspace, workflowId = 1, workflowName = "Answer", status = ExecutionStatus.RUNNING,
                    trigger = ExecutionTrigger.API, startedAt = OffsetDateTime.now(), input = "{}",
                ),
            ).id,
        )
        val step = steps.save(
            ExecutionStep(
                executionId = run, nodeKey = "think", kind = NodeKind.AGENT, name = "think", order = 0,
                x = 0.0, y = 0.0, status = StepStatus.RUNNING, startedAt = OffsetDateTime.now(),
            ).apply { sessionId = session },
        )

        // Mid-turn: the turn reads the inbox itself, so nothing to wake.
        assertThat(steps.executionIdsWaitingOnSession(session)).isEmpty()

        step.status = StepStatus.WAITING
        steps.save(step)
        assertThat(steps.executionIdsWaitingOnSession(session)).containsExactly(run)
    }

    @Test
    fun `agent_wait takes its seconds however the model wrote them`() {
        assertThat(runTools.waitSecondsIn("""{"seconds":"300"}""")).isEqualTo(300)
        assertThat(runTools.waitSecondsIn("""{"seconds":120}""")).isEqualTo(120)
        assertThat(runTools.waitSecondsIn("""{}""")).isEqualTo(AgentRunTools.SOME_WAIT_SECONDS)
    }

    @Test
    fun `agent_asks names the agent, and reads a failed ask as failed after a restart`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "asks-${System.nanoTime()}")).id)
        val asker = agents.save(Agent(workspaceId = workspace, name = "Responder", type = AgentType.LLM))
        val parent = recorder.open(workspace, "node", "asks")
        val child = recorder.openUnder(parent, "Issue analysis")
        recorder.describeAgent(child, """{"agent":"AzureAgent","agentId":3,"model":"deepseek"}""")
        // What the ask left in its own conversation: a first line, then the failure.
        recorder.agentSaid(child, "AzureAgent", "I'll start by gathering the open issues.")
        recorder.note(child, "AzureAgent could not answer: 429: rate limit")

        // Nothing held in memory for it, and its last line long ago - the state a restart leaves.
        sessions.findById(child).orElseThrow().let { held ->
            held.lastEventAt = OffsetDateTime.now().minusHours(1)
            sessions.save(held)
        }
        val asks = mapper.readTree(runTools.asked(asker, parent)).path("asks")
        val one = asks.first()
        assertThat(one.path("asked").stringValue()).isEqualTo("AzureAgent")
        assertThat(one.path("error").stringValue()).contains("429")
        assertThat(one.has("answer")).isFalse()
    }
}
