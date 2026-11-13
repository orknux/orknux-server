package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser

/**
 * A shape turned about its middle. The turn is kept with the node - through its
 * row, its document and back - the pictures draw the shape turned, with room
 * for the corners that now reach past the box it was turned from, and a line
 * meets the turned outline: a floating end where the turned shape actually is,
 * a pinned end on the side that turned with it.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramRotationTest(
    @Autowired val api: DiagramAPI,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-rotation-${System.nanoTime()}")).id)
    }

    private val near = Offset.offset(0.5)

    @Test
    fun `a turn is kept with its node, brought into one turn, and upright is kept as nothing`() {
        val made = api.createDiagram(
            workspaceId, "Turned", null,
            """{"nodes": [
                 {"id": "a", "label": "A", "x": 0, "y": 0, "w": 160, "h": 60, "rotation": 90},
                 {"id": "b", "label": "B", "x": 400, "y": 0, "rotation": 450},
                 {"id": "c", "label": "C", "x": 800, "y": 0, "rotation": -90},
                 {"id": "d", "label": "D", "x": 1200, "y": 0, "rotation": 360},
                 {"id": "e", "label": "E", "x": 1600, "y": 0}
               ],
               "edges": []}""",
            null,
        )
        val held = api.drawing(made.id.toLong()).nodes.associateBy { it.id }
        assertThat(held.getValue("a").rotation).isEqualTo(90)
        assertThat(held.getValue("b").rotation).isEqualTo(90)
        assertThat(held.getValue("c").rotation).isEqualTo(270)
        assertThat(held.getValue("d").rotation).isNull()
        assertThat(held.getValue("e").rotation).isNull()
        // The box it was turned from is what is kept: turning moves no number but the turn.
        assertThat(held.getValue("a").w).isEqualTo(160)
        assertThat(held.getValue("a").h).isEqualTo(60)

        // And it is in the document a client is handed, only where there is one.
        val shown = api.document.shown(held.getValue("a"))
        assertThat(shown["rotation"]).isEqualTo(90)
        assertThat(api.document.shown(held.getValue("e"))).doesNotContainKey("rotation")

        assertThatThrownBy { api.document.read("""{"nodes": [{"id": "a", "label": "A", "rotation": "sideways"}], "edges": []}""") }
            .isInstanceOf(DiagramDocumentInvalidException::class.java).hasMessageContaining("rotation")
    }

    @Test
    fun `a picture draws the shape turned, and leaves room for its corners`() {
        val upright = DiagramShape("a", DiagramNodeKind.BOX, "A", 0, 0, 160, 60)
        val turned = upright.copy(rotation = 90)
        val svg = DiagramSvg()

        assertThat(svg.drawing(DiagramDrawing(listOf(upright), emptyList()))).doesNotContain("rotate(")
        assertThat(svg.drawing(DiagramDrawing(listOf(turned), emptyList()))).contains("<g transform=\"rotate(90 80.0 30.0)\">")

        // 160 by 60 turned a quarter stands 60 wide and 160 tall about the same middle.
        val (left, top, width, height) = svg.frame(DiagramDrawing(listOf(turned), emptyList()))
        val (_, _, wide, tall) = svg.frame(DiagramDrawing(listOf(upright), emptyList()))
        assertThat(width).isEqualTo(wide - 100)
        assertThat(height).isEqualTo(tall + 100)
        assertThat(left).isEqualTo(svg.frame(DiagramDrawing(listOf(upright), emptyList()))[0] + 50)
        assertThat(top).isEqualTo(svg.frame(DiagramDrawing(listOf(upright), emptyList()))[1] - 50)
    }

    @Test
    fun `a line meets the turned outline, and a pinned side turns with its box`() {
        // 160 by 60 at the origin, turned a quarter: it now reaches x 50 to 110 and y -50 to 110 about (80, 30).
        val a = DiagramShape("a", DiagramNodeKind.BOX, "a", 0, 0, 160, 60, rotation = 90)
        val b = DiagramShape("b", DiagramNodeKind.BOX, "b", 400, 0, 160, 60)

        // Floating toward a box level with it: out of its right edge, which is at 110 now and not at 160.
        val floating = DiagramRoute.of(a, b, DiagramRouting.STRAIGHT).points!!.first()
        assertThat(floating.first).isCloseTo(110.0, near)
        assertThat(floating.second).isCloseTo(30.0, near)

        // Straight down from its middle: its bottom is at 110, where upright it was at 60.
        val (_, bottom) = DiagramOutline.border(a, 80.0, 1000.0)
        assertThat(bottom).isCloseTo(110.0, near)

        // Pinned to its top, which a quarter turn clockwise has put on its right.
        val pinned = DiagramRoute.of(a, b, DiagramRouting.STRAIGHT, fromSide = DiagramSide.TOP).points!!.first()
        assertThat(pinned.first).isCloseTo(110.0, near)
        assertThat(pinned.second).isCloseTo(30.0, near)
        // And the picture draws it from there: a straight line was exported between
        // the points facing the two middles, whatever side it was pinned to.
        val picture = DiagramSvg().drawing(
            DiagramDrawing(listOf(a, b), listOf(DiagramLink("e", "a", "b", routing = DiagramRouting.STRAIGHT, fromSide = DiagramSide.LEFT))),
        )
        assertThat(picture).contains("<line x1=\"80.0\" y1=\"-50.0\"")
        // And its left, which is now on top.
        val left = DiagramRoute.of(a, b, DiagramRouting.STRAIGHT, fromSide = DiagramSide.LEFT).points!!.first()
        assertThat(left.first).isCloseTo(80.0, near)
        assertThat(left.second).isCloseTo(-50.0, near)

        // An ellipse turned an eighth is met on its own outline, not its box's.
        val oval = DiagramShape("o", DiagramNodeKind.ELLIPSE, "o", 0, 0, 200, 100, rotation = 45)
        val (ox, oy) = DiagramOutline.border(oval, 100.0 + 1000.0, 50.0 + 1000.0)
        // Along its long axis, which a turn of 45 points down and to the right: 100 out from the middle.
        assertThat(Math.hypot(ox - 100.0, oy - 50.0)).isCloseTo(100.0, near)
    }
}
