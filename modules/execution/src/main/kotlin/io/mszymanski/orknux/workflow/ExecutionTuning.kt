package io.mszymanski.orknux.workflow

/**
 * A number this module behaves by, which the installation decides. Issue #453.
 *
 * The same arrangement as the connection module's `ConnectionKnob`: the module
 * declares the knob and its default beside the code that reads it, and the
 * application stores, shows and answers it through [ExecutionTuning]. [most]
 * null is no upper bound.
 */
enum class ExecutionKnob(val key: String, val fallback: Int, val least: Int, val most: Int?) {

    /** How many lines one run of a workspace's function may log before the rest are dropped. */
    SCRIPT_LOG_LINES("script.log.lines", 500, 10, null),

    /** The most times one node may do its work in one pass of a run. Issue #661. */
    NODE_REPEATS_MOST("node.repeats.most", 100, 2, null),

    /** The longest timeout a node may be given, in seconds: a day unless the installation says otherwise. */
    NODE_TIMEOUT_MOST_SECONDS("node.timeout.most.seconds", 86_400, 1, null),
}

/** What the installation says each [ExecutionKnob] is; [DEFAULTS] answers the fallbacks. */
fun interface ExecutionTuning {

    fun valueOf(knob: ExecutionKnob): Int

    companion object {
        val DEFAULTS = ExecutionTuning { it.fallback }
    }
}
