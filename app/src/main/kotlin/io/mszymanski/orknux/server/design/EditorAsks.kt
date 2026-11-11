package io.mszymanski.orknux.server.design

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
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

enum class EditorAskStatus { WAITING, ANSWERED, FAILED }

/**
 * One question put to an editor's chat - the conversation down the right of a
 * document - and, once there is one, its answer.
 *
 * The editor used to wait for the answer on the request that asked, so a
 * reload, a closed tab or a dropped connection threw the answer away while the
 * server went on writing it: the page came back with the question and nothing
 * under it. Now the question is kept, answered whoever is waiting, and read
 * from here - by the page that asked, or by the one that reloaded it.
 */
@Entity
@Table(name = "editor_ask")
class EditorAsk(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false, length = 120)
    val userId: String,

    /** Which editor: `document`, `sheet`, `wireframe` or `diagram`. */
    @Column(nullable = false, length = 16)
    val kind: String,

    @Column(name = "item_id", nullable = false)
    val itemId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: EditorAskStatus = EditorAskStatus.WAITING,

    /** The answer as the editor reads it, JSON. */
    @Column(columnDefinition = "text")
    var result: String? = null,

    @Column(columnDefinition = "text")
    var error: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "beat_at", nullable = false)
    var beatAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,
)

interface EditorAskRepository : JpaRepository<EditorAsk, Long> {
    fun findFirstByUserIdAndKindAndItemIdOrderByIdDesc(userId: String, kind: String, itemId: Long): EditorAsk?

    /** What one person asked of one item before: only the newest question is kept. */
    @Modifying
    @Query("delete from EditorAsk a where a.userId = :userId and a.kind = :kind and a.itemId = :itemId and a.id <> :keep")
    fun forgetBefore(@Param("userId") userId: String, @Param("kind") kind: String, @Param("itemId") itemId: Long, @Param("keep") keep: Long): Int

    @Modifying
    @Query("update EditorAsk a set a.beatAt = :now where a.id in :ids")
    fun beat(@Param("ids") ids: Collection<Long>, @Param("now") now: OffsetDateTime): Int
}

/**
 * Answers editors' questions on threads of its own, and keeps the answers for
 * the page to read.
 *
 * Each question runs as the person who asked it - the security context of the
 * request is carried to the worker - so the work is exactly what the asking
 * request used to do; only who waits for it changed. A question being
 * answered is beaten on every few seconds, and one whose beat has gone quiet
 * was cut off by a restart: it is reported as failed, in words, rather than
 * waited on for ever. It is not asked again by itself - the model may have
 * written half of a change already, and the person is there to say whether to
 * go on.
 */
