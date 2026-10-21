package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.manual.OrknuxManual
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The manual, read through the orknux_* tools.
 *
 * Pinned: the jar carries the manual the interface shows, it is offered to a
 * read-only MCP scope and to an agent granted Orknux access alike, a section is
 * read whole or in bounded parts that add up to it, and a search finds what the
 * contents would have.
 */
@SpringBootTest(properties = ["orknux.web.allowed-origins=", "orknux.web.base-url=https://orknux.example.com"])
class ManualToolsTest(
    @Autowired val tools: OrknuxTools,
    @Autowired val agentTools: AgentTools,
    @Autowired val mapper: ObjectMapper,
) {

    private val reading = OrknuxScope(workspaceId = 0)

    private val manual = OrknuxManual.fromClasspath()

    private fun call(name: String, arguments: Map<String, Any?> = emptyMap()): JsonNode =
        mapper.readTree(tools.run(reading, name, mapper.writeValueAsString(arguments)))

    @Test
    fun `the build carries the interface's manual`() {
        assertThat(manual.chapters.map { it.slug }).contains("getting-started", "workflows", "administration")
        assertThat(manual.chapters.flatMap { it.sections }).allSatisfy { assertThat(it.text).isNotBlank() }
    }

    @Test
    fun `a read-only caller and a granted agent are both offered it`() {
        assertThat(tools.specs(reading).map { it.name }).containsAll(ManualTools.NAMES)

        val agent = Agent(workspaceId = 0, name = "", type = AgentType.LLM).apply { orknuxAccess = true }
        assertThat(agentTools.specsFor(agent).map { it.name }).containsAll(ManualTools.NAMES)
    }

    @Test
    fun `the contents name every section by the id it is read by`() {
        val contents = call(ManualTools.CONTENTS)
        val chapters = contents.path("chapters")
        assertThat(chapters.size()).isEqualTo(manual.chapters.size)
        val workflows = (0 until chapters.size()).map { chapters.get(it) }.first { it.path("id").asString() == "workflows" }
        assertThat(workflows.path("url").asString()).isEqualTo("https://orknux.example.com/docs/workflows")
        val sections = workflows.path("sections")
        val ids: List<String> = (0 until sections.size()).map { sections.get(it).path("id").asString() }
        assertThat(ids).isNotEmpty
        assertThat(ids.all { it.startsWith("workflows/") }).isTrue()

        val first = ids.first()
        val read = call(ManualTools.SECTION, mapOf("section" to first))
        assertThat(read.path("text").asString()).isEqualTo(manual.section(first)!!.text)
        assertThat(read.has("next")).isFalse()
    }

    @Test
    fun `a chapter's id alone reads what comes before its first section`() {
        val read = call(ManualTools.SECTION, mapOf("section" to "workflows"))
        assertThat(read.path("title").asString()).isEqualTo("Workflows")
        assertThat(read.path("text").asString()).isEqualTo(manual.chapter("workflows")!!.opening.text)
    }

    /**
     * The splitting, on a text of its own. It was asserted on the manual's
     * longest section, which was over a part long only while the manual had
     * uncommitted edits in it - CI, checking out the committed manual, failed.
     */
    @Test
    fun `a text longer than a part is cut at line breaks into parts that add up to it`() {
        val text = (1..400).joinToString("") { "Line $it of a long section, with some words on it.\n" }
        val part = 1_000
        val parts = mutableListOf<String>()
        var from = 0
        while (from < text.length) {
            val end = ManualTools.cut(text, from, part)
            parts += text.substring(from, end)
            from = end
        }
        assertThat(parts).hasSizeGreaterThan(1)
        assertThat(parts).allSatisfy { assertThat(it.length).isLessThanOrEqualTo(part) }
        assertThat(parts.dropLast(1)).allSatisfy { assertThat(it).endsWith("\n") }
        assertThat(parts.joinToString("")).isEqualTo(text)
    }

    /** Whatever its length, the longest section read through the tool adds up to itself. */
    @Test
    fun `the longest section reads in parts that add up to it`() {
        val longest = manual.chapters.flatMap { it.sections }.maxBy { it.text.length }

        val parts = mutableListOf<String>()
        var from: Int? = 0
        while (from != null) {
            val read = call(ManualTools.SECTION, mapOf("section" to longest.id, "from" to from))
            val text = read.path("text").asString()
            assertThat(text.length).isLessThanOrEqualTo(ManualTools.PART)
            parts += text
            from = read.path("next").takeIf { it.isNumber }?.asInt()
        }
        assertThat(parts.joinToString("")).isEqualTo(longest.text)
    }

    @Test
    fun `an unknown section is refused with where to look`() {
        val read = call(ManualTools.SECTION, mapOf("section" to "workflows/no-such-thing"))
        assertThat(read.path("error").asString()).contains(ManualTools.CONTENTS)
    }

    @Test
    fun `a search finds sections holding every word, with the lines`() {
        val hits = call(ManualTools.SEARCH, mapOf("query" to "cron schedule", "limit" to 3)).path("hits")
        assertThat(hits.size()).isBetween(1, 3)
        (0 until hits.size()).map { hits.get(it) }.forEach { hit ->
            val section = manual.section(hit.path("id").asString())!!
            val text = (section.title + section.text).lowercase()
            assertThat(text).contains("cron", "schedule")
            assertThat(hit.path("lines").size()).isGreaterThan(0)
        }
        assertThat(call(ManualTools.SEARCH, mapOf("query" to "zzqx-nothing-here")).path("hits").size()).isZero()
    }

    @Test
    fun `headings are read outside code only, and a translation is left out`() {
        val parsed = OrknuxManual.of(
            mapOf(
                "01-cli.md" to "# Command line\n\nIntro.\n\n## Install\n\n```\n# not a heading\n## nor this\n```\n\n### Linux\n\n## Install\n\nAgain.\n",
                "01-cli.pl.md" to "# Wiersz polecen\n",
            ),
        )
        val chapter = parsed.chapters.single()
        assertThat(chapter.title).isEqualTo("Command line")
        assertThat(chapter.opening.text).isEqualTo("Intro.")
        assertThat(chapter.sections.map { it.id }).containsExactly("cli/install", "cli/install-2")
        assertThat(chapter.sections.first().text).contains("# not a heading", "## nor this")
        assertThat(chapter.sections.first().subheadings).containsExactly("Linux")
    }
}
