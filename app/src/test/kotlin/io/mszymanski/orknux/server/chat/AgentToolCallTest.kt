package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
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
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An agent using its tools before it answers.
 *
 * The stub here is a model that asks for a skill on its first round and answers
 * on its second, which is the whole shape of tool calling. What is worth
 * checking is that the loop closes: the call is run, its result is threaded back
 * in the shape the provider expects, and the second round sees it.
 *
 * A real provider would be the only other way to test this, and it would test
 * the provider rather than this code.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentToolCallTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
    @Autowired val agents: AgentRepository,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Every request body the stub was sent, so the second can be inspected. */
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        sessions.deleteAll()
        agents.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        /*
         * With hiding built-ins allowed: this class is about what the round
         * withholds, and since #482 a built-in is offered to every agent unless
         * the workspace has said it will take the risk of taking one away. The
         * gate itself is pinned in BuiltInToolsTest.
         */
        workspaceId = requireNotNull(
            workspaces.save(Workspace(name = "backend", unsafeBuiltInTools = true)).id,
        )
        received.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `an agent loads a skill and answers with what it read`() {
        val endpoint = serveToolThenAnswer()
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agentGranted("Reviewer", model(endpoint), "Reviews")

        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        val answer = conversation.answer(
            requireNotNull(agent.modelId),
            agent,
            listOf(ChatTurn("user", "How should I review this?")),
        )

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat((answer as ChatCompletion.Answered).content).isEqualTo("Read the diff twice.")

        // Two rounds: the ask, then the answer.
        assertThat(received).hasSize(2)
        // The first offered the tools this agent has, and only those — no memory
        // tool, because it was granted no catalogs.
        assertThat(received[0]).contains("skill_list").contains("skill_load")
        assertThat(received[0]).doesNotContain("memory_search")
        // The second carried the call and the result the tool produced, which is
        // what makes it a loop rather than two unrelated requests.
        assertThat(received[1]).contains("tool_call_id")
        assertThat(received[1]).contains("Read the diff twice before commenting.")
    }

    /**
     * An agent with no grants is offered nothing of the workspace's.
     *
     * Not tools that answer "nothing here": that is a round trip spent learning
     * what the grant already said, and the model pays for it. What it still
     * holds is what reaches nothing at all - since #416, reading a document to
     * say whether it parses - because withholding arithmetic on a string buys
     * nobody anything.
     */
    @Test
    fun `an agent granted nothing is handed only what reaches nothing`() {
        val endpoint = serveToolThenAnswer()
        val agentId = agentGranted("Plain", model(endpoint), granted = null)

        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        conversation.answer(requireNotNull(agent.modelId), agent, listOf(ChatTurn("user", "Hello")))

        /*
         * The round count is not the assertion: this stub asks for a skill
         * whatever it is offered, so what matters is what it was offered.
         */
        // The skill tools are built-ins like the rest: every agent holds the
        // server's own skills. Memory still comes with a catalog.
        assertThat(received[0]).contains("skill_list").doesNotContain("memory_search")
        assertThat(received[0]).doesNotContain("scratchpad_write").doesNotContain("ask_agent")
        assertThat(received[0]).contains("validate_format")
    }

    /**
     * A model that never stops asking is stopped, and says so.
     *
     * Left alone it would call tools until the request timed out, billing every
     * round; the run has to end somewhere and the reason has to be legible.
     */
    @Test
    fun `an agent that only ever calls tools is stopped and says so`() {
        val endpoint = serveAlwaysCallingTools()
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice.")
        val agentId = agentGranted("Looper", model(endpoint), "Reviews")

        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        val answer = conversation.answer(
            requireNotNull(agent.modelId),
            agent,
            listOf(ChatTurn("user", "How should I review this?")),
        )

        assertThat(answer).isInstanceOf(ChatCompletion.Failed::class.java)
        assertThat((answer as ChatCompletion.Failed).reason).contains("without reaching an answer")
    }

    /** Asks for `skill_load` first, then answers with what the result held. */
    private fun serveToolThenAnswer(): String = serve { body ->
        if (body.contains("tool_call_id")) {
            """{"choices":[{"message":{"role":"assistant","content":"Read the diff twice."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function",
               "function":{"name":"skill_load","arguments":"{\"name\":\"codeReview\"}"}}
            ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
            """.trimIndent()
        }
    }

    /**
     * The production failure this path exists for: an Azure reasoning model
     * with an effort and tools, which chat completions refuse outright.
     *
     * Asked through the Responses API, a tool loop has to carry itself: nothing
     * is stored at the provider, so the second round must hand back the
     * model's encrypted reasoning, the call it made and the call's output -
     * paired by `call_id` - or the model starts over without knowing it asked.
     */
    @Test
    fun `an Azure reasoning agent loops through the Responses API, carrying its reasoning and calls back`() {
        val endpoint = serve("/openai/v1/responses") { body ->
            if (body.contains("function_call_output")) {
                """{"id":"resp_2","object":"response","created_at":1,"model":"gpt-6-sol","status":"completed",
                   "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                     "content":[{"type":"output_text","text":"Read the diff twice.","annotations":[]}]}],
                   "usage":{"input_tokens":9,"output_tokens":4,"total_tokens":13}}"""
            } else {
                """{"id":"resp_1","object":"response","created_at":1,"model":"gpt-6-sol","status":"completed",
                   "output":[
                     {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"The skill will say."}],
                      "encrypted_content":"sealed-thought"},
                     {"type":"function_call","id":"fc_1","call_id":"call_1","name":"skill_load",
                      "arguments":"{\"name\":\"codeReview\"}","status":"completed"}],
                   "usage":{"input_tokens":7,"output_tokens":2,"total_tokens":9}}"""
            }
        }
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agentGranted("Reviewer", azureReasoningModel(endpoint), "Reviews")

        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        val answer = conversation.answer(
            requireNotNull(agent.modelId),
            agent,
            listOf(ChatTurn("user", "How should I review this?")),
        )

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat((answer as ChatCompletion.Answered).content).isEqualTo("Read the diff twice.")
        assertThat(received).hasSize(2)

        val mapper = tools.jackson.databind.ObjectMapper()
        val first = mapper.readTree(received[0])
        // Its effort, a summary to show as thinking, and the reasoning sealed for the next round.
        assertThat(first.path("reasoning").path("effort").asString()).isEqualTo("high")
        assertThat(first.path("reasoning").path("summary").asString()).isEqualTo("auto")
        assertThat(first.path("include").toList().map { it.asString() }).contains("reasoning.encrypted_content")
        assertThat(first.path("store").asBoolean(true)).isFalse()
        assertThat(first.path("tools").toList().map { it.path("name").asString() }).contains("skill_load")

        val input = mapper.readTree(received[1]).path("input").toList()
        val types = input.map { it.path("type").asString("message") }
        // The reasoning in front of the call it led to, then the call's output.
        assertThat(types.takeLast(3)).containsExactly("reasoning", "function_call", "function_call_output")
        val reasoning = input.single { it.path("type").asString() == "reasoning" }
        assertThat(reasoning.path("encrypted_content").asString()).isEqualTo("sealed-thought")
        val call = input.single { it.path("type").asString() == "function_call" }
        val output = input.single { it.path("type").asString() == "function_call_output" }
        assertThat(call.path("call_id").asString()).isEqualTo("call_1")
        assertThat(call.path("name").asString()).isEqualTo("skill_load")
        // Sent back without its item id: that id is what ties a call to its reasoning item.
        assertThat(call.has("id")).isFalse()
        assertThat(output.path("call_id").asString()).isEqualTo("call_1")
        assertThat(output.path("output").asString()).contains("Read the diff twice before commenting.")
    }

    /**
     * Session 501's shape: three calls, round and round, 279 in one message.
     * Issue #518.
     *
     * Identical calls collapse before the cap, so three run and nothing is
     * refused. Every repeat still gets an answer - a provider refuses a request
     * with a call left unanswered - but the answer points at the call that ran
     * rather than carrying its result again, which is what put the same skill
     * page back into 501's context once per repeat.
     */
    @Test
    fun `a message repeating three calls runs three, and the repeats point at them`() {
        val endpoint = serve { body ->
            if (body.contains("tool_call_id")) {
                """{"choices":[{"message":{"role":"assistant","content":"Done."}}],
                   "usage":{"prompt_tokens":9,"completion_tokens":1}}"""
            } else {
                val calls = (1..279).joinToString(",") { n ->
                    val (name, args) = when (n % 3) {
                        1 -> "skill_load" to "{\\\"name\\\":\\\"codeReview\\\"}"
                        2 -> "skill_list" to "{}"
                        else -> "current_time" to "{}"
                    }
                    """{"id":"call_$n","type":"function","function":{"name":"$name","arguments":"$args"}}"""
                }
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[$calls]}}],
                   "usage":{"prompt_tokens":7,"completion_tokens":2}}"""
            }
        }
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agentGranted("Reviewer", model(endpoint), "Reviews")
        val agent = requireNotNull(agents.findByIdOrNull(agentId))

        val answer = conversation.answer(requireNotNull(agent.modelId), agent, listOf(ChatTurn("user", "hi")))

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        val second = received[1]
        // Every one of the 279 call ids is answered.
        assertThat(Regex("\"tool_call_id\"").findAll(second).count()).isEqualTo(279)
        // The skill page went back once, under the call that ran - not per repeat.
        assertThat(Regex("Read the diff twice before commenting").findAll(second).count()).isEqualTo(1)
        // The repeats point at a call rather than repeating a result.
        assertThat(Regex("duplicateOfCall").findAll(second).count()).isEqualTo(276)
        // And three distinct calls is under any cap: nothing refused.
        assertThat(second).doesNotContain("This call was not run: it repeats")
            .doesNotContain("different tool calls at once and the first")
    }

    /**
     * A provider that sends nothing back once is asked again, not given up on.
     * Issue #527: the turn used to end with "could not answer" in the middle of
     * work that had gone fine.
     */
    @Test
    fun `an empty answer is asked for again`() {
        var asked = 0
        val endpoint = serve {
            asked += 1
            if (asked == 1) {
                """{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":0}}"""
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"Here now."}}],
                   "usage":{"prompt_tokens":1,"completion_tokens":2}}"""
            }
        }
        val agentId = agentGranted("Plain", model(endpoint))
        val agent = requireNotNull(agents.findByIdOrNull(agentId))

        val answer = conversation.answer(requireNotNull(agent.modelId), agent, listOf(ChatTurn("user", "hi")))

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat((answer as ChatCompletion.Answered).content).isEqualTo("Here now.")
        // Asked again as it was: the retry carries nothing the first did not.
        assertThat(received).hasSize(2)
        assertThat(received[1]).isEqualTo(received[0])
    }

    /**
     * Session 503: the message ran out of room in the middle of its last call.
     * Issue #528. That call was echoed back cut off, the provider refused every
     * request after it, and the turn died. It goes back as `{}` now, is not run,
     * and is answered as not run.
     */
    @Test
    fun `a call cut off mid-arguments is sent back whole and not run`() {
        val endpoint = serve { body ->
            if (body.contains("tool_call_id")) {
                """{"choices":[{"message":{"role":"assistant","content":"Done."}}],
                   "usage":{"prompt_tokens":9,"completion_tokens":1}}"""
            } else {
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"skill_list","arguments":"{}"}},
                  {"id":"call_2","type":"function","function":{"name":"skill_load","arguments":"{\"name\":\"posting-to-sla"}}
                ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}"""
            }
        }
        val agentId = agentGranted("Reviewer", model(endpoint), "Reviews")
        catalog("Reviews")
        val agent = requireNotNull(agents.findByIdOrNull(agentId))

        val answer = conversation.answer(requireNotNull(agent.modelId), agent, listOf(ChatTurn("user", "hi")))

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        val second = received[1]
        // The broken text is not echoed back; the call goes back as an empty object.
        assertThat(second).doesNotContain("posting-to-sla")
        // Both calls are answered, and the cut one says it was not run.
        assertThat(Regex("\"tool_call_id\"").findAll(second).count()).isEqualTo(2)
        assertThat(second).contains("its arguments were cut off")
    }

    /**
     * The model's thinking goes back with its calls. Issue #532: it never did,
     * so round two saw round one as a bare call, and the model stopped thinking
     * and started its plan over.
     */
    @Test
    fun `what the model thought before its calls is sent back with them`() {
        val endpoint = serve { body ->
            if (body.contains("tool_call_id")) {
                """{"choices":[{"message":{"role":"assistant","content":"Done."}}],
                   "usage":{"prompt_tokens":9,"completion_tokens":1}}"""
            } else {
                """{"choices":[{"message":{"role":"assistant","content":null,
                     "reasoning_content":"I need the review rules first.",
                     "tool_calls":[{"id":"call_1","type":"function",
                       "function":{"name":"skill_list","arguments":"{}"}}]}}],
                   "usage":{"prompt_tokens":7,"completion_tokens":2}}"""
            }
        }
        val agentId = agentGranted("Reviewer", model(endpoint), "Reviews")
        catalog("Reviews")
        val agent = requireNotNull(agents.findByIdOrNull(agentId))

        conversation.answer(requireNotNull(agent.modelId), agent, listOf(ChatTurn("user", "hi")))

        assertThat(received[1]).contains("reasoning_content").contains("I need the review rules first.")
        // And not invented where the model said nothing.
        assertThat(received[0]).doesNotContain("reasoning_content")
    }

    /**
     * A skill read earlier in the turn is pointed at, not read again. Issue
     * #531: session 509 reloaded one skill thirty-two times, each copy putting
     * the whole page back into the prompt.
     */
    @Test
    fun `a skill loaded twice in one turn is sent once`() {
        var round = 0
        val endpoint = serve {
            round += 1
            if (round <= 2) {
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_$round","type":"function",
                   "function":{"name":"skill_load","arguments":"{\"name\":\"codeReview\"}"}}
                ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}"""
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"Done."}}],
                   "usage":{"prompt_tokens":9,"completion_tokens":1}}"""
            }
        }
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agentGranted("Reviewer", model(endpoint), "Reviews")
        val agent = requireNotNull(agents.findByIdOrNull(agentId))

        conversation.answer(requireNotNull(agent.modelId), agent, listOf(ChatTurn("user", "hi")))

        val third = received[2]
        assertThat(Regex("Read the diff twice before commenting").findAll(third).count()).isEqualTo(1)
        assertThat(third).contains("alreadyLoaded").contains("call_1")
    }

    private fun serveAlwaysCallingTools(): String = serve {
        """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
          {"id":"call_n","type":"function","function":{"name":"skill_list","arguments":"{}"}}
        ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
        """.trimIndent()
    }

    private fun serve(path: String = "/chat/completions", answer: (String) -> String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(path) { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val bytes = answer(body).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun agentGranted(name: String, modelId: Long, granted: String? = null): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        /*
         * And the server's own skills are cleared where this wants nothing.
         * Issue #471 grants that catalog to every new agent, which hands it
         * skill_list and skill_load - right for a real agent, and the opposite
         * of what a fixture for "granted nothing" is asking for.
         */
        val grant = if (granted == null) ", skillCatalogs: []" else """, skillCatalogs: ["$granted"]"""
        /*
         * Saving a file is off here, and that is what "granted nothing" has to
         * mean now.
         *
         * It is the one grant that is on by default - it only lets an agent
         * keep its own output where somebody can find it - so an agent left
         * alone is handed three tools rather than none, and a fixture written
         * to produce an agent with nothing has to say so outright.
         */
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: {
                 name: "$name", modelId: $modelId, artifactAccess: false$grant
               }) { id } }""",
        ).execute()
        return id
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    /** A reasoning deployment behind an Azure OpenAI provider, which speaks Responses by default. */
    private fun azureReasoningModel(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Azure", type: AZURE_OPENAI, endpoint: "$endpoint", secret: "azure-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Sol", modelId: "gpt-6-sol", kind: CHAT, reasoningEffort: "high"
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun catalog(name: String): Long = graphQlTester.document(
        """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
    ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()

    private fun skill(name: String, catalogId: Long, content: String): Long = graphQlTester.document(
        """mutation { createSkill(input: {
             workspaceId: $workspaceId, name: "$name", catalogId: $catalogId,
             content: ${'"'}${'"'}${'"'}---
name: $name
description: How to review
---

$content
${'"'}${'"'}${'"'}
           }) { id } }""",
    ).execute().path("createSkill.id").entity(Long::class.java).get()
}
