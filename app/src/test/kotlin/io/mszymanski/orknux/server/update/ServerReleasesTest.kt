package io.mszymanski.orknux.server.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.update.TestReleaseJars.Shape
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Server updates, end to end on the suite's database. Issue #584.
 *
 * The jars are real signed jars (see [TestReleaseJars]) and the server is told
 * to trust the test key through `orknux.update.certificate`, which is the one
 * thing standing in for production here. The launcher is driven against the
 * same tables the server writes, without forking a JVM.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ServerReleasesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val context: WebApplicationContext,
    @Autowired val updates: ServerReleases,
    @Autowired val releases: ServerReleaseRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val dataSource: DataSource,
) {

    private val jdbc = JdbcTemplate(dataSource)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun reset() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        jdbc.update("DELETE FROM server_release_part")
        jdbc.update("DELETE FROM server_release")
        jdbc.update("DELETE FROM installation_setting WHERE name LIKE 'release%'")
        audit.deleteAll()
        listedSha = null
        refuseServerReleases = false
    }

    private fun stored(shape: Shape): ServerRelease =
        updates.store(TestReleaseJars.signed(shape), ServerReleaseSource.UPLOAD, "alice")

    private fun store() = JdbcReleaseStore { dataSource.connection }

    @Test
    fun `a stored jar comes back byte for byte, across several pieces`() {
        val jar = TestReleaseJars.signed(Shape(version = "9.9.1", padding = 20 * 1024 * 1024))
        val release = updates.store(jar, ServerReleaseSource.UPLOAD, "alice")

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM server_release_part WHERE release_id = ?", Int::class.java, release.id))
            .isGreaterThan(2)
        val back = Files.createTempDirectory("orknux-back-").resolve("back.jar")
        store().writeJar(release.id!!, back)
        assertThat(Files.readAllBytes(back)).isEqualTo(Files.readAllBytes(jar))
        assertThat(release.sha256).isEqualTo(ReleaseJarVerifier.sha256(jar))
        assertThat(release.schemaVersion).isEqualTo(333)
        assertThat(release.state).isEqualTo(ServerReleaseState.STORED)
    }

    @Test
    fun `the same jar twice is refused, and a refused jar leaves nothing behind`() {
        val jar = TestReleaseJars.signed(Shape(version = "9.9.2"))
        updates.store(jar, ServerReleaseSource.UPLOAD, "alice")

        assertThatThrownBy { updates.store(jar, ServerReleaseSource.UPLOAD, "alice") }
            .isInstanceOf(ServerReleaseAlreadyStoredException::class.java)
        assertThatThrownBy { updates.store(TestReleaseJars.signed(Shape(version = "9.9.3"), TestReleaseJars.other), ServerReleaseSource.UPLOAD, "alice") }
            .isInstanceOf(ReleaseJarRefusedException::class.java)
        assertThat(releases.findAll().map { it.version }).containsExactly("9.9.2")
    }

    @Test
    fun `only the newest are kept, and never the one chosen`() {
        settings.setReleasesKept(2, "alice")
        val first = stored(Shape(version = "9.1.0"))
        updates.activate(first.id!!, "alice")
        stored(Shape(version = "9.2.0"))
        stored(Shape(version = "9.3.0"))
        stored(Shape(version = "9.4.0"))

        // 9.1.0 is chosen, so it stays; of the rest, the newest fill what is left.
        assertThat(releases.findAll().map { it.version }).containsExactlyInAnyOrder("9.1.0", "9.4.0")
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT release_id) FROM server_release_part", Int::class.java)).isEqualTo(2)
    }

    @Test
    fun `a rollback past the schema floor is refused, and one above it is allowed`() {
        val old = stored(Shape(version = "9.0.1", schemaVersion = 300, schemaFloor = 290))
        val current = stored(Shape(version = "9.0.2", schemaVersion = 333, schemaFloor = 333))

        assertThat(updates.refusalFor(old)).contains("V300").contains("V333")
        assertThatThrownBy { updates.activate(old.id!!, "alice") }
            .isInstanceOf(ServerReleaseNotActivatableException::class.java)
        assertThat(updates.activate(current.id!!, "alice").state).isEqualTo(ServerReleaseState.ACTIVATING)

        // A release that ran raises the floor past what it carries.
        settings.raiseReleaseSchemaFloor(340)
        assertThat(updates.refusalFor(releases.findById(current.id!!).get())).contains("V340")
    }

    @Test
    fun `the Updates query says what runs, what is offered and what is kept`() {
        val kept = stored(Shape(version = "9.9.4"))
        listedJar = TestReleaseJars.signed(Shape(version = "9.9.5"))

        graphQlTester.document(
            """
            { serverUpdates {
                enabled runningVersion restartable offered kept schemaFloor
                available { version changelog stored }
                stored { id version source state activatable refusal running }
            } }
            """,
        ).execute()
            .path("serverUpdates.enabled").entity(Boolean::class.java).isEqualTo(true)
            .path("serverUpdates.restartable").entity(Boolean::class.java).isEqualTo(false)
            .path("serverUpdates.offered").entity(Boolean::class.java).isEqualTo(true)
            .path("serverUpdates.kept").entity(Int::class.java).isEqualTo(3)
            .path("serverUpdates.schemaFloor").entity(Int::class.java).isEqualTo(333)
            .path("serverUpdates.available[*].version").entityList(String::class.java).containsExactly("9.9.5")
            .path("serverUpdates.available[0].changelog").entity(String::class.java).isEqualTo("### Added\n- everything")
            .path("serverUpdates.stored[*].version").entityList(String::class.java).containsExactly("9.9.4")
            .path("serverUpdates.stored[0].id").entity(Long::class.java).isEqualTo(kept.id!!)
            .path("serverUpdates.stored[0].activatable").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `a marketplace that has not heard of server releases offers none, and says so`() {
        refuseServerReleases = true

        graphQlTester.document("{ serverUpdates { offered offeredError available { version } } }").execute()
            .path("serverUpdates.offered").entity(Boolean::class.java).isEqualTo(false)
            .path("serverUpdates.offeredError").valueIsNull()
            .path("serverUpdates.available").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `Update downloads, checks, stores and starts a release, and says so in the audit log`() {
        listedJar = TestReleaseJars.signed(Shape(version = "9.9.6"))

        graphQlTester.document(
            """mutation { installServerRelease(version: "9.9.6") { restarting release { version source state } } }""",
        ).execute()
            .path("installServerRelease.restarting").entity(Boolean::class.java).isEqualTo(false)
            .path("installServerRelease.release.source").entity(String::class.java).isEqualTo("ORKNUX_AI")
            .path("installServerRelease.release.state").entity(String::class.java).isEqualTo("ACTIVATING")

        assertThat(releases.findAll().single().sha256).isEqualTo(ReleaseJarVerifier.sha256(listedJar))
        assertThat(audit.findAll().map { it.message }).contains("Server updated to 9.9.6")
    }

    @Test
    fun `a download that does not match its listing is refused and nothing is stored`() {
        listedJar = TestReleaseJars.signed(Shape(version = "9.9.7"))
        listedSha = "0".repeat(64)

        graphQlTester.document("""mutation { installServerRelease(version: "9.9.7") { restarting } }""").execute()
            .errors().satisfy { errors ->
                assertThat(errors.single().extensions["code"]).isEqualTo("ServerReleaseDownloadMismatch")
            }
        assertThat(releases.findAll()).isEmpty()
    }

    @Test
    fun `rolling back is a stored release activated, audited as a rollback`() {
        // Older than what runs and still able to update: a pre-release of the first that can.
        val older = stored(Shape(version = "0.9.9.8-rc1"))

        graphQlTester.document("mutation { activateServerRelease(id: ${older.id}) { release { state } } }").execute()
            .path("activateServerRelease.release.state").entity(String::class.java).isEqualTo("ACTIVATING")
        assertThat(audit.findAll().map { it.message }).contains("Server rolled back to 0.9.9.8-rc1")
    }

    /**
     * A row stored before the verifier asked - by hand here, since no door
     * takes one any more - is not offered for activation either.
     */
    @Test
    fun `a stored release older than in-place updates cannot be activated`() {
        val release = stored(Shape(version = "9.9.1"))
        jdbc.update("UPDATE server_release SET version = '0.9.9.7' WHERE id = ?", release.id)

        graphQlTester.document("{ serverUpdates { stored { activatable refusal } } }").execute()
            .path("serverUpdates.stored[0].activatable").entity(Boolean::class.java).isEqualTo(false)
            .path("serverUpdates.stored[0].refusal").entity(String::class.java)
            .isEqualTo("It predates in-place updates (0.9.9.8), so this server could not update back from it.")
        graphQlTester.document("mutation { activateServerRelease(id: ${release.id}) { restarting } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ServerReleaseNotActivatable") }
    }

    @Test
    fun `going back to the image's jar is refused by a server that does not know where it is`() {
        graphQlTester.document("{ serverUpdates { imageVersion imageActivatable imageRefusal } }").execute()
            .path("serverUpdates.imageActivatable").entity(Boolean::class.java).isEqualTo(false)
            .path("serverUpdates.imageRefusal").entity(String::class.java).satisfies { assertThat(it).contains("start loop") }
        graphQlTester.document("mutation { activateImageRelease { restarting } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ServerReleaseNotActivatable") }
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who is not an administrator sees and changes nothing`() {
        val release = stored(Shape(version = "9.9.8"))

        graphQlTester.document("{ serverUpdates { enabled } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        graphQlTester.document("mutation { activateServerRelease(id: ${release.id}) { restarting } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        mockMvc.perform(post("/api/server-releases").contentType(MediaType.APPLICATION_OCTET_STREAM).content(byteArrayOf(1)))
            .andExpect(status().isForbidden)
        assertThat(releases.findById(release.id!!).get().state).isEqualTo(ServerReleaseState.STORED)
        graphQlTester.document("mutation { removeServerRelease(id: ${release.id}) }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        assertThat(releases.findById(release.id!!)).isPresent
    }

    @Test
    fun `a kept release can be removed, its jar with it, and the one chosen cannot`() {
        val old = stored(Shape(version = "9.9.6"))
        val chosen = stored(Shape(version = "9.9.7"))
        graphQlTester.document("mutation { activateServerRelease(id: ${chosen.id}) { restarting } }").execute()

        graphQlTester.document("mutation { removeServerRelease(id: ${old.id}) }").execute()
            .path("removeServerRelease").entity(Boolean::class.java).isEqualTo(true)
        assertThat(releases.findById(old.id!!)).isEmpty
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM server_release_part WHERE release_id = ?", Int::class.java, old.id))
            .isZero()
        assertThat(audit.findAll().map { it.message }).contains("Server release 9.9.6 removed")

        graphQlTester.document("mutation { removeServerRelease(id: ${chosen.id}) }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ServerReleaseInUse") }
        assertThat(releases.findById(chosen.id!!)).isPresent
    }

    @Test
    fun `an uploaded jar is verified and stored, and garbage is refused in a sentence`() {
        mockMvc.perform(
            post("/api/server-releases").contentType(MediaType.APPLICATION_OCTET_STREAM).content("garbage".toByteArray()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("This jar cannot be used: it is not a jar."))
            .andExpect(jsonPath("$.code").value("ReleaseJarRefused"))

        val jar = Files.readAllBytes(TestReleaseJars.signed(Shape(version = "9.9.9")))
        mockMvc.perform(post("/api/server-releases").contentType(MediaType.APPLICATION_OCTET_STREAM).content(jar))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.version").value("9.9.9"))

        assertThat(releases.findAll().single().source).isEqualTo(ServerReleaseSource.UPLOAD)
        assertThat(audit.findAll().map { it.message }).contains("Server release 9.9.9 uploaded")
    }

    @Test
    fun `how many are kept and how a start is judged are Admin Settings, refused out of range`() {
        graphQlTester.document("mutation { setReleasesKept(count: 5) { releasesKept releasesKeptConfigured } }").execute()
            .path("setReleasesKept.releasesKept").entity(Int::class.java).isEqualTo(5)
            .path("setReleasesKept.releasesKeptConfigured").entity(Int::class.java).isEqualTo(3)
        graphQlTester.document("mutation { setReleaseBootAttempts(count: 4) { releaseBootAttempts } }").execute()
            .path("setReleaseBootAttempts.releaseBootAttempts").entity(Int::class.java).isEqualTo(4)
        graphQlTester.document("mutation { setReleaseFollowSeconds(seconds: 2) { releaseFollowSeconds } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ReleaseFollowOutOfRange") }

        // The launcher reads the same row, without Spring.
        assertThat(store().bootAttemptsAllowed()).isEqualTo(4)
        assertThat(audit.findAll().map { it.message }).contains("The last 5 server releases are kept")
    }

    // The launcher, against the tables the server just wrote.

    private fun launcher(
        dir: Path,
        imageVersion: String = updates.imageVersion(),
        pin: String? = null,
        lastExit: Int? = null,
        lastJar: String? = null,
        err: ByteArrayOutputStream = ByteArrayOutputStream(),
    ) = ReleaseLauncher(
        store = store(),
        verifier = TestReleaseJars.verifier(),
        imageJar = IMAGE,
        imageVersion = imageVersion,
        releaseDir = dir,
        err = java.io.PrintStream(err),
        pin = pin,
        lastExit = lastExit,
        lastJar = lastJar,
        imageFloor = 0,
    )

    // ORKNUX_RELEASE_PIN and the way back from a jar that does not start, #593.

    @Test
    fun `a pinned release runs, whatever the database chose`() {
        val chosen = stored(Shape(version = "8.1.1"))
        updates.activate(chosen.id!!, "alice")
        val pinned = stored(Shape(version = "8.1.2"))
        val dir = Files.createTempDirectory("orknux-release-")

        assertThat(launcher(dir, pin = "8.1.2").choose()).isEqualTo(dir.resolve(JAR_DIRECTORY).resolve("release-${pinned.id}.jar"))
        // Never booted, so the start is counted - and the database's choice is left as it was.
        assertThat(releases.findById(pinned.id!!).get().bootAttempts).isEqualTo(1)
        assertThat(releases.findById(chosen.id!!).get().state).isEqualTo(ServerReleaseState.ACTIVATING)
        assertThat(releases.findById(chosen.id!!).get().bootAttempts).isEqualTo(0)
    }

    @Test
    fun `a pin to the image's own version runs the image`() {
        val chosen = stored(Shape(version = "8.1.3"))
        updates.activate(chosen.id!!, "alice")

        assertThat(launcher(Files.createTempDirectory("orknux-release-"), imageVersion = "8.1.0", pin = "8.1.0").choose())
            .isEqualTo(IMAGE)
    }

    @Test
    fun `a pin the database cannot honour runs the image, says why, and never runs the database's choice`() {
        val chosen = stored(Shape(version = "8.1.4"))
        updates.activate(chosen.id!!, "alice")
        val said = ByteArrayOutputStream()

        assertThat(launcher(Files.createTempDirectory("orknux-release-"), pin = "8.1.99", err = said).choose()).isEqualTo(IMAGE)
        assertThat(said.toString()).contains("ERROR").contains("ORKNUX_RELEASE_PIN is 8.1.99").contains("keeps no release 8.1.99")
    }

    @Test
    fun `a pin below the schema floor is refused`() {
        stored(Shape(version = "8.1.5", schemaVersion = 333))
        settings.raiseReleaseSchemaFloor(334)

        assertThat(launcher(Files.createTempDirectory("orknux-release-"), pin = "8.1.5").choose()).isEqualTo(IMAGE)
        assertThat(ReleasePin.refusal("8.1.5", ServerReleaseState.STORED, null, 333, 334)).contains("schema V333")
    }

    @Test
    fun `a pinned release that never starts is marked failed, and the image runs from then on`() {
        val pinned = stored(Shape(version = "8.1.6"))
        val dir = Files.createTempDirectory("orknux-release-")
        val jar = dir.resolve(JAR_DIRECTORY).resolve("release-${pinned.id}.jar").toString()

        repeat(DEFAULT_RELEASE_BOOT_ATTEMPTS) { i ->
            val last = if (i == 0) null else 1
            assertThat(launcher(dir, pin = "8.1.6", lastExit = last, lastJar = last?.let { jar }).choose().toString()).isEqualTo(jar)
        }
        assertThat(launcher(dir, pin = "8.1.6", lastExit = 1, lastJar = jar).choose()).isEqualTo(IMAGE)

        val failed = releases.findById(pinned.id!!).get()
        assertThat(failed.state).isEqualTo(ServerReleaseState.FAILED)
        assertThat(failed.failure).contains("did not start in $DEFAULT_RELEASE_BOOT_ATTEMPTS attempts")
        // And the next start does not try it again.
        assertThat(launcher(dir, pin = "8.1.6").choose()).isEqualTo(IMAGE)
    }

    @Test
    fun `an active release counts only the starts its loop saw fail, and goes to the image, not back`() {
        val previous = stored(Shape(version = "8.1.7"))
        updates.activate(previous.id!!, "alice")
        store().restore(previous.id!!)
        val active = stored(Shape(version = "8.1.8"))
        updates.activate(active.id!!, "alice")
        store().restore(active.id!!) // it started
        val dir = Files.createTempDirectory("orknux-release-")
        val jar = dir.resolve(JAR_DIRECTORY).resolve("release-${active.id}.jar").toString()

        // Restarted by Docker, by an update, beside other replicas: not a failure.
        repeat(5) { assertThat(launcher(dir).choose().toString()).isEqualTo(jar) }
        assertThat(releases.findById(active.id!!).get().bootAttempts).isEqualTo(0)
        // Another jar's crash is not this one's.
        assertThat(launcher(dir, lastExit = 1, lastJar = "/tmp/orknux-release/release-999999.jar").choose().toString()).isEqualTo(jar)
        assertThat(releases.findById(active.id!!).get().bootAttempts).isEqualTo(0)

        repeat(DEFAULT_RELEASE_BOOT_ATTEMPTS - 1) {
            assertThat(launcher(dir, lastExit = 137, lastJar = jar).choose().toString()).isEqualTo(jar)
        }
        assertThat(launcher(dir, lastExit = 137, lastJar = jar).choose()).isEqualTo(IMAGE)
        assertThat(releases.findById(active.id!!).get().failure).contains("the last exited with 137")
        assertThat(releases.findById(previous.id!!).get().state).isEqualTo(ServerReleaseState.STORED)
    }

    @Test
    fun `a server that comes up forgives the starts counted against what it runs`() {
        val release = stored(Shape(version = "8.1.9"))
        jdbc.update("UPDATE server_release SET boot_attempts = 2 WHERE id = ?", release.id)
        val running = ServerReleases::class.java.getDeclaredField("runningJar").apply { isAccessible = true }
        val before = running.get(updates)
        running.set(updates, "/tmp/orknux-release/release-${release.id}.jar")
        try {
            updates.started()
        } finally {
            running.set(updates, before)
        }
        val after = releases.findById(release.id!!).get()
        assertThat(after.bootAttempts).isEqualTo(0)
        assertThat(after.bootedAt).isNotNull()
        assertThat(after.state).isEqualTo(ServerReleaseState.STORED)
    }

    @Test
    fun `the launcher runs a verified release, from the file it verified`() {
        val release = stored(Shape(version = "8.0.1"))
        updates.activate(release.id!!, "alice")
        val dir = Files.createTempDirectory("orknux-release-")

        val chosen = launcher(dir).choose()

        assertThat(chosen).isEqualTo(dir.resolve(JAR_DIRECTORY).resolve("release-${release.id}.jar"))
        assertThat(ReleaseJarVerifier.sha256(chosen)).isEqualTo(release.sha256)
        assertThat(Files.isWritable(chosen)).isFalse()
        assertThat(releases.findById(release.id!!).get().bootAttempts).isEqualTo(1)
    }

    /**
     * #600: the configured directory is a mount the launcher may not own - an
     * emptyDir with an fsGroup belongs to root - so its mode is never touched.
     * The jar goes into a directory the launcher makes, and that one is its
     * owner's alone; a jar left in the old place by an earlier release goes.
     * A directory the process cannot chmod is not something a test on any
     * platform can make without root, so the scripts/self-update run with a
     * root-owned volume is what covers the refusal itself.
     */
    @Test
    fun `the jar is written into a directory of the launcher's own, and the mounted one is left as it was`() {
        val release = stored(Shape(version = "8.0.7"))
        updates.activate(release.id!!, "alice")
        val dir = Files.createTempDirectory("orknux-release-")
        val posix = Files.getFileStore(dir).supportsFileAttributeView("posix")
        if (posix) Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxr-x"))
        val leftover = Files.writeString(dir.resolve("release-1.jar"), "an old layout's jar")
        val elsewhere = Files.writeString(dir.resolve("something-else"), "not ours to remove")

        val chosen = launcher(dir).choose()

        assertThat(chosen.parent).isEqualTo(dir.resolve(JAR_DIRECTORY))
        assertThat(Files.isSymbolicLink(chosen.parent)).isFalse()
        assertThat(leftover).doesNotExist()
        assertThat(elsewhere).exists()
        if (posix) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwxrwxr-x")
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(chosen.parent))).isEqualTo("rwx------")
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(chosen))).isEqualTo("r--------")
        }
        // A second start writes it again over the read-only copy.
        assertThat(launcher(dir).choose()).isEqualTo(chosen)
    }

    @Test
    fun `a file where the launcher's directory belongs is removed, never followed`() {
        val release = stored(Shape(version = "8.0.8"))
        updates.activate(release.id!!, "alice")
        val dir = Files.createTempDirectory("orknux-release-")
        Files.writeString(dir.resolve(JAR_DIRECTORY), "in the way")

        val chosen = launcher(dir).choose()

        assertThat(chosen).isEqualTo(dir.resolve(JAR_DIRECTORY).resolve("release-${release.id}.jar"))
        assertThat(ReleaseJarVerifier.sha256(chosen)).isEqualTo(release.sha256)
    }

    @Test
    fun `bytes changed in the database, hash and all, are refused at start-up and the image runs`() {
        val release = stored(Shape(version = "8.0.2"))
        updates.activate(release.id!!, "alice")
        val modified = TestReleaseJars.tampered(TestReleaseJars.signed(Shape(version = "8.0.2")))
        replaceBytes(release.id!!, modified)

        assertThat(launcher(Files.createTempDirectory("orknux-release-")).choose()).isEqualTo(IMAGE)

        val failed = releases.findById(release.id!!).get()
        assertThat(failed.state).isEqualTo(ServerReleaseState.FAILED)
        assertThat(failed.failure).contains("changed after the jar was signed")
        assertThat(failed.failureReported).isFalse()

        // And the next server up says so where an administrator will read it.
        updates.started()
        assertThat(audit.findAll().map { it.message }.single { it.startsWith("Server release 8.0.2") })
            .contains("failed to start")
        assertThat(releases.findById(release.id!!).get().failureReported).isTrue()
    }

    @Test
    fun `a jar re-signed with another key is refused at start-up and the image runs`() {
        val release = stored(Shape(version = "8.0.3"))
        updates.activate(release.id!!, "alice")
        replaceBytes(release.id!!, Files.readAllBytes(TestReleaseJars.signed(Shape(version = "8.0.3"), TestReleaseJars.other)))

        assertThat(launcher(Files.createTempDirectory("orknux-release-")).choose()).isEqualTo(IMAGE)
        assertThat(releases.findById(release.id!!).get().failure).contains("not with the Orknux release key")
    }

    @Test
    fun `a release that never starts is given up on, and what ran before runs again`() {
        val previous = stored(Shape(version = "8.0.4"))
        updates.activate(previous.id!!, "alice")
        store().restore(previous.id!!) // it started
        val next = stored(Shape(version = "8.0.5"))
        updates.activate(next.id!!, "alice")
        jdbc.update("UPDATE server_release SET boot_attempts = ? WHERE id = ?", DEFAULT_RELEASE_BOOT_ATTEMPTS, next.id)
        val dir = Files.createTempDirectory("orknux-release-")

        assertThat(launcher(dir).choose()).isEqualTo(dir.resolve(JAR_DIRECTORY).resolve("release-${previous.id}.jar"))
        assertThat(releases.findById(next.id!!).get().state).isEqualTo(ServerReleaseState.FAILED)
        assertThat(releases.findById(previous.id!!).get().state).isEqualTo(ServerReleaseState.ACTIVE)
    }

    /**
     * The start loop could not read the release table - an installation that
     * names its database only in SPRING_DATASOURCE_URL used to be one - so it
     * ran the image's jar. Following the choice would restart into the same
     * blind launcher every few seconds for ever; instead the server stays up on
     * what it runs and says why where an administrator will look.
     */
    @Test
    fun `a launcher that could not read the releases is not restarted into, and says why`() {
        val chosen = stored(Shape(version = "9.9.7"))
        graphQlTester.document("mutation { activateServerRelease(id: ${chosen.id}) { restarting } }").execute()
        val marker = releaseDir.resolve(BLIND_MARKER)
        java.nio.file.Files.writeString(marker, "java.net.ConnectException: Connection refused")
        try {
            assertThat(updates.launcherBlindness()).contains("Connection refused")

            updates.started()

            assertThat(audit.findAll().map { it.message })
                .anyMatch { it.contains("the start loop could not read the database") }
        } finally {
            java.nio.file.Files.deleteIfExists(marker)
        }
        assertThat(updates.launcherBlindness()).isNull()
    }

    @Test
    fun `a newer image wins over what the database chose under the old one`() {
        val release = stored(Shape(version = "8.0.6"))
        updates.activate(release.id!!, "alice")

        assertThat(launcher(Files.createTempDirectory("orknux-release-"), imageVersion = "99.0").choose()).isEqualTo(IMAGE)
        assertThat(releases.findById(release.id!!).get().state).isEqualTo(ServerReleaseState.STORED)
        assertThat(updates.wantedReleaseId()).isNull()
    }

    private fun replaceBytes(id: Long, bytes: ByteArray) {
        jdbc.update("DELETE FROM server_release_part WHERE release_id = ?", id)
        jdbc.update("INSERT INTO server_release_part (release_id, part, bytes) VALUES (?, 0, ?)", id, bytes)
        val sha = ReleaseJarVerifier.sha256(TestReleaseJars.temporary(bytes))
        jdbc.update("UPDATE server_release SET sha256 = ?, size = ? WHERE id = ?", sha, bytes.size.toLong(), id)
    }

    companion object {
        private val IMAGE: Path = Path.of("/app/app.jar")

        /** What the stub lists and serves; a test sets it. */
        lateinit var listedJar: Path

        /** A hash the listing claims instead of the real one, to spoil a download. */
        var listedSha: String? = null

        /** A marketplace that has never heard of `serverReleases`. */
        var refuseServerReleases = false

        private val stub: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                fun answer(exchange: HttpExchange, body: ByteArray) {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    exchange.close()
                }
                createContext("/graphql") { exchange ->
                    val asked = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                    if (refuseServerReleases || !asked.contains("serverReleases")) {
                        return@createContext answer(
                            exchange,
                            """{"errors":[{"message":"Field 'serverReleases' in type 'Query' is undefined"}]}""".toByteArray(),
                        )
                    }
                    val jar = listedJar
                    val sha = listedSha ?: ReleaseJarVerifier.sha256(jar)
                    val version = ReleaseJarVerifier.versionOf(jar)
                    answer(
                        exchange,
                        """
                        {"data":{"serverReleases":[{"version":"$version","publishedAt":"2026-10-02",
                          "changelog":"### Added\n- everything","jarUrl":"http://${where()}/server-releases/orknux-server-$version.jar",
                          "sha256":"$sha","size":${Files.size(jar)}}]}}
                        """.trimIndent().toByteArray(),
                    )
                }
                createContext("/server-releases/") { exchange ->
                    if (exchange.requestHeaders.getFirst("X-Orknux-Install-Key").isNullOrBlank()) {
                        exchange.sendResponseHeaders(401, -1)
                        exchange.close()
                        return@createContext
                    }
                    answer(exchange, Files.readAllBytes(listedJar))
                }
                start()
            }

        fun where() = "${stub.address.hostString}:${stub.address.port}"

        /** Where this server's launcher would have written, and left its marker. */
        val releaseDir: java.nio.file.Path = java.nio.file.Files.createTempDirectory("orknux-release-test")

        @JvmStatic
        @AfterAll
        fun stop() = stub.stop(0)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("orknux.marketplace.url") { "http://${where()}/graphql" }
            registry.add("orknux.update.certificate") { TestReleaseJars.trusted.pem.toUri().toString() }
            registry.add("orknux.update.release-dir") { releaseDir.toString() }
        }
    }
}