@Service
class EditorAsks(
    private val asks: EditorAskRepository,
    private val mapper: ObjectMapper,
    transactions: PlatformTransactionManager,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    private val writing = TransactionTemplate(transactions)

    private val inHand = ConcurrentHashMap.newKeySet<Long>()

    private val settled = Object()

    private val workers = Executors.newFixedThreadPool(WORKERS) { Thread(it, "editor-ask").apply { isDaemon = true } }

    private var clock: ScheduledExecutorService? = null

    @Volatile
    private var running = false

    /** Keeps the question and starts [work] on it as the person asking; answers its id. */
    fun ask(userId: String, kind: String, itemId: Long, work: () -> Map<String, Any?>): Long {
        val saved = requireNotNull(writing.execute { asks.save(EditorAsk(userId = userId, kind = kind, itemId = itemId)) })
        val id = requireNotNull(saved.id)
        writing.executeWithoutResult { asks.forgetBefore(userId, kind, itemId, id) }
        val asking = SecurityContextHolder.getContext()
        inHand += id
        workers.execute {
            SecurityContextHolder.setContext(asking)
            try {
                val answer = work()
                settle(id, EditorAskStatus.ANSWERED, result = mapper.writeValueAsString(answer))
            } catch (failure: Exception) {
                log.warn("An editor's question {} could not be answered", id, failure)
                settle(id, EditorAskStatus.FAILED, error = failedWith(failure))
            } finally {
                inHand.remove(id)
                SecurityContextHolder.clearContext()
            }
        }
        return id
    }

    /** The question as its asker may see it, waiting up to [seconds] for it to be settled. */
    fun awaited(id: Long, userId: String, seconds: Long): EditorAsk? {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val held = asks.findById(id).orElse(null)?.takeIf { it.userId == userId } ?: return null
            if (held.status != EditorAskStatus.WAITING) return held
            if (id !in inHand && held.beatAt.isBefore(OffsetDateTime.now().minusSeconds(QUIET_SECONDS))) {
                settle(id, EditorAskStatus.FAILED, error = CUT_OFF)
                continue
            }
            val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
            if (left <= 0) return held
            synchronized(settled) { settled.wait(minOf(left, 1_000L)) }
        }
    }

    /** The newest question [userId] put to this item, where it is still being answered. */
    fun pending(userId: String, kind: String, itemId: Long): EditorAsk? =
        asks.findFirstByUserIdAndKindAndItemIdOrderByIdDesc(userId, kind, itemId)?.takeIf { it.status == EditorAskStatus.WAITING }

    /** What the editor reads of a settled question. */
    fun viewOf(ask: EditorAsk): Map<String, Any?> = buildMap {
        put("id", ask.id.toString())
        put("status", ask.status.name)
        ask.result?.let { result -> runCatching { mapper.readValue(result, Map::class.java) }.getOrNull()?.forEach { (key, value) -> put(key.toString(), value) } }
        ask.error?.let { put("error", it) }
    }

    private fun settle(id: Long, status: EditorAskStatus, result: String? = null, error: String? = null) {
        writing.executeWithoutResult {
            val held = asks.findById(id).orElse(null) ?: return@executeWithoutResult
            if (held.status != EditorAskStatus.WAITING) return@executeWithoutResult
            held.status = status
            held.result = result
            held.error = error
            held.finishedAt = OffsetDateTime.now()
            asks.save(held)
        }
        synchronized(settled) { settled.notifyAll() }
    }

    /** Our own refusals in their own words; anything else by its kind, since a library's message says more than a person needs. */
    private fun failedWith(failure: Exception): String {
        val ours = failure.javaClass.name.startsWith("io.mszymanski.orknux.") && !failure.message.isNullOrBlank()
        return if (ours) failure.message!! else "The answer stopped on an error in the server: ${failure.javaClass.simpleName}"
    }

    override fun start() {
        clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "editor-ask-beat").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay(
                {
                    val held = inHand.toSet()
                    if (held.isNotEmpty()) runCatching { writing.executeWithoutResult { asks.beat(held, OffsetDateTime.now()) } }
                },
                BEAT_SECONDS,
                BEAT_SECONDS,
                TimeUnit.SECONDS,
            )
        }
        running = true
    }

    override fun stop() {
        running = false
        clock?.shutdownNow()
        clock = null
    }

    override fun isRunning(): Boolean = running

    companion object {
        const val WORKERS = 8
        const val BEAT_SECONDS = 5L

        /** How long a question goes unbeaten before it is taken for cut off: several beats, so one slow write is not. */
        const val QUIET_SECONDS = 45L

        const val CUT_OFF = "The server stopped while this was being answered, so the answer was lost. Ask again."
    }
}

/** What the document editor sends: the conversation, the model, and whether to only read. */
data class DocumentAskRequest @com.fasterxml.jackson.annotation.JsonCreator constructor(
    @com.fasterxml.jackson.annotation.JsonProperty("modelId") val modelId: Long,
    @com.fasterxml.jackson.annotation.JsonProperty("messages") val messages: List<DocumentChatTurnInput>,
    @com.fasterxml.jackson.annotation.JsonProperty("readOnly") val readOnly: Boolean? = null,
)

/** What a sheet's, wireframe's or diagram's editor sends: the conversation and the model. */
data class EditorAskRequest @com.fasterxml.jackson.annotation.JsonCreator constructor(
    @com.fasterxml.jackson.annotation.JsonProperty("modelId") val modelId: Long,
    @com.fasterxml.jackson.annotation.JsonProperty("messages") val messages: List<DocumentChatTurnInput>,
)

/**
 * The editors' chats, asked and read through kept questions.
 *
 * The answer is what the GraphQL mutation answers, without the document: the
 * editor reads the document again where the answer says it changed.
 */
