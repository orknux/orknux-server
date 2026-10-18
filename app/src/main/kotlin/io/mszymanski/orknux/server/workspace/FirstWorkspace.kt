package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.attachment.InstallationSetting
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.security.ActingIn
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The workspace a fresh installation starts with.
 *
 * A new installation - the desktop app on its first start, an orknux-one
 * container, a server pointed at an empty database - opened on "There is no
 * workspace to chat in yet", and the only way on was the admin section, which
 * somebody who has just installed the product has no reason to know about.
 * So the first start makes one, exactly as the admin page would.
 *
 * Once, and only on an installation that has never had one: the setting it
 * leaves says so. An installation that already has workspaces when this
 * arrives is marked as done and given nothing, and one whose administrator
 * later removes every workspace is not handed a new one on the next restart -
 * that was a decision, and a start-up that undid it would be a start-up
 * arguing with the person who runs it.
 *
 * Before the port opens, like [io.mszymanski.orknux.server.user.BootstrapAdmin]:
 * the first page anybody loads already has it.
 */
@Component
class FirstWorkspace(
    private val workspaces: WorkspaceRepository,
    private val api: WorkspaceAPI,
    private val settings: InstallationSettingRepository,
    private val transactions: PlatformTransactionManager,
    /** Off in the test suite, whose contexts share a database other tests count workspaces in. */
    @Value("\${orknux.workspace.first-enabled:true}") private val enabled: Boolean,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        if (!enabled) return
        runCatching { TransactionTemplate(transactions).executeWithoutResult { ensure() } }
            // A workspace not made is a page that says so, not a server that will not start.
            .onFailure { log.warn("Could not make the first workspace: {}", it.message) }
    }

    /** Makes the first workspace where this installation has never had one, and remembers either way. */
    fun ensure(name: String = FIRST_WORKSPACE_NAME) {
        if (settings.existsById(DONE)) return
        if (workspaces.count() == 0L) {
            // Nobody is signed in at start-up; the audit entry names the system, and nothing is granted by it.
            ActingIn.run(NO_WORKSPACE, "system") { api.create(name, FIRST_WORKSPACE_DESCRIPTION) }
            log.info("Made the first workspace, {}, on an installation that had none", name)
        }
        settings.save(InstallationSetting(name = DONE, value = "true"))
    }

    companion object {
        /** What the first workspace is called; renamed like any other. */
        const val FIRST_WORKSPACE_NAME = "Default"
        const val FIRST_WORKSPACE_DESCRIPTION = "The workspace this installation started with."

        /** The setting that says the first start has been and gone. */
        const val DONE = "workspace.first.made"

        /** No workspace id: what is acted in is being made, so nothing existing is granted. */
        private const val NO_WORKSPACE = -1L
        private val log = LoggerFactory.getLogger(FirstWorkspace::class.java)
    }
}
