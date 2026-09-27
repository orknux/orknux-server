package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.phrases
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Turns what a plugin declared into functions a workflow can call.
 *
 * The declaration is the plugin's; these rows are the server's record of it. They
 * are scoped to the plugin rather than to a workspace, so every workspace can use
 * them, and nothing but the plugin can change them.
 *
 * A function may return, or take, one of the shapes the plugin exports. The
 * declaration names it and a row holds an id, so this is where the one becomes
 * the other — which is why [PluginObjectRegistry] runs first.
 *
 * Reconciled rather than appended: what the plugin declares now is what exists
 * afterwards. A function it no longer declares goes, one it has renamed arrives as
 * a new name, and loading the same plugin twice leaves the same set behind — which
 * is the whole reason the server asks the plugin rather than letting the plugin
 * announce itself whenever it likes.
 */
@Service
class PluginFunctionRegistry(
    private val functions: WorkflowFunctionRepository,
    /** The shapes this plugin exports, so a declared return type can become a reference. */
    private val objects: io.mszymanski.orknux.server.obj.WorkflowObjectRepository,
    private val dependants: ComponentDependants,
    private val declarations: PluginDeclarations,
) {

    /**
     * Makes the plugin's declarations the set of functions it provides.
     *
     * @return the names now provided, for the audit line and the response.
     */
    @Transactional
    fun reconcile(plugin: Plugin): List<String> {
        val pluginId = requireNotNull(plugin.id)
        val declared = declarations.read(plugin.declaredFunctions)
        val wanted = declared.associateBy { qualified(plugin.key, it.name) }

        val existing = functions.findByPluginId(pluginId).associateBy { it.name }

        /*
         * Gone from the declaration means gone from the workspace — unless
         * something is calling it, in which case the plugin has taken away
         * something in use and that is worth refusing rather than breaking.
         */
        /*
         * An edited row is somebody's work, not the declaration's echo, so a
         * declaration that vanished does not take it away: the row runs from
         * its own code now and goes on doing so. Only unedited rows follow the
         * plugin out.
         */
        val removed = (existing.keys - wanted.keys).filter { existing.getValue(it).editedAt == null }
        removed.forEach { name ->
            val function = existing.getValue(name)
            val callers = callersOf(requireNotNull(function.id))
            if (callers.isNotEmpty()) throw PluginFunctionInUseException(name, callers)
        }
        functions.deleteAll(removed.map(existing::getValue))

        /*
         * The shapes this plugin exports, by the name it calls them.
         *
         * A declaration says `returnType: 'Issue'` and a row holds an id, so
         * this is where the one becomes the other. The rows are made first -
         * [PluginObjectRegistry] runs ahead of this - because a reference
         * cannot be written before the thing it points at exists.
         */
        val shapes = shapes(plugin)

        wanted.forEach { (name, declaration) ->
            val params = declaration.params.map {
                FunctionParam(
                    it.name,
                    ValueType.valueOf(it.type),
                    objectId = it.objectName?.let(shapes::get),
                    required = it.required,
                    defaultJson = it.default,
                )
            }
            val returnType = ValueType.valueOf(declaration.returnType)
            val returnObjectId = declaration.returnObject?.let(shapes::get)

            val function = existing[name]?.apply {
                // The declaration no longer speaks for an edited row: a reload
                // that overwrote the edit would be the thing edits exist to
                // survive. The row keeps what somebody made of it.
                if (editedAt != null) return@forEach
                this.description = declaration.description
                this.returnType = returnType
                this.returnObjectId = returnObjectId
                this.params = params.toMutableList()
                this.source = explanation(plugin, declaration)
                this.lastModifiedAt = OffsetDateTime.now()
                this.lastModifiedBy = "plugin ${plugin.key}"
            } ?: WorkflowFunction(
                workspaceId = null,
                scope = FunctionScope.PLUGIN,
                pluginId = pluginId,
                name = name,
                description = declaration.description,
                source = explanation(plugin, declaration),
                returnType = returnType,
                returnObjectId = returnObjectId,
                params = params.toMutableList(),
                lastModifiedAt = OffsetDateTime.now(),
                lastModifiedBy = "plugin ${plugin.key}",
            )
            functions.save(function)
        }

        return wanted.keys.sorted()
    }

    /**
     * Whether anything would break if this plugin were unloaded.
     *
     * Unloading cascades its functions away in the database, so the check has to
     * happen before, not during: a workflow pointing at a function that no longer
     * exists is a run that fails at the moment it matters.
     */
    fun inUse(plugin: Plugin): Map<String, List<String>> =
        functions.findByPluginId(requireNotNull(plugin.id))
            .associate { it.name to callersOf(requireNotNull(it.id)) }
            .filterValues { it.isNotEmpty() }

    /**
     * Actions, conditions and webhooks that name this function.
     *
     * The webhooks are here because a webhook may authenticate with one of these,
     * and a gatekeeper that has been cascaded away refuses every caller — the one
     * kind of breakage nobody is watching when it happens.
     *
     * Asked of [ComponentDependants] rather than assembled here. This was the
     * second copy of the question, and the two had drifted in the one place a
     * copy always drifts: a webhook was named bare, so a plugin's refusal sent the
     * reader looking for an action of that name while the workspace's own refusal
     * for the same function said "the webhook Nightly". One question, one wording,
     * and the same rows the Used by list draws.
     */
    private fun callersOf(functionId: Long): List<String> = dependants.callersOfFunction(functionId).phrases()

    /**
     * `teammates_isTeammate` — the plugin's id, then the name it declared.
     *
     * Prefixed because a function is called by name and two plugins may reasonably
     * both offer `isTeammate`. The plugin's id is unique and its own names are
     * unique within it, so the pair cannot collide with anything.
     */
    private fun qualified(key: String, name: String): String = "${key}_$name"

    /**
     * The rows this plugin's shapes became, keyed by the plugin's own name.
     *
     * The declarations keep the plugin's spelling - `Issue` - and the rows
     * carry the prefix, so the lookup strips one side back to the other. A
     * name with no row is left as null rather than refused: the declaration
     * has already been checked against the exported set, so the only way to
     * get here is a row that went missing, and a dangling reference is
     * reported by the screen that draws it.
     */
    private fun shapes(plugin: Plugin): Map<String, Long> =
        objects.findByPluginId(requireNotNull(plugin.id))
            .mapNotNull { row ->
                val bare = row.name.removePrefix("${plugin.key}_")
                row.id?.let { bare to it }
            }
            .toMap()

    /**
     * What the source column holds for a function nobody has taken over yet.
     *
     * The plugin's own implementation, rewritten as the module an edit would
     * run - so taking a function over starts from the real code, not from a
     * blank page or a commented copy of it. The header says what a save does,
     * and until one happens nothing here is executed: the plugin runs its
     * bundled copy. Where the contract could not read the implementation, an
     * empty body under the declared parameters stands in.
     */
    private fun explanation(plugin: Plugin, declaration: PluginFunctionView): String = buildString {
        appendLine("/*")
        appendLine(" * Provided by the \"${plugin.name}\" plugin (${plugin.key}). This is the")
        appendLine(" * plugin's own implementation, shown here to be edited: saving any change")
        appendLine(" * takes the function over - from then on this module is what runs, and")
        appendLine(" * plugin reloads leave it alone. Until a save, nothing here is executed;")
        appendLine(" * the plugin runs its bundled copy.")
        appendLine(" *")
        appendLine(" * `this.settings` is the plugin's and does not reach an edited module:")
        appendLine(" * where the code reads it, put this workspace's own value instead.")
        appendLine(" */")
        append(moduleOf(declaration) ?: "export default function ${declaration.name}${parameterList(declaration)} {\n}")
    }

    /**
     * The plugin's `run`, rewritten as the module an edited row executes.
     *
     * An arrow or a function expression cannot stand as a module on its own,
     * so the parameters and body are lifted into an `export default function`
     * under the declared name. Null where the source is missing or written in
     * a shape this does not read - a bound method, a reference to a helper -
     * and the caller then falls back to an empty body.
     */
    private fun moduleOf(declaration: PluginFunctionView): String? {
        val source = declaration.source?.trim() ?: return null

        val block = Regex("""^(async\s+)?(?:function\s*[\w$]*\s*)?\(([^)]*)\)\s*(?:=>\s*)?\{([\s\S]*)}$""")
            .find(source)
        if (block != null) {
            val (async, params, body) = block.destructured
            return "export default ${async}function ${declaration.name}($params) {${dedented(body)}}"
        }

        // The concise arrow: one expression, its own return.
        val concise = Regex("""^(async\s+)?(?:\(([^)]*)\)|([\w$]+))\s*=>\s*([\s\S]+)$""").find(source)
        if (concise != null) {
            val async = concise.groupValues[1]
            val params = concise.groupValues[2].ifEmpty { concise.groupValues[3] }
            val expression = concise.groupValues[4].trim().removeSuffix(";")
            return "export default ${async}function ${declaration.name}($params) {\n  return $expression;\n}"
        }

        return null
    }

    /** The body re-indented to the module's own margin, whatever the bundle's was. */
    private fun dedented(body: String): String {
        val lines = body.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        val margin = lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' }.length } ?: 0
        return "\n" + lines.joinToString("\n") { if (it.isBlank()) "" else "  ${it.drop(margin)}" } + "\n"
    }

    /** "(connection, link)" - the declared parameters, for the empty fallback. */
    private fun parameterList(declaration: PluginFunctionView): String =
        declaration.params.joinToString(", ", "(", ")") { it.name }
}

