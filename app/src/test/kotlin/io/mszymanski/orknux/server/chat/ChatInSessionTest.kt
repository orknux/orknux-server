package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import io.mszymanski.orknux.server.llm.SessionMemoryBudget
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
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
 * A person picking up a conversation an agent had.
 *
 * A session is written by agents going to work; nothing in it was ever said by
 * hand. This is the one way somebody joins one, and the round trip is the whole
 * feature: the chat opens holding what was already said, what is typed into it
 * is written back, and the next run to read that session hears the person as
 * plainly as it hears the agent that spoke before them.
 *
 * The binding is a pointer set when the chat is opened, not a key. A key is
 * something two callers can independently arrive at, which is what makes a
 * session shared; a key invented for a chat would name a conversation nothing
 * else could ever reach, so a chat opened any other way still has no session at
 * all.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatInSessionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chats: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val streaming: ChatStreamAPI,
    @Autowired val chatService: ChatService,
    @Autowired val titles: ChatTitles,
    @Autowired val transactions: org.springframework.transaction.PlatformTransactionManager,
    @Autowired val address: io.mszymanski.orknux.server.security.InstallationAddress,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        chats.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        chats.deleteAll()
        events.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        received.clear()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        // A chat is opened on an agent now, and a workspace with none to hand
        // is refused before anything is written - so the workspace needs one
        // before a single reading below can start a chat at all. This is the
        // one every reading that names no agent of its own opens on. It answers
        // nothing: a reading that sends builds its own agent, on a model this
        // test is serving.
        agent("Responder", model("http://models.invalid", name = "Idle"))
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        server = null
    }

    /**
     * The chat opens as a continuation rather than as a blank box.
     *
     * What was said comes back as the chat's own messages, in the order it was
     * said, so the person reads the conversation before adding to it - and the
     * lookup the agent made on the way is between them, where it happened.
     *
     * The chat is opened to work out what an agent did. An answer shown with
     * nothing between it and the question reads as the model having known
     * something it went and found out, which is the one reading this page must
     * not produce.
     */
    /**
     * A last answer longer than the whole memory allowance used to empty the
     * copy: the tail is filled newest first until the allowance runs out, and
     * the newest line ran it out on its own - so "Continue in chat" opened an
     * empty chat on exactly the long conversations worth continuing.
     */
    @Test
    fun `a chat opened from a session whose last answer is longer than its memory still holds it`() {
        val sessionId = session("issue", "43")
        recorder.userSaid(sessionId, "Ask reviewer", "Review the whole pull request, please.")
        recorder.agentSaid(sessionId, "Reviewer", "x".repeat(SessionMemoryBudget.DEFAULT.memoryChars + 1))

        val chatId = startChat(sessionId)

        val content = graphQlTester.document("""{ chatMessages(id: $chatId) { role content } }""")
            .execute()
            .path("chatMessages[*].content").entityList(String::class.java).get()
        assertThat(content).isNotEmpty()
        assertThat(content.last()).startsWith("xxx")
    }

    @Test
    fun `a chat opened from a session starts holding what was already said`() {
        val sessionId = session("issue", "42")
        recorder.userSaid(sessionId, "Ask reviewer", "Why did the database fall over?")
        recorder.toolCalled(sessionId, "skill_load", """{"name":"codeReview"}""")
        recorder.agentSaid(sessionId, "Reviewer", "The connection pool was exhausted.")

        val chatId = startChat(sessionId)

        graphQlTester.document("""{ chatMessages(id: $chatId) { role content actor } }""")
            .execute()
            .path("chatMessages[0].role").entity(String::class.java).isEqualTo("user")
            .path("chatMessages[0].content").entity(String::class.java)
            .isEqualTo("Why did the database fall over?")
            // The call, under the tool's own name and with what the model sent
            // it, exactly as the session's page draws it.
            .path("chatMessages[1].role").entity(String::class.java).isEqualTo("tool")
            .path("chatMessages[1].actor").entity(String::class.java).isEqualTo("skill_load")
            .path("chatMessages[1].content").entity(String::class.java)
            .isEqualTo("""{"name":"codeReview"}""")
            .path("chatMessages[2].role").entity(String::class.java).isEqualTo("assistant")
            .path("chatMessages[2].content").entity(String::class.java)
            .isEqualTo("The connection pool was exhausted.")
            .path("chatMessages").entityList(Any::class.java).hasSize(3)
    }

    /**
     * And the model is not told about it.
     *
     * The two readings are different on purpose. A call replayed into a prompt
     * is a call the model never made in this exchange with no result threaded
     * to it, so the thread the chat sends holds what was *said* and nothing
     * else - which is what it held before any of this, and what it must go on
     * holding on every later send.
     */
    @Test
    fun `the call shown in the chat is never put in front of the model`() {
        val sessionId = session("issue", "42")
        recorder.userSaid(sessionId, "Ask reviewer", "Why did the database fall over?")
        recorder.toolCalled(sessionId, "search_tickets", """{"query":"billing export"}""")
        recorder.agentSaid(sessionId, "Reviewer", "The connection pool was exhausted.")
        val chatId = startChat(sessionId, agentId = agent("Reviewer", model(serveAnswer("Raise the pool size."))))

        send(chatId, "So what do we do about it?")

        val asked = received.single()
        assertThat(asked).contains("Why did the database fall over?")
        assertThat(asked).contains("The connection pool was exhausted.")
        assertThat(asked).doesNotContain("search_tickets")
        assertThat(asked).doesNotContain("billing export")
    }

    /**
     * A call outside the stretch that was carried is not drawn inside it.
     *
     * The names and the calls are put back by matching the thread against the
     * session, and the match is the safe answer when it is unsure. A call made
     * before the oldest turn the chat carried was not part of what it carried,
     * and showing it would say the agent looked something up in the middle of
     * an exchange that had already ended.
     */
    /**
     * A call that named something with a page links to it, read back from
     * what it returned. Issue #660: the run or research an agent started was
     * on the line only as arguments, and somebody wanting to look at it had to
     * go and find it.
     */
    @Test
    fun `a call that named an item comes back with a link to its page`() {
        val sessionId = session("issue", "42")
        recorder.userSaid(sessionId, "Ask reviewer", "File the export bug, and look for the old one.")
        val filed = recorder.toolCalled(sessionId, "orknux_open_issue", """{"title":"Export runs twice"}""")
        recorder.toolReturned(
            filed,
            """{"issue":7,"title":"Export runs twice","url":"${address.base()}/workspace/$workspaceId/issues/7"}""",
        )
        val searched = recorder.toolCalled(sessionId, "search_tickets", """{"query":"export"}""")
        recorder.toolReturned(searched, """{"found":0,"url":"https://tickets.example.com/workspace/1/issues/2"}""")
        recorder.agentSaid(sessionId, "Reviewer", "Filed as #7; there was no old one.")

        val chatId = startChat(sessionId)

        graphQlTester.document("""{ chatMessages(id: $chatId) { role actor link { url name } } }""")
            .execute()
            .path("chatMessages[1].actor").entity(String::class.java).isEqualTo("orknux_open_issue")
            .path("chatMessages[1].link.url").entity(String::class.java).isEqualTo("/workspace/$workspaceId/issues/7")
            .path("chatMessages[1].link.name").entity(String::class.java).isEqualTo("Export runs twice")
            // Somebody else's page is not this installation's item.
            .path("chatMessages[2].actor").entity(String::class.java).isEqualTo("search_tickets")
            .path("chatMessages[2].link").valueIsNull()
    }

    @Test
    fun `a call made before what was carried is left where it happened`() {
        val sessionId = session("issue", "42")
        recorder.toolCalled(sessionId, "search_tickets", """{"query":"billing export"}""")
        recorder.userSaid(sessionId, "Ask reviewer", "Why did the database fall over?")
        recorder.agentSaid(sessionId, "Reviewer", "The connection pool was exhausted.")

        val chatId = startChat(sessionId)

        graphQlTester.document("""{ chatMessages(id: $chatId) { role actor } }""")
            .execute()
            .path("chatMessages[0].role").entity(String::class.java).isEqualTo("user")
            .path("chatMessages[1].role").entity(String::class.java).isEqualTo("assistant")
            .path("chatMessages").entityList(Any::class.java).hasSize(2)
    }

    /**
     * And each of them still says who said it.
     *
     * The store the chat's thread lives in keeps a role and some text, so a
     * carried turn arrives with nowhere to put a name and the screen signs
     * every answer with the model the chat is talking to now. In a chat opened
     * to work out what an agent did, that is the agent's own words under
     * somebody else's name. The names come back off the session the turns were
     * taken from.
     */
    @Test
    fun `the turns carried in keep the name of whoever said them`() {
        val sessionId = session("issue", "42")
        recorder.userSaid(sessionId, "Slack: dana", "Why did the database fall over?")
        recorder.toolCalled(sessionId, "skill_load", """{"name":"codeReview"}""")
        recorder.agentSaid(sessionId, "Reviewer", "The connection pool was exhausted.")

        val chatId = startChat(sessionId)

        graphQlTester.document("""{ chatMessages(id: $chatId) { role content actor } }""")
            .execute()
            // Not "alice", who is reading it - a session's question can come
            // from anywhere, and this one came from Slack.
            .path("chatMessages[0].actor").entity(String::class.java).isEqualTo("Slack: dana")
            // The tool, under the name it was called by.
            .path("chatMessages[1].actor").entity(String::class.java).isEqualTo("skill_load")
            // And the agent, rather than the model the chat happens to hold.
            .path("chatMessages[2].actor").entity(String::class.java).isEqualTo("Reviewer")
    }

    /**
     * What the chat says itself carries no name, which is where the carried
     * part ends.
     *
     * The boundary is the thing a diagnostic reader is looking for - what was
     * already there against what they have just added - so it is answered by
     * the same field rather than by counting. It survives the chat writing back
     * into the session: the turns this chat put there are not turns it carried
     * out of it.
     */
    @Test
    fun `the chat's own turns are not attributed to the session`() {
        val sessionId = session("issue", "42")
        recorder.agentSaid(sessionId, "Reviewer", "The connection pool was exhausted.")
        val chatId = startChat(sessionId, agentId = agent("Reviewer", model(serveAnswer("Raise the pool size."))))

        send(chatId, "So what do we do about it?")

        graphQlTester.document("""{ chatMessages(id: $chatId) { role content actor } }""")
            .execute()
            .path("chatMessages[0].actor").entity(String::class.java).isEqualTo("Reviewer")
            .path("chatMessages[1].actor").valueIsNull()
            .path("chatMessages[2].actor").valueIsNull()
            .path("chatMessages").entityList(Any::class.java).hasSize(3)
    }

    /**
     * A chat continuing nothing names nobody.
     *
     * There is no session to have carried anything out of, so every turn is the
     * chat's own - which is also the chat that existed before any of this, and
     * it must read exactly as it did.
     */
    @Test
    fun `an ordinary chat attributes none of its turns`() {
        val chatId = bareChat(llmSessionId = null, endpoint = serveAnswer("Hello."))

        send(chatId, "Anyone there?")

        graphQlTester.document("""{ chatMessages(id: $chatId) { role content actor } }""")
            .execute()
            .path("chatMessages[0].actor").valueIsNull()
            .path("chatMessages[1].actor").valueIsNull()
    }

    /**
     * And the transcript keeps growing, which is what a later run reads.
     *
     * The person's turn under their own name and the model's under the model's,
     * appended to the same session rather than to a copy of it. Nothing here is
     * a new session: the chat pointed at one that already existed.
     */
    @Test
    fun `what is said in the chat is written back into the session`() {
        val sessionId = session("issue", "42")
        recorder.agentSaid(sessionId, "Reviewer", "The connection pool was exhausted.")
        val chatId = bareChat(sessionId, endpoint = serveAnswer("Raise the pool size."))

        send(chatId, "So what do we do about it?")

        assertThat(sessions.findAll()).hasSize(1)
        val lines = events.findAll().sortedBy { it.id }
        assertThat(lines.map { it.kind }).containsExactly(
            LlmSessionEventKind.AGENT,
            LlmSessionEventKind.USER,
            LlmSessionEventKind.AGENT,
        )
        // Whoever typed it, by name - so a run reading this hears a person
        // rather than another agent.
        assertThat(lines[1].actor).isEqualTo("alice")
        assertThat(lines[1].content).isEqualTo("So what do we do about it?")
        // And what answered, under what the chat was talking to.
        assertThat(lines[2].actor).isEqualTo("Stub")
        assertThat(lines[2].content).isEqualTo("Raise the pool size.")
    }

    /**
     * A chat's thinking is a line in its session, as a task's and a workflow
     * step's is, so the session view shows what led to an answer and not only
     * the answer. It used to go only to the chat's own page.
     */
    @Test
    fun `what the model thought is written into the session`() {
        val sessionId = session("issue", "43")
        val endpoint = serve {
            """{"choices":[{"message":{"role":"assistant","reasoning_content":"The pool is too small.","content":"Raise the pool size."}}],
               "usage":{"prompt_tokens":11,"completion_tokens":6}}"""
        }
        val chatId = startChat(sessionId, agentId = agent("Reviewer", model(endpoint)))

        send(chatId, "So what do we do about it?")

        val thought = events.findAll().filter { it.sessionId == sessionId && it.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.THINKING }
        assertThat(thought).hasSize(1)
        assertThat(thought.single().content).isEqualTo("The pool is too small.")
        assertThat(thought.single().actor).isEqualTo("Reviewer")
        assertThat(thought.single().millis).isNotNull()
    }

    /**
     * A reloaded chat draws its thinking where it happened, as the live one
     * does: each round's thinking a line of its own among the calls, and the
     * answer then carrying none, so the same thought is not drawn twice. It
     * used to come back as one block on the answer, below every call - so
     * what the model concluded from a lookup read as coming before it.
     */
    @Test
    fun `a reloaded chat draws each round's thinking in its place among the calls`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveThinkingToolThenThinkingAnswer()), granted = "Reviews")
        val chatId = startChat(llmSessionId = null, agentId = agentId)

        stream(chatId, "What does the review skill say?")

        graphQlTester.document("""{ chatMessages(id: $chatId) { role content actor thinking } }""")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java)
            .containsExactly("user", "thought", "tool", "thought", "assistant")
            .path("chatMessages[1].thinking").entity(String::class.java).isEqualTo("Which skill holds it?")
            .path("chatMessages[1].actor").entity(String::class.java).isEqualTo("Reviewer")
            .path("chatMessages[2].actor").entity(String::class.java).isEqualTo("skill_load")
            .path("chatMessages[3].thinking").entity(String::class.java).isEqualTo("The skill says twice.")
            .path("chatMessages[4].content").entity(String::class.java).isEqualTo("Read the diff twice.")
            // Drawn among the calls, so not on the answer as well.
            .path("chatMessages[4].thinking").valueIsNull()
    }

    /**
     * And the open chat is told when each round's thinking ends and how long
     * it took, before what the round went on to do. Its own clock read "0.0
     * seconds" for reasoning that arrived at once, and nothing for a round it
     * never saw go live.
     */
    @Test
    fun `each round's thinking is closed with its duration, before the round's call`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveThinkingToolThenThinkingAnswer()), granted = "Reviews")
        val chatId = startChat(llmSessionId = null, agentId = agentId)

        val response = org.springframework.mock.web.MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest("What does the review skill say?"), response)
            .writeTo(response.outputStream)
        val frames = response.contentAsString.replace(" ", "")

        val first = frames.indexOf("event:thought\ndata:{\"text\":\"Whichskillholdsit?\",\"millis\":")
        val second = frames.indexOf("event:thought\ndata:{\"text\":\"Theskillsaystwice.\",\"millis\":")
        assertThat(first).isNotNegative()
        assertThat(second).isGreaterThan(first)
        assertThat(frames.indexOf("event:call")).isGreaterThan(first).isLessThan(second)
    }

    /** A model that thinks once and looks nothing up keeps its thinking on its answer, as it always has. */
    @Test
    fun `thinking with no call to put it among stays on the answer`() {
        val endpoint = serve {
            listOf(
                """data: {"choices":[{"delta":{"reasoning_content":"The pool is too small."}}]}""",
                """data: {"choices":[{"delta":{"content":"Raise the pool size."}}]}""",
                """data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":11,"completion_tokens":6}}""",
                "data: [DONE]",
            ).joinToString("") { it + "\n\n" }
        }
        val chatId = startChat(llmSessionId = null, agentId = agent("Reviewer", model(endpoint)))

        stream(chatId, "So what do we do about it?")
        // The session does hold the thought; it is the reload that keeps it on the answer.
        assertThat(events.findAll().filter { it.kind == LlmSessionEventKind.THINKING }).hasSize(1)

        graphQlTester.document("""{ chatMessages(id: $chatId) { role thinking } }""")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java).containsExactly("user", "assistant")
            .path("chatMessages[1].thinking").entity(String::class.java).isEqualTo("The pool is too small.")
    }

    /**
     * A turn answered without a page watching writes its thinking once, joined
     * and after the answer, so the session cannot say where each round thought.
     * The answer keeps its block, and nothing is drawn as a line of its own.
     */
    @Test
    fun `thinking the session holds only after the answer stays on the answer`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveThinkingToolThenThinkingAnswer()), granted = "Reviews")
        val chatId = startChat(llmSessionId = null, agentId = agentId)

        send(chatId, "What does the review skill say?")
        send(chatId, "And then?")

        graphQlTester.document("""{ chatMessages(id: $chatId) { role thinking } }""")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java)
            .containsExactly("user", "tool", "assistant", "user", "tool", "assistant")
            .path("chatMessages[2].thinking").entity(String::class.java)
            .isEqualTo("Which skill holds it?\n\nThe skill says twice.")
    }

    /**
     * The model hears the session too, once - because the chat's own thread is
     * what was copied in.
     *
     * Reading the session again on every send would put everything said so far
     * in front of the model twice, and the copies would grow with the chat. The
     * thread is the memory from the moment the chat opens.
     */
    @Test
    fun `the model is asked with the session's exchange in front of it, once`() {
        val sessionId = session("issue", "42")
        recorder.agentSaid(sessionId, "Reviewer", "The connection pool was exhausted.")
        val chatId = startChat(sessionId, agentId = agent("Reviewer", model(serveAnswer("Raise the pool size."))))

        send(chatId, "So what do we do about it?")

        val asked = received.single()
        assertThat(asked).contains("The connection pool was exhausted.")
        assertThat(asked.split("The connection pool was exhausted.")).hasSize(2)
    }

    /**
     * An agent's answer is written once, by the round that produced it.
     *
     * [AgentConversation] records an agent's round as it happens - the tools it
     * called included, which nothing outside the loop could produce - so the
     * chat must not write the answer a second time under a different name.
     */
    @Test
    fun `an agent answering in the chat records its round once, under its own name`() {
        val sessionId = session("issue", "42")
        val chatId = startChat(sessionId, agentId = agent("Reviewer", model(serveAnswer("Raise the pool size."))))

        send(chatId, "So what do we do about it?")

        // The setup line is the turn's account of the agent, not something it said.
        val lines = events.findAll().sortedBy { it.id }.filterNot { it.kind == LlmSessionEventKind.AGENT_DETAILS }
        assertThat(lines.map { it.kind })
            .containsExactly(LlmSessionEventKind.USER, LlmSessionEventKind.AGENT)
        assertThat(lines[1].actor).isEqualTo("Reviewer")
    }

    /**
     * A session belongs to one workspace, and so does a chat.
     *
     * Refused when the chat opens rather than when it is first sent to: a chat
     * bound to a session it may not write into looks like a continuation and is
     * not one.
     */
    @Test
    fun `a session from another workspace cannot be continued here`() {
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        val sessionId = recorder.open(elsewhere, "issue", "42")

        graphQlTester.document(
            """mutation { startChat(input: {
                 workspaceId: $workspaceId, title: "issue:42", llmSessionId: $sessionId
               }) { id } }""",
        ).execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("belongs to another workspace")
            }

        assertThat(chats.findAll()).isEmpty()
    }

    /**
     * A chat started any other way still belongs to no session.
     *
     * Every chat there has ever been is this chat, and it must cost nothing: no
     * session opened, no line written, and no key invented on anybody's behalf.
     */
    @Test
    fun `a chat started the ordinary way records nothing`() {
        val chatId = bareChat(llmSessionId = null, endpoint = serveAnswer("Hello."))

        send(chatId, "Anyone there?")

        assertThat(chats.findAll().single().llmSessionId).isNull()
        assertThat(sessions.findAll()).isEmpty()
        assertThat(events.findAll()).isEmpty()
    }

    /**
     * The chat this was found in, and the half of the fix that reaches it.
     *
     * A chat's memory is its thread, and the thread never held a tool's result:
     * the provider's loop resolved the call and only the text the model wrote
     * out of it was kept. So the second question about a lookup was answered
     * out of the first answer's prose - which is how a model came to report
     * issues as unlabelled that carried a label, and to correct itself the
     * moment it called the tool again.
     *
     * A chat with an agent opens a session of its own for exactly this, and the
     * second send is asked with the data in front of it. Asserted on the first
     * request of that send, before any tool has run in it, so what is in the
     * body was remembered rather than fetched again.
     */
    @Test
    fun `a chat with an agent is asked with what its tools returned before`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveToolThenAnswer()), granted = "Reviews")
        val chatId = startChat(llmSessionId = null, agentId = agentId)

        send(chatId, "What does the review skill say?")

        // Its own session, named after the one thing that is this chat's and
        // nothing else's, so nothing can arrive at the key by accident.
        val session = sessions.findAll().single()
        assertThat(session.keyPrefix).isEqualTo("chat")
        assertThat(chats.findAll().single().llmSessionId).isEqualTo(session.id)
        assertThat(events.findAll().single { it.kind == LlmSessionEventKind.TOOL }.result)
            .contains("Read the diff twice before commenting.")

        val second = received.size
        send(chatId, "Are you sure? Check again.")

        assertThat(received[second]).contains("Read the diff twice before commenting.")
    }

    /**
     * And what the agent said on its way to the lookup is on the page too.
     *
     * A provider may answer with a message and tool calls in one reply, and
     * several do it habitually. That message is the agent's, so it is written
     * into the session before the call it came with - and the thread never held
     * it, because the thread keeps only what the round finally answered.
     *
     * Which is the whole reason this is a test of the *page*. The page is
     * assembled by matching the thread against the session, and a said line
     * with no turn opposite it used to knock that alignment out: the run broke
     * at the aside, and the lookup it was about disappeared from the chat along
     * with it.
     */
    @Test
    fun `what the agent said before a lookup is drawn above the lookup`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveRemarkingThenToolThenAnswer()), granted = "Reviews")
        val chatId = startChat(llmSessionId = null, agentId = agentId)

        send(chatId, "What does the review skill say?")

        graphQlTester.document("""{ chatMessages(id: $chatId) { role content actor } }""")
            .execute()
            .path("chatMessages[0].role").entity(String::class.java).isEqualTo("user")
            .path("chatMessages[0].content").entity(String::class.java)
            .isEqualTo("What does the review skill say?")
            // The aside, under the agent's own name, above the call it came with.
            .path("chatMessages[1].role").entity(String::class.java).isEqualTo("assistant")
            .path("chatMessages[1].actor").entity(String::class.java).isEqualTo("Reviewer")
            .path("chatMessages[1].content").entity(String::class.java)
            .isEqualTo("Let me read the review skill first.")
            .path("chatMessages[2].role").entity(String::class.java).isEqualTo("tool")
            .path("chatMessages[2].actor").entity(String::class.java).isEqualTo("skill_load")
            .path("chatMessages[3].role").entity(String::class.java).isEqualTo("assistant")
            .path("chatMessages[3].content").entity(String::class.java).isEqualTo("Read the diff twice.")
            .path("chatMessages").entityList(Any::class.java).hasSize(4)
        // Every line carries when it was sent - the aside and the call too, which only the session holds.
        graphQlTester.document("""{ chatMessages(id: $chatId) { at } }""")
            .execute()
            .path("chatMessages[*].at").entityList(String::class.java).get()
            .also { times -> assertThat(times).hasSize(4).allSatisfy { assertThat(it).isNotBlank() } }
    }

    /**
     * And the open chat is told it as it happens, not on the next reload: what
     * the agent writes beside a call is how it keeps somebody posted on a long
     * piece of work, and it used to reach only the session.
     */
    @Test
    fun `what the agent says beside a lookup is streamed before the lookup`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveRemarkingThenToolThenAnswer()), granted = "Reviews")
        val chatId = startChat(llmSessionId = null, agentId = agentId)

        val response = org.springframework.mock.web.MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest("What does the review skill say?"), response)
            .writeTo(response.outputStream)
        val frames = response.contentAsString.replace(" ", "")

        val said = frames.indexOf("Letmereadthereviewskillfirst.")
        assertThat(said).isNotNegative()
        assertThat(frames.lastIndexOf("event:", said)).isEqualTo(frames.lastIndexOf("event:said", said))
        assertThat(frames.indexOf("event:call")).isGreaterThan(said)
        // The session the chat opened with this turn is named before anything else, so the page can link to it at once.
        val opened = requireNotNull(chats.findByIdOrNull(chatId)?.llmSessionId)
        assertThat(frames).contains("event:session\ndata:{\"id\":\"$opened\"}")
        assertThat(frames.indexOf("event:session")).isLessThan(said)
    }

    /**
     * A chat's session says what the agent was set up with, as a workflow
     * step's and a task's do: an Agent details line where the turn starts, and
     * none again while nothing about the agent changed.
     */
    @Test
    fun `a chat writes the agent's setup into its session once`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveRemarkingThenToolThenAnswer()), granted = "Reviews")
        val chatId = startChat(llmSessionId = null, agentId = agentId)

        send(chatId, "What does the review skill say?")
        send(chatId, "And again?")

        val session = requireNotNull(chats.findByIdOrNull(chatId)?.llmSessionId)
        val details = events.findAll().filter { it.sessionId == session && it.kind == LlmSessionEventKind.AGENT_DETAILS }
        assertThat(details).hasSize(1)
        assertThat(details.single().content).contains("Reviewer").contains("skill_load")
    }

    /**
     * What the person writes while the agent is working reaches the agent at
     * its next round, as the person speaking - and stays in the chat where the
     * agent read it, and in the session as theirs.
     */
    @Test
    fun `a message written while the agent works is read at its next round, as the person's`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        var chatId = 0L
        val endpoint = serve { body ->
            if (body.contains("tool_call_id")) {
                """{"choices":[{"message":{"role":"assistant","content":"Blue it is."}}],"usage":{"prompt_tokens":9,"completion_tokens":4}}"""
            } else {
                // The person writes while the first round is still being answered.
                chatService.postDuringTurn(chatId, "alice", "Use the blue one, please.")
                """{"choices":[{"message":{"role":"assistant","content":"Let me read the review skill first.",
                 "tool_calls":[{"id":"call_1","type":"function","function":{"name":"skill_load","arguments":"{\"name\":\"codeReview\"}"}}]}}],
                 "usage":{"prompt_tokens":7,"completion_tokens":2}}"""
            }
        }
        val agentId = agent("Reviewer", model(endpoint), granted = "Reviews")
        chatId = startChat(llmSessionId = null, agentId = agentId)

        send(chatId, "Which colour?")

        val second = received.last()
        assertThat(second).contains("alice wrote this while you were working").contains("Use the blue one, please.")
        graphQlTester.document("""{ chatMessages(id: $chatId) { role content } }""").execute()
            .path("chatMessages[*].content").entityList(String::class.java).get().also { said ->
                assertThat(said.indexOf("Use the blue one, please.")).isGreaterThan(said.indexOf("Which colour?"))
                assertThat(said.indexOf("Blue it is.")).isGreaterThan(said.indexOf("Use the blue one, please."))
            }
        val session = requireNotNull(chats.findByIdOrNull(chatId)?.llmSessionId)
        assertThat(events.findAll().filter { it.sessionId == session && it.kind == LlmSessionEventKind.USER }.map { it.actor to it.content })
            .contains("alice" to "Use the blue one, please.")
    }

    /**
     * Two messages written at different rounds come back each where the agent
     * read it: after the calls before it and before the calls after it, with
     * times that never go backwards. Session 639 drew one above the whole turn.
     */
    @Test
    fun `messages written at different rounds are drawn where the agent read them`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        var chatId = 0L
        var round = 0
        val call = { aside: String, id: String ->
            """{"choices":[{"message":{"role":"assistant","content":"$aside",
             "tool_calls":[{"id":"$id","type":"function","function":{"name":"skill_load","arguments":"{\"name\":\"codeReview\"}"}}]}}],
             "usage":{"prompt_tokens":7,"completion_tokens":2}}"""
        }
        val endpoint = serve { _ ->
            round++
            when (round) {
                1 -> {
                    chatService.postDuringTurn(chatId, "alice", "First note.")
                    call("Looking first.", "call_1")
                }
                2 -> {
                    chatService.postDuringTurn(chatId, "alice", "Second note.")
                    call("Looking again.", "call_2")
                }
                else -> """{"choices":[{"message":{"role":"assistant","content":"All done."}}],"usage":{"prompt_tokens":9,"completion_tokens":2}}"""
            }
        }
        chatId = startChat(llmSessionId = null, agentId = agent("Reviewer", model(endpoint), granted = "Reviews"))

        send(chatId, "Have a look.")

        val shown = graphQlTester.document("""{ chatMessages(id: $chatId) { role content at } }""").execute()
        val contents = shown.path("chatMessages[*].content").entityList(String::class.java).get()
        val roles = shown.path("chatMessages[*].role").entityList(String::class.java).get()
        val drawn = contents.indices.map { if (roles[it] == "tool") "call" else contents[it] }
        assertThat(drawn).containsExactly(
            "Have a look.", "Looking first.", "call", "First note.", "Looking again.", "call", "Second note.", "All done.",
        )
        val times = shown.path("chatMessages[*].at").entityList(String::class.java).get().map { java.time.OffsetDateTime.parse(it) }
        assertThat(times.zipWithNext().all { (a, b) -> !b.isBefore(a) }).describedAs(times.toString()).isTrue()
    }

    /**
     * The open chat is told when the agent has read the person's message, so the
     * page can say Queued until then and Read after: a model reading a long
     * prompt took minutes to reach it, and "Sent" read as ignored.
     */
    @Test
    fun `the open chat is told when the agent reads what the person wrote mid-turn`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        var chatId = 0L
        val endpoint = serve { body ->
            if (body.contains("tool_call_id")) {
                """{"choices":[{"message":{"role":"assistant","content":"Blue it is."}}],"usage":{"prompt_tokens":9,"completion_tokens":4}}"""
            } else {
                chatService.postDuringTurn(chatId, "alice", "Use the blue one, please.")
                """{"choices":[{"message":{"role":"assistant","content":"Let me read the review skill first.",
                 "tool_calls":[{"id":"call_1","type":"function","function":{"name":"skill_load","arguments":"{\"name\":\"codeReview\"}"}}]}}],
                 "usage":{"prompt_tokens":7,"completion_tokens":2}}"""
            }
        }
        chatId = startChat(llmSessionId = null, agentId = agent("Reviewer", model(endpoint), granted = "Reviews"))

        val response = org.springframework.mock.web.MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest("Which colour?"), response).writeTo(response.outputStream)
        val frames = response.contentAsString.replace(" ", "")

        val read = frames.indexOf("event:personRead")
        assertThat(read).isNotNegative()
        assertThat(frames.substring(read)).contains("Usetheblueone,please.")
        assertThat(frames.indexOf("Blueitis.")).isGreaterThan(read)
    }

    /**
     * Written while the model writes its final answer, the message is not left
     * for later: the turn sees it arrived and asks the model again with it.
     */
    @Test
    fun `a message written during the last round is answered before the turn ends`() {
        var chatId = 0L
        val endpoint = serve { body ->
            if (!body.contains("Use the blue one")) {
                chatService.postDuringTurn(chatId, "alice", "Use the blue one, please.")
                """{"choices":[{"message":{"role":"assistant","content":"Red."}}],"usage":{"prompt_tokens":9,"completion_tokens":1}}"""
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"Blue, then."}}],"usage":{"prompt_tokens":9,"completion_tokens":2}}"""
            }
        }
        chatId = startChat(llmSessionId = null, agentId = agent("Reviewer", model(endpoint)))

        send(chatId, "Which colour?")

        val deadline = System.currentTimeMillis() + 10_000
        while (received.none { it.contains("alice wrote this while you were working") } && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertThat(received.last()).contains("alice wrote this while you were working")
    }

    /** With no turn running there is nothing to write into: the page sends it as a turn of its own. */
    @Test
    fun `a message for a turn that is not running is not taken`() {
        val chatId = startChat(llmSessionId = null, agentId = agent("Reviewer", model(serveAnswer("Hi."))))
        graphQlTester.document("""mutation { postChatMessageDuringTurn(id: $chatId, text: "too late") }""")
            .execute().path("postChatMessageDuringTurn").entity(Boolean::class.java).isEqualTo(false)
    }

    /**
     * And a chat with a bare model still opens nothing.
     *
     * The rule being bent above is that a chat computes no key, and it is bent
     * only where a chat has something its thread cannot hold. A bare model
     * calls no tools, so there is nothing to keep and nothing is invented on
     * anybody's behalf.
     */
    /**
     * Naming a chat asks a model, and nothing else may wait for that.
     *
     * It was one @Transactional method, so on SQLite the database's one writer
     * was held for as long as the model took: a local model thinking for a
     * minute stalled every write, and a stub that had gone away left the next
     * test class failing to commit. While the model answers, another
     * transaction must be able to start - which on SQLite is the question,
     * and on Postgres is true either way.
     */
    @Test
    fun `naming a chat does not hold a transaction while the model answers`() {
        val other = org.springframework.transaction.support.TransactionTemplate(transactions)
        val couldWrite = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val chatId = bareChat(
            llmSessionId = null,
            endpoint = serve {
                couldWrite.set(
                    runCatching {
                        java.util.concurrent.CompletableFuture
                            .supplyAsync { other.execute { chats.count() } }
                            .get(3, java.util.concurrent.TimeUnit.SECONDS)
                        true
                    }.getOrDefault(false),
                )
                """{"choices":[{"message":{"role":"assistant","content":"Pool sizing"}}]}"""
            },
        )
        chats.save(requireNotNull(chats.findByIdOrNull(chatId)).apply { title = "New chat" })

        titles.nameFrom(chatId, "How big should the pool be?", "Twenty.")

        assertThat(couldWrite.get()).describedAs("another transaction could start while the model answered").isTrue()
        assertThat(chats.findByIdOrNull(chatId)?.title).isEqualTo("Pool sizing")
    }

    /**
     * A chat named from its first message before the answer exists - only where
     * a companion model that is not the chat's own can do it, so the naming
     * does not queue behind the answer on one local server - and the model is
     * handed the message alone, with no empty Reply to read as an empty reply.
     */
    @Test
    fun `a companion model other than the chat's names it from the first message alone`() {
        val asked = java.util.concurrent.atomic.AtomicReference<String>()
        val chatId = bareChat(llmSessionId = null, endpoint = serveAnswer("Twenty."))
        chats.save(requireNotNull(chats.findByIdOrNull(chatId)).apply { title = "New chat" })
        assertThat(titles.namesEarly(chatId)).describedAs("no companion: named at the turn's end").isFalse()

        val chatModel = requireNotNull(chats.findByIdOrNull(chatId)).modelId
        val workspace = requireNotNull(workspaces.findByIdOrNull(workspaceId))
        workspaces.save(workspace.apply { companionModelId = chatModel })
        assertThat(titles.namesEarly(chatId)).describedAs("the companion is the chat's own model").isFalse()

        val companion = model(serve { body -> asked.set(body); """{"choices":[{"message":{"role":"assistant","content":"Pool sizing"}}]}""" }, name = "Companion")
        workspaces.save(requireNotNull(workspaces.findByIdOrNull(workspaceId)).apply { companionModelId = companion })
        assertThat(titles.namesEarly(chatId)).isTrue()

        titles.nameFrom(chatId, "How big should the pool be?", "")
        assertThat(chats.findByIdOrNull(chatId)?.title).isEqualTo("Pool sizing")
        assertThat(asked.get()).contains("Message: How big should the pool be?").doesNotContain("Reply:")
        assertThat(titles.namesEarly(chatId)).describedAs("a named chat is not named early again").isFalse()
    }

    /**
     * One naming of a chat at a time. A slow model naming it while the next
     * turn finished was asked again by that turn - "Naming this chat…" came
     * back on every short voice turn - and the last answer won.
     */
    @Test
    fun `a chat being named is not named again by a turn finishing meanwhile`() {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        val chatId = bareChat(
            llmSessionId = null,
            endpoint = serve {
                asked.incrementAndGet()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                """{"choices":[{"message":{"role":"assistant","content":"Pool sizing"}}]}"""
            },
        )
        chats.save(requireNotNull(chats.findByIdOrNull(chatId)).apply { title = "New chat" })

        val first = java.util.concurrent.CompletableFuture.runAsync { titles.nameFrom(chatId, "How big should the pool be?", "Twenty.") }
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until { asked.get() == 1 }
        assertThat(titles.wouldName(chatId)).describedAs("a chat being named is not one to name").isFalse()
        assertThat(titles.nameFrom(chatId, "And the timeout?", "Thirty seconds.")).isNull()
        release.countDown()
        first.get(10, java.util.concurrent.TimeUnit.SECONDS)

        assertThat(asked.get()).isEqualTo(1)
        val named = requireNotNull(chats.findByIdOrNull(chatId))
        assertThat(named.title).isEqualTo("Pool sizing")
        assertThat(named.titleGenerated).isTrue()
    }

    /**
     * A title a model gave is given again every configured number of exchanges;
     * one somebody typed never is, and is not counted towards it.
     */
    @Test
    fun `a model's title is renewed every configured number of exchanges, and a typed one never`() {
        graphQlTester.document("mutation { setChatRetitleEvery(exchanges: 3) { chatRetitleEvery } }").execute()
            .path("setChatRetitleEvery.chatRetitleEvery").entity(Int::class.java).isEqualTo(3)
        try {
            val chatId = bareChat(llmSessionId = null, endpoint = serveAnswer("Timeouts and retries"))
            chats.save(requireNotNull(chats.findByIdOrNull(chatId)).apply { title = "Pool sizing"; titleGenerated = true })

            repeat(2) { titles.nameFrom(chatId, "And then?", "More.") }
            assertThat(chats.findByIdOrNull(chatId)?.title).isEqualTo("Pool sizing")
            assertThat(chats.findByIdOrNull(chatId)?.turnsSinceTitle).isEqualTo(2)
            assertThat(titles.wouldName(chatId)).describedAs("the third exchange names it again").isTrue()
            titles.nameFrom(chatId, "And the retries?", "Three.")
            assertThat(chats.findByIdOrNull(chatId)?.title).isEqualTo("Timeouts and retries")
            assertThat(chats.findByIdOrNull(chatId)?.turnsSinceTitle).isZero()

            // Typed by a person: never named again, and not counted.
            graphQlTester.document("""mutation { renameChat(id: $chatId, title: "My pool notes") { title } }""").execute()
            repeat(4) { titles.nameFrom(chatId, "And then?", "More.") }
            assertThat(chats.findByIdOrNull(chatId)?.title).isEqualTo("My pool notes")
            assertThat(titles.wouldName(chatId)).isFalse()
        } finally {
            graphQlTester.document("mutation { setChatRetitleEvery(exchanges: 20) { chatRetitleEvery } }").execute()
        }
    }

    /** With no companion model in the workspace, the chat's own model names it rather than nothing doing. */
    @Test
    fun `a chat in a workspace with no companion model is named by its own model`() {
        val chatId = bareChat(llmSessionId = null, endpoint = serveAnswer("Pool sizing"))
        chats.save(requireNotNull(chats.findByIdOrNull(chatId)).apply { title = "New chat" })

        send(chatId, "How big should the pool be?")

        assertThat(chats.findByIdOrNull(chatId)?.title).isEqualTo("Pool sizing")
    }

    /**
     * An agent moved to another model answers its existing chats with the new
     * one. Each chat kept the model it began with, and only a new chat followed
     * the change - the old provider went on being called, and refusing.
     */
    @Test
    fun `an agent's chat follows the agent to its new model`() {
        val before = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        before.createContext("/chat/completions") { exchange ->
            // Read before answering: a request left unread past the socket's buffer is reset on Windows, not answered.
            exchange.requestBody.use { it.readAllBytes() }
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"From the old model."}}],"usage":{"prompt_tokens":5,"completion_tokens":3}}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        before.start()
        try {
            val agentId = agent("Mover", model("http://${before.address.hostString}:${before.address.port}", "Old"))
            val chatId = startChat(llmSessionId = null, agentId = agentId)
            send(chatId, "Hello")

            val moved = model(serveAnswer("From the new model."), "New")
            graphQlTester.document("""mutation { updateAgent(id: $agentId, input: { name: "Mover", modelId: $moved }) { id } }""")
                .execute().path("updateAgent.id").hasValue()

            graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Again") { answer { content } } }""")
                .execute().path("sendChatMessage.answer.content").entity(String::class.java).isEqualTo("From the new model.")
            assertThat(chats.findByIdOrNull(chatId)?.modelId).isEqualTo(moved)
        } finally {
            before.stop(0)
        }
    }

    /**
     * A companion model that does not answer within the time limit is passed
     * over for the chat's own model. A busy companion held the title for the
     * whole answer time limit, an hour by default.
     */
    @Test
    fun `a companion model that does not answer in time is passed over for the chat's own`() {
        val silent = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        silent.createContext("/chat/completions") { exchange ->
            Thread.sleep(5_000)
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        silent.start()
        try {
            graphQlTester.document("mutation { setChatTitleSeconds(seconds: 1) { chatTitleSeconds } }").execute()
                .path("setChatTitleSeconds.chatTitleSeconds").entity(Int::class.java).isEqualTo(1)
            val companion = model("http://${silent.address.hostString}:${silent.address.port}", "Companion")
            workspaces.save(requireNotNull(workspaces.findByIdOrNull(workspaceId)).apply { companionModelId = companion })
            val chatId = bareChat(llmSessionId = null, endpoint = serveAnswer("Pool sizing"))
            chats.save(requireNotNull(chats.findByIdOrNull(chatId)).apply { title = "New chat" })

            send(chatId, "How big should the pool be?")

            assertThat(chats.findByIdOrNull(chatId)?.title).isEqualTo("Pool sizing")
        } finally {
            graphQlTester.document("mutation { setChatTitleSeconds(seconds: 30) { chatTitleSeconds } }").execute()
            silent.stop(0)
        }
    }

    /**
     * A model that only thinks when asked for a title still leaves the chat
     * named: from the first words of what was asked. A reasoning model answered
     * the naming request with nothing, and every chat on an installation stayed
     * New chat without a line in the log.
     */
    @Test
    fun `a chat whose model gives no title is named from the first words of the question`() {
        val endpoint = serve { body ->
            if (body.contains("You name conversations")) {
                """{"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"Hmm, a title."}}],"usage":{"prompt_tokens":5,"completion_tokens":3}}"""
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"About forty gigabytes."}}],"usage":{"prompt_tokens":5,"completion_tokens":3}}"""
            }
        }
        val chatId = bareChat(llmSessionId = null, endpoint = endpoint)
        chats.save(requireNotNull(chats.findByIdOrNull(chatId)).apply { title = "New chat" })

        send(chatId, "Can you check what the biggest files are on the server?")

        assertThat(chats.findByIdOrNull(chatId)?.title).isEqualTo("Can you check what the biggest")
    }

    /** And the open chat is told the naming failed, for a moment, rather than nothing: a bad key used to leave every chat New chat in silence. */
    @Test
    fun `the open chat is told when its model could not name it`() {
        val endpoint = serve { body ->
            if (body.contains("You name conversations")) {
                """{"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"Hmm."}}],"usage":{"prompt_tokens":5,"completion_tokens":3}}"""
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"Forty gigabytes."}}],"usage":{"prompt_tokens":5,"completion_tokens":3}}"""
            }
        }
        val chatId = bareChat(llmSessionId = null, endpoint = endpoint)
        chats.save(requireNotNull(chats.findByIdOrNull(chatId)).apply { title = "New chat" })

        val response = org.springframework.mock.web.MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest("How big is the disk?"), response).writeTo(response.outputStream)

        assertThat(response.contentAsString).contains("event: titleFailed").contains("\"reason\"")
        assertThat(chats.findByIdOrNull(chatId)?.title).isEqualTo("How big is the disk")
    }

    /**
     * The page is told the chat is being named before its model is asked, and
     * what it was named once it is - in that order, after the answer. Naming
     * takes as long as a short answer, and the bar said nothing while it did.
     */
    @Test
    fun `the open chat is told it is being named, then its name`() {
        val endpoint = serve { body ->
            val content = if (body.contains("You name conversations")) "Disk usage check" else "Forty gigabytes."
            """{"choices":[{"message":{"role":"assistant","content":"$content"}}],"usage":{"prompt_tokens":5,"completion_tokens":3}}"""
        }
        val chatId = bareChat(llmSessionId = null, endpoint = endpoint)
        chats.save(requireNotNull(chats.findByIdOrNull(chatId)).apply { title = "New chat" })

        val response = org.springframework.mock.web.MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest("How big is the disk?"), response).writeTo(response.outputStream)

        val frames = response.contentAsString
        assertThat(frames).contains("event: titling", "event: titled", "\"title\":\"Disk usage check\"")
        assertThat(frames.indexOf("event: titling")).isLessThan(frames.indexOf("event: titled"))

        // A chat that already has a name is not named again, and says nothing about it.
        val again = org.springframework.mock.web.MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest("And the logs?"), again).writeTo(again.outputStream)
        assertThat(again.contentAsString).doesNotContain("event: titling")
    }

    @Test
    fun `a chat with no agent opens no session even after it is used`() {
        val chatId = bareChat(llmSessionId = null, endpoint = serveAnswer("Hello."))

        send(chatId, "Anyone there?")
        send(chatId, "Still there?")

        assertThat(chats.findAll().single().llmSessionId).isNull()
        assertThat(sessions.findAll()).isEmpty()
    }

    private fun session(prefix: String, key: String): Long = recorder.open(workspaceId, prefix, key)

    /**
     * @param agentId who answers, or absent for whichever agent the workspace
     *   would hand this person. Where a model used to be named: the door that
     *   opened a chat on a bare model is gone, and what is left names an agent
     *   or names nobody.
     */
    private fun startChat(llmSessionId: Long?, agentId: Long? = null): Long {
        val named = llmSessionId?.let { ", llmSessionId: $it" }.orEmpty()
        val talking = agentId?.let { ", agentId: $it" }.orEmpty()
        return graphQlTester.document(
            """mutation { startChat(input: {
                 workspaceId: $workspaceId, title: "Continuing"$talking$named
               }) { id llmSessionId } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
    }

    /**
     * A chat on a bare model, opened on an agent and then stripped of it.
     *
     * Nothing starts one of these any more: a chat is opened on an agent or it
     * is not opened, and a bare model is not offered beside them (issue #295).
     * The chats opened before that door shut are still there, and they still
     * open, still render and still answer - which is exactly what the readings
     * using this are about, so the one thing they may not do is start their
     * chat the way the screen would today. The model the agent supplied stays
     * on the row, so what is left is that chat and not a broken one.
     *
     * Opened and then stripped rather than written as a row, because one of
     * them continues a session: what was already said is copied into a chat's
     * thread as it opens, and a row put there by hand would be a chat that
     * carried nothing pretending it had.
     */
    private fun bareChat(llmSessionId: Long?, endpoint: String): Long {
        val chatId = startChat(llmSessionId, agent("Opener", model(endpoint)))
        val chat = requireNotNull(chats.findByIdOrNull(chatId))
        chat.agentId = null
        chats.save(chat)
        return chatId
    }

    private fun send(chatId: Long, text: String) {
        graphQlTester.document(
            """mutation(${'$'}text: String!) {
                 sendChatMessage(id: $chatId, text: ${'$'}text) { answer { content } }
               }""",
        ).variable("text", text).execute().path("sendChatMessage.answer.content").hasValue()
    }

    private fun agent(name: String, modelId: Long, granted: String? = null): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        val grant = granted?.let { """, skillCatalogs: ["$it"]""" }.orEmpty()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId$grant }) { id } }""",
        ).execute()
        return id
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

    /**
     * @param name what the provider and the model are both called. A name is
     *   unique within a workspace, so the fixture's idle model cannot share one
     *   with a model that is being served - and the default is the name a bare
     *   model's answer is written into the session under, which one of the
     *   readings above reads back.
     */
    private fun model(endpoint: String, name: String = "Stub"): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    /** Answers in one round, and keeps what it was asked so the turns can be read. */
    private fun serveAnswer(said: String): String = serve {
        """
        {"choices":[{"message":{"role":"assistant","content":"$said"}}],
         "usage":{"prompt_tokens":11,"completion_tokens":6}}
        """.trimIndent()
    }

    /** Asks for a skill first, then answers with what it read. */
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
     * Thinks, asks for a skill, then thinks again and answers - two rounds,
     * each with thinking of its own. As frames where it is asked to stream,
     * which is the round a page is watching and the one whose thinking is
     * written into the session as it happens; as one message where it is not.
     */
    private fun serveThinkingToolThenThinkingAnswer(): String = serve { body ->
        val second = body.contains("tool_call_id") && body.lastIndexOf("tool_call_id") > body.lastIndexOf("\"role\":\"user\"")
        val streamed = body.replace(" ", "").contains("\"stream\":true")
        val blank = "\n\n"
        when {
            streamed && second -> buildString {
                append("""data: {"choices":[{"delta":{"reasoning_content":"The skill says twice."}}]}""").append(blank)
                append("""data: {"choices":[{"delta":{"content":"Read the diff twice."}}]}""").append(blank)
                append("""data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":9,"completion_tokens":4}}""").append(blank)
                append("data: [DONE]").append(blank)
            }
            streamed -> buildString {
                append("""data: {"choices":[{"delta":{"reasoning_content":"Which skill holds it?"}}]}""").append(blank)
                append(
                    """data: {"choices":[{"delta":{"tool_calls":[""" +
                        """{"index":0,"id":"call_1","type":"function","function":{"name":"skill_load","arguments":""}}]}}]}""",
                ).append(blank)
                append(
                    """data: {"choices":[{"delta":{"tool_calls":[""" +
                        """{"index":0,"function":{"arguments":"{\"name\":\"codeReview\"}"}}]}}]}""",
                ).append(blank)
                append("""data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}""").append(blank)
                append("data: [DONE]").append(blank)
            }
            second ->
                """{"choices":[{"message":{"role":"assistant","reasoning_content":"The skill says twice.","content":"Read the diff twice."}}],
                   "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
            else ->
                """
                {"choices":[{"message":{"role":"assistant","reasoning_content":"Which skill holds it?","content":null,"tool_calls":[
                  {"id":"call_1","type":"function",
                   "function":{"name":"skill_load","arguments":"{\"name\":\"codeReview\"}"}}
                ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
                """.trimIndent()
        }
    }

    /** Sends through the streaming door - the one with a page watching, which writes each round's thinking as it happens. */
    private fun stream(chatId: Long, text: String) {
        val response = org.springframework.mock.web.MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest(text), response).writeTo(response.outputStream)
        assertThat(response.contentAsString).contains("event: done")
    }

    /** The same round, with a word about it in the reply that asks for the skill. */
    private fun serveRemarkingThenToolThenAnswer(): String = serve { body ->
        if (body.contains("tool_call_id")) {
            """{"choices":[{"message":{"role":"assistant","content":"Read the diff twice."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            """
            {"choices":[{"message":{"role":"assistant","content":"Let me read the review skill first.",
             "tool_calls":[
              {"id":"call_1","type":"function",
               "function":{"name":"skill_load","arguments":"{\"name\":\"codeReview\"}"}}
            ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
            """.trimIndent()
        }
    }

    private fun serve(answer: (String) -> String): String {
        val opened = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server = opened
        opened.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val said = answer(body)
            val bytes = said.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", if (said.startsWith("data:")) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        opened.start()
        return "http://${opened.address.hostString}:${opened.address.port}"
    }
}
