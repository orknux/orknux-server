package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.EntityLoads
import io.mszymanski.orknux.server.plugin.Plugin
import io.mszymanski.orknux.server.plugin.PluginRepository
import jakarta.persistence.EntityManagerFactory
import io.mszymanski.orknux.server.plugin.PluginUploadAPI
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * What the person importing may decide about an agent on the way in. Issue
 * #383.
 *
 * Three things, from one report - copying an agent from one workspace to
 * another was refused over tools a plugin brought:
 *
 * - A tool a plugin brings is not a workspace tool. Every workspace of the
 *   installation has it, so a grant naming one is neither carried nor
 *   missing, and the import goes ahead with the grant intact.
 * - A tool an agent points at can be left out, and the agent arrives without
 *   it - the one kind of reference that can go, because a grant list is a list.
 * - An MCP server an agent points at can be left out the same way, and the
 *   agent arrives without that grant. No file carries one, so without this a
 *   server that exists nowhere here could only be got past by making one.
 *   Issue #580.
 * - A carried component can be given a name of the person's choosing, and
 *   everything in the file that pointed at it follows; a name that is taken is
 *   refused on the row rather than moved along, because somebody typed it.
 *
 * Makes two workspaces, a plugin, tools and agents, and removes them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ImportChoicesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val mcpServers: McpServerRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val factory: EntityManagerFactory,
) {

    private var from: Long = 0
    private var into: Long = 0

    @BeforeEach
    fun reset() {
        actions.deleteAll()
        agents.deleteAll()
        tools.deleteAll()
        catalogs.deleteAll()
        plugins.deleteAll()
        mcpServers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        from = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        into = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
    }

    /* ---------------------------------------------------- plugin tools ---- */

    @Test
    fun `a tool a plugin brings is neither carried nor missing, and the grant arrives intact`() {
        loadPlugin()
        val agentId = createAgent(from, "Triage bot", tools = listOf("greeter_shout"))

        val json = export(from, "AGENT", agentId, "DEEP")
        // Knowing a name is a plugin's needs its declarations, not its bundle. Issue #616.
        val loads = EntityLoads(factory)
        val planned = loads.of(Plugin::class) { plan(into, json) }
        val plan = planned.answer
        assertThat(planned.loaded).describedAs("plugins read to plan the import").isZero()

        assertThat(plan.importable).describedAs(plan.problems.joinToString()).isTrue()
        val row = plan.entries.single { it.kind == "TOOL" && it.name == "greeter_shout" }
        assertThat(row.disposition).isEqualTo("REUSE")
        assertThat(row.droppable).isTrue()

        assertThat(loads.of(Plugin::class) { import(into, json) }.loaded)
            .describedAs("plugins read to import").isZero()
        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot")!!.tools).containsExactly("greeter_shout")
        assertThat(tools.findByWorkspaceIdAndName(into, "greeter_shout")).describedAs("no workspace tool made for it").isNull()
    }

    @Test
    fun `a plugin's skill catalog is a grant, not a folder to make here`() {
        loadPlugin()
        val agentId = createAgent(from, "Triage bot", skillCatalogs = listOf("greeter_plugin"))

        import(into, export(from, "AGENT", agentId, "DEEP"))

        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot")!!.skillCatalogs).containsExactly("greeter_plugin")
        assertThat(catalogs.findByWorkspaceIdAndName(into, "greeter_plugin")).isNull()
    }

    /**
     * Issue #3: an action calling a plugin's function was refused on import as
     * calling a function "this file does not carry and this workspace does not
     * have", though the plugin was installed. A plugin's function is in every
     * workspace, so it is reused by name, as a plugin's tool is.
     */
    @Test
    fun `an action calling a plugin's function reuses it where the plugin is installed`() {
        loadPlugin()
        val function = requireNotNull(functions.findByScopeAndName(FunctionScope.PLUGIN, "greeter_greet"))
        val actionId = graphQlTester.document(
            """mutation { createAction(input: { workspaceId: $from, name: "Greet them", type: EXECUTE,
                 subtype: FUNCTION, functionId: ${function.id} }) { id } }""",
        ).execute().path("createAction.id").entity(Long::class.java).get()
        val json = export(from, "ACTION", actionId, "DEEP")

        val plan = plan(into, json)

        assertThat(plan.importable).describedAs(plan.problems.joinToString()).isTrue()
        val row = plan.entries.single { it.kind == "FUNCTION" && it.name == "greeter_greet" }
        assertThat(row.disposition).isEqualTo("REUSE")
        assertThat(row.detail).contains("plugin")

        import(into, json)
        val arrived = actions.findAllByWorkspaceIdAndName(into, "Greet them").single()
        assertThat(arrived.functionId).isEqualTo(function.id)
        assertThat(functions.findByWorkspaceIdAndName(into, "greeter_greet")).describedAs("no copy made").isNull()
    }

    /**
     * Issue #3: leaving out a tool the file carries left out every agent that
     * held it, silently. The agent now arrives without that grant, and the
     * tool's row says which agents that is.
     */
    @Test
    fun `leaving out a carried tool lands its agent without the grant rather than leaving it out`() {
        createTool(from, "lookup")
        val agentId = createAgent(from, "Triage bot", tools = listOf("lookup"))
        val json = export(from, "AGENT", agentId, "DEEP")

        val planned = plan(into, json, exclude = """[{ kind: TOOL, name: "lookup" }]""")

        assertThat(planned.importable).describedAs(planned.problems.joinToString()).isTrue()
        val tool = planned.entries.single { it.kind == "TOOL" && it.name == "lookup" }
        assertThat(tool.disposition).isEqualTo("EXCLUDE")
        assertThat(tool.detail).contains("Triage bot arrives without it")
        assertThat(planned.entries.single { it.kind == "AGENT" }.disposition).isEqualTo("CREATE")

        import(into, json, exclude = """[{ kind: TOOL, name: "lookup" }]""")
        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot")!!.tools).isEmpty()
        assertThat(tools.findByWorkspaceIdAndName(into, "lookup")).isNull()
    }

    /** And where this workspace has a tool of that name, the agent points at it, as before. */
    @Test
    fun `leaving out a carried tool this workspace has points the agent at the one here`() {
        createTool(from, "lookup")
        val here = createTool(into, "lookup")
        val agentId = createAgent(from, "Triage bot", tools = listOf("lookup"))

        import(into, export(from, "AGENT", agentId, "DEEP"), exclude = """[{ kind: TOOL, name: "lookup" }]""")

        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot")!!.tools).containsExactly("lookup")
        assertThat(tools.findByWorkspaceIdAndName(into, "lookup")!!.id).isEqualTo(here)
    }

    /* ----------------------------------------------- leaving a tool out ---- */

    @Test
    fun `a tool an agent points at can be left out, and the agent arrives without it`() {
        createTool(from, "lookup")
        val agentId = createAgent(from, "Triage bot", tools = listOf("lookup"))
        // Shallow: the tool is pointed at and not carried, and the target has none.
        val json = export(from, "AGENT", agentId, "SHALLOW")

        val refused = plan(into, json)
        assertThat(refused.importable).isFalse()
        val missing = refused.entries.single { it.kind == "TOOL" && it.name == "lookup" }
        assertThat(missing.disposition).isEqualTo("MISSING")
        assertThat(missing.droppable).describedAs("the row may be left out").isTrue()

        val planned = plan(into, json, exclude = """[{ kind: TOOL, name: "lookup" }]""")
        assertThat(planned.importable).describedAs(planned.problems.joinToString()).isTrue()
        assertThat(planned.entries.single { it.kind == "TOOL" && it.name == "lookup" }.disposition).isEqualTo("EXCLUDE")

        import(into, json, exclude = """[{ kind: TOOL, name: "lookup" }]""")
        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot")!!.tools).isEmpty()
    }

    @Test
    fun `what the file points at and an agent cannot do without still cannot be left out`() {
        createTool(from, "lookup")
        val agentId = createAgent(from, "Triage bot", tools = listOf("lookup"))
        val json = export(from, "AGENT", agentId, "SHALLOW")

        graphQlTester.document(
            """query { componentImportPlan(workspaceId: $into, envelope: ${quote(json)},
                 exclude: [{ kind: FUNCTION, name: "lookup" }]) { importable } }""",
        ).execute().errors().expect { it.message!!.contains("none to leave out") }.verify()
    }

    /* ----------------------------------------- leaving an MCP server out --- */

    @Test
    fun `an MCP server an agent points at can be left out, and the agent arrives without it`() {
        createMcpServer(from, "jira")
        createMcpServer(from, "order mcp")
        createMcpServer(into, "jira")
        val agentId = createAgent(from, "Triage bot", mcpServers = listOf("jira", "order mcp"))
        val json = export(from, "AGENT", agentId, "DEEP")

        val refused = plan(into, json)
        assertThat(refused.importable).isFalse()
        val missing = refused.entries.single { it.external == "MCP_SERVER" && it.name == "order mcp" }
        assertThat(missing.disposition).isEqualTo("MISSING")
        assertThat(missing.droppable).describedAs("the row may be left out").isTrue()
        assertThat(refused.entries.single { it.external == "MCP_SERVER" && it.name == "jira" }.droppable).isTrue()

        val leaveOut = """[{ external: MCP_SERVER, name: "order mcp" }]"""
        val planned = plan(into, json, exclude = leaveOut)
        assertThat(planned.importable).describedAs(planned.problems.joinToString()).isTrue()
        val row = planned.entries.single { it.external == "MCP_SERVER" && it.name == "order mcp" }
        assertThat(row.disposition).isEqualTo("EXCLUDE")
        assertThat(row.detail).isEqualTo("Left out: Triage bot arrives without it.")

        import(into, json, exclude = leaveOut)
        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot")!!.mcpServers).containsExactly("jira")
    }

    @Test
    fun `an MCP server left out is left out even where this workspace has one by that name`() {
        createMcpServer(from, "jira")
        createMcpServer(into, "jira")
        val agentId = createAgent(from, "Triage bot", mcpServers = listOf("jira"))

        import(into, export(from, "AGENT", agentId, "DEEP"), exclude = """[{ external: MCP_SERVER, name: "jira" }]""")

        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot")!!.mcpServers).isEmpty()
    }

    @Test
    fun `an MCP server no agent in the file points at cannot be left out, nor a model`() {
        createMcpServer(from, "jira")
        val agentId = createAgent(from, "Triage bot", mcpServers = listOf("jira"))
        val json = export(from, "AGENT", agentId, "DEEP")

        graphQlTester.document(
            """query { componentImportPlan(workspaceId: $into, envelope: ${quote(json)},
                 exclude: [{ external: MCP_SERVER, name: "ghost" }]) { importable } }""",
        ).execute().errors().expect { it.message!!.contains("no mcp server called ghost") }.verify()
        graphQlTester.document(
            """query { componentImportPlan(workspaceId: $into, envelope: ${quote(json)},
                 exclude: [{ external: MODEL, name: "jira" }]) { importable } }""",
        ).execute().errors().expect { it.message!!.contains("none to leave out") }.verify()
    }

    /* ---------------------------------------------------------- renames --- */

    @Test
    fun `a carried component can be given a name on the way in, and what pointed at it follows`() {
        createTool(from, "lookup")
        val agentId = createAgent(from, "Triage bot", tools = listOf("lookup"))
        val json = export(from, "AGENT", agentId, "DEEP")

        val planned = plan(into, json, rename = """[{ kind: TOOL, name: "lookup", targetName: "finder" }]""")
        assertThat(planned.importable).describedAs(planned.problems.joinToString()).isTrue()
        val row = planned.entries.single { it.kind == "TOOL" && it.name == "lookup" }
        assertThat(row.disposition).isEqualTo("RENAME")
        assertThat(row.targetName).isEqualTo("finder")

        import(into, json, rename = """[{ kind: TOOL, name: "lookup", targetName: "finder" }]""")
        assertThat(tools.findByWorkspaceIdAndName(into, "finder")).isNotNull()
        assertThat(tools.findByWorkspaceIdAndName(into, "lookup")).isNull()
        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot")!!.tools).containsExactly("finder")
    }

    @Test
    fun `and the agent itself, whose name is not an identifier`() {
        val agentId = createAgent(from, "Triage bot")
        val json = export(from, "AGENT", agentId, "DEEP")

        import(into, json, rename = """[{ kind: AGENT, name: "Triage bot", targetName: "Triage bot (frontend)" }]""")

        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot (frontend)")).isNotNull()
        assertThat(agents.findByWorkspaceIdAndName(into, "Triage bot")).isNull()
    }

    @Test
    fun `a chosen name that is taken is refused on the row, not moved along`() {
        createTool(from, "lookup")
        val agentId = createAgent(from, "Triage bot", tools = listOf("lookup"))
        createTool(into, "finder")
        val json = export(from, "AGENT", agentId, "DEEP")

        val planned = plan(into, json, rename = """[{ kind: TOOL, name: "lookup", targetName: "finder" }]""")

        assertThat(planned.importable).isFalse()
        assertThat(planned.problems).anyMatch { it.contains("already has a tool called finder") }
        assertThat(planned.entries.single { it.kind == "TOOL" && it.name == "lookup" }.targetName).isEqualTo("finder")
    }

    @Test
    fun `a chosen name that is not a name the kind can have is refused the same way`() {
        createTool(from, "lookup")
        val agentId = createAgent(from, "Triage bot", tools = listOf("lookup"))
        val json = export(from, "AGENT", agentId, "DEEP")

        val planned = plan(into, json, rename = """[{ kind: TOOL, name: "lookup", targetName: "not a tool name" }]""")

        assertThat(planned.importable).isFalse()
        assertThat(planned.problems).anyMatch { it.contains("is not a name a tool can have here") }
    }

    @Test
    fun `renaming something the file does not carry is refused outright`() {
        val agentId = createAgent(from, "Triage bot")
        val json = export(from, "AGENT", agentId, "DEEP")

        graphQlTester.document(
            """query { componentImportPlan(workspaceId: $into, envelope: ${quote(json)},
                 rename: [{ kind: TOOL, name: "ghost", targetName: "finder" }]) { importable } }""",
        ).execute().errors().expect { it.message!!.contains("none to rename") }.verify()
    }

    /* ----------------------------------------------------------- fixture --- */

    private fun loadPlugin() {
        upload.upload(MockMultipartFile("file", "greeter.js", "text/javascript", PLUGIN.toByteArray()), null, null)
        assertThat(plugins.findByKey("greeter")).isNotNull()
    }

    private data class Entry(
        val kind: String?,
        val external: String?,
        val name: String,
        val targetName: String,
        val disposition: String,
        val droppable: Boolean,
        val detail: String,
    )

    private data class Plan(val importable: Boolean, val entries: List<Entry>, val problems: List<String>)

    private fun read(node: JsonNode): Plan = Plan(
        importable = node.path("importable").asBoolean(false),
        entries = node.path("entries").values().map {
            Entry(
                kind = it.path("kind").takeIf { held -> held.isString }?.stringValue(),
                external = it.path("external").takeIf { held -> held.isString }?.stringValue(),
                name = it.path("name").stringValue(),
                targetName = it.path("targetName").stringValue(),
                disposition = it.path("disposition").stringValue(),
                droppable = it.path("droppable").asBoolean(false),
                detail = it.path("detail").stringValue(),
            )
        },
        problems = node.path("problems").values().map { it.stringValue() },
    )

    private fun export(workspaceId: Long, kind: String, id: Long, depth: String): String = graphQlTester.document(
        """query { exportComponent(workspaceId: $workspaceId, kind: $kind, id: $id, depth: $depth) { json } }""",
    ).execute().path("exportComponent.json").entity(String::class.java).get()

    private fun plan(workspaceId: Long, envelope: String, exclude: String = "[]", rename: String = "[]"): Plan = read(
        graphQlTester.document(
            """query { componentImportPlan(workspaceId: $workspaceId, envelope: ${quote(envelope)},
                 exclude: $exclude, rename: $rename) {
                 importable problems entries { kind external name targetName disposition droppable detail } } }""",
        ).execute().path("componentImportPlan").entity(Map::class.java).get().let(mapper::valueToTree),
    )

    private fun import(workspaceId: Long, envelope: String, exclude: String = "[]", rename: String = "[]"): Plan = read(
        graphQlTester.document(
            """mutation { importComponents(workspaceId: $workspaceId, envelope: ${quote(envelope)},
                 exclude: $exclude, rename: $rename) {
                 importable problems entries { kind external name targetName disposition droppable detail } } }""",
        ).execute().path("importComponents").entity(Map::class.java).get().let(mapper::valueToTree),
    )

    private fun quote(text: String): String = mapper.writeValueAsString(text)

    private fun createTool(workspaceId: Long, name: String): Long {
        val body = "export default function (input) { return {}; }"
        return graphQlTester.document(
            """mutation { createTool(input: { workspaceId: $workspaceId, name: "$name",
                 source: ${quote(body)}, typescript: ${quote(body)} }) { id } }""",
        ).execute().path("createTool.id").entity(Long::class.java).get()
    }

    private fun createAgent(
        workspaceId: Long,
        name: String,
        tools: List<String> = emptyList(),
        skillCatalogs: List<String> = emptyList(),
        mcpServers: List<String> = emptyList(),
    ): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: ${quote(name)}, type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: ${quote(name)},
                 tools: [${tools.joinToString(", ") { quote(it) }}],
                 skillCatalogs: [${skillCatalogs.joinToString(", ") { quote(it) }}],
                 mcpServers: [${mcpServers.joinToString(", ") { quote(it) }}] }) { id } }""",
        ).execute()
        return id
    }

    private fun createMcpServer(workspaceId: Long, name: String): Long = graphQlTester.document(
        """mutation { createMcpServer(input: { workspaceId: $workspaceId, name: ${quote(name)},
             address: "https://mcp.example", authType: BEARER_TOKEN, secret: "token" }) { id } }""",
    ).execute().path("createMcpServer.id").entity(Long::class.java).get()

    private companion object {
        val PLUGIN = """
            export default class Greeter extends OrknuxPlugin {
              id() { return 'greeter'; }
              apiVersion() { return 1; }
              functions() {
                return [
                  new OrknuxFunction({
                    name: 'greet',
                    params: [{ name: 'name', type: 'string' }],
                    returnType: 'string',
                    run: (name) => 'hello, ' + name,
                  }),
                ];
              }
              tools() {
                return [
                  new OrknuxTool({
                    name: 'shout',
                    description: 'Says it loudly, to whoever is named.',
                    params: [{ name: 'name', type: 'string' }],
                    returnType: 'string',
                    run: (name) => 'HELLO, ' + name.toUpperCase() + '!',
                  }),
                ];
              }
            }
        """.trimIndent()
    }
}
