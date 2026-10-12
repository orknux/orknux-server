package io.mszymanski.orknux.server.llm

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * The sessions of one conversation: the root a session descends from and
 * everything under that root. Issue #498.
 *
 * A subagent is not a separate piece of work, it is the conversation asking
 * somebody to do part of it, so what the conversation holds - its scratchpads,
 * its box sessions - is the family's rather than one session's. This is the one
 * walk that says who the family is, so the two cannot disagree about it.
 */
@Service
class SessionFamily(private val sessions: LlmSessionRepository) {

    /**
     * The other sessions in this one's family, nearest first, so a name held
     * twice resolves to the closest copy - which is the ancestor an agent
     * inherited it from rather than a cousin that happens to use the same name.
     *
     * Bounded the way the family panel is: a conversation is a few sessions
     * deep and a hundred wide at the very worst, and a walk with no bound is a
     * query that one malformed row turns into a loop.
     */
    fun relativesOf(sessionId: Long): List<Long> {
        val ancestors = ancestorsOf(sessionId)
        val root = ancestors.lastOrNull() ?: sessionId
        val seen = mutableSetOf(root)
        val below = mutableListOf<Long>()
        var edge = listOf(root)
        var depth = 0
        while (edge.isNotEmpty() && depth < ANCESTOR_DEPTH && seen.size < MOST_IN_FAMILY) {
            val next = sessions.findByParentSessionIdInOrderByIdAsc(edge)
                .mapNotNull { it.id }
                .filter { seen.add(it) }
            below += next
            edge = next
            depth += 1
        }
        // Nearest first: what this session inherited, then the rest of the family.
        return (ancestors + below).filter { it != sessionId }.distinct()
    }

    /** This session and its relatives, itself first. */
    fun of(sessionId: Long): List<Long> = listOf(sessionId) + relativesOf(sessionId)

    /** The sessions this one descends from, nearest first, bounded like the family. */
    private fun ancestorsOf(sessionId: Long): List<Long> {
        val chain = mutableListOf<Long>()
        var parent = sessions.findByIdOrNull(sessionId)?.parentSessionId
        var guard = 0
        while (parent != null && guard < ANCESTOR_DEPTH) {
            chain += parent
            parent = sessions.findByIdOrNull(parent)?.parentSessionId
            guard += 1
        }
        return chain
    }

    private companion object {
        /** How far up and down the family reaches, matching the family panel. */
        const val ANCESTOR_DEPTH = 8

        /**
         * How many sessions of one conversation are read. A conversation is a
         * handful of sessions; a hundred is a fan-out nobody intended, and past
         * it the walk stops rather than reading a workspace's worth of rows.
         */
        const val MOST_IN_FAMILY = 100
    }
}
