package io.mszymanski.orknux.server.flag

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Whether an agent's Tools list may say anything about the two flag tools.
 *
 * Off by default (Admin Settings -> Sessions -> "Flag tools on agents' Tools
 * lists"): the rows are not drawn, and every agent holds both tools at Always,
 * from every door - the form, the MCP, an import - because a save that tries to
 * change their mark is put back here rather than refused. Put back rather than
 * refused, because a form that draws no row for them sends no opinion about
 * them, and a refusal there would be a save nobody could make. Whether the
 * tools are lent at all is the other switch, Session flagging, and is decided
 * in [SessionFlagTools.shed].
 *
 * On, the rows are drawn and an administrator marks them per agent like any
 * other built-in. Switched off again, every agent is put back to Always on
 * both: "off" means the marks are not the agent's to hold, and keeping the
 * ones somebody set while it was on would leave agents carrying a choice the
 * screen no longer shows.
 */
@Service
class SessionFlagToolMarks(
    private val settings: InstallationSettings,
    private val agents: AgentRepository,
    private val revisions: ComponentRevisionRecorder,
) {

    /** Whether the Tools list draws the two rows and a save may change them. */
    fun adjustable(): Boolean = settings.sessionFlagToolsAdjustable()

    /** The names an agent's Tools list leaves out: both flag tools while not adjustable, none otherwise. */
    fun withheld(): Set<String> = if (adjustable()) emptySet() else SessionFlagTools.NAMES

    /** [agent] with both tools held at Always where the marks are not adjustable; what a save calls last. */
    fun hold(agent: Agent) {
        if (!adjustable()) holdAlways(agent)
    }

    /**
     * Sets the switch, and switched off puts every agent back to Always on
     * both tools, each changed agent recorded as a version.
     */
    @Transactional
    fun set(adjustable: Boolean, by: String) {
        settings.setSessionFlagToolsAdjustable(adjustable, by)
        if (adjustable) return
        agents.findAll().filter(::differs).forEach { agent ->
            revisions.saved(agent)
            holdAlways(agent)
            agent.lastModifiedAt = OffsetDateTime.now()
            agent.lastModifiedBy = by
            agents.save(agent)
        }
    }

    private fun differs(agent: Agent): Boolean =
        SessionFlagTools.NAMES.any { it in agent.hiddenTools || it !in agent.requiredTools }

    private fun holdAlways(agent: Agent) {
        agent.hiddenTools.removeAll(SessionFlagTools.NAMES)
        SessionFlagTools.NAMES.filter { it !in agent.requiredTools }.forEach { agent.requiredTools.add(it) }
    }
}
