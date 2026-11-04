package io.mszymanski.orknux.server.embedded

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The chart kinds, and the pie in particular. Issues #507 and #503.
 *
 * The pie is the one with history: a model wrote a mermaid pie into a page, was
 * refused, tried four spellings and told somebody the feature was unavailable.
 * The diagram renderer still refuses it - PlantUML has no pie - and points here,
 * so this is where the promise has to be kept. A refusal naming a tool that then
 * cannot draw it either would be worse than the original.
 *
 * No Spring context: a renderer with no collaborators.
 */
class ChartRendererTest {

    private val renderer = ChartRenderer()

    private val spend = listOf(
        ChartRenderer.Point("Rent", 45.0),
        ChartRenderer.Point("Food", 30.0),
        ChartRenderer.Point("Rest", 25.0),
    )

    private fun drawn(kind: String, points: List<ChartRenderer.Point> = spend): String {
        val answer = renderer.svg(kind, points, title = "Where the money went")
        assertThat(answer).describedAs(kind).isInstanceOf(ChartRenderer.Drawing.Drawn::class.java)
        val svg = (answer as ChartRenderer.Drawing.Drawn).svg
        assertThat(svg).contains("<svg")
        return svg
    }

    /** The one the diagram renderer sends people here for. */
    @Test
    fun `a pie draws, which is the promise the diagram refusal makes`() {
        val svg = drawn("pie")
        assertThat(svg).contains("Rent")
    }

    @Test
    fun `every kind this offers actually draws`() {
        ChartRenderer.Kind.entries.forEach { kind -> drawn(kind.asked) }
    }

    @Test
    fun `the title is drawn`() {
        assertThat(drawn("column")).contains("Where the money went")
    }

    /* ------------------------------------------------------------ its words */

    private val kafka = listOf(
        ChartRenderer.Point("Kafka 1 (+7)", 7.0),
        ChartRenderer.Point("Kafka 2 (-35)", -35.0),
        ChartRenderer.Point("Kafka 3 (+35)", 35.0),
    )

    /** Every piece of text the SVG draws, in order. */
    private fun words(svg: String): List<String> =
        Regex(">([^<>]+)<").findAll(svg).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

    /**
     * Reported from a picture of a column chart: "label" under the axis, "value"
     * up its side, and a legend titled "label" repeating the three names the
     * axis already showed. The first two are this class's own column names.
     */
    @Test
    fun `no chart says label or value, which are only the renderer's column names`() {
        ChartRenderer.Kind.entries.forEach { kind ->
            val points = if (kind.asked in setOf("pie", "donut")) spend else kafka
            val said = words((renderer.svg(kind.asked, points, title = "Max") as ChartRenderer.Drawing.Drawn).svg)
            assertThat(said).describedAs("${kind.asked}: $said").doesNotContain("label", "value")
        }
    }

    @Test
    fun `a column names each label once, along its axis, and draws no legend repeating them`() {
        listOf("column", "bar").forEach { kind ->
            val said = words((renderer.svg(kind, kafka) as ChartRenderer.Drawing.Drawn).svg)
            kafka.forEach { point ->
                assertThat(said.count { it == point.label }).describedAs("$kind: $said").isEqualTo(1)
            }
        }
    }

    @Test
    fun `a pie keeps the legend that names its slices`() {
        val said = words(drawn("pie"))
        assertThat(said).contains("Rent", "Food", "Rest")
    }

    /** A pie had a blue grid behind it, with x and y axes running -0.4 to 0.4. */
    @Test
    fun `a pie draws no axes, grid or numbers round it`() {
        listOf("pie", "donut").forEach { kind ->
            val said = words(drawn(kind))
            assertThat(said).describedAs("$kind: $said").doesNotContain("x", "y", "0", "0.2", "-0.2", "0.4")
        }
    }

    @Test
    fun `axes are titled where the caller names them, on a bar too`() {
        listOf("column", "bar").forEach { kind ->
            val svg = (renderer.svg(kind, kafka, across = "Cluster", up = "Samples over the limit") as ChartRenderer.Drawing.Drawn).svg
            assertThat(words(svg)).describedAs(kind).contains("Cluster", "Samples over the limit")
        }
    }

