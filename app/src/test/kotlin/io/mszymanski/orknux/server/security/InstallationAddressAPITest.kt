package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.chat.LinkTools
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/** Admin -> Settings -> Address: set from the browser, it is what links are built on. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class InstallationAddressAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val links: LinkTools,
    @Autowired val mapper: ObjectMapper,
) {

    @AfterEach
    fun reset() {
        graphQlTester.document("""mutation { setInstallationAddress(url: "") { url } }""").execute()
    }

    private fun linked(): String = mapper.readTree(
        links.shed().run(ToolCall(id = "1", name = LinkTools.LINK, arguments = """{"path":"/workspace/1"}""")),
    ).path("url").stringValue()

    @Test
    fun `a set address is what links are built on, and blank goes back to the configured one`() {
        val configured = graphQlTester.document("{ installationAddress { configured } }").execute()
            .path("installationAddress.configured").entity(String::class.java).get()

        graphQlTester.document("""mutation { setInstallationAddress(url: "https://orknux.example.com/") { url stored } }""")
            .execute()
            .path("setInstallationAddress.url").entity(String::class.java).isEqualTo("https://orknux.example.com")
            .path("setInstallationAddress.stored").entity(String::class.java).isEqualTo("https://orknux.example.com")
        assertThat(linked()).isEqualTo("https://orknux.example.com/workspace/1")

        graphQlTester.document("""mutation { setInstallationAddress(url: "  ") { url stored } }""").execute()
            .path("setInstallationAddress.url").entity(String::class.java).isEqualTo(configured)
            .path("setInstallationAddress.stored").valueIsNull()
    }

    @Test
    fun `an address that is not one is refused`() {
        graphQlTester.document("""mutation { setInstallationAddress(url: "orknux.example.com") { url } }""").execute()
            .errors().expect { it.extensions["code"] == "InstallationAddressInvalid" }
    }

    @Test
    @WithMockUser(username = "bob")
    fun `only an administrator sets it`() {
        graphQlTester.document("""mutation { setInstallationAddress(url: "https://evil.example") { url } }""").execute()
            .errors().expect { true }
    }
}
