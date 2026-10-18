package io.mszymanski.orknux.server.attachment

/**
 * What a drawn picture is called, which is what a download names it.
 *
 * Here rather than beside whichever caller drew it, because there are two of
 * them: a chat draws a picture into its thread, and a task draws one into its
 * outcome. It is the same argument [AttachmentDownloads] makes about which
 * types may be shown rather than downloaded - the rule is about the file, not
 * about the screen that asked for one - and a second copy of it would be a
 * picture named one way in a chat and another way in a task, which is a
 * difference nobody chose.
 */
object PictureFilenames {

    /**
     * A name out of the description, rather than a bare id.
     *
     * So a picture saved to a desktop still says what it is. Only letters and
     * digits survive and the rest becomes a dash: what is being named is
     * somebody's prose, and a filename is not the place for prose - or for
     * anything a path would read as meaningful.
     *
     * @param contentType what the picture turned out to be, which decides the
     *   extension. Anything that is not plainly a suffix falls back to `png`,
     *   because a provider that answered with a type nobody expected is not a
     *   reason to write it into a filename.
     */
    /**
     * A name for a picture out of its description: its first words, as written.
     *
     * What a picture is called until somebody calls it something else - the
     * opening of a prompt says what the picture is, and the rest is how.
     */
    fun nameOf(prompt: String): String =
        prompt.trim().split(Regex("\\s+")).take(WORDS).joinToString(" ")
            .trimEnd(',', '.', ';', ':', '-')
            .replaceFirstChar { it.uppercase() }
            .ifEmpty { "Picture" }

    fun of(prompt: String, contentType: String): String {
        val stem = prompt.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .take(WORDS)
            .joinToString("-")
            .ifEmpty { "picture" }
        val extension = contentType.substringAfter('/', "png").takeIf { it.all(Char::isLetterOrDigit) } ?: "png"
        return "$stem.$extension"
    }

    /** Enough of the description to recognise the file by. */
    private const val WORDS = 6
}
