package io.mszymanski.orknux.server.embedded

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * The geometry under svg_measure and the parsing under xml_query, on a small
 * drawing shaped like a KiCad copper layer: the export's own doctype, a
 * footprint whose pads sit in nested transformed groups, a second footprint of
 * a circle and a path, and one known closest gap. Chat 981.
 */
class SvgGeometryTest {

    @Test
    fun `boxes carry every transform of every group above them`() {
        val shapes = measured()
        fun box(id: String) = shapes.single { it.id == id }.box

        // translate(2 1) then scale(2): a 0.5 square at (1, 0) lands at (4, 1), 1 x 1.
        assertThat(box("U1-1").let { listOf(it.x, it.y, it.width, it.height) }).containsExactly(2.0, 1.0, 1.0, 1.0)
        assertThat(box("U1-2").let { listOf(it.x, it.y, it.width, it.height) }).containsExactly(4.0, 1.0, 1.0, 1.0)
        assertThat(box("U1-3").let { listOf(it.x, it.y, it.width, it.height) }).containsExactly(6.0, 1.0, 1.0, 1.0)
        assertThat(box("J1-1").let { listOf(it.x, it.y, it.width, it.height) }).containsExactly(12.0, 1.0, 1.0, 1.0)
        assertThat(box("J1-2").x).isCloseTo(13.8, within(1e-5))
        assertThat(shapes.map { it.tag }).containsExactly("rect", "rect", "path", "circle", "path", "path")
    }

    @Test
    fun `a stroke widens the box unless it is left out`() {
        val document = HostileXml.parse(KICAD.toByteArray())
        val track = listOf(byId(document, "track"))
        val geometry = SvgGeometry(document)
        val stroked = geometry.measure(track, stroke = true).shapes.single()
        val bare = geometry.measure(track, stroke = false).shapes.single()

        // A horizontal line from (2, 8) to (7, 8), stroke-width 0.4 with round caps, inherited from its group.
        assertThat(bare.box.height).isEqualTo(0.0)
        assertThat(stroked.box.height).isCloseTo(0.4, within(1e-5))
        assertThat(stroked.box.width).isCloseTo(5.4, within(1e-5))
        assertThat(stroked.paint).isEqualTo("stroke")
    }

    @Test
    fun `pads group into their two footprints by proximity`() {
        val pads = measured().filter { it.id != "track" }.map { it.box }
        val pitch = SvgGeometry.median(SvgGeometry.nearest(pads))
        assertThat(pitch).isCloseTo(2.0, within(1e-5))

        val groups = SvgGeometry.groups(pads, pitch)
        assertThat(groups).hasSize(2)
        assertThat(groups.map { it.size }).containsExactly(3, 2)
    }

    @Test
    fun `the closest gap is the pair it is, edge to edge`() {
        val shapes = measured().filter { it.id != "track" }
        val (pairs, touching) = SvgGeometry.closest(shapes.map { it.box }, 3)

        assertThat(touching).isZero()
        assertThat(pairs.first().gap).isCloseTo(0.8, within(1e-5))
        assertThat(setOf(shapes[pairs.first().a].id, shapes[pairs.first().b].id)).containsExactlyInAnyOrder("J1-1", "J1-2")
        assertThat(pairs.drop(1).map { it.gap }).allSatisfy { assertThat(it).isCloseTo(1.0, within(1e-5)) }
    }

    @Test
    fun `nearest-neighbour pitch is centre to centre`() {
        val shapes = measured().filter { it.id != "track" }
        val nearest = SvgGeometry.nearest(shapes.map { it.box })
        assertThat(nearest.toList().map { Math.round(it * 1000) / 1000.0 }).containsExactly(2.0, 2.0, 2.0, 1.8, 1.8)
    }

    @Test
    fun `millimetres come from the root's width over its viewBox`() {
        val scale = requireNotNull(SvgGeometry(HostileXml.parse(KICAD.toByteArray())).scale())
        assertThat(scale.mmPerUnitX).isCloseTo(2.0, within(1e-9))
        assertThat(scale.uniform).isTrue()
        assertThat(scale.from).contains("40mm").contains("viewBox")

        val plain = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"/>"""
        assertThat(SvgGeometry(HostileXml.parse(plain.toByteArray())).scale()).isNull()
    }

