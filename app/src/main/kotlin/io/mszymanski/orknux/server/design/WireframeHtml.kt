package io.mszymanski.orknux.server.design

/**
 * A wireframe written out as one HTML page: the export the editor offers and
 * what `orknux_wireframe_html` answers.
 *
 * One file with its styles inline and no script, so it opens from a disk, a
 * mail or a repository as it does from here. Each top-level frame is a screen,
 * a section of its own with the frame's title over it, and everything whose
 * middle lies inside a frame is placed in it by absolute position, at the
 * pixels it was drawn at - a wireframe is a statement about where things go,
 * and a flow layout guessed from it would be a second opinion. What lies in no
 * frame is gathered into one last section around its own bounds.
 *
 * The kinds that have an HTML element are given that element - a `<button>`,
 * an `<input>`, a `<figure>` - with the same pencil styling the editor draws, so
 * the page is a starting point for the real one rather than a picture of it.
 *
 * The drawing is untrusted: a model wrote it, or somebody sharing the
 * workspace. Every word in it is escaped, a prop reaches the page only as one
 * of the few values that are looked for (a number, a weight, a variant) or as
 * a style [WireframeStyle] parsed and wrote out again - never as it was given -
 * and the page refuses scripts in its own Content-Security-Policy besides.
 * A checkbox, a radio button and a switch are real inputs, in a label.
 */
