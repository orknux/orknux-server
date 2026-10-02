package io.mszymanski.orknux.server.update

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.core.io.Resource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import javax.sql.DataSource
import kotlin.system.exitProcess

/**
 * The certificate a jar must be signed by, and nothing else decides it.
 *
 * `classpath:release/orknux-release.pem` - the image's own - unless a test
 * points `orknux.update.certificate` at one it made. Not an `ORKNUX_` variable
 * on purpose: an operator who could swap the certificate could run whatever
 * they signed, and the whole point is that the image fixes what runs.
 */
@Configuration(proxyBeanMethods = false)
class ReleaseVerifierConfig {
    @Bean
    fun releaseJarVerifier(
        @Value("\${orknux.update.certificate:classpath:${ReleaseJarVerifier.PINNED}}") certificate: Resource,
    ): ReleaseJarVerifier {
        val log = LoggerFactory.getLogger(ReleaseJarVerifier::class.java)
        return ReleaseJarVerifier.withTestTrust(ReleaseJarVerifier.certificate(certificate.inputStream)) { log.warn(it) }
    }
}

/**
 * Server updates, #584: storing a jar, choosing one, and following the choice.
 *
 * The launcher decides which jar a container starts from (ReleaseLauncher.kt);
 * this is the half that runs inside the server. An administrator's update or
 * rollback marks a release ACTIVATING, and the server restarts into the
 * launcher; once a server comes up on it, it is ACTIVE. Every server asks now
 * and then whether what it runs is still the choice, and restarts to follow -
 * which is how one replica's update reaches the others.
 */
