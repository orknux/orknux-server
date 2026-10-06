package io.mszymanski.orknux.server.condition

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import io.mszymanski.orknux.server.workflow.MappingMode
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
import org.hibernate.annotations.Fetch
import org.hibernate.annotations.FetchMode
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** What a condition asks about. */
enum class ConditionType {
    /** A Slack event: who said it, where, and what it said. */
    SLACK,

    /** A Jira issue: its priority, status or type. */
    JIRA,

    /** The clock, rather than anything the run is carrying. */
    TIME,

    /**
     * One of the workspace's functions, which has to return a boolean. This is the
     * way out when a question needs more than a property and a check.
     */
    FUNCTION,

    /** True when any of the conditions it names is. */
    ANY_OF,

    /** True when all of them are. */
    ALL_OF,

    /**
     * A value from the run, checked against a list. Issue #378.
     *
     * The general form of the typed conditions above. A Slack condition knows
     * where a message's author is; this one is told: the node it sits on
     * picks the field, with the same reference picker every node's parameters
     * use, and the condition holds the check and the values. Which is why the
     * subject is an argument named [VALUE_SUBJECT] rather than a property - it
     * is filled in where the graph is, and a condition may be reused on two
     * nodes looking at two different fields.
     */
    VALUE,
}

/** The one argument a [ConditionType.VALUE] condition takes: what to check. */
const val VALUE_SUBJECT = "value"

/**
 * Which part of what arrived is being asked about.
 *
 * Each one reads a field of the run's input, which is what a trigger put there;
 * `ConditionEvaluator.valueOf` is where that mapping lives.
 */
enum class ConditionProperty {
    MESSAGE_AUTHOR,
    MESSAGE_CHANNEL,
    MESSAGE_TEXT,
    ISSUE_PRIORITY,
    ISSUE_STATUS,
    ISSUE_TYPE,

    /** The time the condition is asked, not something in the input. */
    CURRENT_TIME,
}

/** How the property is tested. */
enum class ConditionCheck {
    /** The value is one of the listed ones. */
    IN_LIST,
    EQUALS,
    CONTAINS,

    /** The value matches a regular expression. */
    MATCHES,

    /** The time is between the two listed times, as HH:mm. */
    BETWEEN,

    /** The value names someone in the workspace's directory group. */
    WORKSPACEMATE,
}

/**
 * One argument a function condition passes, as written on the condition.
 *
 * Deliberately the same three fields `NodeMapping` has rather than something of
 * its own: a value written in, or the name of a field the run is carrying, and
 * which of the two it is. Two spellings of one idea would be two things to keep
 * in step and two places for a reference to stop resolving.
 */
@Embeddable
class ConditionArgument(
    @Column(name = "name", nullable = false, length = 64)
    var name: String = "",

    @Column(name = "expression", nullable = false, columnDefinition = "text")
    var expression: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    var mode: MappingMode = MappingMode.VALUE,
)

/**
 * A question a workspace asks about what a run is carrying, defined once.
 *
 * Conditions are used from two places — a wait that holds until one holds, and a
 * condition node that stops a run when one does not — so they are a catalogue
 * like actions and triggers, not settings inside whatever uses them.
 *
 * [negate] is what makes "Is External User" the same definition as "Is Workspacemate
 * Message" with the answer turned round, which is how the design has it.
 */
@Entity
@Table(name = "workflow_condition")
class WorkflowCondition(
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
    var type: ConditionType,

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    var property: ConditionProperty? = null,

    /** `check` is reserved in SQL, hence the column name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "check_by", length = 16)
    var check: ConditionCheck? = null,

    @Column(nullable = false)
    var negate: Boolean = false,

    /** The function that answers a [ConditionType.FUNCTION] condition. */
    @Column(name = "function_id")
    var functionId: Long? = null,

    /** What the check compares against; empty for one that needs nothing. */
    // Each list in a select of its own, batched across rows, rather than joined:
    // joining several eager lists into one select returns every combination of
    // their rows, and an agent with a handful in each took a minute. Issue #616.
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "workflow_condition_value", joinColumns = [JoinColumn(name = "condition_id")])
    @OrderColumn(name = "position")
    @Column(name = "value", length = 500)
    var values: MutableList<String> = mutableListOf(),

    /**
     * What a [ConditionType.FUNCTION] condition passes to its function, one per
     * parameter, in the function's own order.
     *
     * Empty is what every condition written before this holds, and it means what
     * it always meant: the function is handed what the run is carrying, as one
     * argument, followed by the workspace values it declared. So nothing that
     * worked stops working, and a condition only starts passing arguments when
     * somebody fills them in.
     *
     * The same shape a node's mappings have - a written value or a reference to
     * a field the run carries - because it is the same decision: a condition
     * asking "is this the first reply" needs the thread and the connection it
     * arrived on, and those are fields of the run rather than constants.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "workflow_condition_argument", joinColumns = [JoinColumn(name = "condition_id")])
    @OrderColumn(name = "position")
    var arguments: MutableList<ConditionArgument> = mutableListOf(),

    /** The conditions a composite is made of, in the order they were added. */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "workflow_condition_member", joinColumns = [JoinColumn(name = "condition_id")])
    @OrderColumn(name = "position")
    @Column(name = "member_id")
    var members: MutableList<Long> = mutableListOf(),

    /**
     * Which icon a node drawn from this starts with.
     *
     * A seed, not a rule: the node owns its icon once it has one, the same way
     * it owns the parameters this seeded. Null draws whatever the kind draws.
     */
    @Column(length = 40)
    var icon: String? = null,
) {

    val composite: Boolean get() = type == ConditionType.ANY_OF || type == ConditionType.ALL_OF
}

