package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.mcp.SkillTools
import io.mszymanski.orknux.server.revision.ComponentRevisionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * An agent writes a skill, and changes one, through the orknux_* tools.
 *
 * Asked in a Slack thread to remember the team's way of drawing a Kafka topic,
 * the agent could only save a memory, which nothing but itself reads. A skill
 * is what every agent granted its catalog follows. Written through the
 * editor's door, so the format check, the audit entry and the version of what
 * a change replaced are the editor's own.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SkillToolsTest(
    @Autowired val tools: OrknuxTools,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val revisions: ComponentRevisionRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private lateinit var scope: OrknuxScope

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "skill-tools-${System.nanoTime()}")).id)
        scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "OKO Bot")
    }

    private fun call(name: String, arguments: Map<String, Any?>, on: OrknuxScope = scope): JsonNode =
        mapper.readTree(tools.run(on, name, mapper.writeValueAsString(arguments)))

    private val kafka = """
        ---
        name: Drawing Kafka topics
        description: How this team draws a Kafka topic on a diagram.
        ---

        # Drawing Kafka topics

        ## Objective

        Topics read the same on every diagram the team draws.

        ## Steps

        - Draw a topic as a database shape, coloured yellow.
        - Label it with the topic's literal name only.
    """.trimIndent()

    @Test
    fun `an agent writes a skill, which the list and the reader then show`() {
        val made = call(
            SkillTools.CREATE,
            mapOf("name" to "Drawing Kafka topics", "description" to "How this team draws a Kafka topic on a diagram.", "content" to kafka),
        )

        assertThat(made.path("error").isMissingNode).describedAs(made.toString()).isTrue()
        assertThat(made.path("url").asString()).endsWith("/workspace/$workspaceId/skills/${made.path("id").asLong()}")
        val held = requireNotNull(skills.findByIdOrNull(made.path("id").asLong()))
        assertThat(held.content).contains("database shape, coloured yellow")
        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message }).contains("Skill Drawing Kafka topics created")

        val listed = call(SkillTools.LIST, emptyMap())
        assertThat(listed.path("catalogs").values().flatMap { it.path("skills").values() }.map { it.path("name").asString() })
            .containsExactly("Drawing Kafka topics")
        assertThat(call(SkillTools.READ, mapOf("skill" to held.key)).path("content").asString()).isEqualTo(kafka)
    }

    @Test
    fun `a change keeps what it replaced as a version, and only what is sent changes`() {
        val id = call(SkillTools.CREATE, mapOf("name" to "Drawing Kafka topics", "description" to "Topics.", "content" to kafka)).path("id").asLong()
        val versionsBefore = revisions.count()

        val changed = call(
            SkillTools.UPDATE,
            mapOf("skill" to "Drawing Kafka topics", "content" to kafka.replace("coloured yellow", "coloured yellow, with no Read or Write in the label")),
        )

        assertThat(changed.path("updated").asString()).isEqualTo("Drawing Kafka topics")
        val held = requireNotNull(skills.findByIdOrNull(id))
        assertThat(held.content).contains("with no Read or Write in the label")
        assertThat(held.description).isEqualTo("Topics.")
        assertThat(revisions.count()).describedAs("the text it replaced is kept").isGreaterThan(versionsBefore)
    }

    @Test
    fun `a skill without its header is refused in words, and nothing is written`() {
        val refused = call(SkillTools.CREATE, mapOf("name" to "Loose", "description" to "No header.", "content" to "# Loose\n\nJust text."))

        assertThat(refused.path("error").asString()).isNotBlank()
        assertThat(skills.findByWorkspaceIdAndName(workspaceId, "Loose")).isNull()
    }

    @Test
    fun `a conversation that may only read is not offered the writing two, and is refused them`() {
        val reading = scope.copy(mayWrite = false)
        val offered = tools.specs(reading).map { it.name }

        assertThat(offered).contains(SkillTools.LIST, SkillTools.READ).doesNotContain(SkillTools.CREATE, SkillTools.UPDATE)
        assertThat(call(SkillTools.CREATE, mapOf("name" to "Sneaky", "description" to "x"), on = reading).path("error").asString())
            .contains("may read")
        assertThat(skills.findByWorkspaceIdAndName(workspaceId, "Sneaky")).isNull()
    }

    @Test
    fun `a skill in another workspace is not found by its id`() {
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "other-${System.nanoTime()}")).id)
        val theirs = call(
            SkillTools.CREATE,
            mapOf("name" to "Theirs", "description" to "x", "content" to kafka.replace("Drawing Kafka topics", "Theirs")),
            on = OrknuxScope(workspaceId = elsewhere, mayWrite = true, actor = "Someone"),
        ).path("id").asLong()

        assertThat(call(SkillTools.READ, mapOf("skill" to theirs.toString())).path("error").asString()).contains("no skill called")
        assertThat(catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).flatMap { skills.findByCatalogId(requireNotNull(it.id)) }).isEmpty()
    }
}