@Service
class ServerReleases(
    private val releases: ServerReleaseRepository,
    private val settings: InstallationSettings,
    private val verifier: ReleaseJarVerifier,
    private val dataSource: DataSource,
    transactions: PlatformTransactionManager,
    private val audit: WorkspaceAuditRecorder,
    private val restart: ServerRestart,
    /** ORKNUX_SELF_UPDATE. False refuses all of this, and the launcher runs the image's jar. */
    @Value("\${orknux.update.enabled:true}") private val enabled: Boolean,
    @Value("\${orknux.version}") private val runningVersion: String,
    /** The jar this server was started from, as the start loop names it; empty outside one. */
    @Value("\${orknux.update.running-jar:}") private val runningJar: String,
    /** The image's own jar, as the start loop names it; empty outside one. */
    @Value("\${orknux.update.image-jar:}") private val imageJar: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactions)

    fun enabled(): Boolean = enabled

    /**
     * What this server is, by the manifest of the jar it was started from where
     * the loop said which, and by the build's own property otherwise. The
     * manifest first, because a release is identified by what its jar says it
     * is - which is also what it was stored as.
     */
    fun runningVersion(): String = ownVersion

    private val ownVersion: String by lazy {
        runningJar.ifBlank { null }?.let { ReleaseJarVerifier.versionOf(Path.of(it)) } ?: runningVersion
    }

    /** The stored release this server runs, or null for the image's own jar. */
    fun runningReleaseId(): Long? = releaseIdOf(runningJar.ifBlank { null })

    /** The image's version: its jar's manifest where the loop said where it is, else this server's own. */
    fun imageVersion(): String =
        imageJar.ifBlank { null }?.let { ReleaseJarVerifier.versionOf(Path.of(it)) } ?: runningVersion

    /**
     * Why going back to the image's own jar is refused, or null where it is
     * allowed: the same floor as any stored release, read off the image's jar.
     */
    fun imageRefusal(floor: Int = schemaFloor()): String? {
        if (imageJar.isBlank()) {
            return "This server was not started by the image's start loop, so it cannot say which jar the image holds."
        }
        if (runningReleaseId() == null && wantedReleaseId() == null) return "It is what runs now."
        val schema = ReleaseJarVerifier.schemaOf(Path.of(imageJar))
            ?: return "The image's jar could not be read."
        if (schema < floor) {
            return "It was built for schema V$schema, and this database has run migrations up to V$floor that it cannot go back past."
        }
        return null
    }

    /** Every server back on the image's own jar: nothing chosen in the database any more. */
    fun useImage(): Unit = transaction.executeWithoutResult {
        requireEnabled()
        imageRefusal()?.let { throw ServerReleaseNotActivatableException(imageVersion(), it) }
        val current = releases.findAllByStateIn(listOf(ServerReleaseState.ACTIVATING, ServerReleaseState.ACTIVE))
        current.forEach { it.state = ServerReleaseState.STORED }
        releases.saveAll(current)
    }

    /**
     * The newest migration this database cannot be rolled back past: the
     * highest floor of anything that has run on it, this server included.
     */
    fun schemaFloor(): Int = maxOf(settings.releaseSchemaFloor(), ReleaseJarVerifier.ownFloor())

    fun stored(): List<ServerRelease> = releases.findAllByOrderByStoredAtDescIdDesc()

    fun bySha256(sha256: String): ServerRelease? = releases.findBySha256(sha256)

    /**
     * Why [release] cannot be activated, or null where it can. A sentence the
     * administrator is shown beside a disabled button.
     */
    fun refusalFor(release: ServerRelease, floor: Int = schemaFloor()): String? = when {
        release.id == runningReleaseId() && release.state == ServerReleaseState.ACTIVE -> "It is the release running now."
        release.schemaVersion < floor ->
            "It was built for schema V${release.schemaVersion}, and this database has run migrations up to " +
                "V$floor that it cannot go back past."
        else -> null
    }

    fun requireEnabled() {
        if (!enabled) throw ServerUpdatesDisabledException()
    }

    /**
     * Verifies [file] and stores it. The signature and the structure are
     * checked before a row exists, so a refused jar leaves nothing behind;
     * the same bytes stored twice are refused rather than kept twice.
     */
    fun store(file: Path, source: ServerReleaseSource, by: String): ServerRelease {
        requireEnabled()
        val size = Files.size(file)
        val limit = settings.releaseMaxMb() * 1024L * 1024L
        if (size > limit) throw ServerReleaseTooLargeException(settings.releaseMaxMb())

        val verified = verifier.verify(file)
        val sha256 = ReleaseJarVerifier.sha256(file)
        releases.findBySha256(sha256)?.let { throw ServerReleaseAlreadyStoredException(it.version) }

        val stored = transaction.execute {
            val row = releases.saveAndFlush(
                ServerRelease(
                    version = verified.version,
                    sha256 = sha256,
                    size = size,
                    schemaVersion = verified.schemaVersion,
                    schemaFloor = verified.schemaFloor,
                    source = source,
                    storedAt = OffsetDateTime.now(),
                    storedBy = by,
                ),
            )
            val connection = DataSourceUtils.getConnection(dataSource)
            try {
                val written = JdbcReleaseStore { connection }.storeJar(connection, requireNotNull(row.id), file)
                // The file changed between being read and being stored. Nothing is kept.
                if (written != sha256) throw ReleaseJarRefusedException("it changed while it was being stored")
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource)
            }
            row
        }!!
        prune(keep = stored.id)
        log.info("Server release {} stored ({} bytes, schema V{})", stored.version, size, stored.schemaVersion)
        return stored
    }

    /**
     * Makes [id] the release every server starts from. Refused below the
     * schema floor; what was running becomes what it falls back to if this
     * one never comes up.
     */
    fun activate(id: Long, by: String): ServerRelease = transaction.execute {
        requireEnabled()
        val release = releases.findByIdOrNull(id) ?: throw ServerReleaseNotFoundException(id)
        refusalFor(release)?.let { throw ServerReleaseNotActivatableException(release.version, it) }

        val current = releases.findAllByStateIn(listOf(ServerReleaseState.ACTIVATING, ServerReleaseState.ACTIVE))
            .filter { it.id != release.id }
        val fallback = current.firstOrNull { it.state == ServerReleaseState.ACTIVE }?.id
            ?: current.firstOrNull { it.state == ServerReleaseState.ACTIVATING }?.fallbackId
        current.forEach { it.state = ServerReleaseState.STORED }
        releases.saveAll(current)

        release.state = ServerReleaseState.ACTIVATING
        release.bootAttempts = 0
        release.fallbackId = fallback?.takeIf { it != release.id }
        release.failure = null
        release.failureReported = true
        release.imageVersion = imageVersion()
        release.activatedAt = OffsetDateTime.now()
        release.activatedBy = by
        releases.save(release)
    }!!

    /**
     * Keeps the newest [InstallationSettings.releasesKept], never removing the
     * release chosen, the one it falls back to, the one running, or [keep].
     */
    fun prune(keep: Long? = null) {
        val all = stored()
        val kept = settings.releasesKept()
        if (all.size <= kept) return
        val current = all.filter { it.state == ServerReleaseState.ACTIVATING || it.state == ServerReleaseState.ACTIVE }
        val protected = buildSet {
            current.forEach { add(it.id); add(it.fallbackId) }
            add(runningReleaseId())
            add(keep)
        }
        var excess = all.size - kept
        for (release in all.reversed()) {
            if (excess <= 0) break
            if (release.id in protected) continue
            transaction.executeWithoutResult {
                val connection = DataSourceUtils.getConnection(dataSource)
                try {
                    connection.prepareStatement("DELETE FROM server_release_part WHERE release_id = ?").use {
                        it.setLong(1, requireNotNull(release.id))
                        it.executeUpdate()
                    }
                } finally {
                    DataSourceUtils.releaseConnection(connection, dataSource)
                }
                releases.deleteById(requireNotNull(release.id))
            }
            log.info("Server release {} removed; {} are kept", release.version, kept)
            excess--
        }
    }

    /**
     * The release every server should be running, or null for the image's own
     * jar - the same answer the launcher would give, without its side effects.
     */
    fun wantedReleaseId(): Long? {
        val current = releases.findAllByStateIn(listOf(ServerReleaseState.ACTIVATING, ServerReleaseState.ACTIVE))
            .maxByOrNull { it.id ?: 0 } ?: return null
        if (ReleaseVersion.newer(imageVersion(), current.imageVersion)) return null
        return current.id
    }

    /**
     * A server that came up: the release it runs has started, the floor rises
     * to what it carries, and whatever the launcher refused while nobody was
     * looking goes into the audit log, where somebody will.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun started() {
        settings.raiseReleaseSchemaFloor(ReleaseJarVerifier.ownFloor())

        val running = runningReleaseId()?.let { releases.findByIdOrNull(it) }
        if (running != null) {
            if (running.state == ServerReleaseState.ACTIVATING || running.state == ServerReleaseState.ACTIVE) {
                running.state = ServerReleaseState.ACTIVE
                running.bootAttempts = 0
                running.bootedAt = OffsetDateTime.now()
                releases.save(running)
            }
            log.info("Running server release {} from the database (activated by {})", running.version, running.activatedBy)
        } else if (runningJar.isNotBlank()) {
            log.info("Running the image's own jar, {}", runningVersion)
        }

        for (failed in releases.findAllByStateAndFailureReportedFalse(ServerReleaseState.FAILED)) {
            log.error("Server release {} was refused at start-up: {}", failed.version, failed.failure)
            audit.recordAutomated(
                null,
                WorkspaceAuditCategory.WORKSPACE,
                "Server release ${failed.version} failed to start: ${failed.failure}",
                actor = "server",
            )
            failed.failureReported = true
            releases.save(failed)
        }

        if (enabled) restart.follow { wantedReleaseId() != runningReleaseId() }
    }
}

/**
 * How a server leaves to be started again: exit code 75, which the image's
 * start loop reads as "choose a jar and start again" and anything else as
 * "stop". Only inside that loop - a server started any other way (a
 * developer's, a test's) has nobody to bring it back, so it is never told to go.
 */
