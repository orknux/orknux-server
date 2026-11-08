package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.TemporaryAgents
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.server.llm.SessionScratchpadService
import io.mszymanski.orknux.server.llm.SessionSpend
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.messages.MessageType
import org.springframework.context.annotation.Lazy
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** What a goal is set with, from `/goal`, `startChatGoal` or `goal_start`. */
data class GoalRequest(
    val goal: String,
    val condition: String,
    val maxIterations: Int? = null,
    val reviewerModelId: Long? = null,
    val reviewerInstructions: String? = null,
)

/**
 * Goals: a chat's agent keeps working on something until a stated condition is
 * met, with a reviewer judging each turn.
 *
 * The loop is the chat's own machinery and nothing beside it. A goal is a row;
 * each turn the agent takes in that chat is an iteration; when the turn ends -
 * [ChatGenerations.whenIdle], the hook [ChatWake] already hangs off - the
 * reviewer, a temporary agent holding no tools, is handed the goal, the
 * condition, what the agent answered and the conversation's scratchpads, and
 * answers `{met, feedback}`. Not met with turns left, the feedback is left in
 * the session's inbox, which wakes the chat for the next turn the way an asked
 * agent's answer does - "What arrives at a session goes through its inbox". Met,
 * out of turns, or stopped, the reviewer is dismissed and the row says why.
 *
 * What the agent is told goes in that inbox message, not in the system turn:
 * the system turn and the tool array are frozen for the session
 * ([io.mszymanski.orknux.server.llm.SessionFrames]), and a briefing that
 * changed every iteration would make a local server re-read the whole prompt
 * every turn. So each iteration's message carries the goal, the condition, the
 * iteration number and the reviewer's last feedback.
 *
 * Every turn is a turn of the chat and counted where any turn is; the
 * reviewer's call is added to the conversation's spend beside it.
 */
