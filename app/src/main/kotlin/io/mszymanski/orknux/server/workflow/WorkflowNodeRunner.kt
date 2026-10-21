package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.workflow.execution.ExecutionEngine
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.KIND_RUNNER_ORDER
import io.mszymanski.orknux.workflow.execution.LogLevel
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.RunEnded
import io.mszymanski.orknux.workflow.execution.RunLogger
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import io.mszymanski.orknux.workflow.execution.StepFailedException
import io.mszymanski.orknux.workflow.execution.StepParked
import io.mszymanski.orknux.workflow.execution.StepResult
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Runs a Workflow node: starts another workflow of the workspace, waits for it
 * to end, and hands on what it produced.
 *
 * The called run is handed the node's parameters as an object, one field per
 * parameter, and with none, whatever reached the node. It records this run as
 * its caller ([WorkflowExecution.parentExecutionId]) and is stored as an API
 * run, so an older server reading it after a rollback reads a value it knows.
 *
 * Which version it runs follows the caller: a run of a draft - somebody trying
 * a chain by hand - runs the called workflow's draft too, and every other run
 * runs what was published.
 *
 * The run it started is written on the step before the step waits, so asking
 * the step again - when the called run ends and wakes it, or when the wait set
 * in Admin Settings runs out - looks at that run rather than starting another.
 * A called run that failed or was stopped fails the step with its words.
 *
 * A workflow already running further up the chain of callers is refused when
 * it would be called again: two workflows calling each other would otherwise
 * start runs until something ran out. Calling itself directly is refused when
 * the graph is saved.
 */
