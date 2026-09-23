package io.mszymanski.orknux.server.condition

import io.mszymanski.orknux.workflow.script.ScriptOrigin
import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.plugin.PluginParameters
import io.mszymanski.orknux.server.plugin.PluginCapabilities
import io.mszymanski.orknux.server.plugin.PluginPermissions
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.plugin.PluginSources
import io.mszymanski.orknux.server.variable.VariableArguments
import io.mszymanski.orknux.server.action.ScriptImports
import io.mszymanski.orknux.server.action.ScriptImportsResult
import io.mszymanski.orknux.server.action.ScriptTimeouts
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import io.mszymanski.orknux.server.workflow.MappingMode
import java.time.Clock
import java.time.OffsetDateTime
import java.time.LocalTime

/**
 * Decides one of a workspace's conditions against what a run is carrying.
 *
 * A condition is data rather than code — a property, a check and what to check
 * against — so this is where that data becomes an answer. It is deliberately not
 * the script sandbox: a condition the UI built should not be able to run
 * anything, and a wait that asks the same question every thirty seconds should
 * not compile a script each time.
 */
@Service
class ConditionEvaluator(
    private val conditions: WorkflowConditionRepository,
    private val functions: WorkflowFunctionRepository,
    private val scripts: ScriptRunner,
    private val pluginRunner: PluginRunner,
    private val scriptImports: ScriptImports,
    private val plugins: PluginRepository,
    private val pluginParameters: PluginParameters,
    private val pluginPermissions: PluginPermissions,
    private val pluginCapabilities: PluginCapabilities,
    private val pluginSources: PluginSources,
    private val externals: VariableArguments,
    private val timeouts: ScriptTimeouts,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /**
     * @param input the JSON the previous node produced, or the run's own input.
     * @throws ConditionNotDecidableException when the input does not carry what
     *   the condition asks about; a wait treats that as "not yet", and a
     *   condition node as a reason to stop.
     */
    fun holds(condition: WorkflowCondition, input: String?): Boolean =
        decide(condition, parse(input), raw = input, depth = 0, passed = emptyMap(), trigger = null)

    /**
     * The same question, asked by a node that says what to pass.
     *
     * A condition is a definition and a node is one use of it, and what to hand
     * the function is the node's business: two nodes asking "is this the first
     * reply" of different threads are the ordinary case, and arguments kept on
     * the definition made them one shared answer. So the node's own bindings
     * win where it has them.
     *
     * [passed] is empty for a node that fills nothing in, and then this behaves
     * exactly as the call above: the condition's own arguments, or the whole of
     * what the run carries. Nothing written before this changes meaning.
     *
     * @param passed what the node fills each declared parameter in with.
     * @param trigger the event that started the run, so a reference may read it.
     */
    fun holds(
        condition: WorkflowCondition,
        input: String?,
        passed: Map<String, NodeBinding>,
        trigger: String?,
        /** Which run this belongs to, for anything the function logs. */
        origin: ScriptOrigin = ScriptOrigin(),
    ): Boolean = decide(condition, parse(input), raw = input, depth = 0, passed = passed, trigger = trigger, origin = origin)

    private fun decide(
        condition: WorkflowCondition,
        input: JsonNode?,
        raw: String?,
        depth: Int,
        passed: Map<String, NodeBinding>,
        trigger: String?,
        origin: ScriptOrigin = ScriptOrigin(),
    ): Boolean {
        if (depth > MAX_DEPTH) throw ConditionNotDecidableException("${condition.name} nests too deeply")

        val answer = when (condition.type) {
            /*
             * The members decide for themselves. What a node passes is named
             * for the parameters of *this* condition's function, and a member
             * is a different condition with a different function - so handing
             * them down would fill one function's parameters with another's
             * names. They fall back to their own arguments, which is what they
             * had before.
             */
            ConditionType.ANY_OF ->
                members(condition).any { decide(it, input, raw, depth + 1, emptyMap(), trigger, origin) }

            ConditionType.ALL_OF ->
                members(condition).all { decide(it, input, raw, depth + 1, emptyMap(), trigger, origin) }

            ConditionType.FUNCTION -> ask(condition, raw, passed, trigger, origin)
            ConditionType.VALUE -> testValue(condition, raw, passed, trigger)
            else -> test(condition, input)
        }
        return answer != condition.negate
    }

    /**
     * One field of what the run is carrying, as JSON.
     *
     * `null` for a field that is not there, which is the same answer a node's
     * reference gives: a condition asking about a thread on a message that has
     * none should decide, not fail. A dotted name walks into nested objects, as
     * everywhere else a reference is written.
     */
    private fun reference(path: String, input: String?): String {
        val carried = runCatching { mapper.readTree(input ?: "null") }.getOrNull() ?: return "null"
        val found = path.split('.').fold<String, JsonNode?>(carried) { held, step ->
            if (held == null || !held.isObject) null else held.get(step)
        }
        return found?.toString() ?: "null"
    }

    /**
     * One of the node's bindings, as the JSON to hand the function.
     *
     * A written value is that text, as a string. A reference reads the field it
     * names out of what the run carries, or out of the event that started it
     * where the name begins `trigger.` - and it keeps the type it finds, so a
     * number arrives as a number. A field that is not there is `null`, the same
     * answer `reference` gives, because a condition asking about a thread on a
     * message that has none should decide rather than fail.
     */
    private fun jsonFor(binding: NodeBinding, input: String?, trigger: String?): String {
        if (!binding.reference) return mapper.writeValueAsString(binding.expression)

        val steps = binding.expression.trim().split('.').filter { it.isNotEmpty() }
        val fromTrigger = steps.firstOrNull() == TRIGGER
        val source = if (fromTrigger) trigger else input
        val path = if (fromTrigger) steps.drop(1) else steps

        val carried = runCatching { mapper.readTree(source ?: "null") }.getOrNull() ?: return "null"
        val found = path.fold<String, JsonNode?>(carried) { held, step ->
            if (held == null || !held.isObject) null else held.get(step)
        }
        return found?.toString() ?: "null"
    }

    /**
     * Runs the workspace's function in the sandbox and takes its answer.
     *
     * The function is handed what the run is carrying, and has to say true or
     * false: a condition that answered `"maybe"` is a condition nobody can act
     * on, so anything else is refused rather than guessed at.
     */
    private fun ask(
        condition: WorkflowCondition,
        input: String?,
        passed: Map<String, NodeBinding>,
        trigger: String?,
        origin: ScriptOrigin,
    ): Boolean {
        val function = condition.functionId?.let { functions.findByIdOrNull(it) }
            ?: throw ConditionNotDecidableException("${condition.name} names a function that has been deleted")

        /*
         * What the condition says to pass, then the workspace's own values.
         *
         * A condition with nothing written on it passes what the run is
         * carrying, as one argument — which is what every condition did before
         * arguments existed, and is why one written last week goes on meaning
         * what it meant. A condition that names its arguments passes those
         * instead: a reference reads a field out of what the run carries, so
         * "the thread this arrived on" and "the connection it came from" can be
         * handed in rather than dug out of the payload by the function.
         *
         * The workspace's values come last either way, because that is where a
         * function declares them and where its declaration expects them.
         */
        /*
         * The node's, in the order the function declares - a map says which
         * parameter, and only the declaration says which position. A parameter
         * the node leaves out is passed as null rather than shifting the ones
         * after it along.
         */
        val fromNode = if (passed.isEmpty()) {
            emptyList()
        } else {
            function.params.map { declared ->
                val binding = passed[declared.name] ?: return@map "null"
                jsonFor(binding, input, trigger)
            }
        }

        val written = fromNode.ifEmpty {
            condition.arguments.map { argument ->
                when (argument.mode) {
                    MappingMode.VALUE -> mapper.writeValueAsString(argument.expression)
                    MappingMode.REFERENCE -> reference(argument.expression, input)
                }
            }
        }
        /*
         * The workspace's values have a position: right after the parameters the
         * function declares. The legacy whole-payload call and a hand-written
         * argument list can both be shorter or longer than the declaration, and
         * an external that slid into a declared parameter's place was read as
         * that parameter — so where grants exist, the list is padded or trimmed
         * to the declared count first, exactly as the sandbox does for an
         * imported function. Where none exist, whatever was written is passed
         * untouched, which is what every condition did before grants existed.
         */
        val said = written.ifEmpty { listOf(input ?: "null") }
        val granted = externals.of(function)
        val arguments = if (granted.isEmpty()) {
            said
        } else {
            val squared = said + List((function.params.size - said.size).coerceAtLeast(0)) { "null" }
            squared.take(function.params.size) + granted
        }
        /*
         * A plugin's function is not this workspace's JavaScript — its source
         * column holds a note saying where the implementation lives — so it is
         * asked of the plugin, in the plugin's own sandbox, the same way an
         * action calls one. Running the note as a script would answer nothing
         * and read as a condition nobody can decide.
         */
        val call = if (function.scope == FunctionScope.PLUGIN && function.editedAt == null) {
            askPlugin(condition, function, arguments)
        } else {
            when (val resolved = scriptImports.resolve(function.imports, function.libraries)) {
                is ScriptImportsResult.Broken ->
                    throw ConditionNotDecidableException("${function.name} ${resolved.reason}")

                is ScriptImportsResult.Resolved -> scripts.call(
                    function.source,
                    function.name,
                    arguments,
                    contextFor(condition),
                    resolved.modules,
                    resolved.imports,
                    on = condition.workspaceId,
                    origin = origin.copy(functionId = function.id),
                    timeoutMillis = timeouts.forFunction(function.timeoutSeconds, condition.workspaceId),
                )
            }
        }
        return when (val result = call) {
            is ScriptResult.Returned -> when (result.json) {
                "true" -> true
                "false" -> false
                else -> throw ConditionNotDecidableException(
                    "${function.name} answered ${result.json ?: "nothing"}, which is not true or false",
                )
            }

            is ScriptResult.Failed -> throw ConditionNotDecidableException("${function.name} ${result.reason}")
        }
    }

    /**
     * Asks a function one of the plugins declared.
     *
     * Unloaded, or configured with something still missing, is a question this
     * workspace cannot answer rather than a false: a wait would otherwise sail
     * past on "no" and a branch take the wrong side, both without saying why.
     */
    private fun askPlugin(
        condition: WorkflowCondition,
        function: WorkflowFunction,
        arguments: List<String>,
    ): ScriptResult {
        val plugin = function.pluginId?.let { plugins.findByIdOrNull(it) }
            ?: throw ConditionNotDecidableException(
                "${function.name} is declared by a plugin that is no longer loaded",
            )

        val missing = pluginParameters.missingFor(plugin, condition.workspaceId)
        if (missing.isNotEmpty()) {
            throw ConditionNotDecidableException(
                "${function.name} cannot run: the ${plugin.key} plugin has not been told " +
                    missing.joinToString(", ") + ". Set it on this workspace's plugins page.",
            )
        }

        // The name the plugin gave it, not the prefixed one a workspace picks it
        // by: the prefix exists so two plugins can both declare `isTeammate`,
        // and the plugin never agreed to answer to it.
        val declared = function.name.removePrefix("${plugin.key}_")
        return pluginRunner.call(
            plugin.source,
            declared,
            arguments,
            pluginParameters.settingsFor(plugin, condition.workspaceId),
            // What a person accepted for this plugin, and nothing else. Read
            // per call from this plugin's row, so one plugin's agreement cannot
            // reach another's context.
            pluginPermissions.grantedTo(plugin),
            // And what it may ask the server to do, read the same way and
            // from its own row: a capability reaches outside the sandbox, so
            // it is granted apart from the permissions above.
            pluginCapabilities.grantedTo(plugin),
            on = condition.workspaceId,
            libraries = pluginSources.librariesOf(plugin),
            // The same bound the condition's own function runs under; see the
            // note in FunctionCaller.
            timeoutMillis = timeouts.forFunction(function.timeoutSeconds, condition.workspaceId),
        )
    }

    /**
     * What the asking function is told about where it is running.
     *
     * The clock is the reason this exists: the sandbox has no host and no I/O,
     * so a question about the date has to be answered from here — and a
     * condition should agree with the run about when "now" is, the same way an
     * action's function does.
     */
    private fun contextFor(condition: WorkflowCondition): String = mapper.writeValueAsString(
        mapOf(
            "now" to OffsetDateTime.now(clock).toString(),
            "timestamp" to clock.millis(),
            "workspaceId" to condition.workspaceId,
            "condition" to condition.name,
        ),
    )

    private fun members(condition: WorkflowCondition): List<WorkflowCondition> = condition.members.map { id ->
        conditions.findByIdOrNull(id)
            ?: throw ConditionNotDecidableException("${condition.name} names a condition that has been deleted")
    }

    /**
     * A value from the run, checked against the list. Issue #378.
     *
     * The subject comes from the node first - the binding it filled in, with
     * the same rules a function's parameters have, `trigger.` included - and
     * from the condition's own argument where the node passed nothing, so a
     * condition made on the settings page still works. What it resolves to is
     * read as text, the way a property is: a string is its characters, and
     * anything else is its JSON.
     */
    private fun testValue(
        condition: WorkflowCondition,
        raw: String?,
        passed: Map<String, NodeBinding>,
        trigger: String?,
    ): Boolean {
        val check = condition.check
            ?: throw ConditionNotDecidableException("${condition.name} has no check to make")
        val binding = passed[VALUE_SUBJECT]
            ?: condition.arguments.firstOrNull { it.name == VALUE_SUBJECT }?.let {
                NodeBinding(expression = it.expression, reference = it.mode == MappingMode.REFERENCE)
            }
            ?: throw ConditionNotDecidableException(
                "${condition.name} has not been told which value to check; pick one on the node",
            )
        val json = jsonFor(binding, raw, trigger)
        val found = runCatching { mapper.readTree(json) }.getOrNull()
        if (found == null || found.isNull) {
            throw ConditionNotDecidableException(
                "${condition.name} looks at ${binding.expression}, which this run is not carrying",
            )
        }
        val value = if (found.isTextual) found.stringValue() else found.toString()
        return when (check) {
            ConditionCheck.IN_LIST -> condition.values.any { it.equalsIgnoringCase(value) }
            ConditionCheck.EQUALS -> condition.values.firstOrNull()?.equalsIgnoringCase(value) == true
            ConditionCheck.CONTAINS -> condition.values.any { value.contains(it, ignoreCase = true) }
            ConditionCheck.MATCHES -> matches(condition, value)
            ConditionCheck.BETWEEN, ConditionCheck.WORKSPACEMATE ->
                throw ConditionNotDecidableException("${condition.name} cannot ${check.name.lowercase()} a value")
        }
    }

    private fun test(condition: WorkflowCondition, input: JsonNode?): Boolean {
        val check = condition.check
            ?: throw ConditionNotDecidableException("${condition.name} has no check to make")
        val property = condition.property
            ?: throw ConditionNotDecidableException("${condition.name} has nothing to check")

        if (check == ConditionCheck.BETWEEN) return withinTime(condition)

        val value = valueOf(property, input)
            ?: throw ConditionNotDecidableException(
                "${condition.name} asks about ${label(property)}, which this run is not carrying",
            )

        return when (check) {
            ConditionCheck.IN_LIST, ConditionCheck.WORKSPACEMATE -> condition.values.any { it.equalsIgnoringCase(value) }
            ConditionCheck.EQUALS -> condition.values.firstOrNull()?.equalsIgnoringCase(value) == true
            ConditionCheck.CONTAINS -> condition.values.any { value.contains(it, ignoreCase = true) }
            ConditionCheck.MATCHES -> matches(condition, value)

            ConditionCheck.BETWEEN -> false
        }
    }

    /**
     * Whether the workspace's pattern matches, with both sides bounded first.
     *
     * The pattern is the workspace's to write, but the value is whatever a
     * webhook body or a Slack message left in the run's input — so this is the
     * one check where a stranger has a say in how much work the host does, and
     * it is done here, on the thread carrying the run, outside the script
     * sandbox and the watchdog that bounds it. `runCatching` catches a pattern
     * that does not compile; it cannot catch one that is slow, because a regex
     * that backtracks is not throwing, it is working.
     *
     * Nothing has been shown to blow up on this runtime — the textbook
     * backtracking shapes all come back in about a millisecond here — so these
     * two are a bound rather than a fix for something observed, and both sit
     * far above anything real. [MAX_PATTERN] is the width of the column the
     * pattern is stored in, so it cannot refuse a pattern that could have been
     * saved; [MAX_VALUE] is past any message a chat will render, and the
     * properties this check is offered for — an author, a channel, a priority,
     * a status, a type, a message — are none of them long.
     */
    private fun matches(condition: WorkflowCondition, value: String): Boolean {
        val pattern = condition.values.firstOrNull() ?: return false
        if (pattern.length > MAX_PATTERN || value.length > MAX_VALUE) {
            // Neither is logged: one is a workspace's, the other a stranger's.
            log.warn(
                "{} did not run its pattern: {} pattern characters against {} value characters is past what is matched here",
                condition.name,
                pattern.length,
                value.length,
            )
            return false
        }
        return runCatching { Regex(pattern).containsMatchIn(value) }.getOrElse { false }
    }

    /** Whether the clock is between the two listed times, as HH:mm. */
    private fun withinTime(condition: WorkflowCondition): Boolean {
        val from = condition.values.getOrNull(0)?.let(::parseTime)
        val until = condition.values.getOrNull(1)?.let(::parseTime)
        if (from == null || until == null) {
            throw ConditionNotDecidableException("${condition.name} needs two times to sit between")
        }

        val now = LocalTime.now(clock)
        // A range that ends before it starts runs over midnight.
        return if (from <= until) now >= from && now <= until else now >= from || now <= until
    }

    private fun parseTime(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim()) }.getOrNull()

    /**
     * Where each property is read from. These are the field names a trigger
     * puts in the run's input — `SlackListener` writes `user`, `channel` and
     * `text` — with a couple of spellings allowed for what someone might map by
     * hand.
     */
    private fun valueOf(property: ConditionProperty, input: JsonNode?): String? {
        if (property == ConditionProperty.CURRENT_TIME) return LocalTime.now(clock).toString()
        val names = when (property) {
            ConditionProperty.MESSAGE_AUTHOR -> listOf("user", "author", "messageAuthor")
            ConditionProperty.MESSAGE_CHANNEL -> listOf("channel", "messageChannel")
            ConditionProperty.MESSAGE_TEXT -> listOf("text", "message", "messageText")
            ConditionProperty.ISSUE_PRIORITY -> listOf("priority", "issuePriority")
            ConditionProperty.ISSUE_STATUS -> listOf("status", "issueStatus")
            ConditionProperty.ISSUE_TYPE -> listOf("issueType", "type")
            ConditionProperty.CURRENT_TIME -> emptyList()
        }

        return names
            .firstNotNullOfOrNull { name -> input?.get(name)?.takeIf { !it.isNull } }
            ?.let { if (it.isTextual) it.stringValue() else it.toString() }
    }

    private fun parse(input: String?): JsonNode? = input
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { mapper.readTree(it) }.getOrNull() }

    private fun String.equalsIgnoringCase(other: String) = equals(other, ignoreCase = true)

    private companion object {
        val log = LoggerFactory.getLogger(ConditionEvaluator::class.java)

        /** A composite of composites is fine; a hundred of them is a mistake. */
        const val MAX_DEPTH = 10

        /** The name a reference starts with to read the event rather than the input. */
        const val TRIGGER = "trigger"

        /**
         * The longest pattern MATCHES will run. `workflow_condition_value.value`
         * is VARCHAR(500), so a pattern longer than this was never stored.
         */
        const val MAX_PATTERN = 500

        /**
         * The longest value MATCHES will run a pattern against. A Slack message
         * a block will render stops at 3,000 characters, and the other
         * properties a pattern is offered for are a word or two.
         */
        const val MAX_VALUE = 10_000
    }
}

/** What each property is called where a person reads it. */
fun label(property: ConditionProperty): String = property.name
    .split('_')
    .joinToString(" ") { it.lowercase() }

/** Raised when the condition cannot be answered from what the run is carrying. */
class ConditionNotDecidableException(message: String) : RuntimeException(message)
