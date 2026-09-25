package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.OffsetDateTime
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** What kind of event a trigger definition waits for. */
enum class TriggerType {
    /** An event arriving on one of the workspace's connections. */
    INCOMING_CONNECTION,

    /** The clock, on a cron expression. */
    SCHEDULED,

    /** A request arriving at a URL this installation answers on. */
    WEBHOOK,
}

/**
 * How a webhook decides whether the caller may start anything.
 *
 * [NONE] is what every webhook was to begin with: knowing the path and the shape
 * is enough. [FUNCTION] hands the request to one of the workspace's functions
 * and believes what it answers — which is how a signature gets checked against a
 * secret nobody has to paste into a graph.
 */
enum class WebhookAuthType {
    NONE,
    FUNCTION,
}

/** The event on a connection that starts the workflow. */
enum class TriggerAction {
    MENTION,
    REPLY,
    MESSAGE,
    ISSUE_CREATED,
    ISSUE_UPDATED,
}

/**
 * One entry in a workspace's trigger catalogue: an event, described once.
 *
 * It names no workflow. A workflow points a trigger node at a definition, and
 * that node is the instance — so one definition can start several workflows, and
 * a definition nobody points at starts none.
 */
@Entity
@Table(name = "workflow_trigger")
class WorkflowTrigger(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /**
     * The workflow this belongs to, or null for one the whole workspace shares.
     *
     * Set means it is that workflow's own - the "Custom" a node offers: a
     * trigger made where it is used rather than a name added to a library
     * everything in the workspace reads. It does not appear on the workspace's
     * page, no other workflow can point at it, and it goes when the workflow
     * does. The same shape an action and a condition take; see V258.
     */
    @Column(name = "workflow_id")
    var workflowId: Long? = null,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var type: TriggerType,

    /** The workspace connection an incoming event arrives on. */
    @Column(name = "connection_id")
    var connectionId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    var action: TriggerAction? = null,

    /**
     * The outbound connections whose own messages a [TriggerAction.REPLY] watches
     * for replies to.
     *
     * **Not the connection it listens on.** [connectionId] is the socket — the
     * app-level token this installation receives Slack events over. These are
     * the bot tokens whose *messages* are the ones a reply has to hang under,
     * and they are a different row: one workspace's Slack app hears everything,
     * and the bots people want answered may be several other apps entirely.
     *
     * A set and not one id because one workflow watching two bots is a good deal
     * more plausible than a reason to forbid it, and because a set of two
     * connections holding the same bot token is the same Slack user twice —
     * which is why `SlackBotUsers` says so rather than the picker pretending
     * otherwise.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_trigger_watch", joinColumns = [JoinColumn(name = "trigger_id")])
    @Column(name = "connection_id", nullable = false)
    var watchedConnectionIds: MutableSet<Long> = mutableSetOf(),

    @Column(length = 120)
    var cron: String? = null,

    @Column(length = 64)
    var timezone: String? = null,

    /**
     * The second half of the URL a webhook trigger answers on.
     *
     * Unique across the installation rather than per workspace, because the URL
     * is: two workspaces cannot both answer at `/api/webhooks/build`.
     */
    @Column(name = "webhook_path", length = 120)
    var webhookPath: String? = null,

    /**
     * The shape a webhook's request has to have.
     *
     * Both a contract and a filter: what does not match is answered 404 rather
     * than started, and what does match is what the workflow can rely on being
     * handed.
     */
    @Column(name = "object_id")
    var objectId: Long? = null,

    /** How a webhook proves its caller may start anything; see [WebhookAuthType]. */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 16)
    var authType: WebhookAuthType = WebhookAuthType.NONE,

    /** The function that answers that question, when one does. */
    @Column(name = "auth_function_id")
    var authFunctionId: Long? = null,

    /**
     * A question asked of the event before anything is started; null fires on
     * everything. Filtering here rather than inside the workflow is what keeps
     * an unwanted event out of the executions list altogether.
     */
    @Column(name = "condition_id")
    var conditionId: Long? = null,

    /**
     * JSON handed to the run this starts, as an object.
     *
     * A scheduled trigger is the reason this exists: the clock carries no data,
     * so what a workflow works on has to be said here. An incoming trigger adds
     * it underneath what arrived, so the event wins where both name a field.
     */
    @Column(columnDefinition = "text")
    var payload: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    /**
     * When a scheduled trigger last fired, which is what the tick compares its
     * cron against. Null until it has, and a trigger that has never fired starts
     * from the tick that finds it rather than replaying the schedule it missed.
     */
    @Column(name = "last_fired_at")
    var lastFiredAt: OffsetDateTime? = null,

    /**
     * Which icon a node drawn from this starts with.
     *
     * A seed, not a rule: the node owns its icon once it has one, the same way
     * it owns the parameters this seeded. Null draws whatever the kind draws.
     */
    @Column(length = 40)
    var icon: String? = null,
)

