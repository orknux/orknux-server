package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * What plan mode leaves an agent, issue #653: the tools that say they change
 * nothing, read off their own declarations, and nothing that says otherwise or
 * says nothing at all.
 */
@SpringBootTest
class ToolEffectsTest(
    @Autowired val effects: ToolEffects,
    @Autowired val tools: AgentTools,
    @Autowired val orknux: OrknuxTools,
) {

    private fun spec(name: String, readOnly: Boolean = false) = ToolSpec(name = name, description = name, readOnly = readOnly)

    @Test
    fun `a tool is read-only only where its declaration says so`() {
        assertThat(effects.readOnly(spec("anything", readOnly = true))).isTrue()
        // Nobody said: it may change something, so it is kept back.
        assertThat(effects.readOnly(spec("anything"))).isFalse()
    }

    @Test
    fun `the orknux tools a reading scope is offered are read-only, and the writing ones are not`() {
        val reading = orknux.specs(OrknuxScope(workspaceId = 0, mayWrite = false)).map { it.name }.toSet()
        val writing = orknux.specs(OrknuxScope(workspaceId = 0, mayWrite = true)).filterNot { it.name in reading }
        assertThat(reading).isNotEmpty()
        assertThat(writing).isNotEmpty()
        assertThat(reading).allMatch { effects.readOnly(spec(it)) }
        assertThat(writing).noneMatch { effects.readOnly(it) }
    }

    @Test
    fun `an agent's built-ins split into what looks and what changes`() {
        val agent = Agent(workspaceId = 1, name = "Planner", type = AgentType.LLM)
        val held = effects.readOnly(tools.offeringFor(agent, ceiling = null)).let { it.core + it.searchable }.map { it.name }
        assertThat(held).contains(AgentTools.VIEW, AgentTools.VALIDATE, "date_now", "pdf_read")
        assertThat(held).doesNotContain(AgentTools.SAVE_ARTIFACT, "http_request")
    }

    @Test
    fun `a lent shed keeps only what looks, and refuses to handle the rest`() {
        val shed = object : ToolShed {
            override fun specs() = listOf(spec("look", readOnly = true), spec("change"))
            override fun briefing() = "about these"
            override fun handles(name: String) = name == "look" || name == "change"
            override fun run(call: ToolCall) = "ran ${call.name}"
        }
        val kept = requireNotNull(effects.readOnly(shed))
        assertThat(kept.specs().map { it.name }).containsExactly("look")
        assertThat(kept.handles("look")).isTrue()
        assertThat(kept.handles("change")).isFalse()
        assertThat(kept.briefing()).isEqualTo("about these")
    }

    @Test
    fun `plan mode names the tool it lends by its declaration, and that tool only looks`() {
        assertThat(ChatModes.PLAN_BRIEFING).contains(ChatModes.PRESENT_SPEC.name)
        assertThat(ChatModes.PRESENT_SPEC.parameters.map { it.name }).containsExactly(ChatModes.PLAN)
        assertThat(effects.readOnly(ChatModes.PRESENT_SPEC)).isTrue()
        // Auto mode lends no tool, so its paragraph names none.
        assertThat(ChatModes.AUTO_BRIEFING).doesNotContain("_")
    }
}
