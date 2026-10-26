package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.EdgeBranch as RunnableEdgeBranch
import io.mszymanski.orknux.workflow.execution.GraphEdge
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.workflow.execution.WorkflowNotPublishedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * What publishing is for.
 *
 * It used to be a word on a screen: a trigger fired every workflow with a node
 * instancing it, and the runner read the rows as they stood - so an event
 * arriving while somebody was halfway through drawing ran the half-drawn
 * graph, whatever the badge said. These pin the two halves of the fix: an
 * event runs the copy taken at publication, and a person pressing Run gets
 * what is on their screen.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PublishedGraphTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val source: AppWorkflowGraphSource,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val publications: WorkflowPublicationRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        publications.deleteAll()
        assignments.deleteAll()
        nodes.deleteAll()
        workflows.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        val workflow = workflows.save(Workflow(name = "Answer the customer"))
        workflowId = requireNotNull(workflow.id)
        assignments.save(WorkspaceWorkflow(workspaceId = workspaceId, workflow = workflow))
    }

    /**
     * How many times a node repeats is part of what runs, so it is part of
     * what is published; once is kept as nothing, and nothing past the
     * installation's most is kept. Issue #661.
     */
    @Test
    fun `a node's repeat survives publishing, once is nothing, and it is kept at no more than the most`() {
        save(
            nodes = """
                { key: "call", kind: ACTION, name: "Tell the customer", x: 0, y: 0, repeatTimes: 3 },
                { key: "think", kind: AGENT, name: "Say it went out", x: 200, y: 0, repeatTimes: 1000000 },
                { key: "once", kind: AGENT, name: "Say it once", x: 400, y: 0, repeatTimes: 1 }
            """,
            edges = "",
        )
        publish()

        val live = source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED)
        assertThat(live.nodes.associate { it.key to it.repeatTimes })
            .isEqualTo(mapOf("call" to 3, "think" to 100, "once" to null))
        graphQlTester.document("""{ workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) { nodes { key repeatTimes } } }""")
            .execute().path("workflowGraph.nodes[?(@.key == 'call')].repeatTimes").entityList(Int::class.java).containsExactly(3)
    }

    /** One agent node, named, which is all a graph needs to be runnable. */
    private fun draw(nodeName: String) {
        graphQlTester.document(
            """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                 nodes: [{ key: "one", kind: AGENT, name: "$nodeName", x: 0, y: 0 }], edges: []
               }) { status } }""",
        ).execute().path("saveWorkflowGraph.status").entity(String::class.java).isEqualTo("DRAFT")
    }

    /** A whole graph, the way the editor sends one: every node and every edge. */
    private fun save(nodes: String, edges: String) {
        graphQlTester.document(
            """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                 nodes: [$nodes], edges: [$edges]
               }) { status } }""",
        ).execute().path("saveWorkflowGraph.status").entity(String::class.java).isEqualTo("DRAFT")
    }

    private fun publish() {
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute().path("publishWorkflow.status").entity(String::class.java).isEqualTo("PUBLISHED")
    }

    @Test
    fun `an edit after publishing does not change what an event runs`() {
        draw("Answer politely")
        publish()

        draw("Half-written change")

        // What a trigger, a schedule or the API would run.
        // Which publication it is aside, which the draft has none of.
        val live = source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).copy(publicationId = null)
        assertThat(live.nodes.single().name).isEqualTo("Answer politely")

        // And publishing again is what moves it on.
        publish()
        assertThat(source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes.single().name)
            .isEqualTo("Half-written change")
    }

    @Test
    fun `a person pressing Run gets the graph on their screen`() {
        draw("Answer politely")
        publish()
        draw("Half-written change")

        assertThat(source.graph(workspaceId, workflowId, GraphVersion.DRAFT).nodes.single().name)
            .isEqualTo("Half-written change")
    }

    /**
     * Re-running repeats what ran.
     *
     * A rerun is recorded as manual, because a person pressed it - and manual
     * means the draft, so without saying which copy to use, re-running what a
     * webhook did would run a graph that webhook never touched.
     */
    @Test
    fun `a rerun asks for the copy the original ran`() {
        draw("Answer politely")
        publish()
        draw("Half-written change")

        assertThat(source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes.single().name)
            .isEqualTo("Answer politely")
        assertThat(source.graph(workspaceId, workflowId, GraphVersion.DRAFT).nodes.single().name)
            .isEqualTo("Half-written change")
    }

    @Test
    fun `a workflow nobody has published has nothing to run`() {
        draw("Not ready")

        assertThatThrownBy { source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED) }
            .isInstanceOf(WorkflowNotPublishedException::class.java)
    }

    /**
     * The upgrade case: live when snapshots arrived, so marked published with
     * nothing to point at. Refusing to run those would take a working
     * installation down at the moment it upgraded.
     */
    @Test
    fun `a workflow published before snapshots existed keeps running, and is snapshotted`() {
        draw("From before")
        publish()
        publications.deleteAll()

        assertThat(source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes.single().name)
            .isEqualTo("From before")
        // Taken once, then read like any other.
        assertThat(publications.current(workflowId)).isNotNull()
    }

    @Test
    fun `publishing records who did it`() {
        draw("Answer politely")
        publish()

        assertThat(publications.current(workflowId)?.publishedBy).isEqualTo("alice")
    }

    /**
     * A session is a declaration, and publishing has to fold it away exactly as
     * running the draft does.
     *
     * `drafted()` is where a session stops being a node: what it holds is put
     * onto every agent an edge leads from it to, and neither it nor its edges
     * reach the engine. Publishing writes that copy down, so a divergence would
     * hide here and nowhere else - the engine's vocabulary has no SESSION kind,
     * so a snapshot that kept the node would not read back at all.
     */
    @Test
    fun `a published session is folded onto its agent, exactly as the draft is`() {
        save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Something happened", x: 0, y: 0 },
                { key: "chat", kind: SESSION, name: "The incident", x: 0, y: 120, mappings: [
                  { name: "sessionKeyPrefix", expression: "issue", mode: VALUE },
                  { name: "sessionKey", expression: "ticket", mode: REFERENCE, sourceNodeKey: "start" }
                ] },
                { key: "think", kind: AGENT, name: "Answer politely", x: 200, y: 0, mappings: [
                  { name: "prompt", expression: "Say something kind", mode: VALUE }
                ] }
            """,
            edges = """
                { source: "start", target: "think" },
                { source: "chat", target: "think" }
            """,
        )
        publish()

        // Which publication it is aside, which the draft has none of.
        val live = source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).copy(publicationId = null)

        // Not a step, so not in what runs - and neither is the edge that said
        // which agent reads it.
        assertThat(live.nodes.map { it.key }).containsExactly("start", "think")
        assertThat(live.edges).containsExactly(GraphEdge("start", "think", null))

        // What it held arrives on the agent instead, under the names the runner
        // reads, a referenced key still a reference to the node that makes it.
        assertThat(live.nodes.single { it.key == "think" }.mappings)
            .containsEntry("sessionKeyPrefix", NodeBinding("issue"))
            .containsEntry("sessionKey", NodeBinding("ticket", reference = true, from = "start"))
            .containsEntry("prompt", NodeBinding("Say something kind"))

        // And the published copy is the draft, field for field. Anything the
        // snapshot dropped on the way through would come out here.
        assertThat(live).isEqualTo(source.graph(workspaceId, workflowId, GraphVersion.DRAFT))
    }

    /**
     * The folding happens when the copy is taken, not when it is read.
     *
     * Deleting the session afterwards leaves the graph on the screen without
     * one, and the workflow that was published still talks into that
     * conversation - which is the whole point of publishing a copy.
     */
    @Test
    fun `a session deleted after publishing does not change what an event runs`() {
        save(
            nodes = """
                { key: "chat", kind: SESSION, name: "The incident", x: 0, y: 120, mappings: [
                  { name: "sessionKeyPrefix", expression: "issue", mode: VALUE },
                  { name: "sessionKey", expression: "42", mode: VALUE }
                ] },
                { key: "think", kind: AGENT, name: "Answer politely", x: 200, y: 0 }
            """,
            edges = """{ source: "chat", target: "think" }""",
        )
        publish()

        // The session node is taken off the canvas.
        save(nodes = """{ key: "think", kind: AGENT, name: "Answer politely", x: 200, y: 0 }""", edges = "")

        assertThat(source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes.single().mappings)
            .containsEntry("sessionKey", NodeBinding("42"))
        // While the draft is what it now says: no session, nothing folded on.
        assertThat(source.graph(workspaceId, workflowId, GraphVersion.DRAFT).nodes.single().mappings).isEmpty()
    }

    /**
     * The newest thing a node carries: what it does when it fails.
     *
     * The two halves are kept in different places - a retry policy is on the
     * node, and the fallback is the FAILURE edge, because the engine has no
     * flag for it - so both have to survive being written down and read back.
     * Losing either would leave a published workflow with handling the canvas
     * shows and the run does not have.
     */
    @Test
    fun `an action's retries and its failure edge survive publishing`() {
        save(
            nodes = """
                { key: "call", kind: ACTION, name: "Tell the customer", x: 0, y: 0,
                  fallbackEnabled: true, retryAttempts: 3, retryBackoffSeconds: 30,
                  retryMultiplier: 1.5, retryMaxWaitSeconds: 90, retryJitter: 0.2,
                  retryBudgetSeconds: 300 },
                { key: "report", kind: AGENT, name: "Say it went out", x: 200, y: 0 },
                { key: "apologise", kind: AGENT, name: "Say it did not", x: 200, y: 120 }
            """,
            edges = """
                { source: "call", target: "report" },
                { source: "call", target: "apologise", branch: FAILURE }
            """,
        )
        publish()

        // Which publication it is aside, which the draft has none of.
        val live = source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).copy(publicationId = null)

        val action = live.nodes.single { it.key == "call" }
        assertThat(action.retryAttempts).isEqualTo(3)
        assertThat(action.retryBackoffSeconds).isEqualTo(30)
        // And the whole of the backoff: a published graph whose waits stopped
        // growing, or lost their ceiling, is a policy quietly changed by having
        // been published.
        assertThat(action.retryMultiplier).isEqualTo(1.5)
        assertThat(action.retryMaxWaitSeconds).isEqualTo(90)
        assertThat(action.retryJitter).isEqualTo(0.2)
        assertThat(action.retryBudgetSeconds).isEqualTo(300)

        // The fallback is the edge: a run that could not do the work leaves by
        // the marked one, and the unmarked one is still the way out when it did.
        assertThat(live.edges).containsExactly(
            GraphEdge("call", "report", null),
            GraphEdge("call", "apologise", RunnableEdgeBranch.FAILURE),
        )
        // The switch itself stays where the editor put it, which is what keeps
        // that edge legal the next time this graph is saved.
        assertThat(nodes.findByWorkflowId(workflowId).single { it.nodeKey == "call" }.fallbackEnabled).isTrue()

        assertThat(live).isEqualTo(source.graph(workspaceId, workflowId, GraphVersion.DRAFT))
    }
}
