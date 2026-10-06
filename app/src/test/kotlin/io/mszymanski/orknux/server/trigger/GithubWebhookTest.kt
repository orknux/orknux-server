package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.plugin.PluginParameterSettingRepository
import io.mszymanski.orknux.server.plugin.PluginParameters
import io.mszymanski.orknux.server.plugin.PluginPermissions
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.plugin.PluginUploadAPI
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * GitHub reaching a workspace, the way issue #274 said it should: as a plugin.
 *
 * There is no GitHub connection and no GitHub trigger. What runs here is the
 * webhook trigger this server already had, guarded by a function the github
 * plugin declares — so what is being tested is that the plugin route carries a
 * real integration end to end, signature and all.
 *
 * **The plugin is a fixture copy now.** The canonical file lives in the
 * orknux-extension repository, so this pins the server's half of the contract
 * and the copy is kept in step with the canonical file by hand. The requests
 * are made anonymously and with the headers GitHub really sends, because that
 * is the only way this endpoint is ever called.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class GithubWebhookTest(
    @Autowired val pluginSources: io.mszymanski.orknux.server.plugin.PluginSources,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val context: WebApplicationContext,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val pluginSettings: PluginParameterSettingRepository,
    @Autowired val pluginParameters: PluginParameters,
    @Autowired val pluginPermissions: PluginPermissions,
    @Autowired val pluginRunner: PluginRunner,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val firings: TriggerFiringRepository,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private lateinit var mockMvc: MockMvc
    private var workspaceId: Long = 0
    private var pluginId: Long = 0

    @BeforeEach
    fun reset() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        firings.deleteAll()
        triggers.deleteAll()
        pluginSettings.deleteAll()
        functions.deleteAll()
        plugins.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        assignments.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        workflows.deleteAll()
        objects.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "platform")).id)

        // Loaded the way an administrator loads it, naming the permission it
        // asks for: a load that did not name TEXT_ENCODING would be refused, so
        // this is also the acceptance being exercised.
        upload.upload(MockMultipartFile("file", "github.js", "text/plain", SOURCE.toByteArray()), null, "TEXT_ENCODING")
        pluginId = requireNotNull(plugins.findAll().single().id)

        tellItTheSecret()
        armWebhook()
    }

    /**
     * The whole of it: GitHub posts a pull request, signed, and a run starts.
     *
     * The signature is computed here with the JDK's own HMAC, so what is being
     * compared is the plugin's longhand SHA-256 against a reference
     * implementation rather than against itself.
     */
    @Test
    fun `a signed pull request starts a run`() {
        val answered = deliver("pull_request", PULL_REQUEST)

        assertThat(answered.status).isEqualTo(202)
        assertThat(answered.contentAsString).contains("\"started\":1")
        assertThat(executions.findAll()).singleElement().satisfies({
            assertThat(it.input).contains("\"full_name\":\"acme/platform\"")
        })
    }

    /**
     * The half without which this endpoint is an unauthenticated remote trigger.
     *
     * One byte of the digest changed, everything else identical — so what is
     * being refused is the signature and not a malformed request.
     */
    @Test
    fun `a delivery whose signature does not check out is refused`() {
        val answered = deliverSigned("pull_request", PULL_REQUEST, tampered(PULL_REQUEST))

        assertThat(answered.status).isEqualTo(401)
        assertThat(executions.findAll()).isEmpty()
        // And the owner is told, because a webhook everybody is being refused
        // from looks exactly like a webhook nobody is calling.
        assertThat(firings.findAll().map { it.outcome }).containsExactly(FiringOutcome.UNAUTHENTICATED)
        assertThat(firings.findAll().single().detail).contains("github_verify did not accept the caller")
    }

    /** An unsigned delivery is a delivery from anybody. */
    @Test
    fun `a delivery with no signature at all is refused`() {
        val answered = deliverSigned("pull_request", PULL_REQUEST, signature = null)

        assertThat(answered.status).isEqualTo(401)
        assertThat(executions.findAll()).isEmpty()
    }

    /**
     * GitHub still sends the old SHA-1 header beside the new one, and a caller
     * who could choose it would be choosing the weaker of the two.
     */
    @Test
    fun `the SHA-1 header GitHub still sends is not accepted in its place`() {
        val answered = mockMvc.perform(
            post("/api/webhooks/github/events")
                .with(anonymous())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature", "sha1=${"0".repeat(40)}")
                .content(PULL_REQUEST),
        ).andReturn().response

        assertThat(answered.status).isEqualTo(401)
    }

    /**
     * The three events the issue named, each signed and each starting a run.
     *
     * They arrive on one path and one trigger, which is how a repository's
     * webhook is actually configured: one URL, several events ticked.
     */
    @Test
    fun `pushes and review comments arrive on the same webhook`() {
        assertThat(deliver("push", PUSH).status).isEqualTo(202)
        assertThat(deliver("pull_request_review_comment", REVIEW_COMMENT).status).isEqualTo(202)

        assertThat(executions.findAll()).hasSize(2)
    }

    /**
     * The gap this issue turned out to be about.
     *
     * GitHub says which event a delivery is in a header, not in the body, so a
     * run handed only the JSON cannot tell a push from a pull request. The
     * headers now arrive under `webhook`, and this is what says so.
     */
    @Test
    fun `the run is handed the header that says which event this is`() {
        deliver("push", PUSH)

        assertThat(executions.findAll()).singleElement().satisfies({
            assertThat(it.input).contains("\"x-github-event\":\"push\"")
            assertThat(it.input).contains("\"path\":\"github/events\"")
        })
    }

    /**
     * And what is written into that row is a description, not a credential.
     *
     * The run's input is stored and shown; a sender that puts a bearer token in
     * an `Authorization` header would otherwise have it kept in the clear beside
     * the payload for ever.
     */
    @Test
    fun `a credential header does not reach the run`() {
        mockMvc.perform(
            post("/api/webhooks/github/events")
                .with(anonymous())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "push")
                .header("X-Hub-Signature-256", signed(PUSH))
                .header("Authorization", "Bearer not-for-keeping")
                .content(PUSH),
        ).andReturn().response

        assertThat(executions.findAll()).singleElement().satisfies({
            assertThat(it.input).doesNotContain("not-for-keeping")
            assertThat(it.input).doesNotContain("authorization")
            // The event is still there, or the filter took the useful half too.
            assertThat(it.input).contains("\"x-github-event\":\"push\"")
        })
    }

    /**
     * A title with an accent in it still verifies.
     *
     * GitHub signs the bytes it sent, which are UTF-8. The body reaches the
     * plugin as text and is encoded again on the way into the digest, so
     * anything that decodes those bytes as something other than UTF-8 anywhere
     * along the way turns every European pull request title into a refused
     * delivery — and only a payload like this one would notice.
     */
    @Test
    fun `a payload that is not plain ASCII still verifies`() {
        assertThat(deliver("pull_request", ACCENTED).status).isEqualTo(202)
    }

    /**
     * What the plugin makes of each of the three, asked the way the endpoint
     * asks it: in the plugin's own sandbox, with what the workspace set.
     */
    @Test
    fun `the plugin names each of the three events`() {
        assertThat(described("push", PUSH)).contains("\"kind\":\"push\"", "\"ref\":\"refs/heads/main\"", "\"commits\":2")
        assertThat(described("pull_request", PULL_REQUEST))
            .contains("\"kind\":\"pull_request\"", "\"number\":7", "\"action\":\"opened\"")
        assertThat(described("pull_request_review_comment", REVIEW_COMMENT))
            .contains("\"kind\":\"review_comment\"", "\"actor\":\"bob\"")
    }

    /**
     * A workspace that has not been told the secret refuses everybody, and says
     * which parameter is missing rather than leaving somebody to guess.
     */
    @Test
    fun `a workspace that has not been told the secret refuses the caller`() {
        pluginSettings.deleteAll()

        assertThat(deliver("pull_request", PULL_REQUEST).status).isEqualTo(401)
        assertThat(firings.findAll().single().detail).contains("has not been told webhookSecret")
    }

    /** One delivery, signed, with the headers GitHub sends. */
    private fun deliver(event: String, body: String) = deliverSigned(event, body, signed(body))

    /** The same, with the signature decided by the caller — or left off entirely. */
    private fun deliverSigned(event: String, body: String, signature: String?) = mockMvc.perform(
        post("/api/webhooks/github/events")
            .with(anonymous())
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-GitHub-Event", event)
            .header("X-GitHub-Delivery", "72d3162e-cc78-11e3-81ab-4c9367dc0958")
            .content(body)
            .also { request -> signature?.let { request.header("X-Hub-Signature-256", it) } },
    ).andReturn().response

    private fun signed(body: String): String = "sha256=" + digestOf(body)

    /** The same delivery with one digit of its digest turned over. */
    private fun tampered(body: String): String {
        val digest = digestOf(body)
        val flipped = if (digest.first() == '0') '1' else '0'
        return "sha256=$flipped${digest.drop(1)}"
    }

    private fun digestOf(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** `github_describe`, asked the way `WebhookAPI` asks `github_verify`. */
    private fun described(event: String, body: String): String {
        val plugin = requireNotNull(plugins.findById(pluginId).orElse(null))
        val headers = """{"x-github-event":"$event","x-github-delivery":"d-1"}"""
        val answered = pluginRunner.call(
            pluginSources.sourceOf(plugin),
            "describe",
            listOf(headers, body),
            pluginParameters.settingsFor(plugin, workspaceId),
            pluginPermissions.grantedTo(plugin),
        )
        return (answered as ScriptResult.Returned).json.orEmpty()
    }

    /**
     * The secret, in a variable the plugin's parameter points at.
     *
     * It cannot be typed in: the plugin declares `webhookSecret` as a secret, so
     * a variable is the only answer the parameter takes.
     */
    private fun tellItTheSecret() {
        val catalogId = requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "github")).id)
        val variableId = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = workspaceId,
                    catalogId = catalogId,
                    name = "githubWebhookSecret",
                    type = VariableType.STRING,
                    kind = VariableKind.SECRET,
                    value = SECRET,
                ),
            ).id,
        )

        graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId,
                name: "webhookSecret", variableId: $variableId
              ) { missing }
            }
            """,
        ).execute().path("setPluginParameter.missing").entityList(String::class.java).hasSize(0)
    }

    /**
     * The shape, the trigger and a workflow to start — the setting-up a person
     * does once per repository.
     *
     * The shape is what the three payloads have in common, which is what makes
     * one trigger enough for all of them.
     */
    private fun armWebhook() {
        val repositoryId = objectOf("Repository", "full_name")
        val senderId = objectOf("Sender", "login")
        val deliveryId = graphQlTester.document(
            """
            mutation {
              createObject(input: {
                workspaceId: $workspaceId, name: "GithubDelivery",
                properties: [
                  { name: "repository", kind: OBJECT, refObjectId: $repositoryId },
                  { name: "sender", kind: OBJECT, refObjectId: $senderId }
                ]
              }) { id }
            }
            """,
        ).execute().path("createObject.id").entity(Long::class.java).get()

        val verify = requireNotNull(
            functions.findAll().single { it.scope == FunctionScope.PLUGIN && it.name == "github_verify" }.id,
        )

        val triggerId = graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "GitHub", type: WEBHOOK,
                webhookPath: "github/events", objectId: $deliveryId,
                authType: FUNCTION, authFunctionId: $verify
              }) { id }
            }
            """,
        ).execute().path("createTrigger.id").entity(Long::class.java).get()

        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Watch" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "trigger", kind: TRIGGER, name: "GitHub", triggerId: $triggerId, x: 0, y: 0 }],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute().errors().verify()

        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute().errors().verify()
    }

    private fun objectOf(name: String, property: String): Long = graphQlTester.document(
        """
        mutation {
          createObject(input: {
            workspaceId: $workspaceId, name: "$name",
            properties: [{ name: "$property", kind: STRING }]
          }) { id }
        }
        """,
    ).execute().path("createObject.id").entity(Long::class.java).get()

    private companion object {

        /** Not a real one, and it never leaves the test database. */
        const val SECRET = "it-came-from-github"

        /*
         * A copy of the plugin, not the plugin. The file an operator loads
         * lives in the orknux-extension repository now, so what this pins is
         * the server side of the contract: the webhook vocabulary the plugin
         * relies on. When the canonical file changes, this copy is brought in
         * step by hand - and a change that breaks this test is exactly the
         * change the plugin's users need to hear about.
         */
        val SOURCE: String = requireNotNull(GithubWebhookTest::class.java.getResource("/plugins/github.js")) {
            "the plugin fixture is not on the test classpath"
        }.readText()

        /*
         * Cut down from real deliveries: what the trigger's shape asks for, plus
         * the fields the plugin reads. Kept on one line each, because the bytes
         * are what is signed and a reformat that changed them would change the
         * digest along with it.
         */
        const val PULL_REQUEST = """{"action":"opened","number":7,""" +
            """"pull_request":{"title":"Retry the flaky probe","html_url":"https://github.com/acme/platform/pull/7"},""" +
            """"repository":{"full_name":"acme/platform"},"sender":{"login":"alice"}}"""

        const val REVIEW_COMMENT = """{"action":"created",""" +
            """"comment":{"body":"This can be a when.","html_url":"https://github.com/acme/platform/pull/7#r1"},""" +
            """"pull_request":{"number":7},""" +
            """"repository":{"full_name":"acme/platform"},"sender":{"login":"bob"}}"""

        const val PUSH = """{"ref":"refs/heads/main","compare":"https://github.com/acme/platform/compare/a...b",""" +
            """"commits":[{"id":"a"},{"id":"b"}],"head_commit":{"message":"Tidy the runner"},""" +
            """"repository":{"full_name":"acme/platform"},"sender":{"login":"alice"}}"""

        /** The same again with a title only UTF-8 gets right. */
        const val ACCENTED = """{"action":"opened","number":8,""" +
            """"pull_request":{"title":"Poprawić literówkę w opisie","html_url":"https://github.com/acme/platform/pull/8"},""" +
            """"repository":{"full_name":"acme/platform"},"sender":{"login":"michał"}}"""
    }
}
