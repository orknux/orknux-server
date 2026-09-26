package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.llm.SessionScratchpadService
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * An archive of what the session already holds. Issues #467 and #499.
 *
 * What is pinned here is the part that was wrong in the wild rather than in a
 * test: a picture packed from a content key came out as the stored row itself,
 * quotes and all, so the PNG in the archive opened as text beginning with a
 * quotation mark. The bytes have to survive the round trip, and the only way to
 * know they did is to open the archive and look.
 */
@SpringBootTest
class ZipToolsTest(
    @Autowired val zips: ZipTools,
    @Autowired val pads: SessionScratchpadService,
    @Autowired val scratch: LlmSessionStore,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: tools.jackson.databind.ObjectMapper,
) {

    private var session: Long = 0

    /** A one-pixel PNG, which is a real picture and small enough to read by eye. */
    private val pixel =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    @BeforeEach
    fun make() {
        val workspaceId = requireNotNull(
            (workspaces.findByName("zips") ?: workspaces.save(Workspace(name = "zips"))).id,
        )
        session = recorder.open(workspaceId, "test", "zips-${System.nanoTime()}")
    }

    @Test
    fun `a picture packed from a key comes out as the picture`() {
        // Stored the way every producer stores one: the content as a JSON value.
        scratch.put(session, "chart.png", mapper.writeValueAsString(pixel))
        pads.create(session, "index.html", null, "<img src=\"chart.png\">")

        val said = zips.run(
            """
            {"files":[{"name":"index.html","scratchpad":"index.html"},
                      {"name":"images/chart.png","contentKey":"chart.png"}],
             "name":"report.zip"}
            """.trimIndent(),
            session,
        )
        assertThat(said).contains("\"zipped\":2")

        val key = mapper.readTree(said).path("contentKey").stringValue()
        val stored = requireNotNull(scratch.get(session, key))
        val archive = Base64.getDecoder().decode(mapper.readTree(stored).stringValue())

        val held = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                held[entry.name] = zip.readBytes()
            }
        }

        assertThat(held.keys).containsExactlyInAnyOrder("index.html", "images/chart.png")
        assertThat(held["index.html"]?.toString(Charsets.UTF_8)).isEqualTo("<img src=\"chart.png\">")

        /*
         * The whole point: real bytes, not the row that held them. A PNG opens
         * with the eight bytes below, and what shipped instead began with a
         * quotation mark and the letters of the base64.
         */
        val png = requireNotNull(held["images/chart.png"])
        assertThat(png.take(8)).isEqualTo(listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() })
        assertThat(png).isEqualTo(Base64.getDecoder().decode(pixel))
    }

    /** And a file that is text stays text, which is the other half of the guess. */
    @Test
    fun `text given outright is written as it stands`() {
        val said = zips.run("""{"files":[{"name":"notes.txt","text":"two words"}]}""", session)
        val key = mapper.readTree(said).path("contentKey").stringValue()
        val archive = Base64.getDecoder().decode(
            mapper.readTree(requireNotNull(scratch.get(session, key))).stringValue(),
        )

        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            val entry = requireNotNull(zip.nextEntry)
            assertThat(entry.name).isEqualTo("notes.txt")
            assertThat(zip.readBytes().toString(Charsets.UTF_8)).isEqualTo("two words")
        }
    }
}
