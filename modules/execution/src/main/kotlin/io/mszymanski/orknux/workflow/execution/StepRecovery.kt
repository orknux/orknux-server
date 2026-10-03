package io.mszymanski.orknux.workflow.execution

/**
 * How a step a dead server was in the middle of is recovered. Issue #601.
 *
 * Two numbers an administrator sets on Admin -> Settings, under Workflow runs.
 * The module asks and the app answers, as [StepConcurrency] does, because the
 * settings live where every other number an administrator sets lives and a
 * module holds none of its own. Both are read when they are needed rather than
 * once at start, so a change applies to the next step without a restart.
 */
interface StepRecovery {

    /**
     * Temporal: how long a step may go without its worker saying it is alive
     * before the step is handed to another worker. Zero turns it off, and a
     * dead worker is then noticed only when the step's whole timeout runs out.
     */
    fun stepHeartbeatSeconds(): Long

    /**
     * Inline engine: how many goes in all an agent step gets when restarts keep
     * cutting it short, the ones that died included. See
     * [NodeRunner.asksAgainAfterRestart].
     */
    fun restartAttempts(): Int
}
