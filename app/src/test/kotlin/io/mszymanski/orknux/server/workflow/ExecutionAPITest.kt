package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.TestPropertySource

/**
 * Runs end to end: the app decides who may look and where the graph comes from,
 * the execution module runs it and keeps what happened.
 *
 * The inline engine, so a run has finished by the time the call answers and the
 * assertions can be about what it did; the Temporal path is ExecutionWorkflowTest.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class ExecutionAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val runs: ExecutionService,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        assignments.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        workflows.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """
            mutation {
              createWorkflow(input: { workspaceId: $workspaceId, name: "Data Processing Pipeline" }) { workflowId }
            }
            """,
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Webhook Trigger", x: 0, y: 0 },
                  { key: "fetch", kind: AGENT, name: "Fetch Data", x: 200, y: 0 }
                ],
                edges: [{ source: "trigger", target: "fetch" }]
              }) { status }
            }
            """,
        ).execute()

        // Published, because a trigger runs the published copy: a graph that
        // was only ever saved is one somebody is still drawing.
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute()
    }

    @Test
    fun `a run reads the graph from the workflow it names`() {
        val run = start()

        assertThat(run.workflowName).isEqualTo("Data Processing Pipeline")
        assertThat(run.steps.map { it.key }).containsExactly("trigger", "fetch")
    }

    @Test
    fun `the detail carries the run's steps and the workflow's edges`() {
        val id = start().id

        graphQlTester.document(
            """
            query {
              execution(id: $id) {
                workflowName status steps { key name status } edges { source target } logs { level message }
              }
            }
            """,
        ).execute()
            .path("execution.steps[*].key").entityList(String::class.java).containsExactly("trigger", "fetch")
            .path("execution.edges[0].source").entity(String::class.java).isEqualTo("trigger")
            .path("execution.edges[0].target").entity(String::class.java).isEqualTo("fetch")
            .path("execution.logs[*].message").entityList(String::class.java).hasSizeGreaterThan(0)
    }

    @Test
    fun `a workspace's runs are listed, and the filters reach the module`() {
        start()

        graphQlTester.document(
            """
            query {
              workspaceExecutions(workspaceId: $workspaceId, search: "pipeline", days: 1) {
                content { workflowName status }
                totalElements
              }
            }
            """,
        ).execute()
            .path("workspaceExecutions.totalElements").entity(Int::class.java).isEqualTo(1)

        graphQlTester.document(
            """query { workspaceExecutions(workspaceId: $workspaceId, search: "nothing-like-this") { totalElements } }""",
        ).execute().path("workspaceExecutions.totalElements").entity(Int::class.java).isEqualTo(0)
    }

    @Test
    fun `re-running starts the workflow again, as a manual run`() {
        val id = start().id

        val newId = graphQlTester.document("""mutation { rerunExecution(id: $id) { id trigger } }""")
            .execute()
            .path("rerunExecution.trigger").entity(String::class.java).isEqualTo("MANUAL")
            .path("rerunExecution.id").entity(Long::class.java).get()

        assertThat(newId).isNotEqualTo(id)
        assertThat(executions.findAll()).hasSize(2)
    }

    /**
     * The mutation behind "run it again from here": the steps ahead of the
     * chosen one come back as what they were, marked, and only the rest is
     * performed. What may and may not be started is the execution module's, and
     * RerunFromStepTest is where that lives; this is that the argument reaches
     * it and the answer comes back shaped for the panel offering the button.
     */
    @Test
    fun `re-running from a step carries what came before it rather than doing it again`() {
        val id = start().id

        graphQlTester.document(
            """
            mutation {
              rerunExecutionStep(id: $id, nodeKey: "fetch") {
                id trigger steps { key status carriedOver }
              }
            }
            """,
        ).execute()
            .path("rerunExecutionStep.trigger").entity(String::class.java).isEqualTo("MANUAL")
            .path("rerunExecutionStep.steps[*].key").entityList(String::class.java).containsExactly("trigger", "fetch")
            .path("rerunExecutionStep.steps[0].carriedOver").entity(Boolean::class.java).isEqualTo(true)
            .path("rerunExecutionStep.steps[1].carriedOver").entity(Boolean::class.java).isEqualTo(false)

        assertThat(executions.findAll()).hasSize(2)
    }

    /**
     * Where a run came from is worth keeping because nothing else on it says.
     *
     * A re-run is recorded as manual and carries the earlier run's input, which
     * describes every hand-started run equally well, so without this the run
     * somebody pressed re-run on is a thing to remember rather than to follow.
     */
    @Test
    fun `re-running records the run it was started from`() {
        val id = start().id

        graphQlTester.document("""mutation { rerunExecution(id: $id) { id startedFrom } }""")
            .execute()
            .path("rerunExecution.startedFrom").entity(Long::class.java).isEqualTo(id)
    }

    @Test
    fun `re-running from a step records the run it was started from`() {
        val id = start().id

        graphQlTester.document(
            """mutation { rerunExecutionStep(id: $id, nodeKey: "fetch") { id startedFrom } }""",
        ).execute()
            .path("rerunExecutionStep.startedFrom").entity(Long::class.java).isEqualTo(id)
    }

    /**
     * The null half of it, which is the half that makes the other half mean
     * something: a column that is never null says nothing when it is set.
     */
    @Test
    fun `a run nobody re-ran was started from nothing`() {
        val id = start().id

        graphQlTester.document("""query { execution(id: $id) { startedFrom } }""")
            .execute()
            .path("execution.startedFrom").valueIsNull()
    }

    /**
     * A refusal has to arrive as a sentence, because the panel offering the
     * button is where somebody reads why it could not be done.
     */
    @Test
    fun `re-running from a node the workflow no longer has says so`() {
        val id = start().id

        graphQlTester.document("""mutation { rerunExecutionStep(id: $id, nodeKey: "removed-since") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("no longer has a step called removed-since") == true }.verify()

        // Nothing half-run left behind: the refusal happens before the run is
        // written down.
        assertThat(executions.findAll()).hasSize(1)
    }

    @Test
    fun `an unknown run is null rather than an error`() {
        graphQlTester.document("""query { execution(id: 999999) { id } }""")
            .execute()
            .path("execution").valueIsNull()
    }

    /**
     * Issue 168. Removing a workflow deletes the workspace's assignment and
     * leaves every run of it, so the executions screen goes on showing runs of a
     * workflow the workspace no longer lists. Both halves of what the screen
     * needs to say so are here: the run says its workflow is gone, and the
     * filter it can be found with knows the workflow at all.
     */
    @Test
    fun `a run outlives the workflow's place in the workspace, and says so`() {
        val id = start().id
        val assignment = requireNotNull(assignments.findByWorkspaceIdAndWorkflowId(workspaceId, workflowId)?.id)

        graphQlTester.document("""query { execution(id: $id) { workflowAssigned } }""")
            .execute()
            .path("execution.workflowAssigned").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("""mutation { removeWorkflow(id: $assignment) }""").execute()

        graphQlTester.document("""query { execution(id: $id) { workflowName workflowAssigned } }""")
            .execute()
            // Still named. The run is the only record of what ran, and the name
            // it recorded is the only copy of it the workspace can still reach.
            .path("execution.workflowName").entity(String::class.java).isEqualTo("Data Processing Pipeline")
            .path("execution.workflowAssigned").entity(Boolean::class.java).isEqualTo(false)

        graphQlTester.document(
            """query { workspaceExecutions(workspaceId: $workspaceId) { content { workflowAssigned } } }""",
        ).execute()
            .path("workspaceExecutions.content[0].workflowAssigned").entity(Boolean::class.java).isEqualTo(false)
    }

    /**
     * The filter is built from this, and it is built from the runs rather than
     * from the workflows the workspace lists - which is the whole of the fix:
     * asked of the assignments, a removed workflow is not there to be chosen,
     * so its runs could be scrolled past and never singled out.
     */
    @Test
    fun `the executions filter offers a removed workflow, marked as removed`() {
        start()
        val assignment = requireNotNull(assignments.findByWorkspaceIdAndWorkflowId(workspaceId, workflowId)?.id)

        graphQlTester.document("""query { executionWorkflows(workspaceId: $workspaceId) { workflowId assigned } }""")
            .execute()
            .path("executionWorkflows[*].workflowId").entityList(Long::class.java).containsExactly(workflowId)
            .path("executionWorkflows[0].assigned").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("""mutation { removeWorkflow(id: $assignment) }""").execute()

        graphQlTester.document(
            """query { executionWorkflows(workspaceId: $workspaceId) { workflowId name assigned } }""",
        ).execute()
            .path("executionWorkflows[*].workflowId").entityList(Long::class.java).containsExactly(workflowId)
            .path("executionWorkflows[0].name").entity(String::class.java).isEqualTo("Data Processing Pipeline")
            .path("executionWorkflows[0].assigned").entity(Boolean::class.java).isEqualTo(false)

        // And choosing it does what choosing it is for.
        graphQlTester.document(
            """query { workspaceExecutions(workspaceId: $workspaceId, workflowId: $workflowId) { totalElements } }""",
        ).execute()
            .path("workspaceExecutions.totalElements").entity(Int::class.java).isEqualTo(1)
    }

    /**
     * A workflow that has never run is not on this list, because the list is
     * what the runs name. A workflow with no runs belongs to the workflows
     * screen, and the executions filter goes on offering those separately.
     */
    /**
     * A run is drawn with the graph it ran. Its edges were read off the workflow
     * as it is now, so a run of the published version showed the wiring of a
     * draft edited since.
     */
    @Test
    fun `a run keeps the edges it was planned with, whatever the workflow became`() {
        val id = start().id

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Webhook Trigger", x: 0, y: 0 },
                  { key: "fetch", kind: AGENT, name: "Fetch Data", x: 200, y: 0 },
                  { key: "store", kind: AGENT, name: "Store", x: 400, y: 0 }
                ],
                edges: [{ source: "trigger", target: "store" }, { source: "store", target: "fetch" }]
              }) { status }
            }
            """,
        ).execute()

        graphQlTester.document("""query { execution(id: $id) { edges { source target } } }""").execute()
            .path("execution.edges[*].target").entityList(String::class.java).containsExactly("fetch")
            .path("execution.edges[*].source").entityList(String::class.java).containsExactly("trigger")
    }

    /**
     * A run says it ran an older version only when it did, as it started: the
     * published copy while the draft had changes. A run of what was newest is
     * not made old by the workflow being edited afterwards.
     */
    @Test
    fun `a run is older only if the draft was ahead when it started`() {
        val first = start().id
        fun behind(id: Any) = graphQlTester.document(
            """query { execution(id: $id) { version { draft editedSince } } }""",
        ).execute().path("execution.version.editedSince").entity(Boolean::class.java)

        behind(first).isEqualTo(false)

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Webhook Trigger", x: 0, y: 0 },
                  { key: "fetch", kind: AGENT, name: "Fetch Data, renamed", x: 200, y: 0 }
                ],
                edges: [{ source: "trigger", target: "fetch" }]
              }) { status }
            }
            """,
        ).execute()

        // Edited after it ran: still the newest at the time, so not an older version.
        behind(first).isEqualTo(false)
        // Run now, with the draft ahead of what is published: an older version.
        behind(start().id).isEqualTo(true)
    }

    @Test
    fun `a webhook run logs the payload it was sent`() {
        val run = runs.startExecution(
            StartExecutionInput(
                workspaceId = workspaceId, workflowId = workflowId, trigger = ExecutionTrigger.WEBHOOK,
                payload = """{"order":{"total":12}}""",
            ),
        )
        // Said by the trigger step, which is where the run began.
        assertThat(run.logs.map { it.message }).anySatisfy { assertThat(it).contains("fired with: ").contains(""""order":{"total":12}""") }
    }

    @Test
    fun `a workflow with no runs is not offered by the executions filter`() {
        graphQlTester.document("""query { executionWorkflows(workspaceId: $workspaceId) { workflowId } }""")
            .execute()
            .path("executionWorkflows").entityList(Long::class.java).hasSize(0)
    }

    private fun start() = runs.startExecution(
        StartExecutionInput(workspaceId = workspaceId, workflowId = workflowId, trigger = ExecutionTrigger.WEBHOOK),
    )
}
