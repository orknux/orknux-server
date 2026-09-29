package io.mszymanski.orknux.workflow.temporal

import io.mszymanski.orknux.workflow.execution.ExecutionEngine
import io.mszymanski.orknux.workflow.execution.ExecutionPlanner
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.ResumePoint
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Hands the run to Temporal and answers as soon as it is accepted.
 *
 * The run is planned here, while the caller waits, so a workflow that cannot be
 * read or ordered is still a rejected request. What Temporal is given is the
 * durable part: carrying the steps out, retrying the ones that fail for a
 * reason worth retrying, and finishing the run on whichever worker is up when
 * the time comes.
 */
@Service
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "true", matchIfMissing = true)
class TemporalExecutionEngine(
    private val planner: ExecutionPlanner,
    private val client: WorkflowClient,
    private val properties: TemporalProperties,
) : ExecutionEngine {

    override fun start(
        workspaceId: Long,
        workflowId: Long,
        trigger: ExecutionTrigger,
        input: String?,
        version: GraphVersion?,
        resumeFrom: ResumePoint?,
        startedFrom: Long?,
        firedTriggerId: Long?,
    ): WorkflowExecution {
        val plan = planner.plan(
            workspaceId, workflowId, trigger, input, version, resumeFrom, startedFrom, firedTriggerId,
        )
        val executionId = requireNotNull(plan.execution.id)

        val workflow = client.newWorkflowStub(
            ExecutionWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(properties.taskQueue)
                // One workflow per recorded run, which also means asking twice
                // for the same run cannot start it twice.
                .setWorkflowId(temporalWorkflowId(executionId))
                .setWorkflowExecutionTimeout(Duration.ofHours(properties.runTimeoutHours))
                .build(),
        )

        // Started, not awaited: a run outlives the request that asked for it.
        WorkflowClient.start(workflow::run, RunPlan(
            executionId = executionId,
            workflowName = plan.execution.workflowName,
            steps = plan.steps.map { it.nodeKey },
            input = input,
            edges = plan.edges.map { PlanEdge(it.source, it.target, it.branch, it.option) },
            carried = plan.carried.map { PlanExit(it.nodeKey, it.branch, it.option) },
            blocked = plan.blocked.toList(),
        ))

        return plan.execution
    }

    /** A signal to the run's workflow, which cuts its step's wait short. Nothing to wake is not a fault. */
    override fun wake(executionId: Long) {
        runCatching {
            client.newUntypedWorkflowStub(temporalWorkflowId(executionId)).signal("wake")
        }.onFailure { log.debug("Execution {} had nothing to wake: {}", executionId, it.message) }
    }

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
}

/**
 * What Temporal calls the workflow that runs one recorded execution.
 *
 * Derived rather than stored, and shared, because two things need to agree on
 * it: the engine that starts the run, and whatever wants to link to it.
 */
fun temporalWorkflowId(executionId: Long): String = "orknux-execution-$executionId"
