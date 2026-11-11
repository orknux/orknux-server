package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.chat.ChatTurnLedger
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Where a question put to the quick chat has got to. */
enum class QuickChatAskStatus { WAITING, ANSWERED, FAILED, STOPPED }

/**
 * One question put to the quick chat, with the conversation it was asked in.
 *
 * The panel kept its conversation in the browser and asked in one request that
 * returned with the answer, so a reload, a closed tab or a server restart lost
 * the answer and the whole conversation with it. Now the question is this row,
 * answered on the server whoever is still waiting, and the newest rows are the
 * conversation the panel draws again when it opens.
 */
@Entity
@Table(name = "quick_chat_ask")
class QuickChatAsk(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "user_id", nullable = false, length = 255)
    val userId: String,

    /** The conversation as the panel sent it, the question last, as JSON. */
    @Column(nullable = false, columnDefinition = "text")
    val messages: String,

    @Column(name = "page_label", length = 500)
    val pageLabel: String? = null,

    @Column(name = "page_path", length = 2000)
    val pagePath: String? = null,

    /** CLAUDE_CODE where the person's own Claude Code answers it; null for the workspace's model. */
    @Column(name = "answered_by", length = 32)
    val answeredBy: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: QuickChatAskStatus = QuickChatAskStatus.WAITING,

    /** The answer and any change it offers, as JSON - what the panel is handed. */
    @Column(columnDefinition = "text")
    var result: String? = null,

    @Column(columnDefinition = "text")
    var error: String? = null,

    /** Set by New conversation: kept for the record, no longer drawn. */
    @Column(nullable = false)
    var cleared: Boolean = false,

    @Column(nullable = false)
    var resumes: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "beat_at", nullable = false)
    var beatAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,
)

interface QuickChatAskRepository : JpaRepository<QuickChatAsk, Long> {

    /** A person's newest question in a workspace that New conversation has not put away. */
    fun findFirstByWorkspaceIdAndUserIdAndClearedFalseOrderByIdDesc(workspaceId: Long, userId: String): QuickChatAsk?

    fun findByStatusAndBeatAtBeforeOrderByBeatAtAsc(status: QuickChatAskStatus, before: OffsetDateTime): List<QuickChatAsk>

    @Modifying
    @Query("update QuickChatAsk a set a.cleared = true where a.workspaceId = :workspaceId and a.userId = :userId and a.cleared = false")
    fun clear(@Param("workspaceId") workspaceId: Long, @Param("userId") userId: String): Int

    @Modifying
    @Query("update QuickChatAsk a set a.beatAt = :now where a.id in :ids")
    fun beat(@Param("ids") ids: Collection<Long>, @Param("now") now: OffsetDateTime): Int

    /** Takes a quiet question for this process: 1 where this call did, 0 where somebody else did first. */
    @Modifying
    @Query(
        "update QuickChatAsk a set a.beatAt = :now, a.resumes = a.resumes + 1 " +
            "where a.id = :id and a.beatAt = :seen and a.status = :waiting",
    )
    fun claim(
        @Param("id") id: Long,
        @Param("seen") seen: OffsetDateTime,
        @Param("now") now: OffsetDateTime,
        @Param("waiting") waiting: QuickChatAskStatus,
    ): Int
}

/** The conversation a person's panel last held in a workspace, and the question still being answered, if one is. */
data class QuickChatThread(
    val messages: List<Map<String, String>>,
    /** The question waiting for its answer, by id, or null. */
    val pending: Long?,
    /** Why the newest question was not answered, where it was not. */
    val error: String?,
)

/**
 * Answers the quick chat's questions on the server, so an answer outlives the
 * page that asked and the process that was working on it.
 *
 * The same bargain [ChatTurnLedger] makes for a chat: a question being answered
 * is beaten on every few seconds, one whose beat goes quiet was cut off, and
 * [io.mszymanski.orknux.server.chat.ChatTurnSweeper] asks it again from the
 * conversation on its row. The panel is stateless, so asking again is asking
 * from the start - the model looks up again what it had looked up.
 */
