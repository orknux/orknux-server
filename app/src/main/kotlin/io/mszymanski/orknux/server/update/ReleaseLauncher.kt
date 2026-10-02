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
)

/** The launcher's access to `server_release`. JDBC in production; the same JDBC in the tests. */
interface ReleaseStore {
    /** The release chosen to run - ACTIVATING or ACTIVE; there is at most one. */
    fun current(): LaunchableRelease?
    fun row(id: Long): LaunchableRelease?
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
) {

    fun choose(): Path {
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

            if (current.state == ServerReleaseState.ACTIVATING) {
                val allowed = store.bootAttemptsAllowed()
                if (current.bootAttempts >= allowed) {
                    store.fail(current.id, "it did not start in $allowed attempts")
                    loud("Release ${current.version} did not start in $allowed attempts and is marked failed.")
                    val previous = current.fallbackId?.let(store::row)
                    if (previous != null && previous.state != ServerReleaseState.FAILED) store.restore(previous.id)
                    return@repeat
                }
                store.countAttempt(current.id)
            }

            return try {
                extracted(current)
            } catch (failure: Exception) {
                val why = (failure as? ReleaseJarRefusedException)?.why ?: (failure.message ?: failure.javaClass.simpleName)
                store.fail(current.id, why)
                loud("Release ${current.version} was refused at start-up and is marked failed: $why. Running the image's own jar.")
                imageJar
            }
        }
        return imageJar
    }

    /**
     * The release written out, and the written file checked - the file that
     * will be run, not a copy of it, and not re-read from the database after.
     * Read-only and its owner's alone before it is checked, so nothing else in
     * the container can change it between the check and `java -jar`.
     */
    private fun extracted(release: LaunchableRelease): Path {
        Files.createDirectories(releaseDir)
        ownerOnly(releaseDir, "rwx------")
        val target = releaseDir.resolve("release-${release.id}.jar")
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
        say("Running release ${release.version} from the database.")
        return target
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

    override fun current(): LaunchableRelease? = query(
        "$SELECT WHERE state IN ('ACTIVATING', 'ACTIVE') ORDER BY id DESC",
    )

    override fun row(id: Long): LaunchableRelease? = query("$SELECT WHERE id = ?", id)

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
            "SELECT id, version, sha256, state, boot_attempts, fallback_id, image_version FROM server_release"

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

/** The exit code that asks the start loop to choose a jar and start again. */
const val RESTART_EXIT_CODE = 75

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
    val chosen = try {
        launch(image)
    } catch (failure: Throwable) {
        System.err.println("orknux launcher: ERROR could not choose a release, running the image's own jar: $failure")
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
    val url = System.getenv("ORKNUX_DB_URL")?.ifBlank { null } ?: "jdbc:postgresql://localhost:5432/orknux"
    val user = System.getenv("ORKNUX_DB_USERNAME") ?: "orknux"
    val password = System.getenv("ORKNUX_DB_PASSWORD") ?: "orknux"
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
    ).choose()
}
