package io.mszymanski.orknux.connector.cluster

import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.SimpleLock
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * How long the lease is, read on every renewal so a change on the Admin screen
 * reaches every replica without a restart.
 *
 * The module's question and the app's answer: the setting lives in the
 * installation's settings, which this module cannot see.
 */
fun interface LeaseTimings {
    fun leaseSeconds(): Long
}

/**
 * Which replica does what must be done once. Issue #597.
 *
 * Several servers on one database each run the same timers and would each open
 * the same Slack sockets. What should happen once - a sweep, a check, a socket -
 * asks [leads] first, and exactly one replica says yes.
 *
 * **The lease is ShedLock's, not ours.** One row in `shedlock`, named [name],
 * taken with [LockProvider.lock] and kept with [SimpleLock.extend] every third
 * of the lease. A replica that dies stops extending, the row runs out, and the
 * next replica to ask takes it, so whatever was gated on it moves within one
 * lease. A replica that stops gracefully lets go at once.
 *
 * **Believed for less than the row says.** [leads] answers yes only until one
 * renewal short of the lease's end, measured on this JVM's monotonic clock from
 * the moment the last renewal came back. A replica whose renewals are failing
 * stops believing it leads a third of a lease before anybody else can take the
 * row, so two replicas leading at once needs a pause longer than that third -
 * which is why the lease must be comfortably longer than the worst pause a
 * replica takes.
 *
 * **Required where a second replica would be wrong rather than wasteful.** The
 * inline engine carries runs and tasks on the threads of the process that
 * started them and revives everything at RUNNING when a process comes up, so a
 * second server on it takes the first one's live work for abandoned work. With
 * [requireSole], a server that cannot take the lease waits one lease - long
 * enough for a crashed predecessor's row to run out - and then refuses to start.
 *
 * **Off is one process alone.** With [enabled] false nothing is written and
 * [leads] is always yes: the suite runs that way, because one JVM there holds
 * several contexts on one database and they are not replicas of each other.
 */
