package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.chat.ToolShed
import io.mszymanski.orknux.server.workflow.SavedArtifacts
import org.springframework.stereotype.Service

/**
 * `save_artifact` for a task, whatever the agent's list says.
 *
 * A task works with nobody watching, and what it makes for somebody - a PDF, a
 * page, a zip - has to end up where they will find it. Reported: a task made a
 * PDF, said it had, and the file was only in its session, so nobody could open
 * it. The workspace's Artifacts is that place, so a task always has the tool:
 * the agent's own grant where it has one, and this where it was hidden.
 */
@Service
class TaskArtifacts(
    private val tools: AgentTools,
    private val artifacts: SavedArtifacts,
) {

    /** The shed for one turn, or null where the agent is offered the tool already or files cannot be kept. */
    fun shed(agent: Agent, session: Long): ToolShed? {
        if (!artifacts.offered() || BuiltInTools.granted(agent, AgentTools.SAVE_ARTIFACT)) return null
        return object : ToolShed {
            override fun specs(): List<ToolSpec> = AgentTools.ARTIFACT_TOOLS.filter { it.name == AgentTools.SAVE_ARTIFACT }
            override fun handles(name: String): Boolean = name == AgentTools.SAVE_ARTIFACT
            override fun run(call: ToolCall): String = tools.saveArtifact(agent, call, session)
        }
    }
}
