package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.attachment.InstallationSettings
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * A working file an agent keeps within one session. Issue #411.
 *
 * ### What it is, and what it is not
 *
 * A scratchpad is a mutable document scoped to a conversation: an agent creates
 * one, writes to it a piece at a time over many turns, reads back a fragment or
 * the whole, and deletes it when done. It is the place an agent assembles
 * something too big to hold in its head across a long job - an HTML page it is
 * drafting, a file of code, a set of notes it is organising.
 *
 * It is neither of the two things next to it. A note ([LlmSessionNote]) is a
 * short fixed line handed back whole on every turn - memory that must not fall
 * out. A scratchpad is a file the agent chooses when to read: it is not put in
 * front of the model unless the model asks for it, so it can be a document
 * rather than a sentence. The session store ([LlmSessionStore]) is a key-value
 * map for tools to pass values between themselves; a scratchpad is text an
 * agent reads and edits by hand.
 *
 * ### Sharing with subagents
 *
 * A scratchpad marked shared is readable and writable by the sessions started
 * under the one that owns it - an agent that asks another agent can hand it a
 * document to add to. Bytes are counted against the owner either way, so a
 * subagent cannot grow a pad past the budget of the conversation it belongs to.
 */
@Entity
@Table(name = "session_scratchpad")
class SessionScratchpad(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** The session that owns it; it goes when the session does, by foreign key. */
    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    /** Its name within the session, which is how the agent addresses it - like a filename. Unique per session. */
    @Column(name = "name", nullable = false, length = NAME_LENGTH)
    val name: String,

    /** What it is for, in a line, so a list of them reads without opening each. */
    @Column(name = "description", length = DESCRIPTION_LENGTH)
    var description: String? = null,

    /** The document itself. */
    @Column(name = "content", nullable = false, columnDefinition = "text")
    var content: String = "",

    /** Whether the sessions started under this one may read and add to it. Issue #411. */
    @Column(name = "shared", nullable = false)
    var shared: Boolean = false,

    /**
     * What this pad holds, where it is not text: `image/png`, `application/pdf`.
     * Issue #490.
     *
     * Null is a text file, which is every pad there was before this. Where it is
     * set, [content] is base64 and three things follow: the read tool answers
     * with the type, the size and a key rather than the bytes, because handing a
     * model a megabyte of base64 spends the turn and teaches it nothing; the
     * editing tools refuse it, since there is no replacing a paragraph of a PNG;
     * and the session page draws it as a picture.
     *
     * The type rather than a boolean, because everything downstream wants it -
     * the archive names the file, the page picks the tag, an upload sets a
     * header - and a boolean would have each of them guessing from the name.
     */
    @Column(name = "content_type", length = 120)
    var contentType: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
) {
    /** Its size in bytes, which is what the budget is spent in. */
    val bytes: Int get() = content.toByteArray(Charsets.UTF_8).size

    companion object {
        const val NAME_LENGTH = 200
        const val DESCRIPTION_LENGTH = 500
    }
}

interface SessionScratchpadRepository : JpaRepository<SessionScratchpad, Long> {

    fun findBySessionIdOrderByNameAsc(sessionId: Long): List<SessionScratchpad>

    fun findBySessionIdAndName(sessionId: Long, name: String): SessionScratchpad?

    fun findBySessionIdInAndSharedTrueOrderByNameAsc(sessionIds: Collection<Long>): List<SessionScratchpad>

    /** Every pad these sessions hold, for the family's list. Issue #498. */
    fun findBySessionIdInOrderByNameAsc(sessionIds: Collection<Long>): List<SessionScratchpad>

    fun countBySessionId(sessionId: Long): Long

    /** Everything nobody has touched since then, for the sweeper. Issue #492. */
    fun findByUpdatedAtBefore(cutoff: java.time.OffsetDateTime): List<SessionScratchpad>

    /** The bytes this session's own pads already occupy, for the budget. */
    @Query(
        """
        select coalesce(sum(length(p.content)), 0) from SessionScratchpad p
        where p.sessionId = :sessionId
        """,
    )
    fun charsHeldBy(sessionId: Long): Long
}

/** What an operation on a scratchpad answered: the pad, or why it could not. */
sealed interface ScratchpadResult {
    data class Ok(val pad: SessionScratchpad, val ownedHere: Boolean) : ScratchpadResult
    data class No(val why: String) : ScratchpadResult
}

