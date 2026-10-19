package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.security.InstallationAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** `app_link`: the installation's address, and a path made a link that opens anywhere. */
class LinkToolsTest {

    private val mapper = JsonMapper.builder().build()
    private val shed = LinkTools(InstallationAddress.fixed("https://orknux.example.com/"), mapper).shed()

    private fun call(arguments: String) = mapper.readTree(shed.run(ToolCall(id = "1", name = LinkTools.LINK, arguments = arguments)))

    @Test
    fun `a path becomes an absolute link on the configured host`() {
        val answer = call("""{ "path": "/workspace/9/tasks/28" }""")
        assertThat(answer.path("base").stringValue()).isEqualTo("https://orknux.example.com")
        assertThat(answer.path("url").stringValue()).isEqualTo("https://orknux.example.com/workspace/9/tasks/28")
    }

    @Test
    fun `without a path the address alone, and a link already absolute is left as it is`() {
        assertThat(call("{}").has("url")).isFalse()
        assertThat(LinkTools.absolute("https://a.example", "https://b.example/x")).isEqualTo("https://b.example/x")
        assertThat(LinkTools.absolute("https://a.example", "workspace/1")).isEqualTo("https://a.example/workspace/1")
    }
}