class PluginFunctionInUseException(name: String, callers: List<String>) : RuntimeException(
    "The plugin no longer declares \"$name\", but it is still used by " +
        "${callers.joinToString(", ")}. Change those first.",
)

/**
 * What still calls this plugin, said so somebody can act on it. Issue #511.
 *
 * The first version printed every caller of every function, and the refusal for
 * one real plugin ran to four lines reading "zzCustomActionSave step, Action,
 * Action, Action, Action, Action, Action, zzCustomActionSave step, ..." - which
 * is twenty-four callers and four distinct names. A list that repeats a name
 * sixteen times is not more informative than one that says how many; it is
 * harder to read, and it buries the part that identifies anything.
 *
 * So: the distinct names, a few of them, and a count for the rest.
 */
class PluginInUseException(used: Map<String, List<String>>) : RuntimeException(
    "This plugin provides functions that are still in use: " +
        used.entries.joinToString("; ") { (name, callers) -> "$name is called by " + phrase(callers) } +
        ". Change those first.",
) {
    private companion object {

        /** How many distinct names are worth printing before a count says it better. */
        const val MOST_NAMED = 4

        /**
         * "the Nightly action and 3 others" - distinct, capped, counted.
         *
         * The total is the number of callers rather than of names, because that
         * is what somebody has to go and change; the names are there to say
         * where to start looking.
         */
        fun phrase(callers: List<String>): String {
            val distinct = callers.distinct()
            val named = distinct.take(MOST_NAMED).joinToString(", ")
            val rest = callers.size - distinct.take(MOST_NAMED).size
            return if (rest <= 0) named else "$named and $rest more"
        }
    }
}
