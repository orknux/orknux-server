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
 * How big a box's words are. Said per node, in whole pixels; kept with the
 * node - through its row, its document and back - left out where nothing was
 * said, and drawn at that size in the pictures, with lines as far apart as the
 * size needs, so an export of a diagram with a headline box does not shrink
 * the headline back to fourteen.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramTextSizeTest(
    @Autowired val api: DiagramAPI,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-size-${System.nanoTime()}")).id)
    }

    @Test
    fun `how big the words are is kept with its node, and nothing said is kept as nothing`() {
        val made = api.createDiagram(
            workspaceId, "Sized", null,
            """{"nodes": [
                 {"id": "a", "label": "A", "x": 0, "y": 0, "w": 400, "h": 200, "textSize": 24},
                 {"id": "b", "label": "B", "x": 500, "y": 0, "textSize": "10"},
                 {"id": "c", "label": "C", "x": 900, "y": 0, "textSize": 17.6},
                 {"id": "d", "label": "D", "x": 1300, "y": 0, "textSize": null},
                 {"id": "e", "label": "E", "x": 1700, "y": 0, "textSize": ""},
                 {"id": "f", "label": "F", "x": 2100, "y": 0}
               ],
               "edges": []}""",
            null,
        )
        val held = api.drawing(made.id.toLong()).nodes.associateBy { it.id }
        assertThat(held.getValue("a").textSize).isEqualTo(24)
        assertThat(held.getValue("b").textSize).isEqualTo(10)
        assertThat(held.getValue("c").textSize).isEqualTo(18)
        assertThat(held.getValue("d").textSize).isNull()
        assertThat(held.getValue("e").textSize).isNull()
        assertThat(held.getValue("f").textSize).isNull()

        // In the document a client is handed, only where something was said.
        assertThat(api.document.shown(held.getValue("a"))["textSize"]).isEqualTo(24)
        assertThat(api.document.shown(held.getValue("f"))).doesNotContainKey("textSize")

        // Saved again as it was read, it is still there.
        api.saveDrawing(made.id.toLong(), api.drawing(made.id.toLong()))
        assertThat(api.drawing(made.id.toLong()).nodes.first { it.id == "a" }.textSize).isEqualTo(24)

        listOf("5", "97", "\"big\"", "true").forEach { said ->
            assertThatThrownBy { api.document.read("""{"nodes": [{"id": "a", "label": "A", "textSize": $said}], "edges": []}""") }
                .isInstanceOf(DiagramDocumentInvalidException::class.java).hasMessageContaining("textSize")
        }
    }

    @Test
    fun `a picture draws the words at the size the box says`() {
        val svg = DiagramSvg()
        fun drawn(node: DiagramShape) = svg.drawing(DiagramDrawing(listOf(node), emptyList()))
        val box = DiagramShape("a", DiagramNodeKind.BOX, "Name", 0, 0, 400, 200)

        // As every box was drawn.
        assertThat(drawn(box)).contains("<text x=\"200\" y=\"104\" font-size=\"14\" fill=\"#3f3f46\" text-anchor=\"middle\">Name</text>")
        // Said to be the size it already was, it is drawn the same.
        assertThat(drawn(box.copy(textSize = 14))).isEqualTo(drawn(box))

        // Bigger: still about the middle, its baseline lower by what a taller letter needs.
        assertThat(drawn(box.copy(textSize = 24))).contains("<text x=\"200\" y=\"108\" font-size=\"24\" fill=\"#3f3f46\" text-anchor=\"middle\">Name</text>")
        // Two lines are as far apart as the size needs - thirty-one pixels at twenty-four, where fourteen keeps eighteen.
        assertThat(drawn(box.copy(label = "One\nTwo", textSize = 24))).contains("y=\"93\" font-size=\"24\"").contains("y=\"124\" font-size=\"24\"")
        assertThat(drawn(box.copy(label = "One\nTwo"))).contains("y=\"95\" font-size=\"14\"").contains("y=\"113\" font-size=\"14\"")

        // Bigger words fill a line sooner, so they wrap sooner.
        val long = box.copy(label = "order saga service that settles every payment", w = 200)
        assertThat(Regex("<text ").findAll(drawn(long.copy(textSize = 24))).count()).isGreaterThan(Regex("<text ").findAll(drawn(long)).count())
        // And what is called cut is what the picture cuts: these words fit a small box at ten and not at forty.
        val tight = DiagramShape("t", DiagramNodeKind.BOX, "order saga", 0, 0, 120, 40)
        assertThat(svg.cut(tight.copy(textSize = 10))).isFalse()
        assertThat(svg.cut(tight.copy(textSize = 40))).isTrue()

        // Words alone, a shape with an outline of its own, and a group's name all take it.
        assertThat(drawn(DiagramShape("w", DiagramNodeKind.TEXT, "Name", 0, 0, 400, 200, textSize = 30))).contains("font-size=\"30\"")
        assertThat(drawn(DiagramShape("p", DiagramNodeKind.byKey("hexagon")!!, "Name", 0, 0, 400, 200, textSize = 30))).contains("font-size=\"30\"")
        val group = DiagramShape("g", DiagramNodeKind.GROUP, "Name", 0, 0, 400, 200)
        assertThat(drawn(group)).contains("<text x=\"10\" y=\"18\" font-size=\"13\"")
        assertThat(drawn(group.copy(textSize = 20))).contains("<text x=\"10\" y=\"25\" font-size=\"20\"")
    }
}
