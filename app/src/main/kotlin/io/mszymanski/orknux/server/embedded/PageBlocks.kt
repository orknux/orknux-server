package io.mszymanski.orknux.server.embedded

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * The drawings a page asks for, drawn into it. Issues #488, #487 and #507.
 *
 * ## What this is for
 *
 * An agent was asked to put charts in a report. The PDF writer drew mermaid
 * from a `<pre>` block but took no pictures, and the charts had been rendered
 * separately as PNGs sitting in a session store. There was no way to name one
 * from the HTML, so the agent reasoned its way to the only thing left - three
 * charts turned into HTML tables - and the person got tables where they asked
 * for charts.
 *
 * Nothing about that was a bug. It was two renderers in two sandboxes with a
 * store between them and no door. Now they are objects in one process, so the
 * door is simply a method call, and a page says what it wants:
 *
 * ```html
 * <pre class="mermaid">flowchart LR ...</pre>
 * <pre class="chart">{"kind":"pie","title":"Spend","values":{"Rent":45}}</pre>
 * <img src="chart.png">                       <!-- a scratchpad or a key -->
 * <img src="data:image/png;base64,iVBOR...">  <!-- written out -->
 * ```
 *
 * Each is replaced in place by the drawing itself, so what comes out the other
 * side is a page with no blocks left in it - which then renders in a browser
 * and lays out into a PDF without either half knowing this happened.
 *
 * ## Vectors, not pictures
 *
 * A diagram and a chart both become inline `<svg>`. That is the whole reason
 * the renderers answer SVG: the layout engine draws it onto the page as paths,
 * so it stays sharp at any size and costs a tenth of what a raster of the same
 * drawing would. A raster is only ever what somebody actually handed us.
 *
 * ## A block that will not draw does not lose the document
 *
 * It becomes a note where the drawing would have been, and the reason is
 * collected. A report with a note in it beats no report - and the note is
 * visible rather than silent, so whoever reads the PDF can see that something
 * was meant to be there. That rule is inherited from the bundle this replaces,
 * where it was learned the hard way: throwing meant one bad diagram cost the
 * whole call, and a caller answered by retrying with the diagram written
 * slightly differently, three or four times, having already been handed a
 * working document on the second attempt.
 */
