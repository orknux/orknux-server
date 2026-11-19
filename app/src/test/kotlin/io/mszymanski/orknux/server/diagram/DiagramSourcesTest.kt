package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.plugin.PluginUploadAPI
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser

/**
 * Code and Jira shapes, read through the GitHub and Jira plugins: nothing is
 * read until a shape asks, what was read is what a diagram opens with until
 * it is older than the workspace keeps it, Refresh reads again regardless, a
 * read that fails keeps the copy that worked, and a Jira shape reads through
 * the Jira connection chosen for diagrams rather than the plugin's own.
 *
 * Plugins keyed `github` and `jira` stand in for the real ones, which no test
 * reaches: the file is whatever the stand-in's `answer` setting says, and the
 * issue says which Jira it came through.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramSourcesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val api: DiagramAPI,
    @Autowired val sourcesApi: DiagramSourcesAPI,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val plugins: PluginRepository,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val connections: WorkspaceConnectionRepository,
    @Autowired val export: DiagramExport,
) {

    private var workspaceId: Long = 0

    @AfterEach
    fun clear() {
        listOf("github", "jira").forEach { key ->
            plugins.findByKey(key)?.let { plugin ->
                functions.deleteAll(functions.findByPluginId(plugin.id!!))
                plugins.delete(plugin)
            }
        }
        connections.deleteAll(connections.findAll().filter { it.pluginType == DiagramSourceKind.JIRA_CONNECTION })
    }

    @BeforeEach
    fun reset() {
        clear()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-sources-${System.nanoTime()}")).id)
    }

    private fun load(key: String, source: String) {
        upload.upload(MockMultipartFile("file", "$key.js", "text/javascript", source.toByteArray()), null, null)
    }

    private fun pluginId(key: String) = requireNotNull(plugins.findByKey(key)?.id)

    private fun setting(key: String, name: String, literal: String) {
        graphQlTester.document(
            """mutation { setPluginParameter(workspaceId: $workspaceId, pluginId: ${pluginId(key)}, name: "$name", literal: "$literal") { missing } }""",
        ).execute().errors().verify()
    }

    private fun jira(name: String, url: String): Long =
        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "$name", type: HTTP, pluginType: "${DiagramSourceKind.JIRA_CONNECTION}",
                 url: "$url", authType: BEARER_TOKEN, secret: "token"
               }) { id } }""",
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

    private val file = "https://github.com/acme/shop/blob/main/src/Pay.kt#L2-L3"

    private fun diagram(): Long = api.createDiagram(
        workspaceId, "Sourced", null,
        """{"nodes": [
             {"id": "c", "kind": "code", "label": "", "source": "$file"},
             {"id": "j", "kind": "jira", "label": "", "source": "https://jira.example.com/browse/oko-4385"}
           ], "edges": []}""",
        null,
    ).id.toLong()

    @Test
    fun `a code shape's address and a Jira shape's key are kept, and what is neither is refused`() {
        val id = diagram()
        val held = api.drawing(id).nodes.associateBy { it.id }
        assertThat(held.getValue("c").source).isEqualTo(file)
        assertThat(held.getValue("j").source).isEqualTo("OKO-4385")
        assertThatThrownBy {
            api.createDiagram(workspaceId, "Bad", null, """{"nodes": [{"id": "c", "kind": "code", "source": "https://gitlab.com/a/b/blob/main/x.kt"}]}""", null)
        }.isInstanceOf(DiagramDocumentInvalidException::class.java).hasMessageContaining("GitHub file")
        assertThat(DiagramCodeSource.of(file)).isEqualTo(DiagramCodeSource("acme", "shop", "main", "src/Pay.kt", 2, 3))
        assertThat(DiagramCodeSource.of("https://github.com/acme/shop/blob/v1/README.md")?.fromLine).isNull()
        assertThat(DiagramJiraSource.of("PROJ-12")?.key).isEqualTo("PROJ-12")
        assertThat(DiagramJiraSource.of("https://x.atlassian.net/jira/software/projects/P/boards/1?selectedIssue=P-7")?.key).isEqualTo("P-7")
        assertThat(DiagramJiraSource.of("not an issue")).isNull()
    }

    @Test
    fun `without the plugins nothing is read, and with them it is kept while fresh and read again on Refresh`() {
        val id = diagram()
        assertThat(sourcesApi.diagramSources(id)).allSatisfy { assertThat(it.configured).isFalse() }

        load("github", GITHUB)
        load("jira", JIRA)
        setting("github", "answer", "one\\ntwo\\nthree\\nfour\\n")
        setting("jira", "jira", jira("Plugin's Jira", "https://plugin.example.com").toString())
        sourcesApi.setWorkspaceDiagramSources(workspaceId, null, 60)

        val first = sourcesApi.diagramSources(id).associateBy { it.kind }
        assertThat(first.getValue(DiagramSourceKind.CODE).configured).isTrue()
        assertThat(first.getValue(DiagramSourceKind.CODE).body).isNull()
        assertThat(first.getValue(DiagramSourceKind.CODE).stale).isTrue()

        val read = sourcesApi.fetchDiagramSource(id, DiagramSourceKind.CODE, file, null)
        assertThat(read.body).isEqualTo("two\nthree")
        assertThat(read.stale).isFalse()
        assertThat(sourcesApi.fetchDiagramSource(id, DiagramSourceKind.JIRA, "OKO-4385", null).body)
            .contains("\"summary\":\"OKO-4385 via https://plugin.example.com\"")

        // The file changes; the copy is fresh, so it is what is drawn - until Refresh.
        setting("github", "answer", "one\\nTWO\\nthree\\n")
        assertThat(sourcesApi.fetchDiagramSource(id, DiagramSourceKind.CODE, file, null).body).isEqualTo("two\nthree")
        assertThat(sourcesApi.diagramSources(id).first { it.kind == DiagramSourceKind.CODE }.body).isEqualTo("two\nthree")
        assertThat(sourcesApi.fetchDiagramSource(id, DiagramSourceKind.CODE, file, true).body).isEqualTo("TWO\nthree")

        // And the export draws what was read, without reading.
        val svg = String(
            (export.export(api.drawing(id), DiagramFormat.SVG, diagramId = id) as DiagramExport.Made.Written).file.bytes,
        )
        assertThat(svg).contains("TWO").contains("OKO-4385")
    }

    @Test
    fun `a Jira shape reads through the connection chosen for diagrams, and choosing another reads again`() {
        val id = diagram()
        load("jira", JIRA)
        setting("jira", "jira", jira("Plugin's Jira", "https://plugin.example.com").toString())
        val chosen = jira("Diagrams' Jira", "https://diagrams.example.com")

        sourcesApi.setWorkspaceDiagramSources(workspaceId, null, 60)
        assertThat(sourcesApi.fetchDiagramSource(id, DiagramSourceKind.JIRA, "OKO-4385", null).body).contains("via https://plugin.example.com")

        val set = sourcesApi.setWorkspaceDiagramSources(workspaceId, chosen, 60)
        assertThat(set.diagramJiraConnectionId).isEqualTo(chosen)
        // What the other Jira said is not kept as this one's.
        assertThat(sourcesApi.diagramSources(id).first { it.kind == DiagramSourceKind.JIRA }.body).isNull()
        assertThat(sourcesApi.fetchDiagramSource(id, DiagramSourceKind.JIRA, "OKO-4385", null).body).contains("via https://diagrams.example.com")
    }

    @Test
    fun `a read that fails keeps the copy that worked, and says why`() {
        val id = diagram()
        load("github", GITHUB)
        setting("github", "answer", "kept")
        sourcesApi.setWorkspaceDiagramSources(workspaceId, null, 0)
        val file = "https://github.com/acme/shop/blob/main/a.kt"
        assertThat(sourcesApi.fetchDiagramSource(id, DiagramSourceKind.CODE, file, null).body).isEqualTo("kept")

        setting("github", "answer", "FAIL")
        val failed = sourcesApi.fetchDiagramSource(id, DiagramSourceKind.CODE, file, null)
        assertThat(failed.body).isEqualTo("kept")
        assertThat(failed.failed).contains("rate limited")
    }

    @Test
    fun `a connection that is not one of this workspace's Jira connections is refused, and so are minutes out of range`() {
        load("jira", JIRA)
        val other = requireNotNull(workspaces.save(Workspace(name = "diagram-sources-other-${System.nanoTime()}")).id)
        val theirs = jira("Elsewhere", "https://elsewhere.example.com")
        assertThatThrownBy { sourcesApi.setWorkspaceDiagramSources(other, theirs, null) }
            .isInstanceOf(DiagramJiraConnectionUnfitException::class.java)
        val plain = graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Plain", type: HTTP, url: "https://plain.example.com", authType: NONE
               }) { id } }""",
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
        assertThatThrownBy { sourcesApi.setWorkspaceDiagramSources(workspaceId, plain, null) }
            .isInstanceOf(DiagramJiraConnectionUnfitException::class.java)
        connections.deleteById(plain)
        assertThatThrownBy { sourcesApi.setWorkspaceDiagramSources(workspaceId, null, -1) }
            .isInstanceOf(DiagramFetchMinutesInvalidException::class.java)
    }

    @Test
    fun `a shape set to fit its text is drawn larger than the usual size where it has room, and inside a diamond`() {
        val id = api.createDiagram(
            workspaceId, "Fit", null,
            """{"nodes": [
                 {"id": "a", "label": "Big", "x": 0, "y": 0, "w": 400, "h": 200, "textFit": true},
                 {"id": "d", "kind": "diamond", "label": "New node", "x": 500, "y": 0, "w": 330, "h": 210, "textFit": true}
               ], "edges": []}""",
            null,
        ).id.toLong()
        assertThat(api.drawing(id).nodes.first().textFit).isTrue()
        val svg = String((export.export(api.drawing(id), DiagramFormat.SVG, diagramId = id) as DiagramExport.Made.Written).file.bytes)
        val size = Regex("font-size=\"(\\d+)\"[^>]*>Big<").find(svg)?.groupValues?.get(1)?.toInt()
        assertThat(size).isGreaterThan(40)
        // The diamond's words, on however many lines: each line inside the diamond at its height, and larger than the usual size.
        val diamond = Regex("<text x=\"(\\d+)\" y=\"(\\d+)\" font-size=\"(\\d+)\"[^>]*>([^<]*(?:New|node)[^<]*)<").findAll(svg).toList()
        assertThat(diamond).isNotEmpty()
        diamond.forEach { line ->
            val (_, y, fontSize, words) = line.destructured
            assertThat(fontSize.toInt()).isGreaterThan(14)
            val half = words.length * fontSize.toInt() * 0.56 / 2
            val middle = kotlin.math.abs(y.toDouble() - fontSize.toInt() * 0.35 - 105)
            assertThat(half / 165 + (middle + fontSize.toInt() / 2.0) / 105).isLessThanOrEqualTo(1.0)
        }
    }

    private companion object {
        val GITHUB = """
            export default class GitHub extends OrknuxPlugin {
              id() { return 'github'; }
              apiVersion() { return 1; }
              parameters() {
                return [new OrknuxParameter({ name: 'answer', type: 'string', required: false })];
              }
              functions() {
                return [
                  new OrknuxFunction({
                    name: 'openFile',
                    description: 'Reads a file.',
                    params: [{ name: 'owner', type: 'string' }, { name: 'repo', type: 'string' }, { name: 'path', type: 'string' }, { name: 'ref', type: 'string' }],
                    returnType: 'string',
                    run: (owner, repo, path, ref) => {
                      const answer = (this.settings.answer ?? '').replace(/\\n/g, '\n');
                      if (answer === 'FAIL') throw new Error('rate limited');
                      return answer;
                    },
                  }),
                ];
              }
            }
        """.trimIndent()

        val JIRA = """
            export default class Jira extends OrknuxPlugin {
              id() { return 'jira'; }
              apiVersion() { return 1; }
              connectionTypes() {
                return [{ name: 'jira', label: 'Jira', description: 'A Jira site.' }];
              }
              parameters() {
                return [new OrknuxParameter({ name: 'jira', type: 'connection', connectionType: 'jira', required: false })];
              }
              functions() {
                return [
                  new OrknuxFunction({
                    name: 'openIssue',
                    description: 'Opens an issue.',
                    params: [{ name: 'key', type: 'string' }],
                    returnType: 'map',
                    run: (key) => ({ key, summary: key + ' via ' + this.settings.jira.url, status: 'In Progress' }),
                  }),
                ];
              }
            }
        """.trimIndent()
    }
}
