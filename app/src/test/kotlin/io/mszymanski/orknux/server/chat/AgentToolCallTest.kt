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
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
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
     * An agent with no grants is offered nothing.
     *
     * Not tools that answer "nothing here": that is a round trip spent learning
     * what the grant already said, and the model pays for it.
     */
    @Test
    fun `an agent granted nothing is handed no tools`() {
        val endpoint = serveToolThenAnswer()
        val agentId = agentGranted("Plain", model(endpoint), granted = null)

        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        conversation.answer(requireNotNull(agent.modelId), agent, listOf(ChatTurn("user", "Hello")))

        assertThat(received).hasSize(1)
        assertThat(received[0]).doesNotContain("tools")
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

    private fun serveAlwaysCallingTools(): String = serve {
        """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
          {"id":"call_n","type":"function","function":{"name":"skill_list","arguments":"{}"}}
        ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
        """.trimIndent()
    }

    private fun serve(answer: (String) -> String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
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
