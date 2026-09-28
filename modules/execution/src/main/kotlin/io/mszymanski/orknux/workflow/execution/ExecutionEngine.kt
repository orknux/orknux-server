package io.mszymanski.orknux.workflow.execution

/**
 * Runs a workflow.
 *
 * Two implementations: [InlineExecutionEngine] carries the run out on the
 * calling thread, and the Temporal one hands it to a service that can retry a
 * step, outlive a restart and wait for hours. Which one is wired is
 * `orknux.temporal.enabled`.
 *
 * Both plan the same way — [ExecutionPlanner] — and both do the work of a step
 * the same way — [StepRunner] — so the only thing that differs is what carries
 * the run from one step to the next, and what happens when that is interrupted.
 */
interface ExecutionEngine {

    /**
     * Records the run and starts it. What comes back is the run as it stood
     * when this returned: finished, for the inline engine, and running for
     * Temporal, which answers as soon as the run is durably accepted.
     */
    fun start(
        workspaceId: Long,
        workflowId: Long,
        trigger: ExecutionTrigger,
        input: String? = null,
        /** Which copy to run, where what started it does not decide. See [StartExecutionInput]. */
        version: GraphVersion? = null,
        /**
         * Where to pick up an earlier run, instead of starting at the
         * beginning. The steps ahead of it are carried over from what that run
         * recorded, and this walks only what is left. See [ResumePoint].
         */
        resumeFrom: ResumePoint? = null,
        /**
         * The run this one was started from, when it is a re-run. Recorded on
         * the run so it can point back at it. See [StartExecutionInput].
         */
        startedFrom: Long? = null,
        /**
         * Which trigger definition fired, where one did. Null for a run nobody
         * was triggered into — a person pressing Run, an API asking directly —
         * and for a re-run, which repeats a graph rather than an event. See
         * [StartExecutionInput].
         */
        firedTriggerId: Long? = null,
    ): WorkflowExecution

    /**
     * Cuts short the wait of a run parked on a step, so the step is run again
     * now rather than when its time is up. For something arriving that the step
     * was waiting on - an answer from an agent it asked - where waiting out the
     * rest of the timer would leave it sitting on what it needed.
     *
     * A hint, not a promise: a run that is not parked has nothing to cut short,
     * and a step woken with nothing new simply parks again.
     */
    fun wake(executionId: Long) {}
}