@Component
class ServerRestart(
    private val context: ConfigurableApplicationContext,
    private val settings: InstallationSettings,
    /** Set by docker/orknux-run.sh on the command line, and by nothing else. */
    @Value("\${orknux.update.launched:false}") private val launched: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var follower: Thread? = null

    fun available(): Boolean = launched

    /** Restarts after the delay an administrator set, so the answer reaches the browser first. */
    fun soon(): Boolean {
        if (!launched) return false
        Thread.ofVirtual().name("orknux-restart").start {
            Thread.sleep(settings.releaseRestartDelaySeconds() * 1000L)
            exit("a release was activated")
        }
        return true
    }

    /**
     * Asks [differs] every few seconds - the administrator's setting - and
     * restarts the first time it says yes.
     */
    fun follow(differs: () -> Boolean) {
        if (!launched || follower != null) return
        follower = Thread.ofVirtual().name("orknux-release-follower").start {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(settings.releaseFollowSeconds() * 1000L)
                    if (differs()) {
                        exit("the chosen release is not the one this server runs")
                        return@start
                    }
                } catch (_: InterruptedException) {
                    return@start
                } catch (failure: Exception) {
                    log.warn("Could not check which release is chosen: {}", failure.message)
                }
            }
        }
    }

    @jakarta.annotation.PreDestroy
    fun stop() {
        follower?.interrupt()
    }

    /**
     * Closes the context and leaves with 75, on a thread of its own that is not
     * a daemon. Not on the caller's: both callers are virtual threads, which
     * are daemons, and once the context has stopped Tomcat nothing else keeps
     * the JVM alive - it ended with 0 before `exitProcess` was reached, and the
     * start loop took 0 for "stop". 75 whatever closing answered, because a
     * server that asked to be restarted should be, even if its shutdown threw.
     */
    private fun exit(why: String) {
        Thread.ofPlatform().name("orknux-exit").daemon(false).start {
            log.info("Restarting: {}", why)
            runCatching { SpringApplication.exit(context, ExitCodeGenerator { RESTART_EXIT_CODE }) }
            exitProcess(RESTART_EXIT_CODE)
        }
    }
}

class ServerUpdatesDisabledException : RuntimeException(
    "Server updates are turned off on this installation (ORKNUX_SELF_UPDATE is false).",
)

class ServerReleaseNotFoundException(val id: Long) : RuntimeException("There is no stored server release $id."), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class ServerReleaseNotActivatableException(val version: String, val why: String) :
    RuntimeException("Server release $version cannot be started: $why"), Refusal {
    override val arguments get() = mapOf("version" to version, "why" to why)
}

class ServerReleaseAlreadyStoredException(val version: String) :
    RuntimeException("That jar is already stored, as release $version."), Refusal {
    override val arguments get() = mapOf("version" to version)
}

class ServerReleaseTooLargeException(val mb: Int) :
    RuntimeException("That jar is larger than the $mb MB this installation takes."), Refusal {
    override val arguments get() = mapOf("mb" to mb)
}

class ServerReleaseNotOfferedException(val version: String) :
    RuntimeException("orknux.ai does not offer server release $version."), Refusal {
    override val arguments get() = mapOf("version" to version)
}

class ServerReleaseDownloadMismatchException(val version: String) :
    RuntimeException("The download of $version does not match what orknux.ai listed for it; nothing was stored."), Refusal {
    override val arguments get() = mapOf("version" to version)
}
