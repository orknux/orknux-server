package io.mszymanski.orknux.server.interaction

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A question put to a person in a conversation, on behalf of something that
 * needs a decision before it can go on - an agent asking to open a port, and
 * whatever asks next.
 *
 * General on purpose: the question, its options and the answer are text and
 * ids, and [kind] names the [InteractionHandler] that acts on the answer and
 * describes what became of it. A new kind of question is a handler; nothing
 * here changes.
 */
@Entity
@Table(name = "interaction_request")
class InteractionRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long = 0,

    /** The conversation it is asked in, which is where it is drawn. */
    @Column(name = "llm_session_id", nullable = false)
    val llmSessionId: Long = 0,

    /** Which handler answers it, such as `box.port`. */
    @Column(nullable = false, length = 60)
    val kind: String = "",

    /** What it is about, in the handler's terms: a box_port row for `box.port`. */
    @Column(name = "subject_id")
    val subjectId: Long? = null,

    /** Who is asking - the agent's name. */
    @Column(name = "asked_by", length = 255)
    val askedBy: String? = null,

    @Column(nullable = false, columnDefinition = "text")
    val question: String = "",

    /** The answers on offer, as [InteractionOptions] writes them. */
    @Column(nullable = false, columnDefinition = "text")
    val options: String = "[]",

    /**
     * What it is about, item by item, as [InteractionItems] writes them - the
     * tools an agent asks for, each with its kind and what it does - for the
     * interface to draw as a list under the question. Null where the question
     * already names the one thing it is about.
     */
    @Column(columnDefinition = "text")
    val items: String? = null,

    /** The id of the option chosen, or null while nobody has. */
    @Column(length = 60)
    var chosen: String? = null,

    @Column(name = "chosen_by", length = 255)
    var chosenBy: String? = null,

    @Column(name = "chosen_at")
    var chosenAt: OffsetDateTime? = null,

    /**
     * What became of it, in a line: "Allowed - http://host:54321". Kept up to
     * date by the handler, so a decision changed somewhere else - a port
     * disabled from the panel - reads the same here.
     */
    @Column(columnDefinition = "text")
    var outcome: String? = null,

    /** Whether more than one option may be chosen; [chosen] then holds their ids joined by commas. */
    @Column(nullable = false)
    val multiple: Boolean = false,

    /** Whether the person may answer in their own words instead of, or beside, an option. */
    @Column(name = "allows_text", nullable = false)
    val allowsText: Boolean = false,

    /** What the person wrote, where they wrote anything. */
    @Column(name = "answer_text", columnDefinition = "text")
    var answerText: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface InteractionRequestRepository : JpaRepository<InteractionRequest, Long> {
    fun findByLlmSessionIdOrderByCreatedAtAscIdAsc(llmSessionId: Long): List<InteractionRequest>

    fun findByKindAndSubjectId(kind: String, subjectId: Long): List<InteractionRequest>
}

/**
 * One thing a request is about: [name] what it is called, [kind] what sort of
 * thing it is ("plugin tool"), [description] a line on what it does.
 */
data class InteractionItem(val name: String, val kind: String? = null, val description: String? = null)

/** One answer on offer: [id] is what is sent back, [label] what the button says. */
data class InteractionOption(val id: String, val label: String, val style: String? = null, val description: String? = null)

class InteractionRequestNotFoundException(val id: Long) :
    RuntimeException("No request with id $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class InteractionOptionUnknownException(val option: String) :
    RuntimeException("$option is not one of this request's options"), Refusal {
    override val arguments get() = mapOf("option" to option)
}

/** An answer the request does not take: two options where one is asked for, words where none are, or nothing at all. */
class InteractionAnswerInvalidException(val why: String) :
    RuntimeException(why), Refusal {
    override val arguments get() = mapOf("why" to why)
}

class InteractionAlreadyAnsweredException(val id: Long) :
    RuntimeException("Request $id has already been answered"), Refusal {
    override val arguments get() = mapOf("id" to id)
}
