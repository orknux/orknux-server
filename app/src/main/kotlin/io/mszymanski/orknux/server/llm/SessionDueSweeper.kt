package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.cluster.ClusterLeader
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Announces what has come due at a session since the last look, so whatever
 * owns the session can wake it.
 *
 * An answer is announced as it is posted, because it is due at once. A reminder
 * is due later, and when it comes due nothing is happening: a turn still running
 * reads it between rounds by itself, but a session whose turn has ended has
 * nobody to notice. This is that somebody, every `orknux.sessions.due-sweep`.
 *
 * Each event is announced once, when it crosses from not due to due - a session
 * whose owner cannot be woken is not announced again every tick for ever. On a
 * start, what came due while the server was down is announced, back a day.
 *
 * An executor built on each start rather than held, for the reason
 * ShellSessionSweeper writes down: a stopped one cannot be started again.
 */
@Component
class SessionDueSweeper(
    private val events: SessionEventRepository,
    private val published: ApplicationEventPublisher,
    private val properties: SessionProperties,
    /** Which replica announces; alone where nothing says otherwise. Issue #597. */
    private val leader: ClusterLeader = ClusterLeader.alone(),
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    private var clock: ScheduledExecutorService? = null

    @Volatile
    private var running = false

    /** Where the last look reached; the next one announces what came due after it. */
    @Volatile
    private var lookedUpTo: OffsetDateTime = OffsetDateTime.now().minusDays(1)

    override fun start() {
        if (!properties.dueSweepEnabled) return
        clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "session-due-sweep").apply { isDaemon = true } }
        running = true
        val every = properties.dueSweep.toMillis().coerceAtLeast(MIN_MILLIS)
        clock?.scheduleWithFixedDelay({ runCatching { timedPass() }.onFailure { log.warn("The due sweep failed", it) } },
            every, every, TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        running = false
        clock?.shutdownNow()
        clock = null
    }

    override fun isRunning(): Boolean = running

    /**
     * What the timer calls. Issue #597.
     *
     * On the leader, a look. Every replica announced every reminder before
     * this, so each woke its session once per replica.
     *
     * On a follower, no look - but the mark is kept one lease behind the
     * clock, so a follower that takes over reads back over the gap the dead
     * leader left rather than starting from now and skipping it. What it reads
     * twice is only what is still unread, since the query asks for that, and
     * a wake for something still owed is a wake that was due anyway.
     */
    fun timedPass(): Int {
        if (!leader.leads()) {
            lookedUpTo = OffsetDateTime.now().minus(leader.lease())
            return 0
        }
        return sweep()
    }

    /** One look. Returns how many sessions were announced, which is what a test asserts on. */
    fun sweep(): Int {
        val now = OffsetDateTime.now()
        val sessions = events.sessionsDueBetween(lookedUpTo, now)
        lookedUpTo = now
        sessions.forEach { published.publishEvent(SessionEventDue(it)) }
        return sessions.size
    }

    private companion object {
        /** A floor under the setting, so a zero does not spin a core. */
        const val MIN_MILLIS = 500L
    }
}
