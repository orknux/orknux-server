package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.DeliverableActions
import io.mszymanski.orknux.connector.connection.SlackBotUser
import io.mszymanski.orknux.connector.connection.SlackBotUserOutcome
import io.mszymanski.orknux.connector.connection.SlackBotUsers
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.workflow.ConditionNotInCatalogueException
import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.DependencyKind
import io.mszymanski.orknux.server.dependency.phrases
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
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A workspace's trigger catalogue: the events it can start work from, each defined
 * once. Nothing here names a workflow — a workflow points one of its trigger
 * nodes at a definition, and that instance is what wires the two together.
 */
@Controller
class TriggerAPI(
    private val triggers: WorkflowTriggerRepository,
    private val connections: WorkspaceConnectionService,
    private val conditions: WorkflowConditionRepository,
    private val objects: WorkflowObjectRepository,
    private val functions: WorkflowFunctionRepository,
    private val firings: TriggerFiringRepository,
    private val mapper: ObjectMapper,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    /** Who a Slack connection posts as, which is what a reply is matched against. */
    private val botUsers: SlackBotUsers,
    private val dependants: ComponentDependants,
) {

    /**
     * The columns this list can be put in the order of. Issue #358.
     *
     * Two headings are not orders. Source is the name of whatever the trigger
     * listens on, resolved row by row, and Last fired comes from the firing log
     * - a different table, whose newest row per trigger is not something this
     * query can order by without becoming a different query.
     */
    private val TRIGGER_ORDERS = mapOf(
        "NAME" to listOf("name"),
        "TYPE" to listOf("type", "name"),
        "ACTION" to listOf("action", "name"),
        "STATUS" to listOf("enabled", "name"),
    )

    @QueryMapping
    /** @param search what to look for in this list, or null for all of it. */
    fun workspaceTriggers(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
        @Argument source: String?,
    ): TriggerPage {
        requireWorkspaceAccess(workspaceId)
        // The shared ones only; see the note on workflowOwnedActions.
        val sort = if (order?.trim()?.uppercase() == "LAST_FIRED") {
            // A trigger that has never fired has no time, and goes last either way
            // round: no answer is not the newest one, and not the oldest either.
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order(
                    if (ascending == false) org.springframework.data.domain.Sort.Direction.DESC else org.springframework.data.domain.Sort.Direction.ASC,
                    "lastFiringAt",
                ).nullsLast(),
                org.springframework.data.domain.Sort.Order.asc("name"),
            )
        } else {
            sortBy(order, ascending, TRIGGER_ORDERS, "NAME")
        }
        val paged = pageRequest(page, size, sort)
        val looking = search?.trim().orEmpty()
        val where = TriggerSource.parse(source)

        return TriggerPage(
            when {
                where != null -> triggers.findAll(triggerFilter(workspaceId, looking, where.type, where.connectionId), paged)
                looking.isEmpty() -> triggers.findByWorkspaceIdAndWorkflowIdIsNull(workspaceId, paged)
                else -> triggers.searching(workspaceId, looking, paged)
            },
            ::describe,
        )
    }

    /**
     * Where this workspace's triggers listen, for the list's Source sieve:
     * the schedule, the webhook, and each connection one listens on - only
     * the ones in use, labelled as the Source column labels them.
     */
    @QueryMapping
    fun workspaceTriggerSources(@Argument workspaceId: Long): List<TriggerSourceView> {
        requireWorkspaceAccess(workspaceId)
        val held = triggers.findByWorkspaceIdAndWorkflowIdIsNull(workspaceId, org.springframework.data.domain.Pageable.unpaged()).content
        val sources = linkedMapOf<String, String>()
        held.forEach { trigger ->
            when (trigger.type) {
                TriggerType.SCHEDULED -> sources[TriggerSource.CRON] = "Cron"
                TriggerType.WEBHOOK -> sources[TriggerSource.WEBHOOK] = "Webhook"
                TriggerType.INCOMING_CONNECTION -> trigger.connectionId?.let { id ->
                    sources["${TriggerSource.CONNECTION}$id"] = connections.workspaceConnection(id)?.name ?: "—"
                }
            }
        }
        return sources.map { (value, label) -> TriggerSourceView(value, label) }.sortedBy { it.label.lowercase() }
    }

    /** The "Custom" triggers one workflow owns; see workflowOwnedActions. */
    @QueryMapping
    fun workflowOwnedTriggers(@Argument workspaceId: Long, @Argument workflowId: Long): List<TriggerView> {
        requireWorkspaceAccess(workspaceId)
        return triggers.findByWorkflowId(workflowId)
            .filter { it.workspaceId == workspaceId }
            .sortedBy { it.name }
            .map(::describe)
    }

    @QueryMapping
    fun trigger(@Argument id: Long): TriggerView? {
        val trigger = triggers.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(trigger)
    }

    @MutationMapping
    @Transactional
    fun createTrigger(@Argument input: CreateTriggerInput): TriggerView {
        val name = input.name.trim()
        if (name.isEmpty()) throw TriggerNameInvalidException()
        requireWorkspaceAccess(input.workspaceId)
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
            triggers.findByWorkspaceIdAndWorkflowIdIsNullAndName(input.workspaceId, name) != null
        ) {
            throw TriggerNameTakenException(name)
        }

        val trigger = triggers.save(
            WorkflowTrigger(
                workspaceId = input.workspaceId,
                workflowId = input.workflowId,
                name = name,
                type = input.type,
                connectionId = input.connectionId.takeIf { input.type == TriggerType.INCOMING_CONNECTION },
                action = input.action
                    ?.also(::requireDeliverable)
                    .takeIf { input.type == TriggerType.INCOMING_CONNECTION },
                watchedConnectionIds = input.watchedConnectionIds
                    .takeIf { input.type == TriggerType.INCOMING_CONNECTION }
                    .orEmpty()
                    .toMutableSet(),
                cron = input.cron?.trim()?.ifEmpty { null }.takeIf { input.type == TriggerType.SCHEDULED },
                timezone = input.timezone?.trim()?.ifEmpty { null }.takeIf { input.type == TriggerType.SCHEDULED },
                webhookPath = input.webhookPath?.let(::pathOf).takeIf { input.type == TriggerType.WEBHOOK },
                objectId = input.objectId.takeIf { input.type == TriggerType.WEBHOOK },
                authType = input.authType.takeIf { input.type == TriggerType.WEBHOOK } ?: WebhookAuthType.NONE,
                authFunctionId = input.authFunctionId.takeIf {
                    input.type == TriggerType.WEBHOOK && input.authType == WebhookAuthType.FUNCTION
                },
                payload = input.payload?.trim()?.ifEmpty { null }?.also(::requireJsonObject),
                conditionId = input.conditionId?.also { requireConditionInWorkspace(input.workspaceId, it) },
                icon = input.icon?.trim()?.ifEmpty { null },
                enabled = input.enabled,
            ).also(::validate),
        )

        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Trigger $name created")
        // Said separately, in the words the toggle uses, so a definition that
        // arrived switched off is findable the same way one switched off later is.
        if (!trigger.enabled) {
            auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, enabledMessage(name, false))
        }
        return describe(trigger)
    }

    /**
     * What this trigger has done, newest first.
     *
     * The entries that matter are mostly the ones no run came of — an event the
     * condition turned down, or a definition nothing instances. Those appear
     * nowhere else, which is what makes a working trigger and a silent one look
     * the same from outside.
     */
    @QueryMapping
    fun triggerFirings(@Argument triggerId: Long, @Argument page: Int?, @Argument size: Int?): TriggerFiringPage {
        // Somebody else's trigger reads as a trigger that is not there: the page
        // cannot be null, so the two answers are made the same one instead.
        triggers.findByIdOrNull(triggerId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw TriggerNotFoundException(triggerId)
        return TriggerFiringPage(
            firings.findByTriggerIdOrderByAtDesc(triggerId, pageRequest(page, size, Sort.by("at").descending())),
            ::describe,
        )
    }

    /**
     * What every trigger in this workspace has done, newest first.
     *
     * The same entries the per-trigger log holds, read the other way round: not
     * "what did this trigger do" but "what has happened here" — which is the
     * question somebody has when a workflow did not run and they do not yet know
     * which trigger to blame.
     */
    @QueryMapping
    fun workspaceTriggerFirings(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
    ): TriggerFiringPage {
        requireWorkspaceAccess(workspaceId)
        val held = firings.findByWorkspaceIdOrderByAtDesc(
            workspaceId,
            pageRequest(page, size, Sort.by("at").descending()),
        )
        // The names in one query rather than one each: a page of twenty entries
        // from three triggers should not be twenty lookups.
        val names = triggers.findAllById(held.content.map { it.triggerId }.distinct())
            .associate { requireNotNull(it.id) to it.name }
        return TriggerFiringPage(held) { firing -> describe(firing, names[firing.triggerId]) }
    }

    /** Backs the trigger settings form; the workspace and the type are what it is. */
    @MutationMapping
    @Transactional
    fun updateTrigger(@Argument id: Long, @Argument input: UpdateTriggerInput): TriggerView {
        val trigger = triggers.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw TriggerNotFoundException(id)

        val name = input.name.trim()
        if (name.isEmpty()) throw TriggerNameInvalidException()
        // The same scope the create uses; see the note there.
        // Only a shared definition has a name to keep apart; see the create.
        val clash = if (trigger.workflowId == null) {
            triggers.findByWorkspaceIdAndWorkflowIdIsNullAndName(trigger.workspaceId, name)
        } else {
            null
        }
        if (name != trigger.name && clash != null) {
            throw TriggerNameTakenException(name)
        }
        val previousName = trigger.name
        val previouslyEnabled = trigger.enabled
        trigger.name = name

        /*
         * What kind of trigger it is, for one a workflow owns.
         *
         * A shared trigger's kind is what it is: several workflows may point
         * at one, and changing it under them rewrites all of them from a
         * screen that shows none of them. A definition belonging to one node
         * is that node's alone, and being unable to change its kind meant
         * starting again - point the picker elsewhere, back to Custom, and
         * fill the form in a second time.
         *
         * The settings of the kind it was are cleared as it changes. They
         * belong to the old kind and nothing reads them under the new one, so
         * leaving them would keep a cron on a trigger that watches a channel.
         */
        input.type?.takeIf { it != trigger.type && trigger.workflowId != null }?.let { became ->
            trigger.type = became
            trigger.connectionId = null
            trigger.action = null
            trigger.watchedConnectionIds.clear()
            trigger.cron = null
            trigger.timezone = null
            trigger.webhookPath = null
            trigger.objectId = null
        }
        if (trigger.type == TriggerType.INCOMING_CONNECTION) {
            input.connectionId?.let { trigger.connectionId = it }
            input.action?.let {
                requireDeliverable(it)
                trigger.action = it
            }
            // Assigned rather than only applied when it was sent, the way the
            // condition below is: watching nobody is a choice a form has to be
            // able to save, and it is the shape every non-reply trigger has.
            //
            // Emptied and refilled rather than replaced, because this is the
            // loaded collection Hibernate is tracking and handing it a new one
            // is how a managed entity gets an orphan it will not save.
            val watched = input.watchedConnectionIds.orEmpty().toSet()
            trigger.watchedConnectionIds.retainAll(watched)
            trigger.watchedConnectionIds.addAll(watched)
        } else if (trigger.type == TriggerType.WEBHOOK) {
            input.webhookPath?.let { trigger.webhookPath = pathOf(it) }
            // Null takes the contract off, which the form has to be able to say.
            trigger.objectId = input.objectId
            input.authType?.let { trigger.authType = it }
            // Kept only while something asks for it, so switching back to open
            // does not leave a function nothing calls.
            trigger.authFunctionId = input.authFunctionId
                ?.takeIf { trigger.authType == WebhookAuthType.FUNCTION }
        } else {
            input.cron?.let { trigger.cron = it.trim().ifEmpty { null } }
            input.timezone?.let { trigger.timezone = it.trim().ifEmpty { null } }
        }
        input.payload?.let { trigger.payload = it.trim().ifEmpty { null }?.also(::requireJsonObject) }
        // Null is "ask nothing", which the form has to be able to say, so this
        // one is assigned rather than only applied when it was sent.
        trigger.conditionId = input.conditionId?.also { requireConditionInWorkspace(trigger.workspaceId, it) }
        // Null clears it, the way the condition above clears: the form sends
        // what it holds, and holding nothing is a choice.
        trigger.icon = input.icon?.trim()?.ifEmpty { null }
        // Left alone when the caller does not mention it: the form carries the
        // switch, and every other door still saves a trigger without one.
        input.enabled?.let { trigger.enabled = it }
        validate(trigger)

        val message = if (name == previousName) "Trigger $name updated" else "Trigger $previousName renamed to $name"
        auditRecorder.record(trigger.workspaceId, WorkspaceAuditCategory.WORKFLOW, message)
        // The same line the toggle in the list writes, because it is the same
        // change: reading the log should not depend on which door it came in by.
        if (trigger.enabled != previouslyEnabled) {
            auditRecorder.record(
                trigger.workspaceId,
                WorkspaceAuditCategory.WORKFLOW,
                enabledMessage(name, trigger.enabled),
            )
        }
        return describe(trigger)
    }

    /**
     * The events this installation can actually deliver.
     *
     * The screen asks rather than hard-coding a list, because what is wired is
     * the connection module's business — and a picker offering an event nothing
     * publishes is how somebody ends up with a trigger that looks configured and
     * never fires.
     */
    @QueryMapping
    fun supportedTriggerActions(): List<TriggerAction> =
        DeliverableActions.published.map { TriggerAction.valueOf(it.name) }.sortedBy { it.name }

    /** The toggle in the list. A disabled trigger stays, and stops firing. */
    @MutationMapping
    @Transactional
    fun setTriggerEnabled(@Argument id: Long, @Argument enabled: Boolean): TriggerView {
        val trigger = triggers.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw TriggerNotFoundException(id)

        trigger.enabled = enabled
        auditRecorder.record(
            trigger.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            enabledMessage(trigger.name, enabled),
        )
        return describe(trigger)
    }

    /** Refused while a workflow starts from it; see [TriggerInUseException]. */
    @MutationMapping
    @Transactional
    fun deleteTrigger(@Argument id: Long): Boolean {
        val trigger = triggers.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val users = dependants.of(DependencyKind.TRIGGER, id)
        if (users.isNotEmpty()) throw TriggerInUseException(trigger.name, users.phrases())

        triggers.delete(trigger)
        auditRecorder.record(trigger.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Trigger ${trigger.name} deleted")
        return true
    }

    /**
     * The list shows where an event comes from and what it is: the connection
     * for an incoming trigger, the clock and the expression for a scheduled one.
     */
    private fun describe(trigger: WorkflowTrigger): TriggerView {
        val connection = trigger.connectionId?.let { connections.workspaceConnection(it) }
        return TriggerView(
            id = requireNotNull(trigger.id),
            workspaceId = trigger.workspaceId,
            workflowId = trigger.workflowId,
            name = trigger.name,
            type = trigger.type,
            connectionId = trigger.connectionId,
            action = trigger.action,
            watchedConnectionIds = trigger.watchedConnectionIds.sorted(),
            cron = trigger.cron,
            timezone = trigger.timezone,
            payload = trigger.payload,
            conditionId = trigger.conditionId,
            lastFiring = trigger.id?.let { firings.findFirstByTriggerIdOrderByAtDesc(it) }?.let(::describe),
            conditionName = trigger.conditionId?.let { conditions.findByIdOrNull(it) }?.name,
            enabled = trigger.enabled,
            icon = trigger.icon,
            webhookPath = trigger.webhookPath,
            objectId = trigger.objectId,
            objectName = trigger.objectId?.let { objects.findByIdOrNull(it) }?.name,
            authType = trigger.authType,
            authFunctionId = trigger.authFunctionId,
            authFunctionName = trigger.authFunctionId?.let { functions.findByIdOrNull(it) }?.name,
            source = when (trigger.type) {
                TriggerType.SCHEDULED -> "Cron"
                // The connection may have been disconnected since.
                TriggerType.INCOMING_CONNECTION -> connection?.name ?: "—"
                TriggerType.WEBHOOK -> "Webhook"
            },
            event = when (trigger.type) {
                TriggerType.SCHEDULED -> trigger.cron ?: "—"
                TriggerType.INCOMING_CONNECTION -> trigger.action?.let(::actionLabel) ?: "—"
                // What somebody has to call, which is the thing worth showing.
                TriggerType.WEBHOOK -> trigger.webhookPath?.let { "/api/webhooks/$it" } ?: "—"
            },
        )
    }

    private fun describe(firing: TriggerFiring, triggerName: String? = null) = TriggerFiringView(
        id = requireNotNull(firing.id),
        at = firing.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        outcome = firing.outcome,
        detail = firing.detail,
        runsStarted = firing.runsStarted,
        triggerId = firing.triggerId,
        // Only worth carrying where the list holds more than one trigger's
        // entries; a trigger's own log already says whose they are.
        triggerName = triggerName,
    )

    /**
     * An event nothing raises is not one to wait for.
     *
     * The enum holds the vocabulary the product intends; only some of it is
     * wired. Saving a trigger on an unwired event would produce a definition
     * that is enabled, instanced, and silent for ever.
     */
    private fun requireDeliverable(action: TriggerAction) {
        if (action.name !in DeliverableActions.published.map { it.name }) {
            throw TriggerActionUnsupportedException(action.name)
        }
    }

    /**
     * A reply trigger has somebody to watch, and every one of them can say who
     * it is.
     *
     * **Resolved at save time and not only at match time.** The match is
     * `parent_user_id` against the Slack user behind each watched bot token, so
     * a token that will not authenticate makes a trigger that is enabled,
     * instanced and permanently deaf. The only way to discover that later is to
     * wait for a reply that never fires, which is the failure this refuses at
     * the door instead. It also warms the cache the matching reads, so the first
     * reply after a save costs nothing.
     *
     * Only a reply needs one. A mention or a message is about the connection it
     * arrives on, so a watch list on either is left alone rather than refused —
     * a form that switches the event over should not have to remember to clear
     * a field the new event does not read.
     */
    private fun requireWatchable(trigger: WorkflowTrigger) {
        if (trigger.action != TriggerAction.REPLY) return
        if (trigger.watchedConnectionIds.isEmpty()) throw TriggerReplyWatchRequiredException()

        for (id in trigger.watchedConnectionIds) {
            val connection = connections.workspaceConnection(id)
            if (connection == null || connection.workspaceId != trigger.workspaceId) {
                throw TriggerConnectionRequiredException()
            }
            // A reply's parent is a Slack user, and only a Slack connection has
            // one. Nothing else could ever be the author of a thread.
            if (connection.type != ConnectionType.SLACK) throw TriggerConnectionRequiredException()

            val bot = botUsers.identify(id)
            if (bot.outcome != SlackBotUserOutcome.FOUND || bot.userId == null) {
                throw TriggerReplyWatchUnusableException(connection.name, bot.message)
            }
        }
    }

    /**
     * Which Slack user each of a workspace's connections posts as.
     *
     * The picker for "whose messages does this watch" is what asks. A bot token
     * is a Slack user, so the row a person chooses is really a user id — and two
     * connections holding the same token are one user twice over, which the
     * answer says out loud rather than leaving a list of two rows to imply a
     * distinction Slack does not make.
     *
     * It never fails and never gates: a connection whose token could not be
     * asked comes back `UNCHECKED` with one line saying why, because a picker
     * that empties itself in silence reads as a broken installation. Saving is
     * where a choice is refused, and it is refused for a reason this has already
     * shown.
     */
    @QueryMapping
    fun slackBotUsers(@Argument workspaceId: Long): List<SlackBotUserView> {
        requireWorkspaceAccess(workspaceId)
        val slack = connections.workspaceConnections(workspaceId).filter { it.type == ConnectionType.SLACK }
        return botUsers.identify(slack.map { it.id }).map(::describe)
    }

    private fun describe(bot: SlackBotUser) = SlackBotUserView(
        connectionId = bot.connectionId,
        name = bot.name,
        outcome = bot.outcome,
        message = bot.message,
        userId = bot.userId,
        handle = bot.handle,
        receives = bot.receives,
    )

    /**
     * A trigger asks its own workspace's question and no other's — the same rule
     * a workflow node follows when it points at a definition.
     */
    private fun requireConditionInWorkspace(workspaceId: Long, conditionId: Long) {
        val condition = conditions.findByIdOrNull(conditionId) ?: throw ConditionNotInCatalogueException(conditionId)
        if (condition.workspaceId != workspaceId) throw ConditionNotInCatalogueException(conditionId)
    }

    /**
     * The payload is read field by field into what the run is handed, so it has
     * to be an object: an array or a bare number has no fields to read.
     */
    private fun requireJsonObject(payload: String) {
        val parsed = try {
            mapper.readTree(payload)
        } catch (failure: JacksonException) {
            throw TriggerPayloadInvalidException()
        }
        if (!parsed.isObject) throw TriggerPayloadInvalidException()
    }

    private fun validate(trigger: WorkflowTrigger) {
        /*
         * A draft belonging to one node, which is allowed to be unfinished.
         *
         * It is filled in on the node that uses it, a field at a time, and the
         * panel writes as it is typed - so the moment between choosing a kind
         * and choosing what it needs is where somebody is, not a mistake.
         * A run reaching an unfinished one is prevented at publish instead;
         * see `GraphValidator`.
         *
         * Unfinished is not the same as borrowed, though: what it does name
         * has to be this workspace's, draft or not - another workspace's
         * function asked to let a webhook through runs with that workspace's
         * variables. Issue #588.
         */
        trigger.connectionId?.let { id ->
            if (connections.workspaceConnection(id)?.workspaceId != trigger.workspaceId) {
                throw TriggerConnectionRequiredException()
            }
        }
        trigger.authFunctionId?.let { id ->
            val function = functions.findByIdOrNull(id)
            if (function != null && function.scope == io.mszymanski.orknux.server.action.FunctionScope.WORKSPACE &&
                function.workspaceId != trigger.workspaceId
            ) {
                throw TriggerWebhookAuthFunctionRequiredException()
            }
        }
        trigger.objectId?.let { id ->
            val shape = objects.findByIdOrNull(id)
            if (shape?.workspaceId != null && shape.workspaceId != trigger.workspaceId) {
                throw TriggerWebhookShapeRequiredException()
            }
        }
        if (trigger.workflowId != null) return

        when (trigger.type) {
            TriggerType.INCOMING_CONNECTION -> {
                val connectionId = trigger.connectionId ?: throw TriggerConnectionRequiredException()
                if (trigger.action == null) throw TriggerConnectionRequiredException()
                // The connection has to be one this workspace holds.
                val connection = connections.workspaceConnection(connectionId)
                if (connection == null || connection.workspaceId != trigger.workspaceId) {
                    throw TriggerConnectionRequiredException()
                }
                requireWatchable(trigger)
            }

            TriggerType.SCHEDULED -> {
                val cron = trigger.cron ?: throw TriggerScheduleRequiredException()
                // Five fields is what a person writes; Spring wants six.
                if (!CronExpression.isValidExpression(sixField(cron))) throw TriggerScheduleInvalidException(cron)
                /*
                 * And that it will ever come round.
                 *
                 * Parsing is not the same question. "0 0 30 2 *" is a valid
                 * expression naming the thirtieth of February, and Spring
                 * answers `next` with null rather than refusing it - so it
                 * saved, sat in the list looking like a schedule, and was
                 * silently skipped on every tick for ever. A trigger that can
                 * never fire is worth refusing at the door, because the only
                 * other way to find out is to wait.
                 */
                val ever = runCatching {
                    CronExpression.parse(sixField(cron)).next(OffsetDateTime.now())
                }.getOrNull()
                if (ever == null) throw TriggerScheduleUnreachableException(cron)
            }

            TriggerType.WEBHOOK -> {
                val path = trigger.webhookPath ?: throw TriggerWebhookPathRequiredException()
                val held = triggers.findByWebhookPath(path)
                if (held != null && held.id != trigger.id) throw TriggerWebhookPathTakenException(path)

                // The shape is what a request is answered by; without one there
                // is nothing to check a request against and nothing to promise a
                // workflow about what it will be handed.
                val objectId = trigger.objectId ?: throw TriggerWebhookShapeRequiredException()
                val shape = objects.findByIdOrNull(objectId)
                if (shape == null || shape.workspaceId != trigger.workspaceId) {
                    throw TriggerWebhookShapeRequiredException()
                }

                /*
                 * A function that authenticates has to be able to say no.
                 *
                 * Checked when it is chosen rather than when a request arrives:
                 * a webhook whose gatekeeper answers an object would refuse
                 * everything, at the one moment nobody is watching.
                 *
                 * A plugin's functions belong to no workspace and are offered in
                 * every one — the picker lists them and the endpoint asks them in
                 * the plugin's own sandbox — so only another workspace's own
                 * function is out of reach. Refusing a plugin's as "no function
                 * chosen" described a box somebody had already filled in.
                 */
                if (trigger.authType == WebhookAuthType.FUNCTION) {
                    val functionId = trigger.authFunctionId ?: throw TriggerWebhookAuthFunctionRequiredException()
                    val function = functions.findByIdOrNull(functionId)
                        ?: throw TriggerWebhookAuthFunctionRequiredException()
                    if (function.scope != FunctionScope.PLUGIN && function.workspaceId != trigger.workspaceId) {
                        throw TriggerWebhookAuthFunctionRequiredException()
                    }
                    if (function.returnType != ValueType.BOOLEAN) {
                        throw TriggerWebhookAuthFunctionNotBooleanException(function.name)
                    }
                }
            }
        }
    }

    /**
     * The path a webhook answers on, as it is stored: no leading slash, no host.
     *
     * This installation is what answers, so what is being named is somewhere on
     * it. Anything that looks like it points elsewhere — a scheme, a host, a
     * `..` climbing out of the endpoint — is refused rather than quietly
     * rewritten, because somebody typing a URL of their own means something by
     * it and should be told it is not what this asks for.
     */
    private fun pathOf(said: String): String {
        val path = said.trim().removePrefix("/")
        if (path.isEmpty()) throw TriggerWebhookPathRequiredException()
        if (path.contains("://") || path.startsWith("/") || path.contains("..")) {
            throw TriggerWebhookPathInvalidException(said.trim())
        }
        if (!WEBHOOK_PATH.matches(path)) throw TriggerWebhookPathInvalidException(said.trim())
        return path
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    /**
     * How the log says a trigger was switched, whichever switch did it.
     *
     * There are three: the toggle in the list, the one on the trigger's own page
     * and the one in the dialog that makes it. Written once so the three cannot
     * word it differently, which is what would make the log unsearchable.
     */
    private fun enabledMessage(name: String, enabled: Boolean) =
        "Trigger $name ${if (enabled) "enabled" else "disabled"}"

    private companion object {
        /** Letters, digits and the punctuation a URL carries plainly. */
        val WEBHOOK_PATH = Regex("[A-Za-z0-9][A-Za-z0-9._~-]*(/[A-Za-z0-9._~-]+)*")
    }
}

/** "MENTION" -> "Mention", "ISSUE_CREATED" -> "Issue Created", as the list shows it. */
fun actionLabel(action: TriggerAction): String = action.name
    .split('_')
    .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }

/** Cron the way people write it is five fields; Spring's parser wants seconds too. */
fun sixField(cron: String): String {
    val fields = cron.trim().split(Regex("\\s+"))
    return if (fields.size == 5) "0 $cron".trim() else cron.trim()
}

data class CreateTriggerInput(
    val workspaceId: Long,
    /** The workflow this belongs to, where it is that workflow's own; see V258. */
    val workflowId: Long? = null,
    val name: String,
    val type: TriggerType,
    val connectionId: Long? = null,
    val action: TriggerAction? = null,
    /**
     * Whose messages a `REPLY` watches for replies to, and required on one.
     *
     * Not the connection it listens on: that one is the socket, these are the
     * bot tokens whose own messages count as a thread's parent.
     */
    val watchedConnectionIds: List<Long>? = null,
    val cron: String? = null,
    val timezone: String? = null,
    /** JSON object handed to the runs this starts. */
    val payload: String? = null,
    /** Asked of the event before anything starts; null fires on everything. */
    val conditionId: Long? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
    /** Where a webhook answers, relative to this installation: `build/finished`. */
    val webhookPath: String? = null,
    /** The shape a webhook's request has to have; anything else is answered 404. */
    val objectId: Long? = null,
    /** How a webhook decides whether its caller may start anything. */
    val authType: WebhookAuthType = WebhookAuthType.NONE,
    /** The function that answers that, when one does. */
    val authFunctionId: Long? = null,
    /**
     * Whether it fires at all.
     *
     * A trigger is made switched on unless somebody says otherwise, which is
     * what the form defaults to. Saying otherwise is worth being able to do: a
     * webhook is often defined before the caller exists, and a definition that
     * answers nothing until it is wanted is better than one somebody has to
     * remember to turn off afterwards.
     */
    val enabled: Boolean = true,
)

data class UpdateTriggerInput(
    val name: String,
    /**
     * What kind of trigger it is; null leaves it alone, which is what every
     * caller but a node's own panel sends.
     *
     * Honoured only for a definition a workflow owns. See [updateTrigger].
     */
    val type: TriggerType? = null,
    val connectionId: Long? = null,
    val action: TriggerAction? = null,
    /** Whose messages a `REPLY` watches; null watches nobody, which only a reply minds. */
    val watchedConnectionIds: List<Long>? = null,
    val cron: String? = null,
    val timezone: String? = null,
    val payload: String? = null,
    /** Null takes the condition off, so the form can stop asking. */
    val conditionId: Long? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
    /** Where a webhook answers, relative to this installation. */
    val webhookPath: String? = null,
    /** The shape a webhook's request has to have; null takes the contract off. */
    val objectId: Long? = null,
    /** How a webhook decides whether its caller may start anything. */
    val authType: WebhookAuthType? = null,
    /** The function that answers that; null when nothing does. */
    val authFunctionId: Long? = null,
    /**
     * Whether it fires at all; null leaves it as it stands.
     *
     * Not treated the way the condition and the icon are, where null means
     * "take it off": there is no off to mean here, and a caller that saves a
     * trigger without mentioning the switch must not be turning it on.
     */
    val enabled: Boolean? = null,
)

data class TriggerView(
    val id: Long,
    val workspaceId: Long,
    /** The workflow this belongs to, or null for one the workspace shares. */
    val workflowId: Long?,
    val name: String,
    val type: TriggerType,
    val connectionId: Long?,
    val action: TriggerAction?,
    /** Whose messages a `REPLY` watches for replies to; empty on every other event. */
    val watchedConnectionIds: List<Long>,
    val cron: String?,
    val timezone: String?,
    /** JSON object handed to the runs this starts; null when it says nothing. */
    val payload: String?,
    val conditionId: Long?,
    /** The most recent thing this trigger did; null when it has never been asked. */
    val lastFiring: TriggerFiringView?,
    /** What the list shows: the condition's name, or nothing when it asks none. */
    val conditionName: String?,
    val enabled: Boolean,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String?,
    /** Where a webhook answers, relative to this installation; null on other kinds. */
    val webhookPath: String?,
    /** The shape a webhook's request has to have. */
    val objectId: Long?,
    /** What that shape is called, for the list. */
    val objectName: String?,
    /** How a webhook decides whether its caller may start anything. */
    val authType: WebhookAuthType,
    val authFunctionId: Long?,
    /** What that function is called, for the form. */
    val authFunctionName: String?,
    /** Where the event comes from, ready to show: the connection, or "Cron". */
    val source: String,
    /** What the event is, ready to show: "Mention", or the cron expression. */
    val event: String,
)

/**
 * Which Slack user one connection posts as, for the picker that chooses whose
 * messages a reply trigger watches.
 *
 * @property message one line, ready to show, and empty when there is nothing
 *   worth saying. Never carries a credential.
 * @property receives whether the bot token carries a scope a channel's messages
 *   arrive under, and null where Slack did not say — a response that carried no
 *   scope header has reported no absence, and drawing one would send somebody to
 *   fix a token that is fine.
 */
data class SlackBotUserView(
    val connectionId: Long,
    val name: String,
    val outcome: SlackBotUserOutcome,
    val message: String,
    val userId: String?,
    val handle: String?,
    val receives: Boolean?,
)

data class TriggerFiringPage(
    val content: List<TriggerFiringView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<TriggerFiring>, describe: (TriggerFiring) -> TriggerFiringView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

data class TriggerFiringView(
    val id: Long,
    /** ISO-8601, the way every other timestamp crosses this API. */
    val at: String,
    val outcome: FiringOutcome,
    val detail: String?,
    val runsStarted: Int,
    /** Which trigger did it; null where the answer is already known from context. */
    val triggerId: Long? = null,
    val triggerName: String? = null,
)

data class TriggerPage(
    val content: List<TriggerView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowTrigger>, describe: (WorkflowTrigger) -> TriggerView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

/** One entry of the Source sieve: `cron`, `webhook`, or `connection:<id>`, with what the column shows for it. */
data class TriggerSourceView(val value: String, val label: String)

/** What a Source value means: one kind of trigger, or one connection. */
data class TriggerSource(val type: TriggerType?, val connectionId: Long?) {
    companion object {
        const val CRON = "cron"
        const val WEBHOOK = "webhook"
        const val CONNECTION = "connection:"

        /** Null for no sieve; a value nothing matches sieves to nothing rather than being refused. */
        fun parse(value: String?): TriggerSource? {
            val held = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                held == CRON -> TriggerSource(TriggerType.SCHEDULED, null)
                held == WEBHOOK -> TriggerSource(TriggerType.WEBHOOK, null)
                held.startsWith(CONNECTION) -> TriggerSource(TriggerType.INCOMING_CONNECTION, held.removePrefix(CONNECTION).toLongOrNull() ?: -1)
                else -> TriggerSource(null, -1)
            }
        }
    }
}
