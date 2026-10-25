package io.mszymanski.orknux.server.diagram

import kotlin.math.abs
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
            append("markerHeight=\"8\" orient=\"auto\"><path d=\"M 10 0 L 0 5 L 10 10 z\" fill=\"$INK\"/></marker></defs>")
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
        if (edge.kind == DiagramEdgeKind.DASHED) append(" stroke-dasharray=\"6 4\"")
        if (edge.kind != DiagramEdgeKind.LINE) append(" marker-end=\"url(#dg-head)\"")
        if (edge.kind == DiagramEdgeKind.BOTH) append(" marker-start=\"url(#dg-tail)\"")
        append("/>")
        val label = edge.label?.let(::clean)?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val shown = fitted(label, 220, 12)
        val mx = (x1 + x2) / 2
        val my = (y1 + y2) / 2
        val half = shown.length * 12 * CHAR_WIDTH / 2 + 4
        append("<rect x=\"${r(mx - half)}\" y=\"${r(my - 9)}\" width=\"${r(half * 2)}\" height=\"16\" rx=\"3\" fill=\"$PAPER\"/>")
        text(mx.toInt(), (my + 4).toInt(), shown, 12, MUTED, anchor = "middle")
    }

    /** Where a line from this box's centre towards (tx, ty) leaves the box. */
    private fun border(node: DiagramShape, tx: Double, ty: Double): Pair<Double, Double> {
        val cx = node.x + node.w / 2.0
        val cy = node.y + node.h / 2.0
        val dx = tx - cx
        val dy = ty - cy
        if (dx == 0.0 && dy == 0.0) return cx to cy
        val scale = minOf(
            if (dx != 0.0) (node.w / 2.0) / abs(dx) else Double.MAX_VALUE,
            if (dy != 0.0) (node.h / 2.0) / abs(dy) else Double.MAX_VALUE,
        )
        return (cx + dx * scale) to (cy + dy * scale)
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
