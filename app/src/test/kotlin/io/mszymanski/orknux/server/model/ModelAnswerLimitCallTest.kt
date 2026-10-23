package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.ModelCallLimits
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration

/**
 * An Anthropic model is held to the same answer time limit as every other.
 *
 * It was not: the OpenAI-shaped providers went through the SDK, which read the
 * installation's Model Answer Time Limit, and Anthropic through a request of
 * its own, which read `ORKNUX_MODEL_TIMEOUT` - two minutes, whatever Admin
 * Settings said. A server that never answers is the honest stand-in, and the
 * installation's limit is two seconds here so the test does not wait out a
 * minute. Issue #644.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
@Import(ModelAnswerLimitCallTest.TwoSeconds::class)
class ModelAnswerLimitCallTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chat: ModelChatClient,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val limits: ModelCallLimits,
) {

    @TestConfiguration
    class TwoSeconds {
        @Bean
        @Primary
        fun twoSeconds(): ModelCallLimits = ModelCallLimits { Duration.ofSeconds(2) }
    }

    private lateinit var silent: HttpServer

    @BeforeEach
    fun start() {
        silent = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        silent.createContext("/") { Thread.sleep(30_000) }
        silent.start()
    }

    @AfterEach
    fun stop() = silent.stop(0)

    @Test
    fun `an Anthropic call is given up on at the installation's limit`() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "limit ${System.nanoTime()}")).id)
        val endpoint = "http://${silent.address.hostString}:${silent.address.port}/v1"
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Anthropic", endpoint: "$endpoint", type: ANTHROPIC, secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val modelId = graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Claude", modelId: "claude-test", kind: CHAT
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()

        val started = System.nanoTime()
        val answer = chat.complete(modelId, listOf(ChatTurn(role = "user", content = "Hi")))

        assertThat(answer).isInstanceOf(ChatCompletion.Failed::class.java)
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20))
    }

    @Test
    fun `a model's own limit wins over the installation's, held to the range`() {
        val model = LlmModel(providerId = 1, name = "Model", modelId = "m")
        assertThat(limits.answerFor(model)).isEqualTo(Duration.ofSeconds(2))
        model.answerLimitMinutes = 15
        assertThat(limits.answerFor(model)).isEqualTo(Duration.ofMinutes(15))
        // A file imported with a number the form would have refused.
        model.answerLimitMinutes = 100_000
        assertThat(limits.answerFor(model)).isEqualTo(Duration.ofMinutes(ModelCallLimits.MOST_MINUTES.toLong()))
    }
}
