package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser

/**
 * A plugin declaring the kinds of host it talks to. Issue #363.
 *
 * A workspace could always hold several HTTP connections, but every one of them
 * read as "HTTP": a Prometheus server and a wiki were indistinguishable, and a
 * plugin's picker offered both. A plugin now names its kinds; a connection
 * wears one as a label over the generic HTTP shape, a plugin's parameter may
 * narrow to it, and a kind nothing declares is refused rather than stored.
 *
 * Makes its own workspace; loads its own plugin.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginConnectionTypesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val kinds: PluginConnectionTypes,
    @Autowired val parameters: PluginParameters,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceConnections.deleteAll()
        plugins.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /** A plugin that talks to Prometheus servers, and says so. */
    private val source = """
        export default class Monitors extends OrknuxPlugin {
          id() { return 'monitors'; }
          apiVersion() { return 1; }
          connectionTypes() {
            return [
              {
                name: 'prometheus',
                label: 'Prometheus',
                description: 'A Prometheus server to query.',
                urlPlaceholder: 'https://prometheus.example.com',
              },
            ];
          }
          parameters() {
            return [
              new OrknuxParameter({
                name: 'server',
                description: 'Which Prometheus to ask.',
                type: 'connection',
                connectionType: 'prometheus',
              }),
            ];
          }
        }
    """.trimIndent()

    private fun load(text: String = source, name: String = "monitors.js") =
        upload.upload(MockMultipartFile("file", name, "text/javascript", text.toByteArray()), null, null)

    /* ------------------------------------------------- what is declared ---- */

    @Test
    fun `a plugin's connection kinds are read from the code and kept`() {
        load()

        val stored = plugins.findByKey("monitors")!!
        val held = declarations.readConnectionTypes(stored.declaredConnectionTypes, stored.key, stored.name)
        assertThat(held).hasSize(1)
        // Keyed by the plugin, so two plugins declaring "server" cannot collide.
        assertThat(held.single().id).isEqualTo("monitors/prometheus")
        assertThat(held.single().label).isEqualTo("Prometheus")
        assertThat(held.single().urlPlaceholder).isEqualTo("https://prometheus.example.com")

        assertThat(kinds.all().map { it.id }).containsExactly("monitors/prometheus")
        assertThat(kinds.known("monitors/prometheus")).isTrue()
        assertThat(kinds.known("monitors/grafana")).isFalse()
    }

    @Test
    fun `a connection parameter may name the plugin's own kind`() {
        load()

        // Accepted at load beside the core kinds; the picker narrows by it.
        val parameter = declarations.readParameters(plugins.findByKey("monitors")!!.declaredParameters).single()
        assertThat(parameter.connectionType).isEqualTo("prometheus")
    }

    @Test
    fun `the kinds are offered to the connection form`() {
        load()

        graphQlTester.document("query { pluginConnectionTypes { id label pluginName urlPlaceholder } }")
            .execute()
            .path("pluginConnectionTypes[0].id").entity(String::class.java).isEqualTo("monitors/prometheus")
            .path("pluginConnectionTypes[0].label").entity(String::class.java).isEqualTo("Prometheus")
            .path("pluginConnectionTypes[0].urlPlaceholder").entity(String::class.java)
            .isEqualTo("https://prometheus.example.com")
    }

    /* ------------------------------------------------- what the plugin is handed */

    private fun connect(name: String, pluginType: String?, auth: String = "BEARER_TOKEN", secret: String = "tok"): Long =
        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "$name", type: HTTP,
                 ${pluginType?.let { "pluginType: \"$it\"," } ?: ""}
                 url: "https://prom.example.com", authType: $auth, secret: "$secret",
                 headers: [{ name: "X-Scope-OrgID", value: "tenant-1" }]
               }) { id } }""",
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

    @Test
    fun `a host of the plugin's own kind crosses with its address and credential`() {
        load()
        val plugin = plugins.findByKey("monitors")!!
        val id = connect("Prod Prometheus", "monitors/prometheus")
        parameters.set(plugin, workspaceId, "server", id.toString(), null, "alice")

        val handed = mapper.readTree(parameters.settingsFor(plugin, workspaceId)).get("server")
        assertThat(handed.get("id").asLong()).isEqualTo(id)
        assertThat(handed.get("pluginType").asString()).isEqualTo("monitors/prometheus")
        assertThat(handed.get("url").asString()).isEqualTo("https://prom.example.com")
        assertThat(handed.get("authType").asString()).isEqualTo("BEARER_TOKEN")
        assertThat(handed.get("secret").asString()).isEqualTo("tok")
        // Everything to send, so a plugin need not know how each auth kind is spelled.
        assertThat(handed.get("headers").get("Authorization").asString()).isEqualTo("Bearer tok")
        assertThat(handed.get("headers").get("X-Scope-OrgID").asString()).isEqualTo("tenant-1")
    }

    @Test
    fun `a connection the plugin did not define stays a handle`() {
        load(
            source.replace(
                "connectionType: 'prometheus',",
                "connectionType: 'HTTP',",
            ),
        )
        val plugin = plugins.findByKey("monitors")!!
        // A plain HTTP endpoint: the server speaks to it, so the plugin gets the name and no more.
        val plain = connect("Wiki", null)
        parameters.set(plugin, workspaceId, "server", plain.toString(), null, "alice")

        val handed = mapper.readTree(parameters.settingsFor(plugin, workspaceId)).get("server")
        assertThat(handed.get("id").asLong()).isEqualTo(plain)
        assertThat(handed.has("url")).isFalse()
        assertThat(handed.has("secret")).isFalse()
        assertThat(handed.has("headers")).isFalse()
    }

    /* ------------------------------------------------- what a connection wears */

    @Test
    fun `a connection can wear a plugin's kind, and takes it off again`() {
        load()

        val id = graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Prod Prometheus", type: HTTP,
                 pluginType: "monitors/prometheus", url: "https://prom.example.com",
                 authType: BEARER_TOKEN, secret: "tok"
               }) { id pluginType type } }""",
        ).execute()
            .path("createWorkspaceConnection.pluginType").entity(String::class.java).isEqualTo("monitors/prometheus")
            .path("createWorkspaceConnection.type").entity(String::class.java).isEqualTo("HTTP")
            .path("createWorkspaceConnection.id").entity(Long::class.java).get()

        assertThat(requireNotNull(workspaceConnections.findById(id).orElse(null)).pluginType)
            .isEqualTo("monitors/prometheus")

        // An empty string clears it; null would have left it alone.
        graphQlTester.document(
            """mutation { updateWorkspaceConnection(id: $id, input: { pluginType: "" }) { pluginType } }""",
        ).execute().path("updateWorkspaceConnection.pluginType").valueIsNull()
    }

    @Test
    fun `a plugin's kind sits only on an HTTP connection`() {
        load()

        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Mail", type: SMTP,
                 pluginType: "monitors/prometheus", url: "smtp.example.com"
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("HTTP")
        }
    }

    @Test
    fun `a kind no loaded plugin declares is refused, not stored`() {
        load()

        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Mystery", type: HTTP,
                 pluginType: "nobody/knows", url: "https://x.example.com"
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("nobody/knows")
        }
        assertThat(workspaceConnections.findAll()).isEmpty()
    }

    @Test
    fun `a plugin that says nothing about hosts declares no kinds`() {
        load(
            """
            export default class Quiet extends OrknuxPlugin {
              id() { return 'quiet'; }
              apiVersion() { return 1; }
            }
            """.trimIndent(),
            "quiet.js",
        )

        val stored = plugins.findByKey("quiet")!!
        assertThat(declarations.readConnectionTypes(stored.declaredConnectionTypes, stored.key, stored.name)).isEmpty()
        assertThat(kinds.all()).isEmpty()
    }
}
