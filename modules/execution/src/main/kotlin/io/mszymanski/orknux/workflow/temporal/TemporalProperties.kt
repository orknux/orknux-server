package io.mszymanski.orknux.workflow.temporal

import org.springframework.boot.context.properties.ConfigurationProperties

/** Where the Temporal service is, and how patient a run is with its steps. */
@ConfigurationProperties(prefix = "orknux.temporal")
data class TemporalProperties(
    /**
     * False runs workflows in this process instead, with no retries and no
     * resumption — see `InlineExecutionEngine`.
     */
    val enabled: Boolean = true,
    /** host:port of the Temporal frontend. */
    val target: String = "localhost:7233",
    val namespace: String = "default",
    /**
     * Where Temporal's own web interface is, if it is running.
     *
     * Only used to build links out to it: what a run did step by step is
     * Temporal's history, and there is no reason to rebuild that screen here.
     * Null means no links are offered, which is right for a deployment that
     * does not expose it.
     */
    val uiUrl: String? = null,
    /** Workers poll this queue; the workflow is started on it. */
    val taskQueue: String = "orknux-workflow",
    /**
     * How long one step may take. A model call is slow, so this is generous,
     * but not unbounded: a step nobody is waiting on any more must not hold a
     * worker for ever.
     */
    val stepTimeoutSeconds: Long = 300,
    /**
     * How long a step may go without its worker saying it is still alive before
     * Temporal counts the worker dead and hands the step to another. Issue #601.
     *
     * A worker that is killed - out of memory, a node drained - says nothing,
     * and without this the only thing that noticed was [stepTimeoutSeconds]:
     * five minutes of a run sitting on a step nothing was running, and a person
     * waiting on an answer for all of them. The step heartbeats while it works,
     * a third of this apart, so a live step in a long model call is never
     * mistaken for a dead one. Thirty seconds: a restarted server is up again in
     * about that, and a heartbeat is one small call. Zero turns it off.
     */
    val stepHeartbeatSeconds: Long = 30,
    /**
     * How many times a step is tried. Most of what a step does is a call to
     * something else, and most of those failures are worth trying again; a node
     * whose failure is not can say so with a non-retryable failure.
     */
    val stepAttempts: Int = 3,
    /** How long a whole run may take, including anything it waits for. */
    val runTimeoutHours: Long = 24,
)
