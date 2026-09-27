package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.DependencyKind
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.plugin.PluginActions
import io.mszymanski.orknux.server.security.WorkspaceAccess
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
 * A workspace's action catalogue: the blocks its workflows are built from.
 *
 * What an action needs and what it produces are not stored. They are read off
 * its settings — the arguments of the function it calls are what it needs, and
 * what it produces follows from what it does — so the two cannot drift from the
 * settings the way a second copy would.
 */
@Controller
class ActionAPI(
    private val actions: WorkflowActionRepository,
    private val functions: WorkflowFunctionRepository,
    private val conditions: WorkflowConditionRepository,
    private val parameters: ActionParameters,
    private val headers: ActionHeaders,
    private val connections: WorkspaceConnectionService,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val dependants: ComponentDependants,
    /** What the loaded plugins declare, for a PLUGIN_ACTION to be checked against. Issue #438. */
    private val pluginActions: PluginActions,
) {

    /**
     * The columns this list can be put in the order of, and what each means in
     * the database. Issue #358.
     *
     * Not every heading is here. The parameter counts are read off the
     * settings - `inputsOf` finds the placeholders - so there is no stored
     * number to order by, and ordering a page of twenty by a count computed
     * after the page was chosen would order the wrong twenty. Those two stay
     * headings rather than becoming controls that lie.
     *
     * The name breaks every tie: two actions of the same type in no particular
     * order is a list that shuffles between reads of the same page.
     */
    private val ACTION_ORDERS = mapOf(
        "NAME" to listOf("name"),
        "TYPE" to listOf("type", "name"),
        "SUBTYPE" to listOf("subtype", "name"),
    )

    @QueryMapping
    /** @param search what to look for in this list, or null for all of it. */
    fun workspaceActions(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): ActionPage {
        requireWorkspaceAccess(workspaceId)
        /*
         * The shared ones only. A definition a workflow owns is reachable from
         * the node that made it and nowhere else, so listing it here would
         * offer somebody a row they cannot use and did not ask for.
         */
        val paged = pageRequest(page, size, sortBy(order, ascending, ACTION_ORDERS, "NAME"))
        val looking = search?.trim().orEmpty()

        return ActionPage(
            if (looking.isEmpty()) {
                actions.findByWorkspaceIdAndWorkflowIdIsNull(workspaceId, paged)
            } else {
                actions.searching(workspaceId, looking, paged)
            },
            ::describe,
        )
    }

    /**
     * What one workflow owns: the "Custom" actions made from its own nodes.
     *
     * Its editor asks for these beside the workspace's list, because they are
     * exactly what that list leaves out - without them a node pointing at one
     * would show an empty picker, its action having been filtered out of the
     * only list the page was given.
     */
    @QueryMapping
    fun workflowOwnedActions(@Argument workspaceId: Long, @Argument workflowId: Long): List<ActionView> {
        requireWorkspaceAccess(workspaceId)
        return actions.findByWorkflowId(workflowId)
            .filter { it.workspaceId == workspaceId }
            .sortedBy { it.name }
            .map(::describe)
    }

    @QueryMapping
    fun action(@Argument id: Long): ActionView? {
        val action = actions.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(action)
    }

    @MutationMapping
    @Transactional
    fun createAction(@Argument input: CreateActionInput): ActionView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        if (name.isEmpty()) throw ActionNameInvalidException()
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
            actions.findByWorkspaceIdAndWorkflowIdIsNullAndName(input.workspaceId, name) != null
        ) {
            throw ActionNameTakenException(name)
        }

        val action = actions.save(
            WorkflowAction(
                workspaceId = input.workspaceId,
                workflowId = input.workflowId,
                name = name,
                type = input.type,
                subtype = input.subtype,
                connectionId = input.connectionId,
                connectionAction = input.connectionAction,
                content = input.content?.trim()?.ifEmpty { null },
                targetName = input.targetName?.trim()?.ifEmpty { null },
                emailTo = input.emailTo?.trim()?.ifEmpty { null },
                emailCc = input.emailCc?.trim()?.ifEmpty { null },
                emailSubject = input.emailSubject?.trim()?.ifEmpty { null },
                emailReplyTo = input.emailReplyTo?.trim()?.ifEmpty { null },
                speechText = input.speechText?.trim()?.ifEmpty { null },
                speechVoice = input.speechVoice?.trim()?.ifEmpty { null },
                speechModelId = input.speechModelId,
                url = input.url?.trim()?.ifEmpty { null },
                method = input.method?.trim()?.uppercase()?.ifEmpty { null },
                headers = writtenHeaders(input.workspaceId, input.headerRows, input.headers),
                functionId = input.functionId,
                pluginKey = input.pluginKey?.trim()?.ifEmpty { null },
                pluginAction = input.pluginAction?.trim()?.ifEmpty { null },
                mappings = input.mappings.orEmpty().toMappings(),
                conditionExpression = input.conditionExpression?.trim()?.ifEmpty { null },
                conditionId = input.conditionId,
                timeoutSeconds = input.timeoutSeconds,
                retryIntervalSeconds = input.retryIntervalSeconds,
                durationSeconds = input.durationSeconds,
                icon = input.icon?.trim()?.ifEmpty { null },
            ).also(::validate),
        )

        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Action $name created")
        return describe(action)
    }

    /** Backs the action settings form; the type and subtype are what it is. */
    @MutationMapping
    @Transactional
    fun updateAction(@Argument id: Long, @Argument input: UpdateActionInput): ActionView {
        val action = actions.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ActionNotFoundException(id)

        val previousName = action.name
        input.name?.trim()?.let { name ->
            if (name.isEmpty()) throw ActionNameInvalidException()
            // The same scope the create uses; see the note there.
            // Only a shared definition has a name to keep apart; see the create.
            val clash = if (action.workflowId == null) {
                actions.findByWorkspaceIdAndWorkflowIdIsNullAndName(action.workspaceId, name)
            } else {
                null
            }
            if (name != action.name && clash != null) {
                throw ActionNameTakenException(name)
            }
            action.name = name
        }
        input.subtype?.let { action.subtype = it }
        input.connectionId?.let { action.connectionId = it }
        input.connectionAction?.let { action.connectionAction = it }
        input.content?.let { action.content = it.trim().ifEmpty { null } }
        input.targetName?.let { action.targetName = it.trim().ifEmpty { null } }
        input.emailTo?.let { action.emailTo = it.trim().ifEmpty { null } }
        input.emailCc?.let { action.emailCc = it.trim().ifEmpty { null } }
        input.emailSubject?.let { action.emailSubject = it.trim().ifEmpty { null } }
        input.emailReplyTo?.let { action.emailReplyTo = it.trim().ifEmpty { null } }
        input.speechText?.let { action.speechText = it.trim().ifEmpty { null } }
        input.speechVoice?.let { action.speechVoice = it.trim().ifEmpty { null } }
        input.speechModelId?.let { action.speechModelId = it }
        input.url?.let { action.url = it.trim().ifEmpty { null } }
        input.method?.let { action.method = it.trim().uppercase().ifEmpty { null } }
        /*
         * Rows win when the form sent any, and the form sends them whenever it
         * could read what was stored. Only a client that has never heard of rows
         * - or a form looking at headers nobody can parse, which offers the text
         * itself instead - falls back to the string, and neither may quietly
         * throw the other's work away.
         */
        input.headerRows?.let { action.headers = headers.write(headers.checked(action.workspaceId, it.toRows())) }
            ?: input.headers?.let { action.headers = it.trim().ifEmpty { null } }
        input.functionId?.let { action.functionId = it }
        input.pluginKey?.let { action.pluginKey = it.trim().ifEmpty { null } }
        input.pluginAction?.let { action.pluginAction = it.trim().ifEmpty { null } }
        input.mappings?.let { action.mappings = it.toMappings() }
        input.conditionExpression?.let { action.conditionExpression = it.trim().ifEmpty { null } }
        input.conditionId?.let { action.conditionId = it }
        input.timeoutSeconds?.let { action.timeoutSeconds = it }
        input.retryIntervalSeconds?.let { action.retryIntervalSeconds = it }
        input.durationSeconds?.let { action.durationSeconds = it }
        // Sent every time the form is saved, so null is "no icon" rather than
        // "not mentioned" — which is what lets Clear clear it.
        action.icon = input.icon?.trim()?.ifEmpty { null }
        validate(action)

        val message = if (previousName == action.name) {
            "Action ${action.name} updated"
        } else {
            "Action $previousName renamed to ${action.name}"
        }
        auditRecorder.record(action.workspaceId, WorkspaceAuditCategory.WORKFLOW, message)
        return describe(action)
    }

    /**
     * Refused while a workflow runs it, the way a function it calls is.
     *
     * A function held in place by an action is the same rule read one link
     * earlier: `deleteFunction` refuses and names the caller, and until this
     * existed the action itself was held in place by nothing. So the reference a
     * published workflow depends on could be taken out from under it - the
     * snapshot went on naming an id that resolved to nothing, and a run said
     * "the action Act ran has been deleted", which is honest but is a workflow
     * that stopped working with nobody touching the workflow.
     *
     * The published copy is what makes this more than a repeat of the function
     * guard. A draft naming it is a canvas somebody can redraw; a snapshot
     * naming it cannot be edited at all, so it is the half that has to be asked.
     */
    @MutationMapping
    @Transactional
    fun deleteAction(@Argument id: Long): Boolean {
        val action = actions.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val users = dependants.of(DependencyKind.ACTION, id)
        if (users.isNotEmpty()) throw ActionInUseException(action.name, users.phrases())

        actions.delete(action)
        auditRecorder.record(action.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Action ${action.name} deleted")
        return true
    }

    /**
     * The list's Subtype column, and the settings a form has to show.
     */
    private fun describe(action: WorkflowAction): ActionView {
        val function = action.functionId?.let { functions.findByIdOrNull(it) }
        return ActionView(
            id = requireNotNull(action.id),
            workspaceId = action.workspaceId,
            workflowId = action.workflowId,
            name = action.name,
            type = action.type,
            subtype = action.subtype,
            subtypeLabel = label(action.subtype),
            connectionId = action.connectionId,
            connectionName = action.connectionId?.let { connections.workspaceConnection(it)?.name },
            connectionAction = action.connectionAction,
            content = action.content,
            targetName = action.targetName,
            emailTo = action.emailTo,
            emailCc = action.emailCc,
            emailSubject = action.emailSubject,
            emailReplyTo = action.emailReplyTo,
            speechText = action.speechText,
            speechVoice = action.speechVoice,
            speechModelId = action.speechModelId,
            url = action.url,
            method = action.method,
            headers = action.headers,
            headerRows = headers.viewOf(action),
            // False is an action whose stored headers are not readable as rows.
            // The form shows the text itself when it hears this, because taking
            // the only editor away from a value nobody can parse would leave
            // somebody looking at a broken action with no way to mend it.
            headersReadable = headers.rowsOf(action.headers) != null,
            functionId = action.functionId,
            functionName = function?.name,
            pluginKey = action.pluginKey,
            pluginAction = action.pluginAction,
            // Null where the plugin is gone or stopped declaring it, which is
            // what lets the form say so beside the two names it still holds.
            pluginActionLabel = declaredBy(action)?.label,
            mappings = action.mappings.map { ArgumentMappingView(it.argument, it.expression) },
            conditionExpression = action.conditionExpression,
            conditionId = action.conditionId,
            conditionName = action.conditionId?.let { conditions.findByIdOrNull(it)?.name },
            timeoutSeconds = action.timeoutSeconds,
            retryIntervalSeconds = action.retryIntervalSeconds,
            durationSeconds = action.durationSeconds,
            icon = action.icon,
            inputParams = parameters.inputsOf(action),
            outputParams = parameters.outputsOf(action),
        )
    }

    /** The declaration a PLUGIN_ACTION names, or null for any other subtype and for a name nothing declares. */
    private fun declaredBy(action: WorkflowAction) = action.pluginKey?.let { key ->
        action.pluginAction?.let { name -> pluginActions.declared(key, name) }
    }

    /**
     * A subtype belongs to one type, and each one needs the setting it runs on:
     * an HTTP request without a URL is a form that was not filled in, not an
     * action that fails later.
     *
     * **Except for a definition one workflow owns, which may be half-made.**
     * That one is filled in on the node that uses it, a field at a time, and
     * the panel holding it writes as it is typed - so "Function, and I have
     * not picked the function yet" is where somebody is while they look
     * through the list, not a mistake to refuse. Refusing it meant the graph
     * was saved with the node pointing at nothing and the work was gone on the
     * next reload, which is what people read as saving being broken.
     *
     * The type still has to belong to the subtype, because that pair is chosen
     * by two controls that cannot disagree, and a run reaching an unfinished
     * one is prevented where the rest of an unfinished graph is: publish. See
     * `GraphValidator.missingFrom`.
     */
    private fun validate(action: WorkflowAction) {
        val allowed = when (action.type) {
            ActionType.EXECUTE -> setOf(
                ActionSubtype.OUTGOING_CONNECTION,
                ActionSubtype.SEND_EMAIL,
                ActionSubtype.HTTP_REQUEST,
                ActionSubtype.FUNCTION,
                ActionSubtype.PLUGIN_ACTION,
                // Speaking performs something and carries on, which is what
                // EXECUTE means. It waits for nothing.
                ActionSubtype.SPEAK,
            )

            ActionType.WAIT -> setOf(
                ActionSubtype.INLINE_CONDITION,
                ActionSubtype.CONDITION,
                ActionSubtype.TIME,
            )
        }
        if (action.subtype !in allowed) throw ActionSubtypeMismatchException(action.type, action.subtype)

        // A draft belonging to one node, which is allowed to be unfinished.
        if (action.workflowId != null) return

        when (action.subtype) {
            ActionSubtype.OUTGOING_CONNECTION -> {
                val connectionId = action.connectionId ?: throw ActionSettingMissingException("a connection")
                val connection = connections.workspaceConnection(connectionId)
                if (connection == null || connection.workspaceId != action.workspaceId) {
                    throw ActionSettingMissingException("a connection this workspace holds")
                }
                if (action.connectionAction == null) throw ActionSettingMissingException("an action to perform")
            }

            /*
             * A mail needs somewhere to go out through, and that somewhere has to
             * be a mail server: pointing this at a Slack connection would save
             * cleanly and then skip every time it ran, which is a worse way to
             * find out than being told here.
             */
            ActionSubtype.SEND_EMAIL -> {
                val connectionId = action.connectionId ?: throw ActionSettingMissingException("a mail connection")
                val connection = connections.workspaceConnection(connectionId)
                if (connection == null || connection.workspaceId != action.workspaceId) {
                    throw ActionSettingMissingException("a connection this workspace holds")
                }
                if (connection.type != ConnectionType.SMTP) {
                    throw ActionSettingMissingException("an SMTP connection to send the mail through")
                }
            }

            ActionSubtype.HTTP_REQUEST -> {
                if (action.url.isNullOrBlank()) throw ActionSettingMissingException("a URL")
                action.method = action.method?.ifBlank { null } ?: "GET"
            }

            /*
             * A plugin's functions belong to no workspace and are offered in
             * every one — the picker lists them, the runner calls them in the
             * plugin's own sandbox without asking whose they are, and unloading
             * a plugin counts the actions naming them. Only another workspace's
             * own function is out of reach, so that is the only one refused:
             * asking for "a function this workspace owns" about one the picker
             * had just offered described a box somebody had already filled in.
             */
            ActionSubtype.FUNCTION -> {
                val functionId = action.functionId ?: throw ActionSettingMissingException("a function")
                val function = functions.findByIdOrNull(functionId)
                if (function == null) throw ActionSettingMissingException("a function")
                // A workspace's own is the only kind tied to a workspace: a
                // plugin's and an embedded one are available everywhere. #501.
                if (function.scope == FunctionScope.WORKSPACE && function.workspaceId != action.workspaceId) {
                    throw ActionSettingMissingException("a function this workspace owns")
                }
            }

            /*
             * Both names, and a plugin that declares them. Checked against the
             * declaration whatever the plugin's switch says - a shared action
             * is a finished thing people pick from a list, and one naming a
             * block no plugin declares is a row nobody could ever run - but
             * not against `enabled`: a plugin switched off is a decision about
             * the plugin, and renaming an action that points at it should not
             * be refused over it. The run is where the switch is felt.
             */
            ActionSubtype.PLUGIN_ACTION -> {
                val key = action.pluginKey ?: throw ActionSettingMissingException("a plugin")
                val name = action.pluginAction ?: throw ActionSettingMissingException("an action the plugin declares")
                if (pluginActions.declared(key, name) == null) {
                    throw ActionSettingMissingException("an action the $key plugin declares; it does not declare $name")
                }
            }

            ActionSubtype.INLINE_CONDITION -> {
                if (action.conditionExpression.isNullOrBlank()) throw ActionSettingMissingException("an expression")
                action.timeoutSeconds = action.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                action.retryIntervalSeconds = action.retryIntervalSeconds ?: DEFAULT_RETRY_SECONDS
            }

            ActionSubtype.CONDITION -> {
                val conditionId = action.conditionId ?: throw ActionSettingMissingException("a condition")
                val condition = conditions.findByIdOrNull(conditionId)
                if (condition == null || condition.workspaceId != action.workspaceId) {
                    throw ActionSettingMissingException("a condition this workspace holds")
                }
                action.timeoutSeconds = action.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                action.retryIntervalSeconds = action.retryIntervalSeconds ?: DEFAULT_RETRY_SECONDS
            }

            /*
             * The words, and nothing else. A model is not asked for here: the
             * workspace has usually chosen one - it is the same model a chat
             * reads an answer aloud with - and an action that named none follows
             * it. A run that reaches one with no model anywhere is told so by
             * the step rather than refused at the form, which is the rule the
             * picture nodes already keep.
             */
            ActionSubtype.SPEAK -> {
                if (action.speechText.isNullOrBlank()) throw ActionSettingMissingException("something to say")
            }

            ActionSubtype.TIME -> {
                if ((action.durationSeconds ?: 0) <= 0) throw ActionSettingMissingException("a duration in seconds")
            }
        }

        refuseHolders("the message", action.content)
        refuseHolders("who it goes to", action.targetName)
        refuseHolders("who the mail goes to", action.emailTo)
        refuseHolders("who is copied", action.emailCc)
        refuseHolders("the subject", action.emailSubject)
        refuseHolders("the reply-to address", action.emailReplyTo)
        refuseHolders("the URL", action.url)
        refuseHolders("the headers", action.headers)
        action.mappings.forEach { refuseHolders("the argument ${it.argument}", it.expression) }
    }

    /**
     * Nothing here is substituted, so nothing may look as though it is.
     *
     * A setting is used exactly as written. `{{input.text}}` used to mean "put
     * the incoming text here" and now means those fourteen characters, so a
     * definition holding one would seed nodes that send it verbatim — which is
     * how a workflow came to reply `{{llmResult}}` to somebody. A node says what
     * varies now: leave the setting empty and the node reads the field by name,
     * or point its parameter at any field the run is carrying.
     */
    private fun refuseHolders(setting: String, written: String?) {
        if (written?.contains("{{") == true) throw ActionHoldsPlaceholderException(setting)
    }

    /**
     * What goes in the column: the rows if the form sent them, the string if not.
     *
     * Both are accepted because both are current. The rows are what the form
     * sends and what a reference can be expressed in at all; the string is what
     * an import writes and what every action saved before this held, and it is
     * read back as rows by the same reader that reads the rows.
     */
    private fun writtenHeaders(workspaceId: Long, rows: List<ActionHeaderInput>?, written: String?): String? =
        rows?.let { headers.write(headers.checked(workspaceId, it.toRows())) }
            ?: written?.trim()?.ifEmpty { null }

    private fun List<ActionHeaderInput>.toRows(): List<ActionHeaderRow> = map { row ->
        ActionHeaderRow(
            name = row.name,
            literal = row.value,
            variableId = row.variableId,
        )
    }

    private fun List<ArgumentMappingInput>.toMappings(): MutableList<ArgumentMapping> = this
        .map { ArgumentMapping(argument = it.argument.trim(), expression = it.expression.trim()) }
        .filter { it.argument.isNotEmpty() }
        .toMutableList()

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 3600
        const val DEFAULT_RETRY_SECONDS = 30
    }
}

