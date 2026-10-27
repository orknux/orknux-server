package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.workflow.script.PluginCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper

/**
 * What a Slack call that names no Slack is told: why, and what to pass instead.
 *
 * "The first argument has to be a Slack connection" was the whole answer to a
 * model that had written "id1", and to one that left the connection out on a
 * plugin with no Slack of its own. Neither said what to do next, and the agent
 * searched elsewhere and gave up.
 */
@SpringBootTest
class SlackNoConnectionTest(@Autowired val host: SlackPluginHost, @Autowired val mapper: ObjectMapper) {

    private fun error(capability: PluginCapability, argument: String): String =
        mapper.readTree(host.ask(capability, argument, null)).path("error").asString()

    @Test
    fun `a connection that is not one is named, with what to pass instead`() {
        val said = error(PluginCapability.SLACK_READ_THREAD, """["id1", "C1", "1700000000.000100", 20]""")

        assertThat(said).startsWith("id1 is not a Slack connection")
            .contains("the id of one of the Slack connections you have been granted")
            .contains("leave it out")
    }

    @Test
    fun `none given and none set up says both ways to give one`() {
        listOf("""[null, "C1", "1700000000.000100", 20]""", """["", "C1", "1700000000.000100", 20]""").forEach { argument ->
            val said = error(PluginCapability.SLACK_READ_THREAD, argument)
            assertThat(said).startsWith("no Slack connection was given, and the Slack plugin has none set up")
                .contains("Plugins page")
        }
    }

    @Test
    fun `every Slack call says it the same way`() {
        assertThat(error(PluginCapability.SLACK_SEARCH, """["id1", "deploy"]""")).startsWith("id1 is not a Slack connection")
        assertThat(error(PluginCapability.SLACK_ADD_REACTION, """["id1", "C1", "1700000000.000100", "eyes"]"""))
            .startsWith("id1 is not a Slack connection")
    }
}
