package io.mszymanski.orknux.server.device

import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller

/** Whether one of the person's Orknux clients is connected now, and where to get it. */
data class DeviceStatus(
    val connected: Boolean,
    val client: String?,
    val version: String?,
    val installUrl: String,
    /** The computer's own name - Linux on <host> - where there may be several of one kind. */
    val name: String? = null,
)

data class MyDevices(
    val browser: DeviceStatus,
    val desktop: DeviceStatus,
    val claude: DeviceStatus,
    /**
     * Every desktop connected now, each by its own system: Orknux for Windows
     * and Orknux for Linux at once is two, and the corner draws a pill for each
     * rather than one for whichever spoke last.
     */
    val desktops: List<DeviceStatus>,
    /** Whether a Claude Code session may be connected here at all; see ClaudeCodeChats. */
    val claudeAvailable: Boolean,
    /**
     * Whether an MCP client of the person's - Claude Desktop, an IDE, Claude Code -
     * is connected to Orknux MCP now, in any workspace, by the rule the MCP
     * Sessions page uses for its Connected pill.
     */
    val mcp: Boolean = false,
)

/**
 * The signed-in person's own clients, for the corner of every screen: a link to
 * get the Chrome extension or Orknux for Windows, or word that it is connected.
 *
 * Connected rather than installed, because connected is all this server can
 * know: a client that is installed and not signed in has never said a word to
 * it. Nobody else's are answered; there is no argument to ask for them.
 */
@Controller
class DeviceStatusAPI(
    private val links: DeviceLinks,
    private val claudeCode: org.springframework.beans.factory.ObjectProvider<io.mszymanski.orknux.server.claude.ClaudeCodeChats>,
    private val mcpSessions: io.mszymanski.orknux.server.mcp.McpClientSessionRepository,
    private val mcpRecorder: io.mszymanski.orknux.server.mcp.McpSessions,
) {

    @QueryMapping
    fun myDevices(): MyDevices {
        val user = SecurityContextHolder.getContext().authentication?.name
        fun of(kind: DeviceKind): DeviceStatus {
            val here = user?.let { links.presence(it, kind) }
            return DeviceStatus(here != null, here?.client, here?.version, kind.installUrl)
        }
        return MyDevices(
            of(DeviceKind.BROWSER),
            of(DeviceKind.DESKTOP),
            of(DeviceKind.CLAUDE),
            user?.let { u ->
                links.computers(u, DeviceKind.DESKTOP).map { DeviceStatus(true, it.system, null, DeviceKind.DESKTOP.installUrl, it.name) }
            }.orEmpty(),
            claudeCode.getObject().availability().available,
            user != null && mcpSessions.existsByUsernameIgnoreCaseAndEndedAtIsNullAndLastSeenAtAfter(
                user, java.time.OffsetDateTime.now().minusMinutes(mcpRecorder.connectedMinutes()),
            ),
        )
    }
}
