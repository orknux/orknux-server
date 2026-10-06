package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.EntityLoads
import io.mszymanski.orknux.server.SqlSeen
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.support.TransactionTemplate

/**
 * The pickers that list what plugins declare read no plugin whole. Issue #616:
 * an installation with a plugin carrying a 4 MB bundle ran out of memory on an
 * agent's settings page, because each picker read every plugin - source,
 * typescript, icons - to look at its list of names.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginPickersTest(
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val api: PluginAPI,
    @Autowired val actions: PluginActions,
    @Autowired val connectionTypes: PluginConnectionTypes,
    @Autowired val entityManagerFactory: EntityManagerFactory,
    @Autowired val workspacePlugins: WorkspacePluginAPI,
    @Autowired val types: PluginTypes,
    @Autowired val code: PluginCodeRepository,
    @Autowired val sources: PluginSources,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val transactions: TransactionTemplate,
) {

    @BeforeEach
    fun reset() {
        plugins.deleteAll()
        val source = """
            export default class Pickers extends OrknuxPlugin {
              id() { return 'pickers'; }
              apiVersion() { return 1; }
              tools() { return [new OrknuxTool({ name: 'echo', description: 'Says it back.', params: [], returnType: 'map', run: () => ({ said: 'x' }) })]; }
            }
        """.trimIndent()
        upload.upload(MockMultipartFile("file", "pickers.js", "text/javascript", source.toByteArray()), null, null)
    }

    @Test
    fun `the tool, action and connection pickers read no plugin bundle`() {
        val loads = EntityLoads(entityManagerFactory)

        val tools = loads.of(Plugin::class) { api.pluginTools() }
        assertThat(tools.answer.map { it.name }).contains("pickers_echo")
        assertThat(tools.loaded).describedAs("plugins read to list their tools").isZero()

        assertThat(loads.of(Plugin::class) { actions.all() }.loaded).describedAs("for the actions").isZero()
        assertThat(loads.of(Plugin::class) { connectionTypes.all() }.loaded).describedAs("for connection kinds").isZero()
    }

    /**
     * Every list and lookup of plugins reads the row without its code.
     *
     * The pickers above were moved to projections one by one; this is the rest
     * - the admin list, a workspace's plugins page, the type picker, the
     * marketplace's `findAll`, and every `findByKey` and `findById` - which read
     * the source because the entity carried it. Measured in SQL rather than in
     * entity loads, because a plugin is still loaded: what must be gone is the
     * column. A 4 MB bundle is what production had.
     */
    @Test
    fun `no list or lookup of plugins selects a plugin's code`() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "pickers-" + System.nanoTime())).id)
        val plugin = requireNotNull(plugins.findByKey("pickers"))
        val id = requireNotNull(plugin.id)
        val bundle = sources.sourceOf(plugin) + "\n// " + "x".repeat(4 * 1024 * 1024)
        transactions.executeWithoutResult { code.replace(id, bundle, null) }

        val (_, statements) = SqlSeen.during {
            assertThat(api.plugins().map { it.key }).contains("pickers")
            assertThat(workspacePlugins.workspacePlugins(workspaceId).map { it.plugin.key }).contains("pickers")
            assertThat(api.pluginTools().map { it.name }).contains("pickers_echo")
            types.offered()
            types.find("pickers_Nothing")
            actions.declared("pickers", "nothing")
            assertThat(plugins.findAll().map { it.key }).contains("pickers")
            assertThat(plugins.findById(id)).isPresent
            assertThat(plugins.findAllByOrderByNameAsc()).isNotEmpty
        }

        val fromPlugin = statements.mapNotNull { sql ->
            PLUGIN_ALIAS.find(sql)?.let { sql to it.groupValues[1] }
        }
        assertThat(fromPlugin).describedAs("statements against the plugin table seen at all").isNotEmpty
        assertThat(fromPlugin.filter { (sql, alias) -> Regex("""\b$alias\.(source|typescript)\b""").containsMatchIn(sql) }
            .map { it.first })
            .describedAs("statements that read a plugin's code")
            .isEmpty()

        // And the code is still there for whoever runs it.
        assertThat(sources.sourceOf(plugin)).hasSize(bundle.length)
        assertThat(code.codeOf(id)?.source).isEqualTo(bundle)
    }

    private companion object {
        /** `from plugin p1_0` or `join plugin p1_0` - and not `plugin_library`. */
        val PLUGIN_ALIAS = Regex("""(?i)\b(?:from|join)\s+plugin\s+(?:as\s+)?(\w+)""")
    }
}
