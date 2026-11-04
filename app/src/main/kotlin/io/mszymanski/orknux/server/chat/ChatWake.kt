package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.server.llm.SessionEventDue
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A chat whose agent is owed something starts a turn on its own.
 *
 * Something arrived for the chat's agent after its turn ended - an agent it
 * asked answering, a reminder it set coming due - and nobody is typing. So the
 * server asks the agent itself: the same three calls a person's message is
 * answered with, with nothing added to the thread, because what arrived is read
 * out of the session's inbox by the conversation and written there as said to
 * the agent. What it answers goes into the chat like any other answer.
 *
 * Not while a turn is being answered on that chat: that turn reads the inbox
 * between its rounds by itself. And one at a time per chat, so two things
 * arriving together wake it once.
 */
@Component
class ChatWake(
    private val chats: ChatSessionRepository,
    private val service: ChatService,
    private val chatTools: ChatTools,
    private val generations: ChatGenerations,
    private val inbox: SessionInbox,
    private val steps: ExecutionStepRepository,
    /** The page a tool call named, for the `called` frame. Issue #660. */
    private val links: ItemLinks,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Chats a woken turn is running on, so a second event does not start another. */
    private val waking = ConcurrentHashMap.newKeySet<Long>()

    /** Off the thread that posted: that is an ask finishing, or the sweep. */
    private val turns: ExecutorService = Executors.newCachedThreadPool { Thread(it, "chat-wake").apply { isDaemon = true } }

    /*
     * What arrived while a turn was being answered is read by that turn - unless
     * it arrived after the turn's last round. A person writing just as the agent
     * finished left a message nothing read until the sweep came round, so a
     * turn ending with something unread wakes the chat at once.
     */
    init {
        generations.whenIdle { chatId ->
            val session = chats.findById(chatId).orElse(null)?.llmSessionId ?: return@whenIdle
            if (inbox.nextDue(session)?.let { !it.isAfter(java.time.OffsetDateTime.now()) } == true) {
                due(SessionEventDue(session))
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun due(event: SessionEventDue) {
        val chat = chats.findFirstByLlmSessionId(event.sessionId) ?: return
        /*
         * A run's conversation is the run's, with a chat open on it or not:
         * what arrives there - an agent it asked answering, a reminder, a
         * watcher - wakes the run's agent, whose answer goes on to the run's
         * next node. The chat answers what a person types into it, and nothing
         * else.
         *
         * It used to stand back for a watcher only. "Continue in chat" on a
         * Slack thread's session then made every answer start two turns - this
         * one and the parked step's - which did the same work twice, doubled
         * the rate-limit failures, and when the step's turn failed the chat's
         * carried on and answered into a page nobody had open: the thread
         * heard nothing for twenty minutes, until somebody asked whether it
         * had finished. WatcherFollowUp and SessionWake take all of it now.
         */
        if (steps.existsBySessionId(event.sessionId)) return
        val id = requireNotNull(chat.id)
        if (generations.answering(id) || !waking.add(id)) return
        turns.execute {
            try {
                answer(id)
            } catch (failure: Exception) {
                log.warn("Chat {} could not be woken: {}", id, failure.message)
            } finally {
                waking.remove(id)
            }
        }
    }

    private fun answer(id: Long) {
        val start = service.beginWake(id) ?: return
        val chat = chats.findById(id).orElse(null) ?: return
        // Followable like a turn somebody asked for, so a page open on this chat
        // - or opened on it while this is being written - sees it arrive (#201).
        val generation = ChatGeneration(woken = true)
        val watch = generation.watch(links)
        generations.register(id, generation)
        try {
            when (val said = service.ask(start, watch, chatTools.shed(chat, watch), generation.hangup)) {
                is ChatCompletion.Answered -> {
                    service.finishSend(
                        id, said.content, said.reasoning, said.reasoningMillis, said.inputTokens, said.outputTokens,
                        said.cachedInputTokens,
                    )
                    generation.post("chunk", mapOf("text" to said.content))
                }
                is ChatCompletion.Failed -> log.warn("Chat {} woke and could not answer: {}", id, said.reason)
                is ChatCompletion.CalledTools -> Unit
            }
        } finally {
            generations.release(id, generation)
        }
    }
}
