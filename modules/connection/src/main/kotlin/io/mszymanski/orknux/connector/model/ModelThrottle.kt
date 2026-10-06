package io.mszymanski.orknux.connector.model

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds a model's calls under the rate it is allowed, so a run slows itself
 * down rather than being turned away with a 429. Issue #426.
 *
 * ### Per model, and shared
 *
 * The rate is the model's, because limits differ per model: every run calling
 * one model shares a single budget and a single growing delay, and a different
 * model of the same provider shares none of it. That sharing is the point - ten
 * workflow executions hammering one model at once must count against one limit,
 * not ten - so the state is keyed on the model and every caller reaches the same
 * instance. The decision is taken under a per-model lock and a slot is reserved
 * on the way out, so two threads arriving together are spaced rather than both
 * waved through.
 *
 * ### The two dimensions, and the growing delay
 *
 * A model may cap tokens a second, requests a second, or both, and whichever is
 * nearer its limit decides the wait. Requests are spaced exactly - a limit of R
 * a second means one every `1/R` seconds, which is the honest way to bound a
 * rate below one a second too. Tokens are reactive, because how many a call will
 * spend is not known until it answers: the wait grows with how full the last
 * second's token window is, nothing until it is half full and then rising, so a
 * run eases off as it approaches the limit instead of hitting it.
 *
 * ### Retry-After
 *
 * A 429 that carries a Retry-After is obeyed exactly and by everyone: the block
 * is recorded on the model, so every run calling it waits the same time out
 * rather than each discovering the wall for itself. Whether it is obeyed at all
 * is the provider's setting, a model may override it, and it takes precedence
 * over a node's own retry policy - see where [ModelChatClient] reads it.
 *
 * ### Cost when off
 *
 * A model with no limits and no active block reserves nothing and waits not at
 * all: `reserveMillis` returns now, and the maps hold only what a throttled
 * model put there.
 *
 * ### Forgetting
 *
 * A model's state is dropped once it holds nothing back - no block still
 * running, no tokens in the window, no request spacing still to serve -
 * because a state like that answers exactly what no state answers. That is
 * what keeps the map from holding every model that was ever throttled: a model
 * deleted, or simply no longer called, goes idle within a window and is gone at
 * the next look. Issue #616. The look is taken at most once a window, from
 * whichever call comes along, so there is no timer of its own to tune.
 */
@Component
class ModelThrottle(private val clock: Clock = Clock.systemUTC()) {

    /** The rate a model is held to, worked out from the model over its provider. */
    data class Limits(
        /** Tokens a second; null or non-positive is no token throttle. */
        val tokensPerSecond: Long?,
        /** Requests a second, possibly below one; null or non-positive is no request throttle. */
        val requestsPerSecond: Double?,
        /** Whether a 429's Retry-After is obeyed. */
        val acceptRetryAfter: Boolean,
    ) {
        val throttled: Boolean get() = (tokensPerSecond ?: 0) > 0 || (requestsPerSecond ?: 0.0) > 0.0

        companion object {
            /**
             * A model's effective limits: its own where set, the provider's
             * default otherwise. A model's 0 turns a dimension off though the
             * provider sets one; its null inherits.
             */
            fun of(model: LlmModel, provider: ModelProvider): Limits = Limits(
                tokensPerSecond = model.throttleTokensPerSecond ?: provider.throttleTokensPerSecond,
                requestsPerSecond = model.throttleRequestsPerSecond ?: provider.throttleRequestsPerSecond,
                acceptRetryAfter = model.acceptRetryAfter ?: provider.acceptRetryAfter,
            )
        }
    }

    private class State {
        /** When the last request was cleared to fire, so the next is spaced from it. */
        var lastRequestAt: Long = 0

        /**
         * The spacing that request was cleared under, so whether it still holds
         * the next one back can be told without the model's limits to hand.
         */
        var spacing: Long = 0

        /** (millis, tokens) the model spent, kept for the last [WINDOW_MILLIS]. */
        val tokens = ArrayDeque<Pair<Long, Long>>()

        /** A Retry-After block: nothing fires before this. */
        var blockedUntil: Long = 0

        /**
         * Taken out of the map. A caller that picked it up just before goes
         * back for the live one, or two callers would be spaced on two states.
         */
        var retired = false

        /** Holding nothing back: what a model with no state at all would answer. Call with [tokens] pruned. */
        fun idle(now: Long): Boolean = blockedUntil <= now && tokens.isEmpty() && lastRequestAt + spacing <= now
    }

    private val states = ConcurrentHashMap<Long, State>()

    /** When idle states were last looked for; see "Forgetting" above. */
    @Volatile
    private var forgotAt = 0L