/** "OUTGOING_CONNECTION" -> "Outgoing Connection", as the list shows it. */
fun label(subtype: ActionSubtype): String = subtype.name
    .split('_')
    .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }
    .replace("Http", "HTTP")

data class ArgumentMappingInput(val argument: String, val expression: String)

/** One header row a form sent: a name, and exactly one of a value and a variable. */
data class ActionHeaderInput(
    val name: String,
    val value: String? = null,
    val variableId: Long? = null,
)

data class CreateActionInput(
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
    val type: ActionType,
    val subtype: ActionSubtype,
    val connectionId: Long? = null,
    val connectionAction: ConnectionAction? = null,
    val content: String? = null,
    /** Where a send goes, as typed; a send resolves it. See [WorkflowAction.targetName]. */
    val targetName: String? = null,
    /** A mail's recipients and copy list, comma-separated, and what it is about. */
    val emailTo: String? = null,
    val emailCc: String? = null,
    val emailSubject: String? = null,
    val emailReplyTo: String? = null,
    /** What a SPEAK action reads out; a node may say something else instead. */
    val speechText: String? = null,
    /** Which voice, where the provider offers more than one; null is its own. */
    val speechVoice: String? = null,
    /** Which model speaks it; null follows the workspace's own choice. */
    val speechModelId: Long? = null,
    val url: String? = null,
    val method: String? = null,
    /** The headers as one JSON string; what an import carries and what rows are read back out of. */
    val headers: String? = null,
    /** The headers as rows, which is what the form sends. Wins over [headers] when both arrive. */
    val headerRows: List<ActionHeaderInput>? = null,
    val functionId: Long? = null,
    /** Which plugin's action a PLUGIN_ACTION runs, and what the plugin calls it. Issue #438. */
    val pluginKey: String? = null,
    val pluginAction: String? = null,
    val mappings: List<ArgumentMappingInput>? = null,
    val conditionExpression: String? = null,
    val conditionId: Long? = null,
    val timeoutSeconds: Int? = null,
    val retryIntervalSeconds: Int? = null,
    val durationSeconds: Int? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
)

