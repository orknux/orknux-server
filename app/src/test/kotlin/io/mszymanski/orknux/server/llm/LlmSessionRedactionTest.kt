package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser

/**
 * That a transcript never holds the password, and still holds the build log.
 *
 * The same claim `WorkspaceAuditRecorderTest` makes about the audit table, and
 * it is made the same way: everything here is asserted on the row read back out
 * of the repository, never on what `save` handed over. A redaction done on the
 * way out to a page would pass a test that looked at the page and leave the
 * plaintext on disk, in every backup and in front of anybody who runs a
 * `SELECT` - and a secret that has been in a backup has to be treated as
 * disclosed, so where it happens is the whole of the fix.
 *
 * Two doors, two strengths, and the second half of this class is the half that
 * would go unnoticed if it broke. Arguments take the full rule set; results
 * take only what is a credential on sight, because a result is handed back to
 * the model as the answer to its own lookup and a build log turned into
 * asterisks is a model that cannot do the work.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LlmSessionRedactionTest(
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var session: Long = 0

    @BeforeEach
    fun reset() {
        events.deleteAll()
        sessions.deleteAll()
        workspaces.deleteAll()

        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        session = recorder.open(workspaceId, null, "redaction")
    }

    @Test
    fun `a password in a tool's arguments never reaches the row`() {
        recorder.toolCalled(
            session,
            "run_command",
            """{"command":"git push https://alice:s3cr3t@github.com/acme/repo.git main"}""",
        )

        assertThat(asked())
            .isEqualTo("""{"command":"git push https://alice:***@github.com/acme/repo.git main"}""")
    }

    @Test
    fun `a token in a tool's arguments never reaches the row`() {
        recorder.toolCalled(
            session,
            "run_command",
            """{"command":"curl -H 'Authorization: Bearer ghp_ZmFrZXRva2VuZm9yYXRlc3Q0Mg' https://api.example.com"}""",
        )

        assertThat(asked()).doesNotContain("ghp_", "s3cr3t")
        assertThat(asked()).contains("Authorization: ***")
    }

    @Test
    fun `an exported secret in a tool's arguments never reaches the row`() {
        recorder.toolCalled(session, "run_command", """{"command":"PGPASSWORD=s3cr3t psql -h db -U alice"}""")

        assertThat(asked()).isEqualTo("""{"command":"PGPASSWORD=*** psql -h db -U alice"}""")
    }

    @Test
    fun `ordinary arguments read the way they always did`() {
        recorder.toolCalled(session, "orknux_issues", """{"status":"OPEN","label":"p1"}""")

        assertThat(asked()).isEqualTo("""{"status":"OPEN","label":"p1"}""")
    }

    @Test
    fun `a key a command printed never reaches the row`() {
        recorder.toolReturned(
            recorder.toolCalled(session, "run_command", """{"command":"cat id_rsa"}"""),
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAvJ8kZmFrZWtleWZvcmF0ZXN0\n-----END RSA PRIVATE KEY-----",
        )

        assertThat(answered()).isEqualTo("***")
    }

    @Test
    fun `a token a command printed never reaches the row`() {
        recorder.toolReturned(
            recorder.toolCalled(session, "run_command", """{"command":"env"}"""),
            "HOME=/home/alice\nGITHUB_TOKEN=ghp_ZmFrZXRva2VuZm9yYXRlc3Q0Mg\nSHELL=/bin/sh",
        )

        assertThat(answered()).isEqualTo("HOME=/home/alice\nGITHUB_TOKEN=***\nSHELL=/bin/sh")
    }

    @Test
    fun `a build log is stored exactly as the command printed it`() {
        // The one that would break the product rather than leak from it. Every
        // line here carries a word the full rule set would replace, and the
        // model has to read all of them to fix the build.
        val log = "[INFO] Scanning for projects...\n" +
            "[ERROR] Db.kt:14:22: cannot find symbol: password\n" +
            "[INFO] --token TEXT is not a recognised option\n" +
            "spring.datasource.password=hunter2\n" +
            "[INFO] BUILD FAILURE"

        recorder.toolReturned(recorder.toolCalled(session, "run_command", """{"command":"mvn test"}"""), log)

        assertThat(answered()).isEqualTo(log)
    }

    /** The row itself, read back rather than what `save` handed over. */
    private fun stored(): LlmSessionEvent = events.findAll().single { it.kind == LlmSessionEventKind.TOOL }

    /** What the table says the model asked for. */
    private fun asked(): String = requireNotNull(stored().content)

    /** And what the table says it got back. */
    private fun answered(): String = requireNotNull(stored().result)
}
