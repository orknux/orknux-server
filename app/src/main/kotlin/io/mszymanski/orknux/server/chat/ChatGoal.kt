package io.mszymanski.orknux.server.chat

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/** Where a goal stands. */
enum class ChatGoalStatus {
    /** Being worked on: a turn, then a review, until one of the three below. */
    RUNNING,

    /** The reviewer judged the condition met. */
    MET,

    /** A person stopped it - `/goal stop`, Stop, or a new goal in its place - or its reviewer could not answer. */
    STOPPED,

    /** Every turn it was given was used and the condition was still not met. */
    EXHAUSTED,
}

/**
 * A goal: an agent keeps working on [goal] until a reviewer judges [condition]
 * met, at most [maxIterations] turns. See [ChatGoals] for the loop.
 *
 * Kept on the conversation by [llmSessionId], with the chat beside it for the
 * screen that asks by chat; one row per goal, so the chat's history of what it
 * was set to do survives the next one.
 */
@Entity
@Table(name = "chat_goal")
class ChatGoal(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "llm_session_id", nullable = false)
    val llmSessionId: Long,

    @Column(name = "chat_id")
    val chatId: Long? = null,

    /** What to achieve, in the words it was set in. */
    @Column(nullable = false, columnDefinition = "text")
    val goal: String,

    /** When it counts as achieved, in words; what the reviewer judges. */
    @Column(nullable = false, columnDefinition = "text")
    val condition: String,

    @Column(name = "max_iterations", nullable = false)
    val maxIterations: Int,

    /** The turn being worked on, or the last one judged; one from the start. */
    @Column(nullable = false)
    var iteration: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ChatGoalStatus = ChatGoalStatus.RUNNING,

    /** What the reviewer said last, met or not. */
    @Column(name = "last_feedback", columnDefinition = "text")
    var lastFeedback: String? = null,

    @Column(name = "reviewer_model_id", nullable = false)
    val reviewerModelId: Long,

    /** What the person or the agent added to the reviewer's standing instructions. */
    @Column(name = "reviewer_instructions", columnDefinition = "text")
    val reviewerInstructions: String? = null,

    /** The temporary agent that reviews, while the goal runs; dismissed when it ends. */
    @Column(name = "reviewer_agent_id")
    var reviewerAgentId: Long? = null,

    @Column(name = "started_by", nullable = false, length = 200)
    val startedBy: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ChatGoalRepository : JpaRepository<ChatGoal, Long> {

    /** The conversation's newest goal, whatever became of it. */
    fun findFirstByLlmSessionIdOrderByIdDesc(llmSessionId: Long): ChatGoal?

    /** The chat's newest goal, whatever became of it. */
    fun findFirstByChatIdOrderByIdDesc(chatId: Long): ChatGoal?
}
