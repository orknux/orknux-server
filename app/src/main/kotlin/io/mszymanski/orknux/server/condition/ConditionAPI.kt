package io.mszymanski.orknux.server.condition

import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.DependencyKind
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workflow.MappingMode
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import io.mszymanski.orknux.server.workspace.sortBy
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * A workspace's conditions: the questions its workflows ask.
 *
 * What a condition means in words is not stored — it is read off the definition,
 * the same as an action's parameters — so the description in the list and the
 * sentence under the form always describe what will actually be asked.
 */
@Controller
class ConditionAPI(
    private val conditions: WorkflowConditionRepository,
    private val functions: WorkflowFunctionRepository,
    private val dependants: ComponentDependants,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    /**
     * The columns this list can be put in the order of. Issue #358.
     *
     * The Description column is a sentence assembled from what the condition
     * asks - there is no stored description - so it is a heading and not an
     * order. The name breaks the tie on type, or a page of conditions sharing
     * one type would shuffle between reads.
     */
    private val CONDITION_ORDERS = mapOf(
        "NAME" to listOf("name"),
        "TYPE" to listOf("type", "name"),
    )

    @QueryMapping
    /** @param search what to look for in this list, or null for all of it. */
    fun workspaceConditions(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): ConditionPage {
        requireWorkspaceAccess(workspaceId)
        // The shared ones only; see the note on workspaceActions.
        val paged = pageRequest(page, size, sortBy(order, ascending, CONDITION_ORDERS, "NAME"))
        val looking = search?.trim().orEmpty()

        return ConditionPage(
            if (looking.isEmpty()) {
                conditions.findByWorkspaceIdAndWorkflowIdIsNull(workspaceId, paged)
            } else {
                conditions.searching(workspaceId, looking, paged)
            },
            ::describe,
        )
    }

    /** What one workflow owns; see the note on workflowOwnedActions. */
    @QueryMapping
    fun workflowOwnedConditions(@Argument workspaceId: Long, @Argument workflowId: Long): List<ConditionView> {
        requireWorkspaceAccess(workspaceId)
        return conditions.findByWorkflowId(workflowId)
            .filter { it.workspaceId == workspaceId }
            .sortedBy { it.name }
            .map(::describe)
    }

    @QueryMapping
    fun condition(@Argument id: Long): ConditionView? {
        val condition = conditions.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(condition)
    }

    @MutationMapping
    @Transactional
    fun createCondition(@Argument input: CreateConditionInput): ConditionView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        if (name.isEmpty()) throw ConditionNameInvalidException()
        /*
         * Only the shared list has names to keep apart.
         *
         * A definition a workflow owns is reached through the node that uses
         * it and appears in no list of its own, so its name is a label rather
         * than a way of finding it - and labels repeat. Checking those made
         * the second Custom definition in a workflow unsaveable: every action
         * node arrives called "Action" and the form names the definition after
         * its node, so the panel - which has no button and writes itself -
         * simply never saved, and the node came back from a reload with
         * nothing on it.
         */
        if (input.workflowId == null &&
            conditions.findByWorkspaceIdAndWorkflowIdIsNullAndName(input.workspaceId, name) != null
        ) {
            throw ConditionNameTakenException(name)
        }

        val condition = conditions.save(
            WorkflowCondition(
                workspaceId = input.workspaceId,
                workflowId = input.workflowId,
                name = name,
                type = input.type,
                property = input.property,
                check = input.check,
                negate = input.negate ?: false,
                functionId = input.functionId,
                values = input.values.orEmpty().clean(),
                arguments = input.arguments.orEmpty().toArguments(),
                members = input.members.orEmpty().toMutableList(),
                icon = input.icon?.trim()?.ifEmpty { null },
            ).also { validate(it, itsOwnId = null) },
        )

        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Condition $name created")
        return describe(condition)
    }

    @MutationMapping
    @Transactional
    fun updateCondition(@Argument id: Long, @Argument input: UpdateConditionInput): ConditionView {
        val condition = conditions.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ConditionNotFoundException(id)

        val previousName = condition.name
        input.name?.trim()?.let { name ->
            if (name.isEmpty()) throw ConditionNameInvalidException()
            // The same scope the create uses; see the note there.
            // Only a shared definition has a name to keep apart; see the create.
            val clash = if (condition.workflowId == null) {
                conditions.findByWorkspaceIdAndWorkflowIdIsNullAndName(condition.workspaceId, name)
            } else {
                null
            }
            if (name != condition.name && clash != null) {
                throw ConditionNameTakenException(name)
            }
            condition.name = name
        }
        input.type?.let { condition.type = it }
        input.property?.let { condition.property = it }
        input.check?.let { condition.check = it }
        input.negate?.let { condition.negate = it }
        input.functionId?.let { condition.functionId = it }
        input.values?.let { condition.values = it.clean() }
        input.arguments?.let { condition.arguments = it.toArguments() }
        input.members?.let { condition.members = it.toMutableList() }
        // Sent whenever the form saves, so null is "no icon" rather than "not
        // mentioned" — which is what lets Clear clear it.
        condition.icon = input.icon?.trim()?.ifEmpty { null }
        // A composite has no property to ask about, and a simple one has no members.
        if (condition.composite) {
            condition.property = null
            condition.check = null
        } else {
            condition.members = mutableListOf()
        }
        validate(condition, itsOwnId = id)

        val message = if (previousName == condition.name) {
            "Condition ${condition.name} updated"
        } else {
            "Condition $previousName renamed to ${condition.name}"
        }
        auditRecorder.record(condition.workspaceId, WorkspaceAuditCategory.WORKFLOW, message)
        return describe(condition)
    }

    /**
     * Refused while anything still asks it.
     *
     * The workflow half of this used to say "a workflow node", counted off the
     * drawn graph. Two things were wrong with that. It named nothing somebody
     * could go and look at, and it saw only the draft - so a condition still
     * asked by a published copy, whose node had since been taken off the canvas,
     * could be deleted out from under the workflow that was running it.
     */
    @MutationMapping
    @Transactional
    fun deleteCondition(@Argument id: Long): Boolean {
        val condition = conditions.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        // Anything pointing at a deleted condition would have nothing to ask.
        val users = dependants.of(DependencyKind.CONDITION, id)
        if (users.isNotEmpty()) throw ConditionInUseException(condition.name, users.phrases())

        conditions.delete(condition)
        auditRecorder.record(condition.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Condition ${condition.name} deleted")
        return true
    }

    /** The list's Type badge and Description, and the sentence under the form. */
    private fun describe(condition: WorkflowCondition): ConditionView {
        val function = condition.functionId?.let { functions.findByIdOrNull(it) }
        val names = condition.members.mapNotNull { conditions.findByIdOrNull(it)?.name }
        return ConditionView(
            id = requireNotNull(condition.id),
            workspaceId = condition.workspaceId,
            workflowId = condition.workflowId,
            name = condition.name,
            type = condition.type,
            typeLabel = typeLabel(condition.type),
            property = condition.property,
            check = condition.check,
            negate = condition.negate,
            functionId = condition.functionId,
            functionName = function?.name,
            values = condition.values.toList(),
            arguments = condition.arguments.map {
                ConditionArgumentView(name = it.name, expression = it.expression, mode = it.mode)
            },
            members = condition.members.toList(),
            icon = condition.icon,
            memberNames = names,
            description = sentence(condition, function?.name, names),
        )
    }

    /**
     * What the condition says, in words. The list shows it as the description
     * and the form shows it under the fields, so both read what is really asked.
     */
    private fun sentence(condition: WorkflowCondition, functionName: String?, memberNames: List<String>): String {
        val negated = condition.negate
        return when (condition.type) {
            ConditionType.ANY_OF ->
                "Matches when any of ${memberNames.joinToString(", ").ifEmpty { "the selected conditions" }} " +
                    "is true".prefixIfNegated(negated)

            ConditionType.ALL_OF ->
                "Matches when all of ${memberNames.joinToString(", ").ifEmpty { "the selected conditions" }} " +
                    "are true".prefixIfNegated(negated)

            ConditionType.FUNCTION ->
                "Matches when ${functionName ?: "the function"} answers true".prefixIfNegated(negated)

            else -> {
                val property = if (condition.type == ConditionType.VALUE) {
                    condition.arguments.firstOrNull { it.name == VALUE_SUBJECT }?.expression
                        ?.takeIf { it.isNotBlank() }?.let { "the value $it" } ?: "the value the node picks"
                } else {
                    condition.property?.let(::label) ?: "what arrives"
                }
                val phrase = when (condition.check) {
                    ConditionCheck.IN_LIST -> "is one of ${condition.values.size} listed values"
                    ConditionCheck.EQUALS -> "is ${condition.values.firstOrNull() ?: "the listed value"}"
                    ConditionCheck.CONTAINS -> "contains ${condition.values.firstOrNull() ?: "the listed text"}"
                    ConditionCheck.MATCHES -> "matches ${condition.values.firstOrNull() ?: "the pattern"}"
                    ConditionCheck.BETWEEN ->
                        "is between ${condition.values.getOrNull(0) ?: "?"} and ${condition.values.getOrNull(1) ?: "?"}"

                    ConditionCheck.WORKSPACEMATE -> "is a workspacemate"
                    null -> "is checked"
                }
                if (negated) {
                    "Negated: $property must NOT be what is checked — $phrase"
                } else {
                    "Matches when $property $phrase".replaceFirstChar(Char::uppercase)
                }
            }
        }
    }

    private fun String.prefixIfNegated(negated: Boolean) = if (negated) "$this — negated" else this

    /**
     * A condition has to be answerable: the property has to belong to the type,
     * the check to the property, and a composite has to name conditions that
     * exist and do not contain it.
     */
    private fun validate(condition: WorkflowCondition, itsOwnId: Long?) {
        /*
         * A draft belonging to one node, which is allowed to be unfinished.
         *
         * It is filled in on the node that uses it, a field at a time, and the
         * panel writes as it is typed - so the moment between choosing a kind
         * and choosing what it needs is where somebody is, not a mistake.
         * A run reaching an unfinished one is prevented at publish instead;
         * see `GraphValidator`.
         */
        if (condition.workflowId != null) return

        when (condition.type) {
            ConditionType.ANY_OF, ConditionType.ALL_OF -> {
                if (condition.members.size < 2) throw ConditionMembersRequiredException()
                condition.members.forEach { member ->
                    val held = conditions.findByIdOrNull(member)
                    if (held == null || held.workspaceId != condition.workspaceId) throw ConditionNotFoundException(member)
                    if (itsOwnId != null && contains(held, itsOwnId, depth = 0)) {
                        throw ConditionCycleException(condition.name)
                    }
                    if (member == itsOwnId) throw ConditionCycleException(condition.name)
                }
            }

            /*
             * The subject is the node's to pick, so nothing here asks for it:
             * a condition made on the settings page has no graph to pick from,
             * and the evaluator says so at run time if nothing ever fills it.
             * What it must have is a check a value can take, and something to
             * check against.
             */
            ConditionType.VALUE -> {
                val check = condition.check ?: throw ConditionValueCheckException(ConditionCheck.BETWEEN)
                if (check !in VALUE_CHECKS) throw ConditionValueCheckException(check)
                if (condition.values.isEmpty()) throw ConditionValuesRequiredException(check)
                condition.property = null
            }
            ConditionType.FUNCTION -> {
                val functionId = condition.functionId ?: throw ConditionFunctionRequiredException()
                // Nothing at that id is the same as nothing chosen: a function
                // deleted out from under a condition leaves it with nothing to
                // call.
                val function = functions.findByIdOrNull(functionId) ?: throw ConditionFunctionRequiredException()
                /*
                 * A plugin's functions belong to no workspace and are offered in
                 * every one — the picker lists them, the evaluator runs them
                 * without asking whose they are, and unloading a plugin counts
                 * the conditions naming them. Only another workspace's own
                 * function is out of reach, and saying so is the point: refusing
                 * it as "needs a function to call" describes a box somebody has
                 * already filled in.
                 */
                // As in ActionAPI: only a workspace's own is tied to one. #501.
                if (function.scope == FunctionScope.WORKSPACE && function.workspaceId != condition.workspaceId) {
                    throw ConditionFunctionElsewhereException(function.name)
                }
                // The whole point of a condition is a yes or a no.
                if (function.returnType != ValueType.BOOLEAN) {
                    throw ConditionFunctionNotBooleanException(
                        function.name,
                        function.returnType.name.lowercase(),
                    )
                }
            }

            else -> {
                val property = condition.property ?: throw ConditionPropertyMismatchException(
                    condition.type,
                    ConditionProperty.MESSAGE_AUTHOR,
                )
                if (property !in propertiesOf(condition.type)) {
                    throw ConditionPropertyMismatchException(condition.type, property)
                }
                val check = condition.check ?: throw ConditionCheckMismatchException(property, ConditionCheck.EQUALS)
                if (check !in checksOf(property)) throw ConditionCheckMismatchException(property, check)

                val needed = when (check) {
                    ConditionCheck.BETWEEN -> 2
                    ConditionCheck.WORKSPACEMATE, ConditionCheck.IN_LIST -> 1
                    else -> 1
                }
                if (condition.values.size < needed) throw ConditionValuesRequiredException(check)
            }
        }
    }

    /** Whether [candidate] is somewhere inside this condition, at any depth. */
    private fun contains(condition: WorkflowCondition, candidate: Long, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        if (condition.id == candidate) return true
        return condition.members.any { member ->
            conditions.findByIdOrNull(member)?.let { contains(it, candidate, depth + 1) } == true
        }
    }

    private fun List<String>.clean(): MutableList<String> =
        map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    private companion object {
        const val MAX_DEPTH = 10
    }
}

