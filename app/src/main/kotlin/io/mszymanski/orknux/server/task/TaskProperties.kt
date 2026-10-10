package io.mszymanski.orknux.server.task

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * What a task may spend before it is stopped and told so.
 *
 * An agent running until it decides it is done is an unbounded bill, and the
 * reasoning here is the reasoning behind the retry policy's budget: a count of
 * goes bounds how often the model is asked, and it says nothing at all about
 * what happens between two of them. A tool call that sits there for a
 * minute, an MCP server that takes its time, a model that thinks for two -
 * five turns of that is not five model calls' worth of time, and no number of
 * turns can express it. So there are two ceilings and they bound different
 * things.
 *
 * Both are copied onto the task when it is created rather than read while it
 * runs, so raising a default does not extend a task already going and lowering
 * one does not kill it.
 *
 * There is deliberately no ceiling on tokens here. A model already carries its
 * own usage cap with a reset interval, which is the installation's real answer
 * to "what may this cost"; a second half-counter on the task would be a number
 * that disagrees with the one on the bill.
 */
@ConfigurationProperties(prefix = "orknux.task")
data class TaskProperties(
    /**
     * How many times the model may be asked before the task is stopped.
     *
     * One turn is one round of the agent's ordinary tool loop, which is itself
     * bounded - so this is the outer of two counts and not the only one. Forty
     * is a long piece of work and about an hour of model calls; it is high
     * enough that hitting it means the agent is going round in circles, which
     * is exactly when somebody should be told rather than billed.
     */
    val maxTurns: Int = 40,

    /**
     * The longest a task may be *working*.
     *
     * Not wall clock: a task parked for two days waiting to be approved has
     * spent none of this. What it bounds is the sum of the turns, which is what
     * an installation is actually paying for.
     */
    val workingTime: Duration = Duration.ofHours(2),

    /**
     * How long a parked task waits for somebody before it gives up.
     *
     * A task that stops for permission and is never answered would otherwise be
     * a durable workflow held open for ever and a row that says "waiting" for
     * the life of the installation. A week is long enough to cover a holiday
     * and short enough that a forgotten task is eventually a finished one.
     */
    val patience: Duration = Duration.ofDays(7),
) {
    companion object {

        /**
         * How often a parked task looks to see whether it has been answered.
         *
         * A constant and not a setting. It is a poll rather than a signal for
         * the reason a parked workflow step is one - the wait belongs to
         * whatever is carrying the task, the answer is in the database, and a
         * timer that survives every process involved is worth more than a
         * delivery that can fail - and how often it looks is an implementation
         * detail of that, not something an installation has a view on. The
         * three above are what an operator is deciding: how much a task may
         * cost, and how long it waits for them.
         */
        val POLL_WHILE_WAITING: Duration = Duration.ofSeconds(30)
    }
}
