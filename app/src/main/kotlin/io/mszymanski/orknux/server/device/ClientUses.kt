package io.mszymanski.orknux.server.device

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.user.AppUserRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * One person's copy of one Orknux client, as Admin > Reports > Clients lists it.
 *
 * Kept because presence is not: [DeviceLinks] knows only who is connected to
 * this process right now, and forgets a client an hour after it stops asking.
 * Who uses the Chrome extension, which desktop app and the Shell is a question
 * about the last month, asked of whichever replica answers.
 */
@Entity
@Table(name = "client_use")
class ClientUse(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 120)
    val username: String,

    /** [DeviceKind]'s name: the door it comes in by. */
    @Column(nullable = false, length = 16)
    val kind: String,

    /** What it calls itself: Chrome, Windows, Linux, Shell. */
    @Column(length = 64)
    var client: String? = null,

    /** Which copy of it: the name [DeviceLinks.computers] gives it. */
    @Column(nullable = false, length = 200)
    val name: String,

    @Column(length = 64)
    var version: String? = null,

    /** The system it runs on, read off its User-Agent: what a browser can say of its computer, since it may not know the machine's name. */
    @Column(length = 120)
    var computer: String? = null,

    @Column(name = "first_seen", nullable = false)
    val firstSeen: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_seen", nullable = false)
    var lastSeen: OffsetDateTime = OffsetDateTime.now(),
)

interface ClientUseRepository : JpaRepository<ClientUse, Long> {
    fun findByUsernameAndKindAndName(username: String, kind: String, name: String): ClientUse?
    fun findAllByOrderByLastSeenDesc(): List<ClientUse>
    fun findByUsernameAndKindOrderByLastSeenDesc(username: String, kind: String): List<ClientUse>
}

/**
 * Writes down that a client asked for work - at most once every few minutes
 * for one copy, since a connected client asks every half a minute and the
 * report is about days, not seconds.
 */
@Service
class ClientUses(
    private val uses: ClientUseRepository,
    transactions: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val writing = TransactionTemplate(transactions)

    /** When each copy was last written down by this process. */
    private val written = ConcurrentHashMap<Triple<String, DeviceKind, String>, Instant>()

    /**
     * [user]'s client of [kind] asked. Named as [DeviceLinks] names it: by the
     * copy it says it is, a desktop client by its system, and otherwise by what
     * it calls itself. Never fails the request that called it.
     */
    fun seen(user: String, kind: DeviceKind, client: String?, version: String?, instance: String?, agent: String? = null) {
        val name = nameOf(kind, client, instance)
        val key = Triple(user, kind, name)
        val now = Instant.now()
        val last = written[key]
        if (last != null && Duration.between(last, now) < EVERY) return
        // Bounded: a copy not heard from in a while is forgotten here, and is written again the next time it asks.
        if (written.size > REMEMBERED) written.entries.removeIf { Duration.between(it.value, now) >= EVERY }
        written[key] = now
        runCatching {
            writing.executeWithoutResult {
                val held = uses.findByUsernameAndKindAndName(user, kind.name, name)
                    ?: ClientUse(username = user, kind = kind.name, name = name).also {
                        /*
                         * A new copy: the person's oldest of this kind go past [COPIES],
                         * so a client that names itself afresh on every start - or a
                         * name made up by hand - cannot grow the report for ever.
                         */
                        uses.findByUsernameAndKindOrderByLastSeenDesc(user, kind.name).drop(COPIES - 1).forEach(uses::delete)
                    }
                held.client = client?.take(64)
                held.version = version?.take(64)
                systemOf(agent)?.let { held.computer = it }
                held.lastSeen = OffsetDateTime.now()
                uses.save(held)
            }
        }.onFailure {
            written.remove(key)
            log.debug("Could not write down that {}'s {} asked", user, kind, it)
        }
    }

    companion object {
        /** How often one copy's last-seen moves: the report reads days, and a client asks twice a minute. */
        val EVERY: Duration = Duration.ofMinutes(5)

        /** How many copies of one kind of client are kept per person: more than anybody runs, few enough to stay small. */
        const val COPIES = 20

        /** How many copies this process remembers writing before it forgets the ones not heard from lately. */
        const val REMEMBERED = 10_000

        /**
         * The operating system a User-Agent names, as a person would say it; null
         * where it names none, which a client that is not a browser often does.
         */
        fun systemOf(agent: String?): String? {
            val said = agent ?: return null
            return when {
                "CrOS" in said -> "ChromeOS"
                "Android" in said -> "Android"
                "iPhone" in said || "iPad" in said -> "iOS"
                "Windows" in said -> "Windows"
                "Mac OS X" in said || "Macintosh" in said -> "macOS"
                "Linux" in said || "X11" in said -> "Linux"
                else -> null
            }
        }

        /** The name [DeviceLinks.computers] gives a client, so the report can say which are connected. */
        fun nameOf(kind: DeviceKind, client: String?, instance: String?): String {
            val named = instance?.takeIf { it.isNotBlank() } ?: if (kind == DeviceKind.DESKTOP) client else null
            return (named?.takeIf { it.isNotBlank() } ?: client?.takeIf { it.isNotBlank() } ?: kind.said).take(120)
        }
    }
}

/** One row of the Clients report. */
data class ClientUseView(
    val username: String,
    val displayName: String?,
    val kind: String,
    val client: String?,
    val name: String,
    val version: String?,
    /** The computer: a desktop client's own name for it, otherwise the system it runs on, where that is known. */
    val computer: String?,
    val firstSeen: String,
    val lastSeen: String,
    val connected: Boolean,
)

/** Admin > Reports > Clients. */
@Controller
class ClientUseAPI(
    private val access: WorkspaceAccess,
    private val uses: ClientUseRepository,
    private val links: DeviceLinks,
    private val users: AppUserRepository,
) {

    @QueryMapping
    fun clientUses(): List<ClientUseView> {
        access.requireAdmin()
        val rows = uses.findAllByOrderByLastSeenDesc()
        val names = rows.map { it.username }.distinct().associateWith { users.findByUsername(it)?.displayName }
        return rows.map { row ->
            val kind = runCatching { DeviceKind.valueOf(row.kind) }.getOrNull()
            ClientUseView(
                username = row.username,
                displayName = names[row.username],
                kind = row.kind,
                client = row.client,
                name = row.name,
                version = row.version,
                computer = if (kind == DeviceKind.DESKTOP) row.name else row.computer,
                firstSeen = row.firstSeen.toString(),
                lastSeen = row.lastSeen.toString(),
                connected = kind != null && links.computers(row.username, kind).any { it.name == row.name },
            )
        }
    }
}
