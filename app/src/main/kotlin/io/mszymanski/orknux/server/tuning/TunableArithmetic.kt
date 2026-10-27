package io.mszymanski.orknux.server.tuning

/**
 * Arithmetic on a [Tunable]'s value, which may be [Int.MAX_VALUE] now that most
 * of them have no upper bound.
 *
 * An administrator who types the largest number the box takes has asked for
 * "as much as there is", and the server must not read that as a negative number
 * because a sum wrapped, or fall over allocating it. So a sum or a product of a
 * setting is done in Long and held at the ends of Int rather than wrapped, and
 * anything sized from a setting is held to what the JVM can hold at all. Neither
 * is a limit somebody meets on the page; past them nothing could be done anyway.
 */
object TunableArithmetic {

    /** The longest array the JVM allocates: a few bytes short of [Int.MAX_VALUE], for the header. */
    const val LARGEST_ARRAY: Int = Int.MAX_VALUE - 8

    /**
     * The longest text a [StringBuilder] can be made to hold whatever it holds: one
     * that has met a character outside Latin-1 keeps two bytes for each.
     */
    const val LONGEST_TEXT: Int = LARGEST_ARRAY / 2

    const val MEGABYTE: Long = 1024L * 1024L

    /** [mb] megabytes in bytes, in Long: 2,047 MB is the most an Int can say. */
    fun bytesOf(mb: Int): Long = mb.toLong() * MEGABYTE

    /** [a] + [b], held at the ends of Int rather than wrapping round. */
    fun plus(a: Int, b: Int): Int = held(a.toLong() + b)

    /** [a] × [b], held at the ends of Int rather than wrapping round. */
    fun times(a: Int, b: Int): Int = held(a.toLong() * b)

    private fun held(value: Long): Int = value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