@Service
class ChatGoals(
    private val goals: ChatGoalRepository,
    private val chats: ChatSessionRepository,
    @param:Lazy private val chatService: ChatService,
    private val agents: AgentRepository,
    private val temporary: TemporaryAgents,
    private val history: ChatMemoryRepository,
    private val pads: SessionScratchpadService,
    private val models: ModelChatClient,
    private val modelInfo: ModelService,
    private val spend: SessionSpend,
    private val inbox: SessionInbox,
    private val generations: ChatGenerations,
    private val audit: WorkspaceAuditRecorder,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
    transactions: PlatformTransactionManager,
) {

    private val writing = TransactionTemplate(transactions)

    /** Goals a review is running for, so two turn-ends do not judge one turn twice. */
    private val reviewing = ConcurrentHashMap.newKeySet<Long>()

    /** Off the thread that released the turn: a review is a model call. */
    private val reviews: ExecutorService = Executors.newCachedThreadPool { Thread(it, "chat-goal").apply { isDaemon = true } }

    init {
        generations.whenIdle { chatId -> turnEnded(chatId) }
    }

    /** The chat's newest goal, whatever became of it, or null where it never had one. */
    fun current(chatId: Long): ChatGoal? = goals.findFirstByChatIdOrderByIdDesc(chatId)

    /**
     * Sets a goal on [chatId], replacing - stopping - one still running.
     *
     * @param working true where the agent set it from inside a turn
     *   (`goal_start`): that turn is the first iteration, and nothing is woken.
     *   From the person, the chat is woken for the first.
     */
    fun start(chatId: Long, asked: GoalRequest, by: String, working: Boolean = false): ChatGoal {
        val chat = chats.findByIdOrNull(chatId) ?: throw ChatSessionNotFoundException(chatId)
        val agent = chat.agentId?.let { agents.findByIdOrNull(it) }
            ?: throw ChatGoalNoAgentException()
        val goal = asked.goal.trim()
        val condition = asked.condition.trim()
        if (goal.isEmpty()) throw ChatGoalEmptyException()
        if (condition.isEmpty()) {
            throw ChatGoalConditionMissingException()
        }
        val most = tuning[Tunable.GOAL_ITERATIONS_MOST]
        val turns = asked.maxIterations ?: minOf(tuning[Tunable.GOAL_ITERATIONS_DEFAULT], most)
        if (turns !in 1..most) throw ChatGoalTurnsOutOfRangeException(most, turns)
        val reviewerModel = reviewerModel(agent, asked.reviewerModelId)
        val session = chatService.goalSession(chatId)
            ?: throw ChatGoalNoAgentException()

        current(chatId)?.takeIf { it.status == ChatGoalStatus.RUNNING }?.let { end(it, ChatGoalStatus.STOPPED, "Replaced by a new goal.", by) }

        val made = writing.execute {
            goals.save(
                ChatGoal(
                    workspaceId = chat.workspaceId,
                    llmSessionId = session,
                    chatId = chatId,
                    goal = goal,
                    condition = condition,
                    maxIterations = turns,
                    iteration = 1,
                    reviewerModelId = reviewerModel,
                    reviewerInstructions = asked.reviewerInstructions?.trim()?.ifEmpty { null },
                    startedBy = by,
                ),
            )
        }!!
        val reviewer = temporary.createReviewer(
            agent,
            session,
            "Reviewer of goal ${made.id}",
            reviewerModel,
            REVIEWER_INSTRUCTIONS + (made.reviewerInstructions?.let { "\n\n$it" } ?: ""),
        )
        val held = writing.execute {
            goals.findByIdOrNull(requireNotNull(made.id))!!.also {
                it.reviewerAgentId = reviewer.id
                goals.save(it)
            }
        }!!
        audit.record(chat.workspaceId, WorkspaceAuditCategory.CHAT, "Goal set in chat ${chat.title}: $goal")
        if (!working) inbox.post(session, SessionEventKind.ANSWER, briefing(held, null))
        return held
    }

    /** Stops the chat's running goal, if it has one; answers it as it now stands, or null where there was none. */
    fun stop(chatId: Long, by: String): ChatGoal? {
        val running = current(chatId)?.takeIf { it.status == ChatGoalStatus.RUNNING } ?: return current(chatId)
        return end(running, ChatGoalStatus.STOPPED, null, by)
    }

    /** What the agent is told at the start of each iteration: the goal, the condition, where it is, and the last feedback. */
    fun briefing(goal: ChatGoal, feedback: String?): String = buildString {
        append("Goal · iteration ${goal.iteration} of ${goal.maxIterations}.\n")
        append("You are working towards a goal set in this conversation. Work on it now, in this turn; when the turn ends a ")
        append("reviewer judges whether the condition is met, and if it is not you are given its feedback and another turn.\n\n")
        append("Goal: ${goal.goal}\n")
        append("Condition: ${goal.condition}\n")
        if (feedback != null) append("\nThe reviewer judged your last turn: the condition is not met yet.\nFeedback: $feedback\n")
    }

    /** Called whenever a turn on [chatId] ends; reviews it where a goal is running. */
    private fun turnEnded(chatId: Long) {
        val goal = current(chatId)?.takeIf { it.status == ChatGoalStatus.RUNNING } ?: return
        val id = requireNotNull(goal.id)
        if (!reviewing.add(id)) return
        reviews.execute {
            try {
                review(id)
            } catch (failure: Exception) {
                log.warn("Goal {} could not be reviewed: {}", id, failure.message)
                goals.findByIdOrNull(id)?.takeIf { it.status == ChatGoalStatus.RUNNING }
                    ?.let { end(it, ChatGoalStatus.STOPPED, "The reviewer could not judge it: ${failure.message}", "Orknux") }
            } finally {
                reviewing.remove(id)
            }
        }
    }

    private fun review(id: Long) {
        val goal = goals.findByIdOrNull(id)?.takeIf { it.status == ChatGoalStatus.RUNNING } ?: return
        val chat = goal.chatId?.let { chats.findByIdOrNull(it) } ?: return
        val reviewer = goal.reviewerAgentId?.let { agents.findByIdOrNull(it) }
        val chars = tuning[Tunable.GOAL_REVIEW_CHARS]
        val answer = history.findByConversationId(chat.conversationId)
            .lastOrNull { it.messageType == MessageType.ASSISTANT }?.text.orEmpty()
        val files = pads.list(goal.llmSessionId).joinToString("\n\n") { pad ->
            "--- ${pad.name}${pad.description?.let { " ($it)" } ?: ""}\n${pad.content.take(chars)}"
        }
        val turns = listOf(
            ChatTurn("system", reviewer?.systemPrompt ?: REVIEWER_INSTRUCTIONS),
            ChatTurn(
                "user",
                "Goal: ${goal.goal}\nCondition: ${goal.condition}\nIteration ${goal.iteration} of ${goal.maxIterations}.\n\n" +
                    "What the agent answered at the end of this turn:\n${answer.take(chars).ifBlank { "(nothing)" }}\n\n" +
                    (if (files.isBlank()) "It has no files in this conversation.\n\n" else "Its files in this conversation:\n$files\n\n") +
                    VERDICT,
            ),
        )
        val said = models.complete(goal.reviewerModelId, turns)
        runCatching { spend.add(goal.llmSessionId, goal.reviewerModelId, said) }
        val verdict = when (said) {
            is ChatCompletion.Answered -> verdictIn(said.content)
            is ChatCompletion.Failed -> {
                end(goal, ChatGoalStatus.STOPPED, "The reviewer could not answer: ${said.reason}", "Orknux")
                return
            }
            is ChatCompletion.CalledTools -> Verdict(false, "The reviewer asked for a tool instead of judging.")
        }
        when {
            verdict.met -> end(goal, ChatGoalStatus.MET, verdict.feedback, "Orknux")
            goal.iteration >= goal.maxIterations -> end(goal, ChatGoalStatus.EXHAUSTED, verdict.feedback, "Orknux")
            else -> {
                val next = writing.execute {
                    goals.findByIdOrNull(id)?.takeIf { it.status == ChatGoalStatus.RUNNING }?.also {
                        it.iteration += 1
                        it.lastFeedback = verdict.feedback
                        it.updatedAt = OffsetDateTime.now()
                        goals.save(it)
                    }
                } ?: return
                inbox.post(next.llmSessionId, SessionEventKind.ANSWER, briefing(next, verdict.feedback))
            }
        }
    }

    /** Puts a goal to rest: the status, what was last said, and the reviewer dismissed. */
    private fun end(goal: ChatGoal, status: ChatGoalStatus, feedback: String?, by: String): ChatGoal {
        val ended = writing.execute {
            val held = goals.findByIdOrNull(requireNotNull(goal.id))!!
            held.status = status
            if (feedback != null) held.lastFeedback = feedback
            held.updatedAt = OffsetDateTime.now()
            goals.save(held)
        }!!
        ended.reviewerAgentId?.let { runCatching { temporary.remove(it, by) } }
        val chat = ended.chatId?.let { chats.findByIdOrNull(it) }
        audit.recordAutomated(
            ended.workspaceId,
            WorkspaceAuditCategory.CHAT,
            "Goal in chat ${chat?.title ?: ended.chatId} " + when (status) {
                ChatGoalStatus.MET -> "met after ${ended.iteration} of ${ended.maxIterations} turns"
                ChatGoalStatus.EXHAUSTED -> "not met after ${ended.maxIterations} turns"
                ChatGoalStatus.STOPPED -> "stopped by $by"
                ChatGoalStatus.RUNNING -> "running"
            },
            by,
        )
        return ended
    }

    /**
     * The reviewer's model: the one asked for, where the agent may create
     * agents with it; otherwise the agent's own.
     */
    private fun reviewerModel(agent: Agent, asked: Long?): Long {
        if (asked == null) {
            return agent.modelId ?: throw ChatGoalReviewerMissingException(agent.name)
        }
        if (asked != agent.modelId && asked !in agent.creatableModelIds) {
            val allowed = (listOfNotNull(agent.modelId) + agent.creatableModelIds).distinct()
                .mapNotNull { id -> modelInfo.model(id)?.name }
            throw ChatGoalReviewerRefusedException(modelInfo.model(asked)?.name ?: "Model $asked", agent.name, allowed)
        }
        return asked
    }

    /** `{met, feedback}` out of what the reviewer said, read leniently; anything else is not met, in its own words. */
    internal fun verdictIn(said: String): Verdict {
        val start = said.indexOf('{')
        val end = said.lastIndexOf('}')
        val node = if (start >= 0 && end > start) runCatching { mapper.readTree(said.substring(start, end + 1)) }.getOrNull() else null
        if (node == null || !node.has("met")) return Verdict(false, said.trim().ifEmpty { "The reviewer said nothing." })
        val met = node.path("met").let { if (it.isBoolean) it.asBoolean() else it.asString().equals("true", ignoreCase = true) }
        val feedback = node.path("feedback").let { if (it.isString) it.asString() else it.toString() }.trim()
        return Verdict(met, feedback.ifEmpty { if (met) "Met." else "Not met." })
    }

    data class Verdict(val met: Boolean, val feedback: String)

    companion object {
        private val log = LoggerFactory.getLogger(ChatGoals::class.java)

        /** The reviewer's standing instructions; whatever the goal adds comes after. */
        const val REVIEWER_INSTRUCTIONS =
            "You review another agent's work towards a goal. Judge whether the condition is met, strictly: met only " +
                "when what you are shown plainly satisfies it, not when it is close or promised. If it is not met, say " +
                "concretely what is missing or wrong and what to do next. You change nothing yourself."

        private const val VERDICT =
            "Answer with JSON alone: {\"met\": true or false, \"feedback\": \"one or two sentences\"}."
    }
}

