package io.mszymanski.orknux.server.model

import io.mszymanski.orknux.connector.model.ModelCallLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.Duration

/**
 * How long a model may take to answer is an Admin setting. It was the OpenAI
 * SDK's ten minutes, never chosen, which cut a thinking model off mid-thought.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ModelAnswerLimitTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val limits: ModelCallLimits,
) {

    @Test
    fun `a fresh installation lets a model take an hour`() {
        graphQlTester.document("{ installationSettings { modelAnswerLimitMinutes modelAnswerLimitMinutesConfigured } }")
            .execute().also {
                it.path("installationSettings.modelAnswerLimitMinutes").entity(Int::class.java).isEqualTo(60)
                it.path("installationSettings.modelAnswerLimitMinutesConfigured").entity(Int::class.java).isEqualTo(60)
            }
        assertThat(limits.answer()).isEqualTo(Duration.ofMinutes(60))
    }

    @Test
    fun `it is saved within its bounds, reaches the model clients, and is refused outside them`() {
        graphQlTester.document("mutation { setModelAnswerLimitMinutes(minutes: 90) { modelAnswerLimitMinutes } }")
            .execute().path("setModelAnswerLimitMinutes.modelAnswerLimitMinutes").entity(Int::class.java).isEqualTo(90)
        assertThat(limits.answer()).isEqualTo(Duration.ofMinutes(90))
        listOf(0, 1441).forEach { minutes ->
            graphQlTester.document("mutation { setModelAnswerLimitMinutes(minutes: $minutes) { modelAnswerLimitMinutes } }")
                .execute().errors().satisfy { errors ->
                    assertThat(errors.single().extensions["code"]).isEqualTo("ModelAnswerLimitOutOfRange")
                }
        }
        graphQlTester.document("mutation { setModelAnswerLimitMinutes(minutes: 60) { modelAnswerLimitMinutes } }").execute()
    }
}
