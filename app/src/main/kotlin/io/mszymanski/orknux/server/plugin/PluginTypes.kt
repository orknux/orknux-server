package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.server.action.ScriptTimeouts
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The value types plugins define, and the two things a plugin does for one.
 * Issue #377.
 *
 * A Slack user id is a string, but it is a string only some values of are real,
 * and the plugin is the one thing that can say which. So a plugin names a type
 * over a base type - `SlackUser` over string - says what it needs to be told to
 * check one, and offers `suggest` for the picker and `validate` for the save.
 * This is where the server asks.
 *
 * ### What a type is called
 *
 * `<plugin key>:<name>` - `slack:SlackUser`. The prefix is the plugin's key
 * rather than nothing, because two plugins may both define a `User` and a
 * variable has to say whose it holds. The colon is not something a plugin key
 * or a type name may contain, so the split is never ambiguous.
 *
 * ### What the plugin is told
 *
 * Its settings, resolved the way they are for any function - the connection it
 * was configured with, its tokens - and, beside them, the type's own arguments:
 * what the variable was told, resolved by the same rules. A connection argument
 * arrives as the same handle a connection setting does, checked against the
 * workspace that is asking. A plugin's `suggest` sees `(typed, arguments)` and
 * `validate` sees `(value, arguments)`, and both may reach the network through
 * whatever capabilities the plugin was granted, because that is what checking a
 * Slack user against Slack needs.
 *
 * ### What a refusal means
 *
 * A plugin that cannot answer - switched off, not told its settings, a call that
 * failed - is not the same as a plugin that said no. The first is reported as
 * "could not be checked", and a variable is saved anyway with a warning in the
 * log: a Slack outage should not stop somebody saving a channel name they can
 * see. The second is a refusal, with the plugin's own reason, and nothing is
 * saved.
 */
