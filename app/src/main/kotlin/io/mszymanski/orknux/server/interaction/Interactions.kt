package io.mszymanski.orknux.server.interaction

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.llm.LlmSessionNotFoundException
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime

/**
 * What acts on the answer to one [kind] of [InteractionRequest].
 *
 * The one thing a new kind of question needs: the request is asked through
 * [Interactions.ask], drawn by the interface from its options alone, and
 * answered here.
 */
interface InteractionHandler {
    val kind: String

    /**
     * Acts on [option] being chosen by [by], and answers the line that says
     * what became of it. Throws to refuse; the request stays unanswered.
     */
    fun answered(request: InteractionRequest, option: String, by: String): String

    /**
     * The same, for a request that takes several options or the person's own
     * words. Only a handler that asks such a question needs it; the rest are
     * only ever answered one option at a time.
     */
    fun answered(request: InteractionRequest, options: List<String>, text: String?, by: String): String =
        answered(request, options.single(), by)
}

/** The items of a request, as the column holds them; see [InteractionRequest.items]. */
object InteractionItems {
    private val json = JsonMapper.builder().build()

    fun encode(items: List<InteractionItem>): String? =
        if (items.isEmpty()) null else json.writeValueAsString(
            items.map { mapOf("name" to it.name, "kind" to it.kind, "description" to it.description) },
        )

    fun decode(text: String?): List<InteractionItem> = if (text.isNullOrBlank()) emptyList() else runCatching {
        json.readTree(text).values().map { one ->
            InteractionItem(
                name = one.path("name").stringValue(),
                kind = one.path("kind").takeIf { it.isString }?.stringValue(),
                description = one.path("description").takeIf { it.isString }?.stringValue(),
            )
        }
    }.getOrDefault(emptyList())
}

/** The options of a request, as the column holds them. */
object InteractionOptions {
    private val json = JsonMapper.builder().build()

    fun encode(options: List<InteractionOption>): String =
        json.writeValueAsString(
            options.map { mapOf("id" to it.id, "label" to it.label, "style" to it.style, "description" to it.description) },
        )

    fun decode(text: String): List<InteractionOption> = runCatching {
        json.readTree(text).values().map { one ->
            InteractionOption(
                id = one.path("id").stringValue(),
                label = one.path("label").stringValue(),
                style = one.path("style").takeIf { it.isString }?.stringValue(),
                description = one.path("description").takeIf { it.isString }?.stringValue(),
            )
        }
    }.getOrDefault(emptyList())

    /** The ids a request offers, for a handler checking which answer it put on offer. */
    fun ids(text: String): Set<String> = decode(text).map { it.id }.toSet()
}

@Service
class Interactions(
    private val requests: InteractionRequestRepository,
    handlers: List<InteractionHandler>,
) {
    private val byKind = handlers.associateBy { it.kind }

    /** Puts a question in [llmSessionId]'s conversation. */
    @Transactional
    fun ask(
        workspaceId: Long,
        llmSessionId: Long,
        kind: String,
        subjectId: Long?,
        askedBy: String?,
        question: String,
        options: List<InteractionOption>,
        /** What it is about, item by item, drawn as a list under the question; empty for none. */
        items: List<InteractionItem> = emptyList(),
        /** Whether several options may be chosen at once. */
        multiple: Boolean = false,
        /** Whether the person may answer in their own words. */
        allowsText: Boolean = false,
    ): InteractionRequest {
        require(kind in byKind) { "No handler answers requests of kind $kind" }
        return requests.save(
            InteractionRequest(
                workspaceId = workspaceId,
                llmSessionId = llmSessionId,
                kind = kind,
                subjectId = subjectId,
                askedBy = askedBy,
                question = question,
                options = InteractionOptions.encode(options),
                items = InteractionItems.encode(items),
                multiple = multiple,
                allowsText = allowsText,
            ),
        )
    }

    /** Hands [option] to the request's handler, and records who chose it and what became of it. */
    @Transactional
    fun answer(request: InteractionRequest, option: String, by: String): InteractionRequest =
        answer(request, listOf(option), null, by)

    /**
     * The general answer: any options the request offers, as many as it allows,
     * and the person's own words where it takes them. [InteractionRequest.chosen]
     * holds the ids joined by commas, or [IN_OWN_WORDS] where only words came.
     */
    @Transactional
    fun answer(request: InteractionRequest, options: List<String>, text: String?, by: String): InteractionRequest {
        if (request.chosen != null) throw InteractionAlreadyAnsweredException(requireNotNull(request.id))
        val offered = InteractionOptions.ids(request.options)
        val chosen = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        chosen.firstOrNull { it !in offered }?.let { throw InteractionOptionUnknownException(it) }
        val words = text?.trim()?.ifEmpty { null }
        if (words != null && !request.allowsText) throw InteractionAnswerInvalidException("This request takes one of its options, not words")
        if (chosen.size > 1 && !request.multiple) throw InteractionAnswerInvalidException("This request takes one option")
        if (chosen.isEmpty() && words == null) throw InteractionAnswerInvalidException("Choose an option or write an answer")
        val handler = byKind[request.kind] ?: error("No handler answers requests of kind ${request.kind}")
        request.outcome = if (words == null && chosen.size == 1) {
            handler.answered(request, chosen.single(), by)
        } else {
            handler.answered(request, chosen, words, by)
        }
        request.chosen = chosen.joinToString(",").ifEmpty { IN_OWN_WORDS }
        request.answerText = words
        request.chosenBy = by
        request.chosenAt = OffsetDateTime.now()
        return requests.save(request)
    }

    /**
     * Says what became of [kind]'s requests about [subjectId] after a change
     * made somewhere else, so the question in the conversation reads the same
     * as the place it was changed from.
     */
    @Transactional
    fun settled(kind: String, subjectId: Long, outcome: String, by: String) {
        requests.findByKindAndSubjectId(kind, subjectId).forEach { request ->
            request.outcome = outcome
            if (request.chosen == null) {
                request.chosen = SETTLED_ELSEWHERE
                request.chosenBy = by
                request.chosenAt = OffsetDateTime.now()
            }
            requests.save(request)
        }
    }

    fun of(llmSessionId: Long): List<InteractionRequest> = requests.findByLlmSessionIdOrderByCreatedAtAscIdAsc(llmSessionId)

    companion object {
        /** What [InteractionRequest.chosen] says when the matter was decided somewhere other than the request. */
        const val SETTLED_ELSEWHERE = "settled"

        /** What [InteractionRequest.chosen] says when the person answered in words and chose no option. */
        const val IN_OWN_WORDS = "own-words"
    }
}

