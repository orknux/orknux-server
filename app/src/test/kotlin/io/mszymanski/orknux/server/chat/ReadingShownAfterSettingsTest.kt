package io.mszymanski.orknux.server.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/** How long a prompt is read before the page is told it is being read is an Admin setting, five seconds by default. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ReadingShownAfterSettingsTest(@Autowired val graphQlTester: ExecutionGraphQlServiceTester) {

    @Test
    fun `a fresh installation shows a read once it has gone on for five seconds`() {
        graphQlTester.document("{ installationSettings { readingShownAfterSecondsConfigured } }")
            .execute().path("installationSettings.readingShownAfterSecondsConfigured").entity(Int::class.java).isEqualTo(5)
    }

    @Test
    fun `it is saved within its bounds, zero included, and refused outside them`() {
        graphQlTester.document("mutation { setReadingShownAfterSeconds(seconds: 0) { readingShownAfterSeconds } }")
            .execute().path("setReadingShownAfterSeconds.readingShownAfterSeconds").entity(Int::class.java).isEqualTo(0)
        graphQlTester.document("mutation { setReadingShownAfterSeconds(seconds: 121) { readingShownAfterSeconds } }")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().extensions["code"]).isEqualTo("ReadingShownAfterOutOfRange")
            }
        graphQlTester.document("mutation { setReadingShownAfterSeconds(seconds: 5) { readingShownAfterSeconds } }").execute()
    }
}
