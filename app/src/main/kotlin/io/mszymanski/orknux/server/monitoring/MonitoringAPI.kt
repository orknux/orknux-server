package io.mszymanski.orknux.server.monitoring

import io.mszymanski.orknux.server.database.isSqlite
import io.mszymanski.orknux.server.database.jdbcUrlOf
import io.mszymanski.orknux.server.security.AuthMethod
import io.mszymanski.orknux.server.security.SecurityProperties
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.workflow.health.ServiceHealth
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.ldap.core.LdapTemplate
import org.springframework.stereotype.Controller
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

/** What a component reports about itself. */
enum class ComponentStatus {
    /** Answering, and everything it depends on is answering too. */
    HEALTHY,

    /** Answering, but something it depends on is not. */
    DEGRADED,

    /** Not answering. */
    DOWN,
}

/**
 * The health of the platform, for the monitoring screen.
 *
 * There is one service and one process: that this code ran is the whole of its
 * own health. What is worth reporting is what it needs to do its work — the
 * database, the directory, and Temporal — so each of those is checked and named.
 * The browser adds its own card for orknux-ui, which it can speak for.
 *
 * What it needs, though, and not what it might have needed. A dependency this
 * installation was never configured with is absent, not unreachable, and it is left
 * out entirely rather than checked and found wanting.
 */
