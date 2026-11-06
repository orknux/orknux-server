package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.PluginToolCaller
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A plugin written in Python, loaded by the doors a JavaScript one is - a file,
 * a zip - and called by an agent the way any plugin's tool is.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PythonPluginTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val pluginTools: PluginToolCaller,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @AfterEach
    fun clear() {
        agents.deleteAll()
        functions.deleteAll()
        plugins.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    @BeforeEach
    fun reset() {
        clear()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    private val source = """
        from orknux import OrknuxPlugin, OrknuxFunction, OrknuxFunctionTool

        class Words(OrknuxPlugin):
            def id(self):
                return 'words'

            def api_version(self):
                return 1

            def functions(self):
                return [OrknuxFunction('count', lambda text: len(text.split()), 'number',
                                       description='How many words', params=[{'name': 'text', 'type': 'string'}])]

            def tools(self):
                return [OrknuxFunctionTool('count', description='Counts the words in a text')]
    """.trimIndent()

    private fun file(name: String, text: String) = MockMultipartFile("file", name, "text/x-python", text.toByteArray())

    @Test
    fun `a py file loads as a plugin, and an agent calls its tool`() {
        upload.upload(file("words.py", source), null, null)

        val stored = requireNotNull(plugins.findByKey("words"))
        assertThat(stored.name).isEqualTo("words")
        assertThat(functions.findAll().map { it.name }).contains("words_count")

        val agent = agent("words_count")
        val answer = pluginTools.call(agent, requireNotNull(pluginTools.resolve(agent, "words_count")), """{"text":"one two three"}""")

        assertThat(answer).isEqualTo("3")
    }

    @Test
    fun `a zip with the plugin at its root as a py file loads too`() {
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("plugin.py"))
                zip.write(source.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("README.md"))
                zip.write("# Words".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        upload.upload(MockMultipartFile("file", "words.zip", "application/zip", archive), null, null)

        assertThat(plugins.findByKey("words")).isNotNull()
    }

    @Test
    fun `a py file that does not import its base class is told so, rather than read as JavaScript`() {
        assertThatThrownBy { upload.upload(file("words.py", "class Words:\n    pass\n"), null, null) }
            .hasMessage("That file is not a usable plugin: words.py is Python, and a Python plugin imports its base class: from orknux import OrknuxPlugin")
    }

    @Test
    fun `it downloads as the Python it is`() {
        upload.upload(file("words.py", source), null, null)
        val id = requireNotNull(plugins.findByKey("words")?.id)

        val downloaded = upload.download(id)

        assertThat(downloaded.headers.contentDisposition.filename).isEqualTo("words.py")
        assertThat(downloaded.body).isEqualTo(source)
    }

    private fun agent(vararg tools: String) = run {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Counter", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Counter", tools: [${tools.joinToString(", ") { "\"$it\"" }}] }) { id } }""",
        ).execute()
        requireNotNull(agents.findByIdOrNull(id))
    }
}
