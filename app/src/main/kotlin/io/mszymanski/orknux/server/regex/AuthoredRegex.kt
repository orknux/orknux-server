package io.mszymanski.orknux.server.regex

/**
 * A regular expression this server did not write, run so that it cannot hold
 * the thread it runs on.
 *
 * `java.util.regex` backtracks, has no timeout, and does not answer to an
 * interrupt: a match that has gone exponential is not throwing, it is working,
 * and `runCatching` round it waits as long as it does. The shape that does it
 * on this JDK is an alternative that can match nothing inside a repetition -
 * `(a|a?){60}b` against forty a's was measured not to come back - and it is
 * eleven characters, so no bound on a pattern's length keeps it out. A model
 * writes the watchers' patterns, a workspace writes its conditions' and a
 * stranger's Slack message is what those are matched against.
 *
 * So the bound is on time. The text being searched is handed to the matcher
 * through [Deadlined], which looks at the clock every so many characters the
 * matcher reads and throws [RegexTooSlowException] once the deadline has
 * passed. Every step of a backtracking search reads a character, so the check
 * cannot be starved, and it runs on the caller's thread: nothing is started,
 * nothing is left running when it gives up. This is the JDK's own extension
 * point for it - the matcher takes any [CharSequence] - rather than a regex
 * engine of our own.
 *
 * How long is [io.mszymanski.orknux.server.tuning.Tunable.REGEX_MATCH_SECONDS],
 * read by the caller, because the callers are a tick, a workflow run and a
 * tool call, and each knows where its settings come from.
 */
object AuthoredRegex {

    /**
     * The longest pattern anybody but this server may have run. It was
     * `ConditionEvaluator`'s, the width of the column a condition's pattern is
     * stored in, and is shared rather than copied so that a watcher, a
     * condition and a scratchpad search refuse at the same length.
     */
    const val MAX_PATTERN = 500

    /** Why [pattern] is too long to be run, or null when it is not. */
    fun tooLong(pattern: String): String? = if (pattern.length > MAX_PATTERN) {
        "A regular expression may be at most $MAX_PATTERN characters, and that one is ${pattern.length}."
    } else {
        null
    }

    /** The moment, on [System.nanoTime]'s clock, [seconds] from now. */
    fun deadlineIn(seconds: Int): Long = System.nanoTime() + seconds * NANOS_PER_SECOND

    /**
     * [text] as a matcher should be handed it: the same characters, with a
     * clock behind them. Pass the result to `find`, `containsMatchIn` or
     * `matches`, never the text itself.
     */
    fun bounded(text: CharSequence, deadline: Long, seconds: Int): CharSequence = Deadlined(text, deadline, seconds)

    /** The first match of [regex] in [text], or null; [RegexTooSlowException] past [seconds]. */
    fun find(regex: Regex, text: CharSequence, seconds: Int): MatchResult? =
        regex.find(bounded(text, deadlineIn(seconds), seconds))

    /** Whether [regex] matches somewhere in [text]; [RegexTooSlowException] past [seconds]. */
    fun containsMatchIn(regex: Regex, text: CharSequence, seconds: Int): Boolean =
        regex.containsMatchIn(bounded(text, deadlineIn(seconds), seconds))

    /** Whether [regex] matches the whole of [text]; [RegexTooSlowException] past [seconds]. */
    fun matches(regex: Regex, text: CharSequence, seconds: Int): Boolean =
        regex.matches(bounded(text, deadlineIn(seconds), seconds))

    private const val NANOS_PER_SECOND = 1_000_000_000L
}

/**
 * The characters of [text], with the clock read on every [EVERY]th one.
 *
 * Reading the clock on each would cost more than the match; every thousandth
 * is a few microseconds apart even at the matcher's fastest, which is as
 * precise as a limit counted in seconds needs to be. A thread being
 * interrupted counts as time being up, so a caller that is shutting down is
 * not kept waiting either.
 *
 * What a match hands back - `MatchResult.value`, a group - is cut from
 * [text] itself, so reading it afterwards is never on the clock.
 */
private class Deadlined(
    private val text: CharSequence,
    private val deadline: Long,
    private val seconds: Int,
) : CharSequence {

    private var reads = 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (++reads >= EVERY) {
            reads = 0
            if (System.nanoTime() - deadline > 0 || Thread.currentThread().isInterrupted) {
                throw RegexTooSlowException(seconds)
            }
        }
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()

    private companion object {
        const val EVERY = 1024
    }
}

/**
 * A regular expression was still matching when its time ran out, and was
 * stopped. Said to whoever wrote it: what to do about it is to write it
 * without the alternative that can match nothing.
 */
class RegexTooSlowException(val seconds: Int) : RuntimeException(
    "The regular expression was still matching after $seconds ${if (seconds == 1) "second" else "seconds"}, so it was " +
        "stopped. A group that can match nothing, repeated - such as (a|a?){60} - makes it try every way of " +
        "splitting the text; write it without the empty alternative.",
)
