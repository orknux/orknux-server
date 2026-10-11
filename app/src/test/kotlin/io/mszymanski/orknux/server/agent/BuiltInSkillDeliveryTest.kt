package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Who is offered the server's own skills.
 *
 * Every agent, whatever its catalog list names - and the box pages only with a
 * box. Session 629: an agent granted a box and only its plugins' catalogs
 * changed a repository without reading how, never built it, and pushed to
 * main.
 */
@SpringBootTest
class BuiltInSkillDeliveryTest(
    @Autowired val skillTool: SkillTool,
    @Autowired val builtIn: BuiltInSkills,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    private val boxPages = setOf(
        "boxes",
        "box-project-changes",
        "box-git",
        "box-debugging",
        "box-long-commands",
        "box-new-project",
    )

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "delivery-" + System.nanoTime())).id)
    }

    private fun agent(name: String, boxes: String? = null, catalogs: List<String> = emptyList()) = agents.save(
        Agent(
            workspaceId = workspaceId,
            name = name,
            type = AgentType.LLM,
            skillCatalogs = catalogs.toMutableList(),
            boxLabelsText = boxes,
        ),
    )

    @Test
    fun `the box pages are the ones whose frontmatter says a box brings them`() {
        assertThat(builtIn.boxKeys).containsExactlyInAnyOrderElementsOf(boxPages)
    }

    @Test
    fun `an agent with no catalogs at all is offered the server's skills`() {
        val bare = agent("bare")

        assertThat(skillTool.list(bare).map { it.id }).contains("plan", "caveman", "watchers")
        assertThat(skillTool.load(bare, "plan")?.catalog).isEqualTo(BuiltInSkills.CATALOG)
    }

    @Test
    fun `an agent given a box is offered the box pages without the server's catalog on its list`() {
        val boxed = agent("boxed", boxes = "local", catalogs = listOf("slack_plugin", "github_plugin"))

        assertThat(skillTool.list(boxed).map { it.id }).containsAll(boxPages)
        val page = requireNotNull(skillTool.load(boxed, BuiltInSkills.PROJECT_CHANGES))
        assertThat(page.catalog).isEqualTo(BuiltInSkills.CATALOG)
        assertThat(page.content).contains("git checkout -b")
        // And searchable mid-work, which is the same list.
        assertThat(skillTool.search(boxed, "push").map { it.id }).contains("box-git")
    }

    @Test
    fun `an agent without a box is not offered the box pages, even naming the catalog`() {
        listOf(agent("plain"), agent("named", catalogs = listOf(BuiltInSkills.CATALOG))).forEach { held ->
            assertThat(skillTool.list(held).map { it.id }).describedAs(held.name).doesNotContainAnyElementsOf(boxPages)
            assertThat(skillTool.load(held, BuiltInSkills.PROJECT_CHANGES)).describedAs(held.name).isNull()
            assertThat(skillTool.search(held, "orkbox_exec")).describedAs(held.name).isEmpty()
        }
    }

    @Test
    fun `a box page marked Always is in force for an agent with a box, and not for one without`() {
        val boxed = agent("always-boxed", boxes = "local").apply { requiredSkills = mutableListOf(BuiltInSkills.PROJECT_CHANGES) }
        val plain = agent("always-plain").apply { requiredSkills = mutableListOf(BuiltInSkills.PROJECT_CHANGES) }

        assertThat(skillTool.always(boxed).map { it.key }).containsExactly(BuiltInSkills.PROJECT_CHANGES)
        assertThat(skillTool.always(plain)).isEmpty()
    }
}
