package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
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
 * Where a run begins, and how many ways a condition sends it.
 *
 * The graph is the one the report was about: a trigger, an action, a condition
 * after it and a node on each of its two ways out. Drawn without the line from
 * the trigger, the engine used to start the action anyway - every node with
 * nothing pointing at it was a beginning - so the canvas warned "a run never
 * reaches it" about a node every run reached. And a condition that could not
 * be answered sent the run down both its ways at once, so the "no" node ran
 * beside the "yes" node and was handed what the "yes" node had produced.
 *
 * Driven through the real modules: a manual run here is inline, so each run is
 * over by the time `startExecution` answers.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class RunBeginningTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val triggers: WorkflowTriggerRepository,
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
        triggers.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Triage" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @Test
    fun `nothing the trigger does not lead to runs`() {
        val problems = draw(wired = false)

        start("""{"user":"alice@example.com"}""")

        // Not one of them: the trigger leads nowhere, so nothing is reached.
        listOf("prepare", "gate", "yes", "no").forEach { key ->
            assertThat(step(key).status).describedAs(key).isEqualTo(StepStatus.SKIPPED)
        }
        assertThat(step("prepare").output).contains("nothing leads to it from the trigger")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)

        // The run's log says first which line is missing, and the trigger itself
        // is where the run began - not a node "with no runtime yet".
        val said = logs.findAll().sortedBy { it.sequence }.map { it.message }
        assertThat(said).anySatisfy { assertThat(it).contains("Nothing is joined to the trigger Start").contains("Draw a line") }
        assertThat(said).noneSatisfy { assertThat(it).contains("no runtime yet") }
        assertThat(executions.findAll().single().let { run -> step("start").status }).isEqualTo(StepStatus.COMPLETED)

        // And the canvas said so before it ran, about the line that is missing.
        assertThat(problems).anySatisfy { assertThat(it).contains("WARNING").contains("Start leads nowhere") }
        assertThat(problems).anySatisfy { assertThat(it).contains("Prepare has nothing before it") }
    }

    @Test
    fun `wired to the trigger, the same graph runs and goes one way`() {
        val problems = draw(wired = true)

        start("""{"user":"alice@example.com"}""")

        assertThat(step("prepare").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(step("gate").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(step("yes").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(step("no").status).isEqualTo(StepStatus.SKIPPED)
        assertThat(problems).noneSatisfy { assertThat(it).contains("leads nowhere") }
    }

    @Test
    fun `a condition that cannot be answered takes neither way`() {
        draw(wired = true)

        // No `user`, so the author the condition asks about is not there.
        start("""{"text":"hello"}""")

        assertThat(step("gate").output).contains("could not be answered")
        assertThat(step("yes").status).isNotEqualTo(StepStatus.COMPLETED)
        assertThat(step("no").status).isNotEqualTo(StepStatus.COMPLETED)

        // An ending, said as one, rather than a run that looks like it did both.
        val run = executions.findAll().single()
        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(run.stoppedAtNodeKey).isEqualTo("gate")
        assertThat(run.stoppedReason).contains("could not be answered")
    }

    /**
     * The trigger, `prepare` -> `gate` -> `yes` / `no`; [wired] draws the line
     * from the trigger to `prepare`. Answers the problems the save reported.
     */
    private fun draw(wired: Boolean): List<String> {
        val triggerId = graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "nightly", type: SCHEDULED, cron: "0 2 * * *"
              }) { id }
            }
            """,
        ).execute().path("createTrigger.id").entity(Long::class.java).get()
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
        val prepare = action("prepare")
        val yes = action("markYes")
        val no = action("markNo")

        val fromTrigger = if (wired) """{ source: "start", target: "prepare" },""" else ""
        return graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "start", kind: TRIGGER, name: "Start", triggerId: $triggerId, x: 0, y: 0 },
                  { key: "prepare", kind: ACTION, name: "Prepare", actionId: $prepare, x: 200, y: 0 },
                  { key: "gate", kind: CONDITION, name: "From alice", conditionId: $conditionId, x: 400, y: 0 },
                  { key: "yes", kind: ACTION, name: "Yes", actionId: $yes, x: 600, y: -100 },
                  { key: "no", kind: ACTION, name: "No", actionId: $no, x: 600, y: 100 }
                ],
                edges: [
                  $fromTrigger
                  { source: "prepare", target: "gate" },
                  { source: "gate", target: "yes", branch: YES },
                  { source: "gate", target: "no", branch: NO }
                ]
              }) { problems { severity message } }
            }
            """,
        ).execute()
            .path("saveWorkflowGraph.problems[*]").entityList(Map::class.java).get()
            .map { "${it["severity"]} ${it["message"]}" }
    }

    /** An action calling a function of the same name, which only says that it ran. */
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

    private fun start(input: String) {
        graphQlTester.document(
            """
            mutation(${'$'}input: String) {
              startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: ${'$'}input) { id }
            }
            """,
        ).variable("input", input).execute().path("startExecution.id").entity(Long::class.java).get()
    }

    private fun step(key: String) = steps.findAll().single { it.nodeKey == key }
}
