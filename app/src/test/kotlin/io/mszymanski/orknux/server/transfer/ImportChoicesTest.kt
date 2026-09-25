package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.plugin.PluginRepository
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
) {

    private var from: Long = 0
    private var into: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        tools.deleteAll()
        catalogs.deleteAll()
        plugins.deleteAll()
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
        val plan = plan(into, json)

        assertThat(plan.importable).describedAs(plan.problems.joinToString()).isTrue()
        val row = plan.entries.single { it.kind == "TOOL" && it.name == "greeter_shout" }
        assertThat(row.disposition).isEqualTo("REUSE")
        assertThat(row.droppable).isTrue()

        import(into, json)
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
        val name: String,
        val targetName: String,
        val disposition: String,
        val droppable: Boolean,
    )

    private data class Plan(val importable: Boolean, val entries: List<Entry>, val problems: List<String>)

    private fun read(node: JsonNode): Plan = Plan(
        importable = node.path("importable").asBoolean(false),
        entries = node.path("entries").values().map {
            Entry(
                kind = it.path("kind").takeIf { held -> held.isString }?.stringValue(),
                name = it.path("name").stringValue(),
                targetName = it.path("targetName").stringValue(),
                disposition = it.path("disposition").stringValue(),
                droppable = it.path("droppable").asBoolean(false),
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
                 importable problems entries { kind name targetName disposition droppable } } }""",
        ).execute().path("componentImportPlan").entity(Map::class.java).get().let(mapper::valueToTree),
    )

    private fun import(workspaceId: Long, envelope: String, exclude: String = "[]", rename: String = "[]"): Plan = read(
        graphQlTester.document(
            """mutation { importComponents(workspaceId: $workspaceId, envelope: ${quote(envelope)},
                 exclude: $exclude, rename: $rename) {
                 importable problems entries { kind name targetName disposition droppable } } }""",
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
    ): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: ${quote(name)}, type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: ${quote(name)},
                 tools: [${tools.joinToString(", ") { quote(it) }}],
                 skillCatalogs: [${skillCatalogs.joinToString(", ") { quote(it) }}] }) { id } }""",
        ).execute()
        return id
    }

    private companion object {
        val PLUGIN = """
            export default class Greeter extends OrknuxPlugin {
              id() { return 'greeter'; }
              apiVersion() { return 1; }
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