@Component
class PageBlocks(
    private val diagrams: DiagramRenderer,
    private val charts: ChartRenderer,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The page with its blocks drawn, and whatever could not be drawn. */
    data class Drawn(val html: String, val problems: List<String>)

    /**
     * The pictures a page names by what this session calls them. Issue #545.
     *
     * The PDF writer takes <img src="picture.30"> and puts the picture in, so a
     * model writes an HTML report the same way and uploads the page itself -
     * where that src is a relative address to nothing, and every picture opens
     * broken. The page is not rewritten: what is wrong is where the picture
     * lives, and the model is told so, to host it or send it beside the page.
     * Web addresses and data URIs are not keys and are never named.
     */
    fun keyedPictures(html: String, held: (String) -> Boolean): List<String> =
        Jsoup.parse(html).select("img[src]")
            .map { it.attr("src").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("data:") && !it.contains("://") }
            .distinct()
            .filter(held)


    /**
     * @param pictures answers the bytes behind a name a page used in an `img`
     *   src - a scratchpad in this session, or a key something handed over.
     *   Null where there is no session to look in, which is the workflow case.
     */
    fun draw(html: String, pictures: (String) -> Picture? = { null }): Drawn {
        val page = Jsoup.parse(html)
        val problems = mutableListOf<String>()

        /*
         * Any element carrying the class, not only a <pre>. Issue #544: a model
         * writes a page the way mermaid.js reads one, as <div class="mermaid">,
         * and that went into the PDF as a paragraph of arrows. Only a block
         * holding nothing but its source - a wrapper round other markup is left.
         */
        page.select(".mermaid, mermaid").filter { it.children().isEmpty() }.forEach { block ->
            replace(block, problems, "diagram") { diagram(block.wholeText()) }
        }
        page.select("pre.chart, chart").forEach { block ->
            replace(block, problems, "chart") { chart(block.wholeText()) }
        }
        page.select("img[src]").forEach { image ->
            picture(image, problems, pictures)
        }
        scripted(page, problems)

        return Drawn(page.outerHtml(), problems)
    }

    /** What a picture is, once something has found it. */
    data class Picture(val bytes: ByteArray, val contentType: String?) {
        /*
         * Written out because a ByteArray compares by identity, and a data class
         * that says two identical pictures are different is a trap rather than a
         * convenience. Nothing here compares them, so the honest thing is to say
         * so rather than to leave the generated ones lying about.
         */
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /* ------------------------------------------------------------- diagrams */

    private fun diagram(source: String): Result<String> =
        when (val drawn = diagrams.svg(source)) {
            is DiagramRenderer.Drawing.Drawn -> Result.success(drawn.svg)
            is DiagramRenderer.Drawing.Refused -> Result.failure(IllegalArgumentException(drawn.reason))
        }

    /**
     * What a script would have drawn. Issue #544: a report written for a browser
     * loads Chart.js and mermaid.js and draws into a <canvas>, and the page opens
     * beautifully in one. No script runs here, so each canvas came out as an
     * empty box and the answer said nothing was wrong. Each is replaced by a
     * note in the document and named in the answer, with the spelling that does
     * draw.
     */
    private fun scripted(page: Document, problems: MutableList<String>) {
        val canvases = page.select("canvas")
        canvases.forEach { it.replaceWith(note("chart not drawn: a <canvas> is drawn by a script, and none runs here")) }
        val scripts = page.select("script")
        scripts.remove()
        if (canvases.isNotEmpty()) {
            problems += "${canvases.size} <canvas> ${if (canvases.size == 1) "was" else "were"} not drawn: no " +
                "JavaScript runs when a PDF is laid out, so Chart.js and anything else a script draws is blank. " +
                "Write each chart as <pre class=\"chart\">{\"kind\":\"bar\",\"values\":{\"A\":1}}</pre> instead."
        } else if (scripts.isNotEmpty()) {
            problems += "the page's scripts were not run: no JavaScript runs when a PDF is laid out, so anything " +
                "they would have drawn or written is missing. Write diagrams as <pre class=\"mermaid\"> and " +
                "charts as <pre class=\"chart\">."
        }
    }

    /* --------------------------------------------------------------- charts */

    /**
     * A chart block, which carries its specification as JSON.
     *
     * `values` is an object rather than two parallel lists, because that is how
     * somebody holds the data in their head - a label and its number - and
     * parallel lists are the shape that silently goes wrong when one is longer.
     */
    private fun chart(source: String): Result<String> {
        val asked = runCatching { mapper.readTree(source) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("that chart block is not valid JSON"))

        /*
         * Read only where they are text. Issue #547: Jackson 3's stringValue()
         * throws on a missing node rather than answering null, so a chart block
         * with no title - the usual kind - failed the whole document.
         */
        val kind = asked.path(KIND).takeIf { it.isTextual }?.stringValue()?.trim().orEmpty().ifEmpty { "bar" }
        val title = asked.path(TITLE).takeIf { it.isTextual }?.stringValue()?.trim()?.ifEmpty { null }
        val values = asked.path(VALUES)
        if (values.isEmpty) {
            return Result.failure(
                IllegalArgumentException("that chart block names no values: \"$VALUES\" is a label and a number each"),
            )
        }

        val points = values.properties().mapNotNull { (label, number) ->
            number.takeIf { it.isNumber }?.let { ChartRenderer.Point(label, it.doubleValue()) }
        }
        if (points.isEmpty()) {
            return Result.failure(IllegalArgumentException("that chart block's values are not numbers"))
        }

        return when (val drawn = charts.svg(kind, points, title)) {
            is ChartRenderer.Drawing.Drawn -> Result.success(drawn.svg)
            is ChartRenderer.Drawing.Refused -> Result.failure(IllegalArgumentException(drawn.reason))
        }
    }

    /* ------------------------------------------------------------- pictures */

    /**
     * A picture named by a page, turned into one the layout engine can read.
     *
     * A `data:` src is already that and is left alone. Anything else is a name
     * this session holds - a scratchpad, or a key something handed over - and
     * becomes a data URI, because the renderer resolves a relative src against
     * a document base that does not exist here, and an http one would have the
     * layout engine fetching whatever a page asked for.
     *
     * That last part is the security half: a page reaching the network during
     * layout is a page that decides what this server connects to.
     */
    private fun picture(image: Element, problems: MutableList<String>, find: (String) -> Picture?) {
        val src = image.attr("src").trim()
        if (src.isEmpty() || src.startsWith("data:")) return

        if (src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)) {
            problems += "a picture was not drawn: \"$src\" is a web address, and a page is not fetched while it " +
                "is laid out. Put the picture in a scratchpad or pass its key."
            image.replaceWith(note("picture not drawn: it names a web address"))
            return
        }

        val held = find(src)
        if (held == null) {
            problems += "a picture was not drawn: nothing in this session is called \"$src\""
            image.replaceWith(note("picture not drawn: $src"))
            return
        }
        // An SVG goes in as vectors, the way a diagram block does. Issue #549.
        if ((held.contentType ?: sniffed(held.bytes)) == "image/svg+xml") {
            image.replaceWith(inline(String(held.bytes, Charsets.UTF_8)))
            return
        }
        image.attr("src", dataUri(held))
    }

    private fun dataUri(held: Picture): String {
        val type = held.contentType?.takeIf { it.startsWith("image/") } ?: sniffed(held.bytes)
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(held.bytes)
    }

    /** The type the bytes say they are, where nothing else did; PNG, which a drawn picture is, otherwise. */
    private fun sniffed(bytes: ByteArray): String {
        fun at(i: Int) = if (bytes.size > i) bytes[i].toInt() and 0xFF else -1
        val start = String(bytes, 0, minOf(bytes.size, 200), Charsets.UTF_8).trimStart()
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "image/gif"
            at(0) == 'R'.code && at(8) == 'W'.code && at(9) == 'E'.code -> "image/webp"
            start.startsWith("<svg") || (start.startsWith("<?xml") && start.contains("<svg")) -> "image/svg+xml"
            else -> "image/png"
        }
    }

    /* ---------------------------------------------------------------- shared */

    /** One block drawn, or a note saying it was not, with the reason kept. */
    private fun replace(block: Element, problems: MutableList<String>, what: String, draw: () -> Result<String>) {
        // A drawing that throws is one block not drawn, never the whole document. Issue #547.
        runCatching(draw).getOrElse { Result.failure(it) }
            .onSuccess { svg -> block.replaceWith(inline(svg)) }
            .onFailure { why ->
                val said = why.message ?: "it could not be drawn"
                log.warn("A {} could not be drawn and was left out: {}", what, said)
                problems += "a $what was not drawn: $said"
                block.replaceWith(note("$what not drawn: $said"))
            }
    }

    /**
     * The SVG, put into the page as an element rather than as text.
     *
     * Parsed as XML because SVG is XML: the HTML parser treats a self-closing
     * `<path/>` as an open tag and nests everything after it inside, which
     * lays out as a blank box with the drawing swallowed into one element.
     */
    private fun inline(svg: String): Element {
        val cleaned = svg.substringAfter("?>", svg).trim()
        val parsed = Jsoup.parse(cleaned, "", org.jsoup.parser.Parser.xmlParser())
        val root = parsed.selectFirst("svg") ?: return note("drawing not drawn: it came back empty")
        sized(root)
        return Document("").appendChild(root.clone()).child(0)
    }

    /**
     * The drawing given a size in points that the layout engine can use.
     *
     * Not a percentage. `width="100%"` with the height dropped reads as a
     * drawing of no determinable size, and what openhtmltopdf does with one is
     * lay out a box of nothing - a document that is produced, valid, opens
     * fine, and has the diagram missing. Which is the failure this whole change
     * is about, so it is worth saying plainly: the sizes below are the fix for
     * a PDF whose headings came through and whose drawings did not.
     *
     * The natural size is kept where it fits the column and scaled down by its
     * own aspect ratio where it does not, so a wide flowchart comes back at the
     * width of the text rather than off the edge of the page.
     */
    private fun sized(root: Element) {
        val box = root.attr("viewBox").trim().split(Regex("[ ,]+")).mapNotNull { it.toDoubleOrNull() }
        val natural = when {
            box.size == 4 && box[2] > 0 && box[3] > 0 -> box[2] to box[3]
            else -> {
                val wide = points(root.attr("width"))
                val tall = points(root.attr("height"))
                if (wide != null && tall != null && wide > 0 && tall > 0) wide to tall else null
            }
        } ?: return

        val (wide, tall) = natural
        val scale = minOf(1.0, COLUMN / wide)
        root.attr("width", format(wide * scale))
        root.attr("height", format(tall * scale))
    }

    /** A length as a number of points, for the `120px` and `120` an SVG writes. */
    private fun points(said: String): Double? =
        said.trim().removeSuffix("px").removeSuffix("pt").toDoubleOrNull()

    /** Whole points; a fractional width in an attribute buys nothing and reads badly. */
    private fun format(value: Double): String = value.toInt().toString()

    /** Where a drawing would have been, visible rather than silent. */
    private fun note(said: String): Element =
        Element("p").attr("class", "orknux-not-drawn").text("[$said]")

    private companion object {
        /**
         * How wide a drawing may be, in points: A4 less the writer's margins.
         * Kept here rather than asked of the writer, because the same prepared
         * page is what an agent writes to a scratchpad or zips up to send, and
         * a drawing that only has a size once it reaches a PDF has none there.
         */
        const val COLUMN = 480.0

        const val KIND = "kind"
        const val TITLE = "title"
        const val VALUES = "values"
    }
}
