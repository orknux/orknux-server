package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.PluginInspection
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A workspace answering what a plugin asked for.
 *
 * The plugin is built here from its own source rather than from a hand-written
 * declaration: what a workspace can set is exactly what the plugin said it
 * needed, and a test that wrote the declaration itself would still pass on the
 * day the two stopped agreeing.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginParameterTest(
    @Autowired val pluginSources: PluginSources,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val plugins: PluginRepository,
    @Autowired val settings: PluginParameterSettingRepository,
    @Autowired val parameters: PluginParameters,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val runner: PluginRunner,
    @Autowired val variables: WorkspaceVariableRepository,
    /** For asking the database what it actually holds, rather than the entity. */
    @Autowired val jdbc: org.springframework.jdbc.core.JdbcTemplate,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0
    private var pluginId: Long = 0

    @BeforeEach
    fun reset() {
        settings.deleteAll()
        plugins.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        pluginId = requireNotNull(load().id)
    }

    @Test
    fun `a plugin says what it has to be told, and the workspace is shown all of it`() {
        graphQlTester.document(
            """
            query {
              workspacePlugins(workspaceId: $workspaceId) {
                missing
                parameters { name type required secret literal variableName missing }
              }
            }
            """,
        ).execute()
            .path("workspacePlugins[0].parameters[*].name").entityList(String::class.java)
            .containsExactly("baseUrl", "token", "retries")
            .path("workspacePlugins[0].parameters[1].secret").entity(Boolean::class.java).isEqualTo(true)
            .path("workspacePlugins[0].parameters[2].required").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `a parameter set to a literal reaches the plugin`() {
        set("baseUrl", literal = "https://tracker.example")

        val plugin = requireNotNull(plugins.findById(pluginId).orElse(null))
        assertThat(parameters.settingsFor(plugin, workspaceId))
            .isEqualTo("""{"baseUrl":"https://tracker.example"}""")

        // Through the sandbox, not only through the resolver: what a workspace
        // typed has to be readable as `this.settings` inside the plugin.
        val answered = runner.call(
            pluginSources.sourceOf(plugin),
            "addressOf",
            listOf(""""ORK-14""""),
            parameters.settingsFor(plugin, workspaceId),
        )
        assertThat((answered as ScriptResult.Returned).json).isEqualTo(""""https://tracker.example/ORK-14"""")
    }

    @Test
    fun `a parameter set to a variable is resolved when the plugin runs, not when it is set`() {
        val catalogId = requireNotNull(
            catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "tracker")).id,
        )
        val variableId = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = workspaceId,
                    catalogId = catalogId,
                    name = "trackerToken",
                    type = VariableType.STRING,
                    kind = VariableKind.SECRET,
                    value = "first",
                ),
            ).id,
        )

        set("token", variableId = variableId)

        val plugin = requireNotNull(plugins.findById(pluginId).orElse(null))
        assertThat(parameters.settingsFor(plugin, workspaceId)).isEqualTo("""{"token":"first"}""")

        // Changed after it was pointed at, and the plugin gets the new one: the
        // reference is read at the moment of the run, which is what makes
        // rotating a token a change in one place.
        val variable = requireNotNull(variables.findById(variableId).orElse(null))
        variable.value = "second"
        variables.save(variable)

        assertThat(parameters.settingsFor(plugin, workspaceId)).isEqualTo("""{"token":"second"}""")

        // The name of the variable is what the screen is told. What it holds is
        // never sent back through this page.
        graphQlTester.document(
            """
            query {
              workspacePlugins(workspaceId: $workspaceId) {
                parameters { name variableName literal }
              }
            }
            """,
        ).execute()
            .path("workspacePlugins[0].parameters[1].variableName").entity(String::class.java)
            .isEqualTo("trackerToken")
            .path("workspacePlugins[0].parameters[1].literal").valueIsNull()
    }

    @Test
    fun `a required parameter nobody answered is reported, in the list and against the parameter`() {
        set("retries", literal = "3")

        graphQlTester.document(
            """
            query {
              workspacePlugins(workspaceId: $workspaceId) {
                missing
                parameters { name missing }
              }
            }
            """,
        ).execute()
            // Both required ones, and not the optional one that was answered.
            .path("workspacePlugins[0].missing").entityList(String::class.java)
            .containsExactly("baseUrl", "token")
            .path("workspacePlugins[0].parameters[0].missing").entity(Boolean::class.java).isEqualTo(true)
            .path("workspacePlugins[0].parameters[2].missing").entity(Boolean::class.java).isEqualTo(false)

        val plugin = requireNotNull(plugins.findById(pluginId).orElse(null))
        assertThat(parameters.missingFor(plugin, workspaceId)).containsExactly("baseUrl", "token")
    }

    @Test
    fun `a parameter the plugin never declared is refused rather than kept`() {
        graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId, name: "adminPassword", literal: "hunter2"
              ) { missing }
            }
            """,
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.first().message).contains("is not a parameter the tracker plugin declares")
            }

        // Nothing was written, so nothing can later be handed over on the
        // strength of a row the plugin never asked for.
        assertThat(settings.findAll()).isEmpty()
    }

    /**
     * A secret typed in is kept, encrypted, and never handed back.
     *
     * It used to be refused, and the sentence said why: what is typed into a
     * parameter is stored as typed and shown back on the page. That was a fact
     * about the column rather than about secrets - so the column changed, and
     * what this pins is the three halves of the new bargain: the value works,
     * the database does not hold it in the clear, and the screen is told only
     * that there is one.
     */
    @Test
    fun `a secret typed in is stored encrypted and never returned`() {
        graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId, name: "token", literal: "hunter2"
              ) { missing }
            }
            """,
        ).execute().errors().verify()

        val row = settings.findAll().single { it.name == "token" }
        assertThat(row.secretValue)
            .describedAs("read back through the converter, which is how the plugin will get it")
            .isEqualTo("hunter2")
        assertThat(row.literalValue)
            .describedAs("and not in the column that is shown back to people")
            .isNull()

        // Nothing carries the secret back out; the screen is told only that
        // there is one.
        graphQlTester.document(
            """
            query {
              workspacePlugins(workspaceId: $workspaceId) {
                parameters { name literal secretSet missing }
              }
            }
            """,
        ).execute()
            .path("workspacePlugins[0].parameters[1].literal").valueIsNull()
            .path("workspacePlugins[0].parameters[1].secretSet").entity(Boolean::class.java).isEqualTo(true)
            .path("workspacePlugins[0].parameters[1].missing").entity(Boolean::class.java).isEqualTo(false)
    }

    /**
     * And what is actually in the database is ciphertext.
     *
     * Asserted against the stored column rather than the entity, because the
     * entity reads through the converter and would answer "hunter2" whether or
     * not anything was ever encrypted - which is the assertion that would have
     * passed while the value sat in the clear.
     */
    @Test
    fun `the stored secret is not the secret`() {
        graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId, name: "token", literal: "hunter2"
              ) { missing }
            }
            """,
        ).execute().errors().verify()

        val stored: String? = jdbc.queryForObject(
            "select secret_value from plugin_parameter where name = 'token'",
            String::class.java,
        )
        assertThat(stored).isNotNull()
        assertThat(stored).isNotEqualTo("hunter2")
        assertThat(stored).doesNotContain("hunter2")
    }

    /** A secret still takes a variable, which is the better answer when one is shared. */
    @Test
    fun `a secret can still point at a variable, and pointing clears what was typed`() {
        graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId, name: "token", literal: "hunter2"
              ) { missing }
            }
            """,
        ).execute().errors().verify()

        val catalogId = requireNotNull(
            catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "shared")).id,
        )
        val variableId = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = workspaceId,
                    catalogId = catalogId,
                    name = "sharedToken",
                    type = VariableType.STRING,
                    kind = VariableKind.SECRET,
                    value = "from-the-variable",
                ),
            ).id,
        )

        graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId, name: "token", variableId: "$variableId"
              ) { missing }
            }
            """,
        ).execute().errors().verify()

        val row = settings.findAll().single { it.name == "token" }
        assertThat(row.variableId).isEqualTo(variableId)
        assertThat(row.secretValue)
            .describedAs("one source at a time: pointing at a variable lets go of what was typed")
            .isNull()
    }

    @Test
    fun `a variable belonging to another workspace cannot be pointed at`() {
        val catalogId = requireNotNull(
            catalogs.save(VariableCatalog(workspaceId = elsewhereId, name = "theirs")).id,
        )
        val variableId = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = elsewhereId,
                    catalogId = catalogId,
                    name = "theirToken",
                    value = "not yours",
                ),
            ).id,
        )

        graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId, name: "token", variableId: $variableId
              ) { missing }
            }
            """,
        ).execute()
            .errors()
            .satisfy { errors -> assertThat(errors.first().message).contains("belongs to another workspace") }
    }

    @Test
    fun `a value that is not what the parameter says it is refused`() {
        graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId, name: "retries", literal: "several"
              ) { missing }
            }
            """,
        ).execute()
            .errors()
            .satisfy { errors -> assertThat(errors.first().message).contains("is a number") }
    }

    @Test
    fun `clearing a parameter puts it back to being reported as missing`() {
        set("baseUrl", literal = "https://tracker.example")

        graphQlTester.document(
            """
            mutation {
              clearPluginParameter(workspaceId: $workspaceId, pluginId: $pluginId, name: "baseUrl") { missing }
            }
            """,
        ).execute()
            .path("clearPluginParameter.missing").entityList(String::class.java)
            .containsExactly("baseUrl", "token")
    }

    /** One workspace's answer does not become another's. */
    @Test
    fun `what one workspace set is not what another one is handed`() {
        set("baseUrl", literal = "https://ours.example")

        val plugin = requireNotNull(plugins.findById(pluginId).orElse(null))
        assertThat(parameters.settingsFor(plugin, elsewhereId)).isEqualTo("{}")
    }

    private fun set(name: String, literal: String? = null, variableId: Long? = null) {
        val given = listOfNotNull(
            literal?.let { "literal: \"$it\"" },
            variableId?.let { "variableId: $it" },
        ).joinToString(", ")

        graphQlTester.document(
            """
            mutation {
              setPluginParameter(workspaceId: $workspaceId, pluginId: $pluginId, name: "$name", $given) {
                missing
              }
            }
            """,
        ).execute().errors().verify()
    }

    /**
     * The plugin row, built the way an upload builds one: the source is asked what
     * it declares, and the answer is what is stored.
     */
    private fun load(): Plugin {
        val read = runner.inspect(SOURCE) as PluginInspection.Read
        return plugins.save(
            Plugin(
                key = read.id,
                name = "tracker",
                filename = "tracker.js",
                source = SOURCE,
                sizeBytes = SOURCE.length.toLong(),
                apiVersion = read.apiVersion,
                sha256 = "0".repeat(64),
                declaredFunctions = declarations.validated(read.functions),
                declaredParameters = declarations.validatedParameters(read.parameters),
            ),
        )
    }

    private companion object {
        val SOURCE = """
            export default class Tracker extends OrknuxPlugin {
              id() { return 'tracker'; }
              apiVersion() { return 1; }

              parameters() {
                return [
                  new OrknuxParameter({ name: 'baseUrl', description: 'Where it lives.', type: 'string' }),
                  new OrknuxParameter({ name: 'token', type: 'string', secret: true }),
                  new OrknuxParameter({ name: 'retries', type: 'number', required: false }),
                ];
              }

              functions() {
                return [
                  new OrknuxFunction({
                    name: 'addressOf',
                    params: [{ name: 'issue', type: 'string' }],
                    returnType: 'string',
                    run: (issue) => this.settings.baseUrl + '/' + issue,
                  }),
                ];
              }
            }
        """.trimIndent()
    }
}
