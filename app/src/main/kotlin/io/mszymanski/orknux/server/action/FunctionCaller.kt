package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.workflow.script.ScriptOrigin
import io.mszymanski.orknux.server.plugin.ConnectionArguments
import io.mszymanski.orknux.server.plugin.Plugin
import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginParameters
import io.mszymanski.orknux.server.plugin.PluginCapabilities
import io.mszymanski.orknux.server.plugin.PluginPermissions
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.plugin.PluginSources
import io.mszymanski.orknux.server.variable.VariableArguments
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Calls one of a workspace's functions.
 *
 * Everything between "here is a function and its arguments" and "here is what it
 * answered" — the imports assembled by the host, the workspace's variables
 * appended after the declared parameters, the plugin sandbox for a function a
 * plugin declared — lives here and nowhere else.
 *
 * It is one class because there is now more than one door onto it. A workflow
 * node calls a function at a point the graph fixed; the editor's Run calls the
 * same function to find out what it does. If those were two pieces of code they
 * would be two behaviours, and the second one — the one somebody uses to decide
 * whether a function works — would be the one that is not what runs. A test run
 * that resolves grants differently, or skips an import, proves nothing.
 *
 * What it deliberately does not do is decide anything about *whether* to call.
 * Access, auditing and what to make of the answer belong to the caller: a node
 * turns a failure into a step that failed and may retry, and the editor turns
 * the same failure into a sentence on a panel.
 */
