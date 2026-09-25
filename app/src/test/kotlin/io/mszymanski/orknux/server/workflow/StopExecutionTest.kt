package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.StepRunner
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.OffsetDateTime

/**
 * Stopping a running execution. Issue #395.
 *
 * The engine's own loop - read the flag, end the run - is a line, and what is
 * pinned here is the state it moves through: a request sets the flag on a run
 * that is still going and nowhere else, and ending a run by request leaves it
 * STOPPED, finished, and saying why. A stopped run is terminal; nothing here
 * resumes it.
 */
@SpringBootTest
class StopExecutionTest(
    @Autowired val runs: ExecutionService,
    @Autowired val steps: StepRunner,
    @Autowired val executions: WorkflowExecutionRepository,
) {

    private fun running(): Long = requireNotNull(
        executions.save(
            WorkflowExecution(
                workspaceId = 9,
                workflowId = 1,
                workflowName = "Nightly report",
                status = ExecutionStatus.RUNNING,
                trigger = ExecutionTrigger.WEBHOOK,
                startedAt = OffsetDateTime.now(),
            ),
        ).id,
    )

    @Test
    fun `a request sets the flag on a running run, and the engine reads it`() {
        val id = running()

        assertThat(steps.wasStopAsked(id)).isFalse()
        runs.requestStop(id)

        assertThat(requireNotNull(executions.findById(id).orElse(null)).stopRequested).isTrue()
        assertThat(steps.wasStopAsked(id)).isTrue()
    }

    @Test
    fun `stopping a run leaves it STOPPED, finished, and saying why`() {
        val id = running()

        val stopped = steps.stopRun(id)

        assertThat(stopped.status).isEqualTo(ExecutionStatus.STOPPED)
        assertThat(stopped.finishedAt).isNotNull()
        assertThat(stopped.stoppedReason).isEqualTo("stopped by request")
    }

    @Test
    fun `a run that has already ended is not stopped, and its flag is left alone`() {
        val id = running()
        // It finished on its own before anyone asked.
        val held = requireNotNull(executions.findById(id).orElse(null))
        held.status = ExecutionStatus.COMPLETED
        executions.save(held)

        runs.requestStop(id)

        val after = requireNotNull(executions.findById(id).orElse(null))
        assertThat(after.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(after.stopRequested).isFalse()
    }

    @Test
    fun `stopping a run that is no longer running changes nothing`() {
        val id = running()
        steps.stopRun(id)

        // A second stop - a duplicate request, a race - does not re-stamp it.
        val again = steps.stopRun(id)
        assertThat(again.status).isEqualTo(ExecutionStatus.STOPPED)
    }
}