interface WorkflowTriggerRepository : JpaRepository<WorkflowTrigger, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkflowTrigger>

    /** The shared ones: what the workspace's own list is. */
    fun findByWorkspaceIdAndWorkflowIdIsNull(workspaceId: Long, pageable: Pageable): Page<WorkflowTrigger>

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
        SELECT e FROM WorkflowTrigger e
        WHERE e.workspaceId = :workspaceId AND e.workflowId IS NULL
          AND LOWER(e.name) LIKE LOWER(CONCAT('%', :looking, '%'))
        """,
    )
    fun searching(
        @Param("workspaceId") workspaceId: Long,
        @Param("looking") looking: String,
        pageable: Pageable,
    ): Page<WorkflowTrigger>

    /** What one workflow owns, for its editor and for what a copy takes along. */
    fun findByWorkflowId(workflowId: Long): List<WorkflowTrigger>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkflowTrigger?

    /**
     * Every one of that name here: a definition a workflow owns may share a
     * name with another's, so the single-row lookup above throws where two
     * workflows each made an "Action". Issue #383.
     */
    fun findAllByWorkspaceIdAndName(workspaceId: Long, name: String): List<WorkflowTrigger>

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
    fun findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId: Long, name: String): WorkflowTrigger?

    /** Which triggers ask this question, so it cannot be deleted from under them. */
    fun findByConditionId(conditionId: Long): List<WorkflowTrigger>

    /** Which webhooks this function guards, so unloading a plugin can say so. */
    fun findByAuthFunctionId(authFunctionId: Long): List<WorkflowTrigger>

    /**
     * Which webhooks answer to this shape, so it cannot be deleted from under them.
     *
     * Only a webhook ever holds one: every other kind of trigger leaves the
     * column null, so anything this finds can be named as a webhook.
     */
    fun findByObjectId(objectId: Long): List<WorkflowTrigger>

    /** What an arriving event asks: who is waiting for this, on this connection? */
    fun findByConnectionIdAndActionAndEnabledTrue(
        connectionId: Long,
        action: TriggerAction,
    ): List<WorkflowTrigger>

    /**
     * The same question asked of several connections at once.
     *
     * Because one Slack app can be several connection rows, and Slack decides
     * for itself which of an app's open sockets an event is delivered to - see
     * `IncomingTriggerListener` for what that did to a trigger bound to one row.
     */
    fun findByConnectionIdInAndActionAndEnabledTrue(
        connectionIds: Collection<Long>,
        action: TriggerAction,
    ): List<WorkflowTrigger>

    /** What the scheduler's tick asks: which definitions run on a clock? */
    fun findByTypeAndEnabledTrue(type: TriggerType): List<WorkflowTrigger>

    /** What a request arriving at a URL is answered by, if anything. */
    fun findByWebhookPath(webhookPath: String): WorkflowTrigger?
}

class TriggerNotFoundException(val id: Long) : RuntimeException("No trigger with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

/** An event nothing publishes: a trigger on it would be enabled and silent for ever. */
class TriggerActionUnsupportedException(val action: String) : RuntimeException(
    "Nothing delivers $action events yet, so a trigger cannot listen for one",
), Refusal {

    override val arguments get() = mapOf("action" to action)
}

class TriggerNameTakenException(val name: String) :
    RuntimeException("A trigger named \"$name\" already exists in this workspace"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class TriggerNameInvalidException : RuntimeException("A trigger name is required")

/**
 * A trigger a workflow starts from is not one to delete.
 *
 * The drawn graph is the whole of it: publishing does not copy a trigger id, and
 * an arriving event finds its workflows by looking for the trigger *node*. So a
 * deleted trigger does not break a run halfway - it stops the workflow being
 * started at all, with nothing anywhere saying why, which is why it is refused
 * here rather than reported later.
 */
class TriggerInUseException(val name: String, val users: List<String>) : RuntimeException(
    "$name is used by ${users.joinToString(", ")}, so it cannot be deleted",
), Refusal {

    override val arguments get() = mapOf("name" to name, "users" to users)
}

class TriggerConnectionRequiredException :
    RuntimeException("An incoming connection trigger needs a connection and an event")

/**
 * A reply trigger with nobody to watch.
 *
 * Every thread reply in every channel the bot reads would match it, which is not
 * what anybody means by "replies to our bot" and is a great many workflow runs.
 */
class TriggerReplyWatchRequiredException : RuntimeException(
    "A reply trigger needs at least one connection whose messages it watches for replies",
)

/**
 * A connection chosen to be watched that cannot say which Slack user it posts as.
 *
 * Refused when it is chosen rather than when a reply arrives. A reply is matched
 * by comparing `parent_user_id` against the bot user behind each watched token,
 * so a token that will not authenticate produces a trigger that is enabled,
 * instanced and deaf — and the only way to find that out is to wait for a reply
 * that never fires.
 */
class TriggerReplyWatchUnusableException(val name: String, val why: String) :
    RuntimeException("$name cannot say which Slack user it posts as: $why"), Refusal {

    override val arguments get() = mapOf("name" to name, "why" to why)
}

class TriggerScheduleRequiredException :
    RuntimeException("A scheduled trigger needs a cron expression")

class TriggerWebhookPathRequiredException :
    RuntimeException("A webhook trigger needs a path to answer on")

class TriggerWebhookPathInvalidException(val path: String) : RuntimeException(
    "\"$path\" is not a path on this installation. A webhook answers here, so name somewhere " +
        "here — \"build/finished\", not a URL of your own.",
), Refusal {

    override val arguments get() = mapOf("path" to path)
}

class TriggerWebhookPathTakenException(val path: String) :
    RuntimeException("Another trigger already answers at /api/webhooks/$path"), Refusal {

    override val arguments get() = mapOf("path" to path)
}

class TriggerWebhookAuthFunctionRequiredException :
    RuntimeException("Function authentication needs a function to ask")

class TriggerWebhookAuthFunctionNotBooleanException(val name: String) : RuntimeException(
    "$name does not answer true or false. A webhook is authenticated by a function that says yes or no.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class TriggerWebhookShapeRequiredException :
    RuntimeException("A webhook trigger needs an object saying what a request has to contain")

class TriggerScheduleInvalidException(val cron: String) :
    RuntimeException("\"$cron\" is not a cron expression this can schedule"), Refusal {

    override val arguments get() = mapOf("cron" to cron)
}

/**
 * A cron that parses and never comes round.
 *
 * Separate from [TriggerScheduleInvalidException] because it is a different
 * thing to be told. The expression is well formed; the date it names does not
 * happen - "0 0 30 2 *" is the thirtieth of February, and Spring answers it
 * with no next occurrence rather than refusing it. Calling that invalid sends
 * somebody hunting for a typo in something that has none.
 */
class TriggerScheduleUnreachableException(val cron: String) :
    RuntimeException("\"$cron\" is a cron expression that never comes round"), Refusal {

    override val arguments get() = mapOf("cron" to cron)
}

class TriggerPayloadInvalidException :
    RuntimeException("The payload has to be a JSON object, so its fields can be read as input")
