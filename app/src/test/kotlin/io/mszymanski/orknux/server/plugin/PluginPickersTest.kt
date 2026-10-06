package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.EntityLoads
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser

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
}
