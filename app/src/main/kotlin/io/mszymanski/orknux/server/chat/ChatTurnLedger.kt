package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.cluster.ClusterLeader
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A chat whose answer is being written, as the database knows it.
 *
 * One row per chat with a turn in flight, and [beatAt] is the last time the
 * process writing it said it still was. See [ChatTurnLedger].
 */
@Entity
@Table(name = "chat_turn")
class ChatTurnRecord(
    @Id
    @Column(name = "chat_id")
    val chatId: Long,

    @Column(name = "started_at", nullable = false)
    val startedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "beat_at", nullable = false)
    var beatAt: OffsetDateTime = OffsetDateTime.now(),

    /** How many times a sweep has picked this turn up again. */
    @Column(nullable = false)
    var resumes: Int = 0,
)

interface ChatTurnRepository : JpaRepository<ChatTurnRecord, Long> {

    /** The turns nobody has said anything about since [before], the quietest first. */
    fun findByBeatAtBeforeOrderByBeatAtAsc(before: OffsetDateTime): List<ChatTurnRecord>

    /** Says these turns are still being written. */
    @Modifying
    @Query("update ChatTurnRecord t set t.beatAt = :now where t.chatId in :ids")
    fun beat(@Param("ids") ids: Collection<Long>, @Param("now") now: OffsetDateTime): Int

    /**
     * Takes a quiet turn for this process, where nobody took it first: 1 where
     * this call did, 0 where its beat moved since it was read.
     */
    @Modifying
    @Query(
        "update ChatTurnRecord t set t.beatAt = :now, t.resumes = t.resumes + 1 " +
            "where t.chatId = :id and t.beatAt = :seen",
    )
    fun claim(@Param("id") id: Long, @Param("seen") seen: OffsetDateTime, @Param("now") now: OffsetDateTime): Int
}

/**
 * Makes a chat's answer survive the server that was writing it.
 *
 * An answer is written on a thread of the process that took the message
 * (#335), and until this nothing outside that thread knew it was being
 * written: a restart, an update or a crash in the middle left the chat ending
 * on the question for good, with nothing anywhere that would ever look at it
 * again. Now every turn [ChatGenerations] registers is a row in `chat_turn`,
 * kept alive by a beat every few seconds and deleted when the turn ends - by
 * its answer being kept, by Stop, or by a failure that was told to the page.
 *
 * **What survives is the row, not the thread.** A row whose beat has gone quiet
 * for [Tunable.CHAT_RESUME_AFTER_SECONDS] is a turn whose process is gone, and
 * [ChatTurnSweeper] answers it again through [ChatWake.resume]: the person's
 * message is in the thread already and an agent's rounds are in its session, so
 * the turn is rebuilt from what was written down, as a woken turn is - and an
 * open page picks it up the same way. It is the inline task engine's bargain,
 * made for chats: nothing is kept in the thread that dies, so losing it loses
 * only the round it was on.
 *
 * **A server stopping on purpose hands its answers over at once.** It is told
 * first among everything that stops ([getPhase]), and from then on a turn
 * that ends - most of them now failing, as the connections under them close -
 * leaves its row where it is, and every row it holds is marked quiet, so the
 * server that comes up next (or a replica still running) answers them on its
 * first sweep rather than a minute later. What a stopping server must not do
 * is put a regenerated answer back or tell a page its turn failed, and the
 * streaming door asks [stopping] for that.
 */
@Component
class ChatTurnLedger(
    private val turns: ChatTurnRepository,
    private val generations: ChatGenerations,
    transactions: PlatformTransactionManager,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    private val writing = TransactionTemplate(transactions)

    /** Set once this process has begun to stop; see the class note. */
    @Volatile
    private var stoppingNow: Boolean = false

    val stopping: Boolean get() = stoppingNow

    @Volatile
    private var running = false

    private var clock: ScheduledExecutorService? = null

    init {
        generations.whenBusy { chatId, generation -> if (generation.durable) generation.take = began(chatId) }
        /*
         * Not while another turn on the chat is being written: a turn the person
         * queued, or a wake, can register between the last one's release and
         * this listener, and deleting by chat would take its row with it.
         */
        generations.whenIdle { chatId -> if (!stopping && !generations.answering(chatId)) ended(chatId) }
    }

    /**
     * A turn on this chat is being written: its row, made or brought back to
     * life. A resumed turn keeps its count, and the count is answered - the
     * take [ChatGeneration.take] holds; null where the row could not be written.
     */
    fun began(chatId: Long): Int? =
        runCatching {
            writing.execute {
                val held = turns.findById(chatId).orElse(null)
                if (held == null) {
                    turns.save(ChatTurnRecord(chatId = chatId)).resumes
                } else {
                    held.beatAt = OffsetDateTime.now()
                    turns.save(held).resumes
                }
            }
        }.onFailure { log.warn("Chat {}'s turn could not be written down; a restart would lose it", chatId, it) }.getOrNull()

    /** The turn is over, however it ended: nothing to answer again. */
    fun ended(chatId: Long) {
        runCatching { writing.executeWithoutResult { if (turns.existsById(chatId)) turns.deleteById(chatId) } }
            .onFailure { log.warn("Chat {}'s finished turn could not be let go of", chatId, it) }
    }

    /** Says every turn this process is writing is still being written. */
    fun beat() {
        val held = generations.answeringChats()
        if (held.isEmpty()) return
        runCatching {
            val beaten = writing.execute { turns.beat(held, OffsetDateTime.now()) } ?: 0
            // A row lost while its turn still runs - let go of by a turn that ended beside it - is written again.
            if (beaten < held.size) held.filterNot { turns.existsById(it) }.forEach { lost -> generations.current(lost)?.takeIf { it.durable }?.let { it.take = began(lost) } }
        }.onFailure { log.warn("Could not say {} chat turn(s) are still being answered", held.size, it) }
    }

    override fun start() {
        stoppingNow = false
        clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "chat-turn-beat").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching { beat() } }, BEAT_SECONDS, BEAT_SECONDS, TimeUnit.SECONDS) }
        running = true
    }

    /**
     * Hands this process's turns over: marked quiet as of long ago, so the
     * next sweep anywhere takes them. Before the web server and the
     * connections stop, which is what [getPhase] is for.
     */
    override fun stop() {
        stoppingNow = true
        running = false
        clock?.shutdownNow()
        clock = null
        val held = generations.answeringChats()
        if (held.isEmpty()) return
        runCatching { writing.executeWithoutResult { turns.beat(held, HANDED_OVER) } }
            .onSuccess { log.info("Handing over {} chat answer(s) still being written, to be answered again", held.size) }
            .onFailure { log.warn("Could not hand over the chat answers still being written", it) }
    }

    override fun isRunning(): Boolean = running

    /** Stops first, so the turns still running are seen as cut off rather than as failed. */
    override fun getPhase(): Int = Int.MAX_VALUE - 10

    companion object {
        /** How often a live turn says so; a third of the shortest quiet a sweep acts on. */
        const val BEAT_SECONDS = 5L

        /** What a turn handed over on a stop is marked as last heard from: long enough ago for any sweep. */
        val HANDED_OVER: OffsetDateTime = OffsetDateTime.parse("2000-01-01T00:00:00Z")
    }
}