data class InteractionOptionView(val id: String, val label: String, val style: String?, val description: String?)

data class InteractionItemView(val name: String, val kind: String?, val description: String?)

data class InteractionRequestView(
    val id: Long,
    val kind: String,
    val askedBy: String?,
    val question: String,
    val options: List<InteractionOptionView>,
    val items: List<InteractionItemView>,
    val chosen: String?,
    val chosenBy: String?,
    val chosenAt: String?,
    val outcome: String?,
    val createdAt: String,
    val multiple: Boolean,
    val allowsText: Boolean,
    val text: String?,
) {
    companion object {
        fun of(request: InteractionRequest) = InteractionRequestView(
            id = requireNotNull(request.id),
            kind = request.kind,
            askedBy = request.askedBy,
            question = request.question,
            options = InteractionOptions.decode(request.options).map { InteractionOptionView(it.id, it.label, it.style, it.description) },
            items = InteractionItems.decode(request.items).map { InteractionItemView(it.name, it.kind, it.description) },
            chosen = request.chosen,
            chosenBy = request.chosenBy,
            chosenAt = request.chosenAt?.toString(),
            outcome = request.outcome,
            createdAt = request.createdAt.toString(),
            multiple = request.multiple,
            allowsText = request.allowsText,
            text = request.answerText,
        )
    }
}

@Controller
class InteractionAPI(
    private val interactions: Interactions,
    private val requests: InteractionRequestRepository,
    private val llmSessions: LlmSessionRepository,
    private val access: WorkspaceAccess,
    /** Whose chat a session is; somebody else's is answered as not there. */
    private val visibility: io.mszymanski.orknux.server.llm.SessionVisibility,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun sessionRequests(@Argument sessionId: Long): List<InteractionRequestView> {
        visibility.requireReadable(sessionId)
        return interactions.of(sessionId).map(InteractionRequestView::of)
    }

    @MutationMapping
    fun answerRequest(
        @Argument id: Long,
        @Argument option: String?,
        @Argument options: List<String>?,
        @Argument text: String?,
    ): InteractionRequestView {
        val request = requests.findByIdOrNull(id) ?: throw InteractionRequestNotFoundException(id)
        access.requireVisible(request.workspaceId)
        val by = SecurityContextHolder.getContext().authentication?.name ?: "system"
        val chosen = listOfNotNull(option) + options.orEmpty()
        val answered = interactions.answer(request, chosen, text, by)
        val labels = InteractionOptions.decode(request.options).associate { it.id to it.label }
        val label = (chosen.map { labels[it] ?: it } + listOfNotNull(text?.trim()?.ifEmpty { null }?.let { "\"$it\"" }))
            .joinToString(", ")
        auditRecorder.record(
            request.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Request ${request.id} from ${request.askedBy ?: "an agent"} answered: $label",
        )
        return InteractionRequestView.of(answered)
    }
}

@Component
class InteractionExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val type = when (exception) {
            is InteractionOptionUnknownException, is InteractionAlreadyAnsweredException,
            is InteractionAnswerInvalidException -> ErrorType.BAD_REQUEST
            is InteractionRequestNotFoundException -> ErrorType.NOT_FOUND
            else -> return null
        }
        return refused(exception, type, environment)
    }
}
