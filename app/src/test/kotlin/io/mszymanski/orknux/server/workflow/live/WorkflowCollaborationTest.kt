package io.mszymanski.orknux.server.workflow.live

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Several people in one workflow's editor, which saves as it is edited.
 * Issue #645.
 *
 * The save is guarded by the revision it was drawn over, so an editor holding
 * an older picture is refused rather than writing over somebody else's work;
 * the audit hears once per visit rather than once per drag; and everybody in
 * the room hears that the graph moved.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkflowCollaborationTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val room: WorkflowRoom,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "together ${System.nanoTime()}")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Shared" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    private fun revision(): Long = graphQlTester.document(
        """{ workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) { revision } }""",
    ).execute().path("workflowGraph.revision").entity(Long::class.java).get()

    private fun save(base: Long?, viewer: String?, name: String = "Note") = graphQlTester.document(
        """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
             nodes: [{ key: "a", kind: OBJECT, name: "$name", x: 0, y: 0 }], edges: [],
             ${base?.let { "baseRevision: $it," } ?: ""} ${viewer?.let { "viewer: \"$it\"" } ?: ""}
           }) { revision nodes { name } } }""",
    ).execute()

    @Test
    fun `a save over the revision it read goes through, one over an older one is refused, and a tool's always goes`() {
        val read = revision()
        save(read, "tab-a").path("saveWorkflowGraph.revision").entity(Long::class.java).isEqualTo(read + 1)

        // Somebody who read the graph before that save is told, and nothing of theirs is written.
        save(read, "tab-b", name = "Stale").errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("WorkflowGraphMoved")
        }
        graphQlTester.document("""{ workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) { nodes { name } } }""")
            .execute().path("workflowGraph.nodes[0].name").entity(String::class.java).isEqualTo("Note")

        // An orknux_* tool reads no revision and sends the whole graph it means.
        save(null, null).path("saveWorkflowGraph.revision").entity(Long::class.java).isEqualTo(read + 2)
    }

    @Test
    fun `an editor's saves reach the audit once per visit, and anything else's every time`() {
        fun lines() = audit.findAll().count { it.workspaceId == workspaceId && it.message == "Workflow Shared graph updated" }
        val before = lines()
        val stream = room.join(workflowId, "tab-a", "alice")
        try {
            save(revision(), "tab-a")
            save(revision(), "tab-a")
            save(revision(), "tab-a")
            assertThat(lines()).isEqualTo(before + 1)
            save(null, null)
            assertThat(lines()).isEqualTo(before + 2)
        } finally {
            room.leave(stream)
        }
    }

    @Test
    fun `everybody in the room hears who is there, where the others point, and that the graph moved`() {
        val a = room.join(workflowId, "tab-a", "alice")
        val b = room.join(workflowId, "tab-b", "bob")
        try {
            // Each hears who is there; a hears itself arrive and then b.
            assertThat(frames(a).last().payload.toString()).contains("tab-a", "tab-b")
            assertThat(frames(b).last().payload.toString()).contains("tab-a", "tab-b")

            room.pointer(workflowId, "tab-a", "alice", 10.0, 20.0)
            // A pointer sent under somebody else's login is not passed on.
            room.pointer(workflowId, "tab-a", "mallory", 99.0, 99.0)
            val seen = frames(b).single()
            assertThat(seen.event).isEqualTo("pointer")
            assertThat(seen.payload).isEqualTo(io.mszymanski.orknux.server.live.LiveRooms.PointerView("tab-a", "alice", 10.0, 20.0))
            assertThat(frames(a)).isEmpty()

            val saved = save(revision(), "tab-a").path("saveWorkflowGraph.revision").entity(Long::class.java).get()
            listOf(a, b).forEach { seat ->
                val moved = frames(seat).single { it.event == "graph" }
                assertThat(moved.payload).isEqualTo(io.mszymanski.orknux.server.live.LiveRooms.SavedView(saved, "tab-a", "alice"))
            }

            room.leave(a)
            val left = frames(b)
            assertThat(left.map { it.event }).containsExactly("pointer", "presence")
            assertThat(left.first().payload).isEqualTo(io.mszymanski.orknux.server.live.LiveRooms.PointerView("tab-a", "alice", null, null))
            assertThat(room.viewers(workflowId)).containsExactly(io.mszymanski.orknux.server.live.LiveRooms.ViewerView("tab-b", "bob"))
        } finally {
            room.leave(a)
            room.leave(b)
        }
    }

    @Test
    fun `how long the editor waits before it saves is an admin setting`() {
        graphQlTester.document("{ installationSettings { workflowAutosaveMillis workflowAutosaveMillisConfigured } }").execute()
            .path("installationSettings.workflowAutosaveMillisConfigured").entity(Int::class.java).isEqualTo(300)
        graphQlTester.document("mutation { setWorkflowAutosaveMillis(millis: 1500) { workflowAutosaveMillis } }").execute()
            .path("setWorkflowAutosaveMillis.workflowAutosaveMillis").entity(Int::class.java).isEqualTo(1500)
        graphQlTester.document("""{ workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) { autosaveMillis } }""")
            .execute().path("workflowGraph.autosaveMillis").entity(Int::class.java).isEqualTo(1500)
        listOf(199, 10_001).forEach { millis ->
            graphQlTester.document("mutation { setWorkflowAutosaveMillis(millis: $millis) { workflowAutosaveMillis } }")
                .execute().errors().satisfy { errors ->
                    assertThat(errors.single().extensions["code"]).isEqualTo("WorkflowAutosaveOutOfRange")
                }
        }
        graphQlTester.document("mutation { setWorkflowAutosaveMillis(millis: 800) { workflowAutosaveMillis } }").execute()
    }

    /** What is waiting for [seat] now, without waiting for more. */
    private fun frames(seat: io.mszymanski.orknux.server.live.LiveRooms.Seat): List<io.mszymanski.orknux.server.live.LiveRooms.Frame> = buildList {
        while (seat.waiting()) addAll(seat.next().orEmpty())
    }
}
