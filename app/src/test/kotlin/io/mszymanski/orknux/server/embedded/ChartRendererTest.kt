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
