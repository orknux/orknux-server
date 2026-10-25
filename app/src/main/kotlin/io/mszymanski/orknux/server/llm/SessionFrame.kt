package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * The part of a session's request that comes before its messages, as it was
 * first sent: the system turn and the tool array. See [SessionFrames].
 */
@Entity
@Table(name = "llm_session_frame")
class LlmSessionFrame(
    @Id
    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    /** Whose frame it is: a session another agent takes a turn in is framed again. */
    @Column(name = "agent_id", nullable = false)
    var agentId: Long,

    @Column(name = "model_id", nullable = false)
    var modelId: Long,

    /**
     * The agent's last-modified time, in seconds, when the frame was made - or
     * when a grant made in this conversation was taken into it. A different one
     * is the agent edited from somewhere else, which is a reason to frame again.
     */
    @Column(name = "agent_stamp", nullable = false)
    var agentStamp: Long,

    /** The system turn as sent, or null where the request had none. */
    @Column(name = "system_turn", columnDefinition = "text")
    var systemTurn: String? = null,

    /** The tool array as sent, as JSON; see [SessionFrames.encodeTools]. */
    @Column(nullable = false, columnDefinition = "text")
    var tools: String,

    /** Whether some of what the agent holds was left to be found with tool_load when the array was made. */
    @Column(nullable = false)
    var hunting: Boolean = false,

    /** The system turn the agent would be given now, as last said to it; what a change is measured from. */
    @Column(name = "seen_system", columnDefinition = "text")
    var seenSystem: String? = null,

    /** Every tool the agent held, as last said to it: one per line, its name and its shape. */
    @Column(name = "seen_tools", nullable = false, columnDefinition = "text")
    var seenTools: String = "",

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface LlmSessionFrameRepository : JpaRepository<LlmSessionFrame, Long>

/**
 * A session's frame: what its requests carry before the first message, held
 * byte for byte the same from the first request to the last.
 *
 * **Why.** Measured on llama.cpp with a hybrid model (Qwen3.8-Flash-Next): any
 * change to the tool array - one tool added at its end or in sorted order - or
 * one line added to the system turn, and the server read the whole prompt again;
 * 11,077 of 11,077 tokens, where a request with one message added read 18. In a
 * long conversation that was 167,792 tokens and several minutes, every time a
 * person granted the agent a tool. Hosted providers caching by prefix lose the
 * same way, only more cheaply. [SessionPrompt] already keeps the messages so the
 * next turn sends them again; this keeps the two things in front of them.
 *
 * **What changes instead** goes in as a message: what the agent is told now
 * that differs from what it was told at the start, the tools it holds now that
 * the array does not carry - called through `tool_run` - and the ones it no
 * longer holds, which are refused if called. See
 * [AgentConversation][io.mszymanski.orknux.server.chat.AgentConversation].
 *
 * **When it is made again**: for a new session; for another agent or another
 * model taking a turn in it; for an agent edited since, other than by a grant
 * made in this conversation; and when the messages are rewritten anyway - a
 * compaction, or what was kept outgrowing its ceiling - since a prompt that is
 * read again from the start costs nothing more for a new frame, and the tools
 * that arrived late then go into the array. [SessionPrompt.reset] drops it.
 */
@Service
class SessionFrames(
    private val frames: LlmSessionFrameRepository,
    transactions: PlatformTransactionManager,
) {

    private val writing = TransactionTemplate(transactions)

    /** One session's frame, decoded. */
    data class Frame(
        val agentId: Long,
        val modelId: Long,
        val agentStamp: Long,
        val system: String?,
        val tools: List<ToolSpec>,
        val hunting: Boolean,
        val seenSystem: String?,
        /** Every tool the agent held as last said to it, by name, with [shapeOf] it then. */
        val seenTools: Map<String, String>,
    )

    /** The frame of [session], or null where it has none or it cannot be read. */
    fun read(session: Long): Frame? = runCatching {
        frames.findByIdOrNull(session)?.let { row ->
            Frame(
                agentId = row.agentId,
                modelId = row.modelId,
                agentStamp = row.agentStamp,
                system = row.systemTurn,
                tools = decodeTools(row.tools),
                hunting = row.hunting,
                seenSystem = row.seenSystem,
                seenTools = row.seenTools.split(NL).filter { it.isNotEmpty() }
                    .associate { it.substringBefore(TAB) to it.substringAfter(TAB, "") },
            )
        }
    }.onFailure { log.warn("The frame of session {} could not be read; it is made again", session, it) }.getOrNull()

    /** Writes [frame] as the frame of [session]. Never throws: a frame not kept is a re-read prompt, not a failed turn. */
    fun write(session: Long, frame: Frame) {
        runCatching {
            writing.executeWithoutResult {
                val row = frames.findByIdOrNull(session)
                    ?: LlmSessionFrame(sessionId = session, agentId = frame.agentId, modelId = frame.modelId, agentStamp = frame.agentStamp, tools = "")
                row.agentId = frame.agentId
                row.modelId = frame.modelId
                row.agentStamp = frame.agentStamp
                row.systemTurn = frame.system
                row.tools = encodeTools(frame.tools)
                row.hunting = frame.hunting
                row.seenSystem = frame.seenSystem
                row.seenTools = frame.seenTools.toSortedMap().entries.joinToString(NL) { it.key + TAB + it.value }
                row.updatedAt = OffsetDateTime.now()
                frames.save(row)
            }
        }.onFailure { log.warn("The frame of session {} could not be kept", session, it) }
    }

    /** Drops the frame of [session], so its next turn makes one again. */
    fun drop(session: Long) {
        runCatching { writing.executeWithoutResult { if (frames.existsById(session)) frames.deleteById(session) } }
            .onFailure { log.warn("The frame of session {} could not be dropped", session, it) }
    }

    /**
     * Takes an edit of the agent made from inside [session] into its frame - a
     * grant a person accepted in the conversation - so the next turn keeps the
     * frame and says what changed rather than making it again: the grant is the
     * conversation's own doing, and reading the whole of it again for one is
     * what the frame is for not doing. Only where the frame was of the agent as
     * it stood before the grant: one edited elsewhere meanwhile is still made again.
     */
    fun absorb(session: Long?, agentId: Long?, was: OffsetDateTime, stamp: OffsetDateTime) {
        if (session == null || agentId == null) return
        runCatching {
            writing.executeWithoutResult {
                frames.findByIdOrNull(session)?.takeIf { it.agentId == agentId && it.agentStamp == stampOf(was) }?.let { row ->
                    row.agentStamp = stampOf(stamp)
                    frames.save(row)
                }
            }
        }.onFailure { log.warn("A grant in session {} could not be taken into its frame", session, it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SessionFrames::class.java)

        /** Plain, so what is stored does not move when the application's mapper is configured. */
        private val json = ObjectMapper()

        private val NL = 10.toChar().toString()
        private val TAB = 9.toChar().toString()

        /**
         * A tool's shape, short: what its declaration would send, hashed. A
         * tool whose description or arguments moved since the agent was told
         * of it - a plugin updated, the workspace's drawing model changed - is
         * told again, since the array keeps the declaration it started with.
         */
        fun shapeOf(spec: ToolSpec): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(encodeTools(listOf(spec)).toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }

        /**
         * An agent's last-modified time as a frame keeps it: in whole seconds,
         * so the two engines' timestamp precision cannot make one edit look
         * like two. Two edits inside one second read as one, which costs at
         * most a frame kept that could have been made again - and what changed
         * is then said to the agent all the same.
         */
        fun stampOf(at: OffsetDateTime): Long = at.toEpochSecond()

        /** Every field a request is built from, in order; maps keep their order, so the same specs encode the same. */
        fun encodeTools(tools: List<ToolSpec>): String {
            val list = json.createArrayNode()
            tools.forEach { spec ->
                val node = list.addObject()
                node.put("name", spec.name)
                node.put("description", spec.description)
                spec.summary?.let { node.put("summary", it) }
                if (spec.readOnly) node.put("readOnly", true)
                val params = node.putArray("parameters")
                spec.parameters.forEach { param ->
                    val one = params.addObject()
                    one.put("name", param.name)
                    one.put("description", param.description)
                    one.put("required", param.required)
                    param.schema?.let { one.set("schema", json.valueToTree<JsonNode>(it)) }
                }
            }
            return json.writeValueAsString(list)
        }

        /** And back. By hand: a data class is not constructor-bound by this mapper. */
        @Suppress("UNCHECKED_CAST")
        fun decodeTools(text: String): List<ToolSpec> = json.readTree(text).values().map { node ->
            ToolSpec(
                name = node.path("name").asString(),
                description = node.path("description").asString(""),
                summary = node.get("summary")?.takeUnless(JsonNode::isNull)?.asString(),
                readOnly = node.path("readOnly").asBoolean(false),
                parameters = node.path("parameters").values().map { one ->
                    ToolParameterSpec(
                        name = one.path("name").asString(),
                        description = one.path("description").asString(""),
                        required = one.path("required").asBoolean(false),
                        schema = one.get("schema")?.takeUnless(JsonNode::isNull)?.let {
                            json.treeToValue(it, LinkedHashMap::class.java) as Map<String, Any?>
                        },
                    )
                },
            )
        }
    }
}