/** Which properties each type can ask about; the form offers exactly these. */
fun propertiesOf(type: ConditionType): List<ConditionProperty> = when (type) {
    ConditionType.SLACK -> listOf(
        ConditionProperty.MESSAGE_AUTHOR,
        ConditionProperty.MESSAGE_CHANNEL,
        ConditionProperty.MESSAGE_TEXT,
    )

    ConditionType.JIRA -> listOf(
        ConditionProperty.ISSUE_PRIORITY,
        ConditionProperty.ISSUE_STATUS,
        ConditionProperty.ISSUE_TYPE,
    )

    ConditionType.TIME -> listOf(ConditionProperty.CURRENT_TIME)
    ConditionType.FUNCTION, ConditionType.ANY_OF, ConditionType.ALL_OF, ConditionType.VALUE -> emptyList()
}

/** What a [ConditionType.VALUE] condition may ask of a value: everything but the time and the directory. */
val VALUE_CHECKS = listOf(
    ConditionCheck.IN_LIST,
    ConditionCheck.EQUALS,
    ConditionCheck.CONTAINS,
    ConditionCheck.MATCHES,
)

/** Which checks make sense for a property. */
fun checksOf(property: ConditionProperty): List<ConditionCheck> = when (property) {
    ConditionProperty.MESSAGE_AUTHOR -> listOf(
        ConditionCheck.WORKSPACEMATE,
        ConditionCheck.IN_LIST,
        ConditionCheck.EQUALS,
        ConditionCheck.MATCHES,
    )

    ConditionProperty.MESSAGE_CHANNEL -> listOf(ConditionCheck.IN_LIST, ConditionCheck.EQUALS, ConditionCheck.MATCHES)
    ConditionProperty.MESSAGE_TEXT -> listOf(ConditionCheck.CONTAINS, ConditionCheck.MATCHES, ConditionCheck.EQUALS)
    ConditionProperty.ISSUE_PRIORITY,
    ConditionProperty.ISSUE_STATUS,
    ConditionProperty.ISSUE_TYPE,
    -> listOf(ConditionCheck.IN_LIST, ConditionCheck.EQUALS)

    ConditionProperty.CURRENT_TIME -> listOf(ConditionCheck.BETWEEN)
}

