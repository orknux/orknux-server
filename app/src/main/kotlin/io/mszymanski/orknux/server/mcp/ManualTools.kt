package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.chat.LinkTools
import io.mszymanski.orknux.server.manual.ManualSection
import io.mszymanski.orknux.server.manual.OrknuxManual
import io.mszymanski.orknux.server.security.InstallationAddress
import io.mszymanski.orknux.server.security.WebProperties
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The manual, readable by whoever holds the orknux_* tools.
 *
 * An agent asked how a trigger is set up, or what a setting does, answered from
 * whatever it remembered of products that look like this one. The manual is
 * the account of this one, shipped with the version running, so it is offered
 * the way everything else about the product is - through [OrknuxTools], to an
 * agent granted Orknux access and to an MCP client alike.
 *
 * Read-only and offered whatever the scope may write: it is the same text every
 * signed-in person can open under /docs, and says nothing about the workspace.
 *
 * Three tools, because a chapter runs to sixty thousand characters: the
 * contents to find a section, the section itself, and a search for when the
 * contents do not name the thing.
 */
@Service
class ManualTools(
    private val web: WebProperties,
    private val address: InstallationAddress,
    private val mapper: ObjectMapper,
) {

    /** Read on first use and kept: the files are in the jar and cannot change under it. */
    private val manual: OrknuxManual by lazy { OrknuxManual.fromClasspath() }

    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = CONTENTS,
            description =
                "The Orknux manual's contents: every chapter and its sections, with the id $SECTION reads each " +
                    "by. The manual is how this product works, for the version running - read it before " +
                    "explaining a screen, a setting or a feature rather than answering from memory.",
            parameters = emptyList(),
        ),
        ToolSpec(
            name = SECTION,
            description =
                "One section of the Orknux manual, as markdown. A long one comes in parts: pass from with " +
                    "the next it answered to read on.",
            parameters = listOf(
                ToolParameterSpec(
                    "section",
                    "The section's id from $CONTENTS or $SEARCH, like workflows/the-editor; a chapter's id " +
                        "alone reads its opening.",
                    required = true,
                ),
                ToolParameterSpec("from", "Where to carry on from, as next answered it; the start when absent.", required = false),
            ),
        ),
        ToolSpec(
            name = SEARCH,
            description =
                "Searches the Orknux manual for sections holding every one of the words, the most mentions " +
                    "first, with the lines that matched. Read a hit with $SECTION.",
            parameters = listOf(
                ToolParameterSpec("query", "The words to look for; a section must hold all of them.", required = true),
                ToolParameterSpec("limit", "How many sections to return; $SOME_HITS by default, $MOST_HITS at most.", required = false),
            ),
        ),
    )

    fun handles(name: String): Boolean = name in NAMES

    fun run(name: String, arguments: String): String = when (name) {
        CONTENTS -> contents()
        SECTION -> section(arguments)
        SEARCH -> search(arguments)
        else -> refuse("There is no tool called $name")
    }

    private fun contents(): String {
        if (manual.chapters.isEmpty()) return refuse("This build of the server carries no manual")
        return mapper.writeValueAsString(
            mapOf(
                "chapters" to manual.chapters.map { chapter ->
                    mapOf(
                        "id" to chapter.slug,
                        "title" to chapter.title,
                        "url" to link(chapter.slug),
                        "sections" to chapter.sections.map { section ->
                            buildMap {
                                put("id", section.id)
                                put("title", section.title)
                                if (section.subheadings.isNotEmpty()) put("covers", section.subheadings)
                            }
                        },
                    )
                },
            ),
        )
    }

    private fun section(arguments: String): String {
        val node = mapper.readTree(arguments.ifBlank { "{}" })
        val id = said(node.path("section"))?.trim()?.trim('/')?.lowercase()
        if (id.isNullOrEmpty()) return refuse("Say which section: an id from $CONTENTS")
        val section = manual.section(id)
            ?: return refuse("The manual has no section $id; $CONTENTS lists them, $SEARCH finds them")
        val from = counted(node.path("from")) ?: 0
        if (from < 0 || from > section.text.length) return refuse("from is past the end of $id, which is ${section.text.length} characters")
        val end = cut(section, from)
        return mapper.writeValueAsString(
            buildMap {
                put("id", section.id)
                put("chapter", section.chapter)
                put("title", section.title)
                put("url", link(section.chapter))
                put("text", section.text.substring(from, end))
                if (end < section.text.length) {
                    put("next", end)
                    put("length", section.text.length)
                }
            },
        )
    }

    private fun cut(section: ManualSection, from: Int): Int = cut(section.text, from)

    private fun search(arguments: String): String {
        val node = mapper.readTree(arguments.ifBlank { "{}" })
        val query = said(node.path("query"))?.trim()
        if (query.isNullOrEmpty()) return refuse("Say what to look for")
        val limit = (counted(node.path("limit")) ?: SOME_HITS)
            .coerceIn(1, MOST_HITS)
        val hits = manual.search(query, limit)
        return mapper.writeValueAsString(
            mapOf(
                "hits" to hits.map { hit ->
                    mapOf(
                        "id" to hit.section.id,
                        "title" to hit.section.title,
                        "mentions" to hit.mentions,
                        "lines" to hit.lines,
                    )
                },
            ),
        )
    }

    /** The chapter's page; the interface draws no anchor per section. Absolute, as every link a model is handed. */
    private fun link(chapter: String): String =
        LinkTools.absolute(address.base().ifEmpty { web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty() }, "/docs/$chapter")

    private fun said(node: JsonNode): String? = node.takeIf { it.isString }?.stringValue()

    /** A number a model may well have sent as a string. */
    private fun counted(node: JsonNode): Int? =
        if (node.isNumber) node.asInt() else said(node)?.trim()?.toIntOrNull()

    private fun refuse(says: String): String = mapper.writeValueAsString(mapOf("error" to says))

    companion object {
        const val CONTENTS = "orknux_manual"
        const val SECTION = "orknux_manual_section"
        const val SEARCH = "orknux_manual_search"
        val NAMES = setOf(CONTENTS, SECTION, SEARCH)

        /**
         * Characters a part of a section answers with. The longest sections are
         * about fifteen thousand, so nearly all come whole; this bounds the few
         * that do not and anything the manual grows.
         */
        const val PART = 12_000

        /**
         * Where a part of [text] starting at [from] ends: at a line break before
         * the limit, so a part never ends inside a sentence's line or a table's
         * row. Apart from the tool so it is tested on a text of its own, not on
         * whichever section of the manual happens to be longest.
         */
        fun cut(text: String, from: Int, part: Int = PART): Int {
            val limit = from + part
            if (limit >= text.length) return text.length
            // limit - 1: a break at the limit itself ended the part one character past it.
            val atLine = text.lastIndexOf('\n', limit - 1)
            return if (atLine > from) atLine + 1 else limit
        }
        const val SOME_HITS = 8
        const val MOST_HITS = 20
    }
}
