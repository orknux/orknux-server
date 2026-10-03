package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.AuthType
import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.HttpHeader
import io.mszymanski.orknux.connector.connection.MailSecurity
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.PluginCapability
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * `orknux.connections.query`, against real connections. Issue #597.
 *
 * Three things, and the first is the one the capability is allowed to exist on:
 *
 *   no secret     every connection kind that has a credential column is filled
 *                 with a value nobody would type, own copy and variable
 *                 reference alike, and none of them appears in the answer -
 *                 whose keys are exactly the allow-list, so a field added to a
 *                 connection later cannot ride out unnoticed
 *   one workspace a run in one workspace never sees another's, whatever it
 *                 asks for, through the real runners
 *   the filter    a built-in kind, a plugin's kind and a name, each ignoring case
 */
@SpringBootTest
class ConnectionsQueryTest(
    @Autowired val host: SlackPluginHost,
    @Autowired val scripts: ScriptRunner,
    @Autowired val plugins: PluginRunner,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var mine: Long = 0
    private var theirs: Long = 0

    /** Every credential below contains this, so one search finds any of them. */
    private val planted = "PLANTED${UUID.randomUUID().toString().replace("-", "").take(10)}"

    @BeforeEach
    fun seed() {
        workspaceConnections.deleteAll()
        mine = requireNotNull(workspaces.save(Workspace(name = "mine-${UUID.randomUUID()}")).id)
        theirs = requireNotNull(workspaces.save(Workspace(name = "theirs-${UUID.randomUUID()}")).id)
        val catalog = requireNotNull(catalogs.save(VariableCatalog(workspaceId = mine, name = "Keys")).id)

        // Slack holding all three of its tokens itself.
        workspaceConnections.save(
            WorkspaceConnection(
                workspaceId = mine, name = "Support Slack", type = ConnectionType.SLACK, url = "https://slack.com/api",
                authType = AuthType.BEARER_TOKEN,
                secret = "xoxb-$planted", appToken = "xapp-$planted", userToken = "xoxp-$planted",
            ),
        )
        // Slack reading all three from workspace secrets.
        workspaceConnections.save(
            WorkspaceConnection(
                workspaceId = mine, name = "Sales Slack", type = ConnectionType.SLACK, url = "https://slack.com/api",
                authType = AuthType.BEARER_TOKEN,
                secretVariableId = secret(catalog, "BOT"),
                appTokenVariableId = secret(catalog, "APP"),
                userTokenVariableId = secret(catalog, "USER"),
            ),
        )
        // A mail server with a password.
        workspaceConnections.save(
            WorkspaceConnection(
                workspaceId = mine, name = "Mail", type = ConnectionType.SMTP, url = "smtp.example.invalid",
                secret = "mail-$planted", smtpPort = 587, smtpUsername = "robot", smtpFrom = "robot@example.invalid",
                smtpSecurity = MailSecurity.STARTTLS,
            ),
        )
        // A plugin's kind of HTTP host: a credential, a header carrying one, and one written into the URL.
        workspaceConnections.save(
            WorkspaceConnection(
                workspaceId = mine, name = "Prod Prometheus", type = ConnectionType.HTTP,
                url = "https://scraper:$planted@prometheus.example.invalid/api",
                pluginType = "monitors/prometheus", authType = AuthType.BASIC, secret = "basic-$planted",
                headers = mutableListOf(HttpHeader("X-Api-Key", "header-$planted")),
            ),
        )
        // And a plain HTTP endpoint reading its key from a variable.
        workspaceConnections.save(
            WorkspaceConnection(
                workspaceId = mine, name = "Pager", type = ConnectionType.HTTP, url = "https://pager.example.invalid",
                authType = AuthType.API_KEY, secretVariableId = secret(catalog, "PAGER"),
            ),
        )
        workspaceConnections.save(
            WorkspaceConnection(
                workspaceId = theirs, name = "Their Slack", type = ConnectionType.SLACK, url = "https://slack.com/api",
                secret = "xoxb-theirs-$planted",
            ),
        )
    }

    private fun secret(catalog: Long, name: String): Long = requireNotNull(
        variables.save(
            WorkspaceVariable(
                workspaceId = mine, catalogId = catalog, name = "${name}_$planted",
                type = VariableType.STRING, kind = VariableKind.SECRET, value = "var-$planted",
            ),
        ).id,
    )

    private fun ask(filter: String, on: Long? = mine): JsonNode =
        mapper.readTree(host.ask(PluginCapability.CONNECTIONS_QUERY, filter, on))

    @Test
    fun `no credential of any kind is in the answer`() {
        val raw = host.ask(PluginCapability.CONNECTIONS_QUERY, "{}", mine)

        assertThat(raw).doesNotContain(planted)
        val listed = mapper.readTree(raw).get("connections").values().toList()
        assertThat(listed).hasSize(5)

        listed.forEach { one ->
            // Exactly the allow-list, so a field somebody adds stays out until somebody decides.
            assertThat(one.propertyNames().toSet())
                .isSubsetOf(ConnectionsPluginHost.CROSSES)
                .containsAll(ConnectionsPluginHost.CROSSES - "smtp")
            one.get("smtp")?.let { assertThat(it.propertyNames().toSet()).isEqualTo(ConnectionsPluginHost.SMTP_CROSSES) }
        }
        // No key that so much as names a credential, not even the id of the variable holding one.
        assertThat(keysOf(mapper.readTree(raw)).map { it.lowercase() })
            .noneMatch { key -> listOf("secret", "token", "password", "variable", "passphrase", "key").any { it in key } }
    }

    private fun keysOf(node: JsonNode): List<String> = when {
        node.isObject -> node.propertyNames().flatMap { listOf(it) + keysOf(node.get(it)) }
        node.isArray -> node.values().flatMap { keysOf(it) }
        else -> emptyList()
    }

    @Test
    fun `what does cross is what a script needs to tell them apart`() {
        val prometheus = ask("""{"name":"prod prometheus"}""").get("connections").single()

        assertThat(prometheus.get("type").asString()).isEqualTo("HTTP")
        assertThat(prometheus.get("pluginType").asString()).isEqualTo("monitors/prometheus")
        assertThat(prometheus.get("url").asString()).isEqualTo("https://prometheus.example.invalid/api")
        assertThat(prometheus.get("authType").asString()).isEqualTo("BASIC")
        assertThat(prometheus.get("headers").values().map { it.asString() }).containsExactly("X-Api-Key")

        val mail = ask("""{"type":"smtp"}""").get("connections").single()
        assertThat(mail.get("smtp").get("username").asString()).isEqualTo("robot")
        assertThat(mail.get("smtp").get("port").asInt()).isEqualTo(587)
    }

    @Test
    fun `a filter takes a built-in kind, a plugin's kind and a name, ignoring case`() {
        fun names(filter: String) = ask(filter).get("connections").values().map { it.get("name").asString() }

        assertThat(names("""{"type":"slack"}""")).containsExactlyInAnyOrder("Support Slack", "Sales Slack")
        assertThat(names("""{"type":"MONITORS/PROMETHEUS"}""")).containsExactly("Prod Prometheus")
        assertThat(names("""{"type":"prometheus"}""")).containsExactly("Prod Prometheus")
        assertThat(names("""{"name":"PAGER"}""")).containsExactly("Pager")
        // Whole names only: a prefix is not a match.
        assertThat(names("""{"name":"Pag"}""")).isEmpty()
        assertThat(names("""{"type":"slack","name":"sales slack"}""")).containsExactly("Sales Slack")
    }

    @Test
    fun `a run never sees another workspace's connections`() {
        assertThat(ask("""{"name":"Their Slack"}""").get("connections").values().toList()).isEmpty()
        assertThat(ask("{}", on = theirs).get("connections").values().map { it.get("name").asString() })
            .containsExactly("Their Slack")
        // A call that belongs to no workspace is refused, not answered with everybody's.
        assertThat(ask("{}", on = null).get("error").asString()).contains("no workspace")
    }

    /** The same answer from a workspace's function as from a plugin granted it - the real runners, both ways. */
    @Test
    fun `both sandboxes answer the same, scoped to the run`() {
        val function = """
            export default function find() {
              return orknux.connections.query({ type: 'SLACK' }).connections.map((c) => c.name).sort();
            }
        """.trimIndent()
        val plugin = """
            export default class Finder extends OrknuxPlugin {
              id() { return 'finder'; }
              apiVersion() { return 1; }
              capabilities() { return ['CONNECTIONS_QUERY']; }
              functions() {
                return [new OrknuxFunction({
                  name: 'find', params: [], returnType: 'array',
                  run: () => orknux.connections.query({ type: 'SLACK' }).connections.map((c) => c.name).sort(),
                })];
              }
            }
        """.trimIndent()

        val fromFunction = scripts.call(function, "find", emptyList(), on = mine)
        val fromPlugin = plugins.call(
            plugin, "find", emptyList(), capabilities = setOf(PluginCapability.CONNECTIONS_QUERY), on = mine,
        )
        val ungranted = plugins.call(plugin, "find", emptyList(), capabilities = emptySet(), on = mine)

        assertThat((fromFunction as ScriptResult.Returned).json).isEqualTo("""["Sales Slack","Support Slack"]""")
        assertThat((fromPlugin as ScriptResult.Returned).json).isEqualTo(fromFunction.json)
        // Not granted: the helper answers its refusal, so `.connections` is undefined and the call fails.
        assertThat(ungranted).isInstanceOf(ScriptResult.Failed::class.java)
    }
}