/** "ANY_OF" -> "Any Of", as the badge and the form show it. */
fun typeLabel(type: ConditionType): String = type.name
    .split('_')
    .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }

data class CreateConditionInput(
    val workspaceId: Long,
    val name: String,
    /**
     * The workflow this belongs to, where it is that workflow's own.
     *
     * Null is the ordinary case: a definition the workspace shares, listed on
     * its page and pointable-at by anything. Set is "Custom" — made from a
     * node by somebody who wanted this one node to do a thing rather than to
     * add a name to a shared library — and it is left out of every list meant
     * for choosing from.
     */
    val workflowId: Long? = null,
    val type: ConditionType,
    val property: ConditionProperty? = null,
    val check: ConditionCheck? = null,
    val negate: Boolean? = null,
    val functionId: Long? = null,
    val values: List<String>? = null,
    /** What a function condition passes, one per parameter, in the function's order. */
    val arguments: List<ConditionArgumentInput>? = null,
    val members: List<Long>? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
)

/** One argument a function condition passes: a written value, or a field of the run. */
/**
 * The rows a form sent, as the entity keeps them.
 *
 * A row with no name is dropped, exactly as an unnamed value is: the form draws
 * one per parameter and a parameter nobody filled in is not an argument.
 */
private fun List<ConditionArgumentInput>.toArguments(): MutableList<ConditionArgument> =
    filter { it.name.trim().isNotEmpty() }
        .map { ConditionArgument(name = it.name.trim(), expression = it.expression, mode = it.mode) }
        .toMutableList()