@RestController
class EditorAskAPI(
    private val asks: EditorAsks,
    private val documents: DocumentAPI,
    @param:org.springframework.context.annotation.Lazy private val sheets: SheetAPI,
    @param:org.springframework.context.annotation.Lazy private val wireframes: WireframeAPI,
    @param:org.springframework.context.annotation.Lazy private val diagrams: io.mszymanski.orknux.server.diagram.DiagramAPI,
) {

    /** A sheet's chat: the same kept question as a document's, answered by the sheet's own mutation. */
    @PostMapping("/api/design/sheets/{id}/asks")
    fun askSheet(@PathVariable id: Long, @RequestBody asked: EditorAskRequest): ResponseEntity<Any> =
        kept(KIND_SHEET, id, { sheets.find(id) }) {
            val answered = sheets.askSheet(id, asked.modelId, asked.messages.map { SheetChatTurnInput(it.role, it.content, it.attachmentIds) })
            mapOf("answer" to answered.answer, "changed" to answered.changed, "revision" to answered.sheet.revision)
        }

    /** A wireframe's chat. */
    @PostMapping("/api/design/wireframes/{id}/asks")
    fun askWireframe(@PathVariable id: Long, @RequestBody asked: EditorAskRequest): ResponseEntity<Any> =
        kept(KIND_WIREFRAME, id, { wireframes.find(id) }) {
            val answered = wireframes.askWireframe(id, asked.modelId, asked.messages.map { WireframeChatTurnInput(it.role, it.content, it.attachmentIds) })
            mapOf("answer" to answered.answer, "changed" to answered.changed, "revision" to answered.wireframe.revision)
        }

    /** A diagram's chat. */
    @PostMapping("/api/explore/diagrams/{id}/asks")
    fun askDiagram(@PathVariable id: Long, @RequestBody asked: EditorAskRequest): ResponseEntity<Any> =
        kept(KIND_DIAGRAM, id, { diagrams.find(id) }) {
            val answered = diagrams.askDiagram(
                id,
                asked.modelId,
                asked.messages.map { io.mszymanski.orknux.server.diagram.DiagramChatTurnInput(it.role, it.content, it.attachmentIds) },
            )
            mapOf("answer" to answered.answer, "changed" to answered.changed, "revision" to answered.diagram.revision)
        }

    /** Refused now where the item is not there or not this person's to see; otherwise kept and answered on a worker. */
    private fun kept(kind: String, id: Long, visible: () -> Any, work: () -> Map<String, Any?>): ResponseEntity<Any> {
        val person = person() ?: return refuse(HttpStatus.UNAUTHORIZED, "Nobody is signed in.")
        runCatching { visible() }.getOrNull() ?: return refuse(HttpStatus.NOT_FOUND, "There is no such $kind.")
        val askId = asks.ask(person, kind, id, work)
        return ResponseEntity.ok(mapOf("id" to askId.toString(), "status" to EditorAskStatus.WAITING.name))
    }

    @PostMapping("/api/design/documents/{id}/asks")
    fun askDocument(@PathVariable id: Long, @RequestBody asked: DocumentAskRequest): ResponseEntity<Any> {
        val person = person() ?: return refuse(HttpStatus.UNAUTHORIZED, "Nobody is signed in.")
        // Refused now rather than on the worker: a document that is not there, or not theirs to see.
        runCatching { documents.find(id) }.getOrNull() ?: return refuse(HttpStatus.NOT_FOUND, "There is no such document.")
        val askId = asks.ask(person, KIND_DOCUMENT, id) {
            val answered = documents.askDocument(id, asked.modelId, asked.messages, asked.readOnly)
            mapOf(
                "answer" to answered.answer,
                "changed" to answered.changed,
                "revision" to answered.document.revision,
                "compacted" to answered.compacted?.let { mapOf("summary" to it.summary, "replaced" to it.replaced, "kept" to it.kept) },
            )
        }
        return ResponseEntity.ok(mapOf("id" to askId.toString(), "status" to EditorAskStatus.WAITING.name))
    }

    /** The question being answered for this person on this document, if there is one: what a page opened again follows. */
    @GetMapping("/api/design/documents/{id}/asks/pending")
    fun pendingOnDocument(@PathVariable id: Long): ResponseEntity<Any> {
        val person = person() ?: return refuse(HttpStatus.UNAUTHORIZED, "Nobody is signed in.")
        val held = asks.pending(person, KIND_DOCUMENT, id) ?: return ResponseEntity.ok(mapOf("id" to null))
        return ResponseEntity.ok(mapOf("id" to held.id.toString()))
    }

    /** Where a question has got to, held up to [wait] seconds (at most thirty) for it to be settled. */
    @GetMapping("/api/editor-asks/{id}")
    fun asked(@PathVariable id: Long, @RequestParam(required = false, defaultValue = "0") wait: Long): ResponseEntity<Any> {
        val person = person() ?: return refuse(HttpStatus.UNAUTHORIZED, "Nobody is signed in.")
        val held = asks.awaited(id, person, wait.coerceIn(0, 30)) ?: return refuse(HttpStatus.NOT_FOUND, "There is no such question.")
        return ResponseEntity.ok(asks.viewOf(held))
    }

    private fun person(): String? = SecurityContextHolder.getContext().authentication?.takeIf { it.isAuthenticated }?.name

    private fun refuse(status: HttpStatus, why: String): ResponseEntity<Any> = ResponseEntity.status(status).body(mapOf("error" to why))

    companion object {
        const val KIND_DOCUMENT = "document"
        const val KIND_SHEET = "sheet"
        const val KIND_WIREFRAME = "wireframe"
        const val KIND_DIAGRAM = "diagram"
    }
}
