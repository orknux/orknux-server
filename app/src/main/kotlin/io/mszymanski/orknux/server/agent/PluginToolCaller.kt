package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.action.FunctionCaller
import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.plugin.Plugin
import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginParameters
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.workflow.script.ScriptOrigin
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Calling a plugin's tool, as an agent does it.
 *
 * A plugin declares two surfaces and says which reader each is for: `functions()`
 * for workflows, `tools()` for agents. What a grant list resolves against is the
 * tools - a function is not offered to a model unless the plugin put a tool in
 * front of it, because the two are described for different readers and a
 * description written for a workflow builder is the wrong words for a model.
 *
 * Most tools are that front: an `OrknuxFunctionTool` proxies one of the plugin's
 * own functions, and its call resolves to the function's row and goes down
 * [FunctionCaller] - so the plugin's settings, its capability grants and any
 * edit somebody made to the function all apply exactly as they do in a run. A
 * tool with a `run` of its own goes down the same assembly against the plugin's
 * `tools()` surface instead.
 *
 * A workspace tool of the same name wins, the way a built-in wins over a
 * workspace tool: the resolution order is the shadow rule, and a plugin's names
 * are prefixed with its key precisely so this stays theoretical.
 */
@Service
class PluginToolCaller(
    private val plugins: PluginRepository,
    private val declarations: PluginDeclarations,
    private val functions: WorkflowFunctionRepository,
    private val caller: FunctionCaller,
    /** What a connection argument takes, said to the model in its own workspace's terms. */
    private val parameters: PluginParameters,
    private val mapper: ObjectMapper,
) {

    /**
     * What a model is told to pass for a connection argument of [tool]: the
     * kind, by id or name, and which of this workspace's connections those are.
     * The call resolves it to the handle; see [FunctionCaller].
     */
    fun connectionMeaning(tool: PluginTool, workspaceId: Long): String =
        parameters.connectionArgumentMeaning(tool.plugin, workspaceId)

    /**
     * One granted tool, ready to be offered and dispatched.
     *
     * [name] carries the plugin's key prefix - it is the name on the grant list
     * and the name the model calls - while [declared] keeps the plugin's own
     * spelling for the dispatch.
     */
    data class PluginTool(
        val name: String,
        val description: String?,
        val params: List<FunctionParam>,
        val plugin: Plugin,
        val declared: io.mszymanski.orknux.server.plugin.PluginToolView,
    )

    /**
     * The plugin tools this agent may call: its granted names that resolve to
     * one. The caller has already taken the workspace tools off the list, so
     * what reaches this is only what nothing else answered to.
     */
    fun granted(agent: Agent, except: Set<String>): List<PluginTool> {
        if (agent.tools.isEmpty()) return emptyList()
        return all().filter { it.name in agent.tools && it.name !in except }
    }

    /** One granted plugin tool by name, or null - for the dispatch. */
    fun resolve(agent: Agent, name: String): PluginTool? =
        name.takeIf { it in agent.tools }?.let { wanted -> all().firstOrNull { it.name == wanted } }

    /**
     * Every loaded plugin's tools, under their granted names.
     *
     * A plugin switched off offers none: its tools leave the agents' menus
     * and stop resolving, which is what the switch means. The grants naming
     * them stay on the agents — switching it back on is meant to put things
     * back, not to leave somebody re-granting what they never revoked.
     */
    fun all(): List<PluginTool> = plugins.findAll().filter { it.enabled }.flatMap { plugin ->
        declarations.readTools(plugin.declaredTools).map { declared ->
            PluginTool(
                name = "${plugin.key}_${declared.name}",
                description = declared.description,
                params = declared.params.map {
                    FunctionParam(
                        it.name,
                        ValueType.valueOf(it.type),
                        required = it.required,
                        defaultJson = it.default,
                    )
                },
                plugin = plugin,
                declared = declared,
            )
        }
    }

    /**
     * Runs one, handing it the arguments the model composed - by name in the
     * schema, positionally to the plugin, the same translation a workspace
     * tool's call makes and under the same two kindnesses.
     */
    fun call(agent: Agent, tool: PluginTool, arguments: String, sessionId: Long? = null): String {
        /*
         * A key, or the document where a key was meant. Issue #466.
         *
         * Every tool that takes a `contentKey` takes it precisely so the bytes
         * do not travel through the model - and a model that has the document
         * in front of it will sometimes send the document. What happened then
         * was a lookup that failed, and an answer saying nothing was kept under
         * eight thousand characters of HTML, which reads as the store being
         * broken rather than as the argument being wrong.
         *
         * Refused here rather than in each plugin: it is the same mistake
         * whatever the tool, and a rule every plugin has to remember is a rule
         * half of them will not.
         */
        retypedKeyIn(arguments)?.let { said -> return mapper.writeValueAsString(mapOf("error" to said)) }

        val positional = argumentsFor(tool.params, arguments)

        val result = if (tool.declared.proxyOf != null) {
            /*
             * The tool is a front for one of the plugin's functions, so the
             * call is the function's call: resolved to the row the registry
             * keeps, run down the one path a function runs on. An edit to the
             * function is an edit to the tool, which is the point of the proxy.
             */
            val qualified = "${tool.plugin.key}_${tool.declared.proxyOf}"
            val function = functions.findByScopeAndName(FunctionScope.PLUGIN, qualified)
                ?: return mapper.writeValueAsString(
                    mapOf("error" to "This tool fronts $qualified, which is no longer provided"),
                )
            caller.call(
                function,
                positional,
                context = mapper.writeValueAsString(
                    mapOf("workspaceId" to agent.workspaceId, "agent" to agent.name, "tool" to tool.name),
                ),
                workspaceId = agent.workspaceId,
                origin = ScriptOrigin(),
                sessionId = sessionId,
            )
        } else {
            caller.callPluginTool(tool.plugin, tool.declared.name, positional, agent.workspaceId, sessionId)
        }

        return when (result) {
            is ScriptResult.Returned -> result.json ?: mapper.writeValueAsString(mapOf("result" to null))
            is ScriptResult.Failed -> {
                log.warn("Plugin tool {} failed for agent {}: {}", tool.name, agent.name, result.reason)
                mapper.writeValueAsString(mapOf("error" to result.reason))
            }
        }
    }

    /**
     * Whether a key-shaped argument was given a document, and what to say.
     * Issue #466.
     *
     * A key is short, one line and has no spaces - `pdf.1lt2fm6`, `report.zip`.
     * Anything long, or with a line break in it, is the content: nothing that
     * hands out keys has ever made one of those. Null where every key-shaped
     * argument looks like a key, which is almost every call.
     */
    internal fun retypedKeyIn(arguments: String): String? {
        val sent = runCatching { mapper.readTree(arguments) }.getOrNull() ?: return null
        if (!sent.isObject) return null

        sent.properties().forEach { (name, value) ->
            if (!name.lowercase().endsWith("contentkey") || !value.isTextual) return@forEach
            val given = value.stringValue()
            val bad = given.length > MOST_KEY_CHARS || given.any { it == LINE_BREAK || it == RETURN }
            if (bad) {
                return "$name takes the key something handed you - a short name like report.zip - " +
                    "not the content itself. What arrived is ${given.length} characters. " +
                    "Call the tool that made or fetched this and pass the key its answer carried; " +
                    "where it is in a scratchpad, keep the pad and pass that key."
            }
        }
        return null
    }

    /** The same layout rule as a workspace tool's; see [WorkspaceToolCaller.argumentsFor]. */
    private fun argumentsFor(params: List<FunctionParam>, arguments: String): List<String> {
        if (params.isEmpty()) return emptyList()
        val sent = runCatching { mapper.readTree(arguments) }.getOrNull()

        return params.map { param ->
            val given = sent?.path(param.name)
            when {
                given == null || given.isMissingNode || given.isNull ->
                    /*
                     * What was left out: the default where the parameter has
                     * one, and null where it does not.
                     *
                     * This is what the sentinel used to be for - "0 for the
                     * default", explained in a description a model read on
                     * every call. Declared, the model simply omits the
                     * argument and the server puts the value in.
                     */
                    param.defaultJson
                        ?: if (params.size == 1) arguments.ifBlank { "{}" } else "null"

                given.isString && param.type != ValueType.STRING -> unwrapped(given.stringValue().orEmpty())
                    ?: mapper.writeValueAsString(given)

                else -> mapper.writeValueAsString(given)
            }
        }
    }

    /** The JSON inside a string a model stringified, or null if it was only a string. */
    private fun unwrapped(text: String): String? {
        val parsed = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
        return if (parsed.isMissingNode || parsed.isNull || parsed.isString) null else text
    }

    private companion object {

        /**
         * The longest a key is before what arrived is plainly a document.
         * Issue #466.
         *
         * Keys here are short by construction - a tool names one after the file
         * it made - and the longest anything hands out is well under this. A
         * bound rather than a pattern, because a key is only ever compared for
         * equality and nothing should have to guess its shape.
         */
        const val MOST_KEY_CHARS = 200

        /** What no key has in it, and every document has. */
        const val LINE_BREAK = 10.toChar()
        const val RETURN = 13.toChar()
        val log = LoggerFactory.getLogger(PluginToolCaller::class.java)
    }
}
