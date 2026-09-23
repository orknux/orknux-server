package io.mszymanski.orknux.server.variable

import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import io.mszymanski.orknux.server.workspace.sortBy
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter

/**
 * A workspace's variables: named values its functions are handed.
 *
 * The value goes in and never comes back. Nothing here returns it, and no screen
 * asks for it — what a variable is *for* is being read by a function inside the
 * sandbox, which is the one place it is needed. Changing a variable means
 * writing a new value over the old one rather than editing what is there.
 */
@Controller
class VariableAPI(
    private val variables: WorkspaceVariableRepository,
    private val catalogs: VariableCatalogRepository,
    /**
     * Asked what still reads a variable before one is removed or made readable.
     *
     * Five sources behind one call: a function taking it as an external
     * parameter, an action whose headers read it, and the model provider,
     * connection and MCP server that authenticate with it — because a credential
     * is not one card's any more. Since #244 every secret field in the product
     * may read a workspace secret, and a guard that only knows about model
     * providers is a guard that lets somebody delete the variable a Slack
     * connection posts with.
     *
     * Asked here rather than assembled here, so that the sentence this refuses
     * with and the list the variable's own screen draws are the same rows.
     */
    private val dependants: ComponentDependants,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    /** The types plugins define, and the check a value of one gets. Issue #377. */
    private val pluginTypes: io.mszymanski.orknux.server.plugin.PluginTypes,
    private val mapper: tools.jackson.databind.ObjectMapper,
) {

    /**
     * The types a variable may be beyond the three built in. Issue #377.
     *
     * Every enabled plugin's, whatever the workspace: a type is a plugin's
     * vocabulary, not a workspace's, and the workspace decides what it holds
     * when it points a variable at one.
     */
    @QueryMapping
    fun variableTypes(@Argument workspaceId: Long): List<io.mszymanski.orknux.server.plugin.VariableTypeOffer> {
        requireWorkspaceAccess(workspaceId)
        return pluginTypes.offered()
    }

    /**
     * What the plugin that defines a type offers for what was typed so far.
     *
     * @param arguments what the variable is told, as a JSON object - the
     *   connection to look in, for a Slack user.
     */
    @QueryMapping
    fun variableSuggestions(
        @Argument workspaceId: Long,
        @Argument type: String,
        @Argument arguments: String?,
        @Argument typed: String,
    ): List<io.mszymanski.orknux.server.plugin.VariableSuggestion> {
        requireWorkspaceAccess(workspaceId)
        return pluginTypes.suggest(workspaceId, type, argumentsOf(arguments), typed)
    }

    /** The workspace's catalogs, by name, each with what it holds. */
    @QueryMapping
    fun variableCatalogs(@Argument workspaceId: Long): List<VariableCatalogView> {
        requireWorkspaceAccess(workspaceId)
        return catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map(::describe)
    }

    /**
     * The columns this list can be put in the order of. Issue #358.
     *
     * The Value column is not one. A variable's value is a secret more often
     * than not - it is stored encrypted and never handed back - so there is
     * nothing to order the rows by and nothing an order would mean.
     */
    private val VARIABLE_ORDERS = mapOf(
        "NAME" to listOf("name"),
        "DESCRIPTION" to listOf("description", "name"),
        "TYPE" to listOf("type", "name"),
    )

    /**
     * One page of variables: a catalog's, or the whole workspace's.
     *
     * @param catalogId which catalog to look in; omitted, every variable the
     *   workspace holds, which is what a search across catalogs wants.
     * @param search a name, or part of one; blank is no filter rather than a
     *   search for nothing.
     */
    @QueryMapping
    fun workspaceVariables(
        @Argument workspaceId: Long,
        @Argument catalogId: Long?,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): VariablePage {
        requireWorkspaceAccess(workspaceId)
        val looking = search?.trim().orEmpty()
        val pageable = pageRequest(page, size, sortBy(order, ascending, VARIABLE_ORDERS, "NAME"))

        val catalog = catalogId?.let { requireCatalog(it, workspaceId) }
        val held = when {
            catalog == null && looking.isEmpty() -> variables.findByWorkspaceId(workspaceId, pageable)
            catalog == null -> variables.findByWorkspaceIdAndNameContainingIgnoreCase(
                workspaceId,
                looking,
                pageable,
            )

            looking.isEmpty() -> variables.findByCatalogId(requireNotNull(catalog.id), pageable)
            else -> variables.findByCatalogIdAndNameContainingIgnoreCase(
                requireNotNull(catalog.id),
                looking,
                pageable,
            )
        }
        return VariablePage(held, ::describe)
    }

    @MutationMapping
    @Transactional
    fun createVariableCatalog(@Argument workspaceId: Long, @Argument name: String): VariableCatalogView {
        requireWorkspaceAccess(workspaceId)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw VariableCatalogNameInvalidException()
        if (catalogs.findByWorkspaceIdAndName(workspaceId, trimmed) != null) {
            throw VariableCatalogNameTakenException(trimmed)
        }

        val saved = catalogs.save(
            VariableCatalog(workspaceId = workspaceId, name = trimmed, createdBy = currentUser()),
        )
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Catalog $trimmed created")
        return describe(saved)
    }

    @MutationMapping
    @Transactional
    fun renameVariableCatalog(@Argument id: Long, @Argument name: String): VariableCatalogView {
        val catalog = catalogs.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw VariableCatalogNotFoundException(id)

        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw VariableCatalogNameInvalidException()
        if (trimmed != catalog.name && catalogs.findByWorkspaceIdAndName(catalog.workspaceId, trimmed) != null) {
            throw VariableCatalogNameTakenException(trimmed)
        }

        val previous = catalog.name
        catalog.name = trimmed
        auditRecorder.record(
            catalog.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Catalog $previous renamed to $trimmed",
        )
        return describe(catalog)
    }

    /**
     * Removes an empty catalog.
     *
     * Never its contents with it: a catalog is a folder, and what is in it is
     * somebody's secret that something may be built on. Emptying it is a
     * decision to make one variable at a time.
     */
    @MutationMapping
    @Transactional
    fun deleteVariableCatalog(@Argument id: Long): Boolean {
        val catalog = catalogs.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val held = variables.countByCatalogId(id)
        if (held > 0) throw VariableCatalogNotEmptyException(catalog.name, held)

        catalogs.delete(catalog)
        auditRecorder.record(catalog.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Catalog ${catalog.name} removed")
        return true
    }

    @QueryMapping
    fun variable(@Argument id: Long): VariableView? {
        val variable = variables.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(variable)
    }

    @MutationMapping
    @Transactional
    fun createVariable(@Argument input: CreateVariableInput): VariableView {
        requireWorkspaceAccess(input.workspaceId)
        val catalog = requireCatalog(input.catalogId, input.workspaceId)
        val name = requireNameable(input.name)
        if (variables.findByCatalogIdAndName(input.catalogId, name) != null) {
            throw VariableNameTakenException(name, catalog.name)
        }

        val variable = WorkspaceVariable(
            workspaceId = input.workspaceId,
            catalogId = input.catalogId,
            name = name,
            description = input.description?.trim()?.ifEmpty { null },
            type = input.type,
            kind = input.kind,
            elementType = input.elementType?.takeIf { input.type == VariableType.LIST },
            customType = input.customType?.trim()?.ifEmpty { null },
            typeArguments = input.typeArguments?.trim()?.ifEmpty { null },
            value = input.value?.takeIf { it.isNotEmpty() },
            createdBy = currentUser(),
            lastModifiedBy = currentUser(),
        )
        requireHoldable(variable)
        val saved = variables.save(variable)
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Variable $name created")
        return describe(saved)
    }

    /**
     * Backs the variable form: the name, the type, and a new value.
     *
     * A null value leaves what is stored alone, because the form cannot show it
     * and so cannot send it back — asking somebody to retype a secret to rename
     * a variable would be a good way to have them stop using variables.
     */
    @MutationMapping
    @Transactional
    fun updateVariable(@Argument id: Long, @Argument input: UpdateVariableInput): VariableView {
        val variable = variables.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw VariableNotFoundException(id)

        // Moved first, so a name is checked against the catalog it is going to
        // rather than the one it is leaving.
        input.catalogId?.let { variable.catalogId = requireCatalog(it, variable.workspaceId).id ?: variable.catalogId }

        val previousName = variable.name
        input.name?.let { said ->
            val name = requireNameable(said)
            val clash = variables.findByCatalogIdAndName(variable.catalogId, name)
            if (name != variable.name && clash != null && clash.id != variable.id) {
                throw VariableNameTakenException(name, catalogName(variable.catalogId))
            }
            variable.name = name
        }
        input.description?.let { variable.description = it.trim().ifEmpty { null } }
        input.type?.let { variable.type = it }
        input.elementType?.let { variable.elementType = it }
        if (variable.type != VariableType.LIST) variable.elementType = null
        // An empty string takes the type off; only an absent one leaves it alone.
        input.customType?.let { variable.customType = it.trim().ifEmpty { null } }
        input.typeArguments?.let { variable.typeArguments = it.trim().ifEmpty { null } }
        input.kind?.let { kind ->
            // A VALUE is returned with the listing, so a credential turned into
            // one is a credential on a screen. The other end of the rule that
            // refuses to bind a provider to anything but a SECRET.
            if (kind != VariableKind.SECRET && variable.kind == VariableKind.SECRET) {
                val credentialOf = credentialOf(variable)
                if (credentialOf.isNotEmpty()) throw VariableSecrecyHeldException(variable.name, credentialOf)
            }
            variable.kind = kind
        }
        // An empty string clears what is stored; only an absent value leaves it alone.
        input.value?.let { variable.value = it.ifEmpty { null } }
        requireHoldable(variable)
        variable.lastModifiedAt = java.time.OffsetDateTime.now()
        variable.lastModifiedBy = currentUser()

        val message = if (previousName == variable.name) {
            "Variable ${variable.name} updated"
        } else {
            "Variable $previousName renamed to ${variable.name}"
        }
        auditRecorder.record(variable.workspaceId, WorkspaceAuditCategory.WORKFLOW, message)
        return describe(variable)
    }

    /**
     * Shows what a secret holds, once, to somebody who asked.
     *
     * Nothing else returns it — not the list, not the form — because a value on
     * screen is a value in a screenshot, a recording, or the shoulder of the
     * person walking past. Asking for it is a deliberate act, so it is recorded
     * as one: the audit log says who looked and when, which is the whole of what
     * makes revealing it safe to offer.
     */
    @MutationMapping
    @Transactional
    fun revealVariable(@Argument id: Long): String? {
        val variable = variables.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw VariableNotFoundException(id)

        auditRecorder.record(
            variable.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Variable ${variable.name} revealed",
        )
        return variable.value
    }

    /**
     * Removes it, unless a function is built on it.
     *
     * An external parameter is part of a function's signature, so taking the
     * variable away would silently change what that function is handed. Said out
     * loud, with the functions named, rather than cascading.
     */
    @MutationMapping
    @Transactional
    fun deleteVariable(@Argument id: Long): Boolean {
        val variable = variables.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        /*
         * An action's header reads a variable the same way a function's external
         * parameter does, so it holds it in place the same way. Read out of the
         * JSON rather than joined to, because that is where the reference is
         * kept - there is no column for a foreign key to guard, so the guard is
         * here or it is nowhere, and nowhere means a header that names a variable
         * nobody can find and a request that fails at three in the morning.
         */
        val usedBy = dependants.signatureOfVariable(id)
        if (usedBy.isNotEmpty()) throw VariableInUseException(variable.name, usedBy.phrases())

        /*
         * And anything reading it for a credential holds it in place too - see
         * [VariableHeldAsCredentialException] for why that is a refusal rather
         * than a warning. Asked of the connection module, which owns all three
         * kinds of holder, rather than joined to: there is no foreign key across
         * that boundary and there is not meant to be one.
         */
        val credentialOf = credentialOf(variable)
        if (credentialOf.isNotEmpty()) throw VariableHeldAsCredentialException(variable.name, credentialOf)

        variables.delete(variable)
        auditRecorder.record(
            variable.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Variable ${variable.name} removed",
        )
        return true
    }

    /**
     * Everything reading this variable for a credential, each named the way a
     * sentence would name it.
     *
     * One list rather than three, and the noun is carried by the entry rather
     * than by the sentence around it: "the connection Slack, the MCP server
     * brave-search" reads, and a bare "Slack, brave-search" leaves whoever hit
     * the refusal to go and find out what those are. The order is by kind and
     * then by name, so two runs say the same thing.
     *
     * The sentence is assembled here and the rows come from
     * [ComponentDependants], which is the same set the variable's own screen
     * lists as links.
     */
    private fun credentialOf(variable: WorkspaceVariable): List<String> =
        dependants.credentialOfVariable(requireNotNull(variable.id)).phrases()

    /**
     * A name a function can receive as an argument.
     *
     * The value arrives inside the sandbox as a parameter of that name, so
     * anything that is not an identifier would be a variable that can be made
     * and never used.
     */
    /**
     * Whether what the variable holds is what its type says. Issue #377.
     *
     * Refused at the save rather than found by a function at three in the
     * morning. A number has to parse, a boolean has to be one, a list has to
     * be a JSON array of its element type - and a value of a plugin's type is
     * put to the plugin, whose refusal carries its own reason. A plugin that
     * could not be asked is not a refusal: the value is kept and the log says
     * it was not checked, because a Slack outage should not stop somebody
     * saving a channel they can see.
     */
    private fun requireHoldable(variable: WorkspaceVariable) {
        if (variable.type == VariableType.LIST && variable.elementType == VariableType.LIST) {
            throw VariableValueInvalidException(variable.name, "a list of lists has no shape a function could be handed")
        }
        val base = if (variable.type == VariableType.LIST) variable.elementType ?: VariableType.STRING else variable.type

        val custom = variable.customType
        val defined = custom?.let { pluginTypes.find(it) ?: throw VariableTypeUnknownException(it) }
        if (defined != null && !defined.second.base.equals(base.name, ignoreCase = true)) {
            throw VariableValueInvalidException(
                variable.name,
                "$custom is a ${defined.second.base} underneath, and this variable is a ${base.name.lowercase()}",
            )
        }

        val value = variable.value ?: return
        val elements = if (variable.type == VariableType.LIST) {
            val parsed = runCatching { mapper.readTree(value) }.getOrNull()
            if (parsed == null || !parsed.isArray) {
                throw VariableValueInvalidException(variable.name, "a list is written as a JSON array, like [\"a\", \"b\"]")
            }
            parsed.values().map { if (it.isTextual) it.asString() else it.toString() }
        } else {
            listOf(value)
        }

        elements.forEach { one ->
            when (base) {
                VariableType.NUMBER -> one.trim().toBigDecimalOrNull()
                    ?: throw VariableValueInvalidException(variable.name, "\"$one\" is not a number")
                VariableType.BOOLEAN -> one.trim().lowercase().toBooleanStrictOrNull()
                    ?: throw VariableValueInvalidException(variable.name, "\"$one\" is neither true nor false")
                else -> Unit
            }
            if (custom != null) {
                val check = pluginTypes.check(variable.workspaceId, custom, argumentsOf(variable.typeArguments), one)
                if (!check.ok) throw VariableValueInvalidException(variable.name, check.reason ?: "$custom refused it")
                if (!check.checked) log.warn("{} was saved unchecked: {}", variable.name, check.reason)
            }
        }
    }

    /** What a variable was told, read off the JSON it is kept as; numbers and booleans as text. */
    private fun argumentsOf(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyMap()
        if (!node.isObject) return emptyMap()
        return node.properties().associate { (name, held) ->
            name to (if (held.isTextual) held.asString() else held.toString())
        }
    }

    private fun requireNameable(said: String): String {
        val name = said.trim()
        if (!NAME.matches(name)) throw VariableNameInvalidException(name)
        return name
    }

    private fun describe(catalog: VariableCatalog) = VariableCatalogView(
        id = requireNotNull(catalog.id),
        workspaceId = catalog.workspaceId,
        name = catalog.name,
        variableCount = variables.countByCatalogId(requireNotNull(catalog.id)).toInt(),
        createdAt = catalog.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        createdBy = catalog.createdBy,
    )

    private fun describe(variable: WorkspaceVariable) = VariableView(
        id = requireNotNull(variable.id),
        workspaceId = variable.workspaceId,
        catalogId = variable.catalogId,
        catalogName = catalogName(variable.catalogId),
        name = variable.name,
        description = variable.description,
        type = variable.type,
        kind = variable.kind,
        elementType = variable.elementType,
        customType = variable.customType,
        typeArguments = variable.typeArguments,
        // A value is read with the list; a secret is not, and asking for one is
        // what `revealVariable` is — recorded, deliberate, one at a time.
        value = variable.value.takeIf { variable.kind == VariableKind.VALUE },
        valueSet = !variable.value.isNullOrEmpty(),
        createdAt = variable.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        createdBy = variable.createdBy,
        lastModifiedAt = variable.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = variable.lastModifiedBy,
    )

    /** The catalog, and that it is this workspace's rather than another's. */
    private fun requireCatalog(id: Long, workspaceId: Long): VariableCatalog {
        val catalog = catalogs.findByIdOrNull(id) ?: throw VariableCatalogNotFoundException(id)
        if (catalog.workspaceId != workspaceId) throw VariableCatalogNotFoundException(id)
        return catalog
    }

    /** For a view; a catalog that has gone is not a reason to fail reading a list. */
    private fun catalogName(id: Long): String = catalogs.findByIdOrNull(id)?.name ?: "—"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val log = org.slf4j.LoggerFactory.getLogger(VariableAPI::class.java)
    }
}

