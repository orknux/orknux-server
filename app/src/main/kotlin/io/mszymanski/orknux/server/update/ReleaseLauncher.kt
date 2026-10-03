package io.mszymanski.orknux.server.update

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Driver
import java.util.Properties

/*
 * The start-up half of #584: which jar this container runs.
 *
 * Run by the image's start loop (docker/orknux-run.sh), before the server, as
 *
 *   java -cp /app/app.jar -Dloader.main=io.mszymanski.orknux.server.update.ReleaseLauncherKt \
 *        org.springframework.boot.loader.launch.PropertiesLauncher /app/app.jar
 *
 * PropertiesLauncher is what puts BOOT-INF/lib on the class path, which is where
 * the JDBC drivers are; there is no Spring context here and there must not be
 * one - this has to work on a database the server itself could not start on.
 * It prints one line on standard out, the path of the jar to run, and nothing
 * else ever reaches standard out. Whatever goes wrong, the answer is the image's
 * own jar: an update must never be able to stop a container starting.
 *
 * Two things can override the database's choice (#593). ORKNUX_RELEASE_PIN
 * names a version, and the launcher runs that one or - where it cannot - the
 * image's own jar, saying why; never what the database chose instead. And a
 * stored jar that keeps failing to start is given up on: the start loop runs
 * the launcher again when a stored jar exits, telling it how that one ended, so
 * a release that cannot boot ends on the image's own jar rather than in a crash
 * loop, whichever way it was chosen.
 */

/** The states a stored release moves through. The strings are the column's CHECK. */
enum class ServerReleaseState { STORED, ACTIVATING, ACTIVE, FAILED }

/** A stored release as the launcher sees it: everything but the bytes. */
data class LaunchableRelease(
    val id: Long,
    val version: String,
    val sha256: String,
    val state: ServerReleaseState,
    val bootAttempts: Int,
    val fallbackId: Long?,
    val imageVersion: String?,
    val schemaVersion: Int = 0,
    /** Whether a server has ever come up on it; a pinned release counts every start until one has. */
    val booted: Boolean = false,
    val failure: String? = null,
)

/** The launcher's access to `server_release`. JDBC in production; the same JDBC in the tests. */
interface ReleaseStore {
    /** The release chosen to run - ACTIVATING or ACTIVE; there is at most one. */
    fun current(): LaunchableRelease?
    fun row(id: Long): LaunchableRelease?
    /** The newest stored release that says it is [version], in any state. */
    fun byVersion(version: String): LaunchableRelease?
    /** The schema floor the server keeps in installation_setting; 0 where none is kept yet. */
    fun schemaFloor(): Int
    fun bootAttemptsAllowed(): Int
    fun countAttempt(id: Long)
    /** FAILED, with the reason, and flagged so the server reports it once it is up. */
    fun fail(id: Long, why: String)
    fun restore(id: Long)
    fun deactivate(id: Long)
    /** The jar's bytes, a piece at a time, into [to]. */
    fun writeJar(id: Long, to: Path)
}

/**
 * The choice itself, with nothing about where it is printed or how the store is
 * reached - so a test can drive it against the real tables without forking a JVM.
 */
