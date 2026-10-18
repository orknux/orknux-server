package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.update.RESTART_EXIT_CODE
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.PasswordTooShortException
import io.mszymanski.orknux.server.user.SHORTEST_PASSWORD
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/** Who can reach the desktop app's server: this machine, or the network with everybody signing in. */
enum class DesktopNetworkMode { LOCAL, EXPOSED }

/**
 * The desktop app's network setting, as Admin › Settings draws it.
 *
 * @param mode what the server runs as now.
 * @param chosen what desktop.json says the next start will be - different from
 *   [mode] only between a change and the restart that applies it.
 * @param addresses where others on the network reach it once exposed.
 * @param account who signs in once exposed: the account set up with the switch.
 */
data class DesktopNetworkView(
    val mode: DesktopNetworkMode,
    val chosen: DesktopNetworkMode,
    val restartRequired: Boolean,
    val addresses: List<String>,
    val account: String,
    /** Whether the server will start again by itself once the setting is saved. */
    val restarts: Boolean,
)

/** Asked of a server that is not the desktop app's, which has no such switch. */
class DesktopNetworkUnavailableException :
    RuntimeException("Network access is a setting of the desktop app, and this server is not one")

/**
 * The desktop app's switch between this machine only and the network.
 *
 * What it decides - the address the server binds and how anybody signs in - is
 * known before the server starts, so it lives in desktop.json beside the
 * database, which the desktop app reads on every start (desktop/src/data.js).
 * The server cannot rebind itself: it writes the file, and leaves with 75, the
 * code the desktop app's start loop reads as "start me again" - the same one a
 * release activation leaves with - so the next start runs as chosen.
 *
 * Exposed means everybody signs in, so the switch sets the password then: on an
 * account named after the person at the machine, holding the administrator
 * role, so that after the restart they sign in as themselves and are not locked
 * out of what they just locked. desktop.json names that account, and the
 * desktop app then generates no password of its own.
 */
@Controller
class DesktopNetworkAPI(
    @Value("\${orknux.security.desktop:false}") private val desktop: Boolean,
    @Value("\${ORKNUX_DATA:}") private val data: String,
    /** Set by the desktop app's start loop; only a server it started can be started again by leaving. */
    @Value("\${orknux.update.launched:false}") private val launched: Boolean,
    private val properties: SecurityProperties,
    private val desktopMode: DesktopMode,
    private val access: WorkspaceAccess,
    private val users: AppUserRepository,
    private val roles: RoleRepository,
    private val encoder: PasswordEncoder,
    private val audit: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
    private val environment: Environment,
    private val context: ConfigurableApplicationContext,
) {

    /** The switch's state, or null on a server that is not the desktop app's - where the card is not drawn. */
    @QueryMapping
    fun desktopNetwork(): DesktopNetworkView? {
        if (!available()) return null
        access.requireAdmin()
        return view()
    }

    @MutationMapping
    @Transactional
    fun setDesktopNetwork(@Argument mode: DesktopNetworkMode, @Argument password: String?): DesktopNetworkView {
        if (!available()) throw DesktopNetworkUnavailableException()
        access.requireAdmin()
        val account = accountName()
        if (mode == DesktopNetworkMode.EXPOSED) {
            val given = password.orEmpty()
            if (given.length < SHORTEST_PASSWORD) throw PasswordTooShortException(SHORTEST_PASSWORD)
            signsInAs(account, given)
        }
        write(mode, account)
        audit.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (mode == DesktopNetworkMode.EXPOSED) {
                "The desktop app was opened to the network; $account signs in"
            } else {
                "The desktop app was closed to the network; only this computer reaches it"
            },
        )
        val answer = view()
        if (answer.restartRequired && launched) restartSoon()
        return answer
    }

    private fun available(): Boolean = desktop && data.isNotBlank()

    private fun file(): Path = Path.of(data).resolve(FILE)

    /** What the server runs as now: open access on loopback is local, anything else is exposed. */
    private fun running(): DesktopNetworkMode =
        if (properties.authMethod == AuthMethod.NONE) DesktopNetworkMode.LOCAL else DesktopNetworkMode.EXPOSED

    private fun chosen(): DesktopNetworkMode = runCatching {
        val held = mapper.readTree(Files.readString(file()))
        if (held.path("network").asString("") == "exposed") DesktopNetworkMode.EXPOSED else DesktopNetworkMode.LOCAL
    }.getOrDefault(DesktopNetworkMode.LOCAL)

    private fun view(): DesktopNetworkView {
        val now = running()
        val next = chosen()
        return DesktopNetworkView(
            mode = now,
            chosen = next,
            restartRequired = now != next,
            addresses = addresses(),
            account = accountName(),
            restarts = launched,
        )
    }

    /**
     * The person at the machine, as an account name: what the machine calls them,
     * in the characters a sign-in box takes; "admin" where nothing usable is left.
     */
    private fun accountName(): String =
        desktopMode.person.lowercase().replace(Regex("[^a-z0-9._-]+"), "").ifEmpty { "admin" }

    /** The account that signs in once exposed: made, or given the password and the administrator role. */
    private fun signsInAs(username: String, password: String) {
        val administrators = roles.findByBuiltinTrue().firstOrNull { it.administers }
            ?: throw IllegalStateException("There is no built-in administrator role to give $username")
        val held = users.findByUsername(username)
        if (held == null) {
            users.save(
                AppUser(
                    username = username,
                    displayName = desktopMode.person,
                    type = UserType.INTERNAL,
                    roles = mutableSetOf(administrators),
                    passwordHash = encoder.encode(password),
                    lastModifiedBy = "desktop",
                ),
            )
        } else {
            held.passwordHash = encoder.encode(password)
            held.roles.add(administrators)
            users.save(held)
        }
    }

    /** desktop.json with the choice in it, everything else in the file kept; written beside it and moved over it. */
    private fun write(mode: DesktopNetworkMode, account: String) {
        val target = file()
        val held = runCatching { mapper.readTree(Files.readString(target)) as? ObjectNode }.getOrNull()
            ?: mapper.createObjectNode()
        held.put("network", if (mode == DesktopNetworkMode.EXPOSED) "exposed" else "local")
        if (mode == DesktopNetworkMode.EXPOSED) held.put("account", account)
        val written = target.resolveSibling("$FILE.writing")
        Files.writeString(written, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(held))
        Files.move(written, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** The machine's own addresses others on its network could use, with the port this server listens on. */
    private fun addresses(): List<String> {
        val port = environment.getProperty("local.server.port") ?: environment.getProperty("server.port") ?: "8080"
        return runCatching {
            NetworkInterface.networkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses().toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { "http://${it.hostAddress}:$port" }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }

    /**
     * Leaves with 75 once the answer has gone, on a thread of its own that is not
     * a daemon - see ServerReleases.exit for why both of those matter.
     */
    private fun restartSoon() {
        Thread.ofPlatform().name("orknux-desktop-network").daemon(false).start {
            Thread.sleep(ANSWER_FIRST_MS)
            log.info("Restarting so the desktop app applies the network setting")
            runCatching { SpringApplication.exit(context, ExitCodeGenerator { RESTART_EXIT_CODE }) }
            exitProcess(RESTART_EXIT_CODE)
        }
    }

    companion object {
        /** Beside the database; desktop/src/data.js reads the same name. */
        const val FILE = "desktop.json"

        /** Long enough for the answer to reach the page before the server goes. */
        private const val ANSWER_FIRST_MS = 1500L
        private val log = LoggerFactory.getLogger(DesktopNetworkAPI::class.java)
    }
}
