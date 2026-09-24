package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.condition.ConditionEvaluator
import io.mszymanski.orknux.server.condition.ConditionNotDecidableException
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workflow.AppWorkflowGraphSource
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import io.mszymanski.orknux.workflow.execution.WorkflowNotPublishedException
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Starts what a trigger definition is wired to.
 *
 * A definition is a catalogue entry and starts nothing by itself; what runs is
 * every workflow with a trigger node instancing it. Both ways a trigger fires —
 * an event arriving on a connection, and the clock — end up here, so they start
 * runs the same way and are audited the same way.
 *
 * Nobody is signed in when a trigger fires, so a run is attributed to the
 * trigger rather than to a person. That is also why the workspace's switch on a
 * workflow is honoured here: this is the path nobody is watching, and a
 * workflow somebody has switched off is one they have said should not start
 * without them.
 */
@Service
class TriggerRunner(
    private val instances: WorkflowNodeRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val runs: ExecutionService,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
    private val conditions: WorkflowConditionRepository,
    private val evaluator: ConditionEvaluator,
    private val firings: TriggerFiringRepository,
    private val graphs: AppWorkflowGraphSource,
) {

    /**
     * @param context what the run is handed: the message for an incoming event,
     *   the schedule for a scheduled one.
     * @return how many runs were started.
     */
    /**
     * The same firing, for something that arrived as JSON rather than as a
     * handful of strings.
     *
     * A webhook's body is the thing the workflow was written against, so it is
     * carried in with its shape intact: a nested object stays an object, and a
     * reference to `order.total` finds a number rather than the text of one.
     *
     * @param delivery what arrived besides the body, when anything did. It goes
     *   in under `webhook`, between the trigger's own payload and the body: it
     *   describes this call, which a payload written once cannot, and the body is
     *   still what the workflow was written against, so a sender with a field of
     *   that name keeps it.
     */
    fun fire(trigger: WorkflowTrigger, body: JsonNode, delivery: WebhookDelivery? = null): Int = fire(trigger) { input ->
        delivery?.let { input.set(WEBHOOK, it.asJson(mapper)) }
        if (body.isObject) {
            body.properties().forEach { (name, value) -> input.set(name, value) }
        }
    }

    fun fire(trigger: WorkflowTrigger, context: Map<String, Any?>): Int = fire(trigger) { input ->
        // What arrived describes this firing, so it wins. A list - the
        // commands in a message - is handed on as one, so a reference reads a
        // list and not the text of one. Issue #381.
        context.forEach { (name, value) ->
            when (value) {
                null -> Unit
                is Collection<*> -> input.putArray(name).also { array -> value.forEach { array.add(it.toString()) } }
                else -> input.put(name, value.toString())
            }
        }
    }

    /** What both ways of firing have in common, once the input is decided. */
    private fun fire(trigger: WorkflowTrigger, fill: (ObjectNode) -> Unit): Int {
        val triggerId = requireNotNull(trigger.id)
        val assigned = instances.findByTriggerId(triggerId)
            .map { it.workflowId }
            .distinct()
            .mapNotNull { assignments.findByWorkspaceIdAndWorkflowId(trigger.workspaceId, it) }

        if (assigned.isEmpty()) {
            log.info("Trigger {} (#{}) fired, but no workflow instances it", trigger.name, triggerId)
            record(trigger, FiringOutcome.NO_INSTANCE, "No workflow has a trigger node pointing at this definition")
            return 0
        }

        /*
         * A workflow the workspace has switched off is not started by anything
         * that starts by itself, which is what the switch is for.
         *
         * Asked before the condition, and before the payload is assembled,
         * because a condition can call a function and there is nothing left for
         * its answer to decide: whichever way it went, nothing would run.
         */
        val (runnable, switchedOff) = assigned.partition { it.enabled }
        val off = switchedOff.map {
            Refused(
                workflowId = requireNotNull(it.workflow.id),
                reason = SWITCHED_OFF,
                said = "${it.workflow.name} is switched off in this workspace",
            )
        }
        if (runnable.isEmpty()) {
            log.info(
                "Trigger {} (#{}) fired at {} workflow(s), all switched off: {}",
                trigger.name,
                triggerId,
                assigned.size,
                off.joinToString(", ") { it.short },
            )
            record(trigger, FiringOutcome.WORKFLOW_DISABLED, off.joinToString("; ") { it.said })
            return 0
        }

        val payload = inputFor(trigger, fill)
        val verdict = admits(trigger, payload)
        if (verdict != null) {
            // Said out loud as well as written down. The row is on the trigger's
            // own page; the log is where somebody watching a message not arrive
            // is looking, and a condition quietly refusing it is the commonest
            // reason for the silence they are reading.
            log.info("Trigger {} (#{}) did not fire: {}", trigger.name, triggerId, verdict.detail)
            record(trigger, verdict.outcome, verdict.detail)
            return 0
        }

        // The ones that are off are refusals like any other, so a firing that
        // started two of three says which one it left alone and why.
        val refusals = off.toMutableList()
        val begun = runnable.filter { start(trigger, requireNotNull(it.workflow.id), payload, refusals) }
        val started = begun.map { requireNotNull(it.workflow.id) }

        /*
         * Read by a person, and still greppable.
         *
         * The names came out because a name is not an identity - two workflows
         * may share one, and a rename makes an old line describe something that
         * no longer exists - and the key=value line that replaced them was
         * worse in the other direction: nobody reads `outcome=partial
         * assigned=3` at a glance. So: a sentence, with the ids in it. The
         * counts are words, the workflows are numbers, and each refusal says
         * what it was in three or four of them.
         */
        log.info(
            "Trigger {} (#{}) started {} of {} workflow(s): {}",
            trigger.name,
            triggerId,
            started.size,
            assigned.size,
            (started.map { "#$it started" } + refusals.map { it.short }).joinToString(", "),
        )

        val said = buildList {
            if (started.isNotEmpty()) add(begun.joinToString(", ") { "${it.workflow.name} (#${it.workflow.id})" })
            addAll(refusals.map { it.said })
        }.joinToString("; ")

        if (started.size == assigned.size) {
            record(trigger, FiringOutcome.STARTED, "Started ${started.size} of ${assigned.size}: $said", started.size)
        } else {
            record(trigger, FiringOutcome.FAILED, "Started ${started.size} of ${assigned.size}: $said", started.size)
        }
        return started.size
    }

    /**
     * A firing that came to nothing, and why — or null when it should go ahead.
     */
    private data class Refusal(val outcome: FiringOutcome, val detail: String)

    /**
     * Whether this firing is one the trigger wanted.
     *
     * Asked before anything starts, which is the point of asking here at all: a
     * condition inside the workflow only decides after a run exists, has been
     * audited, and is sitting in the executions list looking like work.
     *
     * A condition that cannot be decided does not admit the event. The question
     * was asked because not everything arriving is wanted, and an event nobody
     * could evaluate is not evidence that it was — so it is refused, and said
     * out loud rather than dropped quietly.
     */
    private fun admits(trigger: WorkflowTrigger, payload: String): Refusal? {
        val conditionId = trigger.conditionId ?: return null
        val condition = conditions.findByIdOrNull(conditionId)
        if (condition == null) {
            log.warn("Trigger {} asks a condition that has been deleted; nothing started", trigger.name)
            return Refusal(FiringOutcome.UNDECIDED, "The condition this trigger asks has been deleted")
        }

        return try {
            if (evaluator.holds(condition, payload)) {
                null
            } else {
                log.debug("Trigger {} fired, but {} did not hold", trigger.name, condition.name)
                Refusal(FiringOutcome.CONDITION_DID_NOT_HOLD, "${condition.name} did not hold")
            }
        } catch (undecided: ConditionNotDecidableException) {
            log.warn("Trigger {} could not decide {}: {}", trigger.name, condition.name, undecided.message)
            Refusal(FiringOutcome.UNDECIDED, "${condition.name} could not be decided: ${undecided.message}")
        }
    }

    /**
     * One line in the trigger's log, written by something outside this class.
     *
     * The endpoint answers a machine, so a request it turned down leaves no
     * trace anywhere a person looks. This is that trace.
     */
    fun note(trigger: WorkflowTrigger, outcome: FiringOutcome, detail: String) = record(trigger, outcome, detail)

    /**
     * One line in the trigger's log.
     *
     * Never allowed to be the reason a firing fails: a run that started matters
     * more than the note saying it did.
     */
    private fun record(trigger: WorkflowTrigger, outcome: FiringOutcome, detail: String, started: Int = 0) {
        runCatching {
            firings.save(
                TriggerFiring(
                    triggerId = requireNotNull(trigger.id),
                    workspaceId = trigger.workspaceId,
                    outcome = outcome,
                    detail = detail,
                    runsStarted = started,
                ),
            )
        }.onFailure { log.warn("Could not record what trigger {} did", trigger.name, it) }
    }

    /**
     * What the run is handed: the trigger's own payload, with what happened on
     * top of it. The payload keeps its shape — a nested object stays an object —
     * so a function can be handed something to work on rather than a flat set of
     * strings about the trigger.
     */
    private fun inputFor(trigger: WorkflowTrigger, fill: (ObjectNode) -> Unit): String {
        val input = trigger.payload
            ?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.takeIf { it.isObject }
            ?.let { (it as ObjectNode).deepCopy() }
            ?: mapper.createObjectNode()

        fill(input)
        return mapper.writeValueAsString(input)
    }

    /**
     * Whether this message has already started this workflow, and remembering
     * it where it has not.
     *
     * Swept as it goes, the way the Slack listener's own guard is: the map
     * holds a few minutes of one installation's traffic, and walking it when
     * it has grown is cheaper than a thread that wakes all night to find
     * nothing.
     */
    private fun firstTime(workflowId: Long, connectionId: Long?, payload: String): Boolean {
        val ts = runCatching { mapper.readTree(payload).path("ts").takeIf { it.isTextual }?.stringValue() }
            .getOrNull()
            // No ts is no identity: a webhook, a schedule, or a Slack event
            // without one. Started rather than dropped on a guess.
            ?: return true

        val key = "$workflowId:$connectionId:$ts"
        val now = System.currentTimeMillis()
        if (started.size > MOST_REMEMBERED) {
            started.entries.removeIf { now - it.value > REMEMBER_FOR_MILLIS }
        }
        val before = started.put(key, now)
        return before == null || now - before >= REMEMBER_FOR_MILLIS
    }

    private fun start(
        trigger: WorkflowTrigger,
        workflowId: Long,
        payload: String,
        refusals: MutableList<Refused>,
    ): Boolean {
        /*
         * Asked before starting, rather than found out by catching.
         *
         * A workflow somebody is still drawing is an ordinary thing for a
         * trigger to point at - an import arrives as a draft - and the ordinary
         * case should raise nothing. It used to raise something expensive: the
         * exception came out of a transactional method, which marked the
         * caller's transaction rollback-only on its way, and no amount of
         * catching down here put that back. The catch below stays, for the race
         * where a graph is published between this question and the answer being
         * used, but it is no longer how a draft is normally discovered.
         */
        if (!graphs.published(workflowId)) {
            return unpublished(trigger, workflowId, WorkflowNotPublishedException(workflowId).message, refusals)
        }

        /*
         * One message starts one workflow once.
         *
         * Slack delivers a single thing somebody did as more than one event: a
         * file uploaded with a mention in the comment arrives as a message
         * *and* as an app_mention, and a message in a thread is both a message
         * and a reply. Each of those is a real event and a workflow waiting on
         * either is entitled to it - but a workflow waiting on two of them ran
         * twice for one upload, which is nobody's idea of what a trigger does.
         *
         * Keyed by the message rather than by the event, and by the workflow
         * rather than the trigger: two workflows watching the same channel
         * both still run, which is two people's separate decisions. The
         * listener's own guard is a different question and stays - that one is
         * about Slack sending the same event twice, this one is about one
         * message wearing two names.
         *
         * Only where the payload carries a `ts`, which is Slack's identity for
         * a message. A schedule and a webhook have no such thing and are not
         * touched.
         */
        if (!firstTime(workflowId, trigger.connectionId, payload)) {
            log.info(
                "Workflow #{} was already started by this message; {} did not start it again",
                workflowId,
                trigger.name,
            )
            return false
        }

        return try {
            val started = runs.startExecution(
                StartExecutionInput(
                    workspaceId = trigger.workspaceId,
                    workflowId = workflowId,
                    trigger = when (trigger.type) {
                        TriggerType.SCHEDULED -> ExecutionTrigger.SCHEDULE
                        TriggerType.INCOMING_CONNECTION, TriggerType.WEBHOOK -> ExecutionTrigger.WEBHOOK
                    },
                    payload = payload,
                    // Which trigger this is. A workflow may be drawn with two,
                    // and only the half belonging to this one should run.
                    firedTriggerId = trigger.id,
                ),
            )
            auditRecorder.recordAutomated(
                workspaceId = trigger.workspaceId,
                category = WorkspaceAuditCategory.WORKFLOW,
                message = "Workflow ${started.workflowName} run started by trigger ${trigger.name}",
                actor = "trigger:${trigger.name}",
            )
            true
        } catch (notPublished: WorkflowNotPublishedException) {
            unpublished(trigger, workflowId, notPublished.message, refusals)
        } catch (failure: Exception) {
            // One workflow failing is no reason for the others to miss the
            // trigger. The stack trace stays: this is the one refusal that is
            // not an ordinary state of the graph.
            log.error("Trigger {} (#{}) could not start workflow #{}", trigger.name, trigger.id, workflowId, failure)
            refusals += Refused(workflowId, FAILED, failure.message ?: failure::class.simpleName.orEmpty())
            false
        }
    }

    /**
     * Said the same way whichever of the two found it, so what is written into
     * the firing log does not depend on which one did.
     *
     * Not an error worth a stack trace: a graph that has never been published is
     * a graph somebody is still drawing, and a trigger firing at one is a thing
     * to be told about plainly.
     */
    private fun unpublished(
        trigger: WorkflowTrigger,
        workflowId: Long,
        detail: String?,
        refusals: MutableList<Refused>,
    ): Boolean {
        refusals += Refused(workflowId, UNPUBLISHED, detail ?: "a workflow that has never been published")
        return false
    }

    /**
     * One workflow this firing did not start, and why.
     *
     * Two audiences, so two fields. [reason] is the short phrase the log line
     * carries beside the id - "not published" - and [said] is the fuller
     * sentence on the firing record, where a person reads it with the workflow
     * in front of them. Neither is a name on its own: a name is not an
     * identity, and an old line naming a workflow that has since been renamed
     * describes something that no longer exists.
     */
    private data class Refused(val workflowId: Long, val reason: String, val said: String) {

        /** `#637 not published` — what the log line lists it as. */
        val short: String get() = "#$workflowId $reason"
    }

    /**
     * Which messages have started which workflows, and when.
     *
     * In memory and per instance, like the Slack listener's guard: what it
     * protects against is one delivery arriving as several events, and those
     * all reach the same instance within seconds of each other. A second
     * instance running the same workflow is a different conversation.
     */
    private val started = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private companion object {
        /** How long one message is remembered as having started a workflow. */
        const val REMEMBER_FOR_MILLIS = 10 * 60 * 1000L

        /** When the map is bigger than this, the stale half is swept on the way past. */
        const val MOST_REMEMBERED = 5_000

        val log = LoggerFactory.getLogger(TriggerRunner::class.java)

        /*
         * What a refusal is called in the log: three or four words, the same
         * three or four every time, so the line reads as a sentence and still
         * greps as a token.
         */
        const val SWITCHED_OFF = "switched off"
        const val UNPUBLISHED = "not published"
        const val FAILED = "failed to start"

        /** Where a webhook's own description of the call sits in the run's input. */
        const val WEBHOOK = "webhook"
    }
}
