package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * The workflow actions the loaded plugins declare, in one place. Issue #438.
 *
 * Three things read it. The editor's Action node lists them beside the built-in
 * subtypes, so a plugin's "Reply in the thread" is picked the way "Send Message"
 * is; the action form refuses a plugin action nothing declares, so an Action
 * row cannot name a block no node could ever be handed; and the runner reads a
 * node's inputs and outputs off the declaration rather than asking the sandbox,
 * which is what lets the ports be drawn without loading a plugin.
 *
 * Only plugins that are switched on are offered: a plugin turned off offers
 * nothing, its actions included. An Action already pointing at one keeps
 * pointing - the row stays so a graph naming it still draws - and the switch is
 * felt where a switched-off plugin's function is felt, at the run.
 */
@Controller
class PluginActions(
    private val plugins: PluginRepository,
    private val declarations: PluginDeclarations,
    private val access: WorkspaceAccess,
) {

    /**
     * Every action an enabled plugin declares, in plugin order, for the editor.
     *
     * A plugin belongs to the installation rather than to a workspace, so the
     * list is the same for every workspace; the argument is who is asking, so a
     * workspace nobody may see does not answer the question either.
     */
    @QueryMapping
    fun pluginActions(@Argument workspaceId: Long): List<PluginActionView> {
        access.requireVisible(workspaceId)
        return all()
    }

    fun all(): List<PluginActionView> = plugins.findAllByOrderByNameAsc()
        .filter { it.enabled }
        .flatMap { declarations.readActions(it.declaredActions, it.key, it.name) }

    /**
     * The declaration an Action row names, or null where no loaded plugin
     * declares it.
     *
     * Read off the row whatever the switch says: this answers "what does this
     * action take and hand on", which the editor asks about a node already
     * drawn, and a node whose plugin is switched off should still show its
     * ports rather than a blank. Whether it may *run* is the runner's question.
     */
    fun declared(pluginKey: String, name: String): PluginActionView? {
        val plugin = plugins.findByKey(pluginKey) ?: return null
        return declared(plugin, name)
    }

    fun declared(plugin: Plugin, name: String): PluginActionView? =
        declarations.readActions(plugin.declaredActions, plugin.key, plugin.name).firstOrNull { it.name == name }
}
