package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.llm.LlmSession
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.StoredKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * svg_draw and svg_toPng: what the model writes is drawn in the colours it
 * wrote, and an SVG kept under a key comes back as a PNG.
 */
@SpringBootTest
class SvgCapabilityTest(
    @Autowired val svg: SvgCapability,
    @Autowired val scratch: LlmSessionStore,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    /** Left half hsl() red, right half a gradient from blue to green, nothing behind. */
    private val coloured = """
        <svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100">
          <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stop-color="#0000ff"/><stop offset="1" stop-color="rgb(0,255,0)"/>
          </linearGradient></defs>
          <rect x="0" y="0" width="100" height="100" fill="hsl(0, 100%, 50%)"/>
          <rect x="100" y="0" width="100" height="100" fill="url(#g)"/>
          <circle cx="100" cy="50" r="2" fill="teal" fill-opacity="0.5"/>
        </svg>
    """.trimIndent()

    @Test
    fun `a drawing comes out in the colours it was written in`() {
        val answer = run("draw", mapOf("svg" to coloured, "width" to 200))
        val image = picture(answer)

        assertThat(image.width).isEqualTo(200)
        assertThat(rgb(image, 20, 50)).isEqualTo(0xFF0000)
        // The gradient: blue at its start, green at its end.
        assertThat(rgb(image, 101, 50) and 0x0000FF).isGreaterThan(0xC0)
        assertThat(rgb(image, 198, 50) and 0x00FF00).isGreaterThan(0xC000)
    }

    @Test
    fun `a background can be any colour or none`() {
        val sparse = """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"><rect x="5" y="5" width="2" height="2"/></svg>"""

        val dark = picture(run("draw", mapOf("svg" to sparse, "width" to 20, "background" to "#1e293b")))
        assertThat(rgb(dark, 0, 0)).isEqualTo(0x1E293B)

        val clear = picture(run("draw", mapOf("svg" to sparse, "width" to 20, "background" to "transparent")))
        assertThat(clear.getRGB(0, 0) ushr 24).isEqualTo(0)

        assertThat(run("draw", mapOf("svg" to sparse, "background" to "dark blue")).path("error").stringValue())
            .contains("#hex")
    }

    @Test
    fun `svg kept under a key turns into a png`() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "Svg ${System.nanoTime()}")).id)
        val sessionId = requireNotNull(sessions.save(LlmSession(workspaceId = workspaceId, sessionKey = "svg-${System.nanoTime()}")).id)
        val asSvg = run("draw", mapOf("svg" to coloured, "format" to "svg"), sessionId)
        val key = asSvg.path("contentKey").stringValue()
        assertThat(scratch.kindOf(sessionId, key)).isEqualTo(StoredKind("image/svg+xml", true))

        val answer = run("toPng", mapOf("key" to key, "width" to 100), sessionId)
        assertThat(answer.path("pictureType").stringValue()).isEqualTo("image/png")
        assertThat(scratch.kindOf(sessionId, answer.path("contentKey").stringValue())?.contentType).isEqualTo("image/png")
        assertThat(rgb(picture(answer), 10, 25)).isEqualTo(0xFF0000)

        assertThat(run("toPng", mapOf("key" to "nothing.here"), sessionId).path("error").stringValue())
            .contains("nothing.here")
    }

    @Test
    fun `markup that is not svg is refused in a sentence`() {
        assertThat(run("draw", mapOf("svg" to "<html><body/></html>")).path("error").stringValue())
            .contains("does not look like svg")
    }

    private fun run(tool: String, arguments: Map<String, Any>, sessionId: Long? = null): JsonNode =
        mapper.readTree(svg.run(tool, mapper.writeValueAsString(arguments), 0, sessionId))

    private fun picture(answer: JsonNode): BufferedImage {
        assertThat(answer.path("error").isMissingNode).withFailMessage(answer.toString()).isTrue()
        return ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(answer.path("picture").stringValue())))
    }

    private fun rgb(image: BufferedImage, x: Int, y: Int): Int = image.getRGB(x, y) and 0xFFFFFF
}
