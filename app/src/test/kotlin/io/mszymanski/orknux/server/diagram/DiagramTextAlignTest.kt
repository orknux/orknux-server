package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser

/**
 * Where a box's words sit inside it. Said per node, across and down; kept with
 * the node - through its row, its document and back - left out where nothing
 * was said, and drawn in the pictures where the editor draws it, so an export
 * of a box with its name at the top left does not put the name back in the
 * middle.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramTextAlignTest(
    @Autowired val api: DiagramAPI,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-align-${System.nanoTime()}")).id)
    }

    @Test
    fun `where the words sit is kept with its node, and nothing said is kept as nothing`() {
        val made = api.createDiagram(
            workspaceId, "Aligned", null,
            """{"nodes": [
                 {"id": "a", "label": "A", "x": 0, "y": 0, "w": 400, "h": 200, "align": "left", "valign": "top"},
                 {"id": "b", "label": "B", "x": 500, "y": 0, "align": "Centre", "valign": "bottom"},
                 {"id": "c", "label": "C", "x": 900, "y": 0, "align": "right"},
                 {"id": "d", "label": "D", "x": 1300, "y": 0, "align": null, "valign": ""},
                 {"id": "e", "label": "E", "x": 1700, "y": 0}
               ],
               "edges": []}""",
            null,
        )
        val held = api.drawing(made.id.toLong()).nodes.associateBy { it.id }
        assertThat(held.getValue("a").align).isEqualTo(DiagramTextAlign.LEFT)
        assertThat(held.getValue("a").valign).isEqualTo(DiagramTextValign.TOP)
        assertThat(held.getValue("b").align).isEqualTo(DiagramTextAlign.CENTER)
        assertThat(held.getValue("b").valign).isEqualTo(DiagramTextValign.BOTTOM)
        assertThat(held.getValue("c").align).isEqualTo(DiagramTextAlign.RIGHT)
        assertThat(held.getValue("c").valign).isNull()
        assertThat(held.getValue("d").align).isNull()
        assertThat(held.getValue("d").valign).isNull()
        assertThat(held.getValue("e").align).isNull()

        // In the document a client is handed, only where something was said.
        val shown = api.document.shown(held.getValue("a"))
        assertThat(shown["align"]).isEqualTo("left")
        assertThat(shown["valign"]).isEqualTo("top")
        assertThat(api.document.shown(held.getValue("e"))).doesNotContainKeys("align", "valign")

        assertThatThrownBy { api.document.read("""{"nodes": [{"id": "a", "label": "A", "align": "justified"}], "edges": []}""") }
            .isInstanceOf(DiagramDocumentInvalidException::class.java).hasMessageContaining("align")
        assertThatThrownBy { api.document.read("""{"nodes": [{"id": "a", "label": "A", "valign": "sideways"}], "edges": []}""") }
            .isInstanceOf(DiagramDocumentInvalidException::class.java).hasMessageContaining("valign")
    }

    @Test
    fun `a picture sets the words where the box says`() {
        val svg = DiagramSvg()
        fun drawn(node: DiagramShape) = svg.drawing(DiagramDrawing(listOf(node), emptyList()))
        val box = DiagramShape("a", DiagramNodeKind.BOX, "Name", 0, 0, 400, 200)

        // As every box was drawn: about its middle, both ways.
        assertThat(drawn(box)).contains("<text x=\"200\" y=\"104\" font-size=\"14\" fill=\"#3f3f46\" text-anchor=\"middle\">Name</text>")
        // Said to be where it already was, it is drawn the same.
        assertThat(drawn(box.copy(align = DiagramTextAlign.CENTER, valign = DiagramTextValign.MIDDLE))).isEqualTo(drawn(box))

        // At the top left: from the box's left inset, a line down from its top, and anchored at its start.
        val topLeft = drawn(box.copy(align = DiagramTextAlign.LEFT, valign = DiagramTextValign.TOP))
        assertThat(topLeft).contains("<text x=\"8\" y=\"17\" font-size=\"14\" fill=\"#3f3f46\">Name</text>")
        // At the bottom right: up to its right inset, a line up from its bottom.
        val bottomRight = drawn(box.copy(align = DiagramTextAlign.RIGHT, valign = DiagramTextValign.BOTTOM))
        assertThat(bottomRight).contains("<text x=\"392\" y=\"191\" font-size=\"14\" fill=\"#3f3f46\" text-anchor=\"end\">Name</text>")

        // Two lines at the bottom end on the last line's place, the first a line above it.
        val two = drawn(box.copy(label = "One\nTwo", valign = DiagramTextValign.BOTTOM))
        assertThat(two).contains("y=\"173\"").contains("y=\"191\"")

        // A shape with an outline of its own sets them in the room that outline keeps.
        val process = drawn(DiagramShape("p", DiagramNodeKind.byKey("hexagon")!!, "Name", 0, 0, 400, 200, align = DiagramTextAlign.RIGHT))
        assertThat(process).contains("text-anchor=\"end\">Name</text>")

        // Words alone start at the left, as typed, and go where they are sent.
        val words = DiagramShape("t", DiagramNodeKind.TEXT, "Name", 0, 0, 400, 200)
        assertThat(drawn(words)).contains("<text x=\"4\" y=\"104\"")
        assertThat(drawn(words.copy(align = DiagramTextAlign.RIGHT))).contains("<text x=\"396\" y=\"104\"").contains("text-anchor=\"end\"")

        // A group's name stays at its top left until it is put elsewhere.
        val group = DiagramShape("g", DiagramNodeKind.GROUP, "Name", 0, 0, 400, 200)
        assertThat(drawn(group)).contains("<text x=\"10\" y=\"18\" font-size=\"13\"")
        assertThat(drawn(group.copy(align = DiagramTextAlign.CENTER))).contains("<text x=\"200\" y=\"18\" font-size=\"13\"").contains("text-anchor=\"middle\"")
    }
}
