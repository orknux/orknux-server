package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelProviderCheckProperties
import io.mszymanski.orknux.connector.model.ModelProviderMonitor
import io.mszymanski.orknux.connector.model.ModelProviderSaved
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ProviderStatus
import io.mszymanski.orknux.connector.model.ModelUsageDay
import io.mszymanski.orknux.connector.model.ModelUsageRecorder
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate

/**
 * Providers and models end to end: the app decides who may ask and records what
 * they did, the connection module holds them and adds up what they were used for.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@RecordApplicationEvents
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ModelAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val service: ModelService,
    @Autowired val models: LlmModelRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val recorder: ModelUsageRecorder,
    @Autowired val chat: ModelChatClient,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    /** Stub providers, stopped after each test whatever it did. */
    private val servers = mutableListOf<HttpServer>()

    @BeforeEach
    fun reset() {
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @AfterEach
    fun stopServers() {
        servers.forEach { it.stop(0) }
        servers.clear()
    }

    @Test
    fun `a key makes a provider worth checking, not connected, and is never listed`() {
        val id = provider("Google AI", "https://generativelanguage.googleapis.com", key = null)

        graphQlTester.document("""{ modelProviders(workspaceId: $workspaceId) { id name endpoint status secretSet } }""")
            .execute()
            .path("modelProviders[0].status").entity(String::class.java).isEqualTo("NOT_CONFIGURED")
            .path("modelProviders[0].secretSet").entity(Boolean::class.java).isEqualTo(false)

        graphQlTester.document(
            """
            mutation { updateModelProvider(id: $id, input: {
              name: "Google AI", endpoint: "https://generativelanguage.googleapis.com", secret: "sk-test"
            }) { status secretSet } }
            """,
        ).execute()
            // A stored key is not a working one: only a check can say that.
            .path("updateModelProvider.status").entity(String::class.java).isEqualTo("NOT_CHECKED")
            .path("updateModelProvider.secretSet").entity(Boolean::class.java).isEqualTo(true)

        // Stored, but still not something a listing hands out.
        assertThat(providers.findAll().single().secret).isEqualTo("sk-test")
        assertThat(audit.findAll().map { it.message }).contains("Provider Google AI updated")
    }

    /** What the provider form sends when it creates one. The create input once refused it, so no provider could be made. */
    @Test
    fun `a provider is created with its default throttle, as the form sends it`() {
        graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Throttled", endpoint: "https://example.invalid/v1",
                 throttleTokensPerSecond: 250, throttleRequestsPerSecond: 1.5, acceptRetryAfter: false
               }) { id } }""",
        ).execute().path("createModelProvider.id").hasValue()

        val stored = providers.findAll().single { it.name == "Throttled" }
        assertThat(stored.throttleTokensPerSecond).isEqualTo(250L)
        assertThat(stored.throttleRequestsPerSecond).isEqualTo(1.5)
        assertThat(stored.acceptRetryAfter).isFalse()
    }

    @Test
    fun `an Azure provider keeps its own settings, and Entra ID its own credentials`() {
        val id = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Azure OpenAI Production", type: AZURE_OPENAI,
                 endpoint: "https://myinstance.openai.azure.com", authMethod: ENTRA_ID,
                 apiVersion: "2024-06-01", deploymentName: "gpt-4o-deployment", region: "East US",
                 tenantId: "11111111-1111-1111-1111-111111111111",
                 clientId: "22222222-2222-2222-2222-222222222222",
                 secret: "client-secret", scope: "https://cognitiveservices.azure.com/.default"
               }) { id type authMethod apiVersion deploymentName region tenantId clientId scope status } }""",
        ).execute()
            .path("createModelProvider.type").entity(String::class.java).isEqualTo("AZURE_OPENAI")
            .path("createModelProvider.authMethod").entity(String::class.java).isEqualTo("ENTRA_ID")
            .path("createModelProvider.apiVersion").entity(String::class.java).isEqualTo("2024-06-01")
            .path("createModelProvider.deploymentName").entity(String::class.java).isEqualTo("gpt-4o-deployment")
            .path("createModelProvider.region").entity(String::class.java).isEqualTo("East US")
            // Tenant and client are configuration, so they come back; the secret does not.
            .path("createModelProvider.clientId").entity(String::class.java)
            .isEqualTo("22222222-2222-2222-2222-222222222222")
            .path("createModelProvider.status").entity(String::class.java).isEqualTo("NOT_CHECKED")
            .path("createModelProvider.id").entity(Long::class.java).get()

        assertThat(providers.findAll().single { it.id == id }.secret).isEqualTo("client-secret")
    }

    @Test
    fun `a check that cannot reach the provider says so, and is not a connection`() {
        // .invalid can never resolve, so this tests the probe without the network.
        val id = provider("Broken", "https://api.example.invalid/v1")

        graphQlTester.document(
            """mutation { testModelProvider(id: $id) { status lastCheckMessage lastCheckedAt } }""",
        ).execute()
            .path("testModelProvider.status").entity(String::class.java).isEqualTo("FAILED")
            .path("testModelProvider.lastCheckedAt").entity(String::class.java).get()

        assertThat(providers.findAll().single().lastCheckMessage).isNotBlank()
        assertThat(audit.findAll().map { it.message }).anyMatch { it.startsWith("Provider Broken checked:") }
    }

    @Test
    fun `discovery lists what the provider offers, and says which are already added`() {
        // Both spellings at once, the way llama.cpp answers: `data[].id` wins.
        val body = """
            {"models":[{"name":"ignored-when-data-is-present"}],
             "object":"list",
             "data":[{"id":"gemma-4-31b-it","object":"model"},{"id":"qwen-3-8b","object":"model"}]}
        """.trimIndent()
        val id = provider("Local", serve(200, body))
        model(id, "Gemma", "gemma-4-31b-it")

        graphQlTester.document("""{ discoveredModels(providerId: $id) { modelId added } }""")
            .execute()
            // Sorted, and the one already in the catalogue is marked rather than dropped.
            .path("discoveredModels[0].modelId").entity(String::class.java).isEqualTo("gemma-4-31b-it")
            .path("discoveredModels[0].added").entity(Boolean::class.java).isEqualTo(true)
            .path("discoveredModels[1].modelId").entity(String::class.java).isEqualTo("qwen-3-8b")
            .path("discoveredModels[1].added").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `an Ollama-shaped listing is read too`() {
        val body = """{"models":[{"name":"llama3.2:latest","model":"llama3.2:latest"}]}"""
        val id = provider("Ollama", serve(200, body))

        graphQlTester.document("""{ discoveredModels(providerId: $id) { modelId added } }""")
            .execute()
            .path("discoveredModels[0].modelId").entity(String::class.java).isEqualTo("llama3.2:latest")
            .path("discoveredModels").entityList(Any::class.java).hasSize(1)
    }

    @Test
    fun `a provider that will not answer says why, rather than offering nothing`() {
        val id = provider("Wrong endpoint", serve(404, ""))

        graphQlTester.document("""{ discoveredModels(providerId: $id) { modelId } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                // The provider's own words: an empty picker would read as "it has none".
                assertThat(errors.single().message).contains("No model list at")
            }
    }

    @Test
    fun `discovery needs credentials before it can ask`() {
        val id = provider("Unconfigured", serve(200, """{"data":[]}"""), key = null)

        graphQlTester.document("""{ discoveredModels(providerId: $id) { modelId } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message).contains("no credentials")
            }
    }

    @Test
    fun `saving a provider asks for it to be checked`(events: ApplicationEvents) {
        val id = provider("Local", "https://api.example.invalid/v1")

        // Saving is when somebody wants to know, so the check is not left to the
        // next sweep. The monitor is switched off for the suite, so what is
        // asserted here is the asking.
        assertThat(events.stream(ModelProviderSaved::class.java).map { it.providerId }).contains(id)
    }

    @Test
    fun `the check a save asks for actually runs`() {
        val id = provider("Local", serve(200, """{"data":[{"id":"m1"}]}"""))
        // Built by hand because the suite runs with the monitor switched off.
        val monitor = ModelProviderMonitor(providers, service, ModelProviderCheckProperties())
        monitor.start()
        try {
            monitor.onProviderSaved(ModelProviderSaved(id))

            // It runs off the caller's thread, so the answer arrives shortly after.
            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                val checked = providers.findAll().single { it.id == id }
                assertThat(checked.status).isEqualTo(ProviderStatus.CONNECTED)
                assertThat(checked.lastCheckMessage).isEqualTo("Connected; 1 model listed")
            }
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `the periodic sweep checks what is configured and leaves the rest alone`() {
        // Configured, and unreachable: the sweep should record that.
        provider("Broken", "https://api.example.invalid/v1")
        // Nothing to check with, which is not the same as broken.
        provider("Unconfigured", "https://api.example.invalid/v1", key = null)

        ModelProviderMonitor(providers, service, ModelProviderCheckProperties()).sweep()

        val byName = providers.findAll().associateBy { it.name }
        assertThat(byName.getValue("Broken").status).isEqualTo(ProviderStatus.FAILED)
        assertThat(byName.getValue("Broken").lastCheckedAt).isNotNull()
        // Skipped rather than failed: not configured is not broken.
        assertThat(byName.getValue("Unconfigured").status).isEqualTo(ProviderStatus.NOT_CONFIGURED)
        assertThat(byName.getValue("Unconfigured").lastCheckedAt).isNull()
    }

    @Test
    fun `editing a provider forgets what the last check found`() {
        val id = provider("Broken", "https://api.example.invalid/v1")
        graphQlTester.document("""mutation { testModelProvider(id: $id) { status } }""").execute()
        assertThat(providers.findAll().single().status.name).isEqualTo("FAILED")

        // The endpoint moved, so the old answer describes a provider that is gone.
        graphQlTester.document(
            """mutation { updateModelProvider(id: $id, input: {
                 name: "Broken", endpoint: "https://api.elsewhere.invalid/v1"
               }) { status lastCheckMessage lastCheckedAt } }""",
        ).execute()
            .path("updateModelProvider.status").entity(String::class.java).isEqualTo("NOT_CHECKED")
            .path("updateModelProvider.lastCheckMessage").valueIsNull()
            .path("updateModelProvider.lastCheckedAt").valueIsNull()
    }

    @Test
    fun `models are listed by provider, then by name`() {
        val anthropic = provider("Anthropic", "https://api.anthropic.com/v1")
        val openai = provider("OpenAI", "https://api.openai.com/v1")
        model(openai, "GPT-4o", "gpt-4o")
        model(anthropic, "Claude 3.5 Sonnet", "claude-3-5-sonnet-20241022")
        model(anthropic, "Claude 3 Haiku", "claude-3-haiku-20240307")

        graphQlTester.document("""{ models(workspaceId: $workspaceId) { name providerName } }""")
            .execute()
            .path("models[*].name").entityList(String::class.java)
            .containsExactly("Claude 3 Haiku", "Claude 3.5 Sonnet", "GPT-4o")
    }

    @Test
    fun `removing a provider takes its models with it`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        model(providerId, "Claude 3.5 Sonnet", "claude-3-5-sonnet-20241022")

        graphQlTester.document("""mutation { removeModelProvider(id: $providerId) }""")
            .execute().path("removeModelProvider").entity(Boolean::class.java).isEqualTo(true)

        assertThat(models.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message }).contains("Provider Anthropic removed, with its models")
    }

    @Test
    fun `the quotas card saves its fields together, and an emptied one is no limit`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val modelId = model(providerId, "Claude 3.5 Sonnet", "claude-3-5-sonnet-20241022")

        graphQlTester.document(
            """mutation { updateModelQuotas(id: $modelId, input: {
                 tokenLimit: 5000000, resetInterval: WEEKLY, requestsPerMinute: 60
               }) { tokenLimit resetInterval requestsPerMinute } }""",
        ).execute()
            .path("updateModelQuotas.tokenLimit").entity(Double::class.java).isEqualTo(5_000_000.0)
            .path("updateModelQuotas.resetInterval").entity(String::class.java).isEqualTo("WEEKLY")
            .path("updateModelQuotas.requestsPerMinute").entity(Int::class.java).isEqualTo(60)

        // The box was emptied, so the limit is meant to go.
        graphQlTester.document(
            """mutation { updateModelQuotas(id: $modelId, input: {
                 tokenLimit: null, resetInterval: MONTHLY, requestsPerMinute: 60
               }) { tokenLimit requestsPerMinute } }""",
        ).execute()
            .path("updateModelQuotas.tokenLimit").valueIsNull()
            .path("updateModelQuotas.requestsPerMinute").entity(Int::class.java).isEqualTo(60)

        assertThat(audit.findAll().map { it.message })
            .contains("Quotas for Claude 3.5 Sonnet updated")
    }

    @Test
    fun `the throttle card saves a model's own rate, and an emptied one inherits`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val modelId = model(providerId, "Claude 3.5 Sonnet", "claude-3-5-sonnet-20241022")

        graphQlTester.document(
            """mutation { updateModelThrottle(id: $modelId, input: {
                 throttleTokensPerSecond: 40000, throttleRequestsPerSecond: 5, acceptRetryAfter: false
               }) { throttleTokensPerSecond throttleRequestsPerSecond acceptRetryAfter } }""",
        ).execute()
            .path("updateModelThrottle.throttleTokensPerSecond").entity(Double::class.java).isEqualTo(40_000.0)
            .path("updateModelThrottle.throttleRequestsPerSecond").entity(Double::class.java).isEqualTo(5.0)
            .path("updateModelThrottle.acceptRetryAfter").entity(Boolean::class.java).isEqualTo(false)

        // Emptied, so the rate goes back to inheriting the provider's default.
        graphQlTester.document(
            """mutation { updateModelThrottle(id: $modelId, input: {
                 throttleTokensPerSecond: null, throttleRequestsPerSecond: null, acceptRetryAfter: null
               }) { throttleTokensPerSecond acceptRetryAfter } }""",
        ).execute()
            .path("updateModelThrottle.throttleTokensPerSecond").valueIsNull()
            .path("updateModelThrottle.acceptRetryAfter").valueIsNull()

        assertThat(audit.findAll().map { it.message })
            .contains("Throttle for Claude 3.5 Sonnet updated")
    }

    @Test
    fun `a provider's default throttle is saved and read back`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")

        graphQlTester.document(
            """mutation { updateModelProvider(id: $providerId, input: {
                 name: "Anthropic", endpoint: "https://api.anthropic.com/v1",
                 throttleTokensPerSecond: 100000, throttleRequestsPerSecond: 8, acceptRetryAfter: false
               }) { throttleTokensPerSecond throttleRequestsPerSecond acceptRetryAfter } }""",
        ).execute()
            .path("updateModelProvider.throttleTokensPerSecond").entity(Double::class.java).isEqualTo(100_000.0)
            .path("updateModelProvider.throttleRequestsPerSecond").entity(Double::class.java).isEqualTo(8.0)
            .path("updateModelProvider.acceptRetryAfter").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `a model nothing has called reports an empty window rather than zeros as a result`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val modelId = model(providerId, "Claude 3.5 Sonnet", "claude-3-5-sonnet-20241022")

        graphQlTester.document(
            """{ modelUsage(id: $modelId) { empty requests totalTokens costEstimate series { day } } }""",
        ).execute()
            .path("modelUsage.empty").entity(Boolean::class.java).isEqualTo(true)
            .path("modelUsage.requests").entity(Int::class.java).isEqualTo(0)
            // A day per day of the window, all of them nothing: `empty` above is
            // what says so, not the absence of points.
            .path("modelUsage.series").entityList(Any::class.java).hasSize(30)
            .path("modelUsage.series[?(@.requests > 0)]").entityList(Any::class.java).hasSize(0)
            // No prices recorded, so there is no cost to state.
            .path("modelUsage.costEstimate").valueIsNull()
    }

    @Test
    fun `a call adds itself to the day, and a second call adds to the same row`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val id = model(providerId, "Claude", "claude-3-5-sonnet")

        recorder.record(id, inputTokens = 1_000, outputTokens = 200, millis = 120)
        recorder.record(id, inputTokens = 500, outputTokens = 100, millis = 80)

        // One row for the day, holding both calls: this is what the metrics
        // screen adds up, and what it reported as empty while nothing wrote here.
        val today = usage.findAll().single()
        assertThat(today.requests).isEqualTo(2)
        assertThat(today.inputTokens).isEqualTo(1_500)
        assertThat(today.outputTokens).isEqualTo(300)
        assertThat(today.latencyMillisTotal).isEqualTo(200)

        graphQlTester.document("""{ modelUsage(id: $id) { empty requests totalTokens averageLatencyMillis } }""")
            .execute()
            .path("modelUsage.empty").entity(Boolean::class.java).isEqualTo(false)
            .path("modelUsage.requests").entity(Int::class.java).isEqualTo(2)
            .path("modelUsage.totalTokens").entity(Double::class.java).isEqualTo(1_800.0)
            // The mean is the total time over the total requests.
            .path("modelUsage.averageLatencyMillis").entity(Double::class.java).isEqualTo(100.0)
    }

    @Test
    fun `usage is summed over the recorded days, with the cost the prices give`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val modelId = model(providerId, "Claude 3.5 Sonnet", "claude-3-5-sonnet-20241022")

        graphQlTester.document(
            """mutation { updateModel(id: $modelId, input: {
                 name: "Claude 3.5 Sonnet", modelId: "claude-3-5-sonnet-20241022",
                 inputCostPerMillion: 3.0, outputCostPerMillion: 15.0
               }) { id } }""",
        ).execute()

        val today = LocalDate.now()
        usage.save(day(modelId, today.minusDays(1), requests = 100, input = 1_000_000, output = 200_000, latency = 120_000))
        usage.save(day(modelId, today, requests = 100, input = 1_000_000, output = 200_000, latency = 180_000))

        graphQlTester.document(
            """{ modelUsage(id: $modelId) {
                   empty requests inputTokens outputTokens totalTokens averageLatencyMillis costEstimate
                   periodTokens series { day requests tokens }
                 } }""",
        ).execute()
            .path("modelUsage.empty").entity(Boolean::class.java).isEqualTo(false)
            .path("modelUsage.requests").entity(Int::class.java).isEqualTo(200)
            .path("modelUsage.totalTokens").entity(Double::class.java).isEqualTo(2_400_000.0)
            // Total time over total requests, not a mean of the two days' means.
            .path("modelUsage.averageLatencyMillis").entity(Double::class.java).isEqualTo(1500.0)
            // 2M in at $3/M, 400k out at $15/M.
            .path("modelUsage.costEstimate").entity(Double::class.java).isEqualTo(12.0)
            // The window, with the two recorded days in it.
            .path("modelUsage.series").entityList(Any::class.java).hasSize(30)
            .path("modelUsage.series[?(@.requests > 0)].requests").entityList(Int::class.java)
            .containsExactly(100, 100)
    }

    /* --------------------------------------- the window somebody asked for */

    /**
     * Issue #370. Thirty days was fixed and nothing on the page could ask for
     * anything else - and every question people actually bring here is about a
     * different window: what yesterday's run cost, what was spent last month,
     * whether the spike on the 14th was this model.
     */
    @Test
    fun `a range answers for the days between the two dates and nothing else`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val modelId = model(providerId, "Claude", "claude-3-5-sonnet")

        val today = LocalDate.now()
        usage.save(day(modelId, today.minusDays(10), requests = 7, input = 700, output = 70, latency = 700))
        usage.save(day(modelId, today.minusDays(3), requests = 5, input = 500, output = 50, latency = 500))
        usage.save(day(modelId, today, requests = 2, input = 200, output = 20, latency = 200))

        val from = today.minusDays(4)
        val to = today.minusDays(2)

        graphQlTester.document(
            """{ modelUsage(id: $modelId, from: "$from", to: "$to") {
                   days from to requests totalTokens series { day requests }
                 } }""",
        ).execute()
            // Three days, which is what was asked for rather than the thirty
            // nobody asked for - and the headings read off this number.
            .path("modelUsage.days").entity(Int::class.java).isEqualTo(3)
            .path("modelUsage.from").entity(String::class.java).isEqualTo(from.toString())
            .path("modelUsage.to").entity(String::class.java).isEqualTo(to.toString())
            // Only the day inside it: the other two are outside the window and
            // are the whole reason somebody narrowed it.
            .path("modelUsage.requests").entity(Int::class.java).isEqualTo(5)
            .path("modelUsage.totalTokens").entity(Double::class.java).isEqualTo(550.0)
            .path("modelUsage.series").entityList(Any::class.java).hasSize(3)
    }

    /**
     * "Since the 1st" is a range. Asking somebody to type today's date to mean
     * "until now" is asking them to type what the server already knows.
     */
    @Test
    fun `one end alone is read the way it is said`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val modelId = model(providerId, "Claude", "claude-3-5-sonnet")

        val today = LocalDate.now()
        usage.save(day(modelId, today.minusDays(6), requests = 9, input = 900, output = 90, latency = 900))
        usage.save(day(modelId, today, requests = 1, input = 100, output = 10, latency = 100))

        graphQlTester.document(
            """{ modelUsage(id: $modelId, from: "${today.minusDays(2)}") { days to requests } }""",
        ).execute()
            .path("modelUsage.days").entity(Int::class.java).isEqualTo(3)
            .path("modelUsage.to").entity(String::class.java).isEqualTo(today.toString())
            .path("modelUsage.requests").entity(Int::class.java).isEqualTo(1)
    }

    /**
     * Read rather than refused: somebody who picked the two dates in the other
     * order meant the days between them, and an error about which box was which
     * answers nothing they asked.
     */
    @Test
    fun `a range picked the other way round is still the days between them`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val modelId = model(providerId, "Claude", "claude-3-5-sonnet")

        val today = LocalDate.now()
        graphQlTester.document(
            """{ modelUsage(id: $modelId, from: "$today", to: "${today.minusDays(4)}") { days from to } }""",
        ).execute()
            .path("modelUsage.days").entity(Int::class.java).isEqualTo(5)
            .path("modelUsage.from").entity(String::class.java).isEqualTo(today.minusDays(4).toString())
            .path("modelUsage.to").entity(String::class.java).isEqualTo(today.toString())
    }

    /** There is no usage in the future, and empty days after the last one would
     * squash the part somebody is looking at. */
    @Test
    fun `a window running past today is cut off at today`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val modelId = model(providerId, "Claude", "claude-3-5-sonnet")

        val today = LocalDate.now()
        graphQlTester.document(
            """{ modelUsage(id: $modelId, from: "${today.minusDays(2)}", to: "${today.plusDays(40)}") {
                   days to
                 } }""",
        ).execute()
            .path("modelUsage.days").entity(Int::class.java).isEqualTo(3)
            .path("modelUsage.to").entity(String::class.java).isEqualTo(today.toString())
    }

    /**
     * Refused rather than ignored: a window quietly falling back to the last
     * thirty days would be a chart answering a different question from the one
     * on screen.
     */
    @Test
    fun `a date that is not one is refused by name`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val modelId = model(providerId, "Claude", "claude-3-5-sonnet")

        graphQlTester.document("""{ modelUsage(id: $modelId, from: "last tuesday") { days } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().message).contains("is not a date").contains("`from`")
            }
    }

    /** An emptied box sends nothing, which is the thirty days it opened on. */
    @Test
    fun `an empty date is nothing asked for rather than a bad one`() {
        val providerId = provider("Anthropic", "https://api.anthropic.com/v1")
        val modelId = model(providerId, "Claude", "claude-3-5-sonnet")

        graphQlTester.document("""{ modelUsage(id: $modelId, from: "", to: "  ") { days } }""")
            .execute().path("modelUsage.days").entity(Int::class.java).isEqualTo(30)
    }

    @Test
    fun `the active toggle is recorded, and says which way it went`() {
        val providerId = provider("OpenAI", "https://api.openai.com/v1")
        val modelId = model(providerId, "GPT-4o mini", "gpt-4o-mini")

        graphQlTester.document("""mutation { setModelEnabled(id: $modelId, enabled: false) { enabled } }""")
            .execute().path("setModelEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)

        assertThat(audit.findAll().map { it.message }).contains("Model GPT-4o mini deactivated")
        assertThat(audit.findAll().map { it.category.name }).contains("MODEL")
    }

    @Test
    fun `two providers in a workspace cannot share a name`() {
        provider("Anthropic", "https://api.anthropic.com/v1")

        graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Anthropic", endpoint: "https://example.invalid"
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("already exists")
        }
    }

    /**
     * A provider endpoint that answers `/models` with [body].
     *
     * A real server on the loopback address rather than a stubbed client: the
     * URL building, the header and the parsing are the parts worth testing, and
     * a fake HttpClient would only prove the code agrees with itself. Loopback
     * is not the network, so this stays hermetic.
     */
    private fun serve(status: Int, body: String): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/models") { exchange ->
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            if (bytes.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        servers += server
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /**
     * A stub chat endpoint that streams, the way the chat screen calls one.
     * The counts arrive in a frame of their own, as `include_usage` asks.
     */
    private fun serveStream(): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val frames = listOf(
                """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
                """data: {"choices":[{"delta":{"content":"lo."}}]}""",
                """data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2}}""",
                "data: [DONE]",
            ).joinToString(FRAME_BREAK, postfix = FRAME_BREAK).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, frames.size.toLong())
            exchange.responseBody.use { it.write(frames) }
            exchange.close()
        }
        server.start()
        servers += server
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /**
     * A stub chat endpoint answering one completion, with the token counts a
     * provider reports.
     */
    private fun serveChat(): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = """
                {"choices":[{"message":{"content":"Hello."}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":3}}
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        servers += server
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /** The Provider select on a model's page: the model keeps its id and follows to the provider chosen. */
    @Test
    fun `a model moves to another provider in its workspace`() {
        val from = provider("Local", "https://local.example.invalid/v1")
        val to = provider("Azure", "https://azure.example.invalid/v1")
        val id = model(from, "Gemma", "gemma")

        graphQlTester.document(
            """mutation { updateModel(id: $id, input: { name: "Gemma", modelId: "gemma", providerId: $to }) {
                 id providerId providerName } }""",
        ).execute()
            .path("updateModel.id").entity(Long::class.java).isEqualTo(id)
            .path("updateModel.providerId").entity(Long::class.java).isEqualTo(to)
            .path("updateModel.providerName").entity(String::class.java).isEqualTo("Azure")

        assertThat(models.findById(id).get().providerId).isEqualTo(to)
        assertThat(audit.findAll().map { it.message }).contains("Model Gemma moved to provider Azure")

        // Left out, the provider stays where it is: a caller that predates the move is not a move.
        graphQlTester.document("""mutation { updateModel(id: $id, input: { name: "Gemma", modelId: "gemma" }) { providerId } }""")
            .execute().path("updateModel.providerId").entity(Long::class.java).isEqualTo(to)
    }

    @Test
    fun `a model will not move to another workspace's provider, nor onto a name taken there`() {
        val from = provider("Local", "https://local.example.invalid/v1")
        val to = provider("Azure", "https://azure.example.invalid/v1")
        val id = model(from, "Gemma", "gemma")
        model(to, "Gemma", "gemma-other")

        graphQlTester.document(
            """mutation { updateModel(id: $id, input: { name: "Gemma", modelId: "gemma", providerId: $to }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).isEqualTo("A model named \"Gemma\" already exists on this provider")
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelNameTaken")
        }

        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "elsewhere")).id)
        val foreign = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $elsewhere, name: "Foreign", endpoint: "https://foreign.example.invalid/v1"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateModel(id: $id, input: { name: "Gemma", modelId: "gemma", providerId: $foreign }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("only move to a provider in its own workspace")
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelProviderInAnotherWorkspace")
        }

        assertThat(models.findById(id).get().providerId).isEqualTo(from)
        assertThat(audit.findAll().map { it.message }).noneMatch { it.contains("moved to provider") }
    }

    /** The Duplicate button on a provider's row: its settings and its models come, its key does not. */
    @Test
    fun `a provider is duplicated with its models and without its key`() {
        val id = provider("Local", "https://local.example.invalid/v1")
        model(id, "Gemma", "gemma")
        model(id, "Qwen", "qwen")

        val copy = graphQlTester.document("""mutation { duplicateModelProvider(id: $id) { id name endpoint secretSet status } }""")
            .execute()
        copy.path("duplicateModelProvider.name").entity(String::class.java).isEqualTo("Local (copy)")
        copy.path("duplicateModelProvider.endpoint").entity(String::class.java).isEqualTo("https://local.example.invalid/v1")
        copy.path("duplicateModelProvider.secretSet").entity(Boolean::class.java).isEqualTo(false)
        copy.path("duplicateModelProvider.status").entity(String::class.java).isEqualTo("NOT_CONFIGURED")
        val copyId = copy.path("duplicateModelProvider.id").entity(Long::class.java).get()

        assertThat(models.findByProviderId(copyId).map { it.name to it.modelId })
            .containsExactlyInAnyOrder("Gemma" to "gemma", "Qwen" to "qwen")
        // The original is as it was, key and models both.
        assertThat(providers.findById(id).get().secret).isEqualTo("sk-test")
        assertThat(models.findByProviderId(id)).hasSize(2)
        assertThat(audit.findAll().map { it.message }).contains("Provider Local duplicated as Local (copy), with its models")

        graphQlTester.document("""mutation { duplicateModelProvider(id: $id) { name } }""").execute()
            .path("duplicateModelProvider.name").entity(String::class.java).isEqualTo("Local (copy 2)")
    }

    private fun provider(name: String, endpoint: String, key: String? = "sk-test"): Long {
        val secret = if (key == null) "" else """, secret: "$key""""
        return graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name", endpoint: "$endpoint"$secret
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
    }

    private fun model(providerId: Long, name: String, modelId: String): Long = graphQlTester.document(
        """mutation { createModel(input: {
             providerId: $providerId, name: "$name", modelId: "$modelId", kind: CHAT
           }) { id } }""",
    ).execute().path("createModel.id").entity(Long::class.java).get()

    /**
     * Calling a model is what writes a usage row.
     *
     * The recorder had a test of its own and the screen had a query, but nothing
     * covered the step between them — so a chat could answer for weeks while the
     * metrics stayed empty and every test still passed. This goes through
     * [ModelChatClient], which is the only thing that calls a model.
     */
    @Test
    fun `answering a chat is what counts a call`() {
        val providerId = provider("Local", serveChat())
        val modelId = model(providerId, "Gemma", "gemma-4")

        val answer = chat.complete(modelId, listOf(ChatTurn(role = "user", content = "Hi")))

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        val today = usage.findAll().single()
        assertThat(today.modelId).isEqualTo(modelId)
        assertThat(today.requests).isEqualTo(1)
        assertThat(today.inputTokens).isEqualTo(11)
        assertThat(today.outputTokens).isEqualTo(3)
    }

    /**
     * And the streamed path counts too, which is the one the chat screen uses:
     * `complete` is only called for the small jobs. A call counted in one and
     * not the other would under-report exactly the traffic there is most of.
     */
    @Test
    fun `a streamed answer is counted as well`() {
        val providerId = provider("Local", serveStream())
        val modelId = model(providerId, "Gemma", "gemma-4")

        val pieces = mutableListOf<String>()
        // Named, because there are two callbacks now - the answer and the
        // thinking - and which one a bare third argument was would be a guess.
        val answer = chat.stream(modelId, listOf(ChatTurn(role = "user", content = "Hi")), onChunk = pieces::add)

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat(pieces.joinToString("")).isEqualTo("Hello.")
        val today = usage.findAll().single()
        assertThat(today.requests).isEqualTo(1)
        assertThat(today.inputTokens).isEqualTo(7)
        assertThat(today.outputTokens).isEqualTo(2)
    }

    private fun day(modelId: Long, day: LocalDate, requests: Int, input: Long, output: Long, latency: Long) =
        ModelUsageDay(
            modelId = modelId,
            day = day,
            requests = requests,
            inputTokens = input,
            outputTokens = output,
            latencyMillisTotal = latency,
        )

    private companion object {
        /** A blank line is what ends one server-sent event and starts the next. */
        const val FRAME_BREAK = "\n\n"
    }
}
