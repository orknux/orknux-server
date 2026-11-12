package io.mszymanski.orknux.server.recyclebin

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * A bin nobody empties is a bin that only grows, so the clock empties it.
 *
 * The retention is the Tunable RECYCLE_BIN_RETENTION_DAYS - seven days by
 * default - read on every pass, so a change on Admin -> Settings takes on the
 * next sweep without a restart. The scheduler is off in the suite
 * (`db-scheduler.enabled=false`), so this drives the pass itself, as the
 * revision retention test does.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class RecycleBinSweepTest(
    @Autowired val recycleBin: RecycleBin,
    @Autowired val entries: RecycleBinRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    /**
     * The one table these counts are exact against. Nothing references a bin
     * entry, so emptying it cascades to nothing, and the sweep is
     * installation-wide - so this writes the only old rows and everything else
     * in the suite writes rows dated now.
     */
    @BeforeEach
    fun reset() {
        entries.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "recycle-sweep-${counter.incrementAndGet()}")).id)
    }

    /** Older than the retention goes; within it stays. The default is a week. */
    @Test
    fun `the sweep purges entries past the retention and leaves the rest`() {
        val stale = bin(deletedAt = OffsetDateTime.now().minusDays(20))
        val recent = bin(deletedAt = OffsetDateTime.now().minusDays(3))

        assertThat(recycleBin.sweep()).isEqualTo(1)
        assertThat(entries.findById(stale)).isEmpty()
        assertThat(entries.findById(recent)).isPresent()
    }

    private fun bin(deletedAt: OffsetDateTime): Long = requireNotNull(
        entries.save(
            RecycleBinEntry(
                workspaceId = workspaceId,
                kind = RecycleBinKind.TOOL,
                originalId = 1,
                name = "forecast",
                deletedAt = deletedAt,
                deletedBy = "alice",
                snapshot = """{"version":1,"name":"forecast"}""",
            ),
        ).id,
    )

    private companion object {
        val counter = AtomicLong(System.nanoTime())
    }
}
