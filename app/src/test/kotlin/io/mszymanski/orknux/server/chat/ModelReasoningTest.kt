package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.workspace.Workspace
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * What a reasoning model thought, read out of each shape this product speaks.
 *
 * There are three ways it arrives and the client used to read none of them:
 *
 *  - **`reasoning_content` in the OpenAI shape.** DeepSeek's field, copied by
 *    vLLM, SGLang and llama.cpp's server; `reasoning` is the same field under
 *    the other spelling some gateways use. Not in OpenAI's own specification,
 *    and OpenAI's own endpoint sends no reasoning text at all - only a token
 *    count - so a model behind it thinks in silence and that is the provider's
 *    decision rather than something this can recover.
 *  - **A `thinking` content block in Anthropic's shape**, beside the text ones.
 *  - **A leading `<think>` block inside `content`**, which is what a local
 *    server passing a chat template through hands back for DeepSeek-R1 and
 *    Qwen3. This was the visible half of the bug: the tags were on screen, in
 *    the copy control and read out by the speech model, because the thinking
 *    was arriving as part of the answer.
 *
 * The one assertion every test here makes is the same one: **the answer does
 * not contain the thinking**. Everything downstream is correct because of that
 * and not because three separate places remember to strip it - the copy
 * control, the speech model and the next turn's prompt all read the answer.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ModelReasoningTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chat: ModelChatClient,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    @BeforeEach
    fun reset() {
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val asked = listOf(ChatTurn(role = "user", content = "Two and two?"))

    @Test
    fun `a streamed reasoning_content is thinking, not answer`() {
        val modelId = openAi(
            serve(
                "/chat/completions",
                """
                data: {"choices":[{"delta":{"reasoning_content":"Two plus two. "}}]}

                data: {"choices":[{"delta":{"reasoning_content":"That is four."}}]}

                data: {"choices":[{"delta":{"content":"Four."}}]}

                data: [DONE]

                """.trimIndent(),
            ),
        )

        val said = mutableListOf<String>()
        val thought = mutableListOf<String>()
        val answer = chat.stream(modelId, asked, onThinking = thought::add, onChunk = said::add)

        // Two frames of thinking, handed over as two frames: the screen draws
        // it as it arrives, the same way it draws the answer.
        assertThat(thought).containsExactly("Two plus two. ", "That is four.")
        assertThat(said).containsExactly("Four.")

        val whole = answer as ChatCompletion.Answered
        assertThat(whole.content).isEqualTo("Four.")
        assertThat(whole.reasoning).isEqualTo("Two plus two. That is four.")
    }

    /**
     * How long it thought, for a provider that sends its reasoning all at once.
     *
     * This is the defect the feature was rejected for the second time, and it
     * is invisible from a stub that pauses between frames. The duration was
     * measured from the first reasoning frame to the last, so a model that
     * emits the whole of its reasoning in a single frame had a first and a last
     * frame in the same instant and reported nought - and the screen, told to
     * draw nothing rather than nought seconds, drew nothing. On the machine it
     * was tested on it read "Thought" with no time beside it at all.
     *
     * Measured from the request going out instead, which is both always
     * non-zero and the more honest number: what somebody waited through is the
     * request, the prompt being read, and then the reasoning - all of it before
     * there was a word of answer on the screen.
     */
    @Test
    fun `reasoning that arrives in one frame still reports how long it took`() {
        val modelId = openAi(
            serve(
                "/chat/completions",
                """
                data: {"choices":[{"delta":{"reasoning_content":"All of it, at once."}}]}

                data: {"choices":[{"delta":{"content":"Four."}}]}

                data: [DONE]

                """.trimIndent(),
            ),
        )

        val answer = chat.stream(modelId, asked, onChunk = {}) as ChatCompletion.Answered

        assertThat(answer.reasoning).isEqualTo("All of it, at once.")
        // The point: a duration the screen can draw, from a single frame.
        assertThat(answer.reasoningMillis).isGreaterThan(0)
    }

    /** And a model that thought nothing still reports no duration to draw. */
    @Test
    fun `a model that does not think reports no duration either`() {
        val modelId = openAi(
            serve(
                "/chat/completions",
                """
                data: {"choices":[{"delta":{"content":"Four."}}]}

                data: [DONE]

                """.trimIndent(),
            ),
        )

        assertThat((chat.stream(modelId, asked, onChunk = {}) as ChatCompletion.Answered).reasoningMillis)
            .isEqualTo(0)
    }

    /** The other spelling, which some gateways send instead. */
    @Test
    fun `the reasoning field is read as well as reasoning_content`() {
        val modelId = openAi(
            serve(
                "/chat/completions",
                """
                data: {"choices":[{"delta":{"reasoning":"Counting."}}]}

                data: {"choices":[{"delta":{"content":"Four."}}]}

                data: [DONE]

                """.trimIndent(),
            ),
        )

        val answer = chat.stream(modelId, asked, onChunk = {}) as ChatCompletion.Answered
        assertThat(answer.reasoning).isEqualTo("Counting.")
        assertThat(answer.content).isEqualTo("Four.")
    }

    /**
     * The local case, and the one that was actually on somebody's screen.
     *
     * The tag is deliberately cut across two frames, because that is what a
     * provider does and it is the half a naive fix gets wrong.
     */
    @Test
    fun `a think block split across frames is pulled out of the answer`() {
        val modelId = openAi(
            serve(
                "/chat/completions",
                """
                data: {"choices":[{"delta":{"content":"<thi"}}]}

                data: {"choices":[{"delta":{"content":"nk>Two plus two."}}]}

                data: {"choices":[{"delta":{"content":"</think>Four."}}]}

                data: [DONE]

                """.trimIndent(),
            ),
        )

        val said = mutableListOf<String>()
        val thought = mutableListOf<String>()
        val answer = chat.stream(modelId, asked, onThinking = thought::add, onChunk = said::add)

        val whole = answer as ChatCompletion.Answered
        assertThat(whole.content).isEqualTo("Four.")
        assertThat(whole.reasoning).isEqualTo("Two plus two.")
        // The thing anybody would actually notice: no tags in the answer.
        assertThat(said.joinToString("")).doesNotContain("think")
        assertThat(thought.joinToString("")).isEqualTo("Two plus two.")
    }

    /** Most models have none, and nothing must pretend otherwise. */
    @Test
    fun `a model that does not think reports no thinking`() {
        val modelId = openAi(
            serve(
                "/chat/completions",
                """
                data: {"choices":[{"delta":{"content":"Four."}}]}

                data: [DONE]

                """.trimIndent(),
            ),
        )

        val thought = mutableListOf<String>()
        val answer = chat.stream(modelId, asked, onThinking = thought::add, onChunk = {})

        assertThat(thought).isEmpty()
        assertThat((answer as ChatCompletion.Answered).reasoning).isEmpty()
    }

    /**
     * A round that thought and then said nothing has not answered.
     *
     * Reported as a provider that produced no message rather than as an answer
     * made of reasoning: showing the model's working under a silence would read
     * as an answer somebody has to interpret.
     */
    @Test
    fun `thinking alone is not an answer`() {
        val modelId = openAi(
            serve(
                "/chat/completions",
                """
                data: {"choices":[{"delta":{"reasoning_content":"Still working."}}]}

                data: [DONE]

                """.trimIndent(),
            ),
        )

        assertThat(chat.stream(modelId, asked, onChunk = {})).isInstanceOf(ChatCompletion.Failed::class.java)
    }

    /** Anthropic puts it in a content block beside the text ones. */
    @Test
    fun `an anthropic thinking block is read and kept out of the answer`() {
        val modelId = anthropic(
            serve(
                "/messages",
                """
                {"content":[
                  {"type":"thinking","thinking":"Two plus two.","signature":"abc"},
                  {"type":"text","text":"Four."}
                ],"usage":{"input_tokens":7,"output_tokens":2}}
                """.trimIndent(),
            ),
        )

        val answer = chat.complete(modelId, asked) as ChatCompletion.Answered
        assertThat(answer.content).isEqualTo("Four.")
        assertThat(answer.reasoning).isEqualTo("Two plus two.")
    }

    /** And the blocking OpenAI shape, which the agent loop uses on every round. */
    @Test
    fun `a blocking answer carries its reasoning separately`() {
        val modelId = openAi(
            serve(
                "/chat/completions",
                """
                {"choices":[{"message":{"role":"assistant",
                  "reasoning_content":"Two plus two.","content":"Four."}}],
                 "usage":{"prompt_tokens":7,"completion_tokens":2}}
                """.trimIndent(),
            ),
        )

        val answer = chat.complete(modelId, asked) as ChatCompletion.Answered
        assertThat(answer.content).isEqualTo("Four.")
        assertThat(answer.reasoning).isEqualTo("Two plus two.")
    }

    /**
     * A stub provider that answers one thing, whatever it is asked.
     *
     * The path matters: the two shapes are called at different ones, and a
     * context registered for the wrong one answers 404 rather than the body.
     */
    private fun serve(path: String, body: String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(path) { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            // Labelled as what it is, as a provider labels it: since 5f4e1d5a the
            // client reads a body that is not an event stream as one whole answer.
            exchange.responseHeaders.add("Content-Type", if (body.startsWith("data:")) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun openAi(endpoint: String) = model(provider(endpoint, "OPENAI"))

    private fun anthropic(endpoint: String) = model(provider(endpoint, "ANTHROPIC"))

    private fun provider(endpoint: String, type: String): Long = graphQlTester.document(
        """mutation { createModelProvider(input: {
             workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint",
             type: $type, secret: "sk-test"
           }) { id } }""",
    ).execute().path("createModelProvider.id").entity(Long::class.java).get()

    private fun model(providerId: Long): Long = graphQlTester.document(
        """mutation { createModel(input: {
             providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT
           }) { id } }""",
    ).execute().path("createModel.id").entity(Long::class.java).get()
}
