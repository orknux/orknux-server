package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
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
 * A Workflow node: one workflow running another, handing it the node's
 * parameters, waiting for it and passing on what it produced.
 *
 * Runs here are manual, so inline: the called run is over by the time the
 * node has started it, and the caller is over by the time `startExecution`
 * answers.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkflowNodeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun workspace() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "calls-${System.nanoTime()}")).id)
    }

    @Test
    fun `a workflow runs another, hands it its parameters and passes on what it produced`() {
        val callee = workflow("Greeter")
        draw(callee, """{ key: "work", kind: ACTION, name: "Work", actionId: ${action("greet")}, x: 200, y: 0 }""")

        val caller = workflow("Caller")
        draw(
            caller,
            """
            { key: "call", kind: WORKFLOW, name: "Call Greeter", calledWorkflowId: $callee, outputName: "called",
              mappings: [{ name: "who", expression: "alice", mode: VALUE }], x: 200, y: 0 }
            """,
        )

        val run = start(caller)

        val call = steps.findByExecutionIdAndNodeKey(run.id!!, "call")!!
        assertThat(call.status).describedAs(call.error).isEqualTo(StepStatus.COMPLETED)
        val called = executions.findById(call.childExecutionId!!).get()
        assertThat(called.workflowId).isEqualTo(callee)
        assertThat(called.parentExecutionId).isEqualTo(run.id)
        assertThat(called.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(called.input).isEqualTo("""{"who":"alice"}""")
        // A hand-started caller runs the callee's draft too: it was never published.
        assertThat(called.ranDraft).isTrue()
        // What the callee produced, under the node's name.
        assertThat(call.output).contains(""""called":""").contains(""""greet":true""").contains(""""who":"alice"""")
        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)

        graphQlTester.document("{ execution(id: ${called.id}) { parentExecutionId } }").execute()
            .path("execution.parentExecutionId").entity(String::class.java).isEqualTo(run.id.toString())
        val listed = graphQlTester.document("{ execution(id: ${run.id}) { steps { key childExecutionId } } }").execute()
            .path("execution.steps").entityList(Map::class.java).get()
        assertThat(listed.single { it["key"] == "call" }["childExecutionId"].toString()).isEqualTo(called.id.toString())
    }

    @Test
    fun `two workflows calling each other stop at the second call`() {
        val first = workflow("Ping")
        val second = workflow("Pong")
        draw(first, """{ key: "call", kind: WORKFLOW, name: "Call Pong", calledWorkflowId: $second, x: 200, y: 0 }""")
        draw(second, """{ key: "call", kind: WORKFLOW, name: "Call Ping", calledWorkflowId: $first, x: 200, y: 0 }""")

        val run = start(first)

        assertThat(run.status).isEqualTo(ExecutionStatus.FAILED)
        val pong = executions.findById(steps.findByExecutionIdAndNodeKey(run.id!!, "call")!!.childExecutionId!!).get()
        val refused = steps.findByExecutionIdAndNodeKey(pong.id!!, "call")!!
        assertThat(refused.status).isEqualTo(StepStatus.FAILED)
        assertThat(refused.error).contains("Ping is already running in this chain (Ping → Pong → Ping)")
        assertThat(refused.childExecutionId).isNull()
    }

    @Test
    fun `a node cannot call its own workflow, nor another workspace's`() {
        val own = workflow("Self")
        saveRefused(own, """{ key: "call", kind: WORKFLOW, name: "Again", calledWorkflowId: $own, x: 0, y: 0 }""", "WorkflowCallsItself")

        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "other-${System.nanoTime()}")).id)
        val theirs = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $elsewhere, name: "Theirs" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        saveRefused(own, """{ key: "call", kind: WORKFLOW, name: "Theirs", calledWorkflowId: $theirs, x: 0, y: 0 }""", "CalledWorkflowNotInWorkspace")
    }

    @Test
    fun `a node naming no workflow is skipped, and the editor says what it lacks`() {
        val caller = workflow("Unfinished")
        val problems = draw(caller, """{ key: "call", kind: WORKFLOW, name: "Call", x: 200, y: 0 }""")
        assertThat(problems).anySatisfy { assertThat(it).contains("no workflow chosen") }

        val run = start(caller)
        assertThat(steps.findByExecutionIdAndNodeKey(run.id!!, "call")!!.status).isEqualTo(StepStatus.SKIPPED)
    }

    private fun workflow(name: String): Long = graphQlTester.document(
        """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "$name" }) { workflowId } }""",
    ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

    /** A manual trigger leading to [node], whose key is `call` or `work`. */
    private fun draw(workflowId: Long, node: String): List<String> {
        val key = Regex("""key: "(\w+)"""").find(node)!!.groupValues[1]
        return graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "start", kind: TRIGGER, name: "Start", x: 0, y: 0 },
                  $node
                ],
                edges: [{ source: "start", target: "$key" }]
              }) { problems { severity message } }
            }
            """,
        ).execute()
            .path("saveWorkflowGraph.problems[*]").entityList(Map::class.java).get()
            .map { "${it["severity"]} ${it["message"]}" }
    }

    private fun saveRefused(workflowId: Long, node: String, code: String) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: { nodes: [$node], edges: [] }) { workflowId }
            }
            """,
        ).execute().errors().satisfy { errors ->
            assertThat(errors.map { it.extensions["code"] }).containsExactly(code)
        }
    }

    /** An action calling a function that says it ran. */
    private fun action(name: String): Long {
        val source = "export default function $name() { return { $name: true }; }"
        val functionId = graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "$name", returnType: MAP,
                source: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'}, typescript: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'},
                params: []
              }) { id }
            }
            """,
        ).execute().path("createFunction.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "$name", type: EXECUTE, subtype: FUNCTION, functionId: $functionId
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()
    }

    private fun start(workflowId: Long): WorkflowExecution {
        val id = graphQlTester.document(
            """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId) { id } }""",
        ).execute().path("startExecution.id").entity(Long::class.java).get()
        return executions.findById(id).get()
    }
}
