package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The built-ins that reach outside, and why their rule is the other way round.
 * Issues #509 and #510.
 *
 * Every other built-in is governed by a list of what is *refused*, which was
 * the answer #455 settled on: a name nobody has had an opinion about answers
 * "yes", so a tool added in a later release arrives switched on rather than
 * silently off everywhere.
 *
 * That is exactly wrong for a tool that makes requests to somewhere else. These
 * are governed by a list of what is *allowed*, so a name nobody has had an
 * opinion about answers "no" - and the next one added does not switch itself on
 * across every installation the day it ships.
 */
class ReachingToolsTest {

    private fun agentWith(tools: List<String> = emptyList(), hidden: List<String> = emptyList()) =
        Agent(workspaceId = 1, name = "a", type = AgentType.LLM).apply {
            this.tools = tools.toMutableList()
            this.hiddenTools = hidden.toMutableList()
        }

    @Test
    fun `a reaching tool is off for an agent that has not asked for it`() {
        val agent = agentWith()
        BuiltInTools.REACHING.forEach { name ->
            assertThat(BuiltInTools.granted(agent, name))
                .describedAs(name)
                .isFalse()
        }
    }

    @Test
    fun `and on for one that names it`() {
        val agent = agentWith(tools = listOf("http_get"))
        assertThat(BuiltInTools.granted(agent, "http_get")).isTrue()
        // Naming one does not bring the others.
        assertThat(BuiltInTools.granted(agent, "web_search")).isFalse()
    }

    /**
     * The difference that matters, stated as a test: an ordinary built-in is on
     * for an agent that has never heard of it, and a reaching one is not.
     */
    @Test
    fun `an ordinary built-in is still on by default`() {
        val agent = agentWith()
        assertThat(BuiltInTools.granted(agent, DateTools.NOW)).isTrue()
        assertThat(BuiltInTools.granted(agent, "pdf_fromHtml")).isTrue()
    }

    /**
     * And the hidden list does not reach them.
     *
     * That list is how the unsafe-visibility switch works, and it is
     * deliberately not what governs these: switching off something that makes
     * outbound requests is the cautious direction and must not need a scary
     * workspace setting first.
     */
    @Test
    fun `hiding is not how a reaching tool is switched`() {
        val hidden = agentWith(tools = listOf("web_search"), hidden = listOf("web_search"))
        assertThat(BuiltInTools.granted(hidden, "web_search")).isTrue()
    }

    @Test
    fun `they are listed as switchable, under their own governance`() {
        BuiltInTools.REACHING.forEach { name ->
            assertThat(BuiltInTools.switchable(name)).describedAs(name).isTrue()
            assertThat(BuiltInTools.reaches(name)).describedAs(name).isTrue()
        }
        assertThat(BuiltInTools.reaches("pdf_fromHtml")).isFalse()
    }

    @Test
    fun `what an agent holds includes the reaching ones it asked for`() {
        val agent = agentWith(tools = listOf("http_download"))
        assertThat(BuiltInTools.grantedTo(agent)).contains("http_download")
        assertThat(BuiltInTools.grantedTo(agentWith())).doesNotContain("http_download")
    }
}
