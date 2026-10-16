package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.plugin.Plugin
import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginFunctionRegistry
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.PluginInspection
import io.mszymanski.orknux.workflow.script.PluginRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The action catalogue. What an action needs and what it produces are read off
 * its settings rather than stored, so most of what is asserted here is that
 * those come out right for each kind of action.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ActionAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val plugins: PluginRepository,
    @Autowired val registry: PluginFunctionRegistry,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val pluginRunner: PluginRunner,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun reset() {
        actions.deleteAll()
        // A condition that asks a function holds it, so conditions go first.
        conditions.deleteAll()
        functions.deleteAll()
        plugins.deleteAll()
        workspaceConnections.deleteAll()
        connections.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Slack", type: SLACK
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
    }

    @Test
    fun `a message action offers what a send is made of, and answers with where it landed`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Send Slack Notification", type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $connectionId, connectionAction: SEND_MESSAGE,
                content: "Your request is approved",
                targetName: "#notifications"
              }) { subtypeLabel connectionName inputParams { display } outputParams { display } }
            }
            """,
        ).execute()
            .path("createAction.subtypeLabel").entity(String::class.java).isEqualTo("Outgoing Connection")
            .path("createAction.connectionName").entity(String::class.java).isEqualTo("Slack")
            .path("createAction.inputParams[*].display").entityList(String::class.java)
            .containsExactly("target: string", "content: string", "threadTs: string")
            .path("createAction.outputParams[*].display").entityList(String::class.java)
            .containsExactly("channel: string", "ts: string")

        assertThat(audit.findAll().map { it.message }).contains("Action Send Slack Notification created")
    }

    /**
     * A workflow's own action is its own: off the workspace's list, on its.
     *
     * "Custom" is a definition made from a node, for that one node, rather
     * than a name added to a library everything in the workspace can see. Two
     * lists rather than one flag read at every call site: the workspace's page
     * asks for what it shares, the editor asks for both, and neither has to
     * know about the other's case.
     */
    @Test
    fun `a workflow owns the actions made from its nodes, and the workspace list leaves them out`() {
        val workflowId = graphQlTester.document(
            """
            mutation {
              createWorkflow(input: { workspaceId: $workspaceId, name: "Nightly" }) { workflowId }
            }
            """,
            // The definition's id, not the assignment's: an owned action points
            // at `workflow`, and the two ids are only ever the same number by
            // accident - which is how this passed here and failed on a runner
            // whose sequences had drifted apart.
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Shared Call", type: EXECUTE, subtype: HTTP_REQUEST,
                url: "https://example.test", method: "GET"
              }) { workflowId }
            }
            """,
        ).execute().path("createAction.workflowId").valueIsNull()

        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, workflowId: $workflowId,
                name: "This Node's Call", type: EXECUTE, subtype: HTTP_REQUEST,
                url: "https://example.test", method: "GET"
              }) { workflowId }
            }
            """,
        ).execute().path("createAction.workflowId").entity(Long::class.java).isEqualTo(workflowId)

        // The workspace's own page: the shared one only.
        graphQlTester.document(
            """query { workspaceActions(workspaceId: $workspaceId) { content { name } } }""",
        ).execute()
            .path("workspaceActions.content[*].name").entityList(String::class.java)
            .containsExactly("Shared Call")

        // The editor's second ask: what this workflow owns.
        graphQlTester.document(
            """query { workflowOwnedActions(workspaceId: $workspaceId, workflowId: $workflowId) { name } }""",
        ).execute()
            .path("workflowOwnedActions[*].name").entityList(String::class.java)
            .containsExactly("This Node's Call")
    }

    @Test
    fun `an HTTP action offers the call a node may vary, and answers with the response`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Fetch API Data", type: EXECUTE, subtype: HTTP_REQUEST,
                url: "https://api.example.com/orders", method: "get",
                headers: "{ \"Authorization\": \"Bearer a-token\" }"
              }) { method subtypeLabel inputParams { display } outputParams { display } }
            }
            """,
        ).execute()
            .path("createAction.method").entity(String::class.java).isEqualTo("GET")
            .path("createAction.subtypeLabel").entity(String::class.java).isEqualTo("HTTP Request")
            .path("createAction.inputParams[*].display").entityList(String::class.java)
            .containsExactly("url: string", "body: string")
            .path("createAction.outputParams[*].display").entityList(String::class.java)
            .containsExactly("response: map")
    }

    @Test
    fun `a function action answers with what the function returns`() {
        val functionId = createFunction("transformPayload", "MAP")

        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Transform Data", type: EXECUTE, subtype: FUNCTION,
                functionId: $functionId
              }) { functionName inputParams { display } outputParams { display } }
            }
            """,
        ).execute()
            .path("createAction.functionName").entity(String::class.java).isEqualTo("transformPayload")
            .path("createAction.inputParams[*].display").entityList(String::class.java)
            .containsExactly("input: map", "format: string")
            .path("createAction.outputParams[*].display").entityList(String::class.java)
            .containsExactly("result: map")
    }

    /**
     * A plugin's function is a function an action may call.
     *
     * It belongs to no workspace by design — `FunctionScope.PLUGIN` says so, and
     * the picker offers it in every workspace — but the gate here compared its
     * workspace with the action's, which a null can never match. So the picker
     * offered something that could not be saved, and said the workspace did not
     * own a function it had just listed.
     */
    @Test
    fun `an action can call a function a plugin declared`() {
        val declared = pluginFunction()

        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Ask the Plugin", type: EXECUTE, subtype: FUNCTION,
                functionId: $declared
              }) { functionName }
            }
            """,
        ).execute()
            .path("createAction.functionName").entity(String::class.java).isEqualTo("teammates_isTeammate")
    }

    @Test
    fun `a function from another workspace is still refused`() {
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        val theirs = graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $elsewhere, name: "theirs", returnType: MAP,
                params: [{ name: "input", type: MAP }]
              }) { id }
            }
            """,
        ).execute().path("createFunction.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Borrowed", type: EXECUTE, subtype: FUNCTION,
                functionId: $theirs
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("a function this workspace owns") == true }.verify()
    }

    @Test
    fun `a wait takes and answers with what its condition names`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Wait for Approval", type: WAIT, subtype: INLINE_CONDITION,
                conditionExpression: "input.approved === true", timeoutSeconds: 3600, retryIntervalSeconds: 30
              }) { subtypeLabel inputParams { display } outputParams { display } }
            }
            """,
        ).execute()
            .path("createAction.subtypeLabel").entity(String::class.java).isEqualTo("Inline Condition")
            .path("createAction.inputParams[*].display").entityList(String::class.java)
            .containsExactly("approved: boolean")
            .path("createAction.outputParams[*].display").entityList(String::class.java)
            .containsExactly("approved: boolean")
    }

    @Test
    fun `a delay takes a duration and answers with nothing`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Delay Timer", type: WAIT, subtype: TIME, durationSeconds: 60
              }) { inputParams { display } outputParams { display } }
            }
            """,
        ).execute()
            .path("createAction.inputParams[*].display").entityList(String::class.java)
            .containsExactly("duration: number")
            .path("createAction.outputParams").entityList(String::class.java).hasSize(0)
    }

    @Test
    fun `a subtype that belongs to the other type is refused`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Confused", type: WAIT, subtype: HTTP_REQUEST, url: "https://example.com"
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("cannot be http request") == true }.verify()
    }

    @Test
    fun `an action without the setting it runs on is refused`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Nowhere", type: EXECUTE, subtype: HTTP_REQUEST
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("needs a URL") == true }.verify()
    }

    /**
     * Sending is Slack's alone. A Jira connection here used to save, and every
     * run skipped the step with "JIRA connections cannot send messages yet".
     */
    @Test
    fun `a message through a connection that cannot send one is refused`() {
        val jira = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Jira", type: HTTP, url: "https://jira.example.invalid"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Send Jira message", type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $jira, connectionAction: SEND_MESSAGE, content: "Approved"
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("a Slack connection") == true }.verify()
    }

    @Test
    fun `a connection from another workspace is refused`() {
        val otherWorkspace = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        val theirs = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $otherWorkspace, name: "Theirs", type: SLACK, url: "https://slack.com/api"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Borrowed", type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $theirs, connectionAction: SEND_MESSAGE
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("a connection this workspace holds") == true }.verify()
    }

    @Test
    fun `renaming and deleting are recorded`() {
        val id = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Delay Timer", type: WAIT, subtype: TIME, durationSeconds: 60
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAction(id: $id, input: { name: "Pause" }) { name } }""",
        ).execute().path("updateAction.name").entity(String::class.java).isEqualTo("Pause")

        graphQlTester.document("""mutation { deleteAction(id: $id) }""")
            .execute().path("deleteAction").entity(Boolean::class.java).isEqualTo(true)

        assertThat(actions.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message })
            .contains("Action Delay Timer renamed to Pause", "Action Pause deleted")
    }

    @Test
    fun `a duplicate name inside a workspace is refused`() {
        repeat(2) { attempt ->
            val execution = graphQlTester.document(
                """
                mutation {
                  createAction(input: {
                    workspaceId: $workspaceId, name: "Delay Timer", type: WAIT, subtype: TIME, durationSeconds: 60
                  }) { id }
                }
                """,
            ).execute()
            if (attempt == 1) {
                execution.errors().expect { it.message?.contains("already exists") == true }.verify()
            }
        }
    }

    /** A plugin loaded from its own source, and its functions materialised. */
    private fun pluginFunction(): Long {
        val read = pluginRunner.inspect(TEAMMATES) as PluginInspection.Read
        val plugin = plugins.save(
            Plugin(
                key = read.id,
                name = "teammates",
                filename = "teammates.js",
                source = TEAMMATES,
                sizeBytes = TEAMMATES.length.toLong(),
                apiVersion = read.apiVersion,
                sha256 = "0".repeat(64),
                declaredFunctions = declarations.validated(read.functions),
                declaredParameters = declarations.validatedParameters(read.parameters),
            ),
        )
        registry.reconcile(plugin)
        return requireNotNull(functions.findAll().first { it.scope == FunctionScope.PLUGIN }.id)
    }

    private fun createFunction(name: String, returnType: String): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: {
            workspaceId: $workspaceId, name: "$name", returnType: $returnType,
            params: [{ name: "input", type: MAP }, { name: "format", type: STRING }]
          }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    private companion object {
        val TEAMMATES = """
            export default class Teammates extends OrknuxPlugin {
              id() { return 'teammates'; }
              apiVersion() { return 1; }

              functions() {
                return [
                  new OrknuxFunction({
                    name: 'isTeammate',
                    params: [{ name: 'input', type: 'map' }],
                    returnType: 'boolean',
                    run: (input) => input !== null && input.user === 'alice@example.com',
                  }),
                ];
              }
            }
        """.trimIndent()
    }
}
