package io.mszymanski.orknux.server.watcher

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.chat.ToolShed
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The tools an agent sets, lists, changes and ends its watchers with. Issues
 * #606 and #618.
 *
 * Lent per turn like `timer_set`, because a watcher belongs to a conversation:
 * it wakes the session it was set in, and without a session there is nowhere
 * for it to report to, so none is offered. Built-ins on the Tools list all the
 * same - [io.mszymanski.orknux.server.chat.BuiltInTools.GRANTED] names them -
 * so an agent's page can hide them like any other.
 *
 * On by default, which is the argument [io.mszymanski.orknux.server.chat.BuiltInTools.REACHING]
 * makes the other way for the HTTP tools and which does not apply here: a
 * watcher reaches nothing its agent cannot already reach, because it calls one
 * of the agent's own tools with the agent's own grants, and the installation
 * bounds how often, for how long and how many - see [WatcherSettings]. It
 * spends what the agent could spend by calling the tool in a loop, only
 * without a model call each time.
 *
 * None of them is offered where the installation allows no watchers at all: a
 * model is only ever offered tools that will run.
 */
@Service
class WatcherTools(
    private val service: WatcherService,
    private val settings: WatcherSettings,
    private val mapper: ObjectMapper,
) {

    /** The shed for one turn, or null with no session - there is nothing to wake. */
    fun shed(agent: Agent, session: Long?): ToolShed? = session?.let { Shed(agent, it) }

    private inner class Shed(private val agent: Agent, private val session: Long) : ToolShed {

        override fun specs(): List<ToolSpec> {
            val most = settings.maxPerAgent()
            if (most <= 0) return emptyList()
            val shortest = settings.minIntervalSeconds()
            val longest = settings.maxSeconds()
            val looks = settings.minAgentCheckSeconds()
            return listOf(
                ToolSpec(
                    name = SET,
                    description = "Sets a watcher: the server calls one of your own tools every interval_seconds, " +
                        "with the arguments you give, and wakes you when what it returns matches your condition - " +
                        "a JSONPath that finds something, or a regular expression found in the result. Returns at " +
                        "once, so you carry on or finish; the watcher keeps going without you. It ends when it " +
                        "fires, and you are told if it times out first. Use it to wait for something outside " +
                        "you - a build, a deployment, a reply - without checking by hand. Load the Watchers " +
                        "skill for how to write the condition. interval_seconds at least $shortest, " +
                        "timeout_seconds at most $longest, and at most $most running at once.",
                    summary = "call one of your tools on an interval and be woken when its result matches",
                    parameters = listOf(
                        ToolParameterSpec(TOOL, "The name of one of your tools, exactly as your list spells it.", required = true),
                        ToolParameterSpec(
                            ARGUMENTS,
                            "The arguments to call it with, as a JSON object, e.g. {\"id\": \"42\"}. {} for none.",
                        ),
                        ToolParameterSpec(
                            RESULT_PATH,
                            "Which part of the tool's result the condition is held against, as a JSONPath: \$ for " +
                                "the whole result, \$.body for the body of an http_get, \$.status for a status " +
                                "field. Choose it on purpose: a regex held against the whole result also finds " +
                                "what is in the status and headers.",
                            required = true,
                        ),
                        ToolParameterSpec(CONDITION_TYPE, "jsonpath or regex.", required = true),
                        ToolParameterSpec(
                            CONDITION,
                            "For jsonpath, a path that finds something only when you should be woken, e.g. " +
                                "\$[?(@.status == 'done')]. For regex, an expression searched anywhere in the whole result " +
                                "text - for http_get that is JSON with status, headers and body, so `0` also " +
                                "matches the 0 of 200: target the field, e.g. jsonpath \$[?(@.body == '0')]. " +
                                "The condition is checked once when you set it; one that already matches sets " +
                                "nothing.",
                            required = true,
                        ),
                        ToolParameterSpec(INTERVAL, "Seconds between two calls; at least $shortest.", required = true),
                        ToolParameterSpec(TIMEOUT, "Seconds before it gives up; at most $longest.", required = true),
                        ToolParameterSpec(NOTE, "What you are waiting for, in your own words; handed back when it fires."),
                        ToolParameterSpec(
                            DESCRIPTION,
                            "At most $LONGEST_DESCRIPTION characters: a short label shown on the Watchers " +
                                "page, so whoever is debugging can tell your watchers apart - e.g. " +
                                "\"nightly build of main\". Longer is refused.",
                        ),
                        ToolParameterSpec(
                            AGENT_CHECK,
                            "Optional. Seconds between looks of your own: while the condition has not matched, " +
                                "you are woken this often with the latest result, to judge it yourself and change " +
                                "the watcher with $UPDATE or end it. Use it when you are not sure your condition " +
                                "will recognise what you are waiting for. At least $looks and at least " +
                                "interval_seconds; each look costs you a turn.",
                        ),
                    ),
                ),
                ToolSpec(
                    name = UPDATE,
                    description = "Changes one of your running watchers, by its number: the arguments, the " +
                        "condition, which part of the result it is held against, the interval, how often you are " +
                        "shown the result, the note or the description. What you leave out stays as it was. Use it when a look " +
                        "at the result shows the condition will never match what you are waiting for. The tool it " +
                        "calls and how long it runs cannot change; set a new watcher for that.",
                    summary = "change one of your running watchers",
                    parameters = listOf(
                        ToolParameterSpec(WATCHER, "The watcher's number.", required = true),
                        ToolParameterSpec(ARGUMENTS, "New arguments for its tool, as a JSON object."),
                        ToolParameterSpec(RESULT_PATH, "A new JSONPath into the result for the condition to be held against."),
                        ToolParameterSpec(CONDITION_TYPE, "jsonpath or regex; give it with condition."),
                        ToolParameterSpec(CONDITION, "A new condition, as for $SET."),
                        ToolParameterSpec(INTERVAL, "New seconds between two calls; at least $shortest."),
                        ToolParameterSpec(AGENT_CHECK, "New seconds between your own looks; at least $looks. 0 stops them."),
                        ToolParameterSpec(NOTE, "A new note."),
                        ToolParameterSpec(DESCRIPTION, "A new description; at most $LONGEST_DESCRIPTION characters."),
                    ),
                ),
                ToolSpec(
                    name = LIST,
                    description = "Lists your own watchers - only yours, from every conversation: each one's " +
                        "number, the tool and arguments it calls, its condition, interval and timeout, when it " +
                        "gives up, its state, when it was set and last checked, and how many times it has looked. " +
                        "Running ones only, unless $FINISHED is true, which adds the ended ones with when and why " +
                        "they ended. Look here before setting another, so you do not watch the same thing twice.",
                    summary = "list your own watchers, running or ended",
                    parameters = listOf(
                        ToolParameterSpec(FINISHED, "true to include the ones that have ended; false by default."),
                    ),
                ),
                ToolSpec(
                    name = FINISH,
                    description = "Ends one of your watchers before it fires, by the number $SET or $LIST gave " +
                        "you - when you no longer need what it is waiting for.",
                    summary = "end one of your watchers",
                    parameters = listOf(ToolParameterSpec(WATCHER, "The watcher's number.", required = true)),
                ),
            )
        }

        override fun handles(name: String): Boolean = name in NAMES && settings.maxPerAgent() > 0

        override fun run(call: ToolCall): String = try {
            val asked = runCatching { mapper.readTree(call.arguments) }.getOrNull()
            when (call.name) {
                SET -> set(asked)
                LIST -> {
                    val all = asked?.path(FINISHED)?.let { it.isBoolean && it.asBoolean() || text(it)?.lowercase() == "true" } == true
                    val mine = if (all) service.everyOneOf(agent, session) else service.runningFor(agent, session)
                    answer(mapOf("watchers" to mine.map(::described)))
                }
                UPDATE -> update(asked)
                FINISH -> {
                    val id = whole(asked?.path(WATCHER))
                        ?: throw WatcherRefused("Say in $WATCHER the number of the watcher to end.")
                    val ended = service.finish(agent, session, id.toLong())
                    answer(mapOf("finished" to ended.id, "note" to "It will not call ${ended.tool} again."))
                }
                else -> refusal("There is no tool called ${call.name}")
            }
        } catch (refused: WatcherRefused) {
            refusal(refused.message ?: "That watcher could not be set.")
        }

        private fun set(asked: JsonNode?): String {
            val tool = text(asked?.path(TOOL)).orEmpty()
            val kind = when (text(asked?.path(CONDITION_TYPE))?.lowercase()?.replace("_", "")) {
                "jsonpath" -> WatcherConditionKind.JSONPATH
                "regex", "regexp" -> WatcherConditionKind.REGEX
                else -> throw WatcherRefused("$CONDITION_TYPE must be jsonpath or regex.")
            }
            val condition = text(asked?.path(CONDITION))?.takeIf { it.isNotBlank() }
                ?: throw WatcherRefused("Say in $CONDITION what the result has to match.")
            val resultPath = text(asked?.path(RESULT_PATH))?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw WatcherRefused(
                    "Say in $RESULT_PATH which part of the result the condition is held against: \$ for the " +
                        "whole result, or a JSONPath such as \$.body for one field.",
                )
            val interval = whole(asked?.path(INTERVAL))
                ?: throw WatcherRefused("$INTERVAL must be a whole number of seconds.")
            val timeout = whole(asked?.path(TIMEOUT))
                ?: throw WatcherRefused("$TIMEOUT must be a whole number of seconds.")
            val arguments = argumentsOf(asked?.path(ARGUMENTS))
            val note = text(asked?.path(NOTE))?.trim()?.ifEmpty { null }
            val description = description(asked)?.ifEmpty { null }
            val agentCheck = present(asked?.path(AGENT_CHECK))?.let {
                whole(it) ?: throw WatcherRefused("$AGENT_CHECK must be a whole number of seconds.")
            }?.takeIf { it > 0 }

            val watcher = try {
                service.create(
                    agent,
                    session,
                    WatcherRequest(
                        tool.trim(), arguments, kind, condition, resultPath, interval, timeout, note, agentCheck, description,
                    ),
                )
            } catch (already: WatcherAlreadyMatches) {
                return answer(
                    linkedMapOf(
                        "set" to false,
                        "alreadyMatches" to already.matched,
                        "result" to already.result,
                        "note" to "Called $tool once now, and the condition already matches what it returned, so " +
                            "no watcher was set. If this is what you were waiting for, it has happened. If not, " +
                            "the condition is too broad: narrow $RESULT_PATH to the field it is about - \$.body " +
                            "rather than \$ for an http_get - or the condition itself, and set it again.",
                    ),
                )
            }
            return answer(
                linkedMapOf(
                    "watcher" to watcher.id,
                    "firstCheckInSeconds" to watcher.intervalSeconds,
                    "endsAt" to watcher.expiresAt.toString(),
                    "note" to "Carry on, or finish; you will be woken when it matches, or told if it times out.",
                ),
            )
        }

        private fun update(asked: JsonNode?): String {
            val id = whole(asked?.path(WATCHER)) ?: throw WatcherRefused("Say in $WATCHER the number of the watcher to change.")
            val kind = text(asked?.path(CONDITION_TYPE))?.lowercase()?.replace("_", "")?.let {
                when (it) {
                    "jsonpath" -> WatcherConditionKind.JSONPATH
                    "regex", "regexp" -> WatcherConditionKind.REGEX
                    else -> throw WatcherRefused("$CONDITION_TYPE must be jsonpath or regex.")
                }
            }
            fun seconds(name: String): Int? = present(asked?.path(name))?.let {
                whole(it) ?: throw WatcherRefused("$name must be a whole number of seconds.")
            }
            val change = WatcherChange(
                arguments = present(asked?.path(ARGUMENTS))?.let { argumentsOf(it) },
                kind = kind,
                condition = text(asked?.path(CONDITION))?.takeIf { it.isNotBlank() },
                toolResultPath = text(asked?.path(RESULT_PATH))?.trim()?.takeIf { it.isNotEmpty() },
                intervalSeconds = seconds(INTERVAL),
                agentCheckIntervalSeconds = seconds(AGENT_CHECK),
                note = text(asked?.path(NOTE)),
                description = description(asked),
            )
            if (change == WatcherChange()) {
                throw WatcherRefused(
                    "Say what to change: $ARGUMENTS, $CONDITION, $RESULT_PATH, $INTERVAL, $AGENT_CHECK, $NOTE or $DESCRIPTION.",
                )
            }
            return answer(mapOf("changed" to described(service.update(agent, session, id.toLong(), change))))
        }

        /** The description as given, trimmed; one too long for a line on a page is refused. #621. */
        private fun description(asked: JsonNode?): String? = text(asked?.path(DESCRIPTION))?.trim()?.also {
            if (it.length > LONGEST_DESCRIPTION) {
                throw WatcherRefused("$DESCRIPTION is ${it.length} characters; keep it to one line of at most $LONGEST_DESCRIPTION.")
            }
        }

        /** An object as given, or a string holding one; anything else is refused. */
        private fun argumentsOf(node: JsonNode?): String {
            if (node == null || node.isMissingNode || node.isNull) return "{}"
            val parsed = if (node.isTextual) {
                val raw = node.stringValue().trim()
                if (raw.isEmpty()) return "{}"
                runCatching { mapper.readTree(raw) }.getOrNull()
            } else {
                node
            }
            if (parsed == null || !parsed.isObject) {
                throw WatcherRefused("$ARGUMENTS must be a JSON object, such as {\"id\": \"42\"}, or {} for none.")
            }
            return mapper.writeValueAsString(parsed)
        }

        private fun described(watcher: Watcher): Map<String, Any?> = linkedMapOf(
            "watcher" to watcher.id,
            "tool" to watcher.tool,
            "arguments" to watcher.arguments,
            "conditionType" to watcher.conditionKind.name.lowercase(),
            "condition" to watcher.condition,
            "toolResultPath" to (watcher.toolResultPath ?: WatcherCondition.WHOLE),
            "intervalSeconds" to watcher.intervalSeconds,
            "agentCheckIntervalSeconds" to watcher.agentCheckIntervalSeconds,
            "timeoutSeconds" to watcher.timeoutSeconds,
            "endsAt" to watcher.expiresAt.toString(),
            "state" to watcher.status.name.lowercase(),
            "createdAt" to watcher.createdAt.toString(),
            "lastCheckedAt" to watcher.lastCheckedAt?.toString(),
            "checks" to watcher.checks,
            "finishedAt" to watcher.finishedAt?.toString(),
            "why" to watcher.outcome,
            "note" to watcher.note,
            "description" to watcher.description,
            "thisConversation" to (watcher.sessionId == session),
        )
    }

    private fun text(node: JsonNode?): String? = when {
        node == null || node.isMissingNode || node.isNull -> null
        node.isTextual -> node.stringValue()
        node.isValueNode -> node.asString()
        else -> null
    }

    /** The node where something was given, null where it was left out. */
    private fun present(node: JsonNode?): JsonNode? =
        node?.takeUnless { it.isMissingNode || it.isNull || (it.isTextual && it.stringValue().isBlank()) }

    private fun whole(node: JsonNode?): Int? = when {
        node == null -> null
        node.isIntegralNumber -> node.asLong().takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        node.isTextual -> node.stringValue().trim().toIntOrNull()
        else -> null
    }

    private fun answer(said: Map<String, Any?>): String = mapper.writeValueAsString(said)

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    companion object {
        const val SET = "watcher_set"
        const val LIST = "watcher_list"
        const val FINISH = "watcher_finish"
        const val UPDATE = "watcher_update"
        val NAMES = setOf(SET, LIST, FINISH, UPDATE)

        const val TOOL = "tool"
        const val ARGUMENTS = "arguments"
        const val CONDITION_TYPE = "condition_type"
        const val CONDITION = "condition"
        const val RESULT_PATH = "tool_result_path"
        const val INTERVAL = "interval_seconds"
        const val TIMEOUT = "timeout_seconds"
        const val NOTE = "note"
        const val DESCRIPTION = "description"

        /** A line on a page, not a paragraph. #621. */
        const val LONGEST_DESCRIPTION = 100
        const val AGENT_CHECK = "agent_check_interval_seconds"
        const val WATCHER = "watcher"
        const val FINISHED = "include_finished"
    }
}