@Controller
class MonitoringAPI(
    private val entityManager: EntityManager,
    private val ldap: LdapTemplate,
    /** Everything that can say whether it is up; Temporal is the one today. */
    private val services: List<ServiceHealth>,
    @Value("\${orknux.version:unknown}") private val version: String,
    private val temporal: TemporalLinks,
    private val access: WorkspaceAccess,
    /** Read for one thing only: whether there is a directory to have an opinion about. */
    private val security: SecurityProperties,
    /** Read for one thing only: which engine the card below should name. See [engine]. */
    dataSource: DataSource,
) {

    /**
     * Which database this installation actually keeps its rows in.
     *
     * The card used to say "Postgres" whatever was underneath, and `orknux-one`
     * — the image most people meet this product through — is SQLite. So the
     * screen an operator opens when something is wrong named the wrong engine
     * precisely where they were least likely to already know better.
     *
     * It is not cosmetic. The two differ in ways that reach whoever is reading
     * this: SQLite takes one writer at a time, has no `information_schema`, and
     * does not enforce a varchar length. Somebody diagnosing a lock timeout on a
     * page that says Postgres goes looking for the wrong thing entirely.
     *
     * Asked the way the rest of the application asks — [isSqlite] over the URL
     * the pool was built from — because that is what already chooses the
     * dialect, the scheduler's SQL, the session store's propagation and the
     * doctor's catalogue query, and a second way of deciding is a second answer
     * waiting to disagree with them. Settled once here: a pool does not change
     * the database it was built against.
     */
    private val engine: String = if (isSqlite(jdbcUrlOf(dataSource))) "SQLite" else "Postgres"

    @QueryMapping
    fun components(): List<ComponentView> {
        access.requireAdmin()

        val dependencies = listOfNotNull(
            check("Database", "$engine, for everything the platform stores") {
                entityManager.createNativeQuery("SELECT 1").singleResult
            },
            /*
             * Only where there is one.
             *
             * An installation signing in with INTERNAL has no directory: nobody
             * configured `spring.ldap.urls`, so the probe went to the default —
             * localhost:389 — found nothing listening, and reported the whole server
             * DEGRADED, "cannot reach directory", for a directory that was never
             * meant to be there. That is not a fault, it is a component this
             * installation does not have, and a card for it is a card that can only
             * ever be red.
             *
             * Absent rather than reported-as-fine, because "Directory: answering"
             * would be a second untruth in place of the first. LDAP and OIDC are
             * untouched: OIDC has kept a directory alongside its provider since
             * before this existed, and nothing here is going to decide for it that
             * it has not.
             *
             * NONE is here for the same reason INTERNAL is, and more plainly: an
             * installation that asks nobody to sign in does not consult a directory
             * to decide anything, so a card reporting on one would be reporting on
             * a dependency it does not have.
             */
            if (security.authMethod == AuthMethod.INTERNAL || security.authMethod == AuthMethod.NONE) {
                null
            } else {
                check("Directory", "LDAP, for who may sign in and what they may see") { ldap.list("") }
            },
        ) + services.map(::probe)

        val unreachable = dependencies.filterNot { it.reachable }
        return listOf(
            ComponentView(
                name = "orknux-server",
                description = "API, sign-in, connections and workflow runs",
                status = if (unreachable.isEmpty()) ComponentStatus.HEALTHY else ComponentStatus.DEGRADED,
                version = version,
                detail = if (unreachable.isEmpty()) {
                    "Answering"
                } else {
                    "Cannot reach " + unreachable.joinToString(", ") { it.name.lowercase() }
                },
                lastCheckedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                dependencies = dependencies,
            ),
        )
    }

    private fun probe(service: ServiceHealth): DependencyView {
        val reachability = service.reachable()
        return DependencyView(
            name = service.service.replaceFirstChar(Char::uppercase),
            description = "Durable execution, for workflow runs",
            reachable = reachability.reachable,
            detail = reachability.detail,
            // Somewhere to go and look, when it is running somewhere anybody can.
            url = temporal.home(),
        )
    }

    private fun check(name: String, description: String, call: () -> Any?): DependencyView = try {
        call()
        noted(name, reachable = true, said = "Answering")
        DependencyView(name, description, true, "Answering")
    } catch (failure: Exception) {
        val said = failure.message ?: "It could not be reached"
        noted(name, reachable = false, said = said)
        DependencyView(name, description, false, said)
    }

    /**
     * Says in the log what the card says on the screen, when it changes.
     *
     * The screen was the only place this went, and a screen is a thing somebody
     * has to already be looking at. A directory that has been refusing
     * connections since Tuesday said so to whoever opened the monitoring page
     * and to nobody else - not to the log anybody greps, not to whatever watches
     * the log, and not to the person reading it a week later to work out when it
     * started.
     *
     * Only on a change, because this runs every time the page polls: a line per
     * poll would be a log that fills itself while nothing is happening, and the
     * two moments worth keeping are when it broke and when it came back.
     */
    private fun noted(name: String, reachable: Boolean, said: String) {
        when (worthSaying(lastSeen.put(name, reachable), reachable)) {
            Said.NOTHING -> Unit
            Said.BROKEN -> log.warn("{} could not be reached: {}", name, said)
            Said.MENDED -> log.info("{} is answering again.", name)
        }
    }

    /**
     * What each dependency last reported, so only changes are logged.
     *
     * Held here rather than anywhere durable on purpose: it exists to stop a
     * repeated line, and a restart is a moment when saying it again is right.
     */
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    companion object {

        private val log = org.slf4j.LoggerFactory.getLogger(MonitoringAPI::class.java)

        /**
         * Whether this reading is worth a line, given the one before it.
         *
         * A dependency that is down stays down, and this runs every time the
         * page polls - so what is logged is the change rather than the state.
         *
         * The first reading of all is the awkward one. Down is said: an
         * installation that starts with an unreachable directory should say so
         * without waiting for it to first work. Up is not: every restart would
         * otherwise announce that everything is answering again, which is a line
         * that reads like a recovery and is nothing of the sort.
         *
         * @param before what it last reported, or null on the first reading.
         */
        internal fun worthSaying(before: Boolean?, now: Boolean): Said = when {
            before == now -> Said.NOTHING
            now -> if (before == null) Said.NOTHING else Said.MENDED
            else -> Said.BROKEN
        }
    }

    /** What a reading is worth saying; see [worthSaying]. */
    internal enum class Said { NOTHING, BROKEN, MENDED }
}

/** Something the service needs to be up, and whether it was. */
data class DependencyView(
    val name: String,
    val description: String,
    val reachable: Boolean,
    /** What the check saw, ready to show. */
    val detail: String,
    /** Its own interface, for the ones that have one; null offers no link. */
    val url: String? = null,
)

data class ComponentView(
    val name: String,
    val description: String,
    val status: ComponentStatus,
    val version: String?,
    /** What the check saw, ready to show. */
    val detail: String,
    /** ISO-8601 offset date-time. */
    val lastCheckedAt: String,
    val dependencies: List<DependencyView>,
)
