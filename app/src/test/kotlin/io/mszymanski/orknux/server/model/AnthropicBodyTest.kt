package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What `anthropicBody` actually puts on the wire.
 *
 * Every test here asserts the bytes of the request rather than that the call
 * succeeded, because succeeding was never what was in doubt. The stub records
 * the request body and the assertions are about what is in it.
 *
 * **Pictures**, which is where this began. `openAiBody` carried a turn's images
 * as `image_url` parts and `anthropicBody` had no branch for them at all, so a
 * turn with a picture reached an Anthropic provider as words alone. Nothing
 * failed and nothing was logged - the model simply answered, plausibly and at
 * length, about something it had never been shown. An agent whose entire
 * purpose was reading a screenshot appeared to work.
 *
 * **Alternation**, which fails the opposite way. Anthropic rejects two messages
 * of one role in a row, so a run of them is a 400 and no answer at all. The
 * shapes that produce one are ordinary - an agent that said something on its
 * way to a lookup and then answered, a round that threaded back three tool
 * results - and they are joined here rather than anywhere upstream, for the
 * reasons `anthropicBody` gives.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AnthropicBodyTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val chat: ModelChatClient,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    /** A one-pixel PNG, so the data is real base64 rather than a word. */
    private val png = "data:image/png;base64," +
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private var workspaceId: Long = 0
    private lateinit var provider: HttpServer

    /** Every request body the stub was sent. */
    private val sent = CopyOnWriteArrayList<String>()

    /** What the stub answers with; a test that needs other counts says so. */
    private var reply = """{"content":[{"type":"text","text":"A picture of nothing much."}],
                "usage":{"input_tokens":11,"output_tokens":7}}"""

    @BeforeEach
    fun start() {
        sent.clear()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)

        provider = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        // Not stable-diffusion.cpp's server: an OpenAI provider saved here is asked, and must stay OpenAI.
        provider.createContext("/sdcpp") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        provider.createContext("/") { exchange ->
            sent += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val body = reply.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        provider.start()
    }

    @AfterEach
    fun stop() = provider.stop(0)

    @Test
    fun `a picture reaches an Anthropic model as an image block`() {
        val modelId = anthropicModel()

        val answer = chat.complete(
            modelId,
            listOf(ChatTurn(role = "user", content = "What is this?", images = listOf(png))),
        )

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat(sent).hasSize(1)

        val content = mapper.readTree(sent.single()).path("messages").first().path("content")
        assertThat(content.isArray).describedAs("a turn with a picture is blocks, not a string").isTrue()

        // The words are still there, and they come first: a picture with the
        // question after it reads as a different question.
        assertThat(content.first().path("type").asString()).isEqualTo("text")
        assertThat(content.first().path("text").asString()).isEqualTo("What is this?")

        val image = content.last()
        assertThat(image.path("type").asString()).isEqualTo("image")
        assertThat(image.path("source").path("type").asString()).isEqualTo("base64")
        // Taken apart rather than passed through: Anthropic does not read a
        // data: URL, which is exactly why the OpenAI shape could not be reused.
        assertThat(image.path("source").path("media_type").asString()).isEqualTo("image/png")
        assertThat(image.path("source").path("data").asString())
            .isEqualTo(png.substringAfter("base64,"))
    }

    /**
     * The refusal, which is the other half of the fix.
     *
     * A picture that cannot be carried must not be quietly left behind - that is
     * the whole defect, in miniature. So an unusable one fails with a sentence,
     * and the request is never made: a model that is not going to see the
     * picture should not be asked about it at all.
     */
    @Test
    fun `a picture that cannot be carried is refused rather than dropped`() {
        val modelId = anthropicModel()

        val answer = chat.complete(
            modelId,
            listOf(ChatTurn(role = "user", content = "What is this?", images = listOf("data:image/tiff;base64,AAAA"))),
        )

        assertThat(answer).isInstanceOf(ChatCompletion.Failed::class.java)
        assertThat((answer as ChatCompletion.Failed).reason)
            .contains("image/tiff")
            .contains("image/png")
        assertThat(sent).describedAs("nothing should have been sent").isEmpty()
    }

    /**
     * And the shape everything else speaks is untouched.
     *
     * The two bodies are the product's claim that a provider is interchangeable.
     * Fixing one of them by changing what the other sends would trade this bug
     * for a wider one, so the OpenAI shape is asserted here beside it.
     */
    @Test
    fun `the OpenAI shape still sends a picture as an image_url part`() {
        val modelId = openAiModel()

        chat.complete(
            modelId,
            listOf(ChatTurn(role = "user", content = "What is this?", images = listOf(png))),
        )

        val content = mapper.readTree(sent.single()).path("messages").first().path("content")
        val image = content.last()
        assertThat(image.path("type").asString()).isEqualTo("image_url")
        assertThat(image.path("image_url").path("url").asString()).isEqualTo(png)
    }

    /**
     * The shape a session now hands back, and the one that would be refused.
     *
     * An agent may answer with a message and tool calls in the same reply, so
     * what it said on the way to a lookup is recorded beside what it finally
     * answered. Read back as memory those are two assistant turns with nothing
     * between them - which Anthropic rejects outright, and which every other
     * provider accepts, so the joining belongs to this body and not to the
     * session that keeps them apart.
     *
     * Two parts rather than one string: two things the agent said are two
     * things, and gluing them together would put a sentence in its mouth.
     */
    @Test
    fun `two assistant turns in a row become one message of two parts`() {
        val modelId = anthropicModel()

        chat.complete(
            modelId,
            listOf(
                ChatTurn(role = "user", content = "What does the review skill say?"),
                ChatTurn(role = "assistant", content = "Let me read the review skill first."),
                ChatTurn(role = "assistant", content = "Read the diff twice."),
                ChatTurn(role = "user", content = "Are you sure?"),
            ),
        )

        val messages = mapper.readTree(sent.single()).path("messages")
        assertThat(messages.size()).describedAs("the two must not be sent as two").isEqualTo(3)
        assertThat(roles(messages)).containsExactly("user", "assistant", "user")

        val said = messages[1].path("content")
        assertThat(said.isArray).isTrue()
        assertThat(said.size()).isEqualTo(2)
        assertThat(each(said) { it.path("type").asString() }).containsOnly("text")
        // In the order they were said in, which is the order the round had them.
        assertThat(said[0].path("text").asString()).isEqualTo("Let me read the review skill first.")
        assertThat(said[1].path("text").asString()).isEqualTo("Read the diff twice.")
    }

    /**
     * And the results of one round arrive as one turn, which was already true
     * of the round and never of the request.
     *
     * A round that called three tools threads three results back as three
     * turns, all of them user turns by the time they reach here. That predates
     * anything about what an agent says beside a call; it is the same wire rule
     * and the same join.
     */
    @Test
    fun `several tool results in a row become one message of several parts`() {
        val modelId = anthropicModel()

        chat.complete(
            modelId,
            listOf(
                ChatTurn(role = "user", content = "Which builds failed?"),
                ChatTurn(role = "assistant", content = "", asked = listOf(ToolCall("call_1", "builds", "{}"))),
                ChatTurn(role = "user", content = "build 12 failed", respondingTo = "call_1"),
                ChatTurn(role = "user", content = "build 13 failed", respondingTo = "call_2"),
            ),
        )

        val messages = mapper.readTree(sent.single()).path("messages")
        assertThat(roles(messages)).containsExactly("user", "assistant", "user")

        val results = messages[2].path("content")
        assertThat(results.size()).isEqualTo(2)
        assertThat(each(results) { it.path("type").asString() }).containsOnly("tool_result")
        assertThat(each(results) { it.path("tool_use_id").asString() }).containsExactly("call_1", "call_2")
        assertThat(each(results) { it.path("content").asString() })
            .containsExactly("build 12 failed", "build 13 failed")
    }

    /**
     * And an ordinary conversation is sent exactly as it was before.
     *
     * The join must be invisible where there is nothing to join. A turn of
     * plain words stays a plain string rather than becoming a one-item array,
     * because that is what this sent before any of it existed and a request
     * that changed shape for every caller would be a wider change than the bug.
     */
    @Test
    fun `alternating turns are sent one for one, as plain strings`() {
        val modelId = anthropicModel()

        chat.complete(
            modelId,
            listOf(
                ChatTurn(role = "user", content = "Why did it fall over?"),
                ChatTurn(role = "assistant", content = "The pool was exhausted."),
                ChatTurn(role = "user", content = "So what do we do?"),
            ),
        )

        val messages = mapper.readTree(sent.single()).path("messages")
        assertThat(messages.size()).isEqualTo(3)
        assertThat(roles(messages)).containsExactly("user", "assistant", "user")
        assertThat(each(messages) { it.path("content").isTextual })
            .describedAs("plain words stay a string").containsOnly(true)
        assertThat(messages[1].path("content").asString()).isEqualTo("The pool was exhausted.")
    }

    /**
     * Every element of a JSON array, read out.
     *
     * Indexed rather than iterated because a `JsonNode` is not the `Iterable`
     * Kotlin's `map` wants, and the assertions here are all about a sequence.
     */
    private fun <T> each(array: JsonNode, of: (JsonNode) -> T): List<T> =
        (0 until array.size()).map { of(array[it]) }

    private fun roles(messages: JsonNode): List<String> = each(messages) { it.path("role").asString() }

    private fun anthropicModel(): Long = modelOn(newProvider("Anthropic", "ANTHROPIC"))

    private fun openAiModel(): Long = modelOn(newProvider("OpenAI", "OPENAI"))

    private fun newProvider(name: String, type: String): Long = graphQlTester.document(
        """mutation { createModelProvider(input: {
             workspaceId: $workspaceId, name: "$name", endpoint: "$endpoint", type: $type, secret: "sk-test"
           }) { id } }""",
    ).execute().path("createModelProvider.id").entity(Long::class.java).get()

    private fun modelOn(providerId: Long): Long = graphQlTester.document(
        """mutation { createModel(input: {
             providerId: $providerId, name: "Sees pictures", modelId: "a-model", kind: CHAT
           }) { id } }""",
    ).execute().path("createModel.id").entity(Long::class.java).get()

    private val endpoint: String
        get() = "http://${provider.address.hostString}:${provider.address.port}"

    /**
     * What Anthropic says it read from and wrote into its prompt cache, which
     * its `input_tokens` leaves out: added back, so the input is every token
     * read as OpenAI counts it, and kept apart so each is charged at its price.
     */
    @Test
    fun `an Anthropic answer's cache reads and writes are counted`() {
        reply = """{"content":[{"type":"text","text":"Hello."}],
            "usage":{"input_tokens":11,"output_tokens":7,"cache_read_input_tokens":300,"cache_creation_input_tokens":50}}"""
        val answer = chat.complete(anthropicModel(), listOf(ChatTurn(role = "user", content = "Hi")))

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        answer as ChatCompletion.Answered
        assertThat(answer.inputTokens).isEqualTo(361)
        assertThat(answer.cachedInputTokens).isEqualTo(300)
        assertThat(answer.cacheWriteTokens).isEqualTo(50)
        assertThat(usage.findAll().single().cacheWriteTokens).isEqualTo(50)
    }
}
