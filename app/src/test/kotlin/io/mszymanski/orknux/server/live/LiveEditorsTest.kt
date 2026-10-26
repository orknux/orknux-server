package io.mszymanski.orknux.server.live

import io.mszymanski.orknux.server.design.WireframeAPI
import io.mszymanski.orknux.server.diagram.DiagramAPI
import io.mszymanski.orknux.server.diagram.DiagramDrawing
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Several people in one diagram, document or wireframe editor, which saves as
 * it is edited. Issue #683, on the rooms the workflow editor has had since #645.
 *
 * The save is guarded by the revision it was made from, now claimed in the
 * same statement that checks it; the audit hears once per visit from an
 * editor and every time from anything else; and whoever has it open hears
 * that it was saved - from the editor and from the orknux_* tools alike.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LiveEditorsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val rooms: LiveRooms,
    @Autowired val live: LiveAPI,
    @Autowired val diagramApi: DiagramAPI,
    @Autowired val wireframeApi: WireframeAPI,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "together ${System.nanoTime()}")).id)
    }

    /** One of the three editors: how to make one, save it, and what its refusal is called. */
    private inner class Editor(val kind: LiveKind, val saving: String, val refused: String, val body: (String) -> String) {
        val id: Long = when (kind) {
            LiveKind.DIAGRAM -> graphQlTester.document("""mutation { createDiagram(workspaceId: $workspaceId, name: "Shared") { id } }""")
                .execute().path("createDiagram.id").entity(Long::class.java).get()
            LiveKind.WIREFRAME -> graphQlTester.document("""mutation { createWireframe(workspaceId: $workspaceId, name: "Shared") { id } }""")
                .execute().path("createWireframe.id").entity(Long::class.java).get()
            LiveKind.DOCUMENT -> graphQlTester.document("""mutation { createDocument(workspaceId: $workspaceId, name: "Shared") { id } }""")
                .execute().path("createDocument.id").entity(Long::class.java).get()
            LiveKind.WORKFLOW -> error("not here")
        }
        val key = LiveRooms.Key(kind, id)

        fun save(said: String, revision: Int?, viewer: String?) = graphQlTester.document(
            """mutation(${'$'}id: ID!, ${'$'}d: String!, ${'$'}r: Int, ${'$'}v: String) { $saving(id: ${'$'}id, ${field()}: ${'$'}d, revision: ${'$'}r, viewer: ${'$'}v) { revision } }""",
        ).variable("id", id).variable("d", body(said)).variable("r", revision).variable("v", viewer).execute()

        fun revision(): Int = graphQlTester.document("""{ ${kind.path}(id: $id) { revision } }""")
            .execute().path("${kind.path}.revision").entity(Int::class.java).get()

        private fun field() = if (kind == LiveKind.DOCUMENT) "content" else "document"
    }

    private fun editors() = listOf(
        Editor(LiveKind.DIAGRAM, "saveDiagram", "DiagramChanged") { """{"nodes":[{"id":"a","kind":"rounded","label":"$it","x":0,"y":0}],"edges":[]}""" },
        Editor(LiveKind.WIREFRAME, "saveWireframe", "WireframeChanged") { """{"elements":[{"id":"a","type":"rect","x":0,"y":0,"w":10,"h":10,"text":"$it"}]}""" },
        Editor(LiveKind.DOCUMENT, "saveDocument", "DocumentChanged") {
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$it"}]}]}"""
        },
    )

    @Test
    fun `a save over the revision it read goes through, one over an older one is refused, and one with none always goes`() {
        editors().forEach { editor ->
            val read = editor.revision()
            editor.save("First", read, "tab-a").path("${editor.saving}.revision").entity(Int::class.java).isEqualTo(read + 1)
            editor.save("Stale", read, "tab-b").errors().satisfy { errors ->
                assertThat(errors.single().extensions["code"]).describedAs(editor.kind.path).isEqualTo(editor.refused)
            }
            editor.save("Tool", null, null).path("${editor.saving}.revision").entity(Int::class.java).isEqualTo(read + 2)
        }
    }

    @Test
    fun `an editor's saves reach the audit once per visit, and anything else's every time`() {
        editors().forEach { editor ->
            fun lines() = audit.findAll().count { it.workspaceId == workspaceId && it.message.contains("Shared") && it.message.contains("saved") }
            val seat = rooms.join(editor.key, "tab-a", "alice")
            try {
                val before = lines()
                repeat(3) { editor.save("Again $it", editor.revision(), "tab-a") }
                assertThat(lines()).describedAs(editor.kind.path).isEqualTo(before + 1)
                editor.save("From a tool", null, null)
                assertThat(lines()).describedAs(editor.kind.path).isEqualTo(before + 2)
            } finally {
                rooms.leave(seat)
            }
        }
    }

    @Test
    fun `everybody in the room hears that it was saved, from the editor and from the tools`() {
        editors().forEach { editor ->
            val a = rooms.join(editor.key, "tab-a", "alice")
            val b = rooms.join(editor.key, "tab-b", "bob")
            try {
                frames(a); frames(b)
                val saved = editor.save("Shown", editor.revision(), "tab-a").path("${editor.saving}.revision").entity(Long::class.java).get()
                listOf(a, b).forEach { seat ->
                    val heard = frames(seat).last { it.event == LiveRooms.SAVED }
                    assertThat(heard.payload).describedAs(editor.kind.path).isEqualTo(LiveRooms.SavedView(saved, "tab-a", "alice"))
                }

                rooms.pointer(editor.key, "tab-a", "alice", 3.0, 4.0)
                assertThat(frames(b).single().payload).isEqualTo(LiveRooms.PointerView("tab-a", "alice", 3.0, 4.0))
            } finally {
                rooms.leave(a)
                rooms.leave(b)
            }
        }
        // The orknux_* tools save through another door, which says so too, with no editor behind it.
        val diagram = editors().first()
        val seat = rooms.join(diagram.key, "tab-a", "alice")
        try {
            frames(seat)
            diagramApi.saveDrawing(diagram.id, DiagramDrawing(emptyList(), emptyList()))
            assertThat(frames(seat).single { it.event == LiveRooms.SAVED }.payload)
                .isEqualTo(LiveRooms.SavedView(diagram.revision().toLong(), null, "alice"))
        } finally {
            rooms.leave(seat)
        }
        val wireframe = editors()[1]
        val held = rooms.join(wireframe.key, "tab-a", "alice")
        try {
            frames(held)
            wireframeApi.saveElements(
                wireframe.id,
                listOf(io.mszymanski.orknux.server.design.WireframeElement("a", io.mszymanski.orknux.server.design.WireframeElementType.RECT, 0, 0, 10, 10, "Tool", emptyMap())),
            )
            assertThat(frames(held).single { it.event == LiveRooms.SAVED }.payload)
                .isEqualTo(LiveRooms.SavedView(wireframe.revision().toLong(), null, "alice"))
        } finally {
            rooms.leave(held)
        }
    }

    @Test
    fun `nothing is opened that is not there, nor a workflow, which has a door of its own`() {
        val response = mock(HttpServletResponse::class.java)
        assertThatThrownBy { live.live("diagram", Long.MAX_VALUE, "tab-a", response) }.isInstanceOf(LiveItemNotFoundException::class.java)
        assertThatThrownBy { live.live("workflow", 1, "tab-a", response) }.isInstanceOf(LiveItemNotFoundException::class.java)
        assertThatThrownBy { live.live("diagram", editors().first().id, "not a viewer!", response) }
            .isInstanceOf(io.mszymanski.orknux.server.workflow.live.ViewerInvalidException::class.java)
    }

    @Test
    fun `somebody who cannot see the workspace cannot open its editor's room`() {
        val id = editors().first().id
        val response = mock(HttpServletResponse::class.java)
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "mallory", "x", listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USERS")),
            )
        assertThatThrownBy { live.live("diagram", id, "tab-m", response) }.isInstanceOf(WorkspaceNotFoundException::class.java)
    }

    /** What is waiting for [seat] now, without waiting for more. */
    private fun frames(seat: LiveRooms.Seat): List<LiveRooms.Frame> = buildList {
        while (seat.waiting()) addAll(seat.next().orEmpty())
    }
}
