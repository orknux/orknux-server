package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.plugin.Plugin
import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginFunctionRegistry
import io.mszymanski.orknux.server.plugin.PluginRepository
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
import io.mszymanski.orknux.workflow.script.PluginInspection
import io.mszymanski.orknux.workflow.script.PluginRunner
import org.assertj.core.api.Assertions.assertThat
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

/**
 * The one endpoint here the open internet can reach.
 *
 * What is asserted is mostly what a caller is *not* told. A webhook path is a
 * name somebody chose, so a caller who can tell "there is nothing here" apart
 * from "there is something here and you sent it the wrong thing" can walk a list
 * of guesses and learn which webhooks this installation has armed. Every answer
 * that is not a start has to be the same answer.
 *
 * The requests are made anonymously on purpose: signing in is exactly what a
 * build server calling a webhook cannot do, and the leak is only a leak for a
 * caller who has not.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WebhookAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val context: WebApplicationContext,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val firings: TriggerFiringRepository,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val plugins: PluginRepository,
    @Autowired val registry: PluginFunctionRegistry,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val pluginRunner: PluginRunner,
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
    private var triggerId: Long = 0
    private var objectId: Long = 0

    @BeforeEach
    fun reset() {
        // Assembled by hand rather than auto-configured, because Boot 4 keeps
        // `@AutoConfigureMockMvc` in a module nothing here depends on. The
        // security chain is applied on top of it on purpose: a webhook is
        // called by somebody who has not signed in, and going round the filters
        // would test the endpoint under conditions it never meets.
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        firings.deleteAll()
        triggers.deleteAll()
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
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Triage" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        objectId = graphQlTester.document(
            """
            mutation {
              createObject(input: {
                workspaceId: $workspaceId, name: "Ticket",
                properties: [{ name: "id", kind: STRING }]
              }) { id }
            }
            """,
        ).execute().path("createObject.id").entity(Long::class.java).get()

        triggerId = graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Ticket Created", type: WEBHOOK,
                webhookPath: "zendesk/ticket-created", objectId: $objectId
              }) { id }
            }
            """,
        ).execute().path("createTrigger.id").entity(Long::class.java).get()

        instance(workflowId, triggerId)
    }

    /**
     * The bug this class was written for: an armed path answered a junk body
     * 400, and a path nothing listens on answered 404, so the difference between
     * the two was a webhook directory anybody could read off the wire.
     */
    @Test
    fun `a junk body is answered the same as a path nothing listens on`() {
        val armed = call("zendesk/ticket-created", "x")
        val unknown = call("zendesk/does-not-exist", "x")

        assertThat(armed.status).isEqualTo(404)
        assertThat(unknown.status).isEqualTo(404)
        // Byte for byte, or the status alone was never the whole answer.
        assertThat(armed.contentAsString).isEqualTo(unknown.contentAsString)
    }

    @Test
    fun `a body that is not the shape the trigger promised is answered the same way`() {
        val wrongShape = call("zendesk/ticket-created", """{"subject":"printer on fire"}""")
        val unknown = call("zendesk/does-not-exist", """{"subject":"printer on fire"}""")

        assertThat(wrongShape.status).isEqualTo(404)
        assertThat(wrongShape.contentAsString).isEqualTo(unknown.contentAsString)
    }

    /**
     * The half of this that a fix could quietly break: 404 for everything would
     * pass every assertion above.
     */
    @Test
    fun `a well-formed call still starts the workflow`() {
        val accepted = call("zendesk/ticket-created", """{"id":"T-1"}""")

        assertThat(accepted.status).isEqualTo(202)
        assertThat(accepted.contentAsString).contains("\"started\":1")
        assertThat(executions.findAll()).singleElement().satisfies({
            assertThat(it.input).contains("\"id\":\"T-1\"")
        })
    }

    /**
     * The switch, on the one firing path where it is not a repository query.
     *
     * An incoming trigger and a scheduled one are found by a finder that asks
     * for enabled ones; a webhook is found by its path, which is unique across
     * the installation, and the switch is read afterwards. So this is the path
     * where honouring it is a line of code somebody could delete, and it is
     * covered here rather than assumed.
     *
     * Answered exactly as an unknown path is, for the reason the rest of this
     * class exists: telling a caller "this exists but is switched off" is the
     * same directory leak by another name.
     */
    @Test
    fun `a switched-off webhook is answered the same as a path nothing listens on`() {
        graphQlTester.document("""mutation { setTriggerEnabled(id: $triggerId, enabled: false) { enabled } }""")
            .execute()

        val off = call("zendesk/ticket-created", """{"id":"T-1"}""")
        val unknown = call("zendesk/does-not-exist", """{"id":"T-1"}""")

        assertThat(off.status).isEqualTo(404)
        assertThat(off.contentAsString).isEqualTo(unknown.contentAsString)
        assertThat(executions.findAll()).isEmpty()
    }

    /**
     * What the caller is told and what the owner is told are different things.
     *
     * The caller gets a 404 that says nothing. The person who owns the trigger
     * still gets the reason in its log, because "somebody is calling this with
     * rubbish" and "nobody is calling this at all" look identical everywhere
     * else, and telling them apart is the whole point of the log.
     */
    @Test
    fun `the owner still finds out that a junk body arrived`() {
        call("zendesk/ticket-created", "x")

        // Read from the repository rather than through the trigger's log query:
        // the anonymous call above leaves the security context anonymous, and
        // what is being checked is that the line was written at all.
        assertThat(firings.findAll()).singleElement().satisfies({
            assertThat(it.triggerId).isEqualTo(triggerId)
            assertThat(it.outcome).isEqualTo(FiringOutcome.FAILED)
            assertThat(it.detail).isEqualTo("The request body was not JSON")
        })
    }

    /**
     * A webhook may be guarded by a function a plugin declared.
     *
     * Two things were wrong at once, and either alone would have made the
     * gatekeeper useless. Choosing one was refused as "no function chosen",
     * because the gate compared the function's workspace with the trigger's and
     * a plugin's function belongs to no workspace. And had one been chosen, the
     * endpoint would have run its source column — which for a plugin holds a
     * note saying where the implementation lives, not code — so the gatekeeper
     * would have thrown, and a gatekeeper that throws refuses everybody.
     */
    @Test
    fun `a webhook guarded by a plugin's function lets the right caller in`() {
        val declared = pluginFunction()
        val guarded = graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Guarded", type: WEBHOOK,
                webhookPath: "zendesk/guarded", objectId: $objectId,
                authType: FUNCTION, authFunctionId: $declared
              }) { id }
            }
            """,
        ).execute().path("createTrigger.id").entity(Long::class.java).get()

        val allowed = call("zendesk/guarded", """{"id":"T-1","token":"let-me-in"}""")
        assertThat(allowed.status).isEqualTo(202)

        val refused = call("zendesk/guarded", """{"id":"T-2","token":"guess"}""")
        assertThat(refused.status).isEqualTo(404) // as an unknown path is, #588

        // The reason is the gatekeeper's answer, not a crash: a note run as a
        // script would have written "SyntaxError" here for both calls.
        assertThat(firings.findAll().filter { it.triggerId == guarded }.map { it.detail })
            .contains("gatekeeper_verify did not accept the caller")
    }

    /**
     * Issue #165: the gatekeeper cannot be deleted out from under the webhook.
     *
     * `deleteFunction` guarded actions and conditions and not this, so the
     * function a webhook authenticates with could be deleted while the webhook
     * went on pointing at it - and the endpoint reported it at request time,
     * into a firing log, with the caller refused. `TriggerAPI` will not let a
     * webhook be *saved* with a missing gatekeeper; deleting the function was
     * the door that got round that rule.
     */
    @Test
    fun `the function a webhook authenticates with cannot be deleted`() {
        val guard = booleanFunction("letIn")
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Guarded", type: WEBHOOK,
                webhookPath: "zendesk/guarded", objectId: $objectId,
                authType: FUNCTION, authFunctionId: $guard
              }) { id }
            }
            """,
        ).execute().path("createTrigger.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { deleteFunction(id: $guard) }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message).isEqualTo("letIn is called by the webhook Guarded")
            }

        // Nothing was deleted, so the webhook still has something to ask.
        assertThat(functions.findAll().map { it.name }).contains("letIn")
    }

    /**
     * And the guard is a guard, not a ban.
     *
     * A function nothing authenticates with deletes exactly as it always did.
     * Said out loud because a refusal that fires on everything is how this
     * change would go wrong.
     */
    @Test
    fun `a function no webhook authenticates with still deletes`() {
        val spare = booleanFunction("unused")

        graphQlTester.document("""mutation { deleteFunction(id: $spare) }""")
            .execute().path("deleteFunction").entity(Boolean::class.java).isEqualTo(true)
    }

    /**
     * Issue #165: and neither can the shape it answers to.
     *
     * The one object reference that is not an annotation. A function's parameter
     * losing its object degrades a signature somebody reads; a webhook losing
     * its shape turns every arriving request into the 404 above - the same
     * answer a path nobody listens on gives - so the webhook does not look
     * broken to its caller, it looks absent. The other object references are
     * deliberately left dangling; `deleteObject` says which and why.
     */
    @Test
    fun `the object a webhook answers to cannot be deleted`() {
        graphQlTester.document("""mutation { deleteObject(id: $objectId) }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message)
                    .isEqualTo("Ticket is used by the webhook Ticket Created, so it cannot be deleted")
            }

        // And the webhook still answers, which is the thing being protected.
        assertThat(call("zendesk/ticket-created", """{"id":"T-1"}""").status).isEqualTo(202)
    }

    /** An object no webhook answers to still deletes. */
    @Test
    fun `an object no webhook answers to still deletes`() {
        val spare = graphQlTester.document(
            """
            mutation {
              createObject(input: {
                workspaceId: $workspaceId, name: "Spare",
                properties: [{ name: "id", kind: STRING }]
              }) { id }
            }
            """,
        ).execute().path("createObject.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { deleteObject(id: $spare) }""")
            .execute().path("deleteObject").entity(Boolean::class.java).isEqualTo(true)
    }

    /** A workspace function that can say yes or no, which is all a gatekeeper has to do. */
    private fun booleanFunction(name: String): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: {
            workspaceId: $workspaceId, name: "$name", returnType: BOOLEAN, params: []
          }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    /** A plugin loaded from its own source, and its functions materialised. */
    private fun pluginFunction(): Long {
        val read = pluginRunner.inspect(GATEKEEPER) as PluginInspection.Read
        val plugin = plugins.save(
            Plugin(
                key = read.id,
                name = "gatekeeper",
                filename = "gatekeeper.js",
                source = GATEKEEPER,
                sizeBytes = GATEKEEPER.length.toLong(),
                apiVersion = read.apiVersion,
                sha256 = "0".repeat(64),
                declaredFunctions = declarations.validated(read.functions),
                declaredParameters = declarations.validatedParameters(read.parameters),
            ),
        )
        registry.reconcile(plugin)
        return requireNotNull(functions.findAll().first { it.scope == FunctionScope.PLUGIN }.id)
    }

    /** One anonymous call, the way anything out there would make it. */
    private fun call(path: String, body: String) = mockMvc.perform(
        post("/api/webhooks/$path")
            .with(anonymous())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    ).andReturn().response

    /** Gives a workflow a trigger node instancing [trigger], and publishes it. */
    private fun instance(workflow: Long, trigger: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflow, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Ticket Created", triggerId: $trigger, x: 0, y: 0 }
                ],
                edges: []
              }) { nodes { key triggerId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].triggerId").entity(Long::class.java).isEqualTo(trigger)

        // A trigger runs the published copy; a graph that was only saved is one
        // somebody is still drawing.
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflow) { status } }""",
        ).execute()
    }

    private companion object {
        val GATEKEEPER = """
            export default class Gatekeeper extends OrknuxPlugin {
              id() { return 'gatekeeper'; }
              apiVersion() { return 1; }

              functions() {
                return [
                  new OrknuxFunction({
                    name: 'verify',
                    params: [{ name: 'body', type: 'map' }],
                    returnType: 'boolean',
                    run: (body) => body !== null && body.token === 'let-me-in',
                  }),
                ];
              }
            }
        """.trimIndent()
    }
}
