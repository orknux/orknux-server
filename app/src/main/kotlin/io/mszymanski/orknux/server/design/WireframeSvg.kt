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

    /** The SVG of [elements], or null where there is nothing to draw. One at a time: the ids of its gradients are counted. */
    @Synchronized
    fun drawing(elements: List<WireframeElement>): String? {
        if (elements.isEmpty()) return null
        val left = elements.minOf { minOf(it.x, it.x + it.w) } - PAD
        val top = elements.minOf { minOf(it.y, it.y + it.h) } - PAD
        val right = elements.maxOf { maxOf(it.x, it.x + it.w) } + PAD
        val bottom = elements.maxOf { maxOf(it.y, it.y + it.h) } + PAD
        val width = maxOf(1, right - left)
        val height = maxOf(1, bottom - top)
        gradients = 0
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" ")
            append("viewBox=\"$left $top $width $height\" font-family=\"$FONT\">")
            append("<defs><marker id=\"wf-arrowhead\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" ")
            // "auto", not "auto-start-reverse": the renderer that makes the chat's PNG refuses the whole picture over it. Heads are only ever at the end.
            append("markerHeight=\"8\" orient=\"auto\"><path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"$INK\"/></marker>")
            // SVG 1.1's way to a drop shadow, which the renderer that makes the chat's PNG reads; feDropShadow it does not.
            WireframeStyle.Shadow.entries.filter { it != WireframeStyle.Shadow.NONE }.forEach { shadow ->
                append("<filter id=\"wf-shadow-${shadow.key}\" x=\"-50%\" y=\"-50%\" width=\"200%\" height=\"200%\">")
                append("<feGaussianBlur in=\"SourceAlpha\" stdDeviation=\"${WireframeStyle.number(shadow.blur / 2.0)}\"/>")
                append("<feOffset dy=\"${shadow.dy}\" result=\"blurred\"/><feFlood flood-color=\"#000000\" flood-opacity=\"${shadow.opacity}\"/>")
                append("<feComposite in2=\"blurred\" operator=\"in\"/><feMerge><feMergeNode/><feMergeNode in=\"SourceGraphic\"/></feMerge></filter>")
            }
            append("</defs>")
            append("<rect x=\"$left\" y=\"$top\" width=\"$width\" height=\"$height\" fill=\"$PAPER\"/>")
            // Frames under what sits in them, whatever order they were drawn in: a frame is the screen.
            elements.filter { it.type == WireframeElementType.FRAME }.forEach { element(it) }
            elements.filter { it.type != WireframeElementType.FRAME }.forEach { element(it) }
            append("</svg>")
        }
    }

    /** How many gradients and heads the drawing being written has defined, so each has an id of its own. */
    private var gradients = 0

    private fun StringBuilder.element(element: WireframeElement) {
        val x = element.x
        val y = element.y
        val w = element.w
        val h = element.h
        val look = WireframeStyle.look(element.props)
        val ink = look.color
        when (element.type) {
            WireframeElementType.FRAME -> {
                box(element, look, fill = WHITE, stroke = INK, width = 1.5, radius = 10)
                element.text?.let { title -> text(x, y - 6, clean(title).lineSequence().first(), 12, ink ?: MUTED) }
            }
            WireframeElementType.RECT -> {
                box(element, look, fill = SHADE, stroke = MUTED, width = 1.0, radius = 6)
                centred(element, size = 14, colour = ink ?: INK)
            }
            WireframeElementType.TEXT -> {
                val size = (element.props["size"] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toInt()
                    ?.coerceIn(WireframeHtml.TEXT_LEAST, WireframeHtml.TEXT_MOST) ?: 14
                val bold = element.props["weight"]?.toString()?.trim()?.lowercase() == "bold"
                box(element, look, fill = null, stroke = null, width = 1.0, radius = 0)
                wrapped(element.text, x, y, w, h, size, ink ?: INK, bold = bold, centre = false)
            }
            WireframeElementType.BUTTON -> {
                val primary = element.props["variant"]?.toString()?.trim()?.lowercase() == "primary"
                box(element, look, fill = if (primary) FILLED else WHITE, stroke = if (primary) FILLED else INK, width = 1.5, radius = 8)
                centred(element, size = 14, colour = ink ?: if (primary) WHITE else INK)
            }
            WireframeElementType.INPUT -> {
                box(element, look, fill = WHITE, stroke = MUTED, width = 1.0, radius = 6)
                element.text?.let { words ->
                    val line = fitted(clean(words).lineSequence().first(), w - 24, 14)
                    text(x + 12, y + h / 2 + 5, line, 14, ink ?: MUTED)
                }
            }
            WireframeElementType.IMAGE -> {
                box(element, look, fill = SHADE, stroke = MUTED, width = 1.0, radius = 4)
                append("<line x1=\"$x\" y1=\"$y\" x2=\"${x + w}\" y2=\"${y + h}\" stroke=\"$MUTED\" stroke-width=\"1\"/>")
                append("<line x1=\"${x + w}\" y1=\"$y\" x2=\"$x\" y2=\"${y + h}\" stroke=\"$MUTED\" stroke-width=\"1\"/>")
                element.text?.let { caption ->
                    val line = fitted(clean(caption).lineSequence().first(), w - 12, 12)
                    text(x + w / 2, y + h - 8, line, 12, ink ?: MUTED, anchor = "middle")
                }
            }
            WireframeElementType.NOTE -> {
                box(element, look, fill = NOTE, stroke = MUTED, width = 1.0, radius = 2, dashed = true)
                wrapped(element.text, x + 10, y + 10, w - 20, h - 20, 13, ink ?: INK, bold = false, centre = false)
            }
            WireframeElementType.CHECKBOX, WireframeElementType.RADIO, WireframeElementType.TOGGLE -> {
                box(element, look, fill = null, stroke = null, width = 1.0, radius = 0)
                val middle = y + h / 2
                val on = look.checked
                val control = when (element.type) {
                    WireframeElementType.TOGGLE -> {
                        append("<rect x=\"$x\" y=\"${middle - 9}\" width=\"32\" height=\"18\" rx=\"9\" fill=\"${if (on) FILLED else WHITE}\" stroke=\"${if (on) FILLED else MUTED}\" stroke-width=\"1.5\"/>")
                        append("<circle cx=\"${if (on) x + 23 else x + 9}\" cy=\"$middle\" r=\"6\" fill=\"${if (on) WHITE else MUTED}\"/>")
                        32
                    }
                    WireframeElementType.RADIO -> {
                        append("<circle cx=\"${x + 8}\" cy=\"$middle\" r=\"7.5\" fill=\"$WHITE\" stroke=\"$INK\" stroke-width=\"1.5\"/>")
                        if (on) append("<circle cx=\"${x + 8}\" cy=\"$middle\" r=\"4\" fill=\"$INK\"/>")
                        16
                    }
                    else -> {
                        append("<rect x=\"$x\" y=\"${middle - 8}\" width=\"16\" height=\"16\" rx=\"3\" fill=\"${if (on) FILLED else WHITE}\" stroke=\"${if (on) FILLED else INK}\" stroke-width=\"1.5\"/>")
                        if (on) {
                            append("<path d=\"M ${x + 4} $middle L ${x + 7} ${middle + 3} L ${x + 12} ${middle - 3}\" fill=\"none\" stroke=\"$WHITE\" ")
                            append("stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")
                        }
                        16
                    }
                }
                element.text?.let { words ->
                    val line = fitted(clean(words).lineSequence().first(), w - control - 8, 14)
                    text(x + control + 8, middle + 5, line, 14, ink ?: INK)
                }
            }
            WireframeElementType.LINE, WireframeElementType.ARROW -> {
                val stroke = border(look, INK, 1.5)
                val head = if (element.type != WireframeElementType.ARROW) {
                    ""
                } else if (stroke.colour == INK) {
                    " marker-end=\"url(#wf-arrowhead)\""
                } else {
                    // A coloured arrow has a head of its own colour.
                    val id = "wf-head-${++gradients}"
                    append("<defs><marker id=\"$id\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" markerHeight=\"8\" orient=\"auto\">")
                    append("<path d=\"M 0 0 L 10 5 L 0 10 z\"${paint("fill", stroke.colour ?: INK)}/></marker></defs>")
                    " marker-end=\"url(#$id)\""
                }
                append("<line x1=\"$x\" y1=\"$y\" x2=\"${x + w}\" y2=\"${y + h}\"")
                append(strokeOf(stroke)).append(" fill=\"none\"$head")
                look.shadow?.takeIf { it != WireframeStyle.Shadow.NONE }?.let { append(" filter=\"url(#wf-shadow-${it.key})\"") }
                append("/>")
            }
        }
    }

    /** A border as drawn: its colour (null for none), its width, and its dashes. */
    private data class Stroke(val colour: String?, val width: Double, val dashes: String?)

    /**
     * The kind's own outline - [colour] at [width], none where [colour] is
     * null - with whatever the props say over it. A width or a colour given
     * to a kind that has no outline draws a solid one.
     */
    private fun border(look: WireframeStyle.Look, colour: String?, width: Double, dashed: Boolean = false): Stroke {
        val said = look.borderWidth != null || look.borderStyle != null || look.borderColor != null
        if (!said) return Stroke(colour, width, if (dashed) "4 3" else null)
        val drawn = look.borderWidth?.toDouble() ?: width
        val style = look.borderStyle ?: if (dashed && colour != null) WireframeStyle.BorderStyle.DASHED else WireframeStyle.BorderStyle.SOLID
        if (style == WireframeStyle.BorderStyle.NONE || drawn <= 0) return Stroke(null, 0.0, null)
        val dashes = when (style) {
            WireframeStyle.BorderStyle.DASHED -> "${WireframeStyle.number(maxOf(4.0, drawn * 3))} ${WireframeStyle.number(maxOf(3.0, drawn * 2))}"
            WireframeStyle.BorderStyle.DOTTED -> "${WireframeStyle.number(drawn)} ${WireframeStyle.number(drawn)}"
            else -> null
        }
        return Stroke(look.borderColor ?: colour ?: INK, drawn, dashes)
    }

    private fun strokeOf(stroke: Stroke): String {
        val colour = stroke.colour ?: return " stroke=\"none\""
        return paint("stroke", colour) + " stroke-width=\"${WireframeStyle.number(stroke.width)}\"" +
            (stroke.dashes?.let { " stroke-dasharray=\"$it\"" } ?: "")
    }

    /** A colour as an attribute, its opacity apart: the renderer behind the chat's PNG takes six digits only. */
    private fun paint(attribute: String, colour: String): String {
        val (hex, opacity) = WireframeStyle.opaque(colour)
        return " $attribute=\"$hex\"" + if (opacity < 1) " $attribute-opacity=\"${WireframeStyle.number(opacity)}\"" else ""
    }

    /**
     * An element's box: the kind's own [fill] and outline, none where they are
     * null, under what its props say - a colour or a gradient, a border, a
     * corner and a shadow. A kind with no box of its own whose props say
     * nothing draws nothing here.
     */
    private fun StringBuilder.box(
        element: WireframeElement,
        look: WireframeStyle.Look,
        fill: String?,
        stroke: String?,
        width: Double,
        radius: Int,
        dashed: Boolean = false,
    ) {
        val x = element.x
        val y = element.y
        val w = maxOf(0, element.w)
        val h = maxOf(0, element.h)
        val outline = border(look, stroke, width, dashed)
        val shadow = look.shadow?.takeIf { it != WireframeStyle.Shadow.NONE }
        val painted = when (val asked = look.fill) {
            null -> fill?.let { paint("fill", it) }
            is WireframeStyle.Fill.Solid -> paint("fill", asked.colour)
            is WireframeStyle.Fill.Gradient -> {
                val id = "wf-fill-${++gradients}"
                val (x1, y1, x2, y2) = WireframeStyle.line(asked.angle, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble()).toList()
                append("<defs><linearGradient id=\"$id\" gradientUnits=\"userSpaceOnUse\" ")
                append("x1=\"${WireframeStyle.number(x1)}\" y1=\"${WireframeStyle.number(y1)}\" x2=\"${WireframeStyle.number(x2)}\" y2=\"${WireframeStyle.number(y2)}\">")
                asked.stops.forEach { stop ->
                    val (hex, opacity) = WireframeStyle.opaque(stop.colour)
                    append("<stop offset=\"${WireframeStyle.number(stop.at)}%\" stop-color=\"$hex\"")
                    if (opacity < 1) append(" stop-opacity=\"${WireframeStyle.number(opacity)}\"")
                    append("/>")
                }
                append("</linearGradient></defs>")
                " fill=\"url(#$id)\""
            }
        }
        if (painted == null && outline.colour == null && shadow == null) return
        append("<rect x=\"$x\" y=\"$y\" width=\"$w\" height=\"$h\" rx=\"${look.radius ?: radius}\"")
        append(painted ?: " fill=\"none\"").append(strokeOf(outline))
        shadow?.let { append(" filter=\"url(#wf-shadow-${it.key})\"") }
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
        append("<text x=\"$x\" y=\"$y\" font-size=\"$size\"").append(paint("fill", colour))
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