class ReleaseLauncher(
    private val store: ReleaseStore,
    private val verifier: ReleaseJarVerifier,
    private val imageJar: Path,
    private val imageVersion: String?,
    private val releaseDir: Path,
    private val err: PrintStream = System.err,
    /** ORKNUX_RELEASE_PIN: the version to run whatever the database chose; null where unset. */
    private val pin: String? = null,
    /**
     * How the last server this start loop ran ended, and from which jar - set
     * only where it ended without being asked to restart (exit 75) or to stop.
     */
    private val lastExit: Int? = null,
    private val lastJar: String? = null,
    /** The image's own schema floor, below which nothing may run here. */
    private val imageFloor: Int = ReleaseJarVerifier.ownFloor(),
) {

    fun choose(): Path {
        pin?.let { return pinned(it) }

        // A bound rather than a loop until settled: each pass either answers or
        // moves a row out of ACTIVATING/ACTIVE, and two is the most that takes.
        repeat(4) {
            val current = store.current() ?: return imageJar

            /*
             * An image newer than the one this release was activated on is a
             * platform team's upgrade, and it wins: otherwise a new image would
             * go on running whatever an administrator chose under the old one,
             * and nobody approving images could change what runs.
             */
            if (ReleaseVersion.newer(imageVersion, current.imageVersion)) {
                store.deactivate(current.id)
                say("The image is $imageVersion, newer than the one release ${current.version} was chosen on; running the image.")
                return imageJar
            }

            /*
             * A release on its way in counts every start, because a container
             * that dies with it is restarted by Docker or Kubernetes and nobody
             * tells the launcher how. One that has run counts only the starts
             * its own loop saw fail: four replicas restarting at once are not
             * four failures, and counting every start would fail a healthy
             * release on an ordinary rolling restart.
             */
            val activating = current.state == ServerReleaseState.ACTIVATING
            if (!admitted(current, countEveryStart = activating)) {
                // What ran before an activation is the way back from it; an
                // active release that stopped starting goes to the image.
                val previous = current.fallbackId?.takeIf { activating }?.let(store::row)
                if (previous != null && previous.state != ServerReleaseState.FAILED) store.restore(previous.id)
                return@repeat
            }

            return started(current)
        }
        return imageJar
    }

    /**
     * ORKNUX_RELEASE_PIN, honoured or refused - and refused out loud, with the
     * image's own jar run in its place. Never the database's choice instead:
     * an operator who pinned a version must not be able to mistake whatever
     * else runs for the pin.
     */
    private fun pinned(pin: String): Path {
        if (pin == imageVersion) {
            say("ORKNUX_RELEASE_PIN is $pin, the image's own version; running the image.")
            return imageJar
        }
        val release = store.byVersion(pin)
        val floor = maxOf(store.schemaFloor(), imageFloor)
        val refusal = ReleasePin.refusal(pin, release?.state, release?.failure, release?.schemaVersion ?: 0, floor)
        if (refusal != null || release == null) {
            loud("ORKNUX_RELEASE_PIN is $pin, and it cannot be run: $refusal. Running the image's own jar, $imageVersion.")
            return imageJar
        }
        if (!admitted(release, countEveryStart = !release.booted)) {
            loud("ORKNUX_RELEASE_PIN is $pin, and it was given up on. Running the image's own jar, $imageVersion.")
            return imageJar
        }
        return started(release)
    }

    /**
     * Counts this start against [release] where it counts, and fails it once it
     * has had the starts the administrator allows. False where it was failed.
     */
    private fun admitted(release: LaunchableRelease, countEveryStart: Boolean): Boolean {
        val crashed = lastExit != null && releaseIdOf(lastJar) == release.id
        if (!countEveryStart && !crashed) return true
        val allowed = store.bootAttemptsAllowed()
        // Where every start is counted, the one that just failed already was;
        // a crash of a release that had run is counted here for the first time.
        val attempts = if (countEveryStart) release.bootAttempts else release.bootAttempts + 1
        if (attempts >= allowed) {
            val why = "it did not start in $allowed attempts" + (if (crashed) " (the last exited with $lastExit)" else "")
            store.fail(release.id, why)
            loud("Release ${release.version} did not start in $allowed attempts and is marked failed.")
            return false
        }
        store.countAttempt(release.id)
        return true
    }

    private fun started(release: LaunchableRelease): Path = try {
        extracted(release)
    } catch (failure: Exception) {
        val why = (failure as? ReleaseJarRefusedException)?.why ?: (failure.message ?: failure.javaClass.simpleName)
        store.fail(release.id, why)
        loud("Release ${release.version} was refused at start-up and is marked failed: $why. Running the image's own jar.")
        imageJar
    }

    /**
     * The release written out, and the written file checked - the file that
     * will be run, not a copy of it, and not re-read from the database after.
     * Read-only and its owner's alone before it is checked, so nothing else in
     * the container can change it between the check and `java -jar`.
     */
    private fun extracted(release: LaunchableRelease): Path {
        val target = jarDirectory().resolve("release-${release.id}.jar")
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            target.toFile().setWritable(true)
            Files.delete(target)
        }
        store.writeJar(release.id, target)
        ownerOnly(target, "r--------")
        target.toFile().setReadOnly()

        // Integrity, not proof: whoever can change the bytes can change this too.
        val written = ReleaseJarVerifier.sha256(target)
        if (written != release.sha256) throw ReleaseJarRefusedException("its bytes no longer match what was stored")

        val verified = verifier.verify(target)
        if (verified.version != release.version) {
            throw ReleaseJarRefusedException("it says it is ${verified.version}, and was stored as ${release.version}")
        }
        say("Running release ${release.version}" + (if (pin != null) ", pinned by ${ReleasePin.VARIABLE}." else " from the database."))
        return target
    }

    /**
     * Where the jar is written: a directory of the launcher's own inside the
     * configured one (#600). The configured directory is often a mount - an
     * emptyDir with an fsGroup on Kubernetes - that belongs to root, and only a
     * directory's owner may change its mode, so it is left exactly as it was
     * mounted and the owner-only treatment goes on the directory made here.
     * Something already at that name that is not a plain directory is removed,
     * never followed; one that is not ours fails the chmod, and so the release,
     * out loud rather than running a jar from a directory somebody else controls.
     */
    private fun jarDirectory(): Path {
        Files.createDirectories(releaseDir)
        clearOldLayout()
        val jars = releaseDir.resolve(JAR_DIRECTORY)
        if (Files.exists(jars, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(jars, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(jars)
        }
        if (!Files.exists(jars, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(jars)
        ownerOnly(jars, "rwx------")
        return jars
    }

    /**
     * Releases before #600 wrote the jar straight into the configured directory.
     * One left there by them is never run again, so it goes; best effort,
     * because it is only space.
     */
    private fun clearOldLayout() {
        runCatching {
            Files.newDirectoryStream(releaseDir, "release-*.jar").use { old ->
                old.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun ownerOnly(path: Path, permissions: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX filesystem - a developer's Windows machine. The image is.
        }
    }

    private fun say(line: String) = err.println("orknux launcher: $line")

    private fun loud(line: String) {
        err.println("orknux launcher: ERROR ********************************************************")
        err.println("orknux launcher: ERROR $line")
        err.println("orknux launcher: ERROR ********************************************************")
    }
}

/**
 * `server_release` over a plain JDBC connection. Shared by the launcher and the
 * server, so the two read and move the states in exactly one way.
 */
class JdbcReleaseStore(private val connect: () -> Connection) : ReleaseStore {

    /**
     * Null, and not an error, where the table is not there yet: the first
     * start after upgrading to the release that brought it runs this before
     * the server has migrated the schema, and nothing can have been chosen.
     */
    override fun current(): LaunchableRelease? {
        if (!tableExists()) return null
        return query("$SELECT WHERE state IN ('ACTIVATING', 'ACTIVE') ORDER BY id DESC")
    }

    private fun tableExists(): Boolean = connect().use { connection ->
        listOf("server_release", "SERVER_RELEASE").any { name ->
            connection.metaData.getTables(null, null, name, arrayOf("TABLE")).use { it.next() }
        }
    }

    override fun row(id: Long): LaunchableRelease? = query("$SELECT WHERE id = ?", id)

    override fun byVersion(version: String): LaunchableRelease? {
        if (!tableExists()) return null
        return query("$SELECT WHERE version = ? ORDER BY id DESC", version)
    }

    override fun schemaFloor(): Int = connect().use { connection ->
        connection.prepareStatement("SELECT value FROM installation_setting WHERE name = ?").use { statement ->
            statement.setString(1, RELEASE_SCHEMA_FLOOR_SETTING)
            statement.executeQuery().use { if (it.next()) it.getString(1).trim().toIntOrNull() else null }
        }
    } ?: 0

    override fun bootAttemptsAllowed(): Int = connect().use { connection ->
        connection.prepareStatement("SELECT value FROM installation_setting WHERE name = ?").use { statement ->
            statement.setString(1, RELEASE_BOOT_ATTEMPTS_SETTING)
            statement.executeQuery().use { if (it.next()) it.getString(1).trim().toIntOrNull() else null }
        }
    }?.takeIf { it in MIN_RELEASE_BOOT_ATTEMPTS..MAX_RELEASE_BOOT_ATTEMPTS } ?: DEFAULT_RELEASE_BOOT_ATTEMPTS

    override fun countAttempt(id: Long) = update("UPDATE server_release SET boot_attempts = boot_attempts + 1 WHERE id = ?", id)

    override fun fail(id: Long, why: String) = connect().use { connection ->
        connection.prepareStatement(
            "UPDATE server_release SET state = 'FAILED', failure = ?, failure_reported = ? WHERE id = ?",
        ).use {
            it.setString(1, why.take(500))
            it.setBoolean(2, false)
            it.setLong(3, id)
            it.executeUpdate()
        }
        Unit
    }

    override fun restore(id: Long) =
        update("UPDATE server_release SET state = 'ACTIVE', boot_attempts = 0 WHERE id = ?", id)

    override fun deactivate(id: Long) = update("UPDATE server_release SET state = 'STORED' WHERE id = ?", id)

    /**
     * One piece per query, so neither engine's driver is asked to hold more
     * than one piece in memory - pgjdbc reads a bytea value whole.
     */
    override fun writeJar(id: Long, to: Path) {
        connect().use { connection ->
            val parts = connection.prepareStatement("SELECT COUNT(*) FROM server_release_part WHERE release_id = ?").use {
                it.setLong(1, id)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            Files.newOutputStream(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                connection.prepareStatement("SELECT bytes FROM server_release_part WHERE release_id = ? AND part = ?").use {
                    for (part in 0 until parts) {
                        it.setLong(1, id)
                        it.setInt(2, part)
                        it.executeQuery().use { rows ->
                            if (!rows.next()) throw ReleaseJarRefusedException("piece $part of it is missing")
                            out.write(rows.getBytes(1))
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes a jar's bytes as pieces, and answers its SHA-256 as it went - the
     * server's half of [writeJar], kept beside it so the two cannot disagree
     * about what a piece is.
     */
    fun storeJar(connection: Connection, id: Long, from: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(from).use { input ->
            connection.prepareStatement("INSERT INTO server_release_part (release_id, part, bytes) VALUES (?, ?, ?)").use {
                var part = 0
                while (true) {
                    val piece = input.readNBytes(PART_BYTES)
                    if (piece.isEmpty()) break
                    digest.update(piece)
                    it.setLong(1, id)
                    it.setInt(2, part++)
                    it.setBytes(3, piece)
                    it.executeUpdate()
                    if (piece.size < PART_BYTES) break
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun query(sql: String, vararg arguments: Any): LaunchableRelease? = connect().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                LaunchableRelease(
                    id = rows.getLong("id"),
                    version = rows.getString("version"),
                    sha256 = rows.getString("sha256"),
                    state = ServerReleaseState.valueOf(rows.getString("state")),
                    bootAttempts = rows.getInt("boot_attempts"),
                    fallbackId = rows.getLong("fallback_id").takeUnless { rows.wasNull() },
                    imageVersion = rows.getString("image_version"),
                    schemaVersion = rows.getInt("schema_version"),
                    booted = rows.getObject("booted_at") != null,
                    failure = rows.getString("failure"),
                )
            }
        }
    }

    private fun update(sql: String, id: Long) = connect().use { connection ->
        connection.prepareStatement(sql).use {
            it.setLong(1, id)
            it.executeUpdate()
        }
        Unit
    }

    companion object {
        private const val SELECT =
            "SELECT id, version, sha256, state, boot_attempts, fallback_id, image_version, schema_version, booted_at, " +
                "failure FROM server_release"

        /**
         * How big a piece of a stored jar is. A storage format rather than a
         * tunable: pieces written at one size are read back at any, but it is
         * also what bounds a driver's memory, and 8 MiB is small for both.
         */
        const val PART_BYTES = 8 * 1024 * 1024
    }
}

/*
 * The boot-attempt setting is read here as well as by InstallationSettings, so
 * its name, range and default live in this file, which needs no Spring.
 */
const val RELEASE_BOOT_ATTEMPTS_SETTING = "release.boot.attempts"
const val DEFAULT_RELEASE_BOOT_ATTEMPTS = 3
const val MIN_RELEASE_BOOT_ATTEMPTS = 1
const val MAX_RELEASE_BOOT_ATTEMPTS = 20

/** InstallationSettings' name for the schema floor, which the launcher reads without Spring. */
const val RELEASE_SCHEMA_FLOOR_SETTING = "release.schema.floor"

/**
 * ORKNUX_RELEASE_PIN, #593: the one version this installation runs whatever
 * the database chose. Judged here for the launcher and the server alike, so
 * Admin -> Updates says exactly what the launcher did.
 */
object ReleasePin {
    const val VARIABLE = "ORKNUX_RELEASE_PIN"

    /**
     * Why a pin to [pin] cannot be honoured, or null where it can - given the
     * newest stored release by that version ([state] null where there is none)
     * and the database's schema floor. The signature is checked when the jar is
     * written out; one that fails it is marked FAILED, and refused here after.
     */
    fun refusal(pin: String, state: ServerReleaseState?, failure: String?, schemaVersion: Int, floor: Int): String? = when {
        state == null -> "the database keeps no release $pin; store it on Admin -> Updates first"
        state == ServerReleaseState.FAILED -> "release $pin is marked failed" + (failure?.let { " ($it)" } ?: "")
        ReleaseJarVerifier.predatesUpdates(pin) -> ReleaseJarVerifier.PREDATES_UPDATES
        schemaVersion < floor ->
            "it was built for schema V$schemaVersion, and this database has run migrations up to V$floor that it cannot go back past"
        else -> null
    }
}

/** The exit code that asks the start loop to choose a jar and start again. */
const val RESTART_EXIT_CODE = 75

/**
 * The file the launcher leaves in the release directory when it could not read
 * the release table, holding why; removed by the next launch that could.
 */
const val BLIND_MARKER = "launcher-could-not-read-releases"

/**
 * The launcher's own directory inside ORKNUX_RELEASE_DIR, where the chosen jar
 * is written and run from (#600). The marker above stays in the configured
 * directory itself, which only has to be writable.
 */
const val JAR_DIRECTORY = "jar"

private fun env(name: String): String? = System.getenv(name)?.ifBlank { null }

/** Which jar file a server was started from, as the launcher names it; null for anything else. */
fun releaseIdOf(jar: String?): Long? =
    jar?.let { Regex("""release-(\d+)\.jar$""").find(it.replace('\\', '/'))?.groupValues?.get(1)?.toLongOrNull() }

fun main(args: Array<String>) {
    // Standard out carries the answer and nothing else. A driver that logs to
    // the console - sqlite-jdbc through slf4j - writes to System.out when it
    // writes, so pointing that at stderr keeps the one line clean.
    val answer = System.out
    System.setOut(System.err)

    val image = Path.of(args.firstOrNull() ?: System.getenv("ORKNUX_IMAGE_JAR") ?: "/app/app.jar")
    val blind = Path.of(env("ORKNUX_RELEASE_DIR") ?: "/tmp/orknux-release").resolve(BLIND_MARKER)
    val chosen = try {
        launch(image).also { runCatching { Files.deleteIfExists(blind) } }
    } catch (failure: Throwable) {
        System.err.println("orknux launcher: ERROR could not choose a release, running the image's own jar: $failure")
        // Said where the server will look, so it does not restart to follow a
        // release this launcher cannot reach - which would be a restart every
        // few seconds for ever. See ServerReleases.started.
        runCatching {
            Files.createDirectories(blind.parent)
            Files.writeString(blind, failure.toString())
        }
        image
    }
    answer.println(chosen.toAbsolutePath())
    answer.flush()
}

private fun launch(image: Path): Path {
    val enabled = System.getenv("ORKNUX_SELF_UPDATE")?.trim()?.lowercase() != "false"
    if (!enabled) return image

    // application.yml's own defaults, so the launcher and the server agree on
    // which database this is when nothing is set.
    //
    // Spring's own names too: the server binds SPRING_DATASOURCE_* over the
    // ORKNUX_ ones, so an installation configured that way is the same
    // database to the server and must be to the launcher, or it reads
    // localhost while the server reads the real one.
    val url = env("SPRING_DATASOURCE_URL") ?: env("ORKNUX_DB_URL") ?: "jdbc:postgresql://localhost:5432/orknux"
    val user = env("SPRING_DATASOURCE_USERNAME") ?: System.getenv("ORKNUX_DB_USERNAME") ?: "orknux"
    val password = env("SPRING_DATASOURCE_PASSWORD") ?: System.getenv("ORKNUX_DB_PASSWORD") ?: "orknux"
    // A first start: there is no file yet, so nothing was ever chosen - and
    // connecting would create an empty one before the server has said where.
    if (url.startsWith("jdbc:sqlite:") && !Files.exists(Path.of(url.removePrefix("jdbc:sqlite:").substringBefore('?')))) {
        return image
    }
    val driver = Class.forName(
        if (url.startsWith("jdbc:sqlite:")) "org.sqlite.JDBC" else "org.postgresql.Driver",
    ).getDeclaredConstructor().newInstance() as Driver
    val properties = Properties().apply {
        if (!url.startsWith("jdbc:sqlite:")) {
            setProperty("user", user)
            setProperty("password", password)
        }
    }
    val store = JdbcReleaseStore { driver.connect(url, properties) ?: error("no driver accepts $url") }
    val directory = Path.of(System.getenv("ORKNUX_RELEASE_DIR")?.ifBlank { null } ?: "/tmp/orknux-release")
    return ReleaseLauncher(
        store = store,
        verifier = ReleaseJarVerifier.pinned(),
        imageJar = image,
        imageVersion = ReleaseJarVerifier.versionOf(image),
        releaseDir = directory,
        pin = env(ReleasePin.VARIABLE)?.trim(),
        // Set by the start loop when the jar it ran last exited on its own.
        lastExit = env("ORKNUX_LAST_EXIT")?.toIntOrNull(),
        lastJar = env("ORKNUX_LAST_JAR"),
    ).choose()
}
