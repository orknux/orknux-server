package io.mszymanski.orknux.connector.connection

import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The admin default connections. They are provisioned into every workspace
 * created after they are defined; editing one reaches the workspaces that hold a copy.
 *
 * Who may call this is settled before the request arrives: orknux-server checks
 * that the caller is an administrator and records the audit entry, and the
 * connector trusts the service token it presented.
 */
@Service
class ConnectionService(
    private val connections: ConnectionRepository,
    private val workspaceConnections: WorkspaceConnectionRepository,
    private val provisioning: ConnectionProvisioning,
) {

    /**
     * The columns the admin list can be put in the order of. Issue #358.
     *
     * The name breaks the tie on the other two, so two connections of one type
     * keep their places between reads of the same page.
     */
    private val orders = mapOf(
        "NAME" to listOf("name"),
        "TYPE" to listOf("type", "name"),
        "URL" to listOf("url", "name"),
    )

    fun connections(page: Int?, size: Int?, order: String? = null, ascending: Boolean? = null): ConnectionPage {
        val asked = order?.trim()?.uppercase()?.takeIf { it in orders }
        val fields = orders[asked] ?: orders.getValue("NAME")
        val up = ascending ?: true
        return ConnectionPage(
            connections.findAll(
                pageRequest(
                    page,
                    size,
                    Sort.by(if (up) Sort.Direction.ASC else Sort.Direction.DESC, *fields.toTypedArray()),
                ),
            ),
        )
    }

    fun connection(id: Long): ConnectionView? =
        connections.findByIdOrNull(id)?.let(::ConnectionView)

    @Transactional
    fun createConnection(input: ConnectionInput): CreatedConnectionView {
        val name = input.name.trim()
        val url = input.slackAwareUrl()
        if (name.isEmpty()) throw ConnectionNameInvalidException()
        if (url.isEmpty()) throw ConnectionUrlInvalidException()
        if (connections.findByName(name) != null) throw ConnectionNameTakenException(name)

        val connection = connections.save(Connection(name = name, type = input.type, url = url))

        // The count goes back to the caller, which is what writes the audit
        // entry: "Default connection Slack added to 3 existing workspaces".
        val backfilled = if (input.addToExistingWorkspaces == true) {
            provisioning.provisionToExistingWorkspaces(connection)
        } else {
            0
        }
        return CreatedConnectionView(ConnectionView(connection), backfilled)
    }

    /** Name, type and URL are shared, so the change also reaches the workspaces holding a copy. */
    @Transactional
    fun updateConnection(id: Long, input: ConnectionInput): ConnectionView {
        val name = input.name.trim()
        val url = input.slackAwareUrl()
        if (name.isEmpty()) throw ConnectionNameInvalidException()
        if (url.isEmpty()) throw ConnectionUrlInvalidException()

        val connection = connections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)
        if (name != connection.name && connections.findByName(name) != null) throw ConnectionNameTakenException(name)

        connection.name = name
        connection.type = input.type
        connection.url = url

        workspaceConnections.findByConnectionId(id).forEach { held ->
            held.name = name
            held.type = input.type
            held.url = url
        }
        return ConnectionView(connection)
    }

    /**
     * Removes the default. The copies workspaces already hold stay where they are,
     * credentials included, and become the workspaces' own: tidying the admin
     * list must not take a working integration away from a workspace.
     */
    @Transactional
    fun deleteConnection(id: Long): Boolean {
        val connection = connections.findByIdOrNull(id) ?: return false

        workspaceConnections.findByConnectionId(id).forEach { it.connectionId = null }
        connections.delete(connection)
        return true
    }
}

/**
 * A Slack default points at Slack, whatever arrived. There is one Web API base
 * and no reason to let an administrator type a different one; see [SLACK_API_URL].
 */
private fun ConnectionInput.slackAwareUrl(): String =
    if (type == ConnectionType.SLACK) SLACK_API_URL else url.orEmpty().trim()

data class ConnectionInput(
    val name: String,
    val type: ConnectionType,
    /** Required, except for the types that have one address: Slack is filled in. */
    val url: String? = null,
    /**
     * Defaults reach new workspaces as they are created; this also gives it to the
     * workspaces that already exist. Ignored when updating.
     */
    val addToExistingWorkspaces: Boolean? = null,
)

data class ConnectionView(
    val id: Long,
    val name: String,
    val type: ConnectionType,
    val url: String,
) {
    constructor(connection: Connection) : this(
        id = requireNotNull(connection.id),
        name = connection.name,
        type = connection.type,
        url = connection.url,
    )
}

/** The new default, plus how many existing workspaces took it, for the caller's audit entry. */
data class CreatedConnectionView(
    val connection: ConnectionView,
    val addedToWorkspaces: Int,
)

data class ConnectionPage(
    val content: List<ConnectionView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<Connection>) : this(
        content = page.content.map(::ConnectionView),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

class ConnectionNotFoundException(id: Long) : RuntimeException("No connection with id $id")

class ConnectionNameTakenException(name: String) :
    RuntimeException("A connection named \"$name\" already exists")

class ConnectionNameInvalidException : RuntimeException("A connection name is required")

/** A plugin-declared kind can only sit on an HTTP connection, or names a kind no loaded plugin declares. Issue #363. */
class ConnectionPluginTypeInvalidException(why: String) : RuntimeException(why)

class ConnectionUrlInvalidException : RuntimeException("A connection URL is required")
