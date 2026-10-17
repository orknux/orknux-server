package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.orkbox.model.CreateSessionRequest
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * The local box is started by the call that wants a session, so the first
 * session of every instance is opened the moment it comes up. orkbox clears
 * away what a previous run left on a coroutine of its own, and a session made
 * before that coroutine had listed the root was in the listing: its marker
 * names this server, so it was taken for a previous run's and deleted, and
 * the session answered `workspace_missing` on its first command. That is how
 * BoxSecretsTest and BoxToolsTest failed CI - each with a `Removing workspace
 * ... left by a previous run` line in a root made empty for that very test.
 *
 * What rules it out is that the clean-up is over before the box answers, and
 * that is what is held here: a large leftover is gone by the time [LocalBox.client]
 * returns, rather than some time later, beside whatever was asked for next.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class LocalBoxStartTest(
    @Autowired val settings: BoxSettings,
    @Autowired val local: LocalBox,
    @Autowired val stored: InstallationSettingRepository,
) {

    @TempDir
    lateinit var root: Path

    @BeforeEach
    fun seed() {
        clean()
        settings.setLocalRuntime("local", "alice")
        settings.setLocalLocation(root.toString(), "alice")
    }

    @AfterEach
    fun clean() {
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("tin.") })
        local.refresh()
    }

    @Test
    fun `what a previous run left is cleared away before the box answers`() {
        // Left as a server that died leaves it: a workspace, and the marker naming this server.
        val left = Files.createDirectories(root.resolve("ses_left0behind0000"))
        // Big enough that removing it takes longer than the next line does.
        repeat(LEFT_FILES) { Files.writeString(left.resolve("file-$it.txt"), "x") }
        Files.writeString(root.resolve("ses_left0behind0000.owner"), settings.localOwner())

        local.client()

        assertThat(left).doesNotExist()
    }

    @Test
    fun `a session opened as the box starts keeps its workspace`() {
        val session = local.client().create(CreateSessionRequest())

        assertThat(root.resolve(session.id)).isDirectory()
        assertThat(local.client().session(session.id).status).isEqualTo("ready")
    }

    @Test
    fun `a workspace another server made under the same root is left alone`() {
        val theirs = Files.createDirectories(root.resolve("ses_somebody0else00"))
        Files.writeString(root.resolve("ses_somebody0else00.owner"), "orkbox")

        local.client()

        assertThat(theirs).isDirectory()
    }

    private companion object {
        const val LEFT_FILES = 2000
    }
}
