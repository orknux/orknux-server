package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/**
 * An agent reads what its conversation has cost: the numbers its session page
 * shows - input tokens, cached, output, the price - read off the same columns,
 * and what the agents it asked cost beside them.
 */
@SpringBootTest
class SessionUsageToolsTest(
    @Autowired val sheds: SessionSheds,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private fun agentIn(workspace: Long) = Agent(workspaceId = workspace, name = "OKO Bot", type = AgentType.LLM)

    @Test
    fun `every session lends it, and it answers what the page shows`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "usage-${System.nanoTime()}")).id)
        val main = recorder.open(workspace, "slack_thread", "t-${System.nanoTime()}")
        val asked = recorder.openUnder(main, "Summarize")
        sessions.addSpend(main, 757_543, 7_284, 614_653, BigDecimal("0.1327"), false)
        sessions.addSpend(asked, 1_000, 200, 0, BigDecimal.ZERO, true)

        val shed = requireNotNull(sheds.of(agentIn(workspace), main))
        assertThat(shed.specs().map { it.name }).contains(SessionUsageTools.USAGE)
        val said = mapper.readTree(shed.run(ToolCall("c1", SessionUsageTools.USAGE, "{}")))

        val here = said.path("conversation")
        assertThat(here.path("inputTokens").longValue()).isEqualTo(757_543)
        assertThat(here.path("cachedInputTokens").longValue()).isEqualTo(614_653)
        assertThat(here.path("cachedShare").stringValue()).isEqualTo("81%")
        assertThat(here.path("outputTokens").longValue()).isEqualTo(7_284)
        assertThat(here.path("cost").stringValue()).isEqualTo("0.1327")

        val subagents = said.path("subagents")
        assertThat(subagents.path("asks").intValue()).isEqualTo(1)
        assertThat(subagents.path("inputTokens").longValue()).isEqualTo(1_000)
        assertThat(subagents.path("partlyUnpriced").booleanValue()).isTrue()
    }

    @Test
    fun `a turn with no session is not lent it`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "usage-none-${System.nanoTime()}")).id)
        assertThat(sheds.of(agentIn(workspace), null)?.specs().orEmpty().map { it.name }).doesNotContain(SessionUsageTools.USAGE)
    }
}
