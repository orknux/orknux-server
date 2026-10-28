package io.mszymanski.orknux.server.action

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.chat.ChatSentences
import io.mszymanski.orknux.server.mcp.OrknuxScope
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
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The function editor's chat and its inline suggestion.
 *
 * Pinned: a model asked in the editor reads the code it was sent - saved or not
 * - and offers a rewrite that comes back beside the answer and is saved
 * nowhere; it is offered the rewrite and reading and nothing that writes; the
 * briefing names only what it is offered; and the completion model's code
 * comes back without its fence, without the line it repeated, and only where
 * a completion model is chosen.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class FunctionChatTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val chat: FunctionChat,
    @Autowired val sentences: ChatSentences,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "function-chat-${System.nanoTime()}")).id)
        received.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `a model asked in the editor offers a rewrite of the code it was sent, and nothing is saved`() {
        val id = graphQlTester.document("""mutation { createFunction(input: { workspaceId: $workspaceId, name: "double" }) { id } }""")
            .execute().path("createFunction.id").entity(Long::class.java).get()
        val stored = functions.findById(id).get().source
        val rewrite = "export default function double(n: number): number {\n  return n * 2;\n}"
        val modelId = model(
            serve {
                if (received.size == 1) {
                    val arguments = mapper.writeValueAsString(mapOf("code" to rewrite, "note" to "Doubles it"))
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                       {"id":"call_1","type":"function","function":{"name":"${FunctionChat.SUGGEST}","arguments":${mapper.writeValueAsString(arguments)}}}
                     ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                } else {
                    """{"choices":[{"message":{"role":"assistant","content":"It doubles now."}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                }
            },
        )

        graphQlTester.document(
            """mutation(${'$'}code: String!) { askFunction(workspaceId: $workspaceId, functionId: $id, code: ${'$'}code, modelId: $modelId,
                 messages: [{role: "user", content: "Make it double"}]) { answer suggestion { code note } } }""",
        ).variable("code", "// what is in the editor, unsaved").execute()
            .path("askFunction.answer").entity(String::class.java).isEqualTo("It doubles now.")
            .path("askFunction.suggestion.code").entity(String::class.java).isEqualTo(rewrite)
            .path("askFunction.suggestion.note").entity(String::class.java).isEqualTo("Doubles it")

        // Shown the code in the editor, offered the rewrite and reading, and nothing that writes.
        assertThat(received.first()).contains("what is in the editor, unsaved", FunctionChat.SUGGEST, "orknux_function")
            .doesNotContain("orknux_run_workflow", "orknux_suggest_function_code", "orknux_create_agent")
        assertThat(functions.findById(id).get().source).isEqualTo(stored)
    }

    @Test
    fun `a function nobody has saved yet can be asked about, and one from elsewhere cannot`() {
        val modelId = model(serve { """{"choices":[{"message":{"role":"assistant","content":"Start with a declaration."}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""" })
        graphQlTester.document(
            """mutation { askFunction(workspaceId: $workspaceId, name: "fresh", code: "", modelId: $modelId,
                 messages: [{role: "user", content: "Where do I start?"}]) { answer suggestion { code } } }""",
        ).execute()
            .path("askFunction.answer").entity(String::class.java).isEqualTo("Start with a declaration.")
            .path("askFunction.suggestion").valueIsNull()
        assertThat(received.single()).contains("fresh", "has not been saved yet")

        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "elsewhere-${System.nanoTime()}")).id)
        val theirs = graphQlTester.document("""mutation { createFunction(input: { workspaceId: $elsewhere, name: "theirs" }) { id } }""")
            .execute().path("createFunction.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { askFunction(workspaceId: $workspaceId, functionId: $theirs, code: "", modelId: $modelId,
                 messages: [{role: "user", content: "Read it"}]) { answer } }""",
        ).execute().errors().expect { it.message == "No function with id $theirs" }.verify()
    }

    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `somebody who cannot see the workspace is refused`() {
        graphQlTester.document(
            """mutation { askFunction(workspaceId: $workspaceId, code: "", modelId: 1, messages: [{role: "user", content: "Hi"}]) { answer } }""",
        ).execute().errors().expect { true }.verify()
        graphQlTester.document("""{ functionCompletion(workspaceId: $workspaceId, before: "const x =") }""")
            .execute().errors().expect { true }.verify()
    }

    /** The briefing names every tool it mentions off what is offered. */
    @Test
    fun `the chat names only the tools it is offered`() {
        val offered = chat.offered(OrknuxScope(workspaceId = workspaceId))
        assertThat(offered.map { it.name }).containsExactlyInAnyOrderElementsOf(FunctionChat.READING + FunctionChat.SUGGEST)
        val briefing = chat.briefing("double", 1, "code", offered)
        offered.forEach { assertThat(briefing).contains("`${it.name}`") }
        assertThat(briefing).contains("never link to it")
    }

    @Test
    fun `the editor's suggestion comes from the completion model, as code without its fence or the line it repeated`() {
        fun ask() = graphQlTester.document(
            """{ functionCompletion(workspaceId: $workspaceId, name: "total", before: "function total(xs: number[]) {\n  const sum = ", after: "\n}") }""",
        ).execute().errors().verify().path("functionCompletion")

        val modelId = model(
            serve {
                """{"choices":[{"message":{"role":"assistant","content":"```typescript\nconst sum = xs.reduce((a, b) => a + b, 0);\n  return sum;\n```"}}],"usage":{"prompt_tokens":4,"completion_tokens":3}}"""
            },
        )
        // No completion model chosen: nothing is asked and nothing suggested.
        ask().valueIsNull()
        assertThat(received).isEmpty()

        graphQlTester.document("""mutation { setWorkspaceCompletionModel(workspaceId: $workspaceId, modelId: $modelId) { completionModelId } }""")
            .execute().errors().verify()
        ask().entity(String::class.java).isEqualTo("xs.reduce((a, b) => a + b, 0);\n  return sum;")
        assertThat(received.single()).contains("total", "const sum = ", "TypeScript")
    }

    @Test
    fun `code from the model keeps its indentation and is held to a few lines`() {
        assertThat(sentences.codeCleaned("if (x) {\n", "    return 1;\n}")).isEqualTo("    return 1;\n}")
        assertThat(sentences.codeCleaned("x", (1..20).joinToString("\n") { "line$it" })!!.lines()).hasSize(8)
        assertThat(sentences.codeCleaned("x", "  \n ")).isNull()
    }

    private fun serve(answer: (String) -> String): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val bytes = answer(body).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        started.start()
        server = started
        return "http://${started.address.hostString}:${started.address.port}"
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub ${System.nanoTime()}", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