@Service
class PluginTypes(
    private val plugins: PluginRepository,
    private val declarations: PluginDeclarations,
    private val parameters: PluginParameters,
    private val permissions: PluginPermissions,
    private val capabilities: PluginCapabilities,
    private val sources: PluginSources,
    private val runner: PluginRunner,
    private val connections: WorkspaceConnectionService,
    private val timeouts: ScriptTimeouts,
    private val mapper: ObjectMapper,
) {

    /** Every type an enabled plugin defines, as the type picker lists them. */
    fun offered(): List<VariableTypeOffer> =
        plugins.findAllByOrderByNameAsc()
            .filter { it.enabled }
            .flatMap { plugin ->
                declarations.readTypes(plugin.declaredTypes).map { type -> offer(plugin, type) }
            }

    /** One type by its full name, or null where no enabled plugin defines it. */
    fun find(key: String): Pair<Plugin, PluginTypeView>? {
        val (pluginKey, name) = split(key) ?: return null
        val plugin = plugins.findByKey(pluginKey)?.takeIf { it.enabled } ?: return null
        val type = declarations.readTypes(plugin.declaredTypes).firstOrNull { it.name == name } ?: return null
        return plugin to type
    }

    /**
     * What the plugin offers for what was typed so far.
     *
     * Empty where the type has no `suggest`, where the plugin could not be
     * asked, or where it answered with nothing - the picker draws the same
     * thing for all three, which is no list, and the reason is in the log.
     */
    fun suggest(workspaceId: Long, key: String, arguments: Map<String, String>, typed: String): List<VariableSuggestion> {
        val (plugin, type) = find(key) ?: return emptyList()
        if (!type.suggests) return emptyList()

        val answered = ask(plugin, type, workspaceId, "types:suggest", typed, arguments) ?: return emptyList()
        if (!answered.isArray) return emptyList()
        return answered.values().mapNotNull { one ->
            val value = one.get("value")?.takeIf { it.isTextual || it.isNumber || it.isBoolean }?.asString()
                ?: return@mapNotNull null
            VariableSuggestion(
                value = value,
                label = one.get("label")?.takeIf { it.isTextual }?.asString() ?: value,
                detail = one.get("detail")?.takeIf { it.isTextual }?.asString(),
            )
        }.take(MOST_SUGGESTIONS)
    }

    /**
     * Whether the plugin accepts this value.
     *
     * [VariableCheck.checked] is false where nothing could be said either way,
     * which is not a refusal; see the class note.
     */
    fun check(workspaceId: Long, key: String, arguments: Map<String, String>, value: String): VariableCheck {
        val (plugin, type) = find(key) ?: return VariableCheck.unknownType(key)
        if (!type.validates) return VariableCheck.accepted()

        val answered = ask(plugin, type, workspaceId, "types:validate", value, arguments)
            ?: return VariableCheck.unchecked("the ${plugin.key} plugin could not be asked")
        val ok = answered.get("ok")
        if (ok == null || !ok.isBoolean) {
            return VariableCheck.unchecked("the ${plugin.key} plugin answered with something other than ok")
        }
        return if (ok.asBoolean()) {
            VariableCheck.accepted()
        } else {
            VariableCheck.refused(answered.get("reason")?.takeIf { it.isTextual }?.asString() ?: "the ${plugin.key} plugin refused it")
        }
    }

    /* --------------------------------------------------------------- how --- */

    private fun ask(
        plugin: Plugin,
        type: PluginTypeView,
        workspaceId: Long,
        surface: String,
        subject: String,
        arguments: Map<String, String>,
    ): JsonNode? {
        val told = argumentsJson(type, workspaceId, arguments)
        val result = runner.call(
            plugin.source,
            type.name,
            listOf(mapper.writeValueAsString(subject), told),
            parameters.settingsFor(plugin, workspaceId),
            permissions.grantedTo(plugin),
            capabilities.grantedTo(plugin),
            on = workspaceId,
            surface = surface,
            libraries = sources.librariesOf(plugin),
            timeoutMillis = timeouts.forFunction(null, workspaceId),
        )
        return when (result) {
            is ScriptResult.Returned -> result.json?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            is ScriptResult.Failed -> {
                log.warn("{}:{} could not {}: {}", plugin.key, type.name, surface.substringAfter(':'), result.reason)
                null
            }
        }
    }

    /**
     * What the variable was told, as the plugin is handed it.
     *
     * The same resolution a plugin's settings get: a connection becomes the
     * handle - id and kind, never the credential - and is checked against the
     * workspace that is asking, so a variable cannot be pointed at another
     * workspace's Slack. Anything unanswered is left out rather than sent as
     * empty, which is what lets the plugin tell "not told" from "told nothing".
     */
    private fun argumentsJson(type: PluginTypeView, workspaceId: Long, given: Map<String, String>): String {
        val json = mapper.createObjectNode()
        type.parameters.forEach { parameter ->
            val raw = given[parameter.name]?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            if (parameter.type.equals(PluginDeclarations.CONNECTION, ignoreCase = true)) {
                val id = raw.toLongOrNull() ?: return@forEach
                val connection = connections.workspaceConnection(id) ?: return@forEach
                if (connection.workspaceId != workspaceId) return@forEach
                if (parameter.connectionType != null && connection.type.name != parameter.connectionType) return@forEach
                val handle = mapper.createObjectNode()
                handle.put("id", id)
                handle.put("type", connection.type.name)
                json.set(parameter.name, handle)
                return@forEach
            }
            when (parameter.type.uppercase()) {
                "NUMBER" -> raw.toBigDecimalOrNull()?.let { json.put(parameter.name, it) }
                "BOOLEAN" -> raw.lowercase().toBooleanStrictOrNull()?.let { json.put(parameter.name, it) }
                else -> json.put(parameter.name, raw)
            }
        }
        return mapper.writeValueAsString(json)
    }

    private fun offer(plugin: Plugin, type: PluginTypeView) = VariableTypeOffer(
        key = "${plugin.key}:${type.name}",
        plugin = plugin.key,
        pluginName = plugin.name,
        name = type.name,
        description = type.description,
        base = VariableType.valueOf(type.base.uppercase()),
        parameters = type.parameters,
        suggests = type.suggests,
        validates = type.validates,
    )

    private fun split(key: String): Pair<String, String>? {
        val at = key.indexOf(':')
        if (at <= 0 || at == key.length - 1) return null
        return key.substring(0, at) to key.substring(at + 1)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PluginTypes::class.java)

        /** A picker is a picker, not a directory. */
        const val MOST_SUGGESTIONS = 50
    }
}

/** A type a variable may be, beyond the three built in, as the picker lists it. */
data class VariableTypeOffer(
    /** `<plugin>:<name>`, which is what a variable stores. */
    val key: String,
    val plugin: String,
    val pluginName: String,
    val name: String,
    val description: String?,
    /** What a value of it is underneath, and so which of the built-in types it may stand in for. */
    val base: VariableType,
    /** What a variable of this type has to be told - a connection, usually. */
    val parameters: List<PluginParameterView>,
    val suggests: Boolean,
    val validates: Boolean,
)

/** One thing a plugin offers for what was typed. */
data class VariableSuggestion(
    /** What the variable is set to when this is taken. */
    val value: String,
    /** What the row says. */
    val label: String,
    /** A second line, where the plugin had one - a real name beside a handle. */
    val detail: String?,
)

/** Whether a plugin accepted a value, and if not, why. */
data class VariableCheck(
    val ok: Boolean,
    /** False where nothing could be said either way, which is not a refusal. */
    val checked: Boolean,
    val reason: String?,
) {
    companion object {
        fun accepted() = VariableCheck(ok = true, checked = true, reason = null)
        fun refused(reason: String) = VariableCheck(ok = false, checked = true, reason = reason)
        fun unchecked(reason: String) = VariableCheck(ok = true, checked = false, reason = reason)
        fun unknownType(key: String) = VariableCheck(ok = false, checked = true, reason = "no plugin defines a type called $key")
    }
}