data class CreateVariableInput(
    val workspaceId: Long,
    /** Which catalog holds it; every variable is in one. */
    val catalogId: Long,
    val name: String,
    /** What it is for, since the name has to be an identifier. */
    val description: String? = null,
    val type: VariableType,
    /** Whether it may be read with the list, or only on request. */
    val kind: VariableKind = VariableKind.SECRET,
    /** What a LIST holds; ignored on anything else. Issue #377. */
    val elementType: VariableType? = null,
    /** A plugin's type, `slack:SlackUser`, over the base type. */
    val customType: String? = null,
    /** What that type is told, as a JSON object. */
    val typeArguments: String? = null,
    /** What it holds; a variable may be made before its value is known. */
    val value: String? = null,
)

data class UpdateVariableInput(
    /** Moves it to another catalog; null leaves it where it is. */
    val catalogId: Long? = null,
    val name: String? = null,
    val description: String? = null,
    val type: VariableType? = null,
    /** Whether it may be read with the list, or only on request. */
    val kind: VariableKind? = null,
    val elementType: VariableType? = null,
    /** Empty takes the plugin type off; absent leaves it alone. */
    val customType: String? = null,
    val typeArguments: String? = null,
    /** Null leaves the stored value alone; a secret's form cannot show it to send it back. */
    val value: String? = null,
)

/** A folder of variables, as the list beside them shows it. */
data class VariableCatalogView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    /** What the count badge shows, so an empty catalog reads as empty. */
    val variableCount: Int,
    val createdAt: String,
    val createdBy: String,
)

data class VariableView(
    val id: Long,
    val workspaceId: Long,
    val catalogId: Long,
    val catalogName: String,
    val name: String,
    val description: String?,
    val type: VariableType,
    val kind: VariableKind,
    /** What a LIST holds; null on anything else. Issue #377. */
    val elementType: VariableType?,
    /** A plugin's type over the base type, `slack:SlackUser`, or null. */
    val customType: String?,
    /** What that type was told, as a JSON object. */
    val typeArguments: String?,
    /** What it holds, on a value. Null on a secret, whatever is stored. */
    val value: String?,
    /** Whether anything is stored, which is all a secret says about itself. */
    val valueSet: Boolean,
    /** ISO-8601 offset date-time. */
    val createdAt: String,
    /** Who put it there; the person who knows what it is for. */
    val createdBy: String,
    /** ISO-8601 offset date-time. */
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

data class VariablePage(
    val content: List<VariableView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkspaceVariable>, describe: (WorkspaceVariable) -> VariableView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
