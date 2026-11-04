package io.mszymanski.orknux.server.embedded

import org.jetbrains.letsPlot.commons.encoding.UnsupportedRGBEncoder
import org.jetbrains.letsPlot.commons.geometry.DoubleVector
import org.jetbrains.letsPlot.core.util.PlotSvgExportCommon
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A chart, drawn here. Issue #507.
 *
 * ## Why this exists at all
 *
 * A pie is not a diagram. PlantUML draws neither pie charts nor plots - checked
 * in the jar rather than taken from the documentation, there is no `@startpie` -
 * and that is the right answer rather than a gap, because plotting numbers is a
 * different job from laying out boxes and arrows. So [DiagramRenderer] refuses
 * a chart by name and points here.
 *
 * ## SVG, not a picture
 *
 * `PlotSvgExportCommon` turns a specification into SVG with no AWT and no
 * display, which matters twice. There is no screen on a server. And vectors are
 * what let a chart go *into* a page - the PDF writer draws it onto the page
 * rather than pasting a raster on top, which is issue #488: an agent asked for
 * charts in a report, could not get a picture into the HTML, and reasoned its
 * way to HTML tables instead.
 *
 * ## What a caller writes
 *
 * A small specification of its own rather than lets-plot's, because lets-plot's
 * is a grammar of graphics and a model asked to write one will spend a turn
 * getting the layers wrong. Here a chart is a kind, some labelled values, and a
 * title - which is what somebody asking for a chart actually has.
 */
@Component
class ChartRenderer {

    private val log = LoggerFactory.getLogger(javaClass)

    /** What came of asking for a chart. */
    sealed interface Drawing {
        data class Drawn(val svg: String, val kind: String) : Drawing

        data class Refused(val reason: String) : Drawing
    }

    /** The kinds this draws, as a caller names them. */
    enum class Kind(val asked: String) {
        BAR("bar"),
        COLUMN("column"),
        LINE("line"),
        AREA("area"),
        PIE("pie"),
        DONUT("donut"),
        SCATTER("scatter"),
        ;

        companion object {
            fun of(name: String): Kind? = entries.firstOrNull { it.asked.equals(name.trim(), ignoreCase = true) }

            /** ", one of bar, column, …" - so a refusal says what was available. */
            fun offered(): String = entries.joinToString(", ") { it.asked }
        }
    }

    /** One labelled value. */
    data class Point(val label: String, val value: Double)

    /**
     * One chart, as SVG.
     *
     * @param width in points, which is what a page is measured in; the height
     *   follows from it so a caller cannot ask for a shape nothing reads.
     * @param across what the labels are, written under them; none where null.
     * @param up what the numbers are, written beside them; none where null.
     */
    fun svg(
        kind: String,
        points: List<Point>,
        title: String? = null,
        width: Int = WIDE,
        across: String? = null,
        up: String? = null,
    ): Drawing {
        val drawn = Kind.of(kind)
            ?: return Drawing.Refused("there is no chart kind called \"$kind\"; this draws ${Kind.offered()}")
        if (points.isEmpty()) return Drawing.Refused("there is nothing to plot: no values were given")
        if (points.size > MOST_POINTS) {
            return Drawing.Refused(
                "that is ${points.size} values, and at most $MOST_POINTS go in one chart. A chart somebody " +
                    "has to read is not a table.",
            )
        }
        if (width < NARROWEST || width > WIDEST) {
            return Drawing.Refused("a width has to be between $NARROWEST and $WIDEST points")
        }
        /*
         * A negative value is refused for the round kinds rather than drawn.
         * A pie slice of minus four has no meaning - lets-plot will draw
         * something, and what it draws is a lie about the data.
         */
        if (drawn in ROUND && points.any { it.value < 0 }) {
            return Drawing.Refused("a ${drawn.asked} cannot show a negative value; use a bar or a column chart")
        }

        val spec = specOf(drawn, points, title, width, across?.trim()?.ifEmpty { null }, up?.trim()?.ifEmpty { null })
        return try {
            val svg = PlotSvgExportCommon.buildSvgImageFromRawSpecs(
                spec,
                DoubleVector(width.toDouble(), (width * TALL_ENOUGH).toDouble()),
                /*
                 * No raster encoder, because nothing here rasterises. It is
                 * only reached for a layer that embeds a bitmap - an image
                 * geom - and this draws none: the whole point of the answer is
                 * that it is vectors a page can carry.
                 */
                UnsupportedRGBEncoder,
                false,
            )
            Drawing.Drawn(svg, drawn.asked)
        } catch (failure: Exception) {
            log.warn("A chart could not be drawn: {}", failure.message)
            Drawing.Refused(failure.message ?: "the chart could not be drawn")
        }
    }

