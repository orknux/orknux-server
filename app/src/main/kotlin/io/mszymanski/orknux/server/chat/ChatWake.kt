package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.server.llm.SessionEventDue
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
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Chats a woken turn is running on, so a second event does not start another. */
    private val waking = ConcurrentHashMap.newKeySet<Long>()

    /** Off the thread that posted: that is an ask finishing, or the sweep. */
    private val turns: ExecutorService = Executors.newCachedThreadPool { Thread(it, "chat-wake").apply { isDaemon = true } }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun due(event: SessionEventDue) {
        val chat = chats.findFirstByLlmSessionId(event.sessionId) ?: return
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
        val watch = generation.watch()
        generations.register(id, generation)
        try {
            when (val said = service.ask(start, watch, chatTools.shed(chat, watch), generation.hangup)) {
                is ChatCompletion.Answered -> {
                    service.finishSend(
                        id, said.content, said.reasoning, said.reasoningMillis, said.inputTokens, said.outputTokens,
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
