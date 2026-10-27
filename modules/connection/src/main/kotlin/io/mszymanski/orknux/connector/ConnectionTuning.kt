package io.mszymanski.orknux.connector

/**
 * A number this module behaves by, which the installation decides. Issue #453.
 *
 * The module says what it needs and where a fresh installation starts; the
 * application stores what an administrator chose, shows it on Admin ->
 * Settings, and answers through [ConnectionTuning]. Declared here rather than
 * in the app because the module cannot see the app, and so that the default
 * stays beside the code that reads it.
 *
 * [least] and [most] are what a stored value is held inside, [most] null meaning
 * no upper bound: a value outside
 * them reads as [fallback], the way every other installation setting does.
 */
enum class ConnectionKnob(val key: String, val fallback: Int, val least: Int, val most: Int?) {

    /** The longest Retry-After a model call waits out itself before the node's own policy takes over. */
    RETRY_AFTER_LONGEST_SECONDS("model.retry.after.longest.seconds", 120, 0, null),

    /** How many Retry-Afters one model call waits out before it gives up. */
    RETRY_AFTER_ATTEMPTS("model.retry.after.attempts", 2, 0, null),

    /** The longest a throttled model call is held before it is let through. */
    THROTTLE_LONGEST_DELAY_SECONDS("model.throttle.longest.delay.seconds", 60, 1, null),

    /** How long fetching a picture a provider answered with by URL may take. */
    IMAGE_FETCH_SECONDS("model.image.fetch.seconds", 60, 1, null),
}

/**
 * What the installation says each [ConnectionKnob] is.
 *
 * The application implements it from Admin -> Settings. [DEFAULTS] is for a
 * caller with no installation behind it - a unit test building a client by
 * hand - and answers every knob's fallback.
 */
fun interface ConnectionTuning {

    fun valueOf(knob: ConnectionKnob): Int

    companion object {
        val DEFAULTS = ConnectionTuning { it.fallback }
    }
}