    /**
     * The plot specification, written as the maps lets-plot reads.
     *
     * Built by hand rather than through the Kotlin DSL: the DSL builds the same
     * maps, and going through it would mean the figure type and its whole
     * builder tree on the classpath to produce something this can write in
     * twenty lines.
     */
    private fun specOf(
        kind: Kind,
        points: List<Point>,
        title: String?,
        width: Int,
        across: String?,
        up: String?,
    ): MutableMap<String, Any> {
        val labels = points.map { it.label }
        val values = points.map { it.value }

        val data = mapOf(LABEL to labels, VALUE to values)
        val layer: MutableMap<String, Any> = when (kind) {
            /*
             * A pie and a donut are the same layer; the hole is what differs,
             * and lets-plot takes it as a fraction of the radius.
             */
            Kind.PIE, Kind.DONUT -> mutableMapOf(
                "geom" to "pie",
                "mapping" to mapOf("slice" to VALUE, "fill" to LABEL),
                "hole" to if (kind == Kind.DONUT) DONUT_HOLE else 0.0,
                "stat" to "identity",
                "size" to ROUND_SIZE,
            )

            Kind.LINE -> mutableMapOf("geom" to "line", "mapping" to mapOf("x" to LABEL, "y" to VALUE))
            Kind.AREA -> mutableMapOf("geom" to "area", "mapping" to mapOf("x" to LABEL, "y" to VALUE))
            Kind.SCATTER -> mutableMapOf("geom" to "point", "mapping" to mapOf("x" to LABEL, "y" to VALUE))

            /*
             * A bar lies along the x axis and a column stands up it, which is
             * the difference everybody means by the two words and nearly every
             * charting library gets to argue about. Here: column stands up,
             * bar lies down, and a bar is a column flipped.
             */
            Kind.BAR, Kind.COLUMN -> mutableMapOf(
                "geom" to "bar",
                "mapping" to mapOf("x" to LABEL, "y" to VALUE, "fill" to LABEL),
                "stat" to "identity",
            )
        }

        val spec = mutableMapOf<String, Any>(
            "kind" to "plot",
            "data" to data,
            "layers" to listOf(layer),
            "ggsize" to mapOf("width" to width, "height" to (width * TALL_ENOUGH).toInt()),
        )
        if (title != null && title.isNotBlank()) {
            spec["ggtitle"] = mapOf("text" to title.trim())
        }
        if (kind == Kind.BAR) {
            spec["coord"] = mapOf("name" to "flip")
        }
        /*
         * "label" and "value" are this class's own column names, and lets-plot
         * writes a column's name as an axis title and a legend title unless
         * told otherwise - so every chart read "label" under its axis and
         * "value" up its side, words that describe nothing to whoever reads
         * it. An axis has a title only where the caller gave one.
         *
         * A pie has no axes, so its legend is what names the slices, and it
         * keeps one - without a title, which would be "label" again. Every
         * other kind names its points along an axis already, so a legend there
         * is the same words a second time, in a third of the width.
         */
        val theme = mutableMapOf<String, Any>()
        if (kind in ROUND) {
            // No axes and no grid. "none", which this used to ask for, is
            // lets-plot's bare debugging theme - a blue grid and x and y axes
            // from -0.5 to 0.5 round every pie - and this version has no
            // "void", so the plain theme is taken and the axes put out.
            theme["name"] = "minimal"
            listOf("axis", "axis_title", "axis_text", "axis_ticks", "axis_line", "panel_grid", "legend_title")
                .forEach { theme[it] = BLANK }
        } else {
            theme["legend_position"] = "none"
            /*
             * By what each axis measures, not by where it is drawn: a bar is a
             * column flipped, and lets-plot's axis_title_x follows the x
             * aesthetic across the flip, as a scale's name does. Blanked by
             * position instead, a bar with only its value axis named lost that
             * name and printed the column's own, "label", on the other.
             */
            val scales = mutableListOf<Map<String, Any>>()
            val x = mutableMapOf<String, Any>()
            if (across == null) theme["axis_title_x"] = BLANK else x["name"] = across
            /*
             * Flipped, the first label lies at the bottom: a bar chart of five
             * fruits given apples first read figs first, upside down from the
             * list it was drawn from. Reversed, it reads top down as given.
             */
            if (kind == Kind.BAR) {
                x["discrete"] = true
                x["reverse"] = true
            }
            if (x.isNotEmpty()) scales += mapOf("aesthetic" to "x") + x
            if (up == null) theme["axis_title_y"] = BLANK else scales += mapOf("aesthetic" to "y", "name" to up)
            if (scales.isNotEmpty()) spec["scales"] = scales
        }
        spec["theme"] = theme
        return spec
    }

    private companion object {
        const val LABEL = "label"
        const val VALUE = "value"

        /** How lets-plot is told to leave a part of the theme out. */
        val BLANK = mapOf("blank" to true)

        /** A column of a page, in points, which is what the PDF writer will ask for. */
        const val WIDE = 480

        const val NARROWEST = 120
        const val WIDEST = 2000

        /** Past this it is a table somebody should read instead. */
        const val MOST_POINTS = 60

        /** A chart is wider than it is tall, the way a figure in a report is. */
        const val TALL_ENOUGH = 0.62

        /** How much of a donut is hole, as a fraction of the radius. */
        const val DONUT_HOLE = 0.5

        /** What lets-plot wants for a pie to fill its box. */
        const val ROUND_SIZE = 20.0

        val ROUND = setOf(Kind.PIE, Kind.DONUT)
    }
}
