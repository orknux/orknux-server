package io.mszymanski.orknux.server.diagram

/**
 * Part of a label styled: the words a shape or a line says, with marks around
 * the ones drawn differently - `**bold**`, `*italic*`, `__underlined__`,
 * `~~struck through~~` and `[coloured]{#dc2626}` - which nest, and which a
 * backslash takes literally (`\*`). The editor's `DiagramRichText` reads the
 * same marks the same way, so a picture exported says what the canvas showed.
 *
 * Kept in the label itself rather than beside it: what a label says and how it
 * is styled cannot drift apart, an agent writes it as it writes markdown, and
 * a label with no marks is exactly what it always was. A mark opens only
 * before a word and closes only after one, as in markdown, so `2 * 3 * 4`
 * stays arithmetic.
 */
object DiagramStyledText {

    data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        /** `#rrggbb`, or null for the label's own ink. */
        val color: String? = null,
    ) {
        val plain: Boolean get() = this == PLAIN
    }

    data class Run(val text: String, val style: Style)

    val PLAIN = Style()

    private val COLOUR = Regex("^\\{(#[0-9a-fA-F]{6})\\}")
    private const val LITERAL = "*_~[]{}\\"

    /** The label as runs of one style each, marks taken out. */
    fun runs(text: String): List<Run> = buildList { read(text, PLAIN, this) }.fold(mutableListOf()) { kept, run ->
        // Neighbours of one style are one run.
        val last = kept.lastOrNull()
        if (last != null && last.style == run.style) kept[kept.size - 1] = Run(last.text + run.text, run.style) else if (run.text.isNotEmpty()) kept += run
        kept
    }

    /** The words alone, as they are read and measured. */
    fun plain(text: String): String = if (MARKS.none { it in text }) text else runs(text).joinToString("") { it.text }

    /** The style of every character of [plain], in order. */
    fun stylesOf(text: String): List<Style> = runs(text).flatMap { run -> List(run.text.length) { run.style } }

    private val MARKS = listOf('*', '_', '~', '[', '\\')

    private fun read(text: String, style: Style, into: MutableList<Run>) {
        val words = StringBuilder()
        fun flush() {
            if (words.isNotEmpty()) into += Run(words.toString(), style)
            words.clear()
        }
        var at = 0
        while (at < text.length) {
            val c = text[at]
            if (c == '\\' && at + 1 < text.length && text[at + 1] in LITERAL) {
                words.append(text[at + 1])
                at += 2
                continue
            }
            val pair = listOf("**", "__", "~~").firstOrNull { text.startsWith(it, at) }
            if (pair != null) {
                val close = close(text, pair, at + 2)
                if (close != null) {
                    flush()
                    val inner = when (pair) {
                        "**" -> style.copy(bold = true)
                        "__" -> style.copy(underline = true)
                        else -> style.copy(strike = true)
                    }
                    read(text.substring(at + 2, close), inner, into)
                    at = close + 2
                    continue
                }
            }
            if (c == '*') {
                val close = close(text, "*", at + 1)
                if (close != null) {
                    flush()
                    read(text.substring(at + 1, close), style.copy(italic = true), into)
                    at = close + 1
                    continue
                }
            }
            if (c == '[') {
                val end = bracket(text, at)
                val colour = end?.let { COLOUR.find(text.substring(it + 1)) }
                if (end != null && colour != null && end > at + 1) {
                    flush()
                    read(text.substring(at + 1, end), style.copy(color = colour.groupValues[1].lowercase()), into)
                    at = end + 1 + colour.value.length
                    continue
                }
            }
            words.append(c)
            at += 1
        }
        flush()
    }

    /** Where [mark] opened at [from] closes: the next one after a word, past escapes and, for one star, past pairs. Null where it opens before a space or never closes. */
    private fun close(text: String, mark: String, from: Int): Int? {
        if (from >= text.length || text[from].isWhitespace()) return null
        var at = from
        while (at < text.length) {
            if (text[at] == '\\' && at + 1 < text.length) {
                at += 2
                continue
            }
            if (mark == "*" && text.startsWith("**", at)) {
                at += 2
                continue
            }
            if (text.startsWith(mark, at) && at > from && !text[at - 1].isWhitespace()) {
                // A run of stars closes on its last pair, so `***both***` is bold round italic rather than bold round an unclosed star.
                var last = at
                if (mark == "**") while (text.startsWith("**", last + 1)) last += 1
                return last
            }
            at += 1
        }
        return null
    }

    /** The `]` that closes the `[` at [from], counting brackets inside it; null where there is none. */
    private fun bracket(text: String, from: Int): Int? {
        var depth = 0
        var at = from
        while (at < text.length) {
            when {
                text[at] == '\\' -> at += 1
                text[at] == '[' -> depth += 1
                text[at] == ']' -> {
                    depth -= 1
                    if (depth == 0) return at
                }
            }
            at += 1
        }
        return null
    }
}
