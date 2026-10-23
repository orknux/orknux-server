package io.mszymanski.orknux.server.transfer

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** What an integrations file says it is. */
const val INTEGRATIONS_FILE_FORMAT: String = "orknux-integrations"

/** The integrations file this installation writes, and the newest it reads; compared only with `>`. */
const val INTEGRATIONS_FILE_VERSION: Int = 1

/** An integrations file, read whole and checked: the rows, as the workspace file's tables read them. */
internal class IntegrationsFileContent(val connections: List<JsonNode>, val mcpServers: List<JsonNode>)

/**
 * Reads an integrations file, refusing it whole when anything in it cannot be
 * held here - every row is read once into a throwaway entity by the same
 * tables the workspace file uses, so a value this installation does not know
 * stops the file before anything is made.
 */
internal fun readIntegrationsFile(mapper: ObjectMapper, content: String): IntegrationsFileContent {
    val root = runCatching { mapper.readTree(content) }.getOrNull()
        ?: throw IntegrationFileRefusedException("That file could not be read as an integrations file.")
    if (root.path("format").asString("") != INTEGRATIONS_FILE_FORMAT) {
        throw IntegrationFileRefusedException("That is not an integrations file.")
    }
    val version = root.path("formatVersion").asInt(0)
    if (version > INTEGRATIONS_FILE_VERSION) {
        throw IntegrationFileRefusedException(
            "That file was written by a newer Orknux (integrations file version $version); this one reads up to $INTEGRATIONS_FILE_VERSION.",
        )
    }
    val connections = root.path("connections").values().toList()
    val mcpServers = root.path("mcpServers").values().toList()
    try {
        connections.forEach { connectionFrom(it, 0, null) }
        mcpServers.forEach { mcpServerFrom(it, 0) }
    } catch (invalid: WorkspaceFileInvalidException) {
        throw IntegrationFileRefusedException("That integrations file cannot be used: ${invalid.says}.")
    }
    return IntegrationsFileContent(connections, mcpServers)
}

/**
 * Connections and MCP servers as templates: Save as template on their rows,
 * and Use a template on the Integrations page. Issue #646.
 *
 * Built the way a template of models is ([ModelTransferAPI]), so the three
 * read as one feature: a template row in the same table holding a small file
 * of its own, written and read by the same field tables the workspace file
 * uses ([CONNECTION_FIELDS], [MCP_SERVER_FIELDS]) - so a template carries
 * exactly what Duplicate carries and leaves behind exactly what it leaves.
 * **No credential travels**, neither a copy nor the variable it is read from,
 * because a variable belongs to the workspace it was made in; a row that held
 * one is marked as needing one, and arrives without it, and the page asks.
 *
 * Publishing is installation-wide, so saving one is an administrator's, and
 * reading the workspace it comes from is checked too. Using one makes rows in
 * one workspace, exactly as adding them by hand would, so it is checked as
 * that is.
 */