/**
 * The scratchpads of one session, and the rules that bound them. Issue #411.
 *
 * The bounds live here rather than in the tool or the API because this is the
 * side that pays for them: a table read whole, a budget spent in bytes, a name
 * that has to stay a name. Both the model's tools and the orknux API go through
 * this, so the two cannot disagree about what a scratchpad may hold.
 */
@Service
class SessionScratchpadService(
    private val pads: SessionScratchpadRepository,
    private val sessions: LlmSessionRepository,
    private val settings: InstallationSettings,
) {

    /**
     * Every pad in this conversation: its own and every other session's in the
     * family. Issue #498.
     *
     * The family and not a sharing flag. A subagent is not a separate piece of
     * work, it is this conversation asking somebody to do part of it, so the
     * files are the conversation's - and a flag meant an agent had to remember
     * to set it, a subagent's work landed where nobody looked, and the question
     * "did it save?" had two answers.
     *
     * Both directions: what the conversation made is there for the agents it
     * asks, and what they make is there for the conversation. A name this
     * session has of its own still wins, the way a local file shadows one
     * further up, so a subagent may keep a working copy under a name the
     * conversation is also using without either overwriting the other by
     * accident.
     */
    @Transactional(readOnly = true)
    fun list(sessionId: Long): List<SessionScratchpad> {
        val own = pads.findBySessionIdOrderByNameAsc(sessionId)
        val relatives = familyOf(sessionId)
        val theirs = if (relatives.isEmpty()) emptyList() else
            pads.findBySessionIdInOrderByNameAsc(relatives)
                .filter { other -> own.none { it.name == other.name } }
        return (own + theirs).sortedBy { it.name }
    }

    /** The pad this name resolves to for this session, or null if there is none. */
    @Transactional(readOnly = true)
    fun find(sessionId: Long, name: String): SessionScratchpad? = resolve(sessionId, name)?.first

    /**
     * Makes a new pad. Refused if the name is taken here, malformed, or the
     * content would put the owner over the byte budget.
     */
    @Transactional
    fun create(
        sessionId: Long,
        name: String,
        description: String?,
        content: String,
        /** What it holds where it is not text; the content is then base64. Issue #490. */
        contentType: String? = null,
    ): ScratchpadResult {
        val clean = name.trim()
        nameProblem(clean)?.let { return ScratchpadResult.No(it) }
        if (pads.findBySessionIdAndName(sessionId, clean) != null) {
            return ScratchpadResult.No("This session already has a scratchpad named \"$clean\".")
        }
        if (pads.countBySessionId(sessionId) >= MOST_PADS) {
            return ScratchpadResult.No("This session already holds $MOST_PADS scratchpads, which is as many as are kept.")
        }
        overBudget(sessionId, added = content.toByteArray(Charsets.UTF_8).size, replacing = 0)?.let {
            return ScratchpadResult.No(it)
        }
        descriptionProblem(description)?.let { return ScratchpadResult.No(it) }
        val saved = pads.save(
            SessionScratchpad(
                sessionId = sessionId,
                name = clean,
                description = description?.trim(),
                content = content,
                contentType = contentType?.trim()?.ifEmpty { null },
            ),
        )
        return ScratchpadResult.Ok(saved, ownedHere = true)
    }

    /** Replaces a pad's whole content. */
    @Transactional
    fun write(sessionId: Long, name: String, content: String): ScratchpadResult =
        edit(sessionId, name) { content }

    /**
     * Adds to the end of a pad.
     *
     * Refused on a pad holding bytes: appending to base64 makes it neither the
     * old picture nor a new one, and the failure would be silent. Issue #490.
     */
    @Transactional
    fun append(sessionId: Long, name: String, text: String): ScratchpadResult {
        binaryProblem(sessionId, name, "added to")?.let { return it }
        return edit(sessionId, name) { it + text }
    }

    /**
     * The files this session holds, oldest first, once they pass the budget.
     * Issue #491.
     *
     * Removed rather than refused. A picture is not a document an agent could
     * have written more briefly: it is what somebody asked for, and refusing to
     * keep it would leave the agent with nothing to send. What is safe to drop
     * is the oldest, which has already been sent, packed or forgotten, while the
     * one just written is the one in use.
     *
     * Text pads are never touched. They are small, they are the work itself,
     * and the character budget already holds them.
     *
     * Answers with the names it removed, so the tool that just wrote one can say
     * so - an agent that finds out later is one that packed an archive around a
     * file that is gone.
     */
    @Transactional
    fun sweepFiles(sessionId: Long): List<String> {
        val budget = settings.scratchpadFileBudgetBytes()
        val files = pads.findBySessionIdOrderByNameAsc(sessionId)
            .filter { it.contentType != null }
            .sortedBy { it.updatedAt }
        var held = files.sumOf { it.content.length.toLong() }
        if (held <= budget) return emptyList()

        val swept = mutableListOf<String>()
        for (pad in files) {
            if (held <= budget) break
            // Never the last one standing: a budget that removes the file just
            // written is a budget that makes the feature useless.
            if (pad === files.last()) break
            held -= pad.content.length.toLong()
            pads.delete(pad)
            swept += pad.name
        }
        return swept
    }

    /** Null where this pad is text, or a refusal naming what it holds. Issue #490. */
    private fun binaryProblem(sessionId: Long, name: String, doing: String): ScratchpadResult.No? {
        val held = find(sessionId, name) ?: return null
        val type = held.contentType ?: return null
        return ScratchpadResult.No(
            "\"${held.name}\" holds a $type, so it cannot be $doing. " +
                "Write the whole file again to replace it.",
        )
    }

    /**
     * Replaces one occurrence of a piece of text in a pad, the cheap way to
     * change a large document without resending it.
     */
    @Transactional
    fun replace(sessionId: Long, name: String, old: String, new: String): ScratchpadResult {
        if (old.isEmpty()) return ScratchpadResult.No("Say what text to replace.")
        // No such thing as replacing a paragraph of a PNG. Issue #490.
        binaryProblem(sessionId, name, "edited")?.let { return it }
        val found = resolve(sessionId, name) ?: return missing(name)
        val pad = found.first
        val at = pad.content.indexOf(old)
        if (at < 0) return ScratchpadResult.No("That text is not in \"$name\", so there is nothing to replace.")
        if (pad.content.indexOf(old, at + 1) >= 0) {
            return ScratchpadResult.No("That text is in \"$name\" more than once; give a longer piece that appears exactly once.")
        }
        return edit(sessionId, name) { it.replaceRange(at, at + old.length, new) }
    }

    /** Sets what a pad is for. */
    @Transactional
    fun describe(sessionId: Long, name: String, description: String?): ScratchpadResult {
        descriptionProblem(description)?.let { return ScratchpadResult.No(it) }
        val found = resolve(sessionId, name) ?: return missing(name)
        found.first.description = description?.trim()
        found.first.updatedAt = OffsetDateTime.now()
        return ScratchpadResult.Ok(pads.save(found.first), found.second)
    }

    /** Whether the sessions under the owner may read and add to this pad. */
    @Transactional
    fun share(sessionId: Long, name: String, shared: Boolean): ScratchpadResult {
        // Only the owner decides sharing - a session cannot re-share a pad it
        // only inherited.
        val own = pads.findBySessionIdAndName(sessionId, name)
            ?: return ScratchpadResult.No("This session has no scratchpad named \"$name\" of its own to share.")
        own.shared = shared
        own.updatedAt = OffsetDateTime.now()
        return ScratchpadResult.Ok(pads.save(own), ownedHere = true)
    }

    /** Removes a pad. Only the owner may. */
    @Transactional
    fun delete(sessionId: Long, name: String): ScratchpadResult {
        val own = pads.findBySessionIdAndName(sessionId, name)
            ?: return ScratchpadResult.No("This session has no scratchpad named \"$name\" of its own to delete.")
        pads.delete(own)
        return ScratchpadResult.Ok(own, ownedHere = true)
    }

    /**
     * Where a piece of text appears across this session's scratchpads: which
     * pad, and the line it is on, so an agent with several large pads finds a
     * fragment without reading each whole. Case-insensitive.
     */
    @Transactional(readOnly = true)
    fun search(sessionId: Long, query: String): List<ScratchpadHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val hits = mutableListOf<ScratchpadHit>()
        list(sessionId).forEach { pad ->
            pad.content.lineSequence().forEachIndexed { index, line ->
                if (line.contains(needle, ignoreCase = true)) {
                    hits += ScratchpadHit(pad.name, index + 1, line.trim().take(HIT_LENGTH))
                    if (hits.size >= MOST_HITS) return hits
                }
            }
        }
        return hits
    }

    private fun edit(sessionId: Long, name: String, change: (String) -> String): ScratchpadResult {
        val found = resolve(sessionId, name) ?: return missing(name)
        val pad = found.first
        val next = change(pad.content)
        val added = next.toByteArray(Charsets.UTF_8).size
        val was = pad.content.toByteArray(Charsets.UTF_8).size
        overBudget(pad.sessionId, added = added, replacing = was)?.let { return ScratchpadResult.No(it) }
        pad.content = next
        pad.updatedAt = OffsetDateTime.now()
        return ScratchpadResult.Ok(pads.save(pad), found.second)
    }

    /** The pad and whether this session owns it: its own first, then the family's. Issue #498. */
    private fun resolve(sessionId: Long, name: String): Pair<SessionScratchpad, Boolean>? {
        pads.findBySessionIdAndName(sessionId, name)?.let { return it to true }
        val relatives = familyOf(sessionId)
        if (relatives.isEmpty()) return null
        return pads.findBySessionIdInOrderByNameAsc(relatives)
            .firstOrNull { it.name == name }
            ?.let { it to false }
    }

    /**
     * The other sessions in this conversation: the root it descends from and
     * everything under that root. Issue #498.
     *
     * Nearest first, so a name held twice resolves to the closest copy - which
     * is the ancestor an agent inherited it from rather than a cousin that
     * happens to use the same name.
     *
     * Bounded the way the family panel is: a conversation is a few sessions
     * deep and a hundred wide at the very worst, and a walk with no bound is a
     * query that one malformed row turns into a loop.
     */
    private fun familyOf(sessionId: Long): List<Long> {
        val ancestors = ancestorsOf(sessionId)
        val root = ancestors.lastOrNull() ?: sessionId
        val seen = mutableSetOf(root)
        val below = mutableListOf<Long>()
        var edge = listOf(root)
        var depth = 0
        while (edge.isNotEmpty() && depth < ANCESTOR_DEPTH && seen.size < MOST_IN_FAMILY) {
            val next = pads.let { _ -> sessions.findByParentSessionIdInOrderByIdAsc(edge) }
                .mapNotNull { it.id }
                .filter { seen.add(it) }
            below += next
            edge = next
            depth += 1
        }
        // Nearest first: what this session inherited, then the rest of the family.
        return (ancestors + below).filter { it != sessionId }.distinct()
    }

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

    /** Null if the added bytes fit the owner's budget, else why not. */
    private fun overBudget(ownerSession: Long, added: Int, replacing: Int): String? {
        val budget = settings.scratchpadBudgetBytes()
        val heldElsewhere = pads.charsHeldBy(ownerSession).toInt() - replacing
        val total = heldElsewhere + added
        return if (total > budget) {
            "That would put this session's scratchpads at ${total / 1024} KB, over the ${budget / 1024} KB a " +
                "session may keep. Shorten it, or delete a scratchpad you no longer need."
        } else {
            null
        }
    }

    private fun nameProblem(name: String): String? = when {
        name.isEmpty() -> "A scratchpad needs a name."
        name.length > SessionScratchpad.NAME_LENGTH -> "That name is too long; keep it under ${SessionScratchpad.NAME_LENGTH} characters."
        else -> null
    }

    private fun descriptionProblem(description: String?): String? =
        if ((description?.length ?: 0) > SessionScratchpad.DESCRIPTION_LENGTH) {
            "That description is too long; keep it under ${SessionScratchpad.DESCRIPTION_LENGTH} characters."
        } else {
            null
        }

    private fun missing(name: String): ScratchpadResult.No =
        ScratchpadResult.No("There is no scratchpad named \"$name\" in this session.")

    companion object {
        /** How many one session keeps, so a table read whole stays a handful of rows. */
        const val MOST_PADS = 50

        /** The most hits a search answers with, so a common word does not return a book. */
        const val MOST_HITS = 50

        /** As much of a matching line as a hit carries. */
        const val HIT_LENGTH = 200

        /** How far up the family a shared pad is inherited, matching the family panel's reach. */
        const val ANCESTOR_DEPTH = 8

        /**
         * How many sessions of one conversation are read for its files.
         * Issue #498.
         *
         * A conversation is a handful of sessions; a hundred is a fan-out
         * nobody intended, and past it the walk stops rather than reading a
         * workspace's worth of rows to answer one list.
         */
        const val MOST_IN_FAMILY = 100
    }
}

/** Where a search found its text: which pad, which line, and the line itself. */
data class ScratchpadHit(val name: String, val line: Int, val text: String)
