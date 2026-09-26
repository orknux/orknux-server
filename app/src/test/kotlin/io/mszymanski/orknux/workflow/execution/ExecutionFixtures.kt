package io.mszymanski.orknux.workflow.execution

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A graph the tests hand over directly, instead of one read from workflow rows.
 * What the engine does with a graph is this module's business; where the graph
 * came from is the app's.
 */
class FakeWorkflowGraphSource : WorkflowGraphSource {

    val graphs = mutableMapOf<Long, WorkflowGraph>()

    /** One graph per workflow: a fake has no draft to tell from a publication. */
    override fun graph(workspaceId: Long, workflowId: Long, version: GraphVersion): WorkflowGraph =
        graphs[workflowId] ?: throw WorkflowNotFoundException(workspaceId, workflowId)
}

/**
 * A runner for the tests to steer: nodes named `ok…` do work and hand something
 * on, `wait…` parks for an hour the first time and is done the second, `boom`
 * fails, `flaky…` fails until it is on its third attempt, `settled…` fails in a
 * way that says trying again is pointless, `asks-yes…` / `asks-no…` answer the
 * way a condition does, and `block…` / `sleep…` stand in for a step stuck in
 * a model call - see [Blocking]. Ahead of [UnimplementedNodeRunner], which
 * claims everything.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
class ScriptedNodeRunner : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = true

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult = when {
        step.name == "boom" -> throw IllegalStateException("boom has no answer")
        // A step in the middle of a call it cannot finish on its own; see Blocking.
        step.name.startsWith("block") -> Blocking.untilHungUp(step)
        step.name.startsWith("sleep") -> Blocking.untilInterrupted(step)
        /*
         * Something that would work if it were asked again: a network that
         * dropped rather than a channel that does not exist. The count is read
         * off the step, which is where a retry policy keeps it, so the same
         * node behaves the same however many workers carry it.
         */
        step.name.startsWith("flaky") ->
            if (step.attempts < FLAKY_UNTIL) {
                throw IllegalStateException("${step.name} could not reach anything on attempt ${step.attempts}")
            } else {
                StepResult(StepStatus.COMPLETED, "${step.name} did the work on attempt ${step.attempts}")
            }
        // A failure the runner has already called final; a policy must not
        // spend attempts on it.
        step.name.startsWith("settled") ->
            throw StepFailedException(step.nodeKey, "${step.name} will never work", permanent = true)
        // What ConditionNodeRunner reports for a condition that holds and one
        // that does not, including the halt on a no: how the graph is drawn
        // decides whether that is a fork or an ending, not the runner.
        step.name.startsWith("asks-yes") ->
            StepResult(StepStatus.COMPLETED, "${step.name} holds", branch = EdgeBranch.YES)
        step.name.startsWith("asks-no") ->
            StepResult(StepStatus.COMPLETED, "${step.name} did not hold", halt = true, branch = EdgeBranch.NO)
        step.name.startsWith("ok") -> StepResult(StepStatus.COMPLETED, "${step.name} did the work")
        step.name.startsWith("wait") -> park(step)
        else -> UnimplementedNodeRunner().run(step, input)
    }

    /**
     * An hour is far longer than any timeout a test worker is registered with,
     * so a run that gets past this parked rather than blocked.
     */
    private fun park(step: ExecutionStep): StepResult {
        if (step.waitUntil != null) return StepResult(StepStatus.COMPLETED, "${step.name} did the work")

        step.waitUntil = OffsetDateTime.now().plus(WAIT)
        return StepResult.waiting(WAIT, "${step.name} is waiting")
    }

    private companion object {
        val WAIT: Duration = Duration.ofHours(1)

        /** The attempt a `flaky` node finally works on. */
        const val FLAKY_UNTIL = 3
    }
}

/**
 * A step stuck in a call that will not end on its own, the way a step waiting
 * on a model is. Issue #440.
 *
 * Two shapes, because a stop reaches a step two ways and each has to be shown
 * working on its own. [untilHungUp] is a model call with a hangup: it does not
 * wake on an interrupt - a socket read does not - and ends only when the hook
 * it handed to the step's interrupt is run. [untilInterrupted] is a plain
 * sleep - the throttle's, a Retry-After's - which hands over nothing and wakes
 * only on the interrupt. Either one, released by a stop, throws the way the
 * real call does: a torn read is a failure to whoever made it.
 *
 * A test learns which run is stuck through [entered], since the inline engine
 * does not return until the run is over.
 */
object Blocking {

    private val entered = LinkedBlockingQueue<Long>()

    /** Forgets a run an earlier test left here. */
    fun reset() = entered.clear()

    /** The execution whose step has just blocked, waited for. */
    fun entered(within: Duration): Long =
        entered.poll(within.toMillis(), TimeUnit.MILLISECONDS) ?: error("No step blocked within $within")

    internal fun untilHungUp(step: ExecutionStep): StepResult {
        val hungUp = CountDownLatch(1)
        val interrupt = StepInterrupts.current() ?: error("${step.name} is not being carried as a step")
        entered.put(step.executionId)
        return interrupt.holding({ hungUp.countDown() }) {
            // Deaf to the interrupt on purpose: the flag is set and the read
            // goes on, exactly as a blocked socket does.
            while (true) {
                try {
                    if (hungUp.await(LONGEST.toMillis(), TimeUnit.MILLISECONDS)) break
                    error("${step.name} was never hung up on")
                } catch (interrupted: InterruptedException) {
                    continue
                }
            }
            throw IllegalStateException("${step.name}: nobody was left to read the answer")
        }
    }

    internal fun untilInterrupted(step: ExecutionStep): StepResult {
        entered.put(step.executionId)
        // Only the interrupt ends this, and the exception it throws is what
        // reaches the engine - the same as a sleep in a runner that catches
        // nothing.
        Thread.sleep(LONGEST.toMillis())
        error("${step.name} was never interrupted")
    }

    /** Longer than any test waits, so a stop that never arrives is a failure and not a hang. */
    private val LONGEST: Duration = Duration.ofSeconds(30)
}

@TestConfiguration
class ExecutionTestConfig {

    @Bean
    @Primary
    fun fakeWorkflowGraphSource(): WorkflowGraphSource = FakeWorkflowGraphSource()

    @Bean
    fun scriptedNodeRunner(): NodeRunner = ScriptedNodeRunner()
}
