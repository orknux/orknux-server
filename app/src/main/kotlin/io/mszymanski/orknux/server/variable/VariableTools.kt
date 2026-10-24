package io.mszymanski.orknux.server.variable

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The workspace's variables, for an agent granted their catalogs.
 *
 * Two grants, because they are two different promises. A variable catalog's
 * plain values are read: [GET] answers one, and nothing hides it afterwards. A
 * secret catalog's secrets are *used*: the model learns a secret's name, asks
 * for it by name on `orkbox_exec`, and the server hands the value to the box
 * command as an environment variable - so it never passes through the model,
 * never sits in a command line, and is masked wherever what the box printed is
 * shown ([io.mszymanski.orknux.server.box.BoxSecrets]).
 *
 * Every call reads the grant from the row rather than from the agent the turn
 * loaded, for the reason `Boxes.labelsNow` does: a turn can run for an hour,
 * and a catalog taken away part way through is taken away.
 */
@Service
class VariableTools(
    private val agents: AgentRepository,
    private val catalogs: VariableCatalogRepository,
    private val variables: WorkspaceVariableRepository,
    private val mapper: ObjectMapper,
    /** The catalogs other workspaces lend this one. Lazy: sharing reads agents, which read this. */
    @param:org.springframework.context.annotation.Lazy
    private val sharing: io.mszymanski.orknux.server.sharing.WorkspaceSharing,
) {

    /** One variable this agent may use, with the catalog it came from. */
    data class Granted(val id: Long, val catalog: String, val name: String, val value: String?)

    /** Offered while the agent holds any secret or variable catalog. */
    fun offered(agent: Agent): Boolean = agent.secretCatalogs.isNotEmpty() || agent.variableCatalogs.isNotEmpty()

    fun handles(name: String): Boolean = name in NAMES

    fun specs(): List<ToolSpec> = SPECS

    fun run(agent: Agent, name: String, arguments: String): String = try {
        val args = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: throw Refused("The arguments were not a JSON object")
        when (name) {
            LIST -> list(agent)
            GET -> get(agent, args.path("name").let { if (it.isString) it.stringValue().trim() else "" })
            else -> throw Refused("There is no tool called $name")
        }
    } catch (refused: Refused) {
        mapper.writeValueAsString(mapOf("error" to refused.message))
    }

    /**
     * The secrets [names] name, with their values, for the server to hand to a
     * box - refused whole when one is not a secret this agent may use, so a
     * command never starts with half its credentials.
     */
    fun secrets(agent: Agent, names: List<String>): List<Granted> =
        names.map { resolve(agent, it, VariableKind.SECRET) }

    /** The plain values [names] name, the same way. */
    fun values(agent: Agent, names: List<String>): List<Granted> =
        names.map { resolve(agent, it, VariableKind.VALUE) }

    private fun list(agent: Agent): String {
        val secretCatalogs = catalogsNow(agent, VariableKind.SECRET)
        val valueCatalogs = catalogsNow(agent, VariableKind.VALUE)
        if (secretCatalogs.isEmpty() && valueCatalogs.isEmpty()) throw Refused("This agent has not been given any variable catalogs")
        val listed = buildList {
            held(agent.workspaceId, valueCatalogs, VariableKind.VALUE).forEach { (catalog, variable) ->
                add(
                    buildMap {
                        put("name", variable.name)
                        put("catalog", catalog.name)
                        put("kind", "value")
                        put("type", variable.type.name.lowercase())
                        variable.description?.let { put("description", it) }
                        put("value", variable.value)
                    },
                )
            }
            held(agent.workspaceId, secretCatalogs, VariableKind.SECRET).forEach { (catalog, variable) ->
                add(
                    buildMap {
                        put("name", variable.name)
                        put("catalog", catalog.name)
                        put("kind", "secret")
                        put("type", variable.type.name.lowercase())
                        variable.description?.let { put("description", it) }
                        put("set", !variable.value.isNullOrEmpty())
                    },
                )
            }
        }
        return mapper.writeValueAsString(
            mapOf(
                "variables" to listed,
                "note" to "A secret's value is never shown to you. To use one, name it in orkbox_exec's secrets " +
                    "(or orkbox_open's): the box command gets it as an environment variable of the same name, " +
                    "such as \$GITHUB_TOKEN, and anything that prints it is masked as ***. Name a variable as " +
                    "Catalog/NAME where two catalogs hold the same name.",
            ),
        )
    }

    private fun get(agent: Agent, name: String): String {
        if (name.isEmpty()) throw Refused("Say which variable: a name from variable_list")
        val found = resolve(agent, name, VariableKind.VALUE)
        return mapper.writeValueAsString(mapOf("name" to found.name, "catalog" to found.catalog, "value" to found.value))
    }

    /**
     * One variable by name - `NAME`, or `Catalog/NAME` where two granted
     * catalogs hold the same one - of the kind asked for, in a catalog granted
     * for that kind. Asking for a secret as a value is told where secrets go
     * rather than told it does not exist: the model would otherwise conclude it
     * cannot be done.
     */
    private fun resolve(agent: Agent, asked: String, kind: VariableKind): Granted {
        val said = asked.trim()
        val catalogName = said.substringBefore('/', "").trim().ifEmpty { null }
        val name = said.substringAfter('/').trim()
        val granted = catalogsNow(agent, kind)
        val candidates = held(agent.workspaceId, granted, kind)
            .filter { (catalog, variable) -> variable.name == name && (catalogName == null || catalog.name == catalogName) }
        if (candidates.size > 1) {
            throw Refused("Two catalogs hold $name: say which, as " + candidates.joinToString(" or ") { "${it.first.name}/$name" })
        }
        val (catalog, variable) = candidates.singleOrNull() ?: run {
            val otherKind = if (kind == VariableKind.VALUE) VariableKind.SECRET else VariableKind.VALUE
            val asOther = held(agent.workspaceId, catalogsNow(agent, otherKind), otherKind)
                .any { (catalog, variable) -> variable.name == name && (catalogName == null || catalog.name == catalogName) }
            throw Refused(
                when {
                    asOther && kind == VariableKind.VALUE ->
                        "$said is a secret, and a secret's value is never shown. Name it in orkbox_exec's secrets and use it as \$$name in the command."
                    asOther -> "$said is a plain value, not a secret: read it with variable_get, or name it in orkbox_exec's values."
                    else -> "There is no ${if (kind == VariableKind.SECRET) "secret" else "variable"} called $said that you may use. variable_list lists them."
                },
            )
        }
        return Granted(requireNotNull(variable.id), catalog.name, variable.name, variable.value)
    }

    /** The catalogs granted for [kind], read from the row now. */
    private fun catalogsNow(agent: Agent, kind: VariableKind): Set<String> {
        val id = agent.id?.takeIf(agents::existsById)
        return when (kind) {
            VariableKind.SECRET -> id?.let(agents::secretCatalogsOf) ?: agent.secretCatalogs
            VariableKind.VALUE -> id?.let(agents::variableCatalogsOf) ?: agent.variableCatalogs
        }.toSet()
    }

    /**
     * The workspace's variables of [kind] in the catalogs named, each with its
     * catalog - its own by name, and a catalog another workspace lends it by
     * `share:<id>`, only while the share stands.
     */
    private fun held(workspaceId: Long, granted: Set<String>, kind: VariableKind): List<Pair<VariableCatalog, WorkspaceVariable>> =
        if (granted.isEmpty()) {
            emptyList()
        } else {
            val own = catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).filter { it.name in granted }
            val lent = sharing.catalogsLentTo(workspaceId)
                .filter { (share, _) -> sharing.grantOf(share) in granted }
                .map { it.second }
            (own + lent).flatMap { catalog ->
                variables.findByCatalogId(requireNotNull(catalog.id)).filter { it.kind == kind }.map { catalog to it }
            }
        }

    /** A refusal the model is told in words, with nothing else going wrong. */
    class Refused(override val message: String) : RuntimeException(message)

    companion object {
        const val LIST = "variable_list"
        const val GET = "variable_get"

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                LIST,
                "Lists the workspace variables you may use: plain values with their value, and secrets by name only. " +
                    "A secret is used, never read - name it in orkbox_exec's or orkbox_open's secrets and the command " +
                    "gets it as an environment variable.",
            ),
            ToolSpec(
                GET,
                "Reads one plain workspace variable's value. Secrets cannot be read; name them in orkbox_exec's secrets instead.",
                listOf(ToolParameterSpec("name", "The variable's name from variable_list, or Catalog/NAME where two catalogs hold it.", true)),
            ),
            // Both only read: a secret is never even shown, let alone changed. Issue #653.
        ).map { it.copy(readOnly = true) }

        val NAMES: Set<String> = SPECS.map { it.name }.toSet()
    }
}
