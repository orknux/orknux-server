package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.EntityLoads
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.OffsetDateTime

/**
 * The scratchpad sweep: what it removes, what it keeps, and that it removes
 * without reading. Issues #492 and #616.
 *
 * The pads it finds are the old ones, and the old ones are where the pictures
 * are, so a sweep that read them in to delete them held every expired megabyte
 * at once. The count of pads loaded is what tells the two apart; the answer is
 * the same either way.
 */
@SpringBootTest
class ScratchpadSweepTest(
    @Autowired val sweeper: ScratchpadSweeper,
    @Autowired val pads: SessionScratchpadRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val factory: EntityManagerFactory,
) {

    private var session: Long = 0

    @BeforeEach
    fun make() {
        pads.deleteAll()
        val workspaceId = requireNotNull(
            (workspaces.findByName("pad-sweep") ?: workspaces.save(Workspace(name = "pad-sweep"))).id,
        )
        session = recorder.open(workspaceId, "test", "pad-sweep-${System.nanoTime()}")
    }

    @Test
    fun `old pads go in one statement, and the ones still in use stay`() {
        val old = OffsetDateTime.now().minusDays(400)
        repeat(3) { pads.save(SessionScratchpad(sessionId = session, name = "old-$it", content = "x".repeat(1000), updatedAt = old)) }
        pads.save(SessionScratchpad(sessionId = session, name = "current", content = "still in use"))

        val swept = EntityLoads(factory).of(SessionScratchpad::class) { sweeper.sweep() }

        assertThat(swept.answer).isEqualTo(3)
        assertThat(swept.loaded).describedAs("pads read in to be deleted").isZero()
        assertThat(pads.findBySessionIdOrderByNameAsc(session).map { it.name }).containsExactly("current")
    }

    @Test
    fun `a pass with nothing to remove answers zero`() {
        pads.save(SessionScratchpad(sessionId = session, name = "current", content = "still in use"))

        assertThat(sweeper.sweep()).isZero()
        assertThat(pads.count()).isEqualTo(1)
    }
}
