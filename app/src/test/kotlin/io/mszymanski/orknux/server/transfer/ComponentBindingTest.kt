package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowStatus
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The half of the catalogue that reaches outside itself.
 *
 * An agent, an action, a trigger and a workflow travel the same way everything
 * else does — by name, in one transaction, or not at all — but each of them
 * points at something no file can carry, because it is kept beside a key. What
 * is worth holding here is the shape of that: the name and the type reach the
 * file and the credential never does, an import that has not been told what a
 * name means refuses by kind and name rather than inventing a connection, and
 * being told is one more call to the same plan rather than a second protocol.
 *
 * The five kinds that travelled before are here too, in one test, for the thing
 * that would be found out by somebody's export breaking: the file they produce
 * is what it always was, down to the version it claims.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ComponentBindingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val memoryCatalogs: MemoryCatalogRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val connections: WorkspaceConnectionRepository,
    @Autowired val mcpServers: McpServerRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var from: Long = 0
    private var into: Long = 0

    @BeforeEach
    fun reset() {
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        triggers.deleteAll()
        actions.deleteAll()
        agents.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        objects.deleteAll()
        tools.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        memoryCatalogs.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        connections.deleteAll()
        mcpServers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        from = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        into = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
    }

    @Test
    fun `an agent carries what it may call, and names the model it thinks with`() {
        createTool(from, "lookup")
        createSkill(from, "Triage", catalog = "Support")
        val modelId = createModel(from, provider = "Anthropic prod", model = "Claude Sonnet", key = "sk-never-exported")
        createMcpServer(from, "Jira MCP", secret = "mcp-never-exported")
        val agentId = createAgent(
            from,
            "Triage bot",
            modelId = modelId,
            // And one of the server's own, which every agent holds by name
            // since #444 and which is a component of no file.
            tools = listOf("lookup", "note_to_self"),
            skillCatalogs = listOf("Support"),
            memoryCatalogs = listOf("Runbooks"),
            mcpServers = listOf("Jira MCP"),
        )

        val json = export(from, "AGENT", agentId, "DEEP")

        // What it may call travels with it; what it thinks with is named. The
        // key that reaches the model is the thing this whole format exists to
        // leave behind. The built-in is a name in the grant and nothing more:
        // no component, and nothing the target has to be asked for.
        assertThat(kinds(json)).containsExactly("TOOL", "SKILL", "AGENT")
        assertThat(json).contains("note_to_self")
        assertThat(json)
            .contains("Claude Sonnet").contains("Anthropic prod").contains("Jira MCP")
            .doesNotContain("sk-never-exported").doesNotContain("mcp-never-exported")

        val refused = plan(into, json)
        assertThat(refused.importable).isFalse()
        val wanted = refused.entries.single { it.external == "MODEL" }
        assertThat(wanted.name).isEqualTo("Anthropic prod / Claude Sonnet")
        assertThat(wanted.disposition).isEqualTo("MISSING")
        assertThat(refused.problems)
            .anySatisfy { assertThat(it).contains("no model called Anthropic prod / Claude Sonnet") }
            .anySatisfy { assertThat(it).contains("no mcp server called Jira MCP") }
            .allSatisfy { assertThat(it).contains("Triage bot needs one") }

        // The target's own, under names with nothing in common with the ones in
        // the file: a binding is what says the two are the same thing.
        val theirs = createModel(into, provider = "Azure", model = "GPT-4o", key = "their own key")
        val theirServer = createMcpServer(into, "Jira (staging)", secret = "their own token")
        val answers = bindings(
            bound("MODEL", "Anthropic prod / Claude Sonnet", theirs),
            bound("MCP_SERVER", "Jira MCP", theirServer),
        )
        val told = plan(into, json, answers)
        assertThat(told.importable).isTrue()
        assertThat(told.entries.single { it.external == "MODEL" }.targetName).isEqualTo("Azure / GPT-4o")

        import(into, json, answers)

        val agent = agents.findByWorkspaceIdAndName(into, "Triage bot")!!
        assertThat(agent.modelId).isEqualTo(theirs)
        // The built-in arrives as itself, beside the tool the file carried -
        // and is kept the way #455 keeps one: not a grant, but not hidden.
        assertThat(agent.tools).containsExactly("lookup")
        assertThat(io.mszymanski.orknux.server.chat.BuiltInTools.granted(agent, "note_to_self")).isTrue()
        // And one the grant list left out is still on: hiding a built-in is
        // refused unless the workspace allows it (#482, #483), so the source
        // agent never had finish_answer hidden, and neither does its copy.
        assertThat(agent.hiddenTools).doesNotContain("finish_answer")
        assertThat(io.mszymanski.orknux.server.chat.BuiltInTools.granted(agent, "finish_answer")).isTrue()
        assertThat(agent.skillCatalogs).containsExactly("Support")
        assertThat(agent.memoryCatalogs).containsExactly("Runbooks")
        // An agent holds an MCP server by name, so the grant is written as the
        // name the bound server has here rather than the one the file used.
        assertThat(agent.mcpServers).containsExactly("Jira (staging)")
        // The skills it was granted came with it, and the two catalogs it names
        // were made here rather than asked about.
        assertThat(skills.findByWorkspaceIdAndName(into, "Triage")).isNotNull()
        assertThat(catalogs.findByWorkspaceIdAndName(into, "Support")).isNotNull()
        assertThat(memoryCatalogs.findByWorkspaceIdAndName(into, "Runbooks")).isNotNull()
    }

    @Test
    fun `a granted tool renamed on the way in is still the tool the agent may call`() {
        createTool(from, "lookup")
        val agentId = createAgent(from, "Triage bot", tools = listOf("lookup"))
        // The target has a lookup of its own, which is not ours to replace.
        createTool(into, "lookup")

        val json = export(from, "AGENT", agentId, "DEEP")
        import(into, json)

        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot")!!.tools).containsExactly("lookup_2")
    }

    @Test
    fun `a connection is named and never carried, and a trigger arrives switched off`() {
        val connectionId = createConnection(from, "Slack", secret = "xoxb-never-exported")
        val triggerId = createTrigger(from, "On mention", connectionId)

        val json = export(from, "TRIGGER", triggerId, "DEEP")
        assertThat(json).contains("Slack").contains("SLACK").doesNotContain("xoxb-never-exported")

        val refused = plan(into, json)
        assertThat(refused.importable).isFalse()
        assertThat(refused.entries.single { it.external == "CONNECTION" }.name).isEqualTo("Slack")
        assertThat(refused.problems.single()).contains("no connection called Slack here")

        // Refusing a plan is not enough: the mutation refuses too, and writes
        // nothing on the way.
        graphQlTester.document(
            """mutation { importComponents(workspaceId: $into, envelope: ${quote(json)}) { importable } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("Nothing was imported")
        }
        assertThat(triggers.findByWorkspaceIdAndName(into, "On mention")).isNull()

        val theirs = createConnection(into, "Slack (staging)", secret = "their own token")
        val answers = bindings(bound("CONNECTION", "Slack", theirs))
        assertThat(plan(into, json, answers).importable).isTrue()
        import(into, json, answers)

        val trigger = triggers.findByWorkspaceIdAndName(into, "On mention")!!
        assertThat(trigger.connectionId).isEqualTo(theirs)
        // Never listening on arrival, whatever it was where it came from.
        assertThat(trigger.enabled).isFalse()
    }

    @Test
    fun `a connection of the same name here needs nobody to say so`() {
        val connectionId = createConnection(from, "Slack", secret = "xoxb-never-exported")
        val actionId = createAction(from, "Post it", connectionId)
        createConnection(into, "Slack", secret = "their own token")

        val json = export(from, "ACTION", actionId, "DEEP")
        val told = plan(into, json)

        assertThat(told.importable).isTrue()
        assertThat(told.entries.single { it.external == "CONNECTION" }.disposition).isEqualTo("REUSE")

        import(into, json)
        assertThat(actions.findByWorkspaceIdAndName(into, "Post it")!!.connectionId)
            .isEqualTo(connections.findByWorkspaceIdAndName(into, "Slack")!!.id)
    }

    @Test
    fun `a binding this workspace cannot honour is refused rather than ignored`() {
        val connectionId = createConnection(from, "Slack", secret = "xoxb-never-exported")
        val actionId = createAction(from, "Post it", connectionId)
        val json = export(from, "ACTION", actionId, "DEEP")

        // An id from the workspace the file came from is not this workspace's to
        // point at, and is refused for the same reason one that never existed is.
        listOf(connectionId, 999999L).forEach { targetId ->
            graphQlTester.document(
                """
                mutation {
                  importComponents(workspaceId: $into, envelope: ${quote(json)},
                    bindings: [{ kind: CONNECTION, name: "Slack", targetId: $targetId }]) { importable }
                }
                """,
            ).execute().errors().satisfy { errors ->
                assertThat(errors.single().message)
                    .contains("no connection with id $targetId")
                    .contains("Nothing was imported")
            }
        }
        assertThat(actions.findByWorkspaceIdAndName(into, "Post it")).isNull()
    }

    @Test
    fun `a workflow carries its graph, and every node points at what came with it`() {
        val functionId = createFunction(from, "normalise")
        val actionId = createAction(from, "Normalise it", functionId = functionId)
        val conditionId = createCondition(from, "Urgent")
        val agentId = createAgent(from, "Reviewer")
        val workflowId = createWorkflow(from, "Triage")
        drawGraph(from, workflowId, agentId, conditionId, actionId)

        val json = export(from, "WORKFLOW", workflowId, "DEEP")
        assertThat(kinds(json)).containsExactly("FUNCTION", "CONDITION", "ACTION", "AGENT", "WORKFLOW")

        // Nothing outside itself: an agent with no model and an action that
        // calls a function reach only what the file already holds.
        val told = plan(into, json)
        assertThat(told.importable).isTrue()
        assertThat(told.entries.map { it.external }).containsOnlyNulls()
        // A workflow's name is unique within a workspace, not the installation,
        // so a copy landing in a workspace that has no workflow by that name
        // keeps it and is created rather than renamed. See issues #338, #341.
        assertThat(told.entries.single { it.kind == "WORKFLOW" }.disposition).isEqualTo("CREATE")
        assertThat(told.entries.single { it.kind == "WORKFLOW" }.targetName).isEqualTo("Triage")

        import(into, json)

        val here = assignments.findByWorkspaceIdAndWorkflowName(into, "Triage")!!.workflow
        assertThat(assignments.findByWorkspaceIdAndWorkflowId(into, here.id!!)).isNotNull()
        // A draft, whatever it was where it came from: publishing takes a copy
        // of the graph to run, and nobody has made one here.
        assertThat(here.status).isEqualTo(WorkflowStatus.DRAFT)

        val drawn = nodes.findByWorkflowId(here.id!!).associateBy { it.nodeKey }
        assertThat(drawn.keys).containsExactlyInAnyOrder("think", "ask", "do")
        assertThat(drawn.getValue("think").agentId).isEqualTo(agents.findByWorkspaceIdAndName(into, "Reviewer")!!.id)
        assertThat(drawn.getValue("ask").conditionId)
            .isEqualTo(conditions.findByWorkspaceIdAndName(into, "Urgent")!!.id)
        assertThat(drawn.getValue("do").actionId)
            .isEqualTo(actions.findByWorkspaceIdAndName(into, "Normalise it")!!.id)
        assertThat(drawn.getValue("think").outputName).isEqualTo("summary")
        assertThat(actions.findByWorkspaceIdAndName(into, "Normalise it")!!.functionId)
            .isEqualTo(functions.findByWorkspaceIdAndName(into, "normalise")!!.id)

        // The keys travelled unchanged, so the lines between them still land.
        assertThat(edges.findByWorkflowId(here.id!!).map { it.sourceKey to it.targetKey })
            .containsExactlyInAnyOrder("think" to "ask", "ask" to "do")
        assertThat(edges.findByWorkflowId(here.id!!).single { it.sourceKey == "ask" }.branch?.name).isEqualTo("YES")
    }

    @Test
    fun `the five kinds that already travelled produce the file they always did`() {
        val objectId = createObject(from, "Order")
        val functionId = createFunction(from, "normalise", returnObjectId = objectId)

        val json = export(from, "FUNCTION", functionId, "DEEP")
        val envelope = mapper.readTree(json)

        // Still version 1, so an installation a release behind still reads it.
        // A file holding one of the new kinds is refused there by name, which is
        // the same whole-file refusal a version it did not know would have got.
        assertThat(envelope.path("formatVersion").asInt()).isEqualTo(1)
        assertThat(kinds(json)).containsExactly("OBJECT", "FUNCTION")
        assertThat(json).doesNotContain("connectionRef").doesNotContain("modelRef")

        val told = plan(into, json)
        assertThat(told.entries.map { it.external }).containsOnlyNulls()
        import(into, json)
        assertThat(functions.findByWorkspaceIdAndName(into, "normalise")!!.returnObjectId)
            .isEqualTo(objects.findByWorkspaceIdAndName(into, "Order")!!.id)
    }

    // ----------------------------------------------------------------- helpers

    private data class Entry(
        val kind: String?,
        val external: String?,
        val name: String,
        val targetName: String,
        val disposition: String,
    )

    private data class Plan(val importable: Boolean, val entries: List<Entry>, val problems: List<String>)

    private fun kinds(json: String): List<String> =
        mapper.readTree(json).path("components").values().map { it.path("kind").stringValue() }

    /** One answer, as the plan named the question. */
    private fun bound(kind: String, name: String, targetId: Long): String =
        """{ kind: $kind, name: ${quote(name)}, targetId: $targetId }"""

    private fun bindings(vararg answers: String): String = answers.joinToString(", ", "[", "]")

    private fun export(workspaceId: Long, kind: String, id: Long, depth: String): String = graphQlTester.document(
        """query { exportComponent(workspaceId: $workspaceId, kind: $kind, id: $id, depth: $depth) { json } }""",
    ).execute().path("exportComponent.json").entity(String::class.java).get()

    private fun plan(workspaceId: Long, envelope: String, bindings: String = "[]"): Plan = read(
        graphQlTester.document(
            """
            query {
              componentImportPlan(workspaceId: $workspaceId, envelope: ${quote(envelope)}, bindings: $bindings) {
                importable problems entries { kind external name targetName disposition }
              }
            }
            """,
        ).execute().path("componentImportPlan").entity(Map::class.java).get().let(mapper::valueToTree),
    )

    private fun import(workspaceId: Long, envelope: String, bindings: String = "[]"): Plan = read(
        graphQlTester.document(
            """
            mutation {
              importComponents(workspaceId: $workspaceId, envelope: ${quote(envelope)}, bindings: $bindings) {
                importable problems entries { kind external name targetName disposition }
              }
            }
            """,
        ).execute().path("importComponents").entity(Map::class.java).get().let(mapper::valueToTree),
    )

    private fun read(node: JsonNode): Plan = Plan(
        importable = node.path("importable").asBoolean(false),
        entries = node.path("entries").values().map {
            Entry(
                kind = it.path("kind").takeIf { held -> held.isString }?.stringValue(),
                external = it.path("external").takeIf { held -> held.isString }?.stringValue(),
                name = it.path("name").stringValue(),
                targetName = it.path("targetName").stringValue(),
                disposition = it.path("disposition").stringValue(),
            )
        },
        problems = node.path("problems").values().map { it.stringValue() },
    )

    /** Anything for a GraphQL string literal, quotes and newlines and all. */
    private fun quote(text: String): String = mapper.writeValueAsString(text)

    private fun createObject(workspaceId: Long, name: String): Long = graphQlTester.document(
        """mutation { createObject(input: { workspaceId: $workspaceId, name: "$name",
             properties: [{ name: "reference", kind: STRING }] }) { id } }""",
    ).execute().path("createObject.id").entity(Long::class.java).get()

    private fun createFunction(workspaceId: Long, name: String, returnObjectId: Long? = null): Long {
        val body = "export default function () { return {}; }"
        return graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "$name",
                source: ${quote(body)}, typescript: ${quote(body)},
                returnType: ${if (returnObjectId != null) "OBJECT" else "MAP"}
                ${returnObjectId?.let { ", returnObjectId: $it" } ?: ""}
              }) { id }
            }
            """,
        ).execute().path("createFunction.id").entity(Long::class.java).get()
    }

    private fun createCondition(workspaceId: Long, name: String): Long = graphQlTester.document(
        """
        mutation {
          createCondition(input: {
            workspaceId: $workspaceId, name: "$name", type: SLACK,
            property: MESSAGE_TEXT, check: CONTAINS, values: [${quote("urgent")}]
          }) { id }
        }
        """,
    ).execute().path("createCondition.id").entity(Long::class.java).get()

    private fun createTool(workspaceId: Long, name: String): Long {
        // Takes the one default `input` the tool declares: code whose arity
        // disagrees with the declared parameters is refused at save now.
        val body = "export default function (input) { return {}; }"
        return graphQlTester.document(
            """
            mutation {
              createTool(input: {
                workspaceId: $workspaceId, name: "$name",
                source: ${quote(body)}, typescript: ${quote(body)}
              }) { id }
            }
            """,
        ).execute().path("createTool.id").entity(Long::class.java).get()
    }

    private fun createSkill(workspaceId: Long, name: String, catalog: String): Long {
        val catalogId = graphQlTester.document(
            """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "$catalog") { id } }""",
        ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()
        val content = "---\nname: $name\ndescription: What to do first.\n---\n\n# $name\n\nRead the ticket.\n"
        return graphQlTester.document(
            """
            mutation {
              createSkill(input: {
                workspaceId: $workspaceId, name: "$name", catalogId: $catalogId, content: ${quote(content)}
              }) { id }
            }
            """,
        ).execute().path("createSkill.id").entity(Long::class.java).get()
    }

    private fun createConnection(workspaceId: Long, name: String, secret: String): Long = graphQlTester.document(
        """
        mutation {
          createWorkspaceConnection(input: {
            workspaceId: $workspaceId, name: ${quote(name)}, type: SLACK,
            url: "https://slack.example", authType: BEARER_TOKEN, secret: ${quote(secret)}
          }) { id }
        }
        """,
    ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

    private fun createMcpServer(workspaceId: Long, name: String, secret: String): Long = graphQlTester.document(
        """
        mutation {
          createMcpServer(input: {
            workspaceId: $workspaceId, name: ${quote(name)}, address: "https://mcp.example",
            authType: BEARER_TOKEN, secret: ${quote(secret)}
          }) { id }
        }
        """,
    ).execute().path("createMcpServer.id").entity(Long::class.java).get()

    private fun createModel(workspaceId: Long, provider: String, model: String, key: String): Long {
        val providerId = graphQlTester.document(
            """
            mutation {
              createModelProvider(input: {
                workspaceId: $workspaceId, name: ${quote(provider)},
                endpoint: "https://models.example", secret: ${quote(key)}
              }) { id }
            }
            """,
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """
            mutation {
              createModel(input: {
                providerId: $providerId, name: ${quote(model)}, modelId: "stub", kind: CHAT
              }) { id }
            }
            """,
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun createAgent(
        workspaceId: Long,
        name: String,
        modelId: Long? = null,
        tools: List<String> = emptyList(),
        skillCatalogs: List<String> = emptyList(),
        memoryCatalogs: List<String> = emptyList(),
        mcpServers: List<String> = emptyList(),
    ): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: ${quote(name)}, type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val settings = buildString {
            modelId?.let { append(", modelId: $it") }
            append(", tools: [${tools.joinToString(", ") { quote(it) }}]")
            append(", skillCatalogs: [${skillCatalogs.joinToString(", ") { quote(it) }}]")
            append(", memoryCatalogs: [${memoryCatalogs.joinToString(", ") { quote(it) }}]")
            append(", mcpServers: [${mcpServers.joinToString(", ") { quote(it) }}]")
        }
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: ${quote(name)}$settings }) { id } }""",
        ).execute()
        return id
    }

    private fun createAction(
        workspaceId: Long,
        name: String,
        connectionId: Long? = null,
        functionId: Long? = null,
    ): Long {
        val settings = if (connectionId != null) {
            "subtype: OUTGOING_CONNECTION, connectionId: $connectionId, " +
                "connectionAction: SEND_MESSAGE, content: ${quote("Something happened")}"
        } else {
            "subtype: FUNCTION, functionId: $functionId"
        }
        return graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: ${quote(name)}, type: EXECUTE, $settings
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()
    }

    private fun createTrigger(workspaceId: Long, name: String, connectionId: Long): Long = graphQlTester.document(
        """
        mutation {
          createTrigger(input: {
            workspaceId: $workspaceId, name: ${quote(name)}, type: INCOMING_CONNECTION,
            connectionId: $connectionId, action: MENTION
          }) { id }
        }
        """,
    ).execute().path("createTrigger.id").entity(Long::class.java).get()

    private fun createWorkflow(workspaceId: Long, name: String): Long = graphQlTester.document(
        """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: ${quote(name)} }) { workflowId } }""",
    ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

    /** An agent that answers, a question about what it said, and something done about it. */
    private fun drawGraph(workspaceId: Long, workflowId: Long, agentId: Long, conditionId: Long, actionId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "think", kind: AGENT, name: "Reviewer", agentId: $agentId,
                    outputName: "summary", x: 0, y: 0 },
                  { key: "ask", kind: CONDITION, name: "Urgent?", conditionId: $conditionId, x: 200, y: 0 },
                  { key: "do", kind: ACTION, name: "Normalise", actionId: $actionId, x: 400, y: 0 }
                ],
                edges: [
                  { source: "think", target: "ask" },
                  { source: "ask", target: "do", branch: YES }
                ]
              }) { nodes { key } }
            }
            """,
        ).execute()
    }
}
