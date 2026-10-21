package io.mszymanski.orknux.server.rating

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.chat.SessionSheds
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.usage.PromptSource
import io.mszymanski.orknux.server.usage.Prompter
import io.mszymanski.orknux.server.usage.Prompting
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

/**
 * Admin > Reports > Ratings: a review a person wrote, sent on by the agent
 * through rate_session - offered only while an administrator has ratings on,
 * booked to whoever is prompting, held to the reviews a conversation may send,
 * and read back with its average and counts.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionRatingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sheds: SessionSheds,
    @Autowired val mapper: ObjectMapper,
    @Autowired val chats: io.mszymanski.orknux.server.chat.ChatSessionRepository,
) {

    private var workspaceId: Long = 0
    private var session: Long = 0
    private val agent = Agent(workspaceId = 0, name = "Helper", type = AgentType.LLM)

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "rating-${System.nanoTime()}")).id)
        session = recorder.open(workspaceId, "test", "rating-${System.nanoTime()}")
    }

    @AfterEach
    fun off() {
        ratings(false)
        graphQlTester.document("mutation { setChatReviews(enabled: false) { enabled } }").execute()
        graphQlTester.document("mutation { setReviewsPerSession(count: 3) { reviewsPerSession } }").execute()
    }

    private fun ratings(enabled: Boolean) {
        graphQlTester.document("mutation { setSessionRatings(enabled: $enabled) { enabled } }").execute()
            .path("setSessionRatings.enabled").entity(Boolean::class.java).isEqualTo(enabled)
    }

    private fun offered(): List<String> = sheds.of(agent, session)?.specs().orEmpty().map { it.name }

    private fun send(arguments: Map<String, Any?>): Map<*, *> = Prompting.by(Prompter(PromptSource.ORKNUX, "alice", name = "Alice")) {
        val shed = requireNotNull(sheds.of(agent, session))
        mapper.readValue(shed.run(ToolCall("c", RatingTools.RATE, mapper.writeValueAsString(arguments))), Map::class.java)
    }

    @Test
    fun `the tool is offered only while ratings are on, and tells the agent when to mention it`() {
        assertThat(BuiltInTools.GRANTED).contains(RatingTools.RATE)
        graphQlTester.document("query { sessionRatingsEnabled { enabled } }").execute()
            .path("sessionRatingsEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)
        assertThat(offered()).doesNotContain(RatingTools.RATE)

        ratings(true)
        assertThat(offered()).contains(RatingTools.RATE)
        assertThat(sheds.of(agent, session)?.briefing()).contains("unhappy or disappointed", RatingTools.RATE)
    }

    @Test
    fun `a review is booked to whoever is prompting, and read back with the average`() {
        ratings(true)
        assertThat(send(mapOf("review" to "The second answer was wrong", "score" to 2))["sent"]).isEqualTo(true)
        assertThat(send(mapOf("review" to "Better now", "score" to "5"))["sent"]).isEqualTo(true)
        assertThat(send(mapOf("score" to 3))["sent"]).isEqualTo(true)
        // Every review has a score: one without is sent back to ask the person for it.
        assertThat(send(mapOf("review" to "No number this time"))["error"] as String).contains("ask the person")

        val read = graphQlTester.document(
            """query { sessionRatings(filter: { workspaceId: $workspaceId }) {
                 total average counts rows { score review agentName source personKey personName sessionId }
               } }""",
        ).execute()
        read.path("sessionRatings.total").entity(Int::class.java).isEqualTo(3)
        read.path("sessionRatings.average").entity(Double::class.java).isEqualTo(10.0 / 3)
        read.path("sessionRatings.counts").entityList(Int::class.java).containsExactly(0, 1, 1, 0, 1)
        // Newest first, and a score alone is a review with no words.
        read.path("sessionRatings.rows[0].score").entity(Int::class.java).isEqualTo(3)
        read.path("sessionRatings.rows[0].review").valueIsNull()
        read.path("sessionRatings.rows[1].agentName").entity(String::class.java).isEqualTo("Helper")
        read.path("sessionRatings.rows[1].personName").entity(String::class.java).isEqualTo("Alice")
        read.path("sessionRatings.rows[1].sessionId").entity(Long::class.java).isEqualTo(session)

        graphQlTester.document("""query { sessionRatings(filter: { workspaceId: $workspaceId }, order: "SCORE", ascending: false) { rows { score } } }""")
            .execute().path("sessionRatings.rows[0].score").entity(Int::class.java).isEqualTo(5)
        graphQlTester.document("""query { sessionRatings(filter: { workspaceId: $workspaceId, search: "wrong" }) { total } }""")
            .execute().path("sessionRatings.total").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    fun `a conversation sends as many reviews as Admin Settings allows, three unless changed`() {
        ratings(true)
        graphQlTester.document("query { installationSettings { reviewsPerSession reviewsPerSessionConfigured } }").execute()
            .path("installationSettings.reviewsPerSession").entity(Int::class.java).isEqualTo(3)
            .path("installationSettings.reviewsPerSessionConfigured").entity(Int::class.java).isEqualTo(3)

        graphQlTester.document("mutation { setReviewsPerSession(count: 1) { reviewsPerSession } }").execute()
            .path("setReviewsPerSession.reviewsPerSession").entity(Int::class.java).isEqualTo(1)
        assertThat(send(mapOf("review" to "Fine", "score" to 4))["sent"]).isEqualTo(true)
        assertThat(send(mapOf("review" to "Again", "score" to 1))["error"] as String).contains("already sent 1")

        graphQlTester.document("mutation { setReviewsPerSession(count: 0) { reviewsPerSession } }").execute()
            .errors().expect { it.extensions["code"] == "ReviewsPerSessionOutOfRange" }
    }

    @Test
    fun `a score outside one to five is refused`() {
        ratings(true)
        assertThat(send(mapOf("review" to "Off the scale", "score" to 9))["error"] as String).contains("from 1 to 5")
    }

    @Test
    fun `a person reviews their own chat from its menu, booked to them and the chat's agent`() {
        val chat = requireNotNull(
            chats.save(
                io.mszymanski.orknux.server.chat.ChatSession(
                    workspaceId = workspaceId,
                    conversationId = java.util.UUID.randomUUID().toString(),
                    title = "Reviewed",
                    userId = "alice",
                ).apply { llmSessionId = session },
            ).id,
        )
        val review = """mutation { sendChatReview(chatId: $chat, score: 1, review: "It ignored the file") }"""
        graphQlTester.document("query { sessionReviewsOffered }").execute()
            .path("sessionReviewsOffered").entity(Boolean::class.java).isEqualTo(false)
        graphQlTester.document(review).execute().errors().expect { it.message?.contains("Chat reviews are switched off") == true }

        // The menu is its own door: the agents' tool being on does not open it.
        ratings(true)
        graphQlTester.document(review).execute().errors().expect { it.message?.contains("Chat reviews are switched off") == true }
        graphQlTester.document("mutation { setChatReviews(enabled: true) { enabled } }").execute()
            .path("setChatReviews.enabled").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("query { sessionReviewsOffered }").execute()
            .path("sessionReviewsOffered").entity(Boolean::class.java).isEqualTo(true)
        ratings(false)
        graphQlTester.document(review).execute().path("sendChatReview").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("""mutation { sendChatReview(chatId: $chat, score: 0) }""").execute()
            .errors().expect { it.message?.contains("from 1 to 5") == true }

        graphQlTester.document("""query { sessionRatings(filter: { workspaceId: $workspaceId }) { rows { score review source personKey sessionId } } }""")
            .execute()
            .path("sessionRatings.rows[0].score").entity(Int::class.java).isEqualTo(1)
            .path("sessionRatings.rows[0].review").entity(String::class.java).isEqualTo("It ignored the file")
            .path("sessionRatings.rows[0].source").entity(String::class.java).isEqualTo("ORKNUX")
            .path("sessionRatings.rows[0].personKey").entity(String::class.java).isEqualTo("alice")
            .path("sessionRatings.rows[0].sessionId").entity(Long::class.java).isEqualTo(session)
    }
}