@Service
class FunctionCaller(
    private val scripts: ScriptRunner,
    private val scriptImports: ScriptImports,
    private val pluginRunner: PluginRunner,
    private val plugins: PluginRepository,
    private val pluginParameters: PluginParameters,
    /** What a plugin declared its functions and tools to take - which arguments are connections. */
    private val pluginDeclarations: PluginDeclarations,
    private val pluginPermissions: PluginPermissions,
    private val pluginCapabilities: PluginCapabilities,
    private val pluginSources: PluginSources,
    /**
     * What the release brings itself, for a function with no plugin behind it.
     *
     * Through a provider: the embedded bundles are read at boot and this is
     * built long before that, so asking for the object at call time is what
     * keeps the two from having to be ordered. Issue #501.
     */
    private val embedded: org.springframework.beans.factory.ObjectProvider<
        io.mszymanski.orknux.server.embedded.EmbeddedCapabilities,
        >,
    private val externals: VariableArguments,
    private val timeouts: ScriptTimeouts,
) {

    /**
     * Runs [function] and says what came back.
     *
     * @param declared JSON for each parameter the function declares, in the order
     *   it declares them. The workspace's variables are appended here rather than
     *   asked for, because they are not the caller's to supply: a grant belongs to
     *   the function that declared it, and a caller that could pass one could pass
     *   something else instead.
     * @param context what the script may know about where it is running, as JSON.
     * @param workspaceId which workspace is asking. Only a plugin's function reads
     *   it — its settings and the permissions somebody agreed to are per workspace.
     * @param insteadOfVariables what to hand a named variable in place of what the
     *   workspace holds. **Empty for every caller but a test run**, and that is the
     *   whole of the rule: a node, a trigger and a workflow pass nothing here, so a
     *   run that happens on its own is handed exactly what the workspace says.
     *
     *   A test run may. The argument against it is real - a run given a value the
     *   real run would never see proves less about the function - but it was
     *   answered: a signature check against a secret nobody may read is untestable
     *   from the editor otherwise, and the person testing it already has the
     *   editor open on its source. What is not affected is anything the run leaves
     *   behind: the audit line says a run was given values by hand, so a run that
     *   behaved differently from the real one is never mistaken for it later.
     *
     * Never throws for anything the script did. A broken import and a plugin that
     * has not been configured come back as [ScriptResult.Failed] with `settled`
     * set, because neither would answer differently on a second attempt — which is
     * exactly what the node runner used to say by throwing a permanent failure.
     */
    fun call(
        function: WorkflowFunction,
        declared: List<String>,
        context: String,
        workspaceId: Long,
        insteadOfVariables: Map<String, String> = emptyMap(),
        /**
         * Which run this belongs to, for the log. The function is filled in
         * here - this is the one thing every caller of this has - and whatever
         * else the caller knows comes in on it.
         */
        origin: ScriptOrigin = ScriptOrigin(),
        /**
         * The AI session this call is made inside, where it is made inside
         * one - an agent's tool call. It is what scopes `orknux.session.store`;
         * a workflow node and the editor's Run pass nothing, and the store's
         * helper says there is no session there.
         */
        sessionId: Long? = null,
    ): ScriptResult {
        val arguments = declared + externals.of(function, insteadOfVariables)

        /*
         * A plugin's function is not this workspace's JavaScript, and its source
         * column holds a note saying so rather than code. It runs in the plugin's
         * own sandbox, out of the plugin's own text, and it is handed what this
         * workspace answered the plugin's parameters with.
         *
         * Unless somebody edited it: then the row's code is the implementation,
         * a module like any workspace function's, and it runs down the script
         * path below. What an edited copy does not have is the plugin's
         * `this.settings` — the editor says so where the edit is made.
         */
        if (function.scope == FunctionScope.PLUGIN && function.editedAt == null) {
            return callPlugin(function, arguments, workspaceId, sessionId)
        }

        /*
         * And the release's own bundles, which have no row behind them at all.
         * Issue #501: what used to be the PDF and chart plugins is the product
         * now, so a graph naming pdf_fromHtml is answered out of the bundle
         * Orknux ships rather than out of something somebody installed.
         */
        if (function.scope == FunctionScope.EMBEDDED && function.editedAt == null) {
            return embedded.getObject().callFunction(function.name, arguments, workspaceId, sessionId)
                ?: ScriptResult.Failed(
                    "cannot run: this release does not bring " + function.name + " any more.",
                    0,
                )
        }

        /*
         * What it imports is assembled before it runs, because the sandbox resolves
         * nothing itself. An import that no longer resolves is settled: nothing
         * about running it again would find the function somebody deleted.
         */
        return when (val resolved = scriptImports.resolve(function.imports, function.libraries)) {
            is ScriptImportsResult.Broken -> ScriptResult.Failed(resolved.reason, 0)

            is ScriptImportsResult.Resolved -> scripts.call(
                function.source,
                function.name,
                arguments,
                context,
                resolved.modules,
                resolved.imports,
                on = workspaceId,
                origin = origin.copy(functionId = function.id),
                timeoutMillis = timeouts.forFunction(function.timeoutSeconds, workspaceId),
                sessionId = sessionId,
            )
        }
    }

    /** The names the workspace's variables arrive under, for anything that has to say so. */
    fun grantsOf(function: WorkflowFunction): List<String> = externals.namesOf(function)

    /**
     * Runs one of a plugin's own tools - a declaration in `tools()` with a
     * `run` of its own, which has no function row to go down [call] with.
     *
     * Here rather than in the agent code because this is the same assembly as
     * a plugin function's: the workspace's settings, the accepted permissions
     * and capabilities, all read per call from the plugin's row. A tool that
     * proxies a function never comes this way - it resolves to the function's
     * row and takes [call], edits and all.
     *
     * @param toolName the name the plugin gave it, without the key prefix.
     */
    fun callPluginTool(
        plugin: Plugin,
        toolName: String,
        arguments: List<String>,
        workspaceId: Long,
        sessionId: Long? = null,
    ): ScriptResult {
        if (!plugin.enabled) {
            return ScriptResult.Failed(
                "cannot run: the ${plugin.key} plugin is switched off. Switch it on under Admin → Plugins.",
                0,
            )
        }

        val missing = pluginParameters.missingFor(plugin, workspaceId)
        if (missing.isNotEmpty()) {
            return ScriptResult.Failed(
                "cannot run: the ${plugin.key} plugin has not been told " + missing.joinToString(", ") +
                    ". Set it on this workspace's plugins page.",
                0,
            )
        }

        val declaredParams = pluginDeclarations.readTools(plugin.declaredTools)
            .firstOrNull { it.name == toolName }?.params.orEmpty()
        val handed = when (val resolved = pluginParameters.connectionArguments(plugin, workspaceId, declaredParams, arguments)) {
            is ConnectionArguments.Refused -> return ScriptResult.Failed("cannot run: " + resolved.reason, 0)
            is ConnectionArguments.Handed -> resolved.arguments
        }

        return pluginRunner.call(
            plugin.source,
            toolName,
            handed,
            pluginParameters.settingsFor(plugin, workspaceId),
            pluginPermissions.grantedTo(plugin),
            pluginCapabilities.grantedTo(plugin),
            on = workspaceId,
            surface = "tools",
            sessionId = sessionId,
            libraries = pluginSources.librariesOf(plugin),
            /*
             * The workspace's tool bound, the same one a tool it wrote itself
             * runs under. A plugin's call used to be bounded by the number
             * meant for *loading* a plugin, so a workspace that had set two
             * minutes was stopped at ten seconds and told so in a sentence
             * naming a number nobody had configured.
             *
             * No per-tool override here: a plugin's tool has no row of its own
             * to carry one. The workspace's is the setting somebody can reach.
             */
            timeoutMillis = timeouts.forTool(null, workspaceId),
        )
    }

    /**
     * Runs one of a plugin's workflow actions - a declaration in `actions()`
     * with a `run` that takes the node's inputs as one object. Issue #438.
     *
     * Here for the reason [callPluginTool] is: it is the same assembly as a
     * plugin function's - the workspace's settings, the accepted permissions
     * and capabilities, all read per call from the plugin's row - and a second
     * copy of it would be a second place a grant could be resolved differently.
     *
     * @param actionName the name the plugin gave it, without the key prefix.
     * @param input the node's mapped inputs as one JSON object, keyed by
     *   parameter name; what the plugin declared as an array arrives as one.
     * @param context what the script may know about where it is running, as
     *   JSON; the sandbox adds the plugin's settings to it before the call.
     */
    fun callPluginAction(
        plugin: Plugin,
        actionName: String,
        input: String,
        context: String,
        workspaceId: Long,
        origin: ScriptOrigin = ScriptOrigin(),
    ): ScriptResult {
        if (!plugin.enabled) {
            return ScriptResult.Failed(
                "cannot run: the ${plugin.key} plugin is switched off. Switch it on under Admin → Plugins.",
                0,
            )
        }

        val missing = pluginParameters.missingFor(plugin, workspaceId)
        if (missing.isNotEmpty()) {
            return ScriptResult.Failed(
                "cannot run: the ${plugin.key} plugin has not been told " + missing.joinToString(", ") +
                    ". Set it on this workspace's plugins page.",
                0,
            )
        }

        return pluginRunner.call(
            plugin.source,
            actionName,
            listOf(input, context),
            pluginParameters.settingsFor(plugin, workspaceId),
            pluginPermissions.grantedTo(plugin),
            pluginCapabilities.grantedTo(plugin),
            on = workspaceId,
            surface = "actions",
            libraries = pluginSources.librariesOf(plugin),
            // The function bound, which is what a workflow's step waits on; a
            // plugin's action has no row of its own to carry one.
            timeoutMillis = timeouts.forFunction(null, workspaceId),
        )
    }

    /**
     * Runs a function one of the plugins declared.
     *
     * A required parameter nobody answered stops it before the plugin is loaded,
     * and stops it settled: what is missing is a piece of configuration, and
     * configuration does not appear because something was tried a second time. The
     * workspace's plugin page marks the same parameters, so the sentence here and
     * the red mark there are the same fact.
     */
    private fun callPlugin(
        function: WorkflowFunction,
        arguments: List<String>,
        workspaceId: Long,
        sessionId: Long? = null,
    ): ScriptResult {
        val plugin = function.pluginId?.let { plugins.findByIdOrNull(it) }
            ?: return ScriptResult.Failed("is declared by a plugin that is no longer loaded", 0)

        /*
         * Settled, like a missing parameter: a plugin switched off is a
         * decision somebody made, and trying again does not change a decision.
         * The function's row stays so a graph naming it still draws — this is
         * where the switch is felt.
         */
        if (!plugin.enabled) {
            return ScriptResult.Failed(
                "cannot run: the ${plugin.key} plugin is switched off. Switch it on under Admin → Plugins.",
                0,
            )
        }

        val missing = pluginParameters.missingFor(plugin, workspaceId)
        if (missing.isNotEmpty()) {
            return ScriptResult.Failed(
                "cannot run: the ${plugin.key} plugin has not been told " + missing.joinToString(", ") +
                    ". Set it on this workspace's plugins page.",
                0,
            )
        }

        // The name the plugin gave it, not the prefixed one a workspace picks it
        // by: the prefix exists so two plugins can both declare `send`, and the
        // plugin never agreed to answer to it.
        val declared = function.name.removePrefix("${plugin.key}_")

        /*
         * A connection argument arrives as whatever named it - the id a node's
         * picker wrote, the id or name a model wrote - and leaves as the handle
         * the plugin can reach the host with. Settled when it names nothing
         * usable: the same id names the same row on every attempt.
         */
        val declaredParams = pluginDeclarations.read(plugin.declaredFunctions)
            .firstOrNull { it.name == declared }?.params.orEmpty()
        val handed = when (val resolved = pluginParameters.connectionArguments(plugin, workspaceId, declaredParams, arguments)) {
            is ConnectionArguments.Refused -> return ScriptResult.Failed("cannot run: " + resolved.reason, 0)
            is ConnectionArguments.Handed -> resolved.arguments
        }

        return pluginRunner.call(
            plugin.source,
            declared,
            handed,
            pluginParameters.settingsFor(plugin, workspaceId),
            // What a person accepted for this plugin, and nothing else. Read per
            // call from this plugin's row, so one plugin's agreement cannot reach
            // another's context.
            pluginPermissions.grantedTo(plugin),
            // And what it may ask the server to do, read the same way and
            // from its own row: a capability reaches outside the sandbox, so
            // it is granted apart from the permissions above.
            pluginCapabilities.grantedTo(plugin),
            on = workspaceId,
            sessionId = sessionId,
            libraries = pluginSources.librariesOf(plugin),
            // The function bound, which is what a workflow's step waits on; see
            // the note on the tool call above.
            timeoutMillis = timeouts.forFunction(function.timeoutSeconds, workspaceId),
        )
    }
}
