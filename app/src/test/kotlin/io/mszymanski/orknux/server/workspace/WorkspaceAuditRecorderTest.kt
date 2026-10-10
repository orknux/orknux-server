package io.mszymanski.orknux.server.workspace

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser

/**
 * That the redaction happens on the way in, and not on the way out.
 *
 * This is the assertion the whole fix rests on: not that the audit page hides a
 * password, but that the row in `workspace_audit` never held one. A read-time
 * filter would pass a test that looked at what the page shows and leave the
 * plaintext on disk, in every backup, and in front of anyone who runs a `SELECT`
 * — so what is asserted here is the stored message, read back out of the
 * repository, on every one of the recorder's three ways in.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceAuditRecorderTest(
    @Autowired val recorder: WorkspaceAuditRecorder,
    @Autowired val repository: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        repository.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "operations")).id)
    }

    @Test
    fun `a command an agent ran is stored without its password`() {
        recorder.recordAutomated(
            workspaceId,
            WorkspaceAuditCategory.AGENT,
            "sre ran on box: git push https://alice:s3cr3t@github.com/acme/repo.git main (exit 0)",
            "sre",
        )

        assertThat(stored()).isEqualTo(
            "sre ran on box: git push https://alice:***@github.com/acme/repo.git main (exit 0)",
        )
    }

    @Test
    fun `a message recorded for a person is stored without its token`() {
        recorder.record(
            workspaceId,
            WorkspaceAuditCategory.AGENT,
            "alice ran on box: curl -H 'Authorization: Bearer ghp_ZmFrZXRva2VuZm9yYXRlc3Q0Mg' https://api.example.com",
        )

        assertThat(stored()).doesNotContain("ghp_", "Bearer ghp")
        assertThat(stored()).contains("Authorization: ***")
    }

    @Test
    fun `an admin-level message is redacted too`() {
        recorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Connection box added with PGPASSWORD=s3cr3t")

        assertThat(stored()).isEqualTo("Connection box added with PGPASSWORD=***")
    }

    @Test
    fun `a workspace lifecycle entry still reads the way it always did`() {
        // Nothing in a rename looks like a credential, and the entry an
        // administrator is used to reading has to come out unchanged.
        recorder.record(workspaceId, WorkspaceOperationType.RENAME, "operations", "platform")

        assertThat(stored()).isEqualTo("Workspace operations renamed to platform")
    }

    /** What the table holds, read back rather than what `save` handed back. */
    private fun stored(): String = repository.findAll().single().message
}
