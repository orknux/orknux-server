package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleRepository
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.plugin.Plugin
import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginFunctionRegistry
import io.mszymanski.orknux.server.plugin.PluginParameterSettingRepository
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.trigger.FiringOutcome
import io.mszymanski.orknux.server.trigger.TriggerFiringRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
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
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import io.mszymanski.orknux.workflow.script.PluginInspection
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Microsoft Teams, both ways, through the teams plugin (in the orknux-extension repository).
 *
 * The point of this class is that nothing under `app` or `modules` knows the
 * word Teams. Receiving is a webhook trigger whose gatekeeper is one of the
 * plugin's functions; sending is an HTTP request action against Graph, with the
 * token in a workspace variable and the call through whatever proxy the rules
 * name. If a change to any of those three general mechanisms would break a Teams
 * installation, it breaks here.
 *
 * The plugin is a fixture copy: the canonical file lives in the
 * orknux-extension repository now, and the copy here is kept in step with it
 * by hand so this stays a test of the mechanisms a Teams installation stands
 * on.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TeamsPluginTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val context: WebApplicationContext,
    @Autowired val plugins: PluginRepository,
    @Autowired val pluginSettings: PluginParameterSettingRepository,
    @Autowired val registry: PluginFunctionRegistry,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val pluginRunner: PluginRunner,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val firings: TriggerFiringRepository,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val proxyRules: ProxyRuleRepository,
    @Autowired val router: ProxyRouter,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private lateinit var mockMvc: MockMvc
    private lateinit var graph: HttpServer
    private lateinit var proxy: HttpServer

    /** What the stub Graph endpoint was sent, in the order the runs sent it. */
    private val posted = CopyOnWriteArrayList<String>()

    /** The Authorization header each of those carried, to prove the variable was read. */
    private val authorised = CopyOnWriteArrayList<String>()

    /** The request line the stub proxy saw, which is absolute only when it was proxied. */
    private val proxied = CopyOnWriteArrayList<String>()

    private var workspaceId: Long = 0
    private var pluginId: Long = 0
    private var tokenVariableId: Long = 0

    @BeforeEach
    fun reset() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        firings.deleteAll()
        triggers.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        actions.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        objects.deleteAll()
        pluginSettings.deleteAll()
        functions.deleteAll()
        plugins.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        proxyRules.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        // The rules are compiled once and cached; a wipe that does not say so
        // leaves the previous test's proxy in the router.
        router.reload()

        posted.clear()
        authorised.clear()
        proxied.clear()

        graph = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        graph.createContext("/") { exchange ->
            posted += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            authorised += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            respond(exchange, """{"id":"1700000000001"}""")
        }
        graph.start()

        proxy = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        proxy.createContext("/") { exchange ->
            proxied += exchange.requestURI.toString()
            respond(exchange, """{"id":"through-the-proxy"}""")
        }
        proxy.start()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        pluginId = loadPlugin()
        tokenVariableId = secretVariable("TEAMS_GRAPH_TOKEN", "Bearer graph-token-9If2")
        /*
         * A workspace answers a plugin's parameters once, and it has to be
         * before any of its functions will run — including the ones that read
         * none of them. A required parameter nobody has answered is a plugin
         * that cannot work, and the server refuses the call rather than running
         * it half configured.
         */
        setParameters()
    }

    /**
     * The stubs go, and so does the webhook this class armed.
     *
     * Cleaning up afterwards as well as before, which most classes here do not
     * need to: a trigger authenticated by a plugin's function is a foreign key
     * from `workflow_trigger` into `workflow_function`, so leaving one behind
     * makes the next class that wipes the plugins fail on a constraint it has
     * nothing to do with.
     */
    @AfterEach
    fun stop() {
        graph.stop(0)
        proxy.stop(0)

        firings.deleteAll()
        triggers.deleteAll()
    }

    /**
     * The receiving half, end to end: Teams posts, the plugin says it is really
     * Teams, and a workflow runs.
     *
     * The signature is computed here with the JDK's own HMAC rather than with
     * anything the plugin shares, so the SHA-256 written out in the plugin is
     * checked against a second implementation rather than against itself.
     */
    @Test
    fun `a message in Teams starts a workflow`() {
        armWebhook()

        val answer = call(ACTIVITY, signature(ACTIVITY))

        assertThat(answer.status).isEqualTo(202)
        assertThat(executions.findAll()).singleElement().satisfies({
            assertThat(it.input).contains("deploy the release please")
        })
    }

    /**
     * And the gate is a gate.
     *
     * A caller who does not hold the token Teams handed out is refused, which is
     * the whole reason a webhook trigger may be pointed at the open internet.
     */
    @Test
    fun `a caller without the workspace's token is refused`() {
        armWebhook()

        val answer = call(ACTIVITY, signature(ACTIVITY, key = "some-other-installations-token"))

        // 404, as an unknown path answers: a refusal that says the path exists tells a prober where to aim. #591.
        assertThat(answer.status).isEqualTo(404)
        assertThat(executions.findAll()).isEmpty()
        assertThat(firings.findAll().map { it.outcome }).contains(FiringOutcome.UNAUTHENTICATED)
    }

    /**
     * The signature covers what arrived, and a signature that covered anything
     * less would pass the two tests above.
     *
     * The body is edited between the signing and the sending, which is the shape
     * of the attack a signature exists for: the token is right, the envelope is
     * right, and one word of the instruction is not the one that was signed.
     */
    @Test
    fun `a body that was edited on the way is refused`() {
        armWebhook()

        val edited = ACTIVITY.replace("deploy the release please", "delete the release please")
        val answer = call(edited, signature(ACTIVITY))

        assertThat(answer.status).isEqualTo(404)
        assertThat(executions.findAll()).isEmpty()
    }

    /**
     * The token has nowhere to live but an encrypted column, and now it has one.
     *
     * Typing it in used to be refused, because what was typed was stored as
     * typed and shown back on the page - a fact about the column rather than
     * about secrets. A secret now lands in the column that encrypts, and
     * nothing carries it back out: the screen is told that a value is set and
     * never what it is. Checked as the operator meets it rather than as a
     * property of the declaration, because the declaration is the plugin's word
     * and this is the server's.
     */
    @Test
    fun `the token can be typed into the plugin's settings, and never comes back out`() {
        val answered = graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId,
                name: "webhookSecret", literal: "pasted-in-here"
              ) { missing parameters { name secret secretSet literal } }
            }
            """,
        ).execute()

        assertThat(answered.path("setPluginParameter.missing").entityList(String::class.java).get())
            .doesNotContain("webhookSecret")

        val rows = answered.path("setPluginParameter.parameters").entityList(Map::class.java).get()
        val token = rows.single { it["name"] == "webhookSecret" }
        assertThat(token["secret"]).isEqualTo(true)
        assertThat(token["secretSet"]).isEqualTo(true)
        // What was typed is not handed back, to this screen or any other.
        assertThat(token["literal"]).isNull()
    }

    /** What was said, with the webhook's own mention taken off it. */
    @Test
    fun `the plugin reads the message and who sent it`() {
        val activity = mapper.readTree(ACTIVITY)

        assertThat(callPlugin("text", mapper.writeValueAsString(activity))).isEqualTo("deploy the release please")

        val sender = mapper.readTree(callPlugin("sender", mapper.writeValueAsString(activity)))
        assertThat(sender.get("user").asString()).isEqualTo("Alice Adams")
        assertThat(sender.get("teamId").asString()).isEqualTo("19:team-one@thread.tacv2")
        assertThat(sender.get("channelId").asString()).isEqualTo("19:channel-one@thread.tacv2")
        assertThat(sender.get("messageId").asString()).isEqualTo("1700000000000")
    }

    /** The addresses, so nobody has to keep Graph's spelling in a workflow's fields. */
    @Test
    fun `the plugin builds the Graph addresses`() {
        assertThat(callPlugin("channelUrl", "\"19:team\"", "\"19:channel\""))
            .isEqualTo("https://graph.microsoft.com/v1.0/teams/19%3Ateam/channels/19%3Achannel/messages")
        assertThat(callPlugin("replyUrl", "\"19:team\"", "\"19:channel\"", "\"17000\""))
            .isEqualTo(
                "https://graph.microsoft.com/v1.0/teams/19%3Ateam/channels/19%3Achannel/messages/17000/replies",
            )
    }

    /**
     * The sending half, end to end: the plugin shapes the message and the
     * request action carries it, with the token read out of the workspace's
     * variables at the moment of the call.
     *
     * Two nodes rather than one, because that is the arrangement a workspace
     * actually builds — the plugin's function runs inside the workflow here, not
     * in the test's own hand.
     */
    @Test
    fun `a workflow sends a Teams message`() {
        val runId = sendingWorkflow()

        assertThat(steps.findAll().filter { it.executionId == runId }.map { it.status })
            .containsOnly(StepStatus.COMPLETED)
        assertThat(posted).containsExactly(
            """{"body":{"contentType":"text","content":"The release is out."}}""",
        )
        // The header is a reference to a variable, so this is the encrypted
        // column having been read at run time and not a literal on the action.
        assertThat(authorised).containsExactly("Bearer graph-token-9If2")
    }

    /**
     * Issue #144 made Slack go through the proxy rules. Teams is not allowed
     * round them either, and this is the assertion that says so: with a rule
     * matching the Graph host, the request arrives at the proxy with the whole
     * URL on its request line and the endpoint itself is never called.
     */
    @Test
    fun `a Teams message goes through the proxy the rules name`() {
        graphQlTester.document(
            """
            mutation {
              createProxyRule(input: {
                name: "Microsoft", pattern: "${graphHost()}",
                proxyHost: "${proxy.address.hostString}", proxyPort: ${proxy.address.port}
              }) { id }
            }
            """,
        ).execute().path("createProxyRule.id").hasValue()

        sendingWorkflow()

        assertThat(proxied).containsExactly("${graphUrl()}/v1.0/teams/T/channels/C/messages")
        assertThat(posted).isEmpty()
    }

    /**
     * Builds the two-node workflow that sends, runs it, and answers with the run.
     *
     * The message is composed by the plugin's `message` function and handed on as
     * the request's body: the composing node names its output and the request
     * node refers to it, which is how a node says where its parameters come from.
     */
    private fun sendingWorkflow(): Long {
        val compose = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Compose", type: EXECUTE, subtype: FUNCTION,
                functionId: ${pluginFunction("message")},
                mappings: [
                  { argument: "text", expression: "The release is out." },
                  { argument: "html", expression: "false" }
                ]
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()

        val post = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Post", type: EXECUTE, subtype: HTTP_REQUEST,
                url: "${graphUrl()}/v1.0/teams/T/channels/C/messages", method: "POST",
                headerRows: [
                  { name: "Content-Type", value: "application/json" },
                  { name: "Authorization", variableId: "$tokenVariableId" }
                ]
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()

        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Announce" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "compose", kind: ACTION, name: "Compose", actionId: $compose,
                    outputName: "message", x: 0, y: 0 },
                  { key: "post", kind: ACTION, name: "Post", actionId: $post, x: 200, y: 0,
                    mappings: [{ name: "body", expression: "message", mode: REFERENCE }] }
                ],
                edges: [{ source: "compose", target: "post" }]
              }) { nodes { key } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes").entityList(Any::class.java).hasSize(2)

        return graphQlTester.document(
            """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId) { id } }""",
        ).execute().path("startExecution.id").entity(Long::class.java).get()
    }

    /**
     * A webhook trigger guarded by the plugin, instanced by a workflow.
     *
     * The shape is the smallest thing that says "this is a Teams activity"; the
     * trigger refuses anything else with the same 404 a path nobody listens on
     * gives, which is `WebhookAPITest`'s subject rather than this one's.
     */
    private fun armWebhook() {
        val objectId = graphQlTester.document(
            """
            mutation {
              createObject(input: {
                workspaceId: $workspaceId, name: "TeamsActivity",
                properties: [
                  { name: "type", kind: STRING },
                  { name: "id", kind: STRING },
                  { name: "text", kind: STRING }
                ]
              }) { id }
            }
            """,
        ).execute().path("createObject.id").entity(Long::class.java).get()

        val triggerId = graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Mentioned in Teams", type: WEBHOOK,
                webhookPath: "teams/mentioned", objectId: $objectId,
                authType: FUNCTION, authFunctionId: ${pluginFunction("verify")}
              }) { id }
            }
            """,
        ).execute().path("createTrigger.id").entity(Long::class.java).get()

        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Triage" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Mentioned", triggerId: $triggerId, x: 0, y: 0 }
                ],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute()

        // A trigger runs the published copy, so a graph that was only saved is
        // one somebody is still drawing.
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute()
    }

    /**
     * Answers the plugin's two parameters: the token by pointing at a variable,
     * which is the only way a secret one may be answered, and the webhook's own
     * name by typing it, which is the ordinary way.
     */
    private fun setParameters() {
        val secret = secretVariable("TEAMS_WEBHOOK_TOKEN", TOKEN)
        graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId,
                name: "webhookSecret", variableId: $secret
              ) { missing }
            }
            """,
        ).execute().path("setPluginParameter.missing").entityList(String::class.java).hasSize(0)

        graphQlTester.document(
            """
            mutation {
              setPluginParameter(
                workspaceId: $workspaceId, pluginId: $pluginId,
                name: "webhookName", literal: "Orknux"
              ) { missing }
            }
            """,
        ).execute()
    }

    /** The plugin as it is shipped, loaded and its functions materialised. */
    private fun loadPlugin(): Long {
        val read = pluginRunner.inspect(SOURCE) as PluginInspection.Read
        val plugin = plugins.save(
            Plugin(
                key = read.id,
                name = "teams",
                filename = "teams.js",
                source = SOURCE,
                sizeBytes = SOURCE.length.toLong(),
                apiVersion = read.apiVersion,
                sha256 = "0".repeat(64),
                declaredFunctions = declarations.validated(read.functions),
                declaredParameters = declarations.validatedParameters(read.parameters),
            ),
        )
        registry.reconcile(plugin)
        return requireNotNull(plugin.id)
    }

    /** One of the plugin's functions, by the name it declared rather than the prefixed one. */
    private fun pluginFunction(declared: String): Long = requireNotNull(
        functions.findAll().single { it.scope == FunctionScope.PLUGIN && it.name == "teams_$declared" }.id,
    )

    /** Calls one of the plugin's functions directly, the way a run would. */
    private fun callPlugin(name: String, vararg arguments: String): String {
        val settings = """{"webhookSecret":${quoted(TOKEN)},"webhookName":"Orknux"}"""
        val result = pluginRunner.call(SOURCE, name, arguments.toList(), settings, emptySet())
        assertThat(result).isInstanceOf(ScriptResult.Returned::class.java)
        val json = requireNotNull((result as ScriptResult.Returned).json)
        // A function returning a string answers with a JSON string; reading it
        // back is what a node's mapping does with it too.
        return runCatching { mapper.readTree(json).takeIf { it.isString }?.asString() }.getOrNull() ?: json
    }

    private fun secretVariable(name: String, held: String): Long {
        val catalogId = requireNotNull(
            catalogs.findAll().firstOrNull { it.workspaceId == workspaceId }
                ?: catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "tokens")),
        ).id
        return requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = workspaceId,
                    catalogId = requireNotNull(catalogId),
                    name = name,
                    type = VariableType.STRING,
                    kind = VariableKind.SECRET,
                    value = held,
                ),
            ).id,
        )
    }

    /** One anonymous call, the way Teams itself would make it. */
    private fun call(body: String, signature: String) = mockMvc.perform(
        post("/api/webhooks/teams/mentioned")
            .with(anonymous())
            .header("Authorization", "HMAC $signature")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    ).andReturn().response

    /** What Teams puts in the Authorization header: the body's HMAC, base64. */
    private fun signature(body: String, key: String = "a-teams-outgoing-webhook-token"): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun quoted(text: String): String = mapper.writeValueAsString(text)

    private fun graphUrl() = "http://${graph.address.hostString}:${graph.address.port}"

    /**
     * The stub's host and port as a pattern, quoted so the dots in an address
     * stay literal. Doubled, because this is going through a GraphQL string
     * literal before it reaches the regular expression.
     */
    private fun graphHost() = """\\Q${graph.address.hostString}:${graph.address.port}\\E"""

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {

        /**
         * A copy of the plugin, not the plugin.
         *
         * The file an operator loads lives in the orknux-extension repository
         * now, so this cannot read the deliverable itself any more. What the
         * copy pins is the server side of the contract the plugin stands on;
         * when the canonical file changes, it is brought in step by hand.
         */
        val SOURCE: String = requireNotNull(TeamsPluginTest::class.java.getResource("/plugins/teams.js")) {
            "the plugin fixture is not on the test classpath"
        }.readText()

        /** What Teams hands out when an outgoing webhook is created: base64. */
        val TOKEN: String = Base64.getEncoder()
            .encodeToString("a-teams-outgoing-webhook-token".toByteArray(StandardCharsets.UTF_8))

        /** One activity, as Teams posts it when somebody mentions the webhook. */
        val ACTIVITY = """
            {"type":"message","id":"1700000000000","timestamp":"2026-08-27T09:00:00.000Z",
            "serviceUrl":"https://smba.trafficmanager.net/emea/","channelId":"msteams",
            "from":{"id":"29:1alice","name":"Alice Adams","aadObjectId":"aad-alice"},
            "conversation":{"isGroup":true,"id":"19:channel-one@thread.tacv2"},
            "recipient":{"id":"28:orknux","name":"Orknux"},
            "text":"<at>Orknux</at> deploy the release please",
            "channelData":{"teamsChannelId":"19:channel-one@thread.tacv2",
            "teamsTeamId":"19:team-one@thread.tacv2","tenant":{"id":"tenant-acme"}}}
        """.trimIndent().replace("\n", "")
    }
}
