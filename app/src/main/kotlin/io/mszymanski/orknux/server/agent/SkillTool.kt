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
    fun list(agent: Agent): List<SkillSummary> = summarise(granted(agent))

    private fun summarise(held: List<GrantedSkill>): List<SkillSummary> = held
        /*
         * The server's own first, then the workspace's. Issue #471: this list is
         * what an agent prints when somebody asks what commands it takes, and
         * the ones every installation has - the plan, the memory search, the
         * formats - are the ones a person is most likely to be asking after, so
         * they go at the top rather than after a workspace's dozen.
         *
         * A listing order only. [load] still reads [granted] in its own order,
         * where the workspace's come first, so a workspace skill that shares a
         * name with a built-in still wins - see there.
         */
        .sortedBy { if (it.catalog == BuiltInSkills.CATALOG) 0 else 1 }
        .map { skill ->
            /*
             * And where two of them answer to one id, both rows say so and
             * name the other's catalog. Issue #473: an id is unique inside a
             * plugin and inside the workspace, and nothing makes it unique
             * across two plugins - so a command could mean either, the first in
             * the order below won it silently, and the other was unreachable
             * without anybody being told. The order still decides; what changes
             * is that the agent can see there was a decision and can ask for
             * the other one by catalog.
             */
            val also = held.filter { it !== skill && it.key.equals(skill.key, ignoreCase = true) }
            SkillSummary(
                name = skill.name,
                id = skill.key,
                description = skill.description,
                catalog = skill.catalog,
                alsoIn = also.map { it.catalog },
            )
        }

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
        val typed = name.trim()
        val asked = typed.substringAfterLast(':').trim().ifEmpty { typed }

        /*
         * A qualified ask first: `jira_plugin:review`, or `jira:review` with the
         * plugin's key on its own. Issue #473: an id is unique inside a catalog
         * and nothing makes it unique across two plugins, so where two answer to
         * one id the list says so and this is how the agent reaches the one the
         * bare id does not. It was already writing a namespace in front of an id
         * sometimes - the prefix used to be stripped and thrown away - so this
         * reads what was always being typed rather than asking for a new spelling.
         */
        val where = typed.substringBeforeLast(':', "").trim().trimEnd(':').trim()
        if (where.isNotEmpty()) {
            held.firstOrNull {
                sameCatalog(it.catalog, where) &&
                    (it.key.equals(asked, ignoreCase = true) || it.name.equals(asked, ignoreCase = true))
            }?.let { return it }
        }

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

    /**
     * The other catalogs holding a skill with this one's id. Issue #473.
     *
     * For the answer `skill_load` gives: an agent that asked for a shared id got
     * one of two pages and had no way of knowing the other existed. Empty for
     * almost every skill.
     */
    fun alsoAnswering(agent: Agent, skill: GrantedSkill): List<String> = granted(agent)
        .filter { it.key.equals(skill.key, ignoreCase = true) && it.catalog != skill.catalog }
        .map { it.catalog }
        .distinct()

    /** `jira_plugin`, `jira`, or a workspace folder's own name: all three name that catalog. */
    private fun sameCatalog(catalog: String, asked: String): Boolean =
        catalog.equals(asked, ignoreCase = true) || catalog.equals(catalogOf(asked), ignoreCase = true)

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
        /*
         * And the ones this agent was told not to see. Issue #480: the catalogs
         * say what is in scope and the hidden list says which of those are out
         * of it, so a workspace can grant a folder and keep one page of it away
         * from one agent without splitting the folder in two. Matched on the id
         * the way everything else is matched on it.
         *
         * Filtered here rather than in [list], so it holds for [load] too: a
         * skill an agent cannot see is a skill it cannot load by guessing the
         * name, which is the same rule an ungranted catalog has always had.
         */
        val unwanted = agent.hiddenSkills.map { it.lowercase() }.toSet()
        val offered = (own + fromPlugins.granted(held))
            .filterNot { it.key.lowercase() in unwanted }
        return offered
    }

    /**
     * The skills in force for this agent every turn, whatever it does. Issue #480.
     *
     * The Always state, and the whole of what it means: these pages are put in
     * front of the model before anybody says anything, instead of being a line
     * it may choose to load. A mark on a skill the agent cannot see does
     * nothing - the grant decides whether, the mark only decides how - so this
     * reads the granted list rather than the mark on its own.
     */
    fun always(agent: Agent): List<GrantedSkill> {
        if (agent.requiredSkills.isEmpty()) return emptyList()
        val wanted = agent.requiredSkills.map { it.lowercase() }.toSet()
        return granted(agent)
            .filter { it.key.lowercase() in wanted }
            .distinctBy { it.catalog + "/" + it.key }
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
data class SkillSummary(
    val name: String,
    val id: String,
    val description: String?,
    val catalog: String,
    /**
     * The other catalogs holding a skill with this same id, where any do.
     *
     * Empty for almost every skill. Where it is not, this id names more than one
     * page and the agent is told so rather than left to find out that half of
     * them cannot be loaded. Issue #473.
     */
    val alsoIn: List<String> = emptyList(),
)

/** What a list of skill ids resolved to, and which ids named nothing. Issue #381. */
data class ResolvedSkills(val found: List<GrantedSkill>, val missing: List<String>)