/**
 * Answers again what a restart or a crash cut off: chat turns from
 * [ChatTurnLedger], and quick chat questions from
 * [io.mszymanski.orknux.server.mcp.QuickChatAsks].
 *
 * One pass on the replica that leads, a few seconds after the process comes
 * up and then every few seconds. The claim is a conditional update of the
 * beat, so two passes that read the same quiet row cannot both take it.
 */
@Component
class ChatTurnSweeper(
    private val turns: ChatTurnRepository,
    private val chats: ChatSessionRepository,
    private val generations: ChatGenerations,
    private val wake: ChatWake,
    private val tuning: Tuning,
    @param:Lazy private val quickChats: io.mszymanski.orknux.server.mcp.QuickChatAsks,
    private val claudeCode: org.springframework.beans.factory.ObjectProvider<io.mszymanski.orknux.server.claude.ClaudeCodeChats>,
    @param:Value("\${orknux.chat.resume.enabled:true}") private val enabled: Boolean,
    transactions: PlatformTransactionManager,
    /** Which replica sweeps; alone where nothing says otherwise. */
    private val leader: ClusterLeader = ClusterLeader.alone(),
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    private val writing = TransactionTemplate(transactions)

    private var clock: ScheduledExecutorService? = null

    @Volatile
    private var running = false

    override fun start() {
        if (!enabled) {
            log.info("Chat answers cut off by a restart are not looked for on a timer")
            return
        }
        clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "chat-turn-sweep").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ pass() }, FIRST_SECONDS, EVERY_SECONDS, TimeUnit.SECONDS) }
        running = true
    }

    override fun stop() {
        running = false
        clock?.shutdownNow()
        clock = null
    }

    override fun isRunning(): Boolean = running

    private fun pass() {
        if (!leader.leads()) return
        runCatching { sweep() }.onFailure { log.warn("Could not look for cut-off chat answers", it) }
    }

    /** One pass. Returns how many answers it set going again, which is what a test asserts on. */
    fun sweep(): Int = sweepChats() + quickChats.resumeQuiet(quietSince(), tuning[Tunable.CHAT_RESUMES])

    private fun quietSince(): OffsetDateTime = OffsetDateTime.now().minusSeconds(tuning[Tunable.CHAT_RESUME_AFTER_SECONDS].toLong())

    private fun sweepChats(): Int {
        val most = tuning[Tunable.CHAT_RESUMES]
        var resumed = 0
        for (turn in turns.findByBeatAtBeforeOrderByBeatAtAsc(quietSince())) {
            val id = turn.chatId
            // Being written here after all - a beat that failed to land - so said to be.
            if (generations.answering(id)) {
                writing.executeWithoutResult { turns.beat(listOf(id), OffsetDateTime.now()) }
                continue
            }
            val chat = chats.findById(id).orElse(null)
            if (chat == null || claudeCode.getIfAvailable()?.isClaudeCode(chat) == true) {
                writing.executeWithoutResult { turns.deleteById(id) }
                continue
            }
            if (turn.resumes >= most) {
                log.warn("Chat {}'s answer was cut off {} time(s) and is not answered again", id, turn.resumes)
                writing.executeWithoutResult { turns.deleteById(id) }
                continue
            }
            val taken = writing.execute { turns.claim(id, turn.beatAt, OffsetDateTime.now()) } ?: 0
            if (taken == 0) continue
            log.info("Chat {}'s answer was cut off; answering it again", id)
            if (wake.resume(id)) resumed++ else writing.executeWithoutResult { turns.deleteById(id) }
        }
        return resumed
    }

    private companion object {
        const val FIRST_SECONDS = 10L
        const val EVERY_SECONDS = 15L
    }
}
