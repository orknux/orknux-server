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
internal object InteractionOptions {
    private val json = JsonMapper.builder().build()

    fun encode(options: List<InteractionOption>): String =
        json.writeValueAsString(options.map { mapOf("id" to it.id, "label" to it.label, "style" to it.style) })

    fun decode(text: String): List<InteractionOption> = runCatching {
        json.readTree(text).values().map { one ->
            InteractionOption(
                id = one.path("id").stringValue(),
                label = one.path("label").stringValue(),
                style = one.path("style").takeIf { it.isString }?.stringValue(),
            )
        }
    }.getOrDefault(emptyList())
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
            ),
        )
    }

    /** Hands [option] to the request's handler, and records who chose it and what became of it. */
    @Transactional
    fun answer(request: InteractionRequest, option: String, by: String): InteractionRequest {
        if (request.chosen != null) throw InteractionAlreadyAnsweredException(requireNotNull(request.id))
        if (InteractionOptions.decode(request.options).none { it.id == option }) throw InteractionOptionUnknownException(option)
        val handler = byKind[request.kind] ?: error("No handler answers requests of kind ${request.kind}")
        request.outcome = handler.answered(request, option, by)
        request.chosen = option
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
    }
}

data class InteractionOptionView(val id: String, val label: String, val style: String?)

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
) {
    companion object {
        fun of(request: InteractionRequest) = InteractionRequestView(
            id = requireNotNull(request.id),
            kind = request.kind,
            askedBy = request.askedBy,
            question = request.question,
            options = InteractionOptions.decode(request.options).map { InteractionOptionView(it.id, it.label, it.style) },
            items = InteractionItems.decode(request.items).map { InteractionItemView(it.name, it.kind, it.description) },
            chosen = request.chosen,
            chosenBy = request.chosenBy,
            chosenAt = request.chosenAt?.toString(),
            outcome = request.outcome,
            createdAt = request.createdAt.toString(),
        )
    }
}

@Controller
class InteractionAPI(
    private val interactions: Interactions,
    private val requests: InteractionRequestRepository,
    private val llmSessions: LlmSessionRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun sessionRequests(@Argument sessionId: Long): List<InteractionRequestView> {
        val session = llmSessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        return interactions.of(sessionId).map(InteractionRequestView::of)
    }

    @MutationMapping
    fun answerRequest(@Argument id: Long, @Argument option: String): InteractionRequestView {
        val request = requests.findByIdOrNull(id) ?: throw InteractionRequestNotFoundException(id)
        access.requireVisible(request.workspaceId)
        val by = SecurityContextHolder.getContext().authentication?.name ?: "system"
        val answered = interactions.answer(request, option, by)
        val label = InteractionOptions.decode(request.options).firstOrNull { it.id == option }?.label ?: option
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
            is InteractionOptionUnknownException, is InteractionAlreadyAnsweredException -> ErrorType.BAD_REQUEST
            is InteractionRequestNotFoundException -> ErrorType.NOT_FOUND
            else -> return null
        }
        return refused(exception, type, environment)
    }
}