    /**
     * Waits as long as [modelId] is due before its next call is made.
     *
     * The wait is worked out and the slot reserved under the lock; the sleep is
     * outside it, so a run resting does not hold the model's next caller off the
     * lock. A model with nothing to hold it back sleeps not at all.
     */
    fun awaitTurn(modelId: Long, limits: Limits) {
        val fireAt = reserveMillis(modelId, limits)
        val wait = fireAt - clock.millis()
        if (wait > 0) {
            try {
                Thread.sleep(wait)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * The instant [modelId]'s next call may fire, reserving its request slot.
     * Split from [awaitTurn] so the arithmetic can be tested without a sleep.
     */
    fun reserveMillis(modelId: Long, limits: Limits): Long {
        val now = clock.millis()
        forgetIdle(now)
        // Nothing to hold it: no limits set, and no state means no lingering
        // block or history either. The common case, and it touches nothing.
        if (!limits.throttled && states[modelId] == null) return now

        return withState(modelId) { state ->
            prune(state, now)

            // Off, but a State exists: only a Retry-After block can still hold it.
            if (!limits.throttled) return@withState maxOf(now, state.blockedUntil)

            val spacing = limits.requestsPerSecond?.takeIf { it > 0.0 }?.let(::spacingMillis) ?: 0
            val requestFloor = if (spacing > 0) maxOf(now, state.lastRequestAt + spacing) else now

            val tokenSum = state.tokens.sumOf { it.second }.toDouble()
            val tokenWait = limits.tokensPerSecond
                ?.takeIf { it > 0 }
                ?.let { now + growingDelay(tokenSum, it.toDouble()) }
                ?: now

            val fireAt = maxOf(requestFloor, tokenWait, state.blockedUntil)
            state.lastRequestAt = fireAt
            state.spacing = spacing
            fireAt
        }
    }

    /** Records the tokens a call spent, so the token window reflects real usage. */
    fun recordUsage(modelId: Long, tokens: Long) {
        if (tokens <= 0) return
        val now = clock.millis()
        forgetIdle(now)
        withState(modelId) { state ->
            prune(state, now)
            state.tokens.addLast(now to tokens)
        }
    }

    /**
     * Records a Retry-After: nothing on this model fires for [wait]. Shared, so
     * every run calling the model waits it out, not just the one that got the
     * 429. Only where the model accepts it - the caller checks [Limits].
     */
    fun blockFor(modelId: Long, wait: Duration) {
        val now = clock.millis()
        forgetIdle(now)
        val until = now + wait.toMillis().coerceAtLeast(0)
        withState(modelId) { state ->
            if (until > state.blockedUntil) {
                state.blockedUntil = until
                log.debug("Model {} is held for {}ms by a Retry-After", modelId, wait.toMillis())
            }
        }
    }

    /** How many models have state held, for the test that an idle one is let go. Issue #616. */
    internal fun held(): Int = states.size

    /**
     * Runs [block] under the lock of [modelId]'s live state, made if there is
     * none. A state retired between being looked up and being locked is not
     * live, so the lookup is made again.
     */
    private fun <T> withState(modelId: Long, block: (State) -> T): T {
        while (true) {
            val state = states.computeIfAbsent(modelId) { State() }
            synchronized(state) {
                if (!state.retired) return block(state)
            }
        }
    }

    /** Drops every state that holds nothing back, at most once a window. */
    private fun forgetIdle(now: Long) {
        if (now - forgotAt < WINDOW_MILLIS) return
        forgotAt = now
        for ((id, state) in states) {
            synchronized(state) {
                prune(state, now)
                if (state.idle(now)) {
                    state.retired = true
                    states.remove(id, state)
                }
            }
        }
    }

    private fun prune(state: State, now: Long) {
        val edge = now - WINDOW_MILLIS
        while (state.tokens.isNotEmpty() && state.tokens.first().first < edge) state.tokens.removeFirst()
    }

    /** Milliseconds between requests at [perSecond] a second; a rate below one spaces past a second. */
    private fun spacingMillis(perSecond: Double): Long = (1000.0 / perSecond).toLong().coerceAtLeast(1)

    /**
     * The delay a dimension earns from how full its window is: nothing below
     * [SOFT] of the limit, then rising linearly - a full second's wait at the
     * limit, and more beyond it, which is what drains an overshoot back under.
     */
    private fun growingDelay(sum: Double, limit: Double): Long {
        if (limit <= 0.0) return 0
        val u = sum / limit
        if (u < SOFT) return 0
        val over = (u - SOFT) / (1.0 - SOFT)
        return (over * WINDOW_MILLIS).toLong().coerceAtMost(MAX_DELAY_MILLIS)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelThrottle::class.java)

        const val WINDOW_MILLIS = 1000L

        /** How full the window is before the token delay starts to grow. */
        const val SOFT = 0.5

        /** The longest a single pre-call token delay grows to, so an overshoot is not an outage. */
        const val MAX_DELAY_MILLIS = 60_000L
    }
}
