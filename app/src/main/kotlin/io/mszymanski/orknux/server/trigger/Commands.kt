package io.mszymanski.orknux.server.trigger

/**
 * The commands in a message: every word that starts with the marker.
 *
 * Orknux's own syntax rather than Slack's. Slack intercepts a message that
 * starts with `/` - a slash command has to be registered in the Slack app, and
 * one that is not is refused by the client before it is sent - so anything a
 * workspace wants to maintain on its own side has to look like ordinary text
 * to Slack. `!review`, then, with the marker a workspace's own to change.
 *
 * A command is the marker followed by what a skill id may hold - letters,
 * underscores and hyphens - so `!review,` is `review` and `!!` is nothing. Every
 * one in the message is kept, in order, once each: `!review !security` is two,
 * and that is the point of a list. Issue #381.
 */
object Commands {

    private val WORD = Regex("[A-Za-z_-]+")

    fun parse(text: String?, marker: String): List<String> {
        if (text.isNullOrBlank() || marker.isEmpty()) return emptyList()
        val seen = LinkedHashMap<String, String>()
        text.split(WHITESPACE).forEach { token ->
            if (!token.startsWith(marker)) return@forEach
            val word = WORD.find(token.removePrefix(marker))?.takeIf { it.range.first == 0 }?.value ?: return@forEach
            seen.putIfAbsent(word.lowercase(), word)
        }
        return seen.values.toList()
    }

    /**
     * What a marker may be: one to three characters, none of them a letter, a
     * digit or a space. A letter as marker would turn ordinary words into
     * commands - `x` marking `xylophone` - which is why the box refuses it
     * rather than the run explaining it.
     */
    fun usableMarker(marker: String): Boolean =
        marker.length in MIN_MARKER_LENGTH..MAX_MARKER_LENGTH && marker.none { it.isLetterOrDigit() || it.isWhitespace() }

    private val WHITESPACE = Regex("\\s+")

    const val DEFAULT_MARKER = "!"
    const val MIN_MARKER_LENGTH = 1
    const val MAX_MARKER_LENGTH = 3
}