class ClusterLeader(
    private val locks: LockProvider,
    private val timings: LeaseTimings,
    private val enabled: Boolean = true,
    private val requireSole: Boolean = false,
    /** Who this replica is, as the lease row records it. */
    val instance: String = defaultInstance(),
    /** The row's name; a test uses its own so it never contends with a context. */
    val name: String = LEADER,
    /** Who currently holds [name], for the Doctor page; null where nobody does or it cannot be read. */
    private val holderOf: (String) -> String? = { null },
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    private val lock = Any()

    @Volatile
    private var held: SimpleLock? = null

    /** Monotonic: when this JVM stops believing it leads. */
    @Volatile
    private var believedUntilNanos = 0L

    @Volatile
    private var running = false

    private var clock: ScheduledExecutorService? = null

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** Whether this replica is the one to do what must be done once, right now. */
    fun leads(): Boolean = !enabled || (held != null && System.nanoTime() < believedUntilNanos)

    /** How long the lease is right now: how far behind a follower must keep anything it would resume from. */
    fun lease(): Duration = Duration.ofSeconds(leaseSeconds())

    /** Whether the lease is in use at all; false is one process alone. */
    fun isEnabled(): Boolean = enabled

    /** Who holds the lease, this replica included. Null where nobody does or it cannot be said. */
    fun holder(): String? = if (!enabled) instance else if (leads()) instance else runCatching { holderOf(name) }.getOrNull()

    /**
     * Told `true` when this replica comes to lead and `false` when it stops,
     * on the renewing thread. What it does there should be quick: closing a
     * socket, not a sweep.
     */
    fun onChange(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    override fun start() {
        if (!enabled) {
            log.info("No cluster lease: this server takes itself to be the only one on its database")
            return
        }
        clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "cluster-lease").apply { isDaemon = true } }
        running = true
        renew()
        if (requireSole && !leads()) awaitSole()
        arm()
        log.info(
            "Cluster lease {} {} as {}, for {}s at a time",
            name,
            if (leads()) "held" else "held by another server",
            instance,
            leaseSeconds(),
        )
    }

    override fun stop() {
        if (!running) return
        running = false
        clock?.shutdownNow()
        clock = null
        synchronized(lock) {
            val was = leads()
            held?.let { runCatching { it.unlock() }.onFailure { failure -> log.warn("Could not let go of the cluster lease", failure) } }
            held = null
            believedUntilNanos = 0
            if (was) tell(false)
        }
    }

    override fun isRunning(): Boolean = running

    /** Before anything that asks [leads]: the sweeps and the listeners start after this has had its go. */
    override fun getPhase(): Int = PHASE

    /**
     * One renewal: extend the lease held, or try to take it. Visible so a test
     * can step a replica by hand rather than wait on its clock.
     *
     * A database that does not answer is neither a yes nor a no: the lease is
     * kept as it was, and [leads] stops saying yes when the belief runs out.
     */
    fun renew() = synchronized(lock) {
        val before = leads()
        val lease = Duration.ofSeconds(leaseSeconds())
        val asked = System.nanoTime()
        try {
            val current = held
            val next = if (current != null) {
                current.extend(lease, Duration.ZERO).orElse(null)
                    ?: locks.lock(LockConfiguration(Instant.now(), name, lease, Duration.ZERO)).orElse(null)
            } else {
                locks.lock(LockConfiguration(Instant.now(), name, lease, Duration.ZERO)).orElse(null)
            }
            held = next
            believedUntilNanos = if (next != null) asked + (lease - renewEvery(lease)).toNanos() else 0
        } catch (failure: Exception) {
            log.warn("Could not renew the cluster lease {}: {}", name, failure.message)
        }
        val after = leads()
        if (after != before) {
            if (after) log.info("This server ({}) now leads", instance) else log.warn("This server ({}) no longer leads", instance)
            tell(after)
        }
    }

    /**
     * Stops renewing without letting go, as a replica that died would. For a
     * test of failover; nothing else should want it.
     */
    fun abandon() {
        running = false
        clock?.shutdownNow()
        clock = null
        synchronized(lock) {
            held = null
            believedUntilNanos = 0
        }
    }

    private fun awaitSole() {
        val lease = Duration.ofSeconds(leaseSeconds())
        val deadline = System.nanoTime() + (lease + renewEvery(lease)).toNanos()
        log.warn(
            "Another server holds the cluster lease ({}). Waiting up to {}s in case it is a server that stopped " +
                "without letting go.",
            runCatching { holderOf(name) }.getOrNull() ?: "unknown",
            (lease + renewEvery(lease)).toSeconds(),
        )
        while (!leads() && System.nanoTime() < deadline) {
            Thread.sleep(renewEvery(lease).toMillis().coerceAtMost(SOLE_POLL_MILLIS))
            renew()
        }
        if (!leads()) {
            running = false
            clock?.shutdownNow()
            clock = null
            throw IllegalStateException(
                "Another server (${runCatching { holderOf(name) }.getOrNull() ?: "unknown"}) is running on this " +
                    "database with the inline engine, which carries work on one server only - a second one would take " +
                    "the first one's runs and tasks for abandoned and run them again. Stop the other server, or set " +
                    "ORKNUX_TEMPORAL_ENABLED=true on both to run more than one.",
            )
        }
    }

    private fun arm() {
        if (!running) return
        val every = renewEvery(Duration.ofSeconds(leaseSeconds()))
        runCatching {
            clock?.schedule({
                try {
                    renew()
                } finally {
                    arm()
                }
            }, every.toMillis(), TimeUnit.MILLISECONDS)
        }.onFailure { if (running) log.warn("The cluster lease could not be scheduled", it) }
    }

    private fun tell(leading: Boolean) {
        listeners.forEach { listener ->
            runCatching { listener(leading) }.onFailure { log.warn("A cluster lease listener failed", it) }
        }
    }

    private fun leaseSeconds(): Long = runCatching { timings.leaseSeconds() }.getOrDefault(DEFAULT_LEASE_SECONDS)
        .coerceAtLeast(MIN_LEASE_SECONDS)

    companion object {
        /** The one lease everything gated on leadership shares. */
        const val LEADER = "orknux-leader"

        /** The table ShedLock keeps its rows in, created by Flyway. */
        const val TABLE = "shedlock"

        /** Early, so a sweep's first look already has an answer; late to stop, so the sweeps stop first. */
        const val PHASE = Int.MIN_VALUE + 1000

        const val DEFAULT_LEASE_SECONDS = 30L

        /** Below this a renewal every third of it is a write every second and a GC pause is a lost lease. */
        const val MIN_LEASE_SECONDS = 3L

        private const val SOLE_POLL_MILLIS = 1000L

        /**
         * A third of the lease: two renewals can fail before the row runs out,
         * and the belief ends one renewal before it does.
         */
        fun renewEvery(lease: Duration): Duration = lease.dividedBy(3).coerceAtLeast(Duration.ofMillis(500))

        /** The host name and a random suffix, so two replicas on one host are two holders. */
        fun defaultInstance(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.ifBlank { null } ?: "server"
            return "$host-${UUID.randomUUID().toString().take(8)}"
        }

        /** One process alone, for a caller built outside a context - a test, mostly. */
        fun alone(): ClusterLeader = ClusterLeader(
            locks = { throw IllegalStateException("No lease is taken by a server that is alone") },
            timings = { DEFAULT_LEASE_SECONDS },
            enabled = false,
        )
    }
}