    @Test
    fun `xpath counts and finds both ways round a namespace`() {
        val bytes = KICAD.toByteArray()
        assertThat((HostileXml.query(bytes, "count(//svg:rect)").answer as HostileXml.Answer.Value).value).isEqualTo(3L)
        assertThat((HostileXml.query(bytes, "//*[local-name()='circle']").answer as HostileXml.Answer.Nodes).nodes).hasSize(1)

        val blind = HostileXml.query(bytes, "//path")
        assertThat(blind.namespacesIgnored).isTrue()
        assertThat((blind.answer as HostileXml.Answer.Nodes).nodes.map { (it as Element).getAttribute("id") })
            .containsExactly("U1-3", "J1-2", "track")
    }

    @Test
    fun `an external entity is refused, and so is anything that is not XML`() {
        val xxe = """<?xml version="1.0"?>
            <!DOCTYPE svg [<!ENTITY secret SYSTEM "file:///c:/windows/win.ini">]>
            <svg xmlns="http://www.w3.org/2000/svg"><title>&secret;</title></svg>"""
        assertThatThrownBy { HostileXml.parse(xxe.toByteArray()) }
            .isInstanceOf(HostileXml.Refused::class.java)
            .hasMessageContaining("outside itself")

        // A doctype naming an external DTD - every KiCad export has one - is read without fetching it.
        val remoteDtd = """<?xml version="1.0"?><!DOCTYPE x SYSTEM "http://example.invalid/x.dtd"><x>fine</x>"""
        assertThat(HostileXml.parse(remoteDtd.toByteArray()).documentElement.textContent).isEqualTo("fine")

        // Ten entities of ten each, a billion characters were it expanded: secure processing stops it.
        val laughs = (1..9).joinToString("") { "<!ENTITY l$it \"" + "&l${it - 1};".repeat(10) + "\">" }
        val bomb = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY l0 \"ha\">$laughs]><x>&l9;</x>"
        assertThatThrownBy { HostileXml.parse(bomb.toByteArray()) }.isInstanceOf(HostileXml.Refused::class.java)

        assertThatThrownBy { HostileXml.parse("<svg><g></svg>".toByteArray()) }
            .isInstanceOf(HostileXml.Refused::class.java)
            .hasMessageContaining("not well-formed")
    }

    private fun measured(): List<SvgGeometry.Measured> {
        val document = HostileXml.parse(KICAD.toByteArray())
        val geometry = SvgGeometry(document)
        return geometry.measure(geometry.drawable(), stroke = true).shapes
    }

    private fun byId(document: org.w3c.dom.Document, id: String): Element {
        val all = document.getElementsByTagNameNS("*", "*")
        return (0 until all.length).map { all.item(it) as Element }.single { it.getAttribute("id") == id }
    }

    companion object {
        /**
         * Two footprints, U1 of three pads at a pitch of 2 and J1 of two at
         * 1.8, five units apart; J1's pads are the closest pair, 0.8 edge to
         * edge. One user unit is 2 mm. A track under a stroked group.
         */
        val KICAD = """<?xml version="1.0" standalone="no"?>
 <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN"
 "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
<svg xmlns:svg="http://www.w3.org/2000/svg" xmlns="http://www.w3.org/2000/svg" version="1.1"
  width="40mm" height="20mm" viewBox="0 0 20 10">
<title>SVG Image created as test-B_Cu.svg</title>
<defs><rect id="unused" x="100" y="100" width="5" height="5"/></defs>
<g style="fill:#000000; stroke:#000000;" transform="translate(2 1)">
  <g transform="scale(2)">
    <rect id="U1-1" x="0" y="0" width="0.5" height="0.5" style="stroke:none"/>
    <rect id="U1-2" x="1" y="0" width="0.5" height="0.5" style="stroke:none"/>
  </g>
  <path id="U1-3" style="fill:#000000; stroke:none;fill-rule:evenodd;" d="M 4,0
5,0
5,1
4,1
Z" />
</g>
<g style="fill:#000000; stroke:none;" transform="translate(12 1)">
  <circle id="J1-1" cx="0.5" cy="0.5" r="0.5"/>
  <path id="J1-2" d="M1.8,0 h1 v1 h-1 z"/>
</g>
<g style="fill:none; stroke:#000000; stroke-width:0.4; stroke-linecap:round;">
  <path id="track" d="M2 8 L7 8"/>
</g>
</svg>"""
    }
}
