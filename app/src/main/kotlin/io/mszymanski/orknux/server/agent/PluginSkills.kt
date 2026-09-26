package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginRepository
import org.springframework.stereotype.Service

/**
 * The instruction sets plugins bring, as catalogs an agent can be granted.
 *
 * A plugin declares three surfaces and says who each is for: `functions()` for
 * workflows, `tools()` for a model to call, `skills()` for a model to read. The
 * third is this one. Nothing here runs — a skill is markdown an agent reads
 * before doing something — and a plugin ships it so the knowledge of how its
 * work is meant to be done travels with the code that does it, instead of being
 * retyped into every workspace by hand.
 *
 * **A plugin's skills are a catalog named `<key>_plugin`**, which is the whole
 * of the grant model. Skills have always been granted by catalog, and a plugin's
 * are granted the same way, from the same picker, by the same field on the
 * agent. Nothing is automatic: a plugin loaded into this installation hands its
 * skills to nobody until somebody grants the catalog.
 *
 * The suffix is what makes the name the plugin's own. A bare key could be a
 * folder somebody already has - and then one grant string meant two things,
 * which is either a silent merge or a shadow rule, and both are worse than a
 * name that cannot collide. It is spelled the way everything else a plugin
 * contributes is: joined with an underscore, qualified by the key.
 *
 * A plugin switched off offers none, the way its tools stop resolving — the
 * grant naming it stays on the agent, so switching the plugin back on puts
 * things back rather than making somebody grant them again.
 */
@Service
class PluginSkills(
    private val plugins: PluginRepository,
    private val declarations: PluginDeclarations,
    /**
     * The skills Orknux brings itself, offered through the same door. Issue
     * #468: a built-in skill is granted, listed and loaded exactly as a
     * plugin's is, so putting it here rather than beside here is what keeps
     * every reader downstream - the briefing, `skill_load`, a graph naming one
     * by id, the catalog picker - from learning that a third kind exists.
     */
    private val builtIn: BuiltInSkills,
) {

    /**
     * Every catalog the loaded plugins offer, by name.
     *
     * A plugin that declares no skills offers no catalog: an empty folder in
     * the picker is a thing somebody grants and then wonders about.
     */
    fun catalogs(): List<PluginSkillCatalog> = builtIn.catalogs() + plugins.findAllByOrderByNameAsc()
        .filter { it.enabled }
        .mapNotNull { plugin ->
            val held = declarations.readSkills(plugin.declaredSkills)
            if (held.isEmpty()) {
                null
            } else {
                val catalog = catalogOf(plugin.key)
                PluginSkillCatalog(
                    name = catalog,
                    key = plugin.key,
                    plugin = plugin.name,
                    skills = held.map {
                        GrantedSkill(
                            name = it.name,
                            key = it.key,
                            description = it.description,
                            catalog = catalog,
                            content = it.content,
                        )
                    }.sortedBy { it.name },
                )
            }
        }

    /** The skills in the plugin catalogs among these granted names. */
    fun granted(names: Collection<String>): List<GrantedSkill> {
        if (names.isEmpty()) return emptyList()
        val held = names.toSet()
        return catalogs().filter { it.name in held }.flatMap { it.skills }
    }
}

/**
 * `jira_plugin` — a plugin's key, suffixed.
 *
 * Suffixed because the name is a grant string and has to be the plugin's
 * alone: a bare key could be a folder a workspace already has, and one grant
 * meaning two things is either a silent merge or a shadow rule. Neither is as
 * good as a name that cannot collide.
 */
fun catalogOf(key: String): String = "${key}_plugin"

/** One plugin's skills, offered as a catalog of its own. */
data class PluginSkillCatalog(
    /** `jira_plugin`: what goes on an agent's grant list. */
    val name: String,
    /** The plugin's key on its own, for a screen that wants to say where this came from. */
    val key: String,
    /** What the plugin is called on screen, for a picker that shows both. */
    val plugin: String,
    val skills: List<GrantedSkill>,
)

/**
 * One skill an agent may draw on, whoever it came from.
 *
 * The one shape the briefing and the two skill tools work in, so a workspace's
 * own skill and a plugin's are the same thing to everything downstream — which
 * is the point: an agent following a skill should not have to know, and a
 * plugin that ships one should not get a second-class reader.
 */
data class GrantedSkill(
    val name: String,
    /** What a graph or a command names it by; see [SkillKeys]. Issue #381. */
    val key: String,
    val description: String?,
    /** The folder it came from: a workspace catalog's name, or a plugin's key. */
    val catalog: String,
    val content: String,
)
