package io.mszymanski.orknux.server.design

/**
 * A wireframe drawn as one SVG: the picture an agent's chat shows beside the
 * link to the editor.
 *
 * Asked for after an agent drew a registration form properly and answered with
 * a link and a paragraph describing it: a person who asked for a mockup wants
 * to see it where they asked, and opening the editor is the second step, not
 * the first.
 *
 * One canvas around everything drawn, at the pixels it was drawn at, in the
 * editor's pencil greys - the grey of [WireframeHtml]'s styles, and grey for the
 * note too, because a picture of a wireframe should read as a wireframe and not
 * as a design. The drawing is untrusted, as it is for the HTML page: every word
 * is escaped, characters XML cannot hold are dropped, a prop reaches the
 * picture only as one of the few values that are looked for, and nothing in it
 * is a script or a reference to anything outside it.
 *
 * SVG has no text wrapping, so words are wrapped here by an estimate of their
 * width. It is a picture to look at, not a layout to measure; what does not fit
 * a box ends in an ellipsis rather than spilling over its neighbours.
 */
class WireframeSvg {

    /** The SVG of [elements], or null where there is nothing to draw. */
    fun drawing(elements: List<WireframeElement>): String? {
        if (elements.isEmpty()) return null
        val left = elements.minOf { minOf(it.x, it.x + it.w) } - PAD
        val top = elements.minOf { minOf(it.y, it.y + it.h) } - PAD
        val right = elements.maxOf { maxOf(it.x, it.x + it.w) } + PAD
        val bottom = elements.maxOf { maxOf(it.y, it.y + it.h) } + PAD
        val width = maxOf(1, right - left)
        val height = maxOf(1, bottom - top)
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" ")
            append("viewBox=\"$left $top $width $height\" font-family=\"$FONT\">")
            append("<defs><marker id=\"wf-arrowhead\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" ")
            append("markerHeight=\"8\" orient=\"auto-start-reverse\"><path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"$INK\"/></marker></defs>")
            append("<rect x=\"$left\" y=\"$top\" width=\"$width\" height=\"$height\" fill=\"$PAPER\"/>")
            // Frames under what sits in them, whatever order they were drawn in: a frame is the screen.
            elements.filter { it.type == WireframeElementType.FRAME }.forEach { element(it) }
            elements.filter { it.type != WireframeElementType.FRAME }.forEach { element(it) }
            append("</svg>")
        }
    }

    private fun StringBuilder.element(element: WireframeElement) {
        val x = element.x
        val y = element.y
        val w = element.w
        val h = element.h
        when (element.type) {
            WireframeElementType.FRAME -> {
                box(x, y, w, h, fill = WHITE, stroke = INK, width = 1.5, radius = 10)
                element.text?.let { title -> text(x, y - 6, clean(title).lineSequence().first(), 12, MUTED) }
            }
            WireframeElementType.RECT -> {
                box(x, y, w, h, fill = SHADE, stroke = MUTED, width = 1.0, radius = 6)
                centred(element, size = 14, colour = INK)
            }
            WireframeElementType.TEXT -> {
                val size = (element.props["size"] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toInt()
                    ?.coerceIn(WireframeHtml.TEXT_LEAST, WireframeHtml.TEXT_MOST) ?: 14
                val bold = element.props["weight"]?.toString()?.trim()?.lowercase() == "bold"
                wrapped(element.text, x, y, w, h, size, INK, bold = bold, centre = false)
            }
            WireframeElementType.BUTTON -> {
                val primary = element.props["variant"]?.toString()?.trim()?.lowercase() == "primary"
                box(x, y, w, h, fill = if (primary) FILLED else WHITE, stroke = if (primary) FILLED else INK, width = 1.5, radius = 8)
                centred(element, size = 14, colour = if (primary) WHITE else INK)
            }
            WireframeElementType.INPUT -> {
                box(x, y, w, h, fill = WHITE, stroke = MUTED, width = 1.0, radius = 6)
                element.text?.let { words ->
                    val line = fitted(clean(words).lineSequence().first(), w - 24, 14)
                    text(x + 12, y + h / 2 + 5, line, 14, MUTED)
                }
            }
            WireframeElementType.IMAGE -> {
                box(x, y, w, h, fill = SHADE, stroke = MUTED, width = 1.0, radius = 4)
                append("<line x1=\"$x\" y1=\"$y\" x2=\"${x + w}\" y2=\"${y + h}\" stroke=\"$MUTED\" stroke-width=\"1\"/>")
                append("<line x1=\"${x + w}\" y1=\"$y\" x2=\"$x\" y2=\"${y + h}\" stroke=\"$MUTED\" stroke-width=\"1\"/>")
                element.text?.let { caption ->
                    val line = fitted(clean(caption).lineSequence().first(), w - 12, 12)
                    text(x + w / 2, y + h - 8, line, 12, MUTED, anchor = "middle")
                }
            }
            WireframeElementType.NOTE -> {
                box(x, y, w, h, fill = NOTE, stroke = MUTED, width = 1.0, radius = 2, dashed = true)
                wrapped(element.text, x + 10, y + 10, w - 20, h - 20, 13, INK, bold = false, centre = false)
            }
            WireframeElementType.LINE, WireframeElementType.ARROW -> {
                val head = if (element.type == WireframeElementType.ARROW) " marker-end=\"url(#wf-arrowhead)\"" else ""
                append("<line x1=\"$x\" y1=\"$y\" x2=\"${x + w}\" y2=\"${y + h}\" stroke=\"$INK\" stroke-width=\"1.5\" fill=\"none\"$head/>")
            }
        }
    }

    private fun StringBuilder.box(x: Int, y: Int, w: Int, h: Int, fill: String, stroke: String, width: Double, radius: Int, dashed: Boolean = false) {
        append("<rect x=\"$x\" y=\"$y\" width=\"${maxOf(0, w)}\" height=\"${maxOf(0, h)}\" rx=\"$radius\" ")
        append("fill=\"$fill\" stroke=\"$stroke\" stroke-width=\"$width\"")
        if (dashed) append(" stroke-dasharray=\"4 3\"")
        append("/>")
    }

    /** A label in the middle of its box, as a button's or a card's is. */
    private fun StringBuilder.centred(element: WireframeElement, size: Int, colour: String) {
        val words = element.text?.let(::clean)?.takeIf { it.isNotBlank() } ?: return
        val lines = wrap(words, element.w - 16, size, maxOf(1, element.h / lineHeight(size)))
        val first = element.y + element.h / 2 - (lines.size - 1) * lineHeight(size) / 2 + size * 7 / 20
        lines.forEachIndexed { at, line ->
            text(element.x + element.w / 2, first + at * lineHeight(size), line, size, colour, anchor = "middle")
        }
    }

    /** Words from the top left of their box, as a paragraph or a note's are. */
    private fun StringBuilder.wrapped(said: String?, x: Int, y: Int, w: Int, h: Int, size: Int, colour: String, bold: Boolean, centre: Boolean) {
        val words = said?.let(::clean)?.takeIf { it.isNotBlank() } ?: return
        val lines = wrap(words, w, size, maxOf(1, h / lineHeight(size)))
        lines.forEachIndexed { at, line ->
            text(if (centre) x + w / 2 else x, y + size + at * lineHeight(size), line, size, colour, bold = bold, anchor = if (centre) "middle" else null)
        }
    }

    private fun StringBuilder.text(x: Int, y: Int, words: String, size: Int, colour: String, bold: Boolean = false, anchor: String? = null) {
        if (words.isEmpty()) return
        append("<text x=\"$x\" y=\"$y\" font-size=\"$size\" fill=\"$colour\"")
        if (bold) append(" font-weight=\"600\"")
        if (anchor != null) append(" text-anchor=\"$anchor\"")
        append(">").append(WireframeHtml.escape(words)).append("</text>")
    }

    /**
     * Words broken into lines that fit [width] by estimate, at most [most] of
     * them; the last one kept ends in an ellipsis where there was more.
     */
    private fun wrap(words: String, width: Int, size: Int, most: Int): List<String> {
        val fits = maxOf(1, (width / (size * CHAR_WIDTH)).toInt())
        val lines = mutableListOf<String>()
        words.lines().forEach { paragraph ->
            var line = ""
            paragraph.split(' ').filter { it.isNotEmpty() }.forEach { word ->
                var rest = word
                while (rest.length > fits) {
                    if (line.isNotEmpty()) { lines += line; line = "" }
                    lines += rest.take(fits)
                    rest = rest.drop(fits)
                }
                line = when {
                    line.isEmpty() -> rest
                    line.length + 1 + rest.length <= fits -> "$line $rest"
                    else -> { lines += line; rest }
                }
            }
            lines += line
        }
        if (lines.size <= most) return lines
        val kept = lines.take(most).toMutableList()
        kept[most - 1] = fitted(kept[most - 1] + ELLIPSIS, width, size)
        return kept
    }

    /** One line held to [width] by estimate, ending in an ellipsis where it was cut. */
    private fun fitted(line: String, width: Int, size: Int): String {
        val fits = maxOf(1, (width / (size * CHAR_WIDTH)).toInt())
        return if (line.length <= fits) line else line.take(maxOf(0, fits - 1)).trimEnd() + ELLIPSIS
    }

    private fun lineHeight(size: Int): Int = maxOf(1, (size * 13 + 9) / 10)

    /** What XML 1.0 can hold: a control character other than a tab or a new line would refuse the whole picture. */
    private fun clean(text: String): String =
        text.replace("\r\n", "\n").filter { it == '\n' || it == '\t' || it >= ' ' }.replace('\t', ' ')

    private companion object {
        /** Round the drawing, and room for a frame's title over it. */
        const val PAD = 24

        /** How wide a character is, as a share of the font size, for wrapping by estimate. */
        const val CHAR_WIDTH = 0.56

        const val ELLIPSIS = "…"

        const val FONT = "Segoe UI, Roboto, Helvetica, Arial, sans-serif"

        // The editor's pencil greys - WireframeHtml's styles - and grey for the note.
        const val PAPER = "#e7e7ea"
        const val WHITE = "#ffffff"
        const val INK = "#3f3f46"
        const val MUTED = "#8a8a93"
        const val SHADE = "#ececef"
        const val FILLED = "#52525b"
        const val NOTE = "#f4f4f5"
    }
}
