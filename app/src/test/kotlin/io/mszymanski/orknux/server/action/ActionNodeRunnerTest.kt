package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.LogLevel
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * An action node running for real: a workflow whose action calls one of the
 * workspace's functions, and the JavaScript that runs in the sandbox deciding what
 * the next node is handed.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ActionNodeRunnerTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val variables: WorkspaceVariableRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @Test
    fun `an action node calls the workspace's function and hands on what it returned`() {
        val functionId = function(
            "transformPayload",
            """
            export default async function transformPayload(input, format) {
              return { id: input.id, format: format, processed: true };
            }
            """.trimIndent(),
        )
        val actionId = functionAction(functionId, "Transform Data")
        graph(actionId)

        val runId = start()

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        /*
         * Beside what reached the step, not instead of it.
         *
         * A step's result is something a run gains on its way past: a function
         * that rewrites an answer used to hand on only what it returned, and
         * the step after it lost the channel, the thread and every other field
         * that arrived. Unnamed, it joins under `result`, which is the port the
         * action declares.
         */
        assertThat(step.output).isEqualTo(
            """{"payload":{"id":7},"format":"compact","result":{"id":7,"format":"compact","processed":true}}""",
        )
        assertThat(executions.findAll().single { it.id == runId }.status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    /**
     * A grant belongs to the function that declared it, however it is reached.
     *
     * The importer supplies what it declared and nothing else — an external is
     * not the caller's to fill, and an importer is not told it exists. So the
     * only side that can hand it over is the one assembling the modules, and a
     * function reached through `imports` that saw `undefined` where its variable
     * should be would be a function whose grants meant one thing when a node
     * called it and another when its neighbour did.
     */
    @Test
    fun `a function reached through an import is handed its own externals`() {
        val token = variable("apiToken", "s3cret")
        val readerId = function(
            "readToken",
            "export default function readToken(word, apiToken) { return { token: apiToken, word: word }; }",
            params = """[{ name: "word", type: STRING }]""",
            extra = ", externalVariableIds: [$token]",
        )
        val callerId = function(
            "useToken",
            "export default async function useToken(input, format) { return await imports.read(format); }",
            extra = """, imports: [{ functionId: $readerId, name: "read" }]""",
        )
        val actionId = functionAction(callerId, "Use Token")
        graph(actionId)

        start()

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        // The word is what the importer passed; the token is what nobody passed.
        // Beside what arrived; see the note above.
        assertThat(step.output).isEqualTo(
            """{"payload":{"id":7},"format":"compact","result":{"token":"s3cret","word":"compact"}}""",
        )
    }

    @Test
    fun `a function that throws stops the run, with the reason on the step`() {
        val functionId = function(
            "transformPayload",
            """export default function transformPayload(input) { throw new Error("bad payload"); }""",
            params = """[{ name: "input", type: MAP }]""",
        )
        graph(functionAction(functionId, "Transform Data"))

        start(expectFailure = true)

        val step = steps.findAll().single { it.actionId != null }
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("bad payload")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.FAILED)
    }

    /**
     * What a function says with `orknux.log` is in the run's log, under its
     * step, before the line the step ends on - and said before it threw, too.
     * The sandbox kept the lines on its answer and nothing wrote them, so the
     * run page's log for a function step had nothing a function said.
     */
    @Test
    fun `what a function logs is in the run's log under its step, whether it returns or throws`() {
        val functionId = function(
            "chatty",
            """
            export default function chatty(input, format) {
              orknux.log.info("looked at", input.id);
              orknux.log.warn("format is", format);
              orknux.log.error("about to fail");
              if (input.id === 8) throw new Error("bad payload");
              return { ok: true };
            }
            """.trimIndent(),
        )
        graph(functionAction(functionId, "Chatty"))

        val runId = start()
        val said = logs.findByExecutionIdOrderBySequenceAsc(runId).filter { it.nodeKey == "act" }
        assertThat(said.map { it.message }.take(3)).containsExactly("looked at 7", "Warning: format is compact", "about to fail")
        assertThat(said.map { it.level }.take(3)).containsExactly(LogLevel.INFO, LogLevel.INFO, LogLevel.ERROR)
        assertThat(said).hasSize(4)

        val failed = start("""{"payload":{"id":8},"format":"compact"}""", expectFailure = true)
        assertThat(logs.findByExecutionIdOrderBySequenceAsc(failed).filter { it.nodeKey == "act" }.map { it.message })
            .startsWith("looked at 8", "Warning: format is compact", "about to fail")
            .anyMatch { it.contains("bad payload") }
    }

    @Test
    fun `a wait carries on as soon as its condition holds, without ever parking`() {
        val actionId = waitAction(
            """
            name: "Wait for Approval", subtype: INLINE_CONDITION,
            conditionExpression: "input.approved === true", timeoutSeconds: 5, retryIntervalSeconds: 1
            """,
        )
        graph(actionId)

        start(input = """{"approved":true}""")

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        // It held the first time it was asked, so nothing was ever waited for.
        assertThat(step.waitUntil).isNull()
    }

    @Test
    fun `a wait for a time parks the step, and the run carries on when it is up`() {
        val actionId = waitAction("""name: "Cool Down", subtype: TIME, durationSeconds: 1""")
        graph(actionId)

        start()

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        // It parked rather than sleeping through the step: the deadline it was
        // counting to is on the step, and the run said so once.
        assertThat(step.waitUntil).isNotNull()
        assertThat(logs.findAll().map { it.message }).anyMatch { it.contains("Cool Down is waiting") }
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    @Test
    fun `a wait whose condition never holds fails when it runs out of time`() {
        val actionId = waitAction(
            """
            name: "Wait for Approval", subtype: INLINE_CONDITION,
            conditionExpression: "input.approved === true", timeoutSeconds: 1, retryIntervalSeconds: 1
            """,
        )
        graph(actionId)

        start(input = """{"approved":false}""", expectFailure = true)

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("ran out of time")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.FAILED)
    }

    @Test
    fun `a wait longer than the inline engine allows fails, and says what would carry it`() {
        val actionId = waitAction("""name: "Wait a Day", subtype: TIME, durationSeconds: 86400""")
        graph(actionId)

        start(expectFailure = true)

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("longer than the inline engine allows")
        assertThat(step.error).contains("orknux.temporal.enabled")
    }

    @Test
    fun `a condition node stops the run when its condition does not hold`() {
        val conditionId = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "From alice", type: SLACK, property: MESSAGE_AUTHOR,
                check: IN_LIST, values: ["alice@example.com"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        // The condition comes first; the action after it only runs if it holds.
        val functionId = function(
            "echo",
            """export default function echo(input) { return { seen: true }; }""",
            params = """[{ name: "input", type: MAP }]""",
        )
        val actionId = functionAction(functionId, "After the gate")
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "gate", kind: CONDITION, name: "From alice", conditionId: $conditionId, x: 0, y: 0 },
                  { key: "act", kind: ACTION, name: "Act", actionId: $actionId, x: 240, y: 0 }
                ],
                edges: [{ source: "gate", target: "act" }]
              }) { nodes { key conditionId } }
            }
            """,
        ).execute()

        start(input = """{"user":"bob@example.com"}""")

        val gate = steps.findAll().single { it.nodeKey == "gate" }
        val act = steps.findAll().single { it.nodeKey == "act" }
        assertThat(gate.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(gate.output).contains("did not hold")
        // The run finished; the step after the gate was never started.
        assertThat(act.status).isEqualTo(StepStatus.PENDING)

        // And the run says so, rather than looking like one that did everything.
        val run = executions.findAll().single()
        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(run.stoppedAtNodeKey).isEqualTo("gate")
        assertThat(run.stoppedReason).contains("From alice did not hold")
        assertThat(logs.findAll().map { it.message }).anyMatch { it.contains("stopped after gate") }
    }

    @Test
    fun `a send with nobody to send to is skipped, and says which node and why`() {
        val connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Slack", type: SLACK
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
        val actionId = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Send Slack Notification", type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $connectionId, connectionAction: SEND_MESSAGE, content: "Hello"
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()
        graph(actionId)

        start()

        val step = steps.findAll().single { it.actionId == actionId }
        // Sending is implemented now, so what stops this is the send itself: the
        // node was given no target, and a step that performed nothing has to say
        // so rather than report a message nobody received.
        assertThat(step.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(step.output).contains("has nobody to send to")
    }

    /**
     * The node decides what it passes, and the action is only ever the suggestion
     * it started from.
     *
     * The same node runs twice on the same input: once as the action suggested,
     * once with a plain value typed over one parameter — which is the thing an
     * expression-only definition cannot express. If the definition were read at
     * run time both runs would agree, and a workspace would need a near-duplicate
     * action per call site. The action itself is checked afterwards: editing a
     * node must not move anything else that uses it.
     */
    @Test
    fun `a node passes what it was given, not what the action suggests`() {
        val functionId = function(
            "transformPayload",
            """
            export default function transformPayload(input, format) {
              return { id: input.id, format: format };
            }
            """.trimIndent(),
        )
        val actionId = functionAction(functionId, "Transform Data")

        graph(actionId)
        val suggested = start()

        // The node is pointed at the same action; only what it passes changes.
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{
                  key: "act", kind: ACTION, name: "Act", actionId: $actionId, x: 0, y: 0,
                  mappings: [
                    { name: "input", expression: "payload", mode: REFERENCE },
                    { name: "format", expression: "verbose" }
                  ]
                }],
                edges: []
              }) { nodes { key mappings { name expression } } }
            }
            """,
        ).execute()
            .path("saveWorkflowGraph.nodes[0].mappings[?(@.name == 'format')].expression")
            .entityList(String::class.java).containsExactly("verbose")
        val overridden = start()

        // Under `result` and beside what arrived; see the note on the first test.
        assertThat(steps.findAll().single { it.executionId == suggested }.output)
            .isEqualTo("""{"payload":{"id":7},"format":"compact","result":{"id":7,"format":"compact"}}""")
        // Same action, same input, and the plain value the node holds is what ran.
        assertThat(steps.findAll().single { it.executionId == overridden }.output)
            .isEqualTo("""{"payload":{"id":7},"format":"compact","result":{"id":7,"format":"verbose"}}""")

        // And editing the node left the action alone.
        assertThat(actions.findAll().single { it.id == actionId }.mappings.map { it.argument to it.expression })
            .containsExactly("format" to "compact")
    }

    /** [extra] is whatever else the input carries: externals, imports. */
    private fun function(
        name: String,
        source: String,
        params: String = """[{ name: "input", type: MAP }, { name: "format", type: STRING }]""",
        extra: String = "",
    ): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: {
            workspaceId: $workspaceId, name: "$name", returnType: MAP,
            source: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'}, typescript: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'},
            params: $params$extra
          }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    /** One of the workspace's variables, for a function to be granted. */
    private fun variable(name: String, held: String): Long {
        val catalogId = requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = name)).id)
        return requireNotNull(
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
    }

    /** A wait action; [settings] is the rest of the input, which differs by subtype. */
    private fun waitAction(settings: String): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: { workspaceId: $workspaceId, type: WAIT, $settings }) { id }
        }
        """,
    ).execute().path("createAction.id").entity(Long::class.java).get()

    private fun functionAction(functionId: Long, name: String): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: {
            workspaceId: $workspaceId, name: "$name", type: EXECUTE, subtype: FUNCTION, functionId: $functionId,
            mappings: [{ argument: "format", expression: "compact" }]
          }) { id }
        }
        """,
    ).execute().path("createAction.id").entity(Long::class.java).get()

    /** One action node, which is the whole workflow. */
    /**
     * One action node, which is the whole workflow.
     *
     * It says where `input` comes from, because that is the node's to say: the
     * action suggests values, and a reference to a field of what the run was
     * started with is not something a definition can suggest for every use of
     * it. [mappings] is what a test wants to vary on top of that.
     */
    private fun graph(
        actionId: Long,
        mappings: String = """[{ name: "input", expression: "payload", mode: REFERENCE }]""",
    ) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{
                  key: "act", kind: ACTION, name: "Act", actionId: $actionId, x: 0, y: 0,
                  mappings: $mappings
                }],
                edges: []
              }) { nodes { key actionId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].actionId").entity(Long::class.java).isEqualTo(actionId)
    }

    /** Starts the run with something for the action to work on. */
    private fun start(
        input: String = """{"payload":{"id":7},"format":"compact"}""",
        expectFailure: Boolean = false,
    ): Long {
        val id = graphQlTester.document(
            """
            mutation(${'$'}input: String) {
              startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: ${'$'}input) { id status }
            }
            """,
        ).variable("input", input).execute().path("startExecution.id").entity(Long::class.java).get()

        val run = executions.findAll().single { it.id == id }
        if (!expectFailure) assertThat(run.status).isIn(ExecutionStatus.COMPLETED, ExecutionStatus.RUNNING)
        return id
    }
}
