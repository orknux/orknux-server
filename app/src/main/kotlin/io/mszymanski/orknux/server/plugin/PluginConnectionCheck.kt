package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.CheckOutcome
import io.mszymanski.orknux.connector.connection.CheckResult
import io.mszymanski.orknux.connector.connection.PluginConnectionChecks
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.server.action.ScriptTimeouts
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * A connection of a plugin's kind, tested by the plugin.
 *
 * Testing used to ask only whether the address answered, which a login page or
 * another service at the same port also does - and reported it Connected. A
 * kind may now bring a `check`; it is handed the connection the way a function
 * taking one of its own kind is, address and credential included, and runs
 * under the plugin's own grants, so reaching the service is NETWORK_REQUEST
 * under the installation's proxy rules like any of its calls.
 *
 * Null - fall back to the address - where the kind brings no check, or its
 * plugin is gone or switched off: the connection is still a host somebody
 * wants to know is up.
 */
@Component
class PluginConnectionCheck(
    private val plugins: PluginRepository,
    private val declarations: PluginDeclarations,
    private val parameters: PluginParameters,
    private val permissions: PluginPermissions,
    private val capabilities: PluginCapabilities,
    private val sources: PluginSources,
    private val runner: PluginRunner,
    private val timeouts: ScriptTimeouts,
    private val mapper: ObjectMapper,
) : PluginConnectionChecks {

    override fun check(connection: WorkspaceConnection): CheckResult? {
        val kind = connection.pluginType ?: return null
        val key = kind.substringBefore('/')
        val name = kind.substringAfter('/', "")
        val plugin = plugins.findByKey(key)?.takeIf { it.enabled } ?: return null
        val declared = declarations.readConnectionTypes(plugin.declaredConnectionTypes, plugin.key, plugin.name)
            .firstOrNull { it.name == name } ?: return null
        if (!declared.checks) return null
        // A saved connection by its id; one the Add dialog is testing, as it was typed.
        val handle = connection.id
            ?.let { parameters.ownHandle(plugin, it, connection.workspaceId) }
            ?: parameters.draftHandle(plugin, connection).takeIf { connection.id == null }
            ?: return null

        val result = runner.call(
            sources.sourceOf(plugin),
            name,
            listOf(handle),
            parameters.settingsFor(plugin, connection.workspaceId),
            permissions.grantedTo(plugin),
            capabilities.grantedTo(plugin),
            on = connection.workspaceId,
            surface = "connectionTypes:check",
            libraries = sources.librariesOf(plugin),
            timeoutMillis = timeouts.forFunction(null, connection.workspaceId),
        )
        return when (result) {
            // A throw is a failed check, and its message is the reason - the plugin's sentence, as it wrote it.
            is ScriptResult.Failed -> CheckResult(CheckOutcome.FAILED, result.reason)
            is ScriptResult.Returned -> {
                val said = result.json?.let { runCatching { mapper.readTree(it) }.getOrNull() }
                val ok = said?.get("ok")
                val message = said?.get("message")?.takeIf { it.isString }?.asString()?.trim()?.ifEmpty { null }
                when {
                    ok == null || !ok.isBoolean ->
                        CheckResult(CheckOutcome.FAILED, "The ${plugin.name} plugin's check answered with something other than ok")
                    ok.asBoolean() -> CheckResult(CheckOutcome.CONNECTED, message ?: "Connected")
                    else -> CheckResult(CheckOutcome.FAILED, message ?: "The ${plugin.name} plugin says this is not a ${declared.label}")
                }
            }
        }
    }
}