    /**
     * One axis named and not the other, on a bar too. A bar is a column turned
     * on its side, and the titles are left out by where they sit on the page
     * while the names are given by what they measure - so a mix-up would blank
     * the one named and print the column's own name, "label" or "value", on the
     * other. Both titles given, as above, cannot show that.
     */
    @Test
    fun `one axis named and the other not names only that one, on a bar too`() {
        listOf("column", "bar").forEach { kind ->
            val valueOnly = words((renderer.svg(kind, kafka, up = "Samples over the limit") as ChartRenderer.Drawing.Drawn).svg)
            assertThat(valueOnly).describedAs("$kind, value axis named").contains("Samples over the limit").doesNotContain("label", "value")
            val labelOnly = words((renderer.svg(kind, kafka, across = "Cluster") as ChartRenderer.Drawing.Drawn).svg)
            assertThat(labelOnly).describedAs("$kind, label axis named").contains("Cluster").doesNotContain("label", "value")
        }
    }

    /**
     * How far down the picture [label] is drawn: the translation of its tick,
     * the last one before its text that moves only down. The text itself sits
     * in a group of its own moved only to the left of the tick.
     */
    private fun heightOf(svg: String, label: String): Double {
        val at = svg.indexOf(">$label<")
        assertThat(at).describedAs(label).isPositive()
        val move = Regex("""translate\(\s*([-0-9.]+)[ ,]+([-0-9.]+)""").findAll(svg.substring(0, at))
            .last { it.groupValues[1].toDouble() == 0.0 }
        return move.groupValues[2].toDouble()
    }

    /**
     * Reported by a model looking at its own picture: five fruits given apples
     * first, drawn with apples at the bottom and figs at the top. A bar is a
     * column turned on its side, and turned, the first label lies lowest.
     */
    @Test
    fun `a bar chart lists its labels top down in the order given`() {
        val fruit = listOf("Apples" to 40.0, "Pears" to 22.0, "Plums" to 15.0, "Cherries" to 31.0, "Figs" to 7.0)
            .map { (label, value) -> ChartRenderer.Point(label, value) }
        val svg = (renderer.svg("bar", fruit) as ChartRenderer.Drawing.Drawn).svg
        val heights = fruit.map { heightOf(svg, it.label) }
        assertThat(heights).describedAs(heights.toString()).isSorted()
        assertThat(heights.toSet()).hasSize(fruit.size)
    }

    /* ------------------------------------------------------- what it refuses */

    @Test
    fun `a kind it does not have is refused, and the refusal lists what it has`() {
        val answer = renderer.svg("sunburst", spend)
        assertThat(answer).isInstanceOf(ChartRenderer.Drawing.Refused::class.java)
        val said = (answer as ChartRenderer.Drawing.Refused).reason
        // Naming what is available is the difference between a retry and a dead
        // end - the whole lesson of #503, applied to the tool it points at.
        assertThat(said).contains("pie").contains("column")
    }

    @Test
    fun `nothing to plot is refused rather than drawn empty`() {
        assertThat(renderer.svg("bar", emptyList())).isInstanceOf(ChartRenderer.Drawing.Refused::class.java)
    }

    /**
     * A negative slice is refused rather than drawn.
     *
     * lets-plot will draw *something* for one, and what it draws misrepresents
     * the data - a slice whose angle says the opposite of its number. A chart
     * that lies is worse than a chart that is missing, because nobody checks it.
     */
    @Test
    fun `a negative value in a round chart is refused, and a bar is offered instead`() {
        val answer = renderer.svg("pie", listOf(ChartRenderer.Point("Loss", -4.0)))
        assertThat(answer).isInstanceOf(ChartRenderer.Drawing.Refused::class.java)
        assertThat((answer as ChartRenderer.Drawing.Refused).reason).contains("bar")
    }

    /** And the same value on a bar is fine, because a bar can point downwards. */
    @Test
    fun `a negative value on a bar draws`() {
        val answer = renderer.svg("column", listOf(ChartRenderer.Point("Loss", -4.0)))
        assertThat(answer).isInstanceOf(ChartRenderer.Drawing.Drawn::class.java)
    }

    @Test
    fun `too many values is refused with the number in it`() {
        val many = (1..200).map { ChartRenderer.Point("n$it", it.toDouble()) }
        val answer = renderer.svg("bar", many)
        assertThat(answer).isInstanceOf(ChartRenderer.Drawing.Refused::class.java)
        assertThat((answer as ChartRenderer.Drawing.Refused).reason).contains("200")
    }

    @Test
    fun `an impossible width is refused`() {
        assertThat(renderer.svg("bar", spend, width = 10))
            .isInstanceOf(ChartRenderer.Drawing.Refused::class.java)
    }
}
