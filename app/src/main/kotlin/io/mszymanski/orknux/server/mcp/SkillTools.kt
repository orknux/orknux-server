package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.CreateSkillInput
import io.mszymanski.orknux.server.agent.SkillAPI
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.agent.UpdateSkillInput
import io.mszymanski.orknux.server.security.InstallationAddress
import io.mszymanski.orknux.server.security.WebProperties
import org.springframework.context.annotation.Lazy
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The orknux_* tools that list, read, write and change the workspace's skills.
 *
 * An agent that had worked out how a job is done - a team's convention for
 * drawing Kafka topics, the order to audit a repository in - could save it as a
 * memory and nothing else, so the next agent doing the same job started from
 * nothing unless a person typed the skill out for it. A skill is where a way of
 * working belongs; these write one.
 *
 * Every write goes through [SkillAPI], the editor's own door: the same format
 * check, the same unique name and key, the same audit entry, and a version kept
 * of what a change replaced - so a skill an agent rewrote is one a person can
 * put back. Offered wherever the rest of the surface is, and the writing two
 * only where the conversation may write.
 */
@Service
class SkillTools(
    private val skills: AgentSkillRepository,
    private val catalogs: SkillCatalogRepository,
    /** Lazy, as [DiagramTools] holds its API: the controllers reach the agents, which reach these tools. */
    @param:Lazy private val api: SkillAPI,
    private val web: WebProperties,
    private val address: InstallationAddress,
    private val mapper: ObjectMapper,
) {

    fun specs(scope: OrknuxScope): List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = LIST,
                description = "The skills written in this workspace - how its agents go about particular jobs - " +
                    "with each one's id, key, name, description, catalog and whether it is on. Read one with $READ. " +
                    "An agent reads the skills of the catalogs it is granted, so a skill is put in a catalog the " +
                    "agents that need it hold.",
                parameters = listOf(ToolParameterSpec(CATALOG, "Only this catalog: its name or id.", required = false)),
                summary = "Lists the workspace's skills",
            ),
        )
        add(
            ToolSpec(
                name = READ,
                description = "One skill in full: its markdown, catalog and when and by whom it was last changed.",
                parameters = listOf(ToolParameterSpec(SKILL, "The skill's key, name or id.", required = true)),
                summary = "Reads one skill",
            ),
        )
        if (!scope.mayWrite) return@buildList
        add(
            ToolSpec(
                name = CREATE,
                description = "Writes a new skill in this workspace: a way of doing a job that agents will follow " +
                    "from now on - a convention a person asked to be remembered, the steps a task turned out to " +
                    "need. $FORMAT_SAID Answers its key and link.",
                summary = "Writes a new skill",
                parameters = listOf(
                    ToolParameterSpec("name", "What it is called; unique in the workspace.", required = true),
                    ToolParameterSpec(DESCRIPTION, "When an agent should use it, in a sentence: this is what an agent picks a skill by.", required = true),
                    ToolParameterSpec(CONTENT, "The whole skill as markdown, header included. Left out, it starts from the shape above with its parts to fill.", required = false),
                    ToolParameterSpec(CATALOG, "The catalog to put it in: its name or id. The workspace's first when left out.", required = false),
                    ToolParameterSpec(KEY, "Its key, letters, digits, underscores and hyphens; made from the name when left out.", required = false),
                ),
            ),
        )
        add(
            ToolSpec(
                name = UPDATE,
                description = "Changes a skill: only what you send changes, and $CONTENT replaces the whole markdown. " +
                    "Read it with $READ first. What it replaced is kept as a version a person can restore. " +
                    "Every agent granted its catalog follows the new text from its next turn. $FORMAT_SAID",
                summary = "Changes a skill",
                parameters = listOf(
                    ToolParameterSpec(SKILL, "The skill's key, name or id.", required = true),
                    ToolParameterSpec("name", "A new name for it.", required = false),
                    ToolParameterSpec(DESCRIPTION, "A new description.", required = false),
                    ToolParameterSpec(CONTENT, "The whole new markdown, header included.", required = false),
                    ToolParameterSpec(CATALOG, "Move it to this catalog: its name or id.", required = false),
                ),
            ),
        )
    }

    fun handles(name: String): Boolean = name in NAMES

    fun run(scope: OrknuxScope, name: String, arguments: String): String {
        val asked = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return refuse("The arguments are not JSON")
        return try {
            when (name) {
                LIST -> list(scope, asked)
                READ -> withSkill(scope, asked) { read(scope, it) }
                else -> if (!scope.mayWrite) refuse("This conversation may read what is here, but not change it") else when (name) {
                    CREATE -> create(scope, asked)
                    UPDATE -> withSkill(scope, asked) { update(scope, it, asked) }
                    else -> refuse("There is no tool called $name")
                }
            }
        } catch (refused: RuntimeException) {
            // The API's refusals - a name taken, a header missing - say what to change; pass them on.
            refuse(refused.message ?: "That could not be done")
        }
    }

    private fun list(scope: OrknuxScope, asked: JsonNode): String {
        val only = text(asked.path(CATALOG))?.let { named ->
            catalogCalled(scope, named) ?: return refuse("There is no skill catalog called $named here")
        }
        val held = catalogs.findByWorkspaceIdOrderByNameAsc(scope.workspaceId).filter { only == null || it.id == only }
        val all = api.workspaceSkills(scope.workspaceId, only, 0, MANY).content
        return mapper.writeValueAsString(
            mapOf(
                "catalogs" to held.map { catalog ->
                    mapOf(
                        "id" to catalog.id,
                        "name" to catalog.name,
                        "skills" to all.filter { it.catalogId == catalog.id }.map { view ->
                            mapOf(
                                "id" to view.id,
                                "key" to view.key,
                                "name" to view.name,
                                DESCRIPTION to view.description,
                                "enabled" to view.enabled,
                            )
                        },
                    )
                },
            ),
        )
    }

    private fun read(scope: OrknuxScope, skill: AgentSkill): String {
        val view = requireNotNull(api.skill(requireNotNull(skill.id)))
        return mapper.writeValueAsString(
            linkedMapOf(
                "id" to view.id,
                "key" to view.key,
                "name" to view.name,
                DESCRIPTION to view.description,
                CATALOG to catalogs.findByIdOrNull(view.catalogId)?.name,
                "enabled" to view.enabled,
                "lastModifiedAt" to view.lastModifiedAt,
                "lastModifiedBy" to view.lastModifiedBy,
                CONTENT to view.content,
                "url" to linkTo(scope, view.id),
            ),
        )
    }

    private fun create(scope: OrknuxScope, asked: JsonNode): String {
        val name = text(asked.path("name")) ?: return refuse("What is the skill called? Give it a name.")
        val catalog = text(asked.path(CATALOG))?.let { named ->
            catalogCalled(scope, named) ?: return refuse("There is no skill catalog called $named here")
        }
        val made = api.createSkill(
            CreateSkillInput(
                workspaceId = scope.workspaceId,
                name = name,
                key = text(asked.path(KEY)),
                description = text(asked.path(DESCRIPTION)),
                content = text(asked.path(CONTENT)),
                catalogId = catalog,
            ),
        )
        return mapper.writeValueAsString(
            mapOf(
                "created" to made.name,
                "id" to made.id,
                "key" to made.key,
                CATALOG to catalogs.findByIdOrNull(made.catalogId)?.name,
                "url" to linkTo(scope, made.id),
            ),
        )
    }

    private fun update(scope: OrknuxScope, skill: AgentSkill, asked: JsonNode): String {
        val catalog = text(asked.path(CATALOG))?.let { named ->
            catalogCalled(scope, named) ?: return refuse("There is no skill catalog called $named here")
        }
        val saved = api.updateSkill(
            requireNotNull(skill.id),
            UpdateSkillInput(
                name = text(asked.path("name")),
                description = if (asked.has(DESCRIPTION)) asked.path(DESCRIPTION).asString("") else null,
                content = text(asked.path(CONTENT)),
                catalogId = catalog,
            ),
        )
        return mapper.writeValueAsString(
            mapOf("updated" to saved.name, "id" to saved.id, "key" to saved.key, "url" to linkTo(scope, saved.id)),
        )
    }

    /** The skill [asked] names, by key, name or id, in this workspace only. */
    private fun withSkill(scope: OrknuxScope, asked: JsonNode, then: (AgentSkill) -> String): String {
        val which = text(asked.path(SKILL)) ?: return refuse("Which skill? Give its key, name or id.")
        val held = skills.findByWorkspaceIdAndKeyIgnoreCase(scope.workspaceId, which)
            ?: skills.findByWorkspaceIdAndName(scope.workspaceId, which)
            ?: which.toLongOrNull()?.let { id -> skills.findByIdOrNull(id)?.takeIf { it.workspaceId == scope.workspaceId } }
            ?: return refuse("There is no skill called $which here. $LIST lists them.")
        return then(held)
    }

    private fun catalogCalled(scope: OrknuxScope, named: String): Long? =
        (catalogs.findByWorkspaceIdAndName(scope.workspaceId, named)
            ?: named.toLongOrNull()?.let { id -> catalogs.findByIdOrNull(id)?.takeIf { it.workspaceId == scope.workspaceId } })
            ?.id

    /** Absolute, because a link handed to a model gets pasted where nothing resolves a path. */
    private fun linkTo(scope: OrknuxScope, id: Long?): String =
        address.base().ifEmpty { web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty() } +
            "/workspace/${scope.workspaceId}/skills/$id"

    private fun text(node: JsonNode): String? =
        node.takeIf { it.isString }?.asString()?.trim()?.ifEmpty { null }

    private fun refuse(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    companion object {
        const val LIST = "orknux_workspace_skills"
        const val READ = "orknux_skill"
        const val CREATE = "orknux_create_skill"
        const val UPDATE = "orknux_update_skill"
        val NAMES = setOf(LIST, READ, CREATE, UPDATE)

        private const val SKILL = "skill"
        private const val CATALOG = "catalog"
        private const val DESCRIPTION = "description"
        private const val CONTENT = "content"
        private const val KEY = "key"
        private const val MANY = 500

        /** The shape a skill is checked against, said once for both writing tools. */
        private const val FORMAT_SAID = "A skill is markdown that starts with a header: a line of three hyphens, " +
            "a line `name: ...`, a line `description: ...`, and three hyphens again. Then a `# Title`, an " +
            "`## Objective` saying what an agent following it is trying to achieve, and `## Steps` as a list. " +
            "Write it for an agent that knows nothing of this conversation. One without the header is refused, " +
            "and the refusal says what is wrong."
    }
}
