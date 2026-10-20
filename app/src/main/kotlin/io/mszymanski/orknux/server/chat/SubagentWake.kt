package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.SessionEventDue
import io.mszymanski.orknux.server.llm.SessionInbox
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import tools.jackson.databind.ObjectMapper

/**
 * An agent asked by another, woken by what it set for itself.
 *
 * Since every kind of turn is lent the same session tools ([SessionSheds]), a
 * subagent can set a reminder or a watcher - and once its turn had answered,
 * nothing owned its session: a reminder came due with nobody to read it, and a
 * watcher woke a turn through [io.mszymanski.orknux.server.watcher.WatcherFollowUp]
 * whose answer went nowhere, because no workflow step is in an ask's session.
 *
 * So this is the fourth of the session's wakers, and the one for a session
 * opened under another - which only an ask does. The agent recorded as answering
 * in it runs again in its own session, reading what arrived first, and what it
 * says goes where its first answer went: into the inbox of the agent that asked,
 * whose own waker brings that one back. Running it is [AgentRunTools.resume],
 * so a woken subagent counts as working in `agent_asks`, holds one of the asking
 * conversation's permits, and is not started beside a turn already running.
 *
 * After the commit, so the event the turn goes looking for is there.
 */
@Component
class SubagentWake(
    private val held: LlmSessionRepository,
    private val agents: AgentRepository,
    private val asks: AgentRunTools,
    private val inbox: SessionInbox,
    private val briefing: AgentBriefing,
    private val sessions: LlmSessionRecorder,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun due(event: SessionEventDue) = wake(event.sessionId)

    fun wake(session: Long) {
        val row = held.findByIdOrNull(session) ?: return
        if (row.parentSessionId == null) return
        val agentId = agentIdIn(row.agentDetails)
            ?: return log.info("Session {} names no agent, so what arrived there wakes nobody", session)
        val agent = agents.findByIdOrNull(agentId)?.takeIf { it.enabled }
            ?: return log.info("The agent of session {} is gone or switched off; nobody to wake", session)
        val modelId = agent.modelId
            ?: return log.info("{} has no model, so session {} wakes nobody", agent.name, session)

        asks.resume(session, agent, modelId) { sub ->
            // Built as a watcher's follow-up is: what was sent, kept, or the log
            // rebuilt; then what arrived, and last what this turn is.
            val history = asks.conversationIn(session, sub, modelId)
            val arrived = inbox.take(session)
            sessions.note(session, "${sub.name} was started again by something it set, after its answer.")
            buildList {
                add(ChatTurn("system", briefing.of(sub).orEmpty()))
                addAll(history)
                arrived.forEach { add(ChatTurn("user", it)) }
                add(ChatTurn("user", WOKEN))
            }
        }
    }

    /** The agent the session's setup line names - `agentId`, which the ask writes before the question. */
    private fun agentIdIn(details: String?): Long? = details?.let { setup ->
        runCatching { mapper.readTree(setup).path("agentId").takeIf { it.isNumber }?.longValue() }.getOrNull()
    }

    companion object {
        /** What the woken agent is told about this turn, after what arrived. */
        const val WOKEN =
            "This turn was started by a reminder or watcher you set, not by the agent that asked you. " +
                "Whatever you answer is sent to that agent as more on what it asked, so say what changed " +
                "and what it means for the question - not which tool you called or what you will look at next."
    }
}
