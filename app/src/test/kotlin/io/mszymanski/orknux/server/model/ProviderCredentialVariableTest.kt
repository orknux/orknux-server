package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.connector.model.LlmModelRepository
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A provider's credential is its own copy or a workspace variable secret.
 *
 * The reference is by id, which is the decision most of this covers. By name is
 * how an agent's MCP server grant works and it is what produced #170 and #228:
 * the name moves and the holder is left pointing at nothing, silently. So the
 * two operations that move a name - a rename and a move between catalogs - are
 * asserted to leave the provider alone, and the one that destroys something is
 * asserted to be refused with the providers named.
 *
 * The stub demands the key in the header, so "the provider is connected" here
 * means the variable's value actually reached the wire rather than that a row
 * was written.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ProviderCredentialVariableTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val service: ModelService,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var catalogId: Long = 0
    private lateinit var server: HttpServer

    /** What the stub was sent as its credential, in order. */
    private val presented = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        catalogId = catalog("Keys")
        presented.clear()

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/models") { exchange ->
            val offered = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            presented += offered
            if (offered == "Bearer sk-from-the-vault") {
                answer(exchange, 200, """{"data":[{"id":"gpt-4o"}]}""")
            } else {
                answer(exchange, 401, """{"error":{"message":"bad key"}}""")
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a provider pointed at a variable calls with what the variable holds`() {
        val secret = variable("OPENAI_KEY", "sk-from-the-vault")
        val provider = referencing("Shared OpenAI", secret)

        val checked = service.testProvider(provider)

        assertThat(checked.lastCheckMessage).isEqualTo("Connected; 1 model listed")
        assertThat(presented).containsExactly("Bearer sk-from-the-vault")
        // It holds no copy of its own, and says so.
        assertThat(requireNotNull(providers.findById(provider).orElse(null)).secret).isNull()
        assertThat(checked.secretSet).isFalse()
        assertThat(checked.secretVariableId).isEqualTo(secret)
        assertThat(checked.secretVariableName).isEqualTo("OPENAI_KEY")
        assertThat(checked.secretVariableCatalog).isEqualTo("Keys")
        assertThat(checked.secretVariableMissing).isFalse()
    }

    /**
     * The whole reason for choosing an id. A rename is tidying, and #228 is what
     * happens when tidying quietly disables something.
     */
    @Test
    fun `renaming the variable and moving it between catalogs leaves the provider working`() {
        val secret = variable("OPENAI_KEY", "sk-from-the-vault")
        val provider = referencing("Shared OpenAI", secret)
        val elsewhere = catalog("Production")

        graphQlTester.document(
            """mutation { updateVariable(id: $secret, input: {
                 name: "OPENAI_PRODUCTION_KEY", catalogId: $elsewhere
               }) { name catalogName } }""",
        ).execute()
            .path("updateVariable.name").entity(String::class.java).isEqualTo("OPENAI_PRODUCTION_KEY")
            .path("updateVariable.catalogName").entity(String::class.java).isEqualTo("Production")

        val checked = service.testProvider(provider)

        assertThat(checked.lastCheckMessage).isEqualTo("Connected; 1 model listed")
        // And the card follows the variable rather than remembering what it was.
        assertThat(checked.secretVariableName).isEqualTo("OPENAI_PRODUCTION_KEY")
        assertThat(checked.secretVariableCatalog).isEqualTo("Production")
        assertThat(checked.secretVariableMissing).isFalse()
    }

    /**
     * Deleting is the one operation that actually destroys something, so it is
     * the one that is refused - and it says which providers are holding on,
     * because "in use" without a name is a puzzle rather than an answer.
     */
    @Test
    fun `a variable a provider reads cannot be deleted`() {
        val secret = variable("OPENAI_KEY", "sk-from-the-vault")
        referencing("Shared OpenAI", secret)

        graphQlTester.document("""mutation { deleteVariable(id: $secret) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().message)
                    .contains("\"OPENAI_KEY\" is the credential of the model provider Shared OpenAI")
            }

        assertThat(variables.findById(secret)).isPresent()
    }

    /** And a variable nobody reads still deletes, with a provider in the workspace holding another. */
    @Test
    fun `a variable no provider reads still deletes`() {
        val held = variable("OPENAI_KEY", "sk-from-the-vault")
        val spare = variable("SPARE_KEY", "sk-unused")
        referencing("Shared OpenAI", held)

        graphQlTester.document("""mutation { deleteVariable(id: $spare) }""")
            .execute().path("deleteVariable").entity(Boolean::class.java).isEqualTo(true)

        assertThat(variables.findById(spare)).isEmpty()
    }

    /**
     * The other end of "only a secret may be a credential". A VALUE is returned
     * with the listing, so this would put the key on every member's screen.
     */
    @Test
    fun `a variable a provider reads cannot stop being a secret`() {
        val secret = variable("OPENAI_KEY", "sk-from-the-vault")
        referencing("Shared OpenAI", secret)

        graphQlTester.document("""mutation { updateVariable(id: $secret, input: { kind: VALUE }) { kind } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().message).contains("has to stay a secret")
            }

        assertThat(requireNotNull(variables.findById(secret).orElse(null)).kind).isEqualTo(VariableKind.SECRET)
    }

    @Test
    fun `a value cannot be bound as a credential in the first place`() {
        val plain = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = workspaceId,
                    catalogId = catalogId,
                    name = "REGION",
                    type = VariableType.STRING,
                    kind = VariableKind.VALUE,
                    value = "eu-west-1",
                ),
            ).id,
        )

        graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Shared OpenAI", endpoint: "${root()}",
                 secretVariableId: $plain
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("is a workspace value rather than a secret")
        }
    }

    @Test
    fun `a key and a variable together are refused rather than resolved`() {
        val secret = variable("OPENAI_KEY", "sk-from-the-vault")

        graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Shared OpenAI", endpoint: "${root()}",
                 secret: "sk-typed-in", secretVariableId: $secret
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("not both")
        }
    }

    /** Another workspace's secret is answered as one that does not exist. */
    @Test
    fun `a variable from another workspace cannot be bound`() {
        val elsewhereId = requireNotNull(workspaces.save(Workspace(name = "elsewhere")).id)
        val elsewhereCatalog = requireNotNull(
            catalogs.save(VariableCatalog(workspaceId = elsewhereId, name = "Theirs")).id,
        )
        val theirs = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = elsewhereId,
                    catalogId = elsewhereCatalog,
                    name = "THEIR_KEY",
                    type = VariableType.STRING,
                    kind = VariableKind.SECRET,
                    value = "sk-not-yours",
                ),
            ).id,
        )

        graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Shared OpenAI", endpoint: "${root()}",
                 secretVariableId: $theirs
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("No workspace variable with id $theirs")
        }
    }

    /**
     * The choice is made by what arrives, and making one unmakes the other. A
     * provider told to read a variable while still holding a copy of a key would
     * be a credential kept past the moment somebody stopped keeping it.
     */
    @Test
    fun `each kind of credential displaces the other`() {
        val secret = variable("OPENAI_KEY", "sk-from-the-vault")
        val provider = referencing("Shared OpenAI", secret)

        update(provider, """secret: "sk-typed-in"""")
        val own = requireNotNull(providers.findById(provider).orElse(null))
        assertThat(own.secret).isEqualTo("sk-typed-in")
        assertThat(own.secretVariableId).isNull()

        update(provider, "secretVariableId: $secret")
        val referenced = requireNotNull(providers.findById(provider).orElse(null))
        assertThat(referenced.secret).isNull()
        assertThat(referenced.secretVariableId).isEqualTo(secret)

        // And an emptied box clears the credential whichever kind it was.
        update(provider, """secret: """"")
        val cleared = requireNotNull(providers.findById(provider).orElse(null))
        assertThat(cleared.secret).isNull()
        assertThat(cleared.secretVariableId).isNull()
        // Still worth calling: an OpenAI-shaped server may check no key at all.
        assertThat(cleared.configured()).isTrue()
    }

    /**
     * Nothing should be able to strand a reference, so this strands one the only
     * way left - by writing the row the guard protects - and asserts the provider
     * says what is wrong in words about the variable.
     *
     * Failing as though the endpoint were wrong is what this whole arrangement
     * exists to avoid: it sends whoever is debugging it to check the one part of
     * the configuration that is right.
     */
    @Test
    fun `a reference that has come apart is reported as one`() {
        val secret = variable("OPENAI_KEY", "sk-from-the-vault")
        val provider = referencing("Shared OpenAI", secret)
        variables.deleteById(secret)

        val checked = service.testProvider(provider)

        assertThat(checked.secretVariableMissing).isTrue()
        assertThat(checked.secretVariableName).isNull()
        assertThat(checked.lastCheckMessage)
            .contains("reads its credential from a workspace secret that no longer exists")
        // Nothing was called: there was no key to call with.
        assertThat(presented).isEmpty()
    }

    /** A variable made but never filled in is said so, rather than sent as blank. */
    @Test
    fun `a variable with no value yet is named in the failure`() {
        val empty = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = workspaceId,
                    catalogId = catalogId,
                    name = "OPENAI_KEY",
                    type = VariableType.STRING,
                    kind = VariableKind.SECRET,
                    value = null,
                ),
            ).id,
        )
        val provider = referencing("Shared OpenAI", empty)

        val checked = service.testProvider(provider)

        assertThat(checked.lastCheckMessage)
            .isEqualTo("The workspace secret \"OPENAI_KEY\" has no value yet, so there is nothing to call this provider with.")
        assertThat(presented).isEmpty()
    }

    /**
     * Revealing is recorded against the secret it reads, so the provider is not
     * a second door onto the same value under the wrong name.
     */
    @Test
    fun `a referenced credential is not revealed through the provider`() {
        val secret = variable("OPENAI_KEY", "sk-from-the-vault")
        val provider = referencing("Shared OpenAI", secret)

        graphQlTester.document("""mutation { revealModelProviderSecret(id: $provider) }""")
            .execute().path("revealModelProviderSecret").valueIsNull()

        graphQlTester.document("""mutation { revealVariable(id: $secret) }""")
            .execute().path("revealVariable").entity(String::class.java).isEqualTo("sk-from-the-vault")

        assertThat(audit.findAll().map { it.message }).contains("Variable OPENAI_KEY revealed")
    }

    /** The value never comes back on the provider, whichever field is asked for. */
    @Test
    fun `nothing on the provider hands the credential out`() {
        val secret = variable("OPENAI_KEY", "sk-from-the-vault")
        referencing("Shared OpenAI", secret)

        val answer = graphQlTester.document(
            """{ modelProviders(workspaceId: $workspaceId) {
                 name secretSet secretVariableId secretVariableName secretVariableCatalog secretVariableMissing
               } }""",
        ).execute().path("modelProviders").entity(Any::class.java).get().toString()

        assertThat(answer).doesNotContain("sk-from-the-vault")
        assertThat(answer).contains("OPENAI_KEY")
    }

    private fun referencing(name: String, variableId: Long): Long = graphQlTester.document(
        """mutation { createModelProvider(input: {
             workspaceId: $workspaceId, name: "$name", endpoint: "${root()}",
             secretVariableId: $variableId
           }) { id } }""",
    ).execute().path("createModelProvider.id").entity(Long::class.java).get()

    private fun update(providerId: Long, credential: String) {
        graphQlTester.document(
            """mutation { updateModelProvider(id: $providerId, input: {
                 name: "Shared OpenAI", endpoint: "${root()}", $credential
               }) { id } }""",
        ).execute().path("updateModelProvider.id").hasValue()
    }

    private fun catalog(name: String): Long =
        requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = name)).id)

    private fun variable(name: String, held: String): Long = requireNotNull(
        variables.save(
            WorkspaceVariable(
                workspaceId = workspaceId,
                catalogId = catalogId,
                name = name,
                type = VariableType.STRING,
                kind = VariableKind.SECRET,
                value = held,
            ),
        ).id,
    )

    private fun root(): String = "http://${server.address.hostString}:${server.address.port}"

    private fun answer(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
