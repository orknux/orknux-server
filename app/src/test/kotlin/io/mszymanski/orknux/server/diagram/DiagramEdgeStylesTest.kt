package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.plugin.SvgRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * A line's style along it and the mark at each end, in place of the four kinds
 * it had - and the four still drawn exactly as they were, since a drawing saved
 * with them must not change how it looks.
 */
class DiagramEdgeStylesTest {

    private val mapper = ObjectMapper()
    private val document = DiagramDocument(mapper)
    private val a = DiagramShape("a", DiagramNodeKind.BOX, "A", 0, 0, 160, 60)
    private val b = DiagramShape("b", DiagramNodeKind.BOX, "B", 400, 0, 160, 60)

    private fun read(edge: String): DiagramLink =
        document.read("""{"nodes":[{"id":"a"},{"id":"b"}],"edges":[{"id":"e","from":"a","to":"b"$edge}]}""").edges.single()

    @Test
    fun `each old kind is read as exactly one line style and two ends, and written back as itself`() {
        DiagramEdgeKind.entries.forEach { kind ->
            val link = read(""","kind":"${kind.key}"""")
            assertThat(listOf(link.line, link.start, link.end)).describedAs(kind.key).containsExactly(kind.line, kind.start, kind.end)
            assertThat(link.kind).describedAs(kind.key).isEqualTo(kind)
        }
        // And a line that says nothing is the arrow it always was.
        assertThat(read("").kind).isEqualTo(DiagramEdgeKind.ARROW)
    }

    @Test
    fun `a style and both ends round-trip through the document, beside the nearest old kind`() {
        val link = read(""","line":"dashed","start":"diamond","end":"triangle"""")
        assertThat(listOf(link.line, link.start, link.end))
            .containsExactly(DiagramLineStyle.DASHED, DiagramEndStyle.DIAMOND, DiagramEndStyle.TRIANGLE)
        val written = mapper.readTree(document.write(DiagramDrawing(listOf(a, b), listOf(link)))).path("edges").single()
        assertThat(listOf("line", "start", "end", "kind").map { written.path(it).stringValue() })
            .containsExactly("dashed", "diamond", "triangle", "both")
        // A kind given beside a style is the default the style overrides, not the other way round.
        assertThat(read(""","kind":"line","end":"circle"""").end).isEqualTo(DiagramEndStyle.CIRCLE)
    }

    @Test
    fun `a change naming only a kind means that kind whole, and one naming an end changes only that end`() {
        val styled = read(""","line":"dotted","start":"circle","end":"open"""")
        val toKind = document.changed(styled, mapper.readTree("""{"kind":"dashed"}"""), emptySet())
        assertThat(listOf(toKind.line, toKind.start, toKind.end))
            .containsExactly(DiagramLineStyle.DASHED, DiagramEndStyle.NONE, DiagramEndStyle.ARROW)
        val oneEnd = document.changed(styled, mapper.readTree("""{"start":"filled-diamond"}"""), emptySet())
        assertThat(listOf(oneEnd.line, oneEnd.start, oneEnd.end))
            .containsExactly(DiagramLineStyle.DOTTED, DiagramEndStyle.FILLED_DIAMOND, DiagramEndStyle.OPEN)
    }

    @Test
    fun `an old kind draws with the markers and dashes it always had`() {
        fun line(kind: DiagramEdgeKind) = Regex("<line [^>]*/>").find(
            DiagramSvg().drawing(DiagramDrawing(listOf(a, b), listOf(DiagramLink.of("e", "a", "b", null, kind)))),
        )!!.value
        assertThat(line(DiagramEdgeKind.ARROW)).contains("marker-end=\"url(#dg-head)\"").doesNotContain("marker-start").doesNotContain("dasharray")
        assertThat(line(DiagramEdgeKind.LINE)).doesNotContain("marker").doesNotContain("dasharray")
        assertThat(line(DiagramEdgeKind.DASHED)).contains("stroke-dasharray=\"6 4\"").contains("marker-end=\"url(#dg-head)\"")
        assertThat(line(DiagramEdgeKind.BOTH)).contains("marker-end=\"url(#dg-head)\"").contains("marker-start=\"url(#dg-tail)\"")
    }

    @Test
    fun `every end is drawn at either end, a dotted line is dotted, and the picture renders`() {
        val ends = DiagramEndStyle.entries.filter { it != DiagramEndStyle.NONE }
        val links = ends.map { end -> DiagramLink("e-${end.key}", "a", "b", null, DiagramLineStyle.DOTTED, end, end) }
        val svg = DiagramSvg().drawing(DiagramDrawing(listOf(a, b), links))
        ends.filter { it != DiagramEndStyle.ARROW }.forEach { end ->
            assertThat(svg).describedAs(end.key)
                .contains("<marker id=\"dg-end-${end.key}\"").contains("<marker id=\"dg-start-${end.key}\"")
                .contains("marker-end=\"url(#dg-end-${end.key})\"").contains("marker-start=\"url(#dg-start-${end.key})\"")
        }
        assertThat(svg).contains("stroke-dasharray=\"1.5 3.5\"")
        assertThat(SvgRenderer().png(svg, null)).isInstanceOf(SvgRenderer.Drawing.Drawn::class.java)
    }

    @Test
    fun `Mermaid draws the old kinds as it did, and the rest as the nearest it has`() {
        fun arrow(link: DiagramLink) =
            DiagramExport(SvgRenderer(), mapper).mermaid(DiagramDrawing(listOf(a, b), listOf(link))).lines().last { it.isNotBlank() }.trim()
        val drawn = DiagramEdgeKind.entries.associateWith { arrow(DiagramLink.of("e", "a", "b", null, it)) }
        assertThat(drawn.getValue(DiagramEdgeKind.ARROW)).contains(" --> ")
        assertThat(drawn.getValue(DiagramEdgeKind.LINE)).contains(" --- ")
        assertThat(drawn.getValue(DiagramEdgeKind.DASHED)).contains(" -.-> ")
        assertThat(drawn.getValue(DiagramEdgeKind.BOTH)).contains(" <--> ")
        assertThat(arrow(DiagramLink("e", "a", "b", null, DiagramLineStyle.SOLID, DiagramEndStyle.NONE, DiagramEndStyle.CIRCLE))).contains(" --o ")
        assertThat(arrow(DiagramLink("e", "a", "b", null, DiagramLineStyle.DOTTED, DiagramEndStyle.NONE, DiagramEndStyle.NONE))).contains(" -.- ")
        assertThat(arrow(DiagramLink("e", "a", "b", null, DiagramLineStyle.DASHED, DiagramEndStyle.FILLED_DIAMOND, DiagramEndStyle.OPEN))).contains(" <-.-> ")
    }
}
