package io.mszymanski.orknux.server.diagram

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

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
    /**
     * Where the picture of [drawing] starts and how big it is, in the
     * drawing's own units: what is shown, padded. What lays anything over the
     * picture - a PDF's links - places it by this.
     */
    fun frame(drawing: DiagramDrawing): List<Int> {
        // Each node's box as it is drawn: a turned one reaches past the box it was turned from.
        val shown = drawing.visible()
        val byId = shown.nodes.associateBy { it.id }
        /*
         * And each line as it is drawn: a run dragged aside or a line bent out
         * stands outside every box, and a frame measured from the boxes alone
         * cut it off the picture.
         */
        val corners = shown.edges.flatMap { edge ->
            val from = byId[edge.from] ?: return@flatMap emptyList()
            val to = byId[edge.to] ?: return@flatMap emptyList()
            DiagramRoute.of(edge, from, to).trace.map { (x, y) -> doubleArrayOf(x, y, x, y) }
        }
        val boxes = shown.nodes.map { DiagramOutline.extent(it) } + corners
        val left = (boxes.minOfOrNull { kotlin.math.floor(it[0]).toInt() } ?: 0) - PAD
        val top = (boxes.minOfOrNull { kotlin.math.floor(it[1]).toInt() } ?: 0) - PAD
        val right = (boxes.maxOfOrNull { kotlin.math.ceil(it[2]).toInt() } ?: 200) + PAD
        val bottom = (boxes.maxOfOrNull { kotlin.math.ceil(it[3]).toInt() } ?: 100) + PAD
        return listOf(left, top, max(1, right - left), max(1, bottom - top))
    }

    /**
     * The drawing as an SVG. [pictures] answers a picture's data address; [read] what a code or a Jira shape
     * last read - its lines, or its issue as JSON - and an export never fetches, so a shape not yet read is an
     * empty card naming its source.
     */
    fun drawing(drawing: DiagramDrawing, read: (DiagramShape) -> String? = { null }, pictures: (DiagramShape) -> String? = { null }): String {
        // Only what is shown: a layer hidden in the editor is out of every picture of it too.
        @Suppress("NAME_SHADOWING")
        val drawing = drawing.visible()
        val nodes = drawing.nodes
        val (left, top, width, height) = frame(drawing)
        val byId = nodes.associateBy { it.id }
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"$width\" height=\"$height\" ")
            append("viewBox=\"$left $top $width $height\" font-family=\"$FONT\">")
            // Two markers rather than auto-start-reverse, which the renderer the PNG goes through does not read.
            // Sized in the drawing's own pixels, as the editor draws them: sized by the line, a 4 pixel line's arrowhead was 32 across.
            append("<defs><marker id=\"dg-head\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerUnits=\"userSpaceOnUse\" markerWidth=\"12\" ")
            append("markerHeight=\"12\" orient=\"auto\"><path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"$INK\"/></marker>")
            append("<marker id=\"dg-tail\" viewBox=\"0 0 10 10\" refX=\"1\" refY=\"5\" markerUnits=\"userSpaceOnUse\" markerWidth=\"12\" ")
            append("markerHeight=\"12\" orient=\"auto\"><path d=\"M 10 0 L 0 5 L 10 10 z\" fill=\"$INK\"/></marker>")
            // The other marks, each drawn twice for the same reason: pointing out of the line's end, and out of its start.
            DiagramEndStyle.entries.filter { it != DiagramEndStyle.NONE && it != DiagramEndStyle.ARROW }.forEach { style ->
                marker(style, atEnd = true)
                marker(style, atEnd = false)
            }
            // Again in each colour a line is given, so its ends are its colour: a marker cannot take the line's own.
            drawing.edges.mapNotNull { it.color }.distinct().forEach { colour ->
                val tag = colour.drop(1)
                append("<marker id=\"dg-head-$tag\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerUnits=\"userSpaceOnUse\" markerWidth=\"12\" ")
                append("markerHeight=\"12\" orient=\"auto\"><path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"$colour\"/></marker>")
                append("<marker id=\"dg-tail-$tag\" viewBox=\"0 0 10 10\" refX=\"1\" refY=\"5\" markerUnits=\"userSpaceOnUse\" markerWidth=\"12\" ")
                append("markerHeight=\"12\" orient=\"auto\"><path d=\"M 10 0 L 0 5 L 10 10 z\" fill=\"$colour\"/></marker>")
                DiagramEndStyle.entries.filter { it != DiagramEndStyle.NONE && it != DiagramEndStyle.ARROW }.forEach { style ->
                    marker(style, atEnd = true, colour)
                    marker(style, atEnd = false, colour)
                }
            }
            append("</defs>")
            append("<rect x=\"$left\" y=\"$top\" width=\"$width\" height=\"$height\" fill=\"$PAPER\"/>")
            nodes.filter { it.kind == DiagramNodeKind.GROUP }.forEach { node(it) }
            // Each line in drawing order, hopping over the straight runs of every line drawn before it.
            val drawn = mutableListOf<List<Pair<Double, Double>>>()
            drawing.edges.forEach { edge ->
                val from = byId[edge.from] ?: return@forEach
                val to = byId[edge.to] ?: return@forEach
                val route = DiagramRoute.of(edge, from, to)
                // Read before this line joins it, so the list itself is enough: no copy per line.
                edge(edge, from, to, route, drawn)
                route.points?.let { drawn += it }
            }
            nodes.filter { it.kind != DiagramNodeKind.GROUP }.forEach { node(it, pictures, read) }
            append("</svg>")
        }
    }

    /**
     * Whether [node]'s label is cut where it is drawn: more lines than its box
     * holds, so the last ends in an ellipsis, or a word too long for a line,
     * broken across two. Worked out the way [drawing] lays the words out, so
     * what [DiagramCheck] calls cut is what the picture shows cut.
     */
    fun cut(node: DiagramShape): Boolean {
        val words = clean(node.label).trim()
        if (words.isEmpty()) return false
        val outline = DiagramOutline.of(node.kind, node.w, node.h)
        if (node.kind == DiagramNodeKind.GROUP) return fitted(words.lineSequence().first(), node.w - 20, sizeOf(node, GROUP_SIZE)) != words.lineSequence().first()
        if (outline?.icon == true) return fitted(words.lineSequence().first(), node.w + 40, sizeOf(node)) != words.lineSequence().first()
        if (node.kind == DiagramNodeKind.CLASS) return false
        if (node.kind == DiagramNodeKind.IMAGE) return fitted(words.lineSequence().first(), node.w, sizeOf(node)) != words.lineSequence().first()
        val (width, height) = if (outline != null) {
            val inside = outline.inside
            ((inside.right - inside.left) * node.w / 100).toInt() to ((inside.bottom - inside.top) * node.h / 100).toInt()
        } else {
            val inset = if (node.kind == DiagramNodeKind.DIAMOND || node.kind == DiagramNodeKind.ELLIPSE) node.w / 5 else 8
            (node.w - inset * 2) to (node.h - 8)
        }
        val fits = max(1, (width / (sizeOf(node) * CHAR_WIDTH)).toInt())
        if (words.split(Regex("\\s+")).any { it.length > fits }) return true
        return wrap(words, width, sizeOf(node), Int.MAX_VALUE).size > max(1, height / lineOf(node))
    }

    /** Whether a line's label is cut short where it is drawn. */
    fun cut(label: String): Boolean {
        val line = clean(label).lineSequence().firstOrNull().orEmpty()
        return label.contains('\n') || fitted(line, EDGE_LABEL_WIDTH, 12) != line
    }

    /** A node as it is drawn: turned about its middle where it is, words and all, as the editor turns it. */
    private fun StringBuilder.node(node: DiagramShape, pictures: (DiagramShape) -> String? = { null }, read: (DiagramShape) -> String? = { null }) {
        val turn = node.rotation ?: return upright(node, pictures, read)
        append("<g transform=\"rotate($turn ${r(node.x + node.w / 2.0)} ${r(node.y + node.h / 2.0)})\">")
        upright(node, pictures, read)
        append("</g>")
    }

    private fun StringBuilder.upright(node: DiagramShape, pictures: (DiagramShape) -> String?, read: (DiagramShape) -> String?) {
        val (x, y, w, h) = listOf(node.x, node.y, node.w, node.h)
        if (node.kind == DiagramNodeKind.IMAGE) {
            picture(node, pictures(node))
            return
        }
        if (node.kind == DiagramNodeKind.CODE) {
            code(node, read(node))
            return
        }
        if (node.kind == DiagramNodeKind.JIRA) {
            jira(node, read(node))
            return
        }
        val fill = node.color ?: when (node.kind) {
            DiagramNodeKind.NOTE -> NOTE
            DiagramNodeKind.GROUP -> GROUP
            else -> WHITE
        }
        val ink = if (dark(fill)) WHITE else INK
        DiagramOutline.of(node.kind, node.w, node.h)?.let { outline(node, it, fill, ink); return }
        when (node.kind) {
            DiagramNodeKind.BOX -> rect(x, y, w, h, fill, 4, stroke = outlined(node))
            DiagramNodeKind.ROUNDED -> rect(x, y, w, h, fill, minOf(h / 2, 24), stroke = outlined(node))
            // Words alone: no box, no outline, no fill unless one was given; the words start at the left, as typed.
            DiagramNodeKind.TEXT -> {
                if (node.color != null) rect(x, y, w, h, fill, 2, stroke = "stroke=\"none\"")
                val lines = wrap(clean(node.label), w - 8, sizeOf(node), max(1, (h - 4) / lineOf(node)))
                // Text that leads somewhere is drawn as the editor draws it: a link's blue, underlined.
                val linked = !node.link.isNullOrBlank()
                val align = node.align ?: DiagramTextAlign.LEFT
                setWords(lines, x + 4, x + w - 4, y + 2, y + h - 2, align, node.valign ?: DiagramTextValign.MIDDLE, if (linked) LINK_INK else if (node.color != null) ink else INK, sizeOf(node), lineOf(node), label = node.label) { line, at, baseline ->
                    if (linked && line.isNotBlank()) {
                        val length = (line.length * sizeOf(node) * CHAR_WIDTH).toInt()
                        // Under the words, wherever they were set: from their left end, which the anchor put at, before or about `at`.
                        val from = when (align) {
                            DiagramTextAlign.LEFT -> at
                            DiagramTextAlign.CENTER -> at - length / 2
                            DiagramTextAlign.RIGHT -> at - length
                        }
                        append("<line x1=\"$from\" y1=\"${baseline + 3}\" x2=\"${from + length}\" y2=\"${baseline + 3}\" stroke=\"$LINK_INK\" stroke-width=\"1\"/>")
                    }
                }
                return
            }
            // A link card: its name, marked as a link, on a card - where it leads cannot be followed from a picture.
            DiagramNodeKind.LINK -> {
                rect(x, y, w, h, fill, 8, stroke = outlined(node))
                val name = fitted("↗ " + clean(node.label).lineSequence().firstOrNull().orEmpty(), w - 20, sizeOf(node))
                // A colour every word of its label was given is the card's words' colour, as the editor draws it.
                val colours = stylesOf(node.label)?.filterIndexed { at, _ -> !clean(node.label)[at].isWhitespace() }?.map { it.color }?.distinct()
                text(x + 12, y + h / 2 + sizeOf(node) * 7 / 20, name, sizeOf(node), colours?.singleOrNull() ?: LINK_INK, bold = true)
                return
            }
            DiagramNodeKind.ELLIPSE ->
                append("<ellipse cx=\"${x + w / 2.0}\" cy=\"${y + h / 2.0}\" rx=\"${w / 2.0}\" ry=\"${h / 2.0}\" fill=\"$fill\" ${outlined(node)}/>")
            DiagramNodeKind.DIAMOND ->
                append("<polygon points=\"${x + w / 2.0},$y ${x + w},${y + h / 2.0} ${x + w / 2.0},${y + h} $x,${y + h / 2.0}\" fill=\"$fill\" ${outlined(node)}/>")
            DiagramNodeKind.NOTE -> rect(x, y, w, h, fill, 2, dashed = true, stroke = outlined(node))
            DiagramNodeKind.GROUP -> {
                rect(x, y, w, h, fill, 8, dashed = true, stroke = outlined(node))
                val size = sizeOf(node, GROUP_SIZE)
                val name = fitted(clean(node.label).lineSequence().firstOrNull().orEmpty(), w - 20, size)
                // A group's name is at its top left, where it is out of the way of what the group holds, unless it was put elsewhere.
                if (node.align == null && node.valign == null) text(x + 10, y + 5 + size, name, size, MUTED, bold = true)
                else setWords(listOf(name), x + 10, x + w - 10, y + 5, y + h - 5, node.align ?: DiagramTextAlign.LEFT, node.valign ?: DiagramTextValign.TOP, MUTED, size, lineOf(node), bold = true)
                return
            }
            else -> Unit
        }
        val rounded = node.kind == DiagramNodeKind.DIAMOND || node.kind == DiagramNodeKind.ELLIPSE
        // Fitted inside a diamond or an ellipse, the words are set as wide as the room they were fitted to, not the box.
        val inset = if (rounded && node.textFit == true) max(8, (w - fitted(node).second) / 2) else if (rounded) w / 5 else 8
        val lines = wrap(clean(node.label), w - inset * 2, sizeOf(node), max(1, (h - 8) / lineOf(node)))
        setWords(lines, x + inset, x + w - inset, y + 4, y + h - 4, node.align ?: DiagramTextAlign.CENTER, node.valign ?: DiagramTextValign.MIDDLE, ink, sizeOf(node), lineOf(node), label = node.label)
    }

    /**
     * A box's lines of words, set in the room they have: across it from the
     * left, about the middle or up to the right, and down it from the top,
     * about the middle or up from the bottom - as the editor sets them.
     */
    private fun StringBuilder.setWords(
        lines: List<String>, left: Int, right: Int, top: Int, bottom: Int,
        align: DiagramTextAlign, valign: DiagramTextValign, ink: String, size: Int = SIZE, line: Int = LINE, bold: Boolean = false,
        /** The label these lines were wrapped from, for the parts of it that are styled; null draws them plain. */
        label: String? = null,
        each: (line: String, x: Int, baseline: Int) -> Unit = { _, _, _ -> },
    ) {
        val styles = label?.let(::stylesOf)
        val source = if (styles != null) clean(label) else ""
        var cursor = 0
        val (x, anchor) = when (align) {
            DiagramTextAlign.LEFT -> left to null
            DiagramTextAlign.CENTER -> (left + right) / 2 to "middle"
            DiagramTextAlign.RIGHT -> right to "end"
        }
        // The baseline of the first line: a line's box is [line] tall and its words sit about seven tenths down it.
        val first = when (valign) {
            DiagramTextValign.TOP -> top + line / 2
            DiagramTextValign.MIDDLE -> (top + bottom) / 2 - (lines.size - 1) * line / 2
            DiagramTextValign.BOTTOM -> bottom - line / 2 - (lines.size - 1) * line
        } + size * 7 / 20
        lines.forEachIndexed { at, words ->
            if (styles == null) {
                text(x, first + at * line, words, size, ink, bold = bold, anchor = anchor)
            } else {
                /*
                 * Each character of the wrapped line found in the label it came from, in order - wrapping only
                 * drops spaces and line breaks, and adds an ellipsis - so it is drawn in the style it has there.
                 */
                val styled = words.map { ch ->
                    while (cursor < source.length && source[cursor] != ch && source[cursor].isWhitespace()) cursor += 1
                    if (cursor < source.length && source[cursor] == ch) styles[cursor++] to ch else DiagramStyledText.PLAIN to ch
                }
                styledText(x, first + at * line, styled, size, ink, bold, anchor)
            }
            each(words, x, first + at * line)
        }
    }

    private fun StringBuilder.edge(edge: DiagramLink, from: DiagramShape, to: DiagramShape, route: DiagramRoute.Route, under: List<List<Pair<Double, Double>>>) {
        val jumped = route.points?.let { DiagramRoute.jumped(it, under) }?.takeIf { " A " in it }
        if (jumped != null) {
            append("<path d=\"$jumped\" fill=\"none\" ${stroked(edge)} stroke-linejoin=\"round\"")
        } else if (edge.routing == DiagramRouting.STRAIGHT) {
            // As a line element, as every line was drawn before there was a choice -
            // between the ends the route found, which is where a pinned side is
            // read: worked out again here from the two middles, a pinned line was
            // exported leaving the side that faced the other box.
            val (x1, y1) = route.points?.firstOrNull() ?: border(from, to.x + to.w / 2.0, to.y + to.h / 2.0)
            val (x2, y2) = route.points?.lastOrNull() ?: border(to, from.x + from.w / 2.0, from.y + from.h / 2.0)
            append("<line x1=\"${r(x1)}\" y1=\"${r(y1)}\" x2=\"${r(x2)}\" y2=\"${r(y2)}\" ${stroked(edge)}")
        } else {
            append("<path d=\"${route.d}\" fill=\"none\" ${stroked(edge)} stroke-linejoin=\"round\"")
        }
        when (edge.line) {
            DiagramLineStyle.SOLID -> Unit
            DiagramLineStyle.DASHED -> append(" stroke-dasharray=\"6 4\"")
            DiagramLineStyle.DOTTED -> append(" stroke-dasharray=\"1.5 3.5\" stroke-linecap=\"round\"")
        }
        markerOf(edge.end, atEnd = true, edge.color)?.let { append(" marker-end=\"url(#$it)\"") }
        markerOf(edge.start, atEnd = false, edge.color)?.let { append(" marker-start=\"url(#$it)\"") }
        append("/>")
        val label = edge.label?.let(::clean)?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val shown = fitted(label, EDGE_LABEL_WIDTH, 12)
        val mx = route.labelX
        val my = route.labelY
        val half = shown.length * 12 * CHAR_WIDTH / 2 + 4
        append("<rect x=\"${r(mx - half)}\" y=\"${r(my - 9)}\" width=\"${r(half * 2)}\" height=\"16\" rx=\"3\" fill=\"$PAPER\"/>")
        val styles = stylesOf(edge.label)
        if (styles == null) {
            text(mx.toInt(), (my + 4).toInt(), shown, 12, MUTED, anchor = "middle")
        } else {
            // Its first line from its start, cut short at most: each character is where it was in the label.
            val source = clean(edge.label)
            styledText(mx.toInt(), (my + 4).toInt(), shown.mapIndexed { at, ch -> (if (at < source.length && source[at] == ch) styles[at] else DiagramStyledText.PLAIN) to ch }, 12, MUTED, false, "middle")
        }
    }

    /** The marker an end is drawn with, by id; the arrow keeps the two it always had, so an old line draws as it did. */
    private fun markerOf(style: DiagramEndStyle, atEnd: Boolean, colour: String? = null): String? = when (style) {
        DiagramEndStyle.NONE -> null
        DiagramEndStyle.ARROW -> if (atEnd) "dg-head" else "dg-tail"
        else -> "dg-${if (atEnd) "end" else "start"}-${style.key}"
    }?.let { id -> colour?.let { "$id-${it.drop(1)}" } ?: id }

    /**
     * One end's mark, its point or its far side on the node. Drawn at the end
     * pointing along the line, and at the start mirrored, since the renderer
     * the PNG goes through does not read auto-start-reverse.
     */
    private fun StringBuilder.marker(style: DiagramEndStyle, atEnd: Boolean, colour: String? = null) {
        val ink = colour ?: INK
        val x = { at: Double -> r(if (atEnd) at else MARK - at) }
        append("<marker id=\"${markerOf(style, atEnd, colour)}\" viewBox=\"0 0 $MARK $MARK\" refX=\"${x(MARK)}\" refY=\"${r(MARK / 2)}\" ")
        append("markerUnits=\"userSpaceOnUse\" markerWidth=\"15\" markerHeight=\"15\" orient=\"auto\">")
        val stroke = "stroke=\"$ink\" stroke-width=\"1.2\" stroke-linejoin=\"round\""
        when (style) {
            DiagramEndStyle.OPEN -> append("<path d=\"M ${x(1.0)} 1 L ${x(MARK)} 6 L ${x(1.0)} 11\" fill=\"none\" $stroke/>")
            DiagramEndStyle.TRIANGLE -> append("<path d=\"M ${x(1.0)} 1 L ${x(MARK)} 6 L ${x(1.0)} 11 z\" fill=\"$PAPER\" $stroke/>")
            DiagramEndStyle.DIAMOND, DiagramEndStyle.FILLED_DIAMOND -> {
                val fill = if (style == DiagramEndStyle.DIAMOND) PAPER else ink
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
                        "${outlined(node)} stroke-linejoin=\"round\"/>",
                )
                is DiagramOutline.Oval -> append(
                    "<ellipse cx=\"${r(fx + piece.cx * fw / 100)}\" cy=\"${r(fy + piece.cy * fh / 100)}\" " +
                        "rx=\"${r(piece.rx * fw / 100)}\" ry=\"${r(piece.ry * fh / 100)}\" fill=\"$painted\" ${outlined(node)}/>",
                )
            }
        }
        val words = clean(node.label)
        if (outline.icon) {
            // Beneath the icon, on the paper rather than on the fill, so it is ink whatever the colour.
            val line = fitted(words.lineSequence().firstOrNull().orEmpty(), node.w + 40, sizeOf(node))
            text(node.x + node.w / 2, node.y + node.h - 6, line, sizeOf(node), INK, anchor = "middle")
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
        val lines = wrap(words, right - left, sizeOf(node), max(1, (bottom - top) / lineOf(node)))
        setWords(lines, left, right, top, bottom, node.align ?: DiagramTextAlign.CENTER, node.valign ?: DiagramTextValign.MIDDLE, ink, sizeOf(node), lineOf(node), label = node.label)
    }

    /** A class: its name bold in the middle, each compartment under a rule, its lines from the left. */
    private fun StringBuilder.compartments(node: DiagramShape, parts: List<List<String>>, ink: String) {
        var y = node.y + 4
        val bottom = node.y + node.h - 4
        parts.forEachIndexed { at, lines ->
            if (at > 0) {
                append("<line x1=\"${node.x}\" y1=\"$y\" x2=\"${node.x + node.w}\" y2=\"$y\" ${outlined(node)}/>")
                y += 2
            }
            lines.forEach { line ->
                if (y + lineOf(node) > bottom) return
                val shown = fitted(line, node.w - 16, sizeOf(node))
                if (at == 0) text(node.x + node.w / 2, y + sizeOf(node), shown, sizeOf(node), ink, bold = true, anchor = "middle")
                else text(node.x + 8, y + sizeOf(node), shown, sizeOf(node), ink)
                y += lineOf(node)
            }
            y += 4
        }
    }

    /**
     * A picture: drawn from [data], a data address, fitted inside its box;
     * where there is none to draw - an address that may not or could not be
     * fetched, or a picture gone - an empty frame naming where it was. Its label is
     * a caption under it, inside the box.
     */
    private fun StringBuilder.picture(node: DiagramShape, data: String?) {
        val caption = clean(node.label).lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
        val room = if (caption != null) node.h - lineOf(node) - 4 else node.h
        if (data != null) {
            append("<image x=\"${node.x}\" y=\"${node.y}\" width=\"${node.w}\" height=\"${max(1, room)}\" ")
            // xlink:href, which browsers still read: the PNG is drawn by Batik, which reads nothing else and refused the drawing.
            append("preserveAspectRatio=\"xMidYMid meet\" xlink:href=\"").append(escape(data)).append("\"/>")
        } else {
            rect(node.x, node.y, node.w, max(1, room), WHITE, 4, dashed = true)
            val said = node.image?.takeUnless { it.all(Char::isDigit) }?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "picture"
            text(node.x + node.w / 2, node.y + room / 2 + 4, fitted(said, node.w - 16, 12), 12, MUTED, anchor = "middle")
        }
        if (node.border != null || node.borderWidth != null) rect(node.x, node.y, node.w, max(1, room), "none", 4, stroke = outlined(node))
        caption?.let { text(node.x + node.w / 2, node.y + node.h - 6, fitted(it, node.w, sizeOf(node)), sizeOf(node), INK, anchor = "middle") }
    }

    /**
     * A code shape: a dark card, as the editor draws it, with a caption naming the file and its lines in a
     * fixed-width face, numbered. Plain: the picture has no highlighting, which is the editor's alone.
     */
    private fun StringBuilder.code(node: DiagramShape, body: String?) {
        val source = node.source?.let(DiagramCodeSource::of)
        rect(node.x, node.y, node.w, node.h, CODE_PAPER, 6, stroke = if (node.border != null || node.borderWidth != null) outlined(node) else "stroke=\"$CODE_RULE\" stroke-width=\"1\"")
        val caption = clean(node.label).lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: source?.let { it.path.substringAfterLast('/') + (it.fromLine?.let { from -> ":$from" + (it.toLine?.takeIf { to -> to != from }?.let { to -> "–$to" } ?: "") } ?: "") }
            ?: "code"
        text(node.x + 10, node.y + 17, fitted(caption, node.w - 20, 12), 12, CODE_MUTED, bold = true)
        append("<line x1=\"${node.x}\" y1=\"${node.y + 26}\" x2=\"${node.x + node.w}\" y2=\"${node.y + 26}\" stroke=\"$CODE_RULE\" stroke-width=\"1\"/>")
        val size = node.textSize ?: CODE_SIZE
        val line = size + 5
        if (body == null) {
            text(node.x + 10, node.y + 26 + line + 4, fitted(node.source ?: "", node.w - 20, size), size, CODE_MUTED)
            return
        }
        val first = source?.fromLine ?: 1
        val rows = max(0, (node.h - 26 - 8) / line)
        val lines = body.split('\n').take(rows)
        val gutter = ((first + lines.size - 1).toString().length + 1) * size * 0.6
        val fits = max(1, ((node.w - 20 - gutter) / (size * 0.6)).toInt())
        lines.forEachIndexed { at, said ->
            val baseline = node.y + 26 + 4 + line * (at + 1) - 4
            append("<text x=\"${node.x + 10}\" y=\"$baseline\" font-size=\"$size\" font-family=\"$MONO\" fill=\"$CODE_MUTED\">${first + at}</text>")
            val shown = said.replace("\t", "    ").let { if (it.length > fits) it.take(max(0, fits - 1)) + ELLIPSIS else it }
            if (shown.isNotBlank()) {
                append("<text x=\"${r(node.x + 10 + gutter)}\" y=\"$baseline\" font-size=\"$size\" font-family=\"$MONO\" fill=\"$CODE_INK\" xml:space=\"preserve\">")
                append(escape(shown)).append("</text>")
            }
        }
    }

    /** A Jira shape: a card with the issue's key, its summary, and its status, type and assignee - or its key alone where nothing was read. */
    private fun StringBuilder.jira(node: DiagramShape, body: String?) {
        val issue = body?.let { runCatching { tools.jackson.databind.ObjectMapper().readTree(it) }.getOrNull() }?.takeIf { it.isObject }
        fun field(name: String): String? = issue?.path(name)?.let { if (it.isString) it.stringValue() else if (it.isObject) it.path("name").stringValue() else null }?.takeIf { it.isNotBlank() }
        val fill = node.color ?: WHITE
        val ink = if (dark(fill)) WHITE else INK
        rect(node.x, node.y, node.w, node.h, fill, 8, stroke = outlined(node))
        val size = sizeOf(node)
        val key = field("key") ?: node.source?.let { DiagramJiraSource.of(it)?.key } ?: "Jira"
        text(node.x + 12, node.y + 8 + size, fitted(key, node.w - 24, size), size, LINK_INK, bold = true)
        val status = field("status")
        if (status != null) {
            val said = fitted(status.uppercase(), node.w / 2, 10)
            val width = (said.length * 10 * CHAR_WIDTH + 12).toInt()
            rect(node.x + node.w - 12 - width, node.y + 8, width, 16, JIRA_STATUS, 3, stroke = "stroke=\"none\"")
            text(node.x + node.w - 12 - width / 2, node.y + 20, said, 10, INK, bold = true, anchor = "middle")
        }
        val summary = field("summary") ?: clean(node.label).ifBlank { "" }
        val rows = max(1, (node.h - 8 - size - 8 - 22) / lineOf(node))
        wrap(summary, node.w - 24, size, rows).forEachIndexed { at, said ->
            text(node.x + 12, node.y + 8 + size + 6 + lineOf(node) * (at + 1), said, size, ink)
        }
        val foot = listOfNotNull(field("type"), field("assignee")).joinToString(" · ")
        if (foot.isNotEmpty()) text(node.x + 12, node.y + node.h - 10, fitted(foot, node.w - 24, 11), 11, MUTED)
    }

    /** A box's outline: its own colour and thickness, or the ink every box had. */
    private fun outlined(node: DiagramShape): String =
        "stroke=\"${node.border ?: INK}\" stroke-width=\"${node.borderWidth?.let { "$it" } ?: "1.5"}\""

    /** A line's stroke: its own colour and thickness, or the ink every line had. */
    private fun stroked(edge: DiagramLink): String =
        "stroke=\"${edge.color ?: INK}\" stroke-width=\"${edge.width?.let { "$it" } ?: "1.5"}\""

    private fun StringBuilder.rect(x: Int, y: Int, w: Int, h: Int, fill: String, radius: Int, dashed: Boolean = false, stroke: String = DEFAULT_STROKE) {
        append("<rect x=\"$x\" y=\"$y\" width=\"$w\" height=\"$h\" rx=\"$radius\" fill=\"$fill\" $stroke")
        if (dashed) append(" stroke-dasharray=\"5 4\"")
        append("/>")
    }

    /** One line of words, each run in its own style: a tspan per run, in one text so the line is anchored as one. */
    private fun StringBuilder.styledText(x: Int, y: Int, chars: List<Pair<DiagramStyledText.Style, Char>>, size: Int, colour: String, bold: Boolean, anchor: String?) {
        if (chars.isEmpty()) return
        append("<text x=\"$x\" y=\"$y\" font-size=\"$size\" fill=\"$colour\" xml:space=\"preserve\"")
        if (bold) append(" font-weight=\"600\"")
        if (anchor != null) append(" text-anchor=\"$anchor\"")
        append(">")
        var at = 0
        while (at < chars.size) {
            val style = chars[at].first
            var end = at
            while (end < chars.size && chars[end].first == style) end += 1
            val words = escape(chars.subList(at, end).joinToString("") { it.second.toString() })
            if (style.plain) {
                append(words)
            } else {
                append("<tspan")
                if (style.bold) append(" font-weight=\"700\"")
                if (style.italic) append(" font-style=\"italic\"")
                val lines = listOfNotNull("underline".takeIf { style.underline }, "line-through".takeIf { style.strike })
                if (lines.isNotEmpty()) append(" text-decoration=\"${lines.joinToString(" ")}\"")
                style.color?.let { append(" fill=\"$it\"") }
                append(">").append(words).append("</tspan>")
            }
            at = end
        }
        append("</text>")
    }

    private fun StringBuilder.text(x: Int, y: Int, words: String, size: Int, colour: String, bold: Boolean = false, anchor: String? = null) {
        if (words.isEmpty()) return
        append("<text x=\"$x\" y=\"$y\" font-size=\"$size\" fill=\"$colour\"")
        if (bold) append(" font-weight=\"600\"")
        if (anchor != null) append(" text-anchor=\"$anchor\"")
        append(">").append(escape(words)).append("</text>")
    }

    /** How big [node]'s words are drawn: the size it asked for, or [usual] for where they are. */
    private fun sizeOf(node: DiagramShape, usual: Int = SIZE): Int =
        if (node.textFit == true) fit(node) else node.textSize ?: usual

    /** How tall a line of [node]'s words is: eighteen pixels at the usual fourteen, and in that proportion at a size it asked for. */
    private fun lineOf(node: DiagramShape): Int =
        (if (node.textFit == true) fit(node) else node.textSize)?.let { max(it + 2, (it * LINE + SIZE / 2) / SIZE) } ?: LINE

    /**
     * The largest size [node]'s words fill it at, for a shape set to fit its text: wrapped as they are drawn,
     * in the room its outline leaves, from [FIT_MOST] down to [FIT_LEAST]. The editor works it out the same way.
     */
    private fun fit(node: DiagramShape): Int = fitted(node).first

    /**
     * The largest size [node]'s words fit at, and how wide they are then set.
     * A diamond's or an ellipse's room is not its box less an inset: the words
     * have to stay inside the slanted or curved sides, so it is tried as a few
     * rectangles that do - wide and short to narrow and tall - and the one that
     * takes the largest words wins. The editor's `fittedIn` does the same.
     */
    private fun fitted(node: DiagramShape): Pair<Int, Int> {
        val words = clean(node.label).trim()
        val outline = DiagramOutline.of(node.kind, node.w, node.h)
        val rooms: List<Pair<Int, Int>> = when {
            outline != null -> {
                val inside = outline.inside
                listOf(((inside.right - inside.left) * node.w / 100).toInt() to ((inside.bottom - inside.top) * node.h / 100).toInt())
            }
            node.kind == DiagramNodeKind.DIAMOND -> FIT_SHARES.map { t -> (node.w * (1 - t) * FIT_ROOM).toInt() to (node.h * t * FIT_ROOM).toInt() }
            node.kind == DiagramNodeKind.ELLIPSE -> FIT_ANGLES.map { a -> (node.w * cos(a) * FIT_ROOM).toInt() to (node.h * sin(a) * FIT_ROOM).toInt() }
            else -> listOf((node.w - 16) to (node.h - 8))
        }
        if (words.isEmpty()) return SIZE to rooms.first().first
        return rooms.map { (width, height) -> sizeIn(words, width, height) to width }.maxBy { it.first }
    }

    private fun sizeIn(words: String, width: Int, height: Int): Int {
        for (size in FIT_MOST downTo FIT_LEAST) {
            val line = max(size + 2, (size * LINE + SIZE / 2) / SIZE)
            val fits = max(1, (width / (size * CHAR_WIDTH)).toInt())
            if (words.split(Regex("\\s+")).any { it.length > fits }) continue
            if (wrap(words, width, size, Int.MAX_VALUE).size * line <= height) return size
        }
        return FIT_LEAST
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

        /** How wide a line's label may run before it is cut. */
        const val EDGE_LABEL_WIDTH = 220

        /** A line label's box, as drawn: its half width for [label] and its height. */
        fun labelHalfWidth(label: String): Double {
            val line = clean(label).lineSequence().firstOrNull().orEmpty()
            return minOf(line.length * 12 * CHAR_WIDTH, EDGE_LABEL_WIDTH.toDouble()) / 2 + 4
        }
        const val LABEL_HEIGHT = 16.0
        /** The square a line end's mark is drawn in, in its own units; a format, not a behaviour. */
        private const val MARK = 12.0
        private const val SIZE = 14

        /** A group's name, a little smaller than a box's words. */
        private const val GROUP_SIZE = 13
        private const val LINE = 18
        private const val CHAR_WIDTH = 0.56
        private const val ELLIPSIS = "…"
        private const val FONT = "Segoe UI, Roboto, Helvetica, Arial, sans-serif"
        private const val PAPER = "#f4f4f5"
        private const val WHITE = "#ffffff"
        private const val INK = "#3f3f46"
        /** A link card's name, in the colour a link is drawn on the page. */
        private const val LINK_INK = "#2563eb"
        private const val DEFAULT_STROKE = "stroke=\"$INK\" stroke-width=\"1.5\""
        private const val MUTED = "#71717a"
        private const val NOTE = "#fef9c3"
        private const val GROUP = "#eef2f7"
        /** A code shape's card, as the editor draws it whatever the theme: dark, with light lines. */
        private const val CODE_PAPER = "#18181b"
        private const val CODE_RULE = "#3f3f46"
        private const val CODE_INK = "#e4e4e7"
        private const val CODE_MUTED = "#a1a1aa"
        private const val CODE_SIZE = 12
        /** The sizes a shape set to fit its text is drawn between; the editor's text size runs the same. */
        private const val FIT_LEAST = 6
        private const val FIT_MOST = 96
        /** How much of a diamond's height its words' room takes - the rest of its width is left to it: rectangles that stay inside the sides. */
        private val FIT_SHARES = listOf(0.3, 0.4, 0.5, 0.6, 0.7)
        /** Where an ellipse's words' room meets its curve, as angles: wide and short to narrow and tall. */
        private val FIT_ANGLES = listOf(25.0, 35.0, 45.0, 55.0, 65.0).map { it * PI / 180 }
        /** A little short of the outline, so a fitted word does not touch it. */
        private const val FIT_ROOM = 0.9
        private const val MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
        private const val JIRA_STATUS = "#e4e4e7"

        /** What XML 1.0 can hold: a control character other than a tab or a new line would refuse the whole picture. */
        /** The words a label says, as they are drawn and measured: control characters out, and the marks that style part of it ([DiagramStyledText]). */
        fun clean(text: String): String = DiagramStyledText.plain(bare(text))

        /** A label with its control characters out and its marks still in. */
        private fun bare(text: String): String =
            text.replace("\r\n", "\n").filter { it == '\n' || it == '\t' || it >= ' ' }.replace('\t', ' ')

        /** The style of each character of [clean] of [text]; null where nothing of it is styled. */
        fun stylesOf(text: String): List<DiagramStyledText.Style>? =
            DiagramStyledText.stylesOf(bare(text)).takeIf { styles -> styles.any { !it.plain } }

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