class WireframeHtml(
    /**
     * A picture an image element names in `props.artifact`, as a `data:`
     * address the page carries in itself, or null to draw it as a placeholder:
     * a page taken away has no server to fetch it from.
     */
    private val picture: (String) -> String? = { null },
) {

    /** The whole page for the wireframe called [name]. */
    fun page(name: String, elements: List<WireframeElement>): String = buildString {
        append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:\">\n")
        append("<title>").append(escape(name)).append("</title>\n")
        append("<style>\n").append(STYLE).append("</style>\n")
        val shown = screens(elements)
        /*
         * The page in the screen's own colour, as the page it is a picture of
         * would be: a frame filled dark sat on a grey sheet, under the name of
         * the wireframe, and read as an export rather than a page.
         */
        backdropOf(shown)?.let { colour ->
            append("<style>body { background: ").append(colour).append("; color-scheme: ")
                .append(if (dark(colour)) "dark" else "light").append("; }</style>\n")
        }
        append("</head>\n<body>\n")
        // The name is the tab's; on the page only an empty drawing says it, as there is nothing else to show.
        if (shown.isEmpty()) append("<h1 class=\"wireframe-name\">").append(escape(name)).append("</h1>\n")
        // One marker for every arrow on the page; a reference by id reaches across the svgs.
        append(
            "<svg class=\"defs\" aria-hidden=\"true\" width=\"0\" height=\"0\"><defs>" +
                "<marker id=\"wf-arrowhead\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" markerHeight=\"8\" orient=\"auto-start-reverse\">" +
                "<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"#3f3f46\"/></marker></defs></svg>\n",
        )
        // A caption only where there are screens to tell apart.
        shown.forEach { screen(it, titled = shown.size > 1) }
        append("</body>\n</html>\n")
    }

    /** A screen: a frame and what sits in it, or the loose elements around their own bounds. */
    data class Screen(
        val title: String?,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val elements: List<WireframeElement>,
        /** Whether a frame drew it, and so whether it has the frame's outline. */
        val framed: Boolean,
        /** The frame's id, which the section carries as an element carries its own. */
        val id: String? = null,
        /** The frame itself, whose style - a fill, a border, a radius, a shadow - the screen is drawn in. */
        val frame: WireframeElement? = null,
    )

    /**
     * The drawing split into screens, top to bottom and then left to right.
     *
     * A frame inside another frame is a panel of that screen rather than a
     * screen of its own. Anything else belongs to the smallest outer frame its
     * middle is in - a line by its midpoint - so a button straddling an edge
     * goes where most of it is.
     */
    fun screens(elements: List<WireframeElement>): List<Screen> {
        val frames = elements.filter { it.type == WireframeElementType.FRAME }
        val outer = frames.filter { frame ->
            frames.none { other -> other !== frame && holds(other, frame) && (!holds(frame, other) || elements.indexOf(other) < elements.indexOf(frame)) }
        }
        val owned = linkedMapOf<WireframeElement, MutableList<WireframeElement>>()
        outer.forEach { owned[it] = mutableListOf() }
        val loose = mutableListOf<WireframeElement>()
        elements.filter { element -> outer.none { it === element } }.forEach { element ->
            val middleX = element.x + element.w / 2.0
            val middleY = element.y + element.h / 2.0
            val owner = outer
                .filter { middleX >= it.x && middleX <= it.x + it.w && middleY >= it.y && middleY <= it.y + it.h }
                .minByOrNull { it.w.toLong() * it.h }
            if (owner == null) loose += element else owned.getValue(owner) += element
        }
        val framed = owned.entries
            .sortedWith(compareBy({ it.key.y }, { it.key.x }))
            .map { (frame, inside) -> Screen(frame.text, frame.x, frame.y, frame.w, frame.h, inside, framed = true, id = frame.id, frame = frame) }
        if (loose.isEmpty()) return framed
        val left = loose.minOf { minOf(it.x, it.x + it.w) }
        val top = loose.minOf { minOf(it.y, it.y + it.h) }
        val right = loose.maxOf { maxOf(it.x, it.x + it.w) }
        val bottom = loose.maxOf { maxOf(it.y, it.y + it.h) }
        return framed + Screen(null, left, top, maxOf(1, right - left), maxOf(1, bottom - top), loose, framed = false)
    }

    private fun holds(outer: WireframeElement, inner: WireframeElement) =
        inner.x >= outer.x && inner.y >= outer.y && inner.x + inner.w <= outer.x + outer.w && inner.y + inner.h <= outer.y + outer.h

    /** The first screen's frame fill, where it is one plain colour: the colour the whole page is drawn in. */
    private fun backdropOf(screens: List<Screen>): String? =
        screens.firstOrNull()?.frame?.let { (WireframeStyle.look(it.props).fill as? WireframeStyle.Fill.Solid)?.colour }

    /** Whether a #rrggbb colour is dark enough that the page around it should be drawn dark too. */
    private fun dark(colour: String): Boolean {
        val hex = colour.removePrefix("#").take(6).takeIf { it.length == 6 } ?: return false
        val (r, g, b) = (0 until 3).map { hex.substring(it * 2, it * 2 + 2).toInt(16) / 255.0 }
        return 0.2126 * r + 0.7152 * g + 0.0722 * b < 0.45
    }

    private fun StringBuilder.screen(screen: Screen, titled: Boolean = true) {
        append("<section class=\"screen\"")
        screen.id?.let { append(" data-id=\"").append(escape(it)).append('"') }
        screen.title?.let { append(" aria-label=\"").append(escape(it)).append('"') }
        append(">\n")
        screen.title?.takeIf { titled }?.let { append("<h2 class=\"screen-title\">").append(escape(it)).append("</h2>\n") }
        append("<div class=\"canvas")
        if (screen.framed) append(" framed")
        // The frame's own style over the stylesheet's white outline: a screen dropped it, so a dark frame exported white.
        val styled = screen.frame?.let { css(it, WireframeStyle.look(it.props)) }.orEmpty()
        append("\" style=\"width:${screen.w}px;height:${screen.h}px$styled\">\n")
        screen.elements.forEach { element(it, screen.x, screen.y) }
        append("</div>\n</section>\n")
    }

    /** The picture an image element names, where there is one and it can be had. */
    private fun pictureOf(element: WireframeElement): String? {
        val artifact = element.props["artifact"]?.toString()?.trim()?.takeIf { DocumentContent.ARTIFACT.matches(it) } ?: return null
        return picture(artifact)?.takeIf { it.startsWith("data:image/") }
    }

    private fun StringBuilder.element(element: WireframeElement, originX: Int, originY: Int) {
        val words = element.text?.let(::escape).orEmpty()
        val id = escape(element.id)
        val look = WireframeStyle.look(element.props)
        if (!element.type.box) {
            line(element, originX, originY, id, look)
            return
        }
        val place = "left:${element.x - originX}px;top:${element.y - originY}px;width:${element.w}px;height:${element.h}px" + css(element, look)
        when (element.type) {
            WireframeElementType.FRAME -> append("<div class=\"el frame\" data-id=\"$id\" style=\"$place\">")
                .append(if (words.isEmpty()) "" else "<span class=\"frame-title\">$words</span>").append("</div>\n")
            WireframeElementType.RECT -> append("<div class=\"el rect\" data-id=\"$id\" style=\"$place\">$words</div>\n")
            WireframeElementType.TEXT -> {
                val size = (element.props["size"] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toInt()?.coerceIn(TEXT_LEAST, TEXT_MOST) ?: 14
                val weight = if (element.props["weight"]?.toString()?.trim()?.lowercase() == "bold") 600 else 400
                append("<p class=\"el text\" data-id=\"$id\" style=\"$place;font-size:${size}px;font-weight:$weight\">$words</p>\n")
            }
            WireframeElementType.BUTTON -> {
                val primary = element.props["variant"]?.toString()?.trim()?.lowercase() == "primary"
                append("<button type=\"button\" class=\"el button${if (primary) " primary" else ""}\" data-id=\"$id\" style=\"$place\">$words</button>\n")
            }
            WireframeElementType.INPUT -> append("<input type=\"text\" class=\"el input\" data-id=\"$id\" style=\"$place\"")
                .append(if (words.isEmpty()) "" else " placeholder=\"$words\" aria-label=\"$words\"").append(">\n")
            WireframeElementType.IMAGE -> pictureOf(element)?.let { source ->
                append("<figure class=\"el image picture\" data-id=\"$id\" style=\"$place\">")
                    .append("<img src=\"").append(escape(source)).append("\" alt=\"$words\">")
                    .append(if (words.isEmpty()) "" else "<figcaption>$words</figcaption>").append("</figure>\n")
            } ?: append("<figure class=\"el image\" data-id=\"$id\" style=\"$place\">")
                .append("<svg viewBox=\"0 0 100 100\" preserveAspectRatio=\"none\" aria-hidden=\"true\">")
                .append("<line x1=\"0\" y1=\"0\" x2=\"100\" y2=\"100\" vector-effect=\"non-scaling-stroke\"/>")
                .append("<line x1=\"100\" y1=\"0\" x2=\"0\" y2=\"100\" vector-effect=\"non-scaling-stroke\"/></svg>")
                .append(if (words.isEmpty()) "" else "<figcaption>$words</figcaption>").append("</figure>\n")
            WireframeElementType.NOTE -> append("<aside class=\"el note\" data-id=\"$id\" style=\"$place\">$words</aside>\n")
            WireframeElementType.CHECKBOX, WireframeElementType.RADIO, WireframeElementType.TOGGLE -> {
                // A real control in a label, so the words are its name and a press on them works it.
                val input = when (element.type) {
                    WireframeElementType.RADIO -> "<input type=\"radio\""
                    WireframeElementType.TOGGLE -> "<input type=\"checkbox\" role=\"switch\" class=\"switch\""
                    else -> "<input type=\"checkbox\""
                }
                append("<label class=\"el check ${element.type.key}\" data-id=\"$id\" style=\"$place\">")
                append(input).append(if (look.checked) " checked" else "").append(">")
                append(if (words.isEmpty()) "" else "<span>$words</span>").append("</label>\n")
            }
            WireframeElementType.LINE, WireframeElementType.ARROW -> Unit
        }
    }

    /**
     * What the props say about how a box looks, as declarations after its
     * place. Every value is one [WireframeStyle] wrote out again from what it
     * parsed - hex digits, numbers and its own words - and is escaped besides.
     */
    private fun css(element: WireframeElement, look: WireframeStyle.Look): String {
        val parts = mutableListOf<String>()
        look.fill?.let { parts += "background:" + WireframeStyle.written(it) }
        look.color?.let { parts += "color:$it" }
        border(element, look)?.let { parts += "border:$it" }
        look.radius?.let { parts += "border-radius:${it}px" }
        look.shadow?.let { parts += "box-shadow:" + it.css() }
        return escape(parts.joinToString("") { ";$it" })
    }

    /**
     * A border, where the props say anything about one: the kind's own width,
     * style and colour - the stylesheet's - under what they say. A width or a
     * colour given to a kind with no outline draws a solid one.
     */
    private fun border(element: WireframeElement, look: WireframeStyle.Look): String? {
        if (look.borderWidth == null && look.borderStyle == null && look.borderColor == null) return null
        val primary = element.props["variant"]?.toString()?.trim()?.lowercase() == "primary"
        val (width, colour) = when (element.type) {
            WireframeElementType.FRAME -> 1.5 to "#3f3f46"
            WireframeElementType.BUTTON -> 1.5 to if (primary) "#52525b" else "#3f3f46"
            WireframeElementType.NOTE -> 1.0 to "#d9c87a"
            WireframeElementType.RECT, WireframeElementType.INPUT, WireframeElementType.IMAGE -> 1.0 to "#8a8a93"
            else -> 1.0 to "#3f3f46"
        }
        val style = look.borderStyle ?: WireframeStyle.BorderStyle.SOLID
        val drawn = look.borderWidth?.toDouble() ?: width
        return "${WireframeStyle.number(drawn)}px ${style.key} ${look.borderColor ?: colour}"
    }

    /** A line or an arrow, as an svg of its own around it, padded for the stroke and the head. */
    private fun StringBuilder.line(element: WireframeElement, originX: Int, originY: Int, id: String, look: WireframeStyle.Look) {
        val left = minOf(element.x, element.x + element.w) - LINE_PAD
        val top = minOf(element.y, element.y + element.h) - LINE_PAD
        val width = Math.abs(element.w) + LINE_PAD * 2
        val height = Math.abs(element.h) + LINE_PAD * 2
        val x1 = element.x - left
        val y1 = element.y - top
        val colour = look.borderColor
        val drawn = look.borderWidth?.toDouble() ?: 1.5
        val stroke = buildString {
            colour?.let { append("stroke:$it;") }
            look.borderWidth?.let { append("stroke-width:$it;") }
            when (look.borderStyle) {
                WireframeStyle.BorderStyle.NONE -> append("stroke:none;")
                WireframeStyle.BorderStyle.DASHED ->
                    append("stroke-dasharray:${WireframeStyle.number(maxOf(4.0, drawn * 3))} ${WireframeStyle.number(maxOf(3.0, drawn * 2))};")
                WireframeStyle.BorderStyle.DOTTED -> append("stroke-dasharray:${WireframeStyle.number(drawn)} ${WireframeStyle.number(drawn)};")
                else -> Unit
            }
        }
        val headId = "wf-arrowhead-" + element.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val head = when {
            element.type != WireframeElementType.ARROW -> ""
            colour == null -> " marker-end=\"url(#wf-arrowhead)\""
            else -> " marker-end=\"url(#$headId)\""
        }
        append("<svg class=\"el ${element.type.key}\" data-id=\"$id\" aria-hidden=\"true\" ")
        append("style=\"left:${left - originX}px;top:${top - originY}px;width:${width}px;height:${height}px")
        look.shadow?.takeIf { it != WireframeStyle.Shadow.NONE }?.let { shadow ->
            append(";filter:drop-shadow(0 ${shadow.dy}px ${shadow.blur / 2}px rgba(0,0,0,${shadow.opacity}))")
        }
        append("\" viewBox=\"0 0 $width $height\">")
        if (element.type == WireframeElementType.ARROW && colour != null) {
            // An arrow in a colour of its own has a head of that colour.
            append("<defs><marker id=\"$headId\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" markerHeight=\"8\" orient=\"auto-start-reverse\">")
            append("<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"$colour\"/></marker></defs>")
        }
        append("<line x1=\"$x1\" y1=\"$y1\" x2=\"${x1 + element.w}\" y2=\"${y1 + element.h}\"$head")
        if (stroke.isNotEmpty()) append(" style=\"").append(escape(stroke)).append('"')
        append("/></svg>\n")
    }

    companion object {
        /** The range a text element's size is held to on the page, in pixels. A format, not a behaviour. */
        const val TEXT_LEAST = 6
        const val TEXT_MOST = 200

        /** Room around a line's svg for its stroke and its arrowhead. */
        private const val LINE_PAD = 8

        /** Text made safe for an element's content and for a quoted attribute alike. */
        fun escape(text: String): String = buildString(text.length) {
            text.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }

        /** A file name made of a wireframe's name: what a file system takes, never empty. */
        fun fileName(name: String, extension: String): String =
            (name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), " ").replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { "wireframe" }) +
                ".$extension"

        /** Pencil on paper, as the editor draws it - the values of the interface's --wireframe-* tokens. */
        private val STYLE = """
            |*, *::before, *::after { box-sizing: border-box; }
            |body { display: flex; flex-direction: column; align-items: center; gap: 48px; min-height: 100vh; margin: 0; padding: 48px 16px; background: #e7e7ea; color: #3f3f46; font: 14px/1.3 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
            |@media (prefers-color-scheme: dark) { body { background: #18181b; } .screen-title { color: #a1a1aa; } }
            |.wireframe-name { margin: 0; font-size: 20px; font-weight: 600; }
            |.defs { position: absolute; width: 0; height: 0; overflow: hidden; }
            |.screen { max-width: 100%; margin: 0; overflow-x: auto; }
            |.screen-title { margin: 0 0 8px; color: #8a8a93; font-size: 12px; font-weight: 400; }
            |.canvas { position: relative; }
            |.canvas.framed { background: #ffffff; border: 1.5px solid #3f3f46; border-radius: 10px; }
            |.el { position: absolute; margin: 0; overflow: hidden; white-space: pre-wrap; overflow-wrap: anywhere; }
            |.frame { overflow: visible; background: #ffffff; border: 1.5px solid #3f3f46; border-radius: 10px; }
            |.frame-title { position: absolute; left: 0; bottom: 100%; padding-bottom: 4px; color: #8a8a93; font-size: 12px; white-space: nowrap; }
            |.rect { display: flex; align-items: center; justify-content: center; padding: 0 8px; background: #ececef; border: 1px solid #8a8a93; border-radius: 6px; text-align: center; }
            |.text { color: #3f3f46; }
            |.button { display: flex; align-items: center; justify-content: center; padding: 0 8px; background: #ffffff; color: #3f3f46; border: 1.5px solid #3f3f46; border-radius: 8px; font: inherit; text-align: center; cursor: pointer; }
            |.button.primary { background: #52525b; border-color: #52525b; color: #ffffff; }
            |.input { padding: 0 12px; background: #ffffff; color: #3f3f46; border: 1px solid #8a8a93; border-radius: 6px; font: inherit; }
            |.input::placeholder { color: #8a8a93; }
            |.image { display: flex; align-items: flex-end; justify-content: center; padding: 6px; background: #ececef; border: 1px solid #8a8a93; border-radius: 4px; }
            |.image svg { position: absolute; inset: 0; width: 100%; height: 100%; }
            |.image line { stroke: #8a8a93; stroke-width: 1; }
            |.image.picture { background: transparent; border: none; padding: 0; }
            |.image img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
            |.image figcaption { position: relative; color: #8a8a93; font-size: 12px; }
            |.note { padding: 10px; background: #fdf3c4; border: 1px solid #d9c87a; border-radius: 2px; font-size: 13px; }
            |.check { display: flex; align-items: center; gap: 8px; color: #3f3f46; white-space: nowrap; cursor: pointer; }
            |.check input { flex: none; width: 16px; height: 16px; margin: 0; accent-color: #52525b; }
            |.check input.switch { appearance: none; position: relative; width: 32px; height: 18px; border: 1.5px solid #8a8a93; border-radius: 9px; background: #ffffff; }
            |.check input.switch::before { content: ""; position: absolute; top: 2px; left: 2px; width: 11px; height: 11px; border-radius: 50%; background: #8a8a93; }
            |.check input.switch:checked { background: #52525b; border-color: #52525b; }
            |.check input.switch:checked::before { left: 16px; background: #ffffff; }
            |svg.el { overflow: visible; }
            |svg.el line { stroke: #3f3f46; stroke-width: 1.5; fill: none; }
            |
        """.trimMargin()
    }
}
