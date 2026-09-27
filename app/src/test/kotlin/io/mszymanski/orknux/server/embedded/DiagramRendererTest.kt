package io.mszymanski.orknux.server.embedded

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every diagram kind that used to be a refusal. Issue #487.
 *
 * The old renderer knew five, and a model asking for anything else was told the
 * headers that work - so what is pinned here is mostly the other kinds: a pie,
 * a gantt, a mindmap. Those are the ones the user actually hit, and the ones a
 * regression would be quietest about, because a diagram that fails to draw
 * still produces a document.
 *
 * No Spring context: this is a renderer with no collaborators, and a test that
 * starts the application to draw a box would take longer than the whole rest of
 * the class.
 */
class DiagramRendererTest {

    private val renderer = DiagramRenderer()

    /** A newline, built rather than typed, per the house rule about heredocs. */
    private val n = 10.toChar().toString()

    private fun drawn(source: String): String {
        val answer = renderer.svg(source)
        assertThat(answer).describedAs(source).isInstanceOf(DiagramRenderer.Drawing.Drawn::class.java)
        val svg = (answer as DiagramRenderer.Drawing.Drawn).svg
        assertThat(svg).contains("<svg")
        /*
         * And a drawing rather than a picture of a complaint. PlantUML answers
         * an image either way: where it cannot read the source it draws the
         * error onto the canvas - a green-on-black crash report, with the
         * version's age on it - and hands that back as a perfectly valid SVG.
         * A test that only checked for `<svg` passed on every one of those,
         * which is how the first flowchart translation looked correct while
         * emitting a syntax error.
         */
        assertThat(svg).doesNotContain("Syntax Error").doesNotContain("days old")
        return svg
    }

    @Test
    fun `a flowchart draws, which is what a model reaches for first`() {
        val svg = drawn(
            listOf(
                "flowchart LR",
                "  A[Webhook] --> B{Verified?}",
                "  B -->|yes| C[Describe]",
                "  B -->|no| D[Drop]",
            ).joinToString(n),
        )
        // The labels survive the translation, which is the part that breaks.
        assertThat(svg).contains("Webhook").contains("Describe")
    }

    @Test
    fun `a sequence diagram draws`() {
        val svg = drawn(
            listOf(
                "sequenceDiagram",
                "  Alice->>Bob: Ask",
                "  Bob-->>Alice: Answer",
            ).joinToString(n),
        )
        assertThat(svg).contains("Alice").contains("Answer")
    }

    /**
     * The one that started this, and it is still not a diagram.
     *
     * A model wrote `pie` into a page, was told the headers that work, and
     * concluded the feature was unavailable. PlantUML does not draw pie charts
     * either - there is no `@startpie` - so what changed is not that this draws
     * one, but that the refusal names the tool that does. A chart plots
     * numbers; that is the chart renderer's work, not this one's.
     *
     * Pinned because the tempting fix is to guess a translation, and PlantUML
     * answers a guess with a picture of a syntax error rather than a failure -
     * which lands in somebody's report looking like a drawing.
     */
    @Test
    fun `a pie is refused as a chart, and told what draws one`() {
        val answer = renderer.svg(
            listOf(
                "pie title Where the money went",
                "  \"Rent\" : 45",
                "  \"Food\" : 30",
            ).joinToString(n),
        )
        assertThat(answer).isInstanceOf(DiagramRenderer.Drawing.Refused::class.java)
        assertThat((answer as DiagramRenderer.Drawing.Refused).reason).contains("charts_render")
    }

    @Test
    fun `a mindmap draws, and its nesting survives the indentation`() {
        val svg = drawn(
            listOf(
                "mindmap",
                "  Root",
                "    Branch one",
                "    Branch two",
            ).joinToString(n),
        )
        assertThat(svg).contains("Root").contains("Branch one")
    }

    @Test
    fun `plantuml written out is passed through untouched`() {
        val svg = drawn(
            listOf(
                "@startuml",
                "Alice -> Bob: Hello",
                "@enduml",
            ).joinToString(n),
        )
        assertThat(svg).contains("Alice")
    }

    /**
     * The flowchart a model actually wrote, verbatim. Issue #525.
     *
     * `graph TD`, a decision, and the label written inside the arrow - `B -- Yes
     * --> C` - which this did not translate: the line went through as it was,
     * PlantUML could not read it, and the drawing came back as a picture of the
     * error that the model then uploaded.
     */
    @Test
    fun `a label written inside the arrow draws, as a model writes it`() {
        val svg = drawn(
            listOf(
                "graph TD",
                "    A[User asks question] --> B{Known issue?}",
                "    B -- Yes --> C[Provide solution]",
                "    B -- No --> D[Investigate/Escalate]",
                "    C --> E[Close ticket]",
                "    D --> E",
            ).joinToString(n),
        )
        assertThat(svg).contains("Known issue?").contains("Close ticket").contains("Yes").contains("No")
    }

    @Test
    fun `the dotted and thick spellings of an inline label draw too`() {
        val svg = drawn(
            listOf(
                "flowchart LR",
                "    A[Start] -. maybe .-> B[Later]",
                "    A == surely ==> C[Now]",
            ).joinToString(n),
        )
        assertThat(svg).contains("maybe").contains("surely")
    }

    /**
     * Whatever PlantUML calls its failure. Issue #525: it described one as
     * `(Error)` rather than as a syntax error, and the check only knew the
     * second - so a red box went out as a drawing with a key to send.
     */
    @Test
    fun `plantuml it cannot read is refused, whatever it calls the failure`() {
        val broken = "@startuml" + n + "rectangle \"A\" as A" + n + "A -- -- --> ((" + n + "@enduml"
        assertThat(renderer.svg(broken)).isInstanceOf(DiagramRenderer.Drawing.Refused::class.java)
    }

    /** And the refusals, which have to stay refusals rather than becoming a red box in somebody's report. */
    @Test
    fun `an empty diagram is refused rather than drawn as an error picture`() {
        assertThat(renderer.svg("   ")).isInstanceOf(DiagramRenderer.Drawing.Refused::class.java)
    }

    @Test
    fun `a diagram longer than the cap is refused`() {
        val long = "@startuml" + n + "Alice -> Bob: x".repeat(20_000) + n + "@enduml"
        val answer = renderer.svg(long)
        assertThat(answer).isInstanceOf(DiagramRenderer.Drawing.Refused::class.java)
        assertThat((answer as DiagramRenderer.Drawing.Refused).reason).contains("characters")
    }
}
