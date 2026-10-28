package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper

/**
 * Every tool a new agent starts at Offer is one tool_find finds.
 *
 * Asked for with the defaults: the rating, picture, MIDI and less basic reading
 * built-ins, and every orknux_* and orkbox_* tool, start at Offer - so under a
 * tool limit each is behind the search, and a tool at Offer that no plausible
 * words find is a tool the agent no longer has. Each is looked for by the
 * words of its own name - or "list" and them, as a model asks for a listing:
 * "workflows" alone ranked orknux_workflows ninth among eight workflow tools -
 * and the ones that matter by the job a model would say it was doing; each
 * must be among the matches one search hands over.
 */
@SpringBootTest
class OfferedToolsFoundTest(
    @Autowired val builtIns: BuiltInTools,
    @Autowired val tools: AgentTools,
    @Autowired val searching: ToolSearchTools,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val ratings: io.mszymanski.orknux.server.rating.RatingTools,
) {

    /**
     * The tools lent per session rather than held - rating, a step's picture -
     * which the round splits by the same [BuiltInTools.carried] the agent's own
     * are split by, so the search sees them beside the rest.
     */
    private val lent by lazy {
        ratings.specs() + listOf(
            io.mszymanski.orknux.server.workflow.StepPictureTools.DRAWING,
            io.mszymanski.orknux.server.workflow.StepPictureTools.LINKING,
        )
    }

    private fun searchable() = tools.offeringFor(agent).searchable + lent.filterNot { BuiltInTools.carried(agent, it.name) }

    private val agent: Agent by lazy {
        val workspace = workspaces.findByName("offered-found") ?: workspaces.save(Workspace(name = "offered-found"))
        Agent(
            workspaceId = requireNotNull(workspace.id),
            name = "Finds",
            type = AgentType.LLM,
            orknuxAccess = true,
            requiredTools = BuiltInTools.startingAlways().toMutableList(),
            hiddenTools = builtIns.orknuxStartsHidden().toMutableList(),
            maxTools = 10,
        ).also { it.boxLabels = listOf("linux") }
    }

    /** What tool_find answers [queries] with: the names, best first. */
    private fun found(searchable: List<io.mszymanski.orknux.connector.model.ToolSpec>, vararg queries: String): List<String> {
        val shed = searching.shed(searchable, mutableSetOf(), room = { 100 })
        val said = shed.run(ToolCall("1", ToolSearchTools.SEARCH, mapper.writeValueAsString(mapOf(ToolSearchTools.QUERIES to queries.toList()))))
        return said.lines().drop(1).map { it.substringBefore(" - ").substringBefore(" (").trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun `every tool that starts at Offer is behind the search, and found by the words of its name`() {
        val searchable = searchable()
        val names = searchable.map { it.name }
        val offered = (BuiltInTools.STARTS_OFFERED + builtIns.orknuxNames() + builtIns.boxNames()) -
            builtIns.orknuxStartsHidden()
        // Not the ones this installation's settings leave out of every agent: none of these depend on one.
        assertThat(names).describedAs("found rather than carried under a tool limit").containsAll(offered)

        val lost = offered.filter { name ->
            val words = name.removePrefix("orknux_").removePrefix("orkbox_")
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ')
            val asked = if (name.startsWith("orkbox_")) "box $words" else words
            name !in found(searchable, asked) && name !in found(searchable, "list $asked")
        }
        assertThat(lost).describedAs("found by its own name's words").isEmpty()
    }

    @Test
    fun `the ones a job asks for are found by the job`() {
        val searchable = searchable()
        val jobs = mapOf(
            "send the review score of this conversation" to "rate_session",
            "remove session rating" to "remove_session_rating",
            "look at a picture" to AgentTools.VIEW,
            "draw svg" to "svg_draw",
            "convert svg to png" to "svg_toPng",
            "pdf page as png image" to "pdf_toPng",
            "render a chart" to "charts_render",
            "read midi file" to "midi_read",
            "write midi music" to "midi_write",
            "query xml" to "xml_query",
            "search pdf" to "pdf_search",
            "measure svg" to "svg_measure",
            "workspace news" to "orknux_news",
            "open an issue" to "orknux_open_issue",
            "comment on issue" to "orknux_comment_on_issue",
            "run a workflow" to "orknux_run_workflow",
            "list the workflows" to "orknux_workflows",
            "create an agent" to "orknux_create_agent",
            "wireframe mockup" to "orknux_create_wireframe",
            "start research" to "orknux_start_research",
            "create diagram" to "orknux_create_diagram",
            "make a spreadsheet" to "orknux_create_sheet",
            "write cells in a sheet" to "orknux_write_sheet_cells",
            "append document section" to "orknux_append_document_section",
            "insert into document" to "orknux_insert_into_document",
            "orknux manual" to "orknux_manual",
            "run a command in a box" to "orkbox_exec",
            "open a box session" to "orkbox_open",
            "write a file in the box" to "orkbox_write",
        )
        val lost = jobs.filter { (job, name) -> name !in found(searchable, job) }
        assertThat(lost).describedAs("job -> tool not found among the matches").isEmpty()
    }
}
