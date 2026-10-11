package io.mszymanski.orknux.server.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * How often a page watching a model write is told how far it has got, and how
 * long such a report is believed, are Admin settings. Both were constants - a
 * second and thirty seconds.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WritingProgressSettingsTest(@Autowired val graphQlTester: ExecutionGraphQlServiceTester) {

    @Test
    fun `a fresh installation reports writing every second and forgets a report after thirty`() {
        graphQlTester.document("{ installationSettings { writingProgressMillisConfigured writingForgottenSecondsConfigured } }")
            .execute().also {
                it.path("installationSettings.writingProgressMillisConfigured").entity(Int::class.java).isEqualTo(1000)
                it.path("installationSettings.writingForgottenSecondsConfigured").entity(Int::class.java).isEqualTo(30)
            }
    }

    @Test
    fun `both are saved within their bounds and refused outside them`() {
        graphQlTester.document("mutation { setWritingProgressMillis(millis: 500) { writingProgressMillis } }")
            .execute().path("setWritingProgressMillis.writingProgressMillis").entity(Int::class.java).isEqualTo(500)
        graphQlTester.document("mutation { setWritingForgottenSeconds(seconds: 90) { writingForgottenSeconds } }")
            .execute().path("setWritingForgottenSeconds.writingForgottenSeconds").entity(Int::class.java).isEqualTo(90)
        listOf(
            "mutation { setWritingProgressMillis(millis: 100) { writingProgressMillis } }" to "WritingProgressIntervalOutOfRange",
            "mutation { setWritingForgottenSeconds(seconds: 601) { writingForgottenSeconds } }" to "WritingForgottenOutOfRange",
        ).forEach { (document, code) ->
            graphQlTester.document(document).execute().errors().satisfy { errors ->
                assertThat(errors.single().extensions["code"]).isEqualTo(code)
            }
        }
        graphQlTester.document("mutation { setWritingProgressMillis(millis: 1000) { writingProgressMillis } }").execute()
        graphQlTester.document("mutation { setWritingForgottenSeconds(seconds: 30) { writingForgottenSeconds } }").execute()
    }
}
