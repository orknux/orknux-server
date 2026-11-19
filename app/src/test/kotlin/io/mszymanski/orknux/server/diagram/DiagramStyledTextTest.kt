package io.mszymanski.orknux.server.diagram

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Part of a label styled by marks around it: read into runs, taken out of the
 * words that are measured and shown, and drawn in an export as the canvas
 * draws them - a tspan per styled run, in the one line of text.
 */
class DiagramStyledTextTest {

    private val bold = DiagramStyledText.Style(bold = true)

    @Test
    fun `marks style the words between them, nest, and come out of the words`() {
        val runs = DiagramStyledText.runs("Pay **now** or *[later]{#DC2626}*")
        assertThat(runs.map { it.text }).containsExactly("Pay ", "now", " or ", "later")
        assertThat(runs[1].style).isEqualTo(bold)
        assertThat(runs[3].style).isEqualTo(DiagramStyledText.Style(italic = true, color = "#dc2626"))
        assertThat(DiagramStyledText.plain("__under__ ~~gone~~ ***both***")).isEqualTo("under gone both")
        assertThat(DiagramStyledText.runs("***both***").single().style).isEqualTo(DiagramStyledText.Style(bold = true, italic = true))
    }

    @Test
    fun `what is not a pair of marks round a word is the words it was`() {
        assertThat(DiagramStyledText.plain("2 * 3 * 4")).isEqualTo("2 * 3 * 4")
        assertThat(DiagramStyledText.plain("a ** b")).isEqualTo("a ** b")
        assertThat(DiagramStyledText.plain("snake_case_name")).isEqualTo("snake_case_name")
        assertThat(DiagramStyledText.plain("[not a colour]{red}")).isEqualTo("[not a colour]{red}")
        assertThat(DiagramStyledText.plain("\\*literal\\*")).isEqualTo("*literal*")
        assertThat(DiagramStyledText.plain("**unclosed")).isEqualTo("**unclosed")
    }

    @Test
    fun `an export draws the styled part in its style and the marks nowhere`() {
        val node = DiagramShape("a", DiagramNodeKind.BOX, "Pay **now** or [later]{#dc2626}", 0, 0, 300, 60)
        val svg = DiagramSvg().drawing(DiagramDrawing(nodes = listOf(node), edges = emptyList()))
        assertThat(svg).contains("<tspan font-weight=\"700\">now</tspan>").contains("<tspan fill=\"#dc2626\">later</tspan>")
        assertThat(svg).doesNotContain("**").doesNotContain("{#dc2626}")
    }

    @Test
    fun `a link card's words take the colour its whole label was given`() {
        fun drawn(label: String) = DiagramSvg().drawing(DiagramDrawing(nodes = listOf(DiagramShape("a", DiagramNodeKind.LINK, label, 0, 0, 220, 64)), edges = emptyList()))
        assertThat(drawn("[Runbook]{#16a34a}")).contains("fill=\"#16a34a\"").contains("Runbook").doesNotContain("{#16a34a}")
        // Only part of it coloured, the card keeps its link colour.
        assertThat(drawn("Run[book]{#16a34a}")).doesNotContain("fill=\"#16a34a\"")
    }
}
