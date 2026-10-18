package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.workspace.Workspace
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

/**
 * A plugin's connection kind may bring a setup guide and a check.
 *
 * Testing a connection of such a kind asks the plugin, handed the connection's
 * address and credential, whether it really is one - where it used to ask only
 * whether something answered at the address. The checks here read the handle
 * rather than reaching a network, which the suite may not.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginConnectionCheckTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceConnections.deleteAll()
        plugins.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "check-${System.nanoTime()}")).id)
    }

    /** A kind whose check believes an address holding "real", refuses "fake", and throws on "broken". */
    private val source = """
        export default class Cards extends OrknuxPlugin {
          id() { return 'cards'; }
          apiVersion() { return 1; }
          connectionTypes() {
            return [
              {
                name: 'deck',
                label: 'Deck server',
                urlPlaceholder: 'http://localhost:8765',
                defaultUrl: 'http://real.invalid:8765',
                guide: '## Setting one up\n\nInstall the add-on, then keep the app running.',
                check: (connection) => {
                  if (connection.url.includes('broken')) throw new Error('could not reach the deck server - is it running?');
                  if (connection.url.includes('fake')) return { ok: false, message: 'that is a login page, not a deck server' };
                  return { ok: true, message: 'Deck server 6, key ' + connection.secret };
                },
              },
              { name: 'plain', label: 'Plain host' },
            ];
          }
        }
    """.trimIndent()

    private fun load(text: String = source) =
        upload.upload(MockMultipartFile("file", "cards.js", "text/javascript", text.toByteArray()), null, null)

    private fun connect(kind: String, url: String): Long =
        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "${url.substringAfter("//")}", type: HTTP, pluginType: "$kind",
                 url: "$url", authType: API_KEY, secret: "s3cret"
               }) { id } }""",
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

    private fun test(id: Long): Pair<String, String?> {
        val tested = graphQlTester.document("mutation { testWorkspaceConnection(id: $id) { status lastCheckMessage } }").execute()
        return tested.path("testWorkspaceConnection.status").entity(String::class.java).get() to
            tested.path("testWorkspaceConnection.lastCheckMessage").entity(String::class.java).get()
    }

    @Test
    fun `the guide and the check are declared and offered to the form`() {
        load()

        graphQlTester.document("query { pluginConnectionTypes { id guide checks } }").execute()
            .path("pluginConnectionTypes[0].id").entity(String::class.java).isEqualTo("cards/deck")
            .path("pluginConnectionTypes[0].guide").entity(String::class.java).satisfies { assertThat(it).startsWith("## Setting one up") }
            .path("pluginConnectionTypes[0].checks").entity(Boolean::class.java).isEqualTo(true)
            .path("pluginConnectionTypes[1].checks").entity(Boolean::class.java).isEqualTo(false)
            .path("pluginConnectionTypes[1].guide").valueIsNull()
    }

    @Test
    fun `testing a connection of the kind asks the plugin, handed its address and credential`() {
        load()

        val (good, said) = test(connect("cards/deck", "http://real.invalid:8765"))
        assertThat(good).isEqualTo("CONNECTED")
        assertThat(said).isEqualTo("Deck server 6, key s3cret")

        // Something answering is not the service answering: the plugin's refusal is the reason shown.
        val (refused, why) = test(connect("cards/deck", "http://fake.invalid:8765"))
        assertThat(refused).isNotEqualTo("CONNECTED")
        assertThat(why).isEqualTo("that is a login page, not a deck server")

        // A throw is a failed check, and its sentence is the reason.
        val (thrown, because) = test(connect("cards/deck", "http://broken.invalid:8765"))
        assertThat(thrown).isNotEqualTo("CONNECTED")
        assertThat(because).contains("could not reach the deck server - is it running?")
    }

    @Test
    fun `a connection not yet added is tested as typed, and nothing is stored`() {
        load()

        fun draft(url: String) = graphQlTester.document(
            """mutation { testNewWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "", type: HTTP, pluginType: "cards/deck",
                 url: "$url", authType: API_KEY, secret: "typed"
               }) { status message } }""",
        ).execute()

        draft("http://real.invalid:8765")
            .path("testNewWorkspaceConnection.status").entity(String::class.java).isEqualTo("CONNECTED")
            .path("testNewWorkspaceConnection.message").entity(String::class.java).isEqualTo("Deck server 6, key typed")
        draft("http://fake.invalid:8765")
            .path("testNewWorkspaceConnection.status").entity(String::class.java).isEqualTo("FAILED")
            .path("testNewWorkspaceConnection.message").entity(String::class.java).isEqualTo("that is a login page, not a deck server")
        assertThat(workspaceConnections.findAll()).isEmpty()
    }

    @Test
    fun `a URL left empty takes the kind's default address, tested and added alike`() {
        load()

        graphQlTester.document("query { pluginConnectionTypes { id defaultUrl } }").execute()
            .path("pluginConnectionTypes[0].defaultUrl").entity(String::class.java).isEqualTo("http://real.invalid:8765")
            .path("pluginConnectionTypes[1].defaultUrl").valueIsNull()

        graphQlTester.document(
            """mutation { testNewWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "", type: HTTP, pluginType: "cards/deck", url: "", authType: API_KEY, secret: "k"
               }) { status message } }""",
        ).execute()
            .path("testNewWorkspaceConnection.status").entity(String::class.java).isEqualTo("CONNECTED")
        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "defaulted", type: HTTP, pluginType: "cards/deck"
               }) { url } }""",
        ).execute()
            .path("createWorkspaceConnection.url").entity(String::class.java).isEqualTo("http://real.invalid:8765")
        // A kind with no default still needs its address.
        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "bare", type: HTTP, pluginType: "cards/plain"
               }) { url } }""",
        ).execute().errors().satisfy { assertThat(it).isNotEmpty() }
    }

    @Test
    fun `a default address that is not a URL is refused at load`() {
        val refused = runCatching { load(source.replace("defaultUrl: 'http://real.invalid:8765'", "defaultUrl: 'localhost:8765'")) }
        assertThat(refused.exceptionOrNull()?.message).contains("has a default address that is not an http or https URL: localhost:8765")
    }

    @Test
    fun `a kind without a check is asked only whether its address answers`() {
        load()

        // .invalid never resolves, so the address question fails - and nothing asked the plugin.
        val (status, said) = test(connect("cards/plain", "http://plain.invalid:8765"))
        assertThat(status).isNotEqualTo("CONNECTED")
        assertThat(said).doesNotContain("Deck server")
    }

    @Test
    fun `a guide longer than four thousand characters is refused at load`() {
        val long = source.replace("'## Setting one up\\n\\nInstall the add-on, then keep the app running.'", "'" + "x".repeat(4001) + "'")

        val failure = runCatching { load(long) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginDeclarationInvalidException::class.java)
        assertThat(failure!!.message).contains("the connection type deck has a guide of 4001 characters, and a guide is at most 4000")
    }

    @Test
    fun `a check that is not a function is refused at load`() {
        val wrong = source.replace("check: (connection) => {", "check: 'yes', unused: (connection) => {")

        val failure = runCatching { load(wrong) }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(failure!!.message).contains("the connection type deck has a check that is not a function")
    }
}