/**
 * A goal that cannot be set as asked, said as the reason. One class per
 * reason, so each has a code of its own and the interface can say it in
 * Polish; the English is [message], for an agent's tool answer and the log.
 */
sealed class ChatGoalRefusedException(says: String, override val arguments: Map<String, Any?> = emptyMap()) :
    RuntimeException(says), Refusal

class ChatGoalNoAgentException :
    ChatGoalRefusedException("A goal needs an agent to work on it. Choose one for this chat first.")

class ChatGoalEmptyException : ChatGoalRefusedException("Say what the goal is.")

class ChatGoalConditionMissingException :
    ChatGoalRefusedException("Say when the goal counts as met, after until - that is what the reviewer judges.")

class ChatGoalTurnsOutOfRangeException(most: Int, turns: Int) :
    ChatGoalRefusedException("A goal may take between 1 and $most turns; $turns is not one of them.", mapOf("most" to most, "turns" to turns))

class ChatGoalReviewerMissingException(agent: String) :
    ChatGoalRefusedException("$agent has no model chosen, so nothing can review its work.", mapOf("agent" to agent))

class ChatGoalReviewerRefusedException(model: String, agent: String, allowed: List<String>) :
    ChatGoalRefusedException(
        "$model is not a model $agent may create agents with, so it cannot review. Choose one of: ${allowed.joinToString(", ")}.",
        mapOf("model" to model, "agent" to agent, "allowed" to allowed),
    )
