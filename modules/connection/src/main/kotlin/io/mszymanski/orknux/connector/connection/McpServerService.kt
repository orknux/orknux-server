package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.Addresses
import io.mszymanski.orknux.connector.CredentialReader
import io.mszymanski.orknux.connector.security.HeldSecret
import io.mszymanski.orknux.connector.security.SecretReferences
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The MCP servers a workspace's agents may connect to. */
@Service
class McpServerService(
    private val servers: McpServerRepository,
    /**
     * The rule a secret field follows when it may keep its own value or read a
     * workspace one - see [SecretReferences].
     */
    private val references: SecretReferences,
    /** What answers the check: the same client an agent's tool call goes through. */
    private val client: McpClient,
) {

    fun mcpServers(workspaceId: Long): List<McpServerView> =
        servers.findByWorkspaceId(workspaceId, Sort.by("name")).map(::view)

    fun mcpServer(id: Long): McpServerView? =
        servers.findByIdOrNull(id)?.let(::view)

    /**
     * The MCP servers in this workspace reading [variableId].
     *
     * What `VariableAPI` asks before it removes a variable or takes its secrecy
     * away. A [CredentialReader] rather than the row: the answer is read by
     * somebody, and a server row is a credential holder this has no business
     * handing out. The id travels with the name so that whoever is told about it
     * can open it — see [CredentialReader].
     */
    fun serversReading(workspaceId: Long, variableId: Long): List<CredentialReader> =
        servers.findByWorkspaceIdAndSecretVariableId(workspaceId, variableId)
            .map { CredentialReader(requireNotNull(it.id), it.name) }
            .sortedBy { it.name }

    /**
     * Asks the server whether it is there, and says what it answered.
     *
     * The same handshake and the same `tools/list` an agent makes, deliberately
     * so: a check that proved something the real path does not do proves
     * nothing. It is where the reason for a failure gets said out loud - until
     * now the only way to find out that a token had expired was to grant the
     * server to an agent and watch a conversation quietly lose a capability.
     *
     * Counting the tools rather than listing them. What somebody pressing this
     * needs to know is that the address, the credential and the protocol all
     * work; the tools themselves are on the screen already.
     */
    fun checkMcpServer(id: Long): McpServerCheck {
        val server = servers.findByIdOrNull(id) ?: throw McpServerNotFoundException(id)
        val check = runCheck(server)
        record(server, check)
        // One statement, after the network conversation rather than around it, so
        // no transaction is held open while a remote server takes its time.
        servers.save(server)
        return check
    }

    /**
     * The check itself, without recording it. The handshake and `tools/list` an
     * agent makes, deliberately the same: a check that proved something the real
     * path does not do proves nothing. It counts the tools rather than listing
     * them - what a check has to establish is that the address, credential and
     * protocol work; the tools are on the screen already.
     */
    private fun runCheck(server: McpServer): McpServerCheck = when (val listing = client.tools(server)) {
        is McpListing.Tools -> {
            log.info("MCP server {} answered a check with {} tool(s)", server.name, listing.tools.size)
            McpServerCheck(
                reachable = true,
                detail = when (listing.tools.size) {
                    0 -> "Connected. The server offers no tools."
                    1 -> "Connected. The server offers one tool."
                    else -> "Connected. The server offers ${listing.tools.size} tools."
                },
                tools = listing.tools.size,
            )
        }

        is McpListing.Failed -> {
            log.info("MCP server {} failed a check: {}", server.name, listing.reason)
            McpServerCheck(reachable = false, detail = listing.reason, tools = null)
        }
    }

    /** Writes a check onto the entity, so the list can show it. The caller saves. */
    private fun record(server: McpServer, check: McpServerCheck) {
        server.lastCheckedAt = java.time.OffsetDateTime.now()
        server.reachable = check.reachable
        server.checkDetail = check.detail.take(DETAIL_LENGTH)
        server.toolCount = check.tools
    }

    /**
     * The same check on a server not yet saved: the add dialog's Test. Built
     * from what the form holds and never saved, so it costs nothing to try an
     * address or a credential before the server exists - and nothing about the
     * check is recorded, there being no server to record it on.
     */
    fun checkMcpServerDraft(input: CreateMcpServerInput): McpServerCheck {
        val address = Addresses.withScheme(input.address)
        if (address.isEmpty()) throw McpServerAddressInvalidException()
        val own = input.secret?.trim()?.ifEmpty { null }
        val variable = references.bind(input.workspaceId, input.secretVariableId, own)
        return runCheck(
            McpServer(
                workspaceId = input.workspaceId,
                name = input.name.trim().ifEmpty { "draft" },
                address = address,
                authType = input.authType ?: AuthType.NONE,
                secret = if (variable == null) own else null,
                secretVariableId = variable,
                headers = input.headers.orEmpty().toHttpHeaders(),
            ),
        )
    }

    @Transactional
    fun createMcpServer(input: CreateMcpServerInput): McpServerView {
        val name = input.name.trim()
        val address = Addresses.withScheme(input.address)
        if (name.isEmpty()) throw McpServerNameInvalidException()
        if (address.isEmpty()) throw McpServerAddressInvalidException()
        if (servers.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw McpServerNameTakenException(name)

        val own = input.secret?.trim()?.ifEmpty { null }
        val variable = references.bind(input.workspaceId, input.secretVariableId, own)

        val server = servers.save(
            McpServer(
                workspaceId = input.workspaceId,
                name = name,
                address = address,
                authType = input.authType ?: AuthType.NONE,
                secret = if (variable == null) own else null,
                secretVariableId = variable,
                headers = input.headers.orEmpty().toHttpHeaders(),
            ),
        )
        return view(server)
    }

    /** Backs the MCP server settings form; a null secret keeps the stored one. */
    @Transactional
    fun updateMcpServer(id: Long, input: UpdateMcpServerInput): McpServerView {
        val server = servers.findByIdOrNull(id) ?: throw McpServerNotFoundException(id)

        val name = input.name.trim()
        val address = Addresses.withScheme(input.address)
        if (name.isEmpty()) throw McpServerNameInvalidException()
        if (address.isEmpty()) throw McpServerAddressInvalidException()
        if (name != server.name && servers.findByWorkspaceIdAndName(server.workspaceId, name) != null) {
            throw McpServerNameTakenException(name)
        }

        server.name = name
        server.address = address
        input.authType?.let { server.authType = it }

        // A value given keeps a copy and drops any reference; a variable given
        // reads it and drops any copy; nothing given leaves the field alone,
        // which is what a masked box sends.
        val own = input.secret?.trim()
        val variable = references.bind(server.workspaceId, input.secretVariableId, own?.ifEmpty { null })
        when {
            variable != null -> {
                server.secretVariableId = variable
                server.secret = null
            }

            own != null -> {
                server.secret = own.ifEmpty { null }
                server.secretVariableId = null
            }
        }
        input.headers?.let { server.headers = it.toHttpHeaders() }
        return view(server)
    }

    @Transactional
    fun removeMcpServer(id: Long): Boolean {
        val server = servers.findByIdOrNull(id) ?: return false
        servers.delete(server)
        return true
    }

    /**
     * Hands the stored credentials back, for the settings form's "Reveal" action.
     *
     * A server reading a variable reveals nothing here: revealing a secret is
     * recorded against the secret, and a second door onto the same value under
     * this name would be a reveal nobody could find in the log.
     */
    @Transactional
    fun revealMcpServerSecret(id: Long): String? {
        val server = servers.findByIdOrNull(id) ?: throw McpServerNotFoundException(id)
        if (server.secretVariableId != null) return null
        log.info("Credentials for MCP server {} (workspace {}) revealed", server.name, server.workspaceId)
        return server.secret
    }

    /** A server as a screen sees it, with the variable it reads named. */
    private fun view(server: McpServer) =
        McpServerView(server, references.describe(server.workspaceId, server.secretVariableId))

    private companion object {
        val log = LoggerFactory.getLogger(McpServerService::class.java)

        /** As much of a check's sentence as the column holds; the rest is a repeat. */
        const val DETAIL_LENGTH = 1000
    }
}

/**
 * What pressing Check on an MCP server found.
 *
 * One sentence rather than a status and a code, because the reader is somebody
 * who has just typed an address and a token and wants to know which of the two
 * is wrong.
 */
data class McpServerCheck(
    val reachable: Boolean,
    val detail: String,
    /** How many tools it offered, on a check that got that far; null otherwise. */
    val tools: Int? = null,
)

data class CreateMcpServerInput(
    val workspaceId: Long,
    val name: String,
    val address: String,
    val authType: AuthType? = null,
    val secret: String? = null,
    /** A workspace secret to read the credential from instead of keeping a copy. */
    val secretVariableId: Long? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class UpdateMcpServerInput(
    val name: String,
    val address: String,
    val authType: AuthType? = null,
    /** Null leaves the stored credential alone; empty clears it, reference and all. */
    val secret: String? = null,
    /**
     * Points the credential at a workspace secret, dropping any copy it held.
     * Null leaves it as it is; sending it with [secret] is refused.
     */
    val secretVariableId: Long? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class McpServerView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val address: String,
    val authType: AuthType,
    val headers: List<HttpHeaderView>,
    /** Whether the server holds a credential of its own. False for one reading a variable. */
    val secretSet: Boolean,
    /** The workspace secret it reads instead, or null when it keeps its own copy. */
    val secretVariableId: Long?,
    /** What that variable is called, and which catalog holds it. */
    val secretVariableName: String?,
    val secretVariableCatalog: String?,
    /** A reference pointing at nothing, reported rather than assumed away. */
    val secretVariableMissing: Boolean,
    /**
     * What the last check found, so the list shows reachability without anybody
     * pressing Check. Null on [reachable] is "not checked yet", drawn as neither
     * green nor red rather than as a failure. Issue #329.
     */
    val reachable: Boolean?,
    /** ISO-8601, the same shape [ModelProviderView.lastCheckedAt] hands over. */
    val lastCheckedAt: String?,
    /** The sentence that check came back with, the same the button shows. */
    val checkDetail: String?,
    /** How many tools it offered on a check that got that far. */
    val toolCount: Int?,
) {
    constructor(server: McpServer, held: HeldSecret? = null) : this(
        id = requireNotNull(server.id),
        workspaceId = server.workspaceId,
        name = server.name,
        address = server.address,
        authType = server.authType,
        headers = server.headers.map { HttpHeaderView(it.name, it.value) },
        secretSet = !server.secret.isNullOrBlank(),
        secretVariableId = server.secretVariableId,
        secretVariableName = held?.name,
        secretVariableCatalog = held?.catalog,
        secretVariableMissing = server.secretVariableId != null && held == null,
        reachable = server.reachable,
        lastCheckedAt = server.lastCheckedAt?.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        checkDetail = server.checkDetail,
        toolCount = server.toolCount,
    )
}

class McpServerNotFoundException(id: Long) : RuntimeException("No MCP server with id $id")

class McpServerNameTakenException(name: String) :
    RuntimeException("An MCP server named \"$name\" already exists in this workspace")

class McpServerNameInvalidException : RuntimeException("An MCP server name is required")

class McpServerAddressInvalidException : RuntimeException("An MCP server address is required")
