package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.chat.ChatSession
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.graphql.execution.ErrorType
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * A chat's session is its owner's and an installation administrator's, and to
 * everybody else who sees the workspace it is not there - through every door
 * that reads a session. Every other session stays the workspace's.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class SessionPrivacyTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val chats: ChatSessionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val pads: SessionScratchpadService,
    @Autowired val csv: SessionTranscriptCsvAPI,
    @Autowired val tools: OrknuxTools,
    @Autowired val visibility: SessionVisibility,
    @Autowired val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId = 0L
    private lateinit var alice: String
    private lateinit var bob: String
    private var chat = 0L
    private var asked = 0L
    private var bobsChat = 0L
    private var ticket = 0L
    private var other = 0L

    @BeforeEach
    fun arrange() {
        val n = System.nanoTime()
        val crew = roles.save(Role(name = "privacy-crew-$n"))
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "privacy-$n", roles = mutableSetOf(crew))).id)
        alice = users.save(AppUser(username = "privacy-alice-$n", displayName = "Alice", type = UserType.INTERNAL, roles = mutableSetOf(crew))).username
        bob = users.save(AppUser(username = "privacy-bob-$n", displayName = "Bob", type = UserType.INTERNAL, roles = mutableSetOf(crew))).username

        chat = chatOf(alice, "what alice asked")
        asked = recorder.openUnder(chat, "look it up")
        recorder.agentSaid(asked, "Helper", "what the helper found")
        pads.create(chat, "plan", null, "alice's plan")
        bobsChat = chatOf(bob, "what bob asked")

        ticket = recorder.open(workspaceId, "ticket", "t-$n")
        recorder.userSaid(ticket, "Desk", "the ticket's question")
        recorder.agentSaid(ticket, "Agent", "the ticket's answer")
        other = recorder.open(workspaceId, "ticket", "u-$n")
        recorder.agentSaid(other, "Agent", "another ticket")
    }

    @AfterEach
    fun leave() = SecurityContextHolder.clearContext()

    private fun chatOf(owner: String, said: String): Long {
        val conversation = UUID.randomUUID().toString()
        val row = chats.save(ChatSession(workspaceId = workspaceId, conversationId = conversation, title = "chat", userId = owner))
        val session = recorder.open(workspaceId, "chat", conversation)
        row.llmSessionId = session
        chats.save(row)
        recorder.userSaid(session, owner, said)
        recorder.agentSaid(session, "Agent", "the answer to $owner")
        return session
    }

    private fun signIn(name: String, admin: Boolean = false) {
        val authorities = if (admin) listOf(SimpleGrantedAuthority("ROLE_ADMINS")) else emptyList()
        SecurityContextHolder.setContext(SecurityContextImpl(UsernamePasswordAuthenticationToken(name, "n/a", authorities)))
    }

    private fun listed(subagents: Boolean = false, page: Int = 0, size: Int = 20): Pair<Int, List<Long>> {
        val path = graphQlTester.document(
            """
            query(${'$'}w: ID!, ${'$'}s: Boolean, ${'$'}p: Int, ${'$'}z: Int) {
              llmSessions(workspaceId: ${'$'}w, includeSubagents: ${'$'}s, page: ${'$'}p, size: ${'$'}z) { totalElements content { id } }
            }
            """,
        ).variable("w", workspaceId).variable("s", subagents).variable("p", page).variable("z", size).execute()
        val total = path.path("llmSessions.totalElements").entity(Int::class.java).get()
        val ids = path.path("llmSessions.content[*].id").entityList(String::class.java).get().map { it.toLong() }
        return total to ids
    }

    private fun session(id: Long): Map<*, *>? =
        graphQlTester.document("query(${'$'}id: ID!) { llmSession(id: ${'$'}id) { id eventCount } }")
            .variable("id", id).execute()
            .path("llmSession").entity(Map::class.java).get()

    private fun notFound(document: String, id: Long) {
        graphQlTester.document(document).variable("id", id).execute().errors().satisfy { errors ->
            assertThat(errors).hasSize(1)
            assertThat(errors.single().errorType).isEqualTo(ErrorType.NOT_FOUND)
            assertThat(errors.single().extensions["code"]).isEqualTo("LlmSessionNotFound")
        }
    }

    private fun events(id: Long): Int =
        graphQlTester.document("query(${'$'}id: ID!) { llmSessionEvents(sessionId: ${'$'}id) { totalElements } }")
            .variable("id", id).execute()
            .path("llmSessionEvents.totalElements").entity(Int::class.java).get()

    @Test
    fun `the owner lists and opens their chat's session, and the sessions asked from it`() {
        signIn(alice)

        val (total, ids) = listed()
        assertThat(ids).contains(chat, ticket, other).doesNotContain(bobsChat, asked)
        assertThat(total).isEqualTo(3)
        assertThat(listed(subagents = true).second).containsExactlyInAnyOrder(chat, asked, ticket, other)

        assertThat(session(chat)).isNotNull
        assertThat(events(chat)).isEqualTo(2)
        assertThat(session(asked)).isNotNull
        assertThat(String(requireNotNull(csv.download(chat, null, null).body), Charsets.UTF_8)).contains("what alice asked")
        val family = graphQlTester.document("query(${'$'}id: ID!) { llmSessionFamily(id: ${'$'}id) { id } }")
            .variable("id", chat).execute().path("llmSessionFamily[*].id").entityList(String::class.java).get()
        assertThat(family.map { it.toLong() }).containsExactly(chat, asked)
        val scratch = graphQlTester.document("query(${'$'}id: ID!) { sessionScratchpads(sessionId: ${'$'}id) { name } }")
            .variable("id", chat).execute().path("sessionScratchpads[*].name").entityList(String::class.java).get()
        assertThat(scratch).containsExactly("plan")
    }

    @Test
    fun `another member of the workspace neither lists, counts nor opens it, by any door`() {
        signIn(bob)

        val (total, ids) = listed()
        assertThat(ids).containsExactlyInAnyOrder(bobsChat, ticket, other)
        assertThat(total).isEqualTo(3)
        assertThat(listed(subagents = true).first).isEqualTo(3)
        // Paged in the database, so every page agrees with the total and none of them is short.
        val pages = (0..2).map { listed(size = 1, page = it) }
        assertThat(pages.map { it.first }).containsOnly(3)
        assertThat(pages.flatMap { it.second }).containsExactlyInAnyOrder(bobsChat, ticket, other)
        assertThat(listed(size = 1, page = 3).second).isEmpty()

        graphQlTester.document("query(${'$'}id: ID!) { llmSession(id: ${'$'}id) { id } }")
            .variable("id", chat).execute().path("llmSession").valueIsNull()
        graphQlTester.document("query(${'$'}id: ID!) { llmSession(id: ${'$'}id) { id } }")
            .variable("id", asked).execute().path("llmSession").valueIsNull()
        graphQlTester.document("query(${'$'}id: ID!) { llmSessionWriting(id: ${'$'}id) { chars } }")
            .variable("id", chat).execute().path("llmSessionWriting").valueIsNull()
        notFound("query(${'$'}id: ID!) { llmSessionEvents(sessionId: ${'$'}id) { totalElements } }", chat)
        notFound("query(${'$'}id: ID!) { llmSessionFamily(id: ${'$'}id) { id } }", chat)
        notFound("query(${'$'}id: ID!) { llmSessionFamily(id: ${'$'}id) { id } }", asked)
        notFound("query(${'$'}id: ID!) { sessionScratchpads(sessionId: ${'$'}id) { name } }", chat)
        notFound("query(${'$'}id: ID!) { sessionScratchpad(sessionId: ${'$'}id, name: \"plan\") { content } }", chat)
        notFound("query(${'$'}id: ID!) { sessionExecutions(sessionId: ${'$'}id) { id } }", chat)
        notFound("query(${'$'}id: ID!) { sessionTodos(sessionId: ${'$'}id) { text } }", chat)
        notFound("query(${'$'}id: ID!) { sessionTimers(sessionId: ${'$'}id) { note } }", chat)
        // The same answer as an id nothing was saved under.
        notFound("query(${'$'}id: ID!) { llmSessionEvents(sessionId: ${'$'}id) { totalElements } }", Long.MAX_VALUE)
        assertThat(csv.download(chat, null, null).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(csv.download(asked, null, null).statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        assumeTrue(settings.sessionsRemovable())
        graphQlTester.document("mutation(${'$'}id: ID!) { removeLlmSession(id: ${'$'}id) }")
            .variable("id", chat).execute().path("removeLlmSession").entity(Boolean::class.java).isEqualTo(false)
        assertThat(sessions.existsById(chat)).isTrue()
    }

    @Test
    fun `every member still reads the workspace's other sessions`() {
        signIn(bob)

        assertThat(session(ticket)).isNotNull
        assertThat(events(ticket)).isEqualTo(2)
        assertThat(String(requireNotNull(csv.download(ticket, null, null).body), Charsets.UTF_8)).contains("the ticket's answer")
    }

    @Test
    fun `an installation administrator sees and opens every session`() {
        signIn("privacy-root", admin = true)

        assertThat(listed().second).containsExactlyInAnyOrder(chat, bobsChat, ticket, other)
        assertThat(listed().first).isEqualTo(4)
        assertThat(listed(subagents = true).second).containsExactlyInAnyOrder(chat, asked, bobsChat, ticket, other)
        assertThat(session(chat)).isNotNull
        assertThat(events(chat)).isEqualTo(2)
        assertThat(csv.download(chat, null, null).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `the orknux tools answer a person's token by the same rule`() {
        signIn(bob)
        val scope = OrknuxScope(workspaceId = workspaceId)

        val list = mapper.readTree(tools.run(scope, "orknux_sessions", "{}"))
        assertThat(list.path("total").asLong()).isEqualTo(3)
        assertThat(list.path("sessions").let { all -> (0 until all.size()).map { all.get(it).path("id").asLong() } }).doesNotContain(chat)
        assertThat(tools.run(scope, "orknux_session", """{"id":$chat}""")).contains("There is no conversation $chat here")
        assertThat(tools.run(scope, "orknux_session", """{"id":$ticket}""")).contains("the ticket's answer")

        signIn(alice)
        assertThat(tools.run(scope, "orknux_session", """{"id":$chat}""")).contains("what alice asked")
    }

    @Test
    fun `an agent with nobody behind it reads the chat it works in, and no other`() {
        SecurityContextHolder.clearContext()

        val inChat = OrknuxScope(workspaceId = workspaceId, session = chat)
        assertThat(tools.run(inChat, "orknux_session", "{}")).contains("what alice asked")
        assertThat(tools.run(inChat, "orknux_session", """{"id":$bobsChat}""")).contains("There is no conversation $bobsChat here")
        val listedInChat = mapper.readTree(tools.run(inChat, "orknux_sessions", "{}"))
        assertThat(listedInChat.path("sessions").let { all -> (0 until all.size()).map { all.get(it).path("id").asLong() } }).contains(chat).doesNotContain(bobsChat)
        // The session it asked from the chat is the chat's too.
        val helper = OrknuxScope(workspaceId = workspaceId, session = asked)
        assertThat(tools.run(helper, "orknux_session", """{"id":$chat}""")).contains("what alice asked")

        val inWorkflow = OrknuxScope(workspaceId = workspaceId, session = ticket)
        assertThat(tools.run(inWorkflow, "orknux_session", """{"id":$chat}""")).contains("There is no conversation $chat here")
        val listedInWorkflow = mapper.readTree(tools.run(inWorkflow, "orknux_sessions", "{}"))
        assertThat(listedInWorkflow.path("total").asLong()).isEqualTo(2)
    }

    @Test
    fun `the query and the rule in code give the same answer for every reader`() {
        val everyone = listOf(chat, asked, bobsChat, ticket, other).map { requireNotNull(sessions.findById(it).orElse(null)) }
        val readers = listOf(
            SessionReader(everyone = false, person = alice, chat = ""),
            SessionReader(everyone = false, person = bob, chat = ""),
            SessionReader(everyone = false, person = "", chat = ""),
            SessionReader(everyone = false, person = "", chat = requireNotNull(SessionVisibility.chatOf(sessions.findById(chat).get().sessionKey))),
            SessionReader(everyone = true, person = "", chat = ""),
        )
        readers.forEach { reader ->
            val queried = sessions.search(workspaceId, "", true, reader.everyone, reader.person, reader.chat, PageRequest.of(0, 50, Sort.by("id")))
                .content.mapNotNull { it.id }
            val ruled = everyone.filter { visibility.mayRead(it, reader) }.mapNotNull { it.id }
            assertThat(queried).describedAs("as %s", reader).containsExactlyInAnyOrderElementsOf(ruled)
        }
    }

    @Test
    fun `a session key shaped like a chat's is told apart from one that only starts with chat`() {
        assertThat(SessionVisibility.chatOf("chat:${UUID.randomUUID()}")).isNotNull
        val conversation = UUID.randomUUID().toString()
        assertThat(SessionVisibility.chatOf("chat:$conversation:ask-1")).isEqualTo("chat:$conversation")
        assertThat(SessionVisibility.chatOf("chat:standup")).isNull()
        assertThat(SessionVisibility.chatOf("ticket:$conversation")).isNull()
    }
}