interface WorkflowConditionRepository : JpaRepository<WorkflowCondition, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkflowCondition>

    /**
     * The shared ones: what the workspace's own list is.
     *
     * A definition a workflow owns is that workflow's own and is reachable
     * only from the node that made it - so it is left out of every list meant
     * for choosing from, which is what keeps "Custom" from filling the
     * library with rows nobody named for anybody else.
     */
    fun findByWorkspaceIdAndWorkflowIdIsNull(workspaceId: Long, pageable: Pageable): Page<WorkflowCondition>

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
        SELECT e FROM WorkflowCondition e
        WHERE e.workspaceId = :workspaceId AND e.workflowId IS NULL
          AND LOWER(e.name) LIKE LOWER(CONCAT('%', :looking, '%'))
        """,
    )
    fun searching(
        @Param("workspaceId") workspaceId: Long,
        @Param("looking") looking: String,
        pageable: Pageable,
    ): Page<WorkflowCondition>

    fun findByWorkspaceIdAndWorkflowIdIsNull(workspaceId: Long): List<WorkflowCondition>

    /** What one workflow owns, for the editor and for what a copy has to take along. */
    fun findByWorkflowId(workflowId: Long): List<WorkflowCondition>

    fun findByWorkspaceId(workspaceId: Long): List<WorkflowCondition>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkflowCondition?

    /*
     * The conditions that call a function, and the groups that hold a
     * condition: asked of the database rather than by reading every condition
     * of a workspace - or of the installation - with its three lists, to keep
     * the few that match. Issue #616.
     */

    fun findByFunctionId(functionId: Long): List<WorkflowCondition>

    fun findByWorkspaceIdAndFunctionId(workspaceId: Long, functionId: Long): List<WorkflowCondition>

    @Query(
        """
        SELECT DISTINCT c FROM WorkflowCondition c JOIN c.members m
        WHERE c.workspaceId = :workspaceId AND m = :memberId
        """,
    )
    fun groupsHolding(@Param("workspaceId") workspaceId: Long, @Param("memberId") memberId: Long): List<WorkflowCondition>

    /**
     * Every one of that name here: a definition a workflow owns may share a
     * name with another's, so the single-row lookup above throws where two
     * workflows each made an "Action". Issue #383.
     */
    fun findAllByWorkspaceIdAndName(workspaceId: Long, name: String): List<WorkflowCondition>

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
    fun findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId: Long, name: String): WorkflowCondition?
}

class ConditionNotFoundException(val id: Long) : RuntimeException("No condition with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class ConditionNameTakenException(val name: String) :
    RuntimeException("A condition named \"$name\" already exists in this workspace"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class ConditionNameInvalidException : RuntimeException("A condition name is required")

class ConditionPropertyMismatchException(type: ConditionType, property: ConditionProperty) :
    RuntimeException("A ${type.name.lowercase()} condition cannot ask about ${property.name.lowercase()}")

/** A check a value cannot take: the time-of-day one, and the directory one. Issue #378. */
class ConditionValueCheckException(val check: ConditionCheck) :
    RuntimeException(
        "a value cannot be tested with ${check.name.lowercase().replace('_', ' ')}; " +
            "it can be in a list, equal to, containing or matching something",
    ),
    Refusal {

    override val arguments get() = mapOf("check" to check.name)
}

class ConditionCheckMismatchException(property: ConditionProperty, check: ConditionCheck) :
    RuntimeException(
        "${property.name.lowercase().replace('_', ' ')} cannot be tested with " +
            check.name.lowercase().replace('_', ' '),
    )

class ConditionValuesRequiredException(check: ConditionCheck) :
    RuntimeException("A ${check.name.lowercase().replace('_', ' ')} check needs something to compare against")

class ConditionMembersRequiredException :
    RuntimeException("A composite condition needs at least two conditions to combine")

class ConditionCycleException(val name: String) :
    RuntimeException("$name would contain itself"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class ConditionInUseException(val name: String, val used: List<String>) :
    RuntimeException("$name is used by ${used.joinToString(", ")}"), Refusal {

    override val arguments get() = mapOf("name" to name, "used" to used)
}

class ConditionFunctionRequiredException :
    RuntimeException("A function condition needs a function to call")

class ConditionFunctionElsewhereException(val name: String) :
    RuntimeException("$name belongs to another workspace; a condition can call this workspace's functions and a plugin's"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class ConditionFunctionNotBooleanException(val name: String, val returnType: String) :
    RuntimeException("$name returns $returnType; a condition needs a function that returns a boolean"), Refusal {

    override val arguments get() = mapOf("name" to name, "returnType" to returnType)
}