@Controller
class IntegrationTemplateAPI(
    private val connections: WorkspaceConnectionRepository,
    private val mcpServers: McpServerRepository,
    private val defaultConnections: ConnectionRepository,
    private val templates: ComponentTemplateService,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
    @Value("\${orknux.version:unknown}") private val version: String,
) {

    /** The templates of connections and MCP servers, for the Integrations page. Signed in, as every template list is. */
    @QueryMapping
    fun integrationTemplates(): List<ComponentTemplateView> {
        access.requireSignedIn()
        return templates.integrationTemplates()
    }

    /** One connection of this workspace, published as a template without its credentials. */
    @MutationMapping
    @Transactional
    fun saveConnectionAsTemplate(
        @Argument workspaceId: Long,
        @Argument id: Long,
        @Argument name: String,
        @Argument description: String?,
    ): ComponentTemplateView {
        access.requireAdmin()
        access.requireVisible(workspaceId)
        val held = connections.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }
            ?: throw IntegrationFileRefusedException("There is no connection $id in this workspace.")
        val root = fileRoot()
        val inherits = held.connectionId?.let { defaultConnections.findByIdOrNull(it)?.name }
        // An inherited connection reads its credential from the installation's default, which a template names rather than carries.
        connectionNode(held, inherits, held.connectionId == null && connectionHadCredential(held), root.putArray("connections").addObject())
        root.putArray("mcpServers")
        val made = templates.create(name, description, mapper.writeValueAsString(root))
        audit.record(null, WorkspaceAuditCategory.WORKSPACE, "Template ${made.name} added from connection ${held.name}")
        return made
    }

    /** One MCP server of this workspace, published as a template without its credential. */
    @MutationMapping
    @Transactional
    fun saveMcpServerAsTemplate(
        @Argument workspaceId: Long,
        @Argument id: Long,
        @Argument name: String,
        @Argument description: String?,
    ): ComponentTemplateView {
        access.requireAdmin()
        access.requireVisible(workspaceId)
        val held = mcpServers.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }
            ?: throw IntegrationFileRefusedException("There is no MCP server $id in this workspace.")
        val root = fileRoot()
        root.putArray("connections")
        mcpServerNode(held, held.secretVariableId != null || !held.secret.isNullOrBlank(), root.putArray("mcpServers").addObject())
        val made = templates.create(name, description, mapper.writeValueAsString(root))
        audit.record(null, WorkspaceAuditCategory.WORKSPACE, "Template ${made.name} added from MCP Server ${held.name}")
        return made
    }

    /**
     * A template of integrations read into this workspace. A row whose name is
     * already taken here is left alone, so using one twice changes nothing; a
     * row that held a credential where it came from is made without one and
     * named in [IntegrationImportView.needsCredential], for the page to ask.
     */
    @MutationMapping
    @Transactional
    fun useIntegrationTemplate(@Argument workspaceId: Long, @Argument id: Long): IntegrationImportView {
        access.requireVisible(workspaceId)
        val template = templates.template(id)?.takeIf { it.ofIntegrations }
            ?: throw IntegrationFileRefusedException("There is no template of integrations $id.")
        val file = readIntegrationsFile(mapper, templates.envelopeOf(template.id))

        val madeConnections = mutableListOf<String>()
        val madeServers = mutableListOf<String>()
        val needs = mutableListOf<IntegrationNeedView>()
        val skipped = mutableListOf<String>()
        file.connections.forEach { node ->
            val name = node.required("name", "A connection")
            if (connections.findByWorkspaceIdAndName(workspaceId, name) != null) {
                skipped += name
                return@forEach
            }
            // As a workspace file reads one: an inherited connection reads the installation's default of that name, if there is one.
            val inherits = node.path("inherits").takeIf { it.isString }?.asString()
            val default = inherits?.let { defaultConnections.findByName(it) }
            val made = connections.save(connectionFrom(node, workspaceId, default?.id))
            madeConnections += made.name
            val needed = node.path("credentialNeeded").asBoolean(false) || (inherits != null && default == null)
            if (default == null && needed) needs += IntegrationNeedView(IntegrationKind.CONNECTION, requireNotNull(made.id), made.name)
            audit.record(workspaceId, WorkspaceAuditCategory.INTEGRATION, "Connection ${made.name} added from template ${template.name}")
        }
        file.mcpServers.forEach { node ->
            val name = node.required("name", "An MCP server")
            if (mcpServers.findByWorkspaceIdAndName(workspaceId, name) != null) {
                skipped += name
                return@forEach
            }
            val made = mcpServers.save(mcpServerFrom(node, workspaceId))
            madeServers += made.name
            if (node.path("credentialNeeded").asBoolean(false)) needs += IntegrationNeedView(IntegrationKind.MCP_SERVER, requireNotNull(made.id), made.name)
            audit.record(workspaceId, WorkspaceAuditCategory.INTEGRATION, "MCP Server ${made.name} added from template ${template.name}")
        }
        return IntegrationImportView(madeConnections, madeServers, needs, skipped)
    }

    private fun fileRoot(): tools.jackson.databind.node.ObjectNode {
        val root = mapper.createObjectNode()
        root.put("format", INTEGRATIONS_FILE_FORMAT)
        root.put("formatVersion", INTEGRATIONS_FILE_VERSION)
        // Only ever quoted back in a message; nothing branches on it.
        root.put("producedBy", "Orknux $version")
        root.put("exportedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        return root
    }
}

/** Which of the two an integration is. */
enum class IntegrationKind { CONNECTION, MCP_SERVER }

/** A row a used template made without the credential it held where it came from. */
data class IntegrationNeedView(val kind: IntegrationKind, val id: Long, val name: String)

/** What using a template of integrations made, and what it left alone. */
data class IntegrationImportView(
    /** Connections made, by name. */
    val connections: List<String>,
    /** MCP servers made, by name. */
    val mcpServers: List<String>,
    /** Of those, the ones that need a credential added here. */
    val needsCredential: List<IntegrationNeedView>,
    /** Rows left alone because this workspace already has one of that name. */
    val skipped: List<String>,
)

/** Why a template of integrations was not saved or used, in the words the page shows. */
class IntegrationFileRefusedException(reason: String) : RuntimeException(reason)

@Component
class IntegrationTemplateExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is IntegrationFileRefusedException -> ErrorType.BAD_REQUEST
            else -> return null
        }
        return refused(exception, errorType, environment)
    }
}
