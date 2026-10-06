package io.mszymanski.orknux.server.plugin

import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * The kinds of host the loaded plugins declare, in one place. Issue #363.
 *
 * Two things read it: the connection form, which lists them on its type menu
 * beside the core ones so a workspace can hold several hosts of one kind - two
 * Prometheus servers, two wikis - each labelled by the plugin rather than all of
 * them reading as "HTTP"; and the connection resolver, which refuses a kind no
 * plugin declares, so a name typed at the API cannot leave a connection wearing
 * a label nothing will ever offer to a picker.
 *
 * Only plugins that are switched on: a plugin turned off offers nothing, its
 * connection kinds included. The connections already wearing one keep it - it
 * is a label, and a label does not change what the connection does.
 */
@Controller
class PluginConnectionTypes(
    private val plugins: PluginRepository,
    private val declarations: PluginDeclarations,
) {

    /** Every kind an enabled plugin declares, in plugin order. */
    @QueryMapping
    fun pluginConnectionTypes(): List<PluginConnectionTypeView> = all()

    // Declarations only: the entity is the bundle, megabytes for some. #616.
    fun all(): List<PluginConnectionTypeView> = plugins.declared()
        .filter { it.enabled }
        .flatMap { declarations.readConnectionTypes(it.declaredConnectionTypes, it.key, it.name) }

    /** Whether [id] - a plugin key and a declared name joined - names a kind an enabled plugin declares. */
    fun known(id: String): Boolean = all().any { it.id == id }
}
