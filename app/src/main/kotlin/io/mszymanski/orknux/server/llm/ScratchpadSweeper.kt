package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.attachment.InstallationSettings
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
import java.util.concurrent.TimeUnit

/**
 * How often old scratchpads are looked for, and whether at all.
 *
 * The timer is the installation's; the age is an administrator's, on the Admin
 * screen, because how long a workspace wants its workings kept is a judgement
 * about how it works and not about this product. The suite turns the timer off
 * and calls [ScratchpadSweeper.sweep] itself.
 */
@ConfigurationProperties(prefix = "orknux.scratchpad.sweep")
data class ScratchpadSweepProperties(
    val enabled: Boolean = true,
    /** How long after starting the first pass runs. */
    val initialDelay: Duration = Duration.ofMinutes(5),
    /** And how often after that. Hourly: this is housekeeping, not a deadline. */
    val interval: Duration = Duration.ofHours(1),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScratchpadSweepProperties::class)
class ScratchpadSweepConfig

/**
 * Removing the working files nobody has touched for a long time. Issue #492.
 *
 * A scratchpad belongs to one session and nothing ever took one away, so an
 * installation accumulates every draft every agent has ever written - and the
 * ones holding pictures are megabytes each.
 *
 * **Age from the last change, not from when it was made.** A document still
 * being worked on survives; one nobody has touched since last month is a
 * workings file whose session is over.
 *
 * **The session itself is left alone.** The transcript is the record of what
 * happened and is swept, where it is swept at all, by its own rules; these are
 * the workings beside it.
 *
 * **Zero is never**, which is the answer for an installation that keeps its
 * pads as part of the record, and is why this reads the setting on every pass
 * rather than at startup.
 */
@Component
class ScratchpadSweeper(
    private val pads: SessionScratchpadRepository,
    private val settings: InstallationSettings,
    private val properties: ScratchpadSweepProperties,
) : SmartLifecycle {

    private val clock = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "scratchpad-sweep").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    override fun start() {
        if (!properties.enabled) {
            log.info("Old scratchpads are not swept on a timer")
            return
        }
        running = true
        clock.scheduleAtFixedRate(
            { runCatching { sweep() }.onFailure { log.warn("A scratchpad sweep did not finish: {}", it.message) } },
            properties.initialDelay.toSeconds(),
            properties.interval.toSeconds(),
            TimeUnit.SECONDS,
        )
        log.info("Looking for scratchpads nobody has touched, every {}", properties.interval)
    }

    override fun stop() {
        if (!running) return
        running = false
        clock.shutdownNow()
    }

    override fun isRunning(): Boolean = running

    /**
     * One pass. Answers with how many pads it removed, which is what a test
     * asserts on.
     *
     * The age is read here rather than held, so an administrator who sets it
     * this morning is obeyed by the next pass and not by the next restart.
     */
    @Transactional
    fun sweep(): Int {
        val days = settings.scratchpadKeepDays()
        if (days <= 0) return 0

        val cutoff = OffsetDateTime.now().minusDays(days.toLong())
        val old = pads.findByUpdatedAtBefore(cutoff)
        if (old.isEmpty()) return 0

        pads.deleteAll(old)
        log.info("Removed {} scratchpads nobody had touched for {} days", old.size, days)
        return old.size
    }

    private companion object {
        val log = LoggerFactory.getLogger(ScratchpadSweeper::class.java)
    }
}
