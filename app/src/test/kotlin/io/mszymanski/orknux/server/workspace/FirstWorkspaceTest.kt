package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * A fresh installation starts with a workspace; one that has ever had one does
 * not get another.
 */
@SpringBootTest
class FirstWorkspaceTest(
    @Autowired val first: FirstWorkspace,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val settings: InstallationSettingRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val catalogs: MemoryCatalogRepository,
    @Autowired val api: WorkspaceAPI,
    @Autowired val transactions: org.springframework.transaction.PlatformTransactionManager,
) {

    @BeforeEach
    fun forget() {
        settings.deleteById(FirstWorkspace.DONE)
    }

    @Test
    fun `an installation with workspaces is marked done and given nothing`() {
        workspaces.save(Workspace(name = "already-${System.nanoTime()}"))
        val before = workspaces.count()

        first.ensure()

        assertThat(workspaces.count()).isEqualTo(before)
        assertThat(settings.existsById(FirstWorkspace.DONE)).isTrue()
    }

    @Test
    fun `an empty installation gets one, made as the admin page makes one, and only once`() {
        val name = "first-${System.nanoTime()}"
        // Every other test's workspaces stand in the way of an empty table, so the count is asked of a copy that has none.
        val emptied = FirstWorkspace(
            object : WorkspaceRepository by workspaces {
                override fun count(): Long = 0
            },
            api, settings, transactions,
            true,
        )
        emptied.ensure(name)

        val made = workspaces.findByName(name)
        assertThat(made).isNotNull()
        // With what a workspace arrives with - a memory catalog among them - and an audit line naming the system.
        assertThat(catalogs.findAll().any { it.workspaceId == made!!.id }).isTrue()
        assertThat(audit.findAll().any { it.workspaceId == made!!.id && it.userId == "system" }).isTrue()

        // Remembered: a second start, or every workspace removed, makes nothing.
        emptied.ensure("$name-again")
        assertThat(workspaces.findByName("$name-again")).isNull()
    }

}
