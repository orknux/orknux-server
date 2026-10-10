package io.mszymanski.orknux.server.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * How many times a round is asked again after an empty answer, and how many
 * times an unusable reply goes back to the model, are Admin settings. Both were
 * one constant, two, which a busy local model ran through in seconds.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class RetrySettingsTest(@Autowired val graphQlTester: ExecutionGraphQlServiceTester) {

    @Test
    fun `a fresh installation asks an empty answer again five times and puts an unusable reply back twice`() {
        graphQlTester.document("{ installationSettings { emptyAnswerRetriesConfigured unusableReplyRetriesConfigured } }")
            .execute().also {
                it.path("installationSettings.emptyAnswerRetriesConfigured").entity(Int::class.java).isEqualTo(5)
                it.path("installationSettings.unusableReplyRetriesConfigured").entity(Int::class.java).isEqualTo(2)
            }
    }

    @Test
    fun `both are saved within their bounds and refused outside them`() {
        graphQlTester.document("mutation { setEmptyAnswerRetries(retries: 7) { emptyAnswerRetries } }")
            .execute().path("setEmptyAnswerRetries.emptyAnswerRetries").entity(Int::class.java).isEqualTo(7)
        graphQlTester.document("mutation { setUnusableReplyRetries(retries: 3) { unusableReplyRetries } }")
            .execute().path("setUnusableReplyRetries.unusableReplyRetries").entity(Int::class.java).isEqualTo(3)
        listOf(
            "mutation { setEmptyAnswerRetries(retries: 21) { emptyAnswerRetries } }" to "EmptyAnswerRetriesOutOfRange",
            "mutation { setUnusableReplyRetries(retries: 11) { unusableReplyRetries } }" to "UnusableReplyRetriesOutOfRange",
        ).forEach { (document, code) ->
            graphQlTester.document(document).execute().errors().satisfy { errors ->
                assertThat(errors.single().extensions["code"]).isEqualTo(code)
            }
        }
        graphQlTester.document("mutation { setEmptyAnswerRetries(retries: 5) { emptyAnswerRetries } }").execute()
        graphQlTester.document("mutation { setUnusableReplyRetries(retries: 2) { unusableReplyRetries } }").execute()
    }
}
