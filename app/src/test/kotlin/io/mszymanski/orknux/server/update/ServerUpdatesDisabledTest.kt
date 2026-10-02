package io.mszymanski.orknux.server.update

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.nio.file.Files

/**
 * ORKNUX_SELF_UPDATE=false: the page says so and every door is shut, a valid
 * signed jar included. Issue #584.
 */
@SpringBootTest(properties = ["orknux.update.enabled=false"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ServerUpdatesDisabledTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val context: WebApplicationContext,
    @Autowired val releases: ServerReleaseRepository,
) {

    @Test
    fun `turned off, the page says so and nothing is stored or started`() {
        val before = releases.count()
        graphQlTester.document("{ serverUpdates { enabled runningVersion officialEnabled uploadEnabled urlEnabled stored { id } } }")
            .execute()
            .path("serverUpdates.enabled").entity(Boolean::class.java).isEqualTo(false)
            // The master switch turns every source off with it, whatever their own say.
            .path("serverUpdates.officialEnabled").entity(Boolean::class.java).isEqualTo(false)
            .path("serverUpdates.uploadEnabled").entity(Boolean::class.java).isEqualTo(false)
            .path("serverUpdates.urlEnabled").entity(Boolean::class.java).isEqualTo(false)
            .path("serverUpdates.runningVersion").hasValue()

        graphQlTester.document("mutation { activateServerRelease(id: 1) { restarting } }").execute()
            .errors().satisfy { errors -> assertThat(errors.single().extensions["code"]).isEqualTo("ServerUpdatesDisabled") }
        graphQlTester.document("""mutation { installServerRelease(version: "9.9.9") { restarting } }""").execute()
            .errors().satisfy { errors -> assertThat(errors.single().extensions["code"]).isEqualTo("ServerUpdatesDisabled") }
        // Refused before anything is fetched: the address cannot even resolve.
        graphQlTester.document("""mutation { installServerReleaseFromUrl(url: "https://artifactory.invalid/orknux.jar") { id } }""")
            .execute()
            .errors().satisfy { errors -> assertThat(errors.single().extensions["code"]).isEqualTo("ServerUpdatesDisabled") }
        graphQlTester.document("""{ serverReleasesAtUrl(url: "https://artifactory.invalid/orknux/") { version } }""")
            .execute()
            .errors().satisfy { errors -> assertThat(errors.single().extensions["code"]).isEqualTo("ServerUpdatesDisabled") }

        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        mockMvc.perform(
            post("/api/server-releases").contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(Files.readAllBytes(TestReleaseJars.signed())),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ServerUpdatesDisabled"))
        assertThat(releases.count()).isEqualTo(before)
    }
}
