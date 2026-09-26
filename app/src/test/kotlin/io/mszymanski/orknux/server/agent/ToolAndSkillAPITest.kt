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
 * The two things a workspace gives its agents to work with: tools, which are
 * JavaScript, and skills, which are markdown. Both are checked before they are
 * stored, and by the same button in the editor.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ToolAndSkillAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentToolRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val skillCatalogs: SkillCatalogRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        tools.deleteAll()
        skills.deleteAll()
        skillCatalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `a new tool starts from a stub that parses, and records who made it`() {
        graphQlTester.document(
            """mutation { createTool(input: { workspaceId: $workspaceId, name: "httpRequest",
                 description: "Make HTTP requests to external APIs" })
               { name description source enabled lastModifiedBy } }""",
        ).execute()
            .path("createTool.name").entity(String::class.java).isEqualTo("httpRequest")
            .path("createTool.enabled").entity(Boolean::class.java).isEqualTo(true)
            .path("createTool.lastModifiedBy").entity(String::class.java).isEqualTo("alice")
            .path("createTool.source").entity(String::class.java)
            .satisfies { source -> assertThat(source).contains("export default async function httpRequest") }

        assertThat(audit.findAll().map { it.message }).contains("Tool httpRequest created")
    }

    /**
     * A tool is written in TypeScript and stored with the JavaScript it compiled
     * to, the way a function is — and the two are only ever written together.
     */
    @Test
    fun `a tool is saved as both what runs and what was written`() {
        val id = tool("shout")

        graphQlTester.document(
            """
            mutation {
              updateTool(id: $id, input: {
                source: "export default function shout(input) { return input.toUpperCase(); }",
                typescript: "export default function shout(input: string): string { return input.toUpperCase(); }"
              }) { source typescript }
            }
            """,
        ).execute()
            .path("updateTool.source").entity(String::class.java)
            .isEqualTo("export default function shout(input) { return input.toUpperCase(); }")
            .path("updateTool.typescript").entity(String::class.java)
            .isEqualTo("export default function shout(input: string): string { return input.toUpperCase(); }")
    }

    /** Half of it is refused: the editor and the sandbox would disagree. */
    /**
     * The editor writes a tool's signature, and reading the tool back gives it
     * again - which is the whole of what issue #140 asked for.
     */
    @Test
    fun `a tool's parameters are saved from the editor and read back`() {
        val id = graphQlTester.document(
            """mutation { createTool(input: { workspaceId: $workspaceId, name: "forecast" }) { id } }""",
        ).execute().path("createTool.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateTool(id: $id, input: {
                 params: [{ name: "city", type: STRING }, { name: "days", type: NUMBER }],
                 source: "export default function forecast(city, days) { return {}; }",
                 typescript: "export default function forecast(city: string, days: number) { return {}; }"
               }) { params { name type } signature } }""",
        ).execute()
            .path("updateTool.params[*].name").entityList(String::class.java).containsExactly("city", "days")
            .path("updateTool.signature").entity(String::class.java).isEqualTo("(city: string, days: number)")

        graphQlTester.document("""query { tool(id: $id) { signature } }""").execute()
            .path("tool.signature").entity(String::class.java).isEqualTo("(city: string, days: number)")
    }

    /** A new tool takes what it says it takes, from the stub onwards. */
    @Test
    fun `a stub is printed taking the parameters the tool was created with`() {
        graphQlTester.document(
            """mutation { createTool(input: { workspaceId: $workspaceId, name: "forecast",
                 params: [{ name: "city", type: STRING }, { name: "days", type: NUMBER }] })
               { source typescript } }""",
        ).execute()
            .path("createTool.source").entity(String::class.java)
            .satisfies { assertThat(it).contains("function forecast(city, days)") }
            .path("createTool.typescript").entity(String::class.java)
            .satisfies { assertThat(it).contains("function forecast(city: string, days: number)") }
    }

    /**
     * Two parameters of one name is a hole rather than a curiosity: the model
     * addresses them by name, so one of them is unreachable.
     */
    @Test
    fun `a tool cannot take two parameters of the same name`() {
        val id = graphQlTester.document(
            """mutation { createTool(input: { workspaceId: $workspaceId, name: "forecast" }) { id } }""",
        ).execute().path("createTool.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateTool(id: $id, input: {
                 params: [{ name: "city", type: STRING }, { name: "city", type: NUMBER }]
               }) { id } }""",
        ).execute().errors().satisfy { failures ->
            assertThat(failures.first().message).contains("already takes a parameter called")
        }
    }

    @Test
    fun `a tool saved with one half of its code is refused`() {
        val id = tool("shout")

        graphQlTester.document(
            """mutation { updateTool(id: $id, input: { source: "export default function shout() {}" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("TypeScript this JavaScript was compiled from") == true }
            .verify()

        graphQlTester.document(
            """mutation { updateTool(id: $id, input: { typescript: "export default function shout(): void {}" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("JavaScript compiled from this TypeScript") == true }
            .verify()
    }

    @Test
    fun `a tool that does not parse is refused, and Validate says where`() {
        val id = tool("parseDocument")

        graphQlTester.document(
            """mutation { updateTool(id: $id, input: { source: "export default function ( {" }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).isNotEmpty()
        }

        // The button answers rather than failing the request.
        graphQlTester.document(
            """mutation { validateToolSource(workspaceId: $workspaceId, source: "export default function ( {")
               { valid message line } }""",
        ).execute()
            .path("validateToolSource.valid").entity(Boolean::class.java).isEqualTo(false)
            .path("validateToolSource.message").entity(String::class.java).satisfies { message ->
                assertThat(message).isNotBlank()
            }

        // The stored source is the one that parsed.
        assertThat(tools.findAll().single().source).contains("parseDocument")
    }

    @Test
    fun `a tool name has to be one JavaScript can call`() {
        graphQlTester.document(
            """mutation { createTool(input: { workspaceId: $workspaceId, name: "not a name" }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("not a name a script can be called by")
        }
    }

    @Test
    fun `the toggle leaves a tool defined but out of reach`() {
        val id = tool("sendNotification")

        graphQlTester.document("""mutation { setToolEnabled(id: $id, enabled: false) { enabled } }""")
            .execute().path("setToolEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)

        assertThat(tools.findAll().single().enabled).isFalse()
        assertThat(audit.findAll().map { it.message }).contains("Tool sendNotification disabled")
    }

    @Test
    fun `a new skill starts from the shape, with its parts named`() {
        graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "codeReviewGuidelines",
                 description: "Guidelines for thorough and consistent code reviews" })
               { name content enabled lastModifiedBy } }""",
        ).execute()
            .path("createSkill.lastModifiedBy").entity(String::class.java).isEqualTo("alice")
            .path("createSkill.content").entity(String::class.java).satisfies { content ->
                assertThat(content).startsWith("---")
                assertThat(content).contains("name: codeReviewGuidelines")
                assertThat(content).contains("description: Guidelines for thorough")
            }

        assertThat(audit.findAll().map { it.message }).contains("Skill codeReviewGuidelines created")
    }

    @Test
    fun `a skill needs frontmatter that names it, describes it, and closes`() {
        // No fence at all.
        expectInvalid("# Just a heading\n\nand a body.", "opens with a --- frontmatter fence")

        // Opened and never closed.
        expectInvalid("---\nname: x\ndescription: y\n\n# Body", "never closed")

        // Closed, but says nothing about itself.
        expectInvalid("---\nname: x\n---\n\n# Body", "no description")

        // Named, described, closed — and then nothing.
        expectInvalid("---\nname: x\ndescription: y\n---\n\n   \n", "no body")

        // A description with nothing after the colon is not a description.
        expectInvalid("---\nname: x\ndescription:\n---\n\n# Body", "description is empty")
    }

    @Test
    fun `a well formed skill is accepted, and Validate agrees`() {
        val content = """
            ---
            name: code-review-guidelines
            description: Guidelines for conducting thorough and consistent code reviews.
            ---

            # Code Review Guidelines

            ## Objective

            Ensure all code submissions meet workspace quality standards before merging.
        """.trimIndent()

        graphQlTester.document(
            """mutation(${'$'}content: String!) { createSkill(input: {
                 workspaceId: $workspaceId, name: "codeReviewGuidelines", content: ${'$'}content
               }) { id } }""",
        ).variable("content", content).execute().path("createSkill.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation(${'$'}content: String!) {
                 validateSkillContent(workspaceId: $workspaceId, content: ${'$'}content) { valid message }
               }""",
        ).variable("content", content).execute()
            .path("validateSkillContent.valid").entity(Boolean::class.java).isEqualTo(true)
            .path("validateSkillContent.message").valueIsNull()
    }

    /**
     * Skills live in catalogs, and one is made for them if nobody has.
     *
     * Creating a skill is not the moment to teach somebody about folders, so the
     * first one arrives in a General rather than being refused — but it does
     * arrive somewhere, because a skill with nowhere to live is invisible on a
     * screen that lists catalogs.
     */
    @Test
    fun `a skill made before any catalog exists gets one`() {
        graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "codeReview" }) { id catalogId } }""",
        ).execute().path("createSkill.catalogId").entity(Long::class.java).get()

        assertThat(skillCatalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map { it.name })
            .containsExactly("General")
    }

    @Test
    fun `a catalog holds its own skills, and the list can be asked for just those`() {
        val reviews = catalog("Reviews")
        val onCall = catalog("On call")
        skill("codeReview", reviews)
        skill("securityReview", reviews)
        skill("pagerResponse", onCall)

        graphQlTester.document(
            """query { skillCatalogs(workspaceId: $workspaceId) { name skillCount } }""",
        ).execute()
            // Ordered by name, each saying what it holds.
            .path("skillCatalogs[0].name").entity(String::class.java).isEqualTo("On call")
            .path("skillCatalogs[0].skillCount").entity(Int::class.java).isEqualTo(1)
            .path("skillCatalogs[1].skillCount").entity(Int::class.java).isEqualTo(2)

        graphQlTester.document(
            """query { workspaceSkills(workspaceId: $workspaceId, catalogId: $reviews) { content { name } totalElements } }""",
        ).execute()
            .path("workspaceSkills.totalElements").entity(Int::class.java).isEqualTo(2)

        // Without a catalog it is still the whole workspace, which is what the
        // editor and an agent ask for.
        graphQlTester.document(
            """query { workspaceSkills(workspaceId: $workspaceId) { totalElements } }""",
        ).execute().path("workspaceSkills.totalElements").entity(Int::class.java).isEqualTo(3)
    }

    /** Deleting a folder takes what was in it, the way a memory catalog does. */
    @Test
    fun `deleting a catalog takes its skills`() {
        val reviews = catalog("Reviews")
        skill("codeReview", reviews)

        graphQlTester.document("""mutation { deleteSkillCatalog(id: $reviews) }""")
            .execute().path("deleteSkillCatalog").entity(Boolean::class.java).isEqualTo(true)

        assertThat(skills.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message })
            .anyMatch { it.contains("Skill catalog Reviews removed with 1 skills") }
    }

    /** A grant is per catalog: what an agent knows is decided once, not per skill. */
    @Test
    fun `an agent is granted skill catalogs, and the change is recorded`() {
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Reviewer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Reviewer", skillCatalogs: ["Reviews"] })
               { skillCatalogs } }""",
        ).execute()
            .path("updateAgent.skillCatalogs").entityList(String::class.java).containsExactly("Reviews")

        assertThat(audit.findAll().map { it.message })
            .anyMatch { it.contains("Agent Reviewer given skill catalog Reviews") }
    }

    @Test
    fun `two tools in a workspace cannot share a name, and neither can two skills`() {
        tool("queryDatabase")
        graphQlTester.document(
            """mutation { createTool(input: { workspaceId: $workspaceId, name: "queryDatabase" }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("already exists")
        }

        skill("securityAuditChecklist")
        graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "securityAuditChecklist" }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("already exists")
        }
    }

    @Test
    fun `deleting says so, and a rename is recorded as one`() {
        val id = tool("generateReport")

        graphQlTester.document(
            """mutation { updateTool(id: $id, input: { name: "buildReport" }) { name } }""",
        ).execute().path("updateTool.name").entity(String::class.java).isEqualTo("buildReport")
        assertThat(audit.findAll().map { it.message }).contains("Tool generateReport renamed to buildReport")

        graphQlTester.document("""mutation { deleteTool(id: $id) }""")
            .execute().path("deleteTool").entity(Boolean::class.java).isEqualTo(true)
        assertThat(tools.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message }).contains("Tool buildReport deleted")
    }

    /**
     * Issue #165: a tool an agent may call cannot be deleted out from under it.
     *
     * The grant is a **name**, so nothing would have been left dangling to
     * notice - the agent would simply have stopped being able to do this, with
     * its own screen still listing the grant and nothing anywhere saying what
     * changed. Silent capability loss is worse than a refused delete, and the
     * refusal names the agent because taking the grant off it is the way out.
     */
    @Test
    fun `a tool an agent is granted cannot be deleted`() {
        val id = tool("forecast")
        granted(tools = listOf("forecast"))

        graphQlTester.document("""mutation { deleteTool(id: $id) }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message)
                    .isEqualTo("forecast is granted to the agent Reviewer, so it cannot be deleted")
            }

        assertThat(tools.findAll().map { it.name }).containsExactly("forecast")
    }

    /**
     * The same, one grant over.
     *
     * A catalog is the unit an agent is granted, so deleting one takes every
     * skill in it away from every agent that had them at once.
     */
    @Test
    fun `a skill catalog an agent draws on cannot be deleted`() {
        val reviews = catalog("Reviews")
        skill("codeReview", reviews)
        granted(skillCatalogs = listOf("Reviews"))

        graphQlTester.document("""mutation { deleteSkillCatalog(id: $reviews) }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message)
                    .isEqualTo("Reviews is granted to the agent Reviewer, so it cannot be deleted")
            }

        assertThat(skills.findAll().map { it.name }).containsExactly("codeReview")
    }

    /**
     * Where the guard stops, said out loud.
     *
     * A single skill carries no grant - the catalog does - so removing one
     * leaves the grant exactly as it was and every agent holding it keeps
     * everything else in the folder. That is editing a folder, not revoking a
     * capability, and it is not refused.
     */
    @Test
    fun `a skill inside a granted catalog still deletes`() {
        val reviews = catalog("Reviews")
        val one = skill("codeReview", reviews)
        skill("securityReview", reviews)
        granted(skillCatalogs = listOf("Reviews"))

        graphQlTester.document("""mutation { deleteSkill(id: $one) }""")
            .execute().path("deleteSkill").entity(Boolean::class.java).isEqualTo(true)

        assertThat(skills.findAll().map { it.name }).containsExactly("securityReview")
    }

    /**
     * And the guards are guards, not bans.
     *
     * A tool nobody was granted and a catalog nobody draws on delete as they
     * always did, with an agent in the workspace holding other grants - so what
     * is being checked is that the question is about *this* name, not that the
     * workspace happens to have no agents.
     */
    @Test
    fun `a tool and a catalog nobody was granted still delete`() {
        val spareTool = tool("unusedTool")
        val spareCatalog = catalog("Unused")
        granted(tools = listOf("somethingElse"), skillCatalogs = listOf("SomethingElse"))

        graphQlTester.document("""mutation { deleteTool(id: $spareTool) }""")
            .execute().path("deleteTool").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("""mutation { deleteSkillCatalog(id: $spareCatalog) }""")
            .execute().path("deleteSkillCatalog").entity(Boolean::class.java).isEqualTo(true)
    }

    /** One agent called Reviewer, holding the grants it is given. */
    private fun granted(tools: List<String> = emptyList(), skillCatalogs: List<String> = emptyList()) {
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Reviewer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val granted = tools.joinToString(", ") { "\"$it\"" }
        val drawn = skillCatalogs.joinToString(", ") { "\"$it\"" }
        graphQlTester.document(
            """
            mutation {
              updateAgent(id: $agentId, input: {
                name: "Reviewer", tools: [$granted], skillCatalogs: [$drawn]
              }) { tools skillCatalogs }
            }
            """,
        ).execute()
            /*
             * The workspace's tools, and every built-in beside them. Issue
             * #482: a built-in is offered to every agent and can only be hidden
             * where a workspace has said it will take that risk, so a save that
             * names no built-in no longer takes them away - which is what this
             * used to assert, and what left agents without a clock.
             */
            .path("updateAgent.tools").entityList(String::class.java).get()
            .let { held ->
                assertThat(held).containsAll(tools)
                assertThat(held).containsAll(io.mszymanski.orknux.server.chat.BuiltInTools.GRANTED)
            }
    }

    private fun expectInvalid(content: String, reason: String) {
        graphQlTester.document(
            """mutation(${'$'}content: String!) {
                 validateSkillContent(workspaceId: $workspaceId, content: ${'$'}content) { valid message line }
               }""",
        ).variable("content", content).execute()
            .path("validateSkillContent.valid").entity(Boolean::class.java).isEqualTo(false)
            .path("validateSkillContent.message").entity(String::class.java).satisfies { message ->
                assertThat(message).contains(reason)
            }
    }

    /**
     * The mismatch behind "at least a is not a string": a tool whose code took
     * three arguments while its details declared one saved fine, and then the
     * model filled the one declared parameter — whose whole object landed in
     * the code's first argument. Refused at save now, as a function's is.
     */
    @Test
    fun `code that takes a different number of arguments than the tool declares is refused`() {
        val id = tool("lookup")

        graphQlTester.document(
            """
            mutation {
              updateTool(id: $id, input: {
                source: "export default function lookup(a, b) { return {}; }",
                typescript: "export default function lookup(a: string, b: string) { return {}; }"
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("The code takes 2") == true }.verify()
    }

    @Test
    fun `a tool's timeout is set on its own, cleared by null, and bounded`() {
        val id = tool("slowCall")

        graphQlTester.document(
            """mutation { setToolTimeout(id: $id, seconds: 120) { timeoutSeconds } }""",
        ).execute().path("setToolTimeout.timeoutSeconds").entity(Int::class.java).isEqualTo(120)

        graphQlTester.document(
            """mutation { setToolTimeout(id: $id, seconds: 601) { id } }""",
        ).execute().errors().expect { it.message?.contains("between 1 and 600") == true }.verify()

        graphQlTester.document(
            """mutation { setToolTimeout(id: $id, seconds: null) { timeoutSeconds } }""",
        ).execute().path("setToolTimeout.timeoutSeconds").valueIsNull()
    }

    private fun tool(name: String): Long = graphQlTester.document(
        """mutation { createTool(input: { workspaceId: $workspaceId, name: "$name" }) { id } }""",
    ).execute().path("createTool.id").entity(Long::class.java).get()

    private fun skill(name: String): Long = graphQlTester.document(
        """mutation { createSkill(input: { workspaceId: $workspaceId, name: "$name" }) { id } }""",
    ).execute().path("createSkill.id").entity(Long::class.java).get()

    private fun catalog(name: String): Long = graphQlTester.document(
        """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
    ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()

    private fun skill(name: String, catalogId: Long): Long = graphQlTester.document(
        """mutation { createSkill(input: { workspaceId: $workspaceId, name: "$name", catalogId: $catalogId })
           { id } }""",
    ).execute().path("createSkill.id").entity(Long::class.java).get()
}
