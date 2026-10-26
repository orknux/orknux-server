package io.mszymanski.orknux.server.diagram

import kotlin.math.max

/**
 * A diagram drawn as one SVG: the picture a chat shows beside the link, and
 * what the PNG, SVG and PDF exports are made from.
 *
 * Groups first, then the lines, then the boxes, so a line is never drawn over
 * the box it ends at. A line runs straight between the two boxes' centres and
 * stops at their edges; the editor routes them more prettily, but a picture in
 * a chat is for reading what is connected to what, not for measuring.
 *
 * The drawing is untrusted: every word is escaped, characters XML cannot hold
 * are dropped, a colour reaches the picture only as the `#rrggbb` the document
 * already held it to, and nothing in it is a script or a reference outside it.
 */
class DiagramSvg {

    /** The SVG of [drawing]; an empty diagram is a small blank page rather than nothing. */
    fun drawing(drawing: DiagramDrawing): String {
        val nodes = drawing.nodes
        val left = (nodes.minOfOrNull { it.x } ?: 0) - PAD
        val top = (nodes.minOfOrNull { it.y } ?: 0) - PAD
        val right = (nodes.maxOfOrNull { it.x + it.w } ?: 200) + PAD
        val bottom = (nodes.maxOfOrNull { it.y + it.h } ?: 100) + PAD
        val width = max(1, right - left)
        val height = max(1, bottom - top)
        val byId = nodes.associateBy { it.id }
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" ")
            append("viewBox=\"$left $top $width $height\" font-family=\"$FONT\">")
            // Two markers rather than auto-start-reverse, which the renderer the PNG goes through does not read.
            append("<defs><marker id=\"dg-head\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" ")
            append("markerHeight=\"8\" orient=\"auto\"><path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"$INK\"/></marker>")
            append("<marker id=\"dg-tail\" viewBox=\"0 0 10 10\" refX=\"1\" refY=\"5\" markerWidth=\"8\" ")
            append("markerHeight=\"8\" orient=\"auto\"><path d=\"M 10 0 L 0 5 L 10 10 z\" fill=\"$INK\"/></marker>")
            // The other marks, each drawn twice for the same reason: pointing out of the line's end, and out of its start.
            DiagramEndStyle.entries.filter { it != DiagramEndStyle.NONE && it != DiagramEndStyle.ARROW }.forEach { style ->
                marker(style, atEnd = true)
                marker(style, atEnd = false)
            }
            append("</defs>")
            append("<rect x=\"$left\" y=\"$top\" width=\"$width\" height=\"$height\" fill=\"$PAPER\"/>")
            nodes.filter { it.kind == DiagramNodeKind.GROUP }.forEach { node(it) }
            drawing.edges.forEach { edge -> edge(edge, byId[edge.from] ?: return@forEach, byId[edge.to] ?: return@forEach) }
            nodes.filter { it.kind != DiagramNodeKind.GROUP }.forEach { node(it) }
            append("</svg>")
        }
    }

    private fun StringBuilder.node(node: DiagramShape) {
        val (x, y, w, h) = listOf(node.x, node.y, node.w, node.h)
        val fill = node.color ?: when (node.kind) {
            DiagramNodeKind.NOTE -> NOTE
            DiagramNodeKind.GROUP -> GROUP
            else -> WHITE
        }
        val ink = if (dark(fill)) WHITE else INK
        DiagramOutline.OUTLINES[node.kind]?.let { outline(node, it, fill, ink); return }
        when (node.kind) {
            DiagramNodeKind.BOX -> rect(x, y, w, h, fill, 4)
            DiagramNodeKind.ROUNDED -> rect(x, y, w, h, fill, minOf(h / 2, 24))
            DiagramNodeKind.ELLIPSE ->
                append("<ellipse cx=\"${x + w / 2.0}\" cy=\"${y + h / 2.0}\" rx=\"${w / 2.0}\" ry=\"${h / 2.0}\" fill=\"$fill\" stroke=\"$INK\" stroke-width=\"1.5\"/>")
            DiagramNodeKind.DIAMOND ->
                append("<polygon points=\"${x + w / 2.0},$y ${x + w},${y + h / 2.0} ${x + w / 2.0},${y + h} $x,${y + h / 2.0}\" fill=\"$fill\" stroke=\"$INK\" stroke-width=\"1.5\"/>")
            DiagramNodeKind.NOTE -> rect(x, y, w, h, fill, 2, dashed = true)
            DiagramNodeKind.GROUP -> {
                rect(x, y, w, h, fill, 8, dashed = true)
                text(x + 10, y + 18, fitted(clean(node.label).lineSequence().firstOrNull().orEmpty(), w - 20, 13), 13, MUTED, bold = true)
                return
            }
            else -> Unit
        }
        val inset = if (node.kind == DiagramNodeKind.DIAMOND || node.kind == DiagramNodeKind.ELLIPSE) w / 5 else 8
        val lines = wrap(clean(node.label), w - inset * 2, SIZE, max(1, (h - 8) / LINE))
        val first = y + h / 2 - (lines.size - 1) * LINE / 2 + SIZE * 7 / 20
        lines.forEachIndexed { at, line -> text(x + w / 2, first + at * LINE, line, SIZE, ink, anchor = "middle") }
    }

    private fun StringBuilder.edge(edge: DiagramLink, from: DiagramShape, to: DiagramShape) {
        val (x1, y1) = border(from, to.x + to.w / 2.0, to.y + to.h / 2.0)
        val (x2, y2) = border(to, from.x + from.w / 2.0, from.y + from.h / 2.0)
        append("<line x1=\"${r(x1)}\" y1=\"${r(y1)}\" x2=\"${r(x2)}\" y2=\"${r(y2)}\" stroke=\"$INK\" stroke-width=\"1.5\"")
        when (edge.line) {
            DiagramLineStyle.SOLID -> Unit
            DiagramLineStyle.DASHED -> append(" stroke-dasharray=\"6 4\"")
            DiagramLineStyle.DOTTED -> append(" stroke-dasharray=\"1.5 3.5\" stroke-linecap=\"round\"")
        }
        markerOf(edge.end, atEnd = true)?.let { append(" marker-end=\"url(#$it)\"") }
        markerOf(edge.start, atEnd = false)?.let { append(" marker-start=\"url(#$it)\"") }
        append("/>")
        val label = edge.label?.let(::clean)?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val shown = fitted(label, 220, 12)
        val mx = (x1 + x2) / 2
        val my = (y1 + y2) / 2
        val half = shown.length * 12 * CHAR_WIDTH / 2 + 4
        append("<rect x=\"${r(mx - half)}\" y=\"${r(my - 9)}\" width=\"${r(half * 2)}\" height=\"16\" rx=\"3\" fill=\"$PAPER\"/>")
        text(mx.toInt(), (my + 4).toInt(), shown, 12, MUTED, anchor = "middle")
    }

    /** The marker an end is drawn with, by id; the arrow keeps the two it always had, so an old line draws as it did. */
    private fun markerOf(style: DiagramEndStyle, atEnd: Boolean): String? = when (style) {
        DiagramEndStyle.NONE -> null
        DiagramEndStyle.ARROW -> if (atEnd) "dg-head" else "dg-tail"
        else -> "dg-${if (atEnd) "end" else "start"}-${style.key}"
    }

    /**
     * One end's mark, its point or its far side on the node. Drawn at the end
     * pointing along the line, and at the start mirrored, since the renderer
     * the PNG goes through does not read auto-start-reverse.
     */
    private fun StringBuilder.marker(style: DiagramEndStyle, atEnd: Boolean) {
        val x = { at: Double -> r(if (atEnd) at else MARK - at) }
        append("<marker id=\"${markerOf(style, atEnd)}\" viewBox=\"0 0 $MARK $MARK\" refX=\"${x(MARK)}\" refY=\"${r(MARK / 2)}\" ")
        append("markerWidth=\"10\" markerHeight=\"10\" orient=\"auto\">")
        val stroke = "stroke=\"$INK\" stroke-width=\"1.2\" stroke-linejoin=\"round\""
        when (style) {
            DiagramEndStyle.OPEN -> append("<path d=\"M ${x(1.0)} 1 L ${x(MARK)} 6 L ${x(1.0)} 11\" fill=\"none\" $stroke/>")
            DiagramEndStyle.TRIANGLE -> append("<path d=\"M ${x(1.0)} 1 L ${x(MARK)} 6 L ${x(1.0)} 11 z\" fill=\"$PAPER\" $stroke/>")
            DiagramEndStyle.DIAMOND, DiagramEndStyle.FILLED_DIAMOND -> {
                val fill = if (style == DiagramEndStyle.DIAMOND) PAPER else INK
                append("<path d=\"M ${x(0.5)} 6 L ${x(6.0)} 1.5 L ${x(MARK)} 6 L ${x(6.0)} 10.5 z\" fill=\"$fill\" $stroke/>")
            }
            DiagramEndStyle.CIRCLE -> append("<circle cx=\"${x(7.0)}\" cy=\"6\" r=\"4.5\" fill=\"$PAPER\" $stroke/>")
            else -> Unit
        }
        append("</marker>")
    }

    /** Where a line from this shape's centre towards (tx, ty) leaves the outline drawn. */
    private fun border(node: DiagramShape, tx: Double, ty: Double): Pair<Double, Double> = DiagramOutline.border(node, tx, ty)

    /** A shape drawn from its [DiagramOutline], with its words where that outline keeps them. */
    private fun StringBuilder.outline(node: DiagramShape, outline: DiagramOutline.Outline, fill: String, ink: String) {
        val frame = DiagramOutline.frame(node, outline)
        val (fx, fy, fw, fh) = frame.toList()
        outline.pieces.forEach { piece ->
            // A piece that is only lines - a database's rim, an actor's limbs - is not filled.
            val painted = if (piece.filled) fill else "none"
            when (piece) {
                is DiagramOutline.Path -> append(
                    "<path d=\"${DiagramOutline.scaled(piece.d, frame)}\" fill=\"$painted\" " +
                        "stroke=\"$INK\" stroke-width=\"1.5\" stroke-linejoin=\"round\"/>",
                )
                is DiagramOutline.Oval -> append(
                    "<ellipse cx=\"${r(fx + piece.cx * fw / 100)}\" cy=\"${r(fy + piece.cy * fh / 100)}\" " +
                        "rx=\"${r(piece.rx * fw / 100)}\" ry=\"${r(piece.ry * fh / 100)}\" fill=\"$painted\" stroke=\"$INK\" stroke-width=\"1.5\"/>",
                )
            }
        }
        val words = clean(node.label)
        if (outline.icon) {
            // Beneath the icon, on the paper rather than on the fill, so it is ink whatever the colour.
            val line = fitted(words.lineSequence().firstOrNull().orEmpty(), node.w + 40, SIZE)
            text(node.x + node.w / 2, node.y + node.h - 6, line, SIZE, INK, anchor = "middle")
            return
        }
        if (node.kind == DiagramNodeKind.CLASS) {
            compartments(node, DiagramOutline.compartments(words), ink)
            return
        }
        val inside = outline.inside
        val left = node.x + (inside.left * node.w / 100).toInt()
        val right = node.x + (inside.right * node.w / 100).toInt()
        val top = node.y + (inside.top * node.h / 100).toInt()
        val bottom = node.y + (inside.bottom * node.h / 100).toInt()
        val lines = wrap(words, right - left, SIZE, max(1, (bottom - top) / LINE))
        val first = (top + bottom) / 2 - (lines.size - 1) * LINE / 2 + SIZE * 7 / 20
        lines.forEachIndexed { at, line -> text((left + right) / 2, first + at * LINE, line, SIZE, ink, anchor = "middle") }
    }

    /** A class: its name bold in the middle, each compartment under a rule, its lines from the left. */
    private fun StringBuilder.compartments(node: DiagramShape, parts: List<List<String>>, ink: String) {
        var y = node.y + 4
        val bottom = node.y + node.h - 4
        parts.forEachIndexed { at, lines ->
            if (at > 0) {
                append("<line x1=\"${node.x}\" y1=\"$y\" x2=\"${node.x + node.w}\" y2=\"$y\" stroke=\"$INK\" stroke-width=\"1.5\"/>")
                y += 2
            }
            lines.forEach { line ->
                if (y + LINE > bottom) return
                val shown = fitted(line, node.w - 16, SIZE)
                if (at == 0) text(node.x + node.w / 2, y + SIZE, shown, SIZE, ink, bold = true, anchor = "middle")
                else text(node.x + 8, y + SIZE, shown, SIZE, ink)
                y += LINE
            }
            y += 4
        }
    }

    private fun StringBuilder.rect(x: Int, y: Int, w: Int, h: Int, fill: String, radius: Int, dashed: Boolean = false) {
        append("<rect x=\"$x\" y=\"$y\" width=\"$w\" height=\"$h\" rx=\"$radius\" fill=\"$fill\" stroke=\"$INK\" stroke-width=\"1.5\"")
        if (dashed) append(" stroke-dasharray=\"5 4\"")
        append("/>")
    }

    private fun StringBuilder.text(x: Int, y: Int, words: String, size: Int, colour: String, bold: Boolean = false, anchor: String? = null) {
        if (words.isEmpty()) return
        append("<text x=\"$x\" y=\"$y\" font-size=\"$size\" fill=\"$colour\"")
        if (bold) append(" font-weight=\"600\"")
        if (anchor != null) append(" text-anchor=\"$anchor\"")
        append(">").append(escape(words)).append("</text>")
    }

    private fun wrap(words: String, width: Int, size: Int, most: Int): List<String> {
        val fits = max(1, (width / (size * CHAR_WIDTH)).toInt())
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
            if (line.isNotEmpty()) lines += line
        }
        if (lines.size <= most) return lines
        val kept = lines.take(most).toMutableList()
        kept[most - 1] = fitted(kept[most - 1] + ELLIPSIS, width, size)
        return kept
    }

    private fun fitted(line: String, width: Int, size: Int): String {
        val fits = max(1, (width / (size * CHAR_WIDTH)).toInt())
        return if (line.length <= fits) line else line.take(max(0, fits - 1)).trimEnd() + ELLIPSIS
    }

    /** Whether words on this fill want to be white. */
    private fun dark(fill: String): Boolean {
        if (!fill.startsWith("#") || fill.length != 7) return false
        val r = fill.substring(1, 3).toInt(16)
        val g = fill.substring(3, 5).toInt(16)
        val b = fill.substring(5, 7).toInt(16)
        return (r * 299 + g * 587 + b * 114) / 1000 < 128
    }

    private fun r(value: Double): String = "%.1f".format(java.util.Locale.ROOT, value)

    companion object {
        const val PAD = 24
        /** The square a line end's mark is drawn in, in its own units; a format, not a behaviour. */
        private const val MARK = 12.0
        private const val SIZE = 14
        private const val LINE = 18
        private const val CHAR_WIDTH = 0.56
        private const val ELLIPSIS = "…"
        private const val FONT = "Segoe UI, Roboto, Helvetica, Arial, sans-serif"
        private const val PAPER = "#f4f4f5"
        private const val WHITE = "#ffffff"
        private const val INK = "#3f3f46"
        private const val MUTED = "#71717a"
        private const val NOTE = "#fef9c3"
        private const val GROUP = "#eef2f7"

        /** What XML 1.0 can hold: a control character other than a tab or a new line would refuse the whole picture. */
        fun clean(text: String): String =
            text.replace("\r\n", "\n").filter { it == '\n' || it == '\t' || it >= ' ' }.replace('\t', ' ')

        fun escape(text: String): String = buildString {
            text.forEach {
                when (it) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(it)
                }
            }
        }
    }
}
