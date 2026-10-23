package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.security.SecretConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * One message a session's model was sent, as it was sent. See [SessionPrompt].
 *
 * Encrypted, because a call's arguments are kept exactly as the model wrote
 * them - the event log redacts what it shows a person, and a replayed prompt
 * with `***` in it would not be the prompt that was sent, so it would not be
 * the prefix a provider cached either.
 */
@Entity
@Table(name = "llm_session_prompt")
class LlmSessionPromptLine(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    /** Which turn of the session wrote it, counted from one. */
    @Column(name = "turn_no", nullable = false)
    val turnNo: Int,

    /** Written by the turn's first write: what it was asked with, not what it answered. */
    @Column(nullable = false)
    val opening: Boolean = false,

    /** The message as JSON; see [SessionPrompt.encode]. */
    @Convert(converter = SecretConverter::class)
    @Column(nullable = false, columnDefinition = "text")
    val message: String,

    /** The newest event in the session's log when this was written. */
    @Column(name = "through_event_id")
    var throughEventId: Long? = null,

    /** Characters of text in it, pictures not counted; see [SessionPrompt.weigh]. */
    @Column(nullable = false)
    val chars: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface LlmSessionPromptRepository : JpaRepository<LlmSessionPromptLine, Long> {

    fun findBySessionIdOrderByIdAsc(sessionId: Long): List<LlmSessionPromptLine>

    fun deleteBySessionId(sessionId: Long)

    @Query("select max(p.turnNo) from LlmSessionPromptLine p where p.sessionId = :sessionId")
    fun lastTurn(sessionId: Long): Int?
}

/**
 * What a session's model has been sent, kept so the next turn sends it again
 * exactly - and only ever adds to it.
 *
 * **Why.** A provider that caches a prompt by its prefix - llama.cpp's slot
 * cache, and every hosted provider that bills cached input for less - can only
 * reuse what is byte for byte the same as last time. Inside a turn that was
 * already true: each round's request is the last one with the calls and their
 * answers added. Across turns it was not. The next turn was rebuilt from the
 * event log - what was said, and a block of what the tools returned - so the
 * assistant message that carried the calls and the reasoning, and every tool
 * result, were replaced by the final answer and a recall block, and a captured
 * llama.cpp session read the whole conversation again from the third message
 * on, on every message somebody sent.
 *
 * **What is kept** is every message after the system turn, in the order it was
 * sent: the questions, the assistant messages with their calls and the reasoning
 * echoed with them, every tool result, the pictures, what arrived from the inbox
 * and what a person said between rounds, and the final answer. Written by
 * [AgentConversation][io.mszymanski.orknux.server.chat.AgentConversation] just
 * before each call to the model, so what is here is what was sent, and nothing
 * else writes it. The final answer is kept as its words alone: it was never
 * sent back inside its own turn, and a reasoning model's last thinking is not
 * something any of the shapes spoken here echo on an answer.
 *
 * **What the next turn is handed** is [history]: these messages, then whatever
 * was said into the session's log after the last of them was written - a
 * person's message to a task, the "carry on" a task is given between turns, a
 * bare model's answer in a chat continuing a session - then the caller's own
 * new message. A session with nothing kept here, which is every session from
 * before this and every session just started, is built the old way from the log,
 * and that becomes what is kept.
 *
 * **When it does change.** Only in one block, and only for a reason:
 *  - It outgrows its [ceiling][SessionMemoryBudgets.promptCeiling] at the start
 *    of a turn: it is dropped whole, the turn is built from the log the old way,
 *    and that smaller conversation is kept from then on. Dropping a little from
 *    the front every turn would change the prefix every turn, which is the
 *    thing this exists to stop; dropping half of it at once changes it once.
 *  - A compaction summarised the session or the chat: dropped the same way, so
 *    the summary is what the next turn starts from.
 *  - A turn was shrunk to fit the model's window mid-way, or its older tool
 *    results were cut to keep it within its memory: the kept copy is rewritten
 *    to what was actually sent. [Kept.sent] notices any request that does not
 *    extend the last one and logs where they part, so a new cause shows up in
 *    the log rather than as a slow model.
 *  - A chat's answer is asked for again: the last turn's answer and its rounds
 *    are taken back, and the question it was asked with stays.
 */
@Service
class SessionPrompt(
    private val lines: LlmSessionPromptRepository,
    private val recorder: LlmSessionRecorder,
    private val svgs: io.mszymanski.orknux.server.plugin.SvgRenderer,
    transactions: PlatformTransactionManager,
) {

    private val writing = TransactionTemplate(transactions)

    /**
     * What to put between the system turn and this turn's new message, or null
     * where nothing is kept and the caller builds it from the log as before.
     *
     * Null as well where what is kept has outgrown [ceiling] - and then it is
     * dropped, so the caller's rebuild is what is kept from this turn on.
     */
    fun history(session: Long, ceiling: Int): List<ChatTurn>? {
        val held = runCatching { lines.findBySessionIdOrderByIdAsc(session) }
            .onFailure { log.warn("What session {} was sent could not be read back", session, it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val weight = held.sumOf { it.chars.toLong() }
        if (weight > ceiling) {
            reset(session)
            recorder.note(
                session,
                "This conversation had grown past what is carried to the model, so it is carried from " +
                    "here on as what was said and what the tools last returned.",
            )
            log.info("Session {} outgrew its prompt ({} > {} characters) and was started again", session, weight, ceiling)
            return null
        }
        val turns = runCatching { held.map { decode(it.message) } }
            .onFailure { log.warn("What session {} was sent could not be decoded; rebuilding it", session, it) }
            .getOrNull()
            ?: return null.also { reset(session) }
        val since = held.last().throughEventId?.let { recorder.saidAfter(session, it) }.orEmpty()
        return turns + since
    }

    /** Drops what is kept, so the next turn is built from the log again. */
    fun reset(session: Long) {
        runCatching { writing.executeWithoutResult { lines.deleteBySessionId(session) } }
            .onFailure { log.warn("What session {} was sent could not be dropped", session, it) }
    }

    /**
     * Takes back the last turn's answer, keeping what it was asked with - for a
     * chat whose answer is asked for again. Everything the turn added after its
     * first request goes; that first request - the question, and anything that
     * arrived with it - stays and is asked again.
     */
    fun rewind(session: Long) {
        runCatching {
            writing.executeWithoutResult {
                val held = lines.findBySessionIdOrderByIdAsc(session)
                val last = held.maxOfOrNull { it.turnNo } ?: return@executeWithoutResult
                val answered = held.filter { it.turnNo == last && !it.opening }
                if (answered.isEmpty()) return@executeWithoutResult
                lines.deleteAll(answered)
                /*
                 * And what the log said during that answer is not something
                 * said since: the answer itself is a line there, and the next
                 * turn would otherwise hand it back as though it still stood.
                 */
                held.lastOrNull { it !in answered }?.let { kept ->
                    kept.throughEventId = answered.mapNotNull { it.throughEventId }.maxOrNull() ?: kept.throughEventId
                    lines.save(kept)
                }
            }
        }.onFailure { log.warn("The last answer in session {} could not be taken back", session, it) }
    }

    /** A turn's record, written as the turn sends. */
    fun turn(session: Long): Kept = Kept(session)

    /**
     * One turn's writer.
     *
     * Loaded once, when the turn starts, and kept in step with every request
     * the turn sends - so each write is the few messages the turn added rather
     * than the conversation again. Never throws: a turn whose prompt could not
     * be kept is a slower next turn, not a failed one.
     */
    inner class Kept internal constructor(private val session: Long) {

        private var stored: List<ChatTurn> = runCatching {
            lines.findBySessionIdOrderByIdAsc(session).map { decode(it.message) }
        }.getOrElse { emptyList() }

        private val turnNo: Int = (runCatching { lines.lastTurn(session) }.getOrNull() ?: 0) + 1

        /** Whether nothing has been written yet this turn. */
        private var first = true

        /**
         * What is about to be sent, everything but the system turn kept.
         *
         * Appended where it extends what is kept, which is every request but
         * the ones [SessionPrompt] lists; rewritten whole where it does not, and
         * said so in the log with where the two part.
         */
        fun sent(turns: List<ChatTurn>) {
            val now = turns.filter { it.role != SYSTEM }
            if (now == stored) return
            val extends = now.size > stored.size && now.subList(0, stored.size) == stored
            val through = recorder.lastEventId(session)
            try {
                writing.executeWithoutResult {
                    if (extends) {
                        lines.saveAll(now.drop(stored.size).map { line(it, through) })
                    } else {
                        val parted = now.indices.firstOrNull { it >= stored.size || now[it] != stored[it] } ?: now.size
                        log.info(
                            "Session {}'s prompt was rewritten from message {} ({} kept, {} sent)",
                            session, parted, stored.size, now.size,
                        )
                        lines.deleteBySessionId(session)
                        lines.saveAll(now.map { line(it, through) })
                    }
                }
                stored = now
                first = false
            } catch (failure: Exception) {
                log.warn("What session {} was sent could not be kept", session, failure)
            }
        }

        private fun line(turn: ChatTurn, through: Long?) = LlmSessionPromptLine(
            sessionId = session,
            turnNo = turnNo,
            opening = first,
            message = encode(turn),
            throughEventId = through,
            chars = weigh(turn),
        )
    }

    /** As JSON, every field the request is built from and nothing else. */
    internal fun encode(turn: ChatTurn): String {
        val node = json.createObjectNode()
        node.put("role", turn.role)
        node.put("content", turn.content)
        if (turn.images.isNotEmpty()) node.putArray("images").also { list -> turn.images.forEach { list.add(it) } }
        if (turn.asked.isNotEmpty()) {
            node.putArray("asked").also { list ->
                turn.asked.forEach { call ->
                    list.addObject().put("id", call.id).put("name", call.name).put("arguments", call.arguments)
                }
            }
        }
        turn.respondingTo?.let { node.put("respondingTo", it) }
        turn.reasoning?.let { node.put("reasoning", it) }
        if (turn.reasoningItems.isNotEmpty()) {
            node.putArray("reasoningItems").also { list -> turn.reasoningItems.forEach { list.add(it) } }
        }
        return json.writeValueAsString(node)
    }

    /**
     * A picture as a model can take it: an SVG drawn as a PNG, anything else as
     * it was kept.
     *
     * An SVG attached to a chat was sent as image/svg+xml, which no provider
     * loads - llama.cpp answered 400 "Failed to load image or audio file" - and
     * this record sends every earlier picture again on every turn, so one SVG
     * made every later message in its chat fail. Drawn here, where every kept
     * picture passes on its way out, a chat that already holds one recovers.
     */
    private fun seeable(dataUrl: String): String {
        if (!dataUrl.startsWith(SVG_DATA)) return dataUrl
        val markup = runCatching {
            String(java.util.Base64.getDecoder().decode(dataUrl.substringAfter("base64,")), Charsets.UTF_8)
        }.getOrNull() ?: return dataUrl
        return when (val drawn = svgs.png(markup, null)) {
            is io.mszymanski.orknux.server.plugin.SvgRenderer.Drawing.Drawn ->
                "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(drawn.png)
            is io.mszymanski.orknux.server.plugin.SvgRenderer.Drawing.Refused -> dataUrl
        }
    }

    /** And back; by hand, since a data class is not constructor-bound here. */
    internal fun decode(message: String): ChatTurn {
        val node = json.readTree(message)
        fun texts(field: String): List<String> = node.path(field).values().map { it.asString() }
        fun text(field: String): String? = node.get(field)?.takeUnless(JsonNode::isNull)?.asString()
        return ChatTurn(
            role = node.path("role").asString(),
            content = node.path("content").asString(""),
            images = texts("images").map(::seeable),
            asked = node.path("asked").values().map {
                ToolCall(it.path("id").asString(), it.path("name").asString(), it.path("arguments").asString())
            },
            respondingTo = text("respondingTo"),
            reasoning = text("reasoning"),
            reasoningItems = texts("reasoningItems"),
        )
    }

    private companion object {
        /** How an SVG picture begins as a data URL. */
        const val SVG_DATA = "data:image/svg+xml;base64,"

        const val SYSTEM = "system"

        /** Plain, so what is stored does not move when the application's mapper is configured. */
        val json = ObjectMapper()

        val log = LoggerFactory.getLogger(SessionPrompt::class.java)

        /**
         * What a message weighs against the ceiling: its words, its reasoning
         * and its calls. A picture is not counted by its base64 - a provider
         * charges a picture by its size in pixels, not by the length of its
         * encoding, and counted that way one screenshot would drop a whole
         * session.
         */
        fun weigh(turn: ChatTurn): Int =
            turn.content.length + (turn.reasoning?.length ?: 0) +
                turn.asked.sumOf { it.name.length + it.arguments.length }
    }
}
