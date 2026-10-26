package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.chat.ToolShed
import io.mszymanski.orknux.server.embedded.EmbeddedCapabilities
import org.springframework.stereotype.Component

/**
 * Web search, lent to an agent for one workflow step. Issue #678.
 *
 * A research that searches the web runs with the agents somebody picked for
 * it, and web search is off on an agent until somebody turns it on (see
 * [BuiltInTools.REACHING]) - so a research's planning and writing agents, and anybody's
 * own agent picked in their place, would plan and write about the web without
 * being able to look at it. The research asked for the web, so for that run
 * the agents it runs with may search it; the agent's own grants are not
 * touched, and the next run that does not ask lends nothing.
 *
 * Lent rather than granted for the reason [ToolShed] says: it is the run's
 * decision, not the agent's. The tool is the agent's own `web_search`, run the
 * way [io.mszymanski.orknux.server.chat.AgentTools] runs it, so a lent search
 * and a granted one answer alike. Nothing is lent to an agent that already
 * holds it, since one name offered twice is a request a provider refuses.
 */
@Component
class LentWebSearch(private val embedded: EmbeddedCapabilities) {

    /** The shed for [agent]'s step, or null where there is nothing to lend. */
    fun shed(agent: Agent, sessionId: Long?): ToolShed? {
        if (BuiltInTools.granted(agent, TOOL)) return null
        val spec = embedded.toolSpecs().firstOrNull { it.name == TOOL } ?: return null
        return object : ToolShed {
            override fun specs(): List<ToolSpec> = listOf(spec)

            override fun handles(name: String): Boolean = name == TOOL

            // The run's decision, not the agent's: lent though its Tools list leaves web search off.
            override fun lentRegardless(name: String): Boolean = name == TOOL

            override fun run(call: ToolCall): String = embedded.run(TOOL, call.arguments, agent.workspaceId, sessionId)
        }
    }

    companion object {
        /** The built-in lent, as the Tools list names it. */
        const val TOOL = "web_search"
    }
}