data class UpdateActionInput(
    val name: String? = null,
    val subtype: ActionSubtype? = null,
    val connectionId: Long? = null,
    val connectionAction: ConnectionAction? = null,
    val content: String? = null,
    /** Where a send goes, as typed; a send resolves it. See [WorkflowAction.targetName]. */
    val targetName: String? = null,
    /** A mail's recipients and copy list, comma-separated, and what it is about. */
    val emailTo: String? = null,
    val emailCc: String? = null,
    val emailSubject: String? = null,
    val emailReplyTo: String? = null,
    /** What a SPEAK action reads out; a node may say something else instead. */
    val speechText: String? = null,
    /** Which voice, where the provider offers more than one; null is its own. */
    val speechVoice: String? = null,
    /** Which model speaks it; null follows the workspace's own choice. */
    val speechModelId: Long? = null,
    val url: String? = null,
    val method: String? = null,
    /** The headers as one JSON string; what an import carries and what rows are read back out of. */
    val headers: String? = null,
    /** The headers as rows, which is what the form sends. Wins over [headers] when both arrive. */
    val headerRows: List<ActionHeaderInput>? = null,
    val functionId: Long? = null,
    /** Which plugin's action a PLUGIN_ACTION runs, and what the plugin calls it. Issue #438. */
    val pluginKey: String? = null,
    val pluginAction: String? = null,
    val mappings: List<ArgumentMappingInput>? = null,
    val conditionExpression: String? = null,
    val conditionId: Long? = null,
    val timeoutSeconds: Int? = null,
    val retryIntervalSeconds: Int? = null,
    val durationSeconds: Int? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
)

