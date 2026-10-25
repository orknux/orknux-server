package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.llm.LlmSession
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.StoredKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * xml_query and svg_measure as an agent calls them: a KiCad-like layer kept
 * under a key, and questions about it answered without its markup coming back.
 * Chat 981.
 */
@SpringBootTest
class XmlQueryToolsTest(
    @Autowired val embedded: EmbeddedCapabilities,
    @Autowired val scratch: LlmSessionStore,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId = 0L
    private var sessionId = 0L

    @BeforeEach
    fun keep() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "Xml ${System.nanoTime()}")).id)
        sessionId = requireNotNull(sessions.save(LlmSession(workspaceId = workspaceId, sessionKey = "xml-${System.nanoTime()}")).id)
        scratch.put(sessionId, "pcb.svg", mapper.writeValueAsString(SvgGeometryTest.KICAD), StoredKind("image/svg+xml", false))
    }

    @Test
    fun `a count is a value, and matches come with their attributes`() {
        assertThat(call("xml_query", mapOf("key" to "pcb.svg", "xpath" to "count(//svg:circle | //svg:path)")).path("value").asInt())
            .isEqualTo(4)

        val circles = call("xml_query", mapOf("key" to "pcb.svg", "xpath" to "//*[local-name()='circle']"))
        assertThat(circles.path("count").asInt()).isEqualTo(1)
        val circle = circles.path("nodes").get(0)
        assertThat(circle.path("element").stringValue()).isEqualTo("circle")
        assertThat(circle.path("attributes").path("r").stringValue()).isEqualTo("0.5")
        assertThat(circle.path("attributes").path("id").stringValue()).isEqualTo("J1-1")
    }

    @Test
    fun `an unprefixed name finds the elements of an SVG and says how`() {
        val paths = call("xml_query", mapOf("key" to "pcb.svg", "xpath" to "//path", "limit" to 2))
        assertThat(paths.path("count").asInt()).isEqualTo(3)
        assertThat(paths.path("nodes").size()).isEqualTo(2)
        assertThat(paths.path("more").stringValue()).contains("offset 2")
        assertThat(paths.path("namespaces").stringValue()).contains("local-name()")

        val strict = call("xml_query", mapOf("key" to "pcb.svg", "xpath" to "//svg:path", "offset" to 2))
        assertThat(strict.path("nodes").get(0).path("attributes").path("id").stringValue()).isEqualTo("track")
        assertThat(strict.has("namespaces")).isFalse()
    }

    @Test
    fun `a long attribute is cut with its length said, unless asked for whole`() {
        val long = "M 0 0 " + (1..200).joinToString(" ") { "L $it.000000 $it.000000" }
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><path id="long" d="$long"/></svg>"""
        scratch.put(sessionId, "long.svg", mapper.writeValueAsString(svg), StoredKind("image/svg+xml", false))

        val cut = call("xml_query", mapOf("key" to "long.svg", "xpath" to "//svg:path")).path("nodes").get(0).path("attributes").path("d").stringValue()
        assertThat(cut).hasSizeLessThan(300).endsWith("… (${long.length} characters)")
        val whole = call("xml_query", mapOf("key" to "long.svg", "xpath" to "//svg:path", "full" to true)).path("nodes").get(0).path("attributes").path("d").stringValue()
        assertThat(whole).isEqualTo(long)
    }

    @Test
    fun `a file asking for something outside itself is refused`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY s SYSTEM "file:///c:/windows/win.ini">]><x>&s;</x>"""
        scratch.put(sessionId, "xxe.xml", mapper.writeValueAsString(xxe), StoredKind("application/xml", false))
        val answer = call("xml_query", mapOf("key" to "xxe.xml", "xpath" to "string(/x)"))
        assertThat(answer.path("error").stringValue()).contains("outside itself")
    }

    @Test
    fun `svg_measure answers boxes with transforms applied, groups, pitch, spacing and millimetres`() {
        val answer = call(
            "svg_measure",
            mapOf(
                "key" to "pcb.svg", "select" to "//svg:rect | //svg:circle | //svg:path[@id != 'track']",
                "stats" to "all", "group_by" to "proximity",
            ),
        )
        assertThat(answer.path("count").asInt()).isEqualTo(6)
        val rows = answer.path("rows").values().associateBy { it.get(2).stringValue() }
        // The rect inside defs is chosen by the XPath and measured where it stands; the U1 pads went through two transforms.
        assertThat(rows.getValue("U1-2").values().drop(4).map { it.asDouble() }).containsExactly(4.0, 1.0, 1.0, 1.0)
        assertThat(answer.path("scale").path("mmPerUnit").asDouble()).isEqualTo(2.0)

        val padsOnly = call(
            "svg_measure",
            mapOf("key" to "pcb.svg", "select" to "//svg:g/svg:rect | //svg:g/svg:circle | //svg:path[@id != 'track']", "stats" to "pitch,spacing", "group_by" to "proximity", "mm" to true, "limit" to 0),
        )
        assertThat(padsOnly.path("units").stringValue()).isEqualTo("mm")
        assertThat(padsOnly.path("count").asInt()).isEqualTo(5)
        assertThat(padsOnly.path("rows").size()).isZero()
        assertThat(padsOnly.path("groups").path("count").asInt()).isEqualTo(2)
        assertThat(padsOnly.path("groups").path("sizes").toString()).isEqualTo("[[2,1],[3,1]]")
        assertThat(padsOnly.path("pitch").path("median").asDouble()).isEqualTo(4.0)
        val spacing = padsOnly.path("spacing")
        assertThat(spacing.path("minGap").asDouble()).isEqualTo(1.6)
        assertThat(spacing.path("rows").get(0).values().drop(3).map { it.stringValue() }).containsExactlyInAnyOrder("J1-1", "J1-2")
        assertThat(padsOnly.path("extent").path("width").asDouble()).isEqualTo(25.6)
    }

    @Test
    fun `the descriptions send a large file to the questions, by the names the tools are declared with`() {
        val declared = embedded.toolSpecs().map { it.name }.toSet()
        val read = embedded.toolSpecs().single { it.name == SvgCapability.READ_TOOL }
        assertThat(read.description).contains(XmlCapability.QUERY_TOOL, SvgCapability.MEASURE_TOOL)
        assertThat(declared).contains(XmlCapability.QUERY_TOOL, SvgCapability.MEASURE_TOOL, PdfCapability.SEARCH_TOOL)
        // Every tool name a description mentions is one that exists.
        val named = Regex("\\b(?:svg|xml|pdf)_[A-Za-z]+\\b")
        embedded.toolSpecs().filter { it.name.substringBefore('_') in setOf("svg", "xml", "pdf") }.forEach { spec ->
            named.findAll(spec.description).forEach { assertThat(declared).describedAs(spec.name).contains(it.value) }
        }
    }

    private fun call(name: String, arguments: Map<String, Any?>): JsonNode =
        mapper.readTree(embedded.run(name, mapper.writeValueAsString(arguments), workspaceId, sessionId))
}