@Service
class QuickChatAsks(
    private val asks: QuickChatAskRepository,
    private val quickChat: QuickChat,
    private val quickChatModels: QuickChatModels,
    private val workspaces: WorkspaceRepository,
    private val claudeCode: io.mszymanski.orknux.server.claude.ClaudeCodeChats,
    private val mapper: ObjectMapper,
    /** Whether this server is stopping, when an answer cut off is one to ask again rather than a failure. */
    private val ledger: ChatTurnLedger,
    transactions: PlatformTransactionManager,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    private val writing = TransactionTemplate(transactions)

    /** The questions this process is answering, for the beat. */
    private val inHand = ConcurrentHashMap.newKeySet<Long>()

    /** Told when a question is settled, so a reader waiting on it hears at once. */
    private val settled = Object()

    private val workers = Executors.newFixedThreadPool(WORKERS) { Thread(it, "quick-chat").apply { isDaemon = true } }

    private var clock: ScheduledExecutorService? = null

    @Volatile
    private var running = false

    /** Records the question and starts answering it. Refusals have been made by the caller. */
    fun ask(workspaceId: Long, userId: String, messages: List<QuickChatMessage>, page: PageContext?, answeredBy: String?): QuickChatAsk {
        val saved = requireNotNull(
            writing.execute {
                asks.save(
                    QuickChatAsk(
                        workspaceId = workspaceId,
                        userId = userId,
                        messages = mapper.writeValueAsString(messages.map { mapOf("role" to it.role, "content" to it.content) }),
                        pageLabel = page?.label?.take(500),
                        pagePath = page?.path?.take(2000),
                        answeredBy = answeredBy,
                    ),
                )
            },
        )
        submit(requireNotNull(saved.id))
        return saved
    }

    /** One question, as its asker may see it; null where it is not theirs or not there. */
    fun of(id: Long, workspaceId: Long, userId: String): QuickChatAsk? =
        asks.findById(id).orElse(null)?.takeIf { it.workspaceId == workspaceId && it.userId == userId }

    /**
     * Waits up to [seconds] for the question to be settled, and answers it as it
     * then stands. How the panel hears its answer without a request held open
     * for as long as the model takes.
     */
    fun awaited(id: Long, workspaceId: Long, userId: String, seconds: Long): QuickChatAsk? {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val held = of(id, workspaceId, userId) ?: return null
            if (held.status != QuickChatAskStatus.WAITING) return held
            val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
            if (left <= 0) return held
            // Bounded, so an answer written by another replica is noticed too.
            synchronized(settled) { settled.wait(minOf(left, 1_000L)) }
        }
    }

    /** Stop: the answer, should it still come, is not kept. */
    fun stop(id: Long, workspaceId: Long, userId: String): Boolean = writing.execute {
        val held = of(id, workspaceId, userId) ?: return@execute false
        if (held.status == QuickChatAskStatus.WAITING) {
            held.status = QuickChatAskStatus.STOPPED
            held.finishedAt = OffsetDateTime.now()
            asks.save(held)
        }
        true
    } == true

    /** New conversation: what was said is put away, and the panel opens empty. */
    fun clear(workspaceId: Long, userId: String) {
        writing.executeWithoutResult { asks.clear(workspaceId, userId) }
    }

    /** The conversation the panel draws on opening: the newest question's, and its answer where it has one. */
    fun thread(workspaceId: Long, userId: String): QuickChatThread {
        val newest = asks.findFirstByWorkspaceIdAndUserIdAndClearedFalseOrderByIdDesc(workspaceId, userId)
            ?: return QuickChatThread(emptyList(), null, null)
        val said = conversationOf(newest).map { mapOf("role" to it.role, "content" to it.content) }
        val answer = newest.result?.let { runCatching { mapper.readTree(it).path("answer").asString() }.getOrNull() }
        return when (newest.status) {
            QuickChatAskStatus.ANSWERED -> QuickChatThread(said + mapOf("role" to "assistant", "content" to answer.orEmpty()), null, null)
            QuickChatAskStatus.WAITING -> QuickChatThread(said, newest.id, null)
            QuickChatAskStatus.FAILED -> QuickChatThread(said, null, newest.error)
            QuickChatAskStatus.STOPPED -> QuickChatThread(said, null, null)
        }
    }

    /** What the panel is handed for a settled question: the same fields the one-request door answers with. */
    fun resultOf(ask: QuickChatAsk): Map<String, Any?> =
        ask.result?.let { runCatching { mapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() }.orEmpty()

    /**
     * Asks again what a restart cut off; called by the sweep. Returns how many
     * it set going.
     */
    fun resumeQuiet(before: OffsetDateTime, most: Int): Int {
        var resumed = 0
        for (quiet in asks.findByStatusAndBeatAtBeforeOrderByBeatAtAsc(QuickChatAskStatus.WAITING, before)) {
            val id = requireNotNull(quiet.id)
            if (id in inHand) {
                writing.executeWithoutResult { asks.beat(listOf(id), OffsetDateTime.now()) }
                continue
            }
            if (quiet.resumes >= most) {
                settle(id, QuickChatAskStatus.FAILED, error = "The server stopped while this was being answered. Ask again.")
                continue
            }
            /*
             * Never asked of a person's Claude Code a second time: it is their
             * conversation, not a model a schedule may run on (AGENTS.md), and it
             * may well have done what it was asked before the server stopped.
             */
            if (quiet.answeredBy == io.mszymanski.orknux.server.chat.ANSWERED_BY_CLAUDE_CODE) {
                settle(
                    id,
                    QuickChatAskStatus.FAILED,
                    error = "The server restarted while your Claude Code was answering, so it was not asked again - it may have done it already. Ask again if it still matters.",
                )
                continue
            }
            if ((writing.execute { asks.claim(id, quiet.beatAt, OffsetDateTime.now(), QuickChatAskStatus.WAITING) } ?: 0) == 0) continue
            log.info("Quick chat question {} was cut off; asking it again", id)
            submit(id)
            resumed++
        }
        return resumed
    }

    /**
     * What the panel says of a failure: our own refusals in their own words -
     * "start Claude Code", say - which the one-request door gave too; anything
     * else by its kind only, since its message is a library's and may say more
     * about the server than a person needs.
     */
    private fun failedWith(failure: Exception): String {
        val ours = failure.javaClass.name.startsWith("io.mszymanski.orknux.") && !failure.message.isNullOrBlank()
        return if (ours) failure.message!! else "The answer stopped on an error in the server: ${failure.javaClass.simpleName}"
    }

    private fun submit(id: Long) {
        if (!inHand.add(id)) return
        try {
            workers.execute {
                try {
                    answer(id)
                } catch (failure: Exception) {
                    if (ledger.stopping) return@execute
                    log.warn("Quick chat question {} could not be answered", id, failure)
                    settle(id, QuickChatAskStatus.FAILED, error = failedWith(failure))
                } finally {
                    inHand.remove(id)
                }
            }
        } catch (refused: java.util.concurrent.RejectedExecutionException) {
            inHand.remove(id)
            log.warn("Quick chat question {} could not be started; it is asked again by the sweep", id)
        }
    }

    private fun answer(id: Long) {
        val ask = asks.findById(id).orElse(null) ?: return
        if (ask.status != QuickChatAskStatus.WAITING) return
        val workspace = workspaces.findById(ask.workspaceId).orElse(null)
            ?: return settle(id, QuickChatAskStatus.FAILED, error = "The workspace is gone.")
        val turns = conversationOf(ask).map { ChatTurn(role = it.role, content = it.content) }
        val page = if (ask.pageLabel != null || ask.pagePath != null) PageContext(ask.pageLabel, ask.pagePath) else null

        if (ask.answeredBy == io.mszymanski.orknux.server.chat.ANSWERED_BY_CLAUDE_CODE) {
            val question = turns.lastOrNull { it.role == "user" }?.content.orEmpty()
            val answered = claudeCode.askAbout(ask.userId, ask.workspaceId, page?.label, page?.path, question)
            return settle(id, QuickChatAskStatus.ANSWERED, result = mapOf("answer" to answered))
        }

        val modelId = quickChatModels.of(workspace)
            ?: return settle(id, QuickChatAskStatus.FAILED, error = "This workspace has no quick chat model.")
        val said = quickChat.answer(modelId, ask.workspaceId, workspace.quickChatMayWrite, page, turns, ask.userId)
        // Cut off by the server stopping: left waiting, for the server that answers next.
        if (ledger.stopping && said.completion !is ChatCompletion.Answered) return
        when (val answer = said.completion) {
            is ChatCompletion.Answered -> settle(id, QuickChatAskStatus.ANSWERED, result = QuickChatAPI.answerOf(answer, said))
            is ChatCompletion.Failed -> settle(id, QuickChatAskStatus.FAILED, error = answer.reason)
            is ChatCompletion.CalledTools -> settle(id, QuickChatAskStatus.FAILED, error = "The model asked for a tool and stopped.")
        }
    }

    /** Writes how a question ended, unless it was stopped meanwhile - a stopped question keeps no answer. */
    private fun settle(id: Long, status: QuickChatAskStatus, result: Map<String, Any?>? = null, error: String? = null) {
        writing.executeWithoutResult {
            val held = asks.findById(id).orElse(null) ?: return@executeWithoutResult
            if (held.status != QuickChatAskStatus.WAITING) return@executeWithoutResult
            held.status = status
            held.result = result?.let { mapper.writeValueAsString(it) }
            held.error = error
            held.finishedAt = OffsetDateTime.now()
            asks.save(held)
        }
        synchronized(settled) { settled.notifyAll() }
    }

    private fun conversationOf(ask: QuickChatAsk): List<QuickChatMessage> =
        runCatching {
            mapper.readTree(ask.messages).values().map {
                QuickChatMessage(if (it.path("role").asString() == "assistant") "assistant" else "user", it.path("content").asString())
            }
        }.getOrElse { emptyList() }

    override fun start() {
        clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "quick-chat-beat").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ beat() }, ChatTurnLedger.BEAT_SECONDS, ChatTurnLedger.BEAT_SECONDS, TimeUnit.SECONDS)
        }
        running = true
    }

    private fun beat() {
        val held = inHand.toSet()
        if (held.isEmpty()) return
        runCatching { writing.executeWithoutResult { asks.beat(held, OffsetDateTime.now()) } }
            .onFailure { log.warn("Could not say {} quick chat question(s) are still being answered", held.size, it) }
    }

    /** Hands over what is being answered, as [ChatTurnLedger.stop] does for chats. */
    override fun stop() {
        running = false
        clock?.shutdownNow()
        clock = null
        val held = inHand.toSet()
        if (held.isNotEmpty()) {
            runCatching { writing.executeWithoutResult { asks.beat(held, ChatTurnLedger.HANDED_OVER) } }
                .onFailure { log.warn("Could not hand over the quick chat questions being answered", it) }
        }
    }

    override fun isRunning(): Boolean = running

    /** Just after the ledger, which is what says the server is stopping. */
    override fun getPhase(): Int = Int.MAX_VALUE - 11

    private companion object {
        /** Questions answered at once; the rest wait their turn on the row. */
        const val WORKERS = 8
    }
}