data class ArgumentMappingView(val argument: String, val expression: String)

data class ActionParamView(val name: String, val type: ValueType) {
    /** "message: string", which is how both the list and the form read them. */
    val display: String get() = "$name: ${type.name.lowercase()}"
}

data class ActionView(
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
    val type: ActionType,
    val subtype: ActionSubtype,
    val subtypeLabel: String,
    val connectionId: Long?,
    val connectionName: String?,
    val connectionAction: ConnectionAction?,
    val content: String?,
    /** Where a send goes, as typed; a send resolves it. See [WorkflowAction.targetName]. */
    val targetName: String?,
    /** A mail's recipients and copy list, comma-separated, and what it is about. */
    val emailTo: String?,
    val emailCc: String?,
    val emailSubject: String?,
    val emailReplyTo: String?,
    /** What a SPEAK action reads out; a node may say something else instead. */
    val speechText: String?,
    /** Which voice, where the provider offers more than one; null is its own. */
    val speechVoice: String?,
    /** Which model speaks it; null follows the workspace's own choice. */
    val speechModelId: Long?,
    val url: String?,
    val method: String?,
    val headers: String?,
    /** The same headers as rows, with a reference naming its variable and never holding its value. */
    val headerRows: List<ActionHeaderView>,
    /** Whether [headers] could be read as rows at all. */
    val headersReadable: Boolean,
    val functionId: Long?,
    val functionName: String?,
    /** Which plugin's action a PLUGIN_ACTION runs, and what the plugin calls it. Issue #438. */
    val pluginKey: String?,
    val pluginAction: String?,
    /** What the plugin labels it, or null where no loaded plugin declares it any more. */
    val pluginActionLabel: String?,
    val mappings: List<ArgumentMappingView>,
    val conditionExpression: String?,
    val conditionId: Long?,
    val conditionName: String?,
    val timeoutSeconds: Int?,
    val retryIntervalSeconds: Int?,
    val durationSeconds: Int?,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String?,
    /** Read off the settings, not stored; see `ActionAPI.inputsOf`. */
    val inputParams: List<ActionParamView>,
    val outputParams: List<ActionParamView>,
)

data class ActionPage(
    val content: List<ActionView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowAction>, describe: (WorkflowAction) -> ActionView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
