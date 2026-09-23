package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** What an action does when a workflow reaches it. */
enum class ActionType {
    /** Performs something and carries on. */
    EXECUTE,

    /** Holds the run until something is true, or until time has passed. */
    WAIT,
}

/** How it does it. Each belongs to one [ActionType] and has its own settings. */
enum class ActionSubtype {
    /** Sends something through one of the workspace's connections. */
    OUTGOING_CONNECTION,
    HTTP_REQUEST,

    /**
     * Sends a mail through one of the workspace's SMTP connections.
     *
     * Its own subtype rather than another [ConnectionAction] under
     * OUTGOING_CONNECTION, because a subtype is what decides the settings a form
     * asks for and the parameters a node fills in - and a mail's are none of a
     * chat message's. "Send Message" and "Reply in Thread" are a channel and
     * some text; a mail is recipients, a subject, a copy list and an address to
     * answer to. Folding them together would have meant a form and a node panel
     * that both branch on the connection's kind anyway, with the parameter names
     * of the other one on screen while somebody filled it in.
     */
    SEND_EMAIL,

    /** Calls one of the workspace's functions. */
    FUNCTION,

    /** Waits until an expression written into the action holds. */
    INLINE_CONDITION,

    /** Waits until one of the workspace's conditions holds. */
    CONDITION,

    /** Waits for a fixed time. */
    TIME,

    /**
     * Says something out loud, and leaves the audio with the run.
     *
     * Issue #264. The product spoke in a chat and nowhere else, so a run that
     * wanted to hand somebody audio - a summary to listen to on the way in, a
     * message for a channel where nobody reads - had no way to make any. Every
     * other kind of output a run produces is a file it leaves behind, and this
     * was the missing one.
     *
     * It produces the file and stops there. Sending it is what the connection
     * actions already do, and a subtype that spoke *and* posted would be two
     * decisions in one node - with no way to keep the audio without also
     * sending it.
     */
    SPEAK,
}

/** What is being sent through a connection. */
enum class ConnectionAction {
    SEND_MESSAGE,
    REPLY_IN_THREAD,
    CREATE_ISSUE,
    UPDATE_ISSUE,
}

/** One function argument, and what the action hands it. */
@Embeddable
class ArgumentMapping(
    @Column(name = "argument", nullable = false, length = 64)
    var argument: String = "",

    /** What to pass: a value, or the name of a field a node reads. */
    @Column(name = "expression", nullable = false, length = 500)
    var expression: String = "",
)

/**
 * A reusable block a workflow is built from.
 *
 * An action is a definition in the workspace's catalogue, like a trigger: it names no
 * workflow, and a workflow uses one by pointing an action node at it. What the
 * action needs and what it produces are not stored — they are read off its
 * settings, so the arguments of the function it calls are its parameters. See
 * `ActionParameters`.
 */
@Entity
@Table(name = "workflow_action")
class WorkflowAction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /**
     * The workflow this belongs to, or null for one the whole workspace shares.
     *
     * Set means it is that workflow's own: it does not appear in the
     * workspace's list, nothing else can point at it, and it goes when the
     * workflow does. That is the "Custom" a node offers - a definition made
     * where it is used, by somebody who wanted this one node to do a thing
     * rather than to add a name to a shared library.
     *
     * A row either way, deliberately. What a node points at is an id, and the
     * runner, the validator, the export and the revisions all read one - so
     * owning the row differently costs nothing downstream, while storing the
     * definition on the node would mean teaching every one of them a second
     * shape for where a definition comes from.
     */
    @Column(name = "workflow_id")
    var workflowId: Long? = null,

    @Column(nullable = false, length = 120)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var type: ActionType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var subtype: ActionSubtype,

    /** The workspace connection to send through; the connection module owns the row. */
    @Column(name = "connection_id")
    var connectionId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_action", length = 32)
    var connectionAction: ConnectionAction? = null,

    @Column(columnDefinition = "text")
    var content: String? = null,

    /**
     * Where a send goes, exactly as it was typed: "#general", "general",
     * "@alice", "alice", an address, or an id pasted out of Slack.
     *
     * One column and no kind beside it. There used to be a `target` of CHANNEL
     * or USER here; it never reached Slack, because `OutgoingMessages` takes a
     * destination as a string, and all it could do was narrow a lookup - a
     * setting that changed nothing when it ran and could still be filled in
     * wrongly. What makes one column enough is that sending resolves this to
     * Slack's own id first, so a handle reaches a person and a name reaches a
     * channel without anything stored saying which is which.
     */
    @Column(name = "target_name", length = 120)
    var targetName: String? = null,

    /**
     * Who a mail goes to, and who is copied: addresses separated by commas, as
     * they are typed into a mail client. One column rather than a child table
     * because the list is written and read whole and nothing ever asks a
     * question about a single recipient.
     */
    @Column(name = "email_to", length = 1000)
    var emailTo: String? = null,

    @Column(name = "email_cc", length = 1000)
    var emailCc: String? = null,

    @Column(name = "email_subject", length = 500)
    var emailSubject: String? = null,

    /** Where answers should go, when that is not the connection's from-address. */
    @Column(name = "email_reply_to", length = 320)
    var emailReplyTo: String? = null,

    /**
     * What a [ActionSubtype.SPEAK] action says.
     *
     * The action's rather than the node's, because the action is the reusable
     * half: "read the summary out" is the action, and which summary is the
     * node's mapping. Expressions are resolved against the step's input the way
     * every other action's text is.
     */
    @Column(name = "speech_text", columnDefinition = "text")
    var speechText: String? = null,

    /** Which voice, where the provider offers more than one; null is its own. */
    @Column(name = "speech_voice", length = 80)
    var speechVoice: String? = null,

    /**
     * Which model speaks it; null follows the workspace's own choice.
     *
     * The same model a chat reads an answer aloud with, so an installation that
     * has set one up has nothing further to do. Named here for the run that
     * wants a different voice or a cheaper model than the one people hear.
     */
    @Column(name = "speech_model_id")
    var speechModelId: Long? = null,

    @Column(length = 1000)
    var url: String? = null,

    @Column(length = 8)
    var method: String? = null,

    /** JSON, exactly as typed. */
    @Column(columnDefinition = "text")
    var headers: String? = null,

    @Column(name = "function_id")
    var functionId: Long? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_action_mapping", joinColumns = [JoinColumn(name = "action_id")])
    @OrderColumn(name = "position")
    var mappings: MutableList<ArgumentMapping> = mutableListOf(),

    @Column(name = "condition_expression", length = 500)
    var conditionExpression: String? = null,

    /** The condition from the catalogue this waits on. */
    @Column(name = "condition_id")
    var conditionId: Long? = null,

    @Column(name = "timeout_seconds")
    var timeoutSeconds: Int? = null,

    @Column(name = "retry_interval_seconds")
    var retryIntervalSeconds: Int? = null,

    @Column(name = "duration_seconds")
    var durationSeconds: Int? = null,

    /**
     * Which icon a node drawn from this starts with.
     *
     * A seed, not a rule: the node owns its icon once it has one, the same way
     * it owns the parameters this seeded. Null draws whatever the kind draws.
     */
    @Column(length = 40)
    var icon: String? = null,
)