/** One argument a function condition passes: a written value, or a field of the run. */
data class ConditionArgumentInput(
    val name: String,
    val expression: String,
    val mode: MappingMode,
)

data class UpdateConditionInput(
    val name: String? = null,
    val type: ConditionType? = null,
    val property: ConditionProperty? = null,
    val check: ConditionCheck? = null,
    val negate: Boolean? = null,
    val functionId: Long? = null,
    val values: List<String>? = null,
    /** What a function condition passes. Absent leaves what is stored alone. */
    val arguments: List<ConditionArgumentInput>? = null,
    val members: List<Long>? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
)

/** One of them, as the screen reads it back. */
data class ConditionArgumentView(
    val name: String,
    val expression: String,
    val mode: MappingMode,
)

data class ConditionView(
    val id: Long,
    val workspaceId: Long,
    /**
     * The workflow this belongs to, where it is that workflow's own.
     *
     * Null is the ordinary case - a definition the workspace shares. Set is
     * "Custom": made from a node, left out of the workspace's list, and shown
     * in no other workflow's picker.
     */
    val workflowId: Long?,
    val name: String,
    val type: ConditionType,
    val typeLabel: String,
    val property: ConditionProperty?,
    val check: ConditionCheck?,
    val negate: Boolean,
    val functionId: Long?,
    val functionName: String?,
    val values: List<String>,
    /** What a function condition passes, one per parameter, in the function's order. */
    val arguments: List<ConditionArgumentView>,
    val members: List<Long>,
    val memberNames: List<String>,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String?,
    /** What it asks, in words; read off the definition rather than stored. */
    val description: String,
)

data class ConditionPage(
    val content: List<ConditionView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowCondition>, describe: (WorkflowCondition) -> ConditionView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
