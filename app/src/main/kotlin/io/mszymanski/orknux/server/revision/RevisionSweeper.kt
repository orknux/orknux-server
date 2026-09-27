package io.mszymanski.orknux.server.revision

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workflow.WorkflowPublicationRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@ConfigurationProperties(prefix = "orknux.revision")
data class RevisionProperties(
    /**
     * How long a component's history is kept, unless an administrator says
     * otherwise on the Admin screen.
     *
     * The floor a fresh installation starts at rather than a limit on what can
     * be chosen: the payloads are function source, tool source and agent
     * prompts, so this is the setting that decides how large the table gets,
     * and it belongs to whoever owns the disk.
     */
    val retentionDays: Int = 14,
    /** False sweeps nothing on a timer. The suite sets it and calls [RevisionSweeper.sweep]. */
    val sweepEnabled: Boolean = true,
    val sweepInterval: Duration = Duration.ofHours(6),
    val sweepInitialDelay: Duration = Duration.ofMinutes(2),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RevisionProperties::class)
class RevisionConfig

/**
 * Throws away history that has been history for longer than was asked.
 *
 * A revision per save with no rule is a table nobody prunes, and the rows are
 * whole copies of source and prompts rather than a line each. So there is a
 * rule, it is a real setting rather than a constant, and something honours it.
 *
 * It measures from when a state stopped being current, not from when it was
 * written: a prompt composed a year ago and replaced this morning is a
 * fortnight of history to come, not a year-old row to drop the moment it is
 * recorded.
 *
 * **A workflow's current publication is never swept, whatever its age.** It is
 * not history — it is what the workflow runs — and a workflow published two
 * years ago and left alone would otherwise be quietly stopped by a tidy-up.
 */
@Component
class RevisionSweeper(
    private val revisions: ComponentRevisionRepository,
    private val publications: WorkflowPublicationRepository,
    private val settings: InstallationSettings,
    private val properties: RevisionProperties,
) : SmartLifecycle {

    /**
     * Built on each start rather than held as a field, because a stopped
     * executor cannot be started again - `shutdownNow` is permanent, and
     * scheduling on one afterwards throws. A context that is stopped and
     * started again is not hypothetical: the test suite does it between
     * classes, and so does anything that restarts the application context in
     * place. Issue #504.
     */
    private var sweeper: ScheduledExecutorService? = null
    private var running = false

    /**
     * Armed only where the timer is wanted, and built either way.
     *
     * A `@ConditionalOnProperty` on the class would have been the shorter
     * spelling and the wrong one: the suite turns the timer off so that no
     * clock deletes a row in the middle of a test, and it still has to be able
     * to call [sweep] itself. A bean that does not exist cannot be asked.
     */
    override fun start() {
        if (!properties.sweepEnabled) {
            log.info("Component history is not swept on a timer")
            return
        }
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "revision-sweep").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            { runCatching(::sweep).onFailure { log.warn("Could not sweep component revisions", it) } },
            properties.sweepInitialDelay.toSeconds(),
            properties.sweepInterval.toSeconds(),
            TimeUnit.SECONDS,
        )
        sweeper = executor
        running = true
        log.info("Sweeping component history older than {} days every {}", retentionDays(), properties.sweepInterval)
    }

    override fun stop() {
        if (!running) return
        sweeper?.shutdownNow()
        sweeper = null
        running = false
    }

    override fun isRunning(): Boolean = running

    /**
     * One pass. Returns how many rows went, which is what a test asserts on.
     *
     * Deleted by id rather than by a bulk statement with a correlated subquery
     * in it: the exclusion is "every workflow's newest publication", which is
     * one query, one set difference and a delete that reads the same on both of
     * the databases this runs on.
     */
    @Transactional
    fun sweep(): Int {
        val cutoff = OffsetDateTime.now().minusDays(retentionDays().toLong())

        val stale = revisions.idsRecordedBefore(cutoff)
        if (stale.isNotEmpty()) revisions.deleteAllByIdInBatch(stale)

        val live = publications.currentIds().toSet()
        val old = publications.idsPublishedBefore(cutoff).filterNot { it in live }
        if (old.isNotEmpty()) publications.deleteAllByIdInBatch(old)

        val swept = stale.size + old.size
        if (swept > 0) log.info("Swept {} revisions recorded before {}", swept, cutoff)
        return swept
    }

    /** What the administrator chose, or what the file said if nobody has. */
    private fun retentionDays(): Int = settings.revisionRetentionDays()

    private companion object {
        val log = LoggerFactory.getLogger(RevisionSweeper::class.java)
    }
}