interface WorkflowActionRepository : JpaRepository<WorkflowAction, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkflowAction>

    /**
     * The shared ones: what the workspace's own list is.
     *
     * A definition a workflow owns is that workflow's own and is reachable
     * only from the node that made it - so it is left out of every list meant
     * for choosing from, which is what keeps "Custom" from filling the
     * library with rows nobody named for anybody else.
     */
    fun findByWorkspaceIdAndWorkflowIdIsNull(workspaceId: Long, pageable: Pageable): Page<WorkflowAction>

    /**
     * The same, narrowed to what a word appears in.
     *
     * The name, which is what a row shows that somebody could be remembering.
     * Case-insensitive and a substring rather than a prefix: what people recall
     * is a phrase from the middle of a name, not how it opened.
     *
     * Asked of the database rather than sieved in the browser because the list
     * is paged - narrowing what arrived on page one would hide matches sitting
     * on page four and quietly call that "no results".
     */
    @Query(
        """
        SELECT e FROM WorkflowAction e
        WHERE e.workspaceId = :workspaceId AND e.workflowId IS NULL
          AND LOWER(e.name) LIKE LOWER(CONCAT('%', :looking, '%'))
        """,
    )
    fun searching(
        @Param("workspaceId") workspaceId: Long,
        @Param("looking") looking: String,
        pageable: Pageable,
    ): Page<WorkflowAction>

    fun findByWorkspaceIdAndWorkflowIdIsNull(workspaceId: Long): List<WorkflowAction>

    /** What one workflow owns, for the editor and for what a copy has to take along. */
    fun findByWorkflowId(workflowId: Long): List<WorkflowAction>

    fun findByWorkspaceId(workspaceId: Long): List<WorkflowAction>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkflowAction?

    /**
     * The one with this name that a new *shared* definition would collide
     * with.
     *
     * Only the shared list has names to keep apart. A workflow's own
     * A definition a workflow owns is reached through the node that uses it
     * and appears in no list at all, so its name is a label rather than a way
     * of finding it - two workflows may each have a "Format agent output", and
     * so may two nodes of one workflow. The shared list still holds one name
     * once, because that is a list people read.
     *
     * Asking wider than this has been wrong twice. Asking the workspace made
     * a Custom definition named after its node collide with any of that name
     * anywhere; asking the workflow made the second Custom action in one
     * workflow unsaveable, because every action node arrives called "Action".
     */
    fun findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId: Long, name: String): WorkflowAction?

    fun findByFunctionId(functionId: Long): List<WorkflowAction>
}

class ActionNotFoundException(val id: Long) : RuntimeException("No action with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class ActionNameTakenException(val name: String) :
    RuntimeException("An action named \"$name\" already exists in this workspace"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class ActionNameInvalidException : RuntimeException("An action name is required")

/**
 * Named workflow by workflow, and marked where the copy is a published one.
 *
 * "The published workflow Answer" and "the workflow Answer" ask for two
 * different things from whoever reads it - republish, or redraw - so the
 * refusal has to be able to say which, rather than counting nodes.
 */
class ActionInUseException(val name: String, val users: List<String>) : RuntimeException(
    "$name is used by ${users.joinToString(", ")}, so it cannot be deleted",
), Refusal {

    override val arguments get() = mapOf("name" to name, "users" to users)
}

class ActionSettingMissingException(val setting: String) :
    RuntimeException("This kind of action needs $setting"), Refusal {

    override val arguments get() = mapOf("setting" to setting)
}

class ActionHoldsPlaceholderException(val setting: String) : RuntimeException(
    "$setting is used exactly as written, so {{…}} would be sent as text. " +
        "Leave it empty and let each node say what goes there.",
), Refusal {

    override val arguments get() = mapOf("setting" to setting)
}

class ActionSubtypeMismatchException(type: ActionType, subtype: ActionSubtype) :
    RuntimeException("A ${type.name.lowercase()} action cannot be ${subtype.name.lowercase().replace('_', ' ')}")