@Component
@Order(KIND_RUNNER_ORDER)
class WorkflowNodeRunner(
    private val executions: WorkflowExecutionRepository,
    /** Lazily: the service's engine is handed every runner, this one among them. */
    @param:org.springframework.context.annotation.Lazy private val service: ExecutionService,
    private val assignments: WorkspaceWorkflowRepository,
    private val expressions: NodeExpressions,
    private val mapper: ObjectMapper,
    private val settings: InstallationSettings,
    private val runLog: RunLogger,
) : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.WORKFLOW

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult {
        val calledId = step.calledWorkflowId
            ?: return StepResult(StepStatus.SKIPPED, "${step.name} names no workflow, so nothing was run.")
        val caller = executions.findByIdOrNull(step.executionId)
            ?: throw StepFailedException(step.nodeKey, "${step.name} cannot find the run it belongs to", permanent = true)

        val childId = step.childExecutionId ?: start(step, caller, calledId, input, trigger)
        val child = executions.findByIdOrNull(childId)
            ?: throw StepFailedException(step.nodeKey, "${step.name}: the run it started, #$childId, is gone", permanent = true)

        return when (child.status) {
            ExecutionStatus.RUNNING -> {
                val wait = Duration.ofSeconds(settings.workflowCallCheckSeconds().toLong())
                step.waitUntil = OffsetDateTime.now().plus(wait)
                StepResult.waiting(wait, "${step.name} is waiting for ${child.workflowName} (run #$childId)")
            }
            ExecutionStatus.COMPLETED -> StepResult(
                StepStatus.COMPLETED,
                expressions.alongsideJson(step.outputName, child.carried ?: child.input ?: "{}", input),
            )
            ExecutionStatus.FAILED -> throw StepFailedException(
                step.nodeKey,
                "${child.workflowName} (run #$childId) failed: ${child.error ?: "it gave no reason"}",
            )
            ExecutionStatus.STOPPED -> throw StepFailedException(
                step.nodeKey,
                "${child.workflowName} (run #$childId) was stopped",
                permanent = true,
            )
        }
    }

    /** Starts the called run and writes it on the step; its id. */
    private fun start(step: ExecutionStep, caller: WorkflowExecution, calledId: Long, input: String?, trigger: String?): Long {
        val called = assignments.findByWorkspaceIdAndWorkflowId(caller.workspaceId, calledId)
            ?: throw StepFailedException(
                step.nodeKey,
                "${step.name} runs a workflow this workspace no longer has (id $calledId)",
                permanent = true,
            )
        val chain = chainOf(caller)
        if (calledId in chain.map { it.workflowId }) {
            val names = (chain.reversed().map { it.workflowName } + called.workflow.name).joinToString(" → ")
            throw StepFailedException(
                step.nodeKey,
                "${called.workflow.name} is already running in this chain ($names); a workflow cannot call itself",
                permanent = true,
            )
        }

        val started = try {
            service.startExecution(
                StartExecutionInput(
                    workspaceId = caller.workspaceId,
                    workflowId = calledId,
                    trigger = ExecutionTrigger.API,
                    payload = payloadOf(step, input, trigger),
                    version = if (caller.ranDraft == true) GraphVersion.DRAFT else GraphVersion.PUBLISHED,
                    parentExecutionId = caller.id,
                ),
            )
        } catch (refused: StepFailedException) {
            throw refused
        } catch (refused: RuntimeException) {
            throw StepFailedException(
                step.nodeKey,
                "${called.workflow.name} could not be started: ${refused.message ?: refused.javaClass.simpleName}",
                permanent = true,
            )
        }
        step.childExecutionId = started.id
        runLog.write(step.executionId, step.nodeKey, LogLevel.INFO, "${step.name} started ${called.workflow.name} (run #${started.id})")
        return started.id
    }

    /** [caller] and every run above it, nearest first. */
    private fun chainOf(caller: WorkflowExecution): List<WorkflowExecution> {
        val chain = mutableListOf(caller)
        var at = caller.parentExecutionId
        while (at != null && chain.none { it.id == at }) {
            val parent = executions.findByIdOrNull(at) ?: break
            chain += parent
            at = parent.parentExecutionId
        }
        return chain
    }

    /**
     * What the called run is handed: one field per parameter, read the way an
     * action's argument is - a reference to an object stays an object - or,
     * with none, whatever reached the node.
     */
    private fun payloadOf(step: ExecutionStep, input: String?, trigger: String?): String? {
        val bindings = expressions.mappingsOf(step).filterValues { it.expression.isNotBlank() }
        if (bindings.isEmpty()) return input
        val given = expressions.parse(input)
        val started = expressions.parse(trigger)
        val payload = mapper.createObjectNode()
        bindings.forEach { (name, binding) ->
            if (binding.reference) {
                val read = runCatching { mapper.readTree(expressions.jsonOf(binding, given, started)) }.getOrNull()
                payload.set(name, read ?: mapper.nullNode())
            } else {
                payload.put(name, expressions.textOf(binding, given, started))
            }
        }
        return mapper.writeValueAsString(payload)
    }
}

/**
 * Wakes a Workflow node when the run it called ends, rather than leaving it to
 * wait out its timer; and stops a called run when its caller is stopped.
 */
@Component
class CalledRunWake(
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val engine: ExecutionEngine,
    private val service: ExecutionService,
) {

    @EventListener
    fun ended(event: RunEnded) {
        val run = executions.findByIdOrNull(event.executionId) ?: return
        run.parentExecutionId?.let(engine::wake)
        if (run.status == ExecutionStatus.STOPPED) {
            executions.findByParentExecutionIdAndStatus(event.executionId, ExecutionStatus.RUNNING)
                .forEach { called -> called.id?.let(service::requestStop) }
        }
    }

    /**
     * A called run that ended between the node starting it and the node
     * parking found nobody waiting to wake. Told the node has parked, look
     * again.
     */
    @EventListener
    fun parked(event: StepParked) {
        val step = steps.findByExecutionIdAndNodeKey(event.executionId, event.nodeKey) ?: return
        val child = step.childExecutionId?.let { executions.findByIdOrNull(it) } ?: return
        if (child.status != ExecutionStatus.RUNNING) engine.wake(event.executionId)
    }
}
