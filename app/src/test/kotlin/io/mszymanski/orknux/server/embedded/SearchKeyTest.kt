package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The web search key is a secret field like every other: its own encrypted
 * copy or a workspace variable secret, never both, shown on request to whoever
 * may read this workspace's secrets, and read at the moment a search is made.
 *
 * Roles are granted by name, as in `SecretReaderRoleTest`: `billing` opens the
 * workspace and `leads` administers it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SearchKeyTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val rows: WorkspaceSearchRepository,
    @Autowired val settings: SearchSettings,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var catalogId: Long = 0

    @BeforeEach
    fun reset() {
        rows.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        roles.deleteAll(roles.findAll().filterNot { it.builtin })

        val billing = roles.save(Role(name = "billing"))
        val leads = roles.save(Role(name = "leads"))
        workspaceId = requireNotNull(
            workspaces.save(
                Workspace(name = "Research", roles = mutableSetOf(billing, leads), adminRoles = mutableSetOf(leads)),
            ).id,
        )
        catalogId = requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "Keys")).id)
    }

    @Test
    fun `a key typed in is kept encrypted, said to be set, and recorded`() {
        set(typed("tvly-typed-in"))
            .path("setWorkspaceSearch.keySet").entity(Boolean::class.java).isEqualTo(true)
            .path("setWorkspaceSearch.apiKeyVariableId").valueIsNull()

        assertThat(settings.keyOf(workspaceId).credential).isEqualTo("tvly-typed-in")
        assertThat(audit.findAll().map { it.message }).contains("Web search changed: key set")
    }

    @Test
    fun `a key read from a workspace secret holds no copy and names the variable`() {
        val key = variable("TAVILY_KEY", "tvly-from-the-vault")
        set(typed("tvly-typed-in"))

        set("apiKeyVariableId: $key")
            .path("setWorkspaceSearch.keySet").entity(Boolean::class.java).isEqualTo(false)
            .path("setWorkspaceSearch.apiKeyVariableId").entity(Long::class.java).isEqualTo(key)
            .path("setWorkspaceSearch.apiKeyVariableName").entity(String::class.java).isEqualTo("TAVILY_KEY")
            .path("setWorkspaceSearch.apiKeyVariableCatalog").entity(String::class.java).isEqualTo("Keys")
            .path("setWorkspaceSearch.apiKeyVariableMissing").entity(Boolean::class.java).isEqualTo(false)

        val row = requireNotNull(rows.findByIdOrNull(workspaceId))
        assertThat(row.apiKey).isNull()
        assertThat(row.apiKeyVariableId).isEqualTo(key)
        assertThat(settings.keyOf(workspaceId).credential).isEqualTo("tvly-from-the-vault")

        // A value typed in again drops the reference; an empty one clears both.
        set(typed("tvly-again"))
        assertThat(requireNotNull(rows.findByIdOrNull(workspaceId)).apiKeyVariableId).isNull()
        set(typed(""))
        val cleared = requireNotNull(rows.findByIdOrNull(workspaceId))
        assertThat(cleared.apiKey).isNull()
        assertThat(cleared.apiKeyVariableId).isNull()
    }

    @Test
    fun `a value and a variable together are refused, and nothing changes`() {
        val key = variable("TAVILY_KEY", "tvly-from-the-vault")
        set(typed("tvly-kept"))

        graphQlTester.document(
            """mutation { setWorkspaceSearch(workspaceId: $workspaceId, apiKey: "tvly-other", apiKeyVariableId: $key) { keySet } }""",
        ).execute().errors().expect { it.message?.contains("not both") == true }.verify()

        assertThat(settings.keyOf(workspaceId).credential).isEqualTo("tvly-kept")
    }

    @Test
    fun `a workspace value cannot hold the key`() {
        val plain = variable("REGION", "eu", VariableKind.VALUE)

        graphQlTester.document(
            """mutation { setWorkspaceSearch(workspaceId: $workspaceId, apiKeyVariableId: $plain) { keySet } }""",
        ).execute().errors().expect { it.message?.contains("is a workspace value rather than a secret") == true }.verify()
    }

    @Test
    fun `the key is revealed to an administrator, and the reveal is recorded`() {
        set(typed("tvly-typed-in"))

        reveal().path("revealWorkspaceSearchKey").entity(String::class.java).isEqualTo("tvly-typed-in")
        assertThat(audit.findAll().map { it.message }).contains("Web search key revealed")
    }

    @Test
    fun `a key read from a variable reveals nothing here`() {
        set("apiKeyVariableId: ${variable("TAVILY_KEY", "tvly-from-the-vault")}")

        reveal().path("revealWorkspaceSearchKey").valueIsNull()
        assertThat(audit.findAll().map { it.message }).doesNotContain("Web search key revealed")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BILLING"])
    fun `seeing a workspace does not reveal its search key`() {
        settings.set(workspaceId, null, "tvly-typed-in", null, null, "alice")

        reveal().errors().expect { it.message?.contains("needs a role that reads secrets") == true }.verify()
        assertThat(audit.findAll().map { it.message }).doesNotContain("Web search key revealed")
    }

    @Test
    @WithMockUser(username = "lena", roles = ["LEADS"])
    fun `administering a workspace reveals its search key`() {
        settings.set(workspaceId, null, "tvly-typed-in", null, null, "alice")

        reveal().path("revealWorkspaceSearchKey").entity(String::class.java).isEqualTo("tvly-typed-in")
    }

    /** Through the same door a model's search goes through, with the index stood in for. */
    @Test
    fun `a search sends the variable's value, read at the moment of the call`() {
        val key = variable("TAVILY_KEY", "tvly-from-the-vault")
        set("apiKeyVariableId: $key")

        val sent = mutableListOf<HttpRequest>()
        val search = SearchCapability(settings, router(sent), mapper, Tuning.DEFAULTS)

        val answered = search.run("search", """{"query":"orknux"}""", workspaceId, null)
        assertThat(answered).doesNotContain("\"error\"")
        assertThat(sent.single().headers().firstValue("authorization")).hasValue("Bearer tvly-from-the-vault")

        // Rotating the variable is one edit, and the next search sends the new value.
        val held = requireNotNull(variables.findByIdOrNull(key))
        held.value = "tvly-rotated"
        variables.save(held)
        search.run("search", """{"query":"orknux"}""", workspaceId, null)
        assertThat(sent.last().headers().firstValue("authorization")).hasValue("Bearer tvly-rotated")
    }

    @Test
    fun `a search with no key is refused before anything is sent`() {
        val sent = mutableListOf<HttpRequest>()
        val answered = SearchCapability(settings, router(sent), mapper, Tuning.DEFAULTS)
            .run("search", """{"query":"orknux"}""", workspaceId, null)

        assertThat(answered).contains("no search key set")
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a variable the search key reads cannot be deleted`() {
        val key = variable("TAVILY_KEY", "tvly-from-the-vault")
        set("apiKeyVariableId: $key")

        graphQlTester.document("mutation { deleteVariable(id: $key) }")
            .execute().errors().expect { it.message?.contains("the workspace's web search key") == true }.verify()
        assertThat(variables.findById(key)).isPresent()
    }

    @Test
    fun `the engines are offered under their names`() {
        graphQlTester.document("query { workspaceSearch(workspaceId: $workspaceId) { engines { name title } } }")
            .execute()
            .path("workspaceSearch.engines[*].title").entityList(String::class.java).containsExactly("Tavily", "Brave")
            .path("workspaceSearch.engines[*].name").entityList(String::class.java).containsExactly("tavily", "brave")
    }

    private fun set(arguments: String) = graphQlTester.document(
        """mutation { setWorkspaceSearch(workspaceId: $workspaceId, $arguments) {
             keySet apiKeyVariableId apiKeyVariableName apiKeyVariableCatalog apiKeyVariableMissing
           } }""",
    ).execute()

    /** The argument that types a key in; an empty one clears it. */
    private fun typed(key: String) = "apiKey: \"$key\""

    private fun reveal() =
        graphQlTester.document("mutation { revealWorkspaceSearchKey(workspaceId: $workspaceId) }").execute()

    private fun variable(name: String, held: String, kind: VariableKind = VariableKind.SECRET): Long = requireNotNull(
        variables.save(
            WorkspaceVariable(
                workspaceId = workspaceId,
                catalogId = catalogId,
                name = name,
                type = VariableType.STRING,
                kind = kind,
                value = held,
            ),
        ).id,
    )

    /** A router whose client keeps every request and answers an empty result list, so nothing leaves the machine. */
    private fun router(sent: MutableList<HttpRequest>): ProxyRouter {
        val answer = mock(HttpResponse::class.java, Answer { asked ->
            when (asked.method.name) {
                "statusCode" -> 200
                "body" -> """{"results":[]}"""
                else -> null
            }
        })
        val client = mock(HttpClient::class.java, Answer { asked ->
            if (asked.method.name == "send") {
                sent += asked.getArgument<HttpRequest>(0)
                answer
            } else {
                null
            }
        })
        return mock(ProxyRouter::class.java, Answer { asked ->
            when (asked.method.name) {
                "client" -> client
                "authorized" -> asked.getArgument<HttpRequest>(0)
                else -> null
            }
        })
    }
}
