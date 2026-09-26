package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Every skill has an id: what a graph or a Slack command names it by. Issue
 * #381.
 *
 * Letters, underscores and hyphens, unique in the workspace. Derived from the
 * name unless somebody typed one, and a derived id that is already held is
 * moved along with a letter rather than refused - nobody typed it, so there
 * is nobody to refuse. A typed one that clashes is refused, because somebody
 * did.
 *
 * Makes a workspace and its skills, and removes them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SkillIdTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    /** For the ids a model types back at it; see the load test. Issue #435. */
    @Autowired val skillTool: SkillTool,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /* --------------------------------------------------------- the rule --- */

    @Test
    fun `an id is letters, underscores and hyphens, and nothing else`() {
        assertThat(SkillKeys.usable("review")).isTrue()
        assertThat(SkillKeys.usable("code_review-v")).isTrue()
        assertThat(SkillKeys.usable("review 2")).isFalse()
        assertThat(SkillKeys.usable("review2")).isFalse()
        assertThat(SkillKeys.usable("")).isFalse()
        assertThat(SkillKeys.usable("a".repeat(SkillKeys.KEY_LENGTH + 1))).isFalse()
    }

    @Test
    fun `a name becomes an id that keeps its words`() {
        // A hyphen where the rule refuses a run, not nothing: an id a model can
        // read off a list and type back. Issue #435.
        assertThat(SkillKeys.derive("Answering in a thread")).isEqualTo("answering-in-a-thread")
        assertThat(SkillKeys.derive("Incident Response (2024)")).isEqualTo("incident-response")
        assertThat(SkillKeys.derive("code-review_v2")).isEqualTo("code-review_v")
        assertThat(SkillKeys.derive("2024")).describedAs("nothing left").isEqualTo("skill")
    }

    /**
     * The ids an older rule ran together are still the same id, so a graph or a
     * command written against one goes on naming its skill. Issue #435.
     */
    @Test
    fun `an id is the same id however it is spelled`() {
        assertThat(SkillKeys.same("answering-in-a-thread", "Answeringinathread")).isTrue()
        assertThat(SkillKeys.same("answering-in-a-thread", "Answering in a thread")).isTrue()
        assertThat(SkillKeys.same("answering-in-a-thread", "answering_in_a_thread")).isTrue()
        assertThat(SkillKeys.same("review", "escalation")).isFalse()
        assertThat(SkillKeys.same("", "")).describedAs("nothing is not an id").isFalse()
    }

    /* ---------------------------------------------------- the two doors --- */

    @Test
    fun `a skill made without an id gets the name's`() {
        graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "Incident Response!" }) { key } }""",
        ).execute().path("createSkill.key").entity(String::class.java).isEqualTo("incident-response")
    }

    @Test
    fun `and a second whose name comes to the same id is moved along, not refused`() {
        skill("Review")
        graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "Review!" }) { key } }""",
        ).execute().path("createSkill.key").entity(String::class.java).isEqualTo("review-b")
    }

    @Test
    fun `a typed id is kept as typed`() {
        graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "Code review", key: "review" }) { key } }""",
        ).execute().path("createSkill.key").entity(String::class.java).isEqualTo("review")
    }

    @Test
    fun `a typed id that breaks the rule is refused`() {
        graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "Code review", key: "review 2" }) { key } }""",
        ).execute().errors().expect { it.message!!.contains("cannot be a skill id") }.verify()
        assertThat(skills.findAll()).isEmpty()
    }

    @Test
    fun `a typed id another skill holds is refused, whatever its case`() {
        skill("Review")
        graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "Second", key: "REVIEW" }) { key } }""",
        ).execute().errors().expect { it.message!!.contains("already exists") }.verify()
    }

    @Test
    fun `an id can be changed on the editor, under the same rules`() {
        val id = skill("Review")
        skill("Other")

        graphQlTester.document("""mutation { updateSkill(id: $id, input: { key: "code-review" }) { key } }""")
            .execute().path("updateSkill.key").entity(String::class.java).isEqualTo("code-review")
        graphQlTester.document("""mutation { updateSkill(id: $id, input: { key: "other" }) { key } }""")
            .execute().errors().expect { it.message!!.contains("already exists") }.verify()
        // Its own id again is not a clash.
        graphQlTester.document("""mutation { updateSkill(id: $id, input: { key: "code-review" }) { key } }""")
            .execute().path("updateSkill.key").entity(String::class.java).isEqualTo("code-review")
    }

    @Test
    fun `renaming a skill leaves its id alone`() {
        val id = skill("Review")
        graphQlTester.document("""mutation { updateSkill(id: $id, input: { name: "Thorough review" }) { key } }""")
            .execute().path("updateSkill.key").entity(String::class.java).isEqualTo("review")
    }

    /**
     * What a model actually types. It read the id in `skill_list` some turns
     * ago and types it back from memory - or types the name, or an id it has
     * put a borrowed namespace in front of. None of those is a different skill.
     * Issue #435.
     */
    @Test
    fun `a skill is loaded however the model spells its id`() {
        skill("Answering in a thread")
        val agent = agents.save(
            Agent(
                workspaceId = workspaceId,
                name = "responder",
                type = AgentType.LLM,
                skillCatalogs = mutableListOf(catalogs.findAll().first().name),
            ),
        )

        listOf(
            "answering-in-a-thread",
            "Answering in a thread",
            "answering_in_a_thread",
            "Answeringinathread",
            "plugin::answering-in-a-thread",
        ).forEach { asked ->
            assertThat(skillTool.load(agent, asked)?.name)
                .describedAs(asked)
                .isEqualTo("Answering in a thread")
        }

        assertThat(skillTool.load(agent, "something else")).isNull()
    }

    private fun skill(name: String): Long = graphQlTester.document(
        """mutation { createSkill(input: { workspaceId: $workspaceId, name: "$name" }) { id } }""",
    ).execute().path("createSkill.id").entity(Long::class.java).get()
}
