package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.memory.ToolDescriptor
import io.mszymanski.orknux.server.memory.ToolParameter
import org.springframework.stereotype.Service

/**
 * Reading the skills this agent was given, as an agent does it.
 *
 * A built-in for the same reason the memory lookup is: a workspace tool is
 * JavaScript in a sandbox with no IO, so it cannot read a table.
 *
 * Two tools rather than one, and that is the whole design. Skills are long —
 * a page of markdown each — and an agent granted five catalogs would spend most
 * of its context on instructions for work it is not doing. So the briefing lists
 * what is available by name and description, and the agent loads the one that
 * applies. What it may see is what it was granted; a catalog nobody gave it does
 * not appear in the list and cannot be loaded by guessing the name.
 *
 * **Two sources, one list.** A skill is either the workspace's own or one a
 * plugin brought, and the grant is the same either way: a catalog by name. An
 * agent following a skill has no reason to care which it was, so nothing
 * downstream of here distinguishes them — see [PluginSkills].
 */
@Service
class SkillTool(
    private val catalogs: SkillCatalogRepository,
    private val skills: AgentSkillRepository,
    private val fromPlugins: PluginSkills,
) {

    fun descriptors(): List<ToolDescriptor> = listOf(LIST, LOAD)

    /**
     * What this agent may draw on: one line each, enough to choose from.
     *
     * Deliberately without content — choosing which skill applies is what this
     * is for, and returning the text here would make the load tool pointless.
     */
    fun list(agent: Agent): List<SkillSummary> = granted(agent)
        .map { SkillSummary(it.name, it.key, it.description, it.catalog) }

    /**
     * One skill in full, by name or by id, spelled how the model managed.
     *
     * A name that is not in the granted list reads as absent rather than
     * refused: an agent guessing at a skill it was never given should learn that
     * there is no such skill, not that there is one it may not have.
     *
     * Asked forgivingly on purpose. What arrives is whatever a model typed from
     * a list it read a few turns ago, and it will not always be the exact id:
     * the name with its spaces, the id with underscores where the hyphens were,
     * or the id behind a namespace it borrowed from somewhere else -
     * `plugin::review`. None of those is a different skill, and refusing them
     * teaches a model to guess again rather than to look. So an exact match is
     * preferred, and anything left is matched on letters alone - but only where
     * exactly one skill answers, since two that differ by a separator are two
     * skills and guessing between them would be worse than saying no.
     */
    fun load(agent: Agent, name: String): GrantedSkill? {
        val held = granted(agent)
        val asked = name.substringAfterLast(':').trim().ifEmpty { name.trim() }

        held.firstOrNull { it.key.equals(asked, ignoreCase = true) || it.name.equals(asked, ignoreCase = true) }
            ?.let { return it }

        val alike = held.filter { SkillKeys.same(it.key, asked) || SkillKeys.same(it.name, asked) }
        return alike.singleOrNull()
    }

    /**
     * The skills these ids name, for a graph that loads them by force.
     *
     * Looked up across the workspace and the loaded plugins rather than the
     * agent's grants: an id written on the graph is the workflow's author
     * choosing, which is a stronger word than a grant list somebody may not
     * have kept up. A workspace skill switched off is out of reach here as
     * everywhere; an id nothing answers to is handed back, so the run can say
     * so. Issue #381.
     *
     * An id is matched exactly first and then on its letters alone, so a graph
     * written against the ids an older rule derived - `Answeringinathread` for
     * what is now `answering-in-a-thread` - goes on naming the same skill.
     * Where two skills answer to those letters the id is not guessed at; it is
     * reported missing, which is what a graph's author needs to hear.
     */
    fun byKeys(workspaceId: Long, keys: Collection<String>): ResolvedSkills {
        val own = skills.findByWorkspaceIdAndEnabledTrue(workspaceId)
            .map { GrantedSkill(it.name, it.key, it.description, catalogNameOf(it.catalogId), it.content) }
        val pool = own + fromPlugins.catalogs().flatMap { it.skills }
        val found = mutableListOf<GrantedSkill>()
        val missing = mutableListOf<String>()
        keys.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.forEach { key ->
            val match = pool.firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?: pool.filter { SkillKeys.same(it.key, key) }.singleOrNull()
            if (match == null) missing += key else if (match !in found) found += match
        }
        return ResolvedSkills(found, missing)
    }

    private fun catalogNameOf(catalogId: Long): String =
        catalogs.findById(catalogId).map { it.name }.orElse("")

    /**
     * The skills in the catalogs this agent holds, from both sources.
     *
     * A granted name that matches no catalog is dropped rather than failing:
     * catalogs are granted by name, and a rename should cost an agent one grant
     * rather than every call it makes. A skill switched off is out of reach here
     * as everywhere.
     *
     * A plugin's catalog carries the `_plugin` suffix, so a grant string
     * naming both is a folder somebody named after a plugin. The two then
     * merge, workspace first — see below.
     */
    private fun granted(agent: Agent): List<GrantedSkill> {
        if (agent.skillCatalogs.isEmpty()) return emptyList()
        val held = agent.skillCatalogs.toSet()

        val own = catalogs.findByWorkspaceIdOrderByNameAsc(agent.workspaceId)
            .filter { it.name in held }
            .flatMap { catalog ->
                skills.findByCatalogId(requireNotNull(catalog.id))
                    .filter { it.enabled }
                    .map { GrantedSkill(it.name, it.key, it.description, catalog.name, it.content) }
            }
            .sortedBy { it.name }

        /*
         * And the plugins'. The `_plugin` suffix means a grant string almost
         * never names both — a workspace would have to have a folder called
         * `jira_plugin` — and where somebody has managed it, the two merge.
         *
         * Merged rather than shadowed, deliberately. A shadow rule silently
         * takes away skills an agent was granted, which is a harder thing to
         * notice than a list with more in it than expected; and a name that
         * collides with `<key>_plugin` is a folder somebody named after a
         * plugin, which is a mistake to fix rather than a case to design
         * around. The workspace's come first, so where two skills share a
         * name, [load] finds the workspace's.
         */
        return own + fromPlugins.granted(held)
    }

    private companion object {
        val LIST = ToolDescriptor(
            name = "skill_list",
            description =
                "List the skills you have been given: the name, what each is for, and which catalog it is in. " +
                    "Call this when you want to know how this workspace goes about something.",
            parameters = emptyList(),
        )

        val LOAD = ToolDescriptor(
            name = "skill_load",
            description =
                "Read one skill in full. Pass the id skill_list gave it, copied as it stands - " +
                    "an id is lower-case words joined by hyphens, like answering-in-a-thread, and nothing " +
                    "goes in front of it. Load a skill before following it rather than guessing at what it says.",
            parameters = listOf(
                ToolParameter(
                    name = "name",
                    description = "The skill's id from skill_list, such as answering-in-a-thread. Its name works too.",
                    required = true,
                ),
            ),
        )
    }
}

/** One line about a skill: enough to decide whether to load it. */
data class SkillSummary(val name: String, val id: String, val description: String?, val catalog: String)

/** What a list of skill ids resolved to, and which ids named nothing. Issue #381. */
data class ResolvedSkills(val found: List<GrantedSkill>, val missing: List<String>)
