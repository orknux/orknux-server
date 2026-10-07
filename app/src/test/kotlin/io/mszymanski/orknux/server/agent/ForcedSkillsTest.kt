package io.mszymanski.orknux.server.agent

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An agent node names the skills to load, by id, and they are loaded before
 * the model starts. Issue #381.
 *
 * Written on the node, or read from what started the run - the trigger's
 * `commands`, which is how `!review` in Slack lands the agent with the review
 * skill in front of it. An id that names nothing is said in the run log where
 * it came from a mapping, and refused at save where it was written on the
 * graph: a word out of a message is not a reason to stop, a typo on the graph
 * is a reason not to start.
 *
 * The model is a stub that records what it was asked, because what is measured
 * is the request: whether the skill's own words reached the model.
 *
 * Makes a workspace, a workflow, a provider, a model, an agent and a skill,
 * and removes them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ForcedSkillsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        agents.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        received.clear()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    /* ------------------------------------------------------- written on ---- */

    /**
     * Named, and the model told to read it - never the page itself. Issue #521:
     * a command used to write every page it named into the system prompt, and a
     * prompt that is mostly one document is what sent the model off loading
     * skills until the context died.
     */
    @Test
    fun `a skill named on the node is named to the model and never spelled out`() {
        skill("Code review", "review", REVIEW_SAYS)
        val agentId = agent(model(serve()))
        graph(agentId, """{ name: "skillIds", expression: "review", mode: VALUE }""")

        start()

        assertThat(steps.findAll().single().status).isEqualTo(StepStatus.COMPLETED)
        assertThat(received.single())
            .contains("This task names a skill")
            .contains("Load each one with skill_load before anything else")
            .contains("Code review (skill_load review)")
            .doesNotContain(REVIEW_SAYS)
    }

    @Test
    fun `several ids, written with commas, are all loaded`() {
        skill("Code review", "review", REVIEW_SAYS)
        skill("Security", "security", SECURITY_SAYS)
        val agentId = agent(model(serve()))
        graph(agentId, """{ name: "skillIds", expression: "review, security", mode: VALUE }""")

        start()

        assertThat(received.single())
            .contains("This task names these skills")
            .contains("(skill_load review)")
            .contains("(skill_load security)")
            .doesNotContain(REVIEW_SAYS)
            .doesNotContain(SECURITY_SAYS)
    }

    /* --------------------------------------------------- from the trigger --- */

    @Test
    fun `the ids can be read from what started the run, as a list`() {
        skill("Code review", "review", REVIEW_SAYS)
        skill("Security", "security", SECURITY_SAYS)
        val agentId = agent(model(serve()))
        graph(agentId, """{ name: "skillIds", expression: "commands", mode: REFERENCE }""")

        start(input = """{"text":"!review please","commands":["review"]}""")

        assertThat(received.single())
            .contains("(skill_load review)")
            .doesNotContain("(skill_load security)")
            .doesNotContain(REVIEW_SAYS)
    }

    @Test
    fun `an id from the run that names nothing is said in the log, and the rest are loaded`() {
        skill("Code review", "review", REVIEW_SAYS)
        val agentId = agent(model(serve()))
        graph(agentId, """{ name: "skillIds", expression: "commands", mode: REFERENCE }""")

        start(input = """{"commands":["urgent","review"]}""")

        assertThat(received.single()).contains("(skill_load review)").doesNotContain("(skill_load urgent)")
        assertThat(logs.findAll().map { it.message })
            .anyMatch { it.contains("No skill in this workspace has the id urgent") }
        assertThat(steps.findAll().single().status).isEqualTo(StepStatus.COMPLETED)
    }

    @Test
    fun `a node naming no skills is the node it always was`() {
        skill("Code review", "review", REVIEW_SAYS)
        val agentId = agent(model(serve()))
        graph(agentId, mapping = null)

        start()

        assertThat(received.single()).doesNotContain(REVIEW_SAYS).doesNotContain("This task names")
    }

    /* ------------------------------------------------------ advertised ---- */

    /**
     * A person in Slack cannot see the skill list, so the agent is the one who
     * says `!review` exists - and loads the skill itself where the graph did
     * not map the message's commands onto it.
     */
    @Test
    fun `an agent granted skills is told their commands, and to say so when asked`() {
        skill("Code review", "review", REVIEW_SAYS)
        val agentId = agent(model(serve()), grantedCatalog = true)
        graph(agentId, mapping = null)

        start()

        /*
         * Offered rather than Always, so it is counted and not named - issue
         * #521, the three states meaning for a skill what they mean for a tool.
         * The command syntax is still spelled out, with its own id as the
         * example, because a person in Slack cannot see the list.
         */
        // Counted with the server's own skills, which every agent holds, and the
        // syntax shown with the first of them - those are listed first.
        assertThat(received.single())
            .containsPattern("You have \\d+ skills")
            .contains(io.mszymanski.orknux.server.chat.AgentBriefing.LOOK_FOR_SKILLS)
            .contains("like !")
            .contains("write one anywhere in a message")
            .contains("how to use")
            .contains("rather than saying there is none")
            .doesNotContain(REVIEW_SAYS)
    }

    @Test
    fun `and under the workspace's own marker`() {
        skill("Code review", "review", REVIEW_SAYS)
        graphQlTester.document("""mutation { setWorkspaceCommandMarker(workspaceId: $workspaceId, marker: "::") { commandMarker } }""")
            .execute().errors().verify()
        val agentId = agent(model(serve()), grantedCatalog = true)
        graph(agentId, mapping = null)

        start()

        assertThat(received.single()).contains("like ::").doesNotContain("like !")
    }

    /* ------------------------------------------------------- at the save --- */

    @Test
    fun `an id written on the graph that names no skill is refused at the save`() {
        skill("Code review", "review", REVIEW_SAYS)
        val agentId = agent(model(serve()))

        graphQlTester.document(save(agentId, """{ name: "skillIds", expression: "review, nope", mode: VALUE }"""))
            .execute().errors().expect { it.message!!.contains("No skill in this workspace has the id \"nope\"") }.verify()
    }

    @Test
    fun `and one that is not an id at all says so`() {
        val agentId = agent(model(serve()))

        graphQlTester.document(save(agentId, """{ name: "skillIds", expression: "re${'$'}view", mode: VALUE }"""))
            .execute().errors().expect { it.message!!.contains("is not a skill id") }.verify()
    }

    /** A reference is what the run carries; nothing to check before it runs. */
    @Test
    fun `a reference is not checked at the save, because the run decides it`() {
        val agentId = agent(model(serve()))
        graphQlTester.document(save(agentId, """{ name: "skillIds", expression: "trigger.commands", mode: REFERENCE }"""))
            .execute().errors().verify()
    }

    /* ----------------------------------------------------------- fixture --- */

    private fun skill(name: String, key: String, says: String) {
        val content = "---\\nname: $name\\ndescription: How this workspace does it.\\n---\\n\\n$says\\n"
        graphQlTester.document(
            """mutation(${'$'}content: String) { createSkill(input: {
                 workspaceId: $workspaceId, name: "$name", key: "$key", content: ${'$'}content
               }) { id } }""",
        ).variable("content", content.replace("\\n", "\n")).execute().path("createSkill.id").entity(Long::class.java).get()
    }

    /** @param grantedCatalog whether the agent holds the workspace's catalogs, where its skills are. */
    private fun agent(modelId: Long, grantedCatalog: Boolean = false): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Reviewer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        val granted = if (!grantedCatalog) {
            ""
        } else {
            ", skillCatalogs: [" + catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).joinToString(", ") { "\"${it.name}\"" } + "]"
        }
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Reviewer", modelId: $modelId, systemPrompt: "You review."$granted, requiredSkills: [] }) { id } }""",
        ).execute()
        return id
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun save(agentId: Long, mapping: String?): String {
        val mappings = if (mapping == null) "" else ", mappings: [$mapping]"
        return """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "think", kind: AGENT, name: "Reviewer", agentId: $agentId$mappings, x: 0, y: 0 }],
                edges: []
              }) { nodes { key } }
            }
        """
    }

    private fun graph(agentId: Long, mapping: String?) {
        graphQlTester.document(save(agentId, mapping)).execute().errors().verify()
    }

    private fun start(input: String = """{"summary":"the database fell over"}""") {
        val id = graphQlTester.document(
            """
            mutation(${'$'}input: String) {
              startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: ${'$'}input) { id status }
            }
            """,
        ).variable("input", input).execute().path("startExecution.id").entity(Long::class.java).get()
        assertThat(executions.findAll().single { it.id == id }.status)
            .isIn(ExecutionStatus.COMPLETED, ExecutionStatus.RUNNING)
    }

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val body = """{"choices":[{"message":{"role":"assistant","content":"Done."}}],
                "usage":{"prompt_tokens":11,"completion_tokens":1}}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private companion object {
        const val REVIEW_SAYS = "Always read the tests before the code."
        const val SECURITY_SAYS = "Look for secrets in the diff first."
    }
}
