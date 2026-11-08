package io.mszymanski.orknux.server.chat

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/**
 * A chat's goal, for the person: `/goal` in the composer is these three, and
 * Orknux Shell will be too. See [ChatGoals] for what a goal does.
 *
 * Each checks the chat is the asker's own before anything else, as every door
 * onto a chat does; [ChatGoals] writes the audit entries, since the agent's
 * `goal_start` sets one through the same call.
 */
@Controller
class ChatGoalAPI(
    private val goals: ChatGoals,
    private val chats: ChatService,
    private val ownership: ChatOwnership,
) {

    @QueryMapping
    fun chatGoal(@Argument chatId: Long): ChatGoalView? {
        own(chatId)
        return goals.current(chatId)?.let(::ChatGoalView)
    }

    @MutationMapping
    fun startChatGoal(@Argument chatId: Long, @Argument input: StartChatGoalInput): ChatGoalView {
        own(chatId)
        return ChatGoalView(
            goals.start(
                chatId,
                GoalRequest(
                    goal = input.goal,
                    condition = input.condition,
                    maxIterations = input.maxIterations,
                    reviewerModelId = input.reviewerModelId,
                    reviewerInstructions = input.reviewerInstructions,
                ),
                currentUser(),
            ),
        )
    }

    /** Stops the running goal; answers the goal as it now stands, or null where the chat never had one. */
    @MutationMapping
    fun stopChatGoal(@Argument chatId: Long): ChatGoalView? {
        own(chatId)
        return goals.stop(chatId, currentUser())?.let(::ChatGoalView)
    }

    private fun own(chatId: Long) {
        val session = chats.session(chatId) ?: throw ChatSessionNotFoundException(chatId)
        ownership.requireOwn(session)
    }

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
}

data class StartChatGoalInput(
    val goal: String,
    val condition: String,
    val maxIterations: Int? = null,
    val reviewerModelId: Long? = null,
    val reviewerInstructions: String? = null,
)

data class ChatGoalView(
    val id: Long,
    val goal: String,
    val condition: String,
    val iteration: Int,
    val maxIterations: Int,
    val status: ChatGoalStatus,
    val lastFeedback: String?,
    val reviewerModelId: Long,
    val startedBy: String,
) {
    constructor(goal: ChatGoal) : this(
        id = requireNotNull(goal.id),
        goal = goal.goal,
        condition = goal.condition,
        iteration = goal.iteration,
        maxIterations = goal.maxIterations,
        status = goal.status,
        lastFeedback = goal.lastFeedback,
        reviewerModelId = goal.reviewerModelId,
        startedBy = goal.startedBy,
    )
}

@Component
class ChatGoalExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val type = when (exception) {
            is ChatGoalRefusedException -> ErrorType.BAD_REQUEST
            else -> return null
        }
        return refused(exception, type, environment)
    }
}
