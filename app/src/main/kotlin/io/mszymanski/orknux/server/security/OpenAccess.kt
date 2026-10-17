package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Who an installation with [AuthMethod.NONE] acts as, and why it is somebody.
 *
 * **Why there has to be an identity at all.** Nothing downstream of the front door
 * was written for a caller who is nobody. An audit entry records
 * `authentication.name`, an issue keeps its reporter as a name, a chat session has
 * an owner, `WorkspaceAccess` reads roles off the authentication, and the
 * preferences page reads a row out of `app_user`. Turning authentication off by
 * removing the caller would mean touching every one of those, and each of them
 * would then have a second, untested path through it — the one where there is no
 * user — which is exactly the kind of path a security-relevant switch must not
 * create. So the switch does not remove the caller. It fixes it.
 *
 * **What that identity is.** An ordinary INTERNAL row in `app_user`, named
 * [OPEN_ACCESS_USERNAME], created here on the first start under this method and
 * left alone on every start after. It holds the built-in administrator role, and
 * it holds *no password hash*, which is the part worth being precise about:
 * [io.mszymanski.orknux.server.user.InternalAuthentication] refuses an account
 * without one, so this row cannot be signed in as. It is not a back door left
 * behind when authentication is turned back on — under LDAP, OIDC or INTERNAL this
 * class does nothing, no filter is installed, and the row is an inert name on the
 * Users screen.
 *
 * **Why it administers, said plainly.** Because the alternative is dishonest
 * rather than safer. This mode exists for trying the product out and for an
 * installation already behind somebody else's gate; both need the Admin section,
 * since there is nobody to grant it later. A non-administrator open identity would
 * be an installation nobody could ever configure, and the operator's first move
 * would be to widen it by hand. There is no middle position that means anything:
 * once nobody has to prove who they are, whoever can reach the port has whatever
 * this identity has. So it has everything, and every surface says so out loud —
 * see [AUTHENTICATION_OFF], which is the one sentence all of them use.
 *
 * **What that costs, deliberately accepted.** Every audit entry on such an
 * installation reads `everyone`, and it is telling the truth: any of them could
 * have done it, and the installation was never told which. An audit log that
 * invented a name would be worse than one that admits it does not know.
 * `@Convert`-encrypted credentials are readable, workspaces are all visible, and
 * the admin screens are open — to anybody who can reach the port. That is what
 * turning authentication off means, and it is why this is not the default and why
 * it cannot be arrived at by a typo.
 */
@Component
class OpenAccess(
    private val users: AppUserRepository,
    private val roles: RoleRepository,
    private val properties: SecurityProperties,
    private val desktop: DesktopMode,
) {

    /** Whether this installation asks anybody to sign in. */
    val off: Boolean get() = properties.authMethod == AuthMethod.NONE

    /**
     * Resolved once and kept for the life of the process.
     *
     * The row is one this class created and nothing edits, and the alternative is a
     * lookup by username on every single request — including the ones a scraper
     * makes every fifteen seconds. A restart re-reads it, which is the same
     * granularity every other thing decided at startup has.
     */
    @Volatile
    private var identity: UsernamePasswordAuthenticationToken? = null

    /**
     * Says it out loud, once, where an operator will see it whether or not anybody
     * ever opens the interface.
     *
     * At [ApplicationReadyEvent] for the reason
     * [io.mszymanski.orknux.server.user.BootstrapAdmin] is: the role this needs is
     * created by a migration, and Flyway has to have run.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun announce() {
        if (!off) return

        val authentication = authentication()
        if (authentication == null) {
            log.error(
                "ORKNUX_AUTH_METHOD is NONE but no identity could be established, so nothing can reach this " +
                    "installation. Every request will be answered 401.",
            )
            return
        }

        log.warn(
            "AUTHENTICATION IS OFF. ORKNUX_AUTH_METHOD is NONE, so nobody signs in and every request that " +
                "reaches this server acts as \"{}\", which holds the built-in administrator role. Anyone who " +
                "can reach this port administers this installation, reads every workspace and can use every " +
                "stored credential. Put a gate of your own in front of it, or set ORKNUX_AUTH_METHOD to LDAP, " +
                "OIDC or INTERNAL.",
            OPEN_ACCESS_USERNAME,
        )
    }

    /**
     * The identity every request acts as, or null when this installation is not
     * open — and null, too, when it is open and the identity could not be made.
     *
     * Null fails closed. A request with nothing on it is answered 401 by the chain
     * that was already there, which is the right way round for the one case that
     * can produce it: an installation whose administrator role is missing. Opening
     * the door to a caller holding nothing would be strictly worse than refusing.
     */
    @Synchronized
    fun authentication(): UsernamePasswordAuthenticationToken? {
        if (!off) return null
        identity?.let { return it }

        val administrators = roles.findByBuiltinTrue().firstOrNull { it.administers }
        if (administrators == null) {
            log.error(
                "No built-in administrator role exists, so \"{}\" could not be established. An open " +
                    "installation whose one identity administers nothing would look open and be unusable.",
                OPEN_ACCESS_USERNAME,
            )
            return null
        }

        val held = users.findByUsername(OPEN_ACCESS_USERNAME) ?: users.save(
            AppUser(
                username = OPEN_ACCESS_USERNAME,
                displayName = OPEN_ACCESS_DISPLAY_NAME,
                type = UserType.INTERNAL,
                roles = mutableSetOf(administrators),
                /*
                 * No password, and this is the line that keeps it from being a way
                 * in. `InternalAuthentication` returns null for an account without
                 * a hash, so this row is refused at the sign-in door under every
                 * method - including the one an operator turns back on tomorrow,
                 * when this row is still sitting here.
                 */
                passwordHash = null,
                // Says on the Users screen where this account came from, which
                // "system" would not.
                lastModifiedBy = "open-access",
            ),
        )
        /*
         * On the desktop app's own loopback server the one identity is the person
         * at the machine, and is shown as them. The username stays: it is what
         * the audit log and every row it wrote are keyed on.
         */
        if (desktop.localOnly && held.displayName != desktop.person) {
            held.displayName = desktop.person
            users.save(held)
        }

        /*
         * The same authority spelling a directory would produce and the same one
         * `InternalAuthentication` writes, so everything past the front door treats
         * this identity exactly like any other administrator rather than through a
         * second path that has to be kept in step.
         */
        val authenticated = UsernamePasswordAuthenticationToken(
            held.username,
            null,
            held.roles.map(::authorityOf),
        )
        identity = authenticated
        return authenticated
    }

    private fun authorityOf(role: Role): GrantedAuthority =
        SimpleGrantedAuthority("ROLE_" + role.name.trim().replace(NON_ROLE_CHARACTERS, "_").uppercase())

    private companion object {
        val log = LoggerFactory.getLogger(OpenAccess::class.java)
        val NON_ROLE_CHARACTERS = Regex("[^A-Za-z0-9]+")
    }
}

/**
 * Puts [OpenAccess]'s identity on a request that arrived with nobody on it.
 *
 * Installed only under [AuthMethod.NONE], and only by [SecurityConfig] reading the
 * bound enum — not by a `@ConditionalOnProperty` on the raw string, which would
 * match `NONE` and not `none` while the binding accepted both, and so would leave
 * one spelling opening the door and the other quietly not. One switch, read once,
 * in one place.
 *
 * It is deliberately not a `@Component`: a `OncePerRequestFilter` bean is also
 * registered by Boot into the servlet container's own chain, which would run it a
 * second time for every request outside this security chain.
 *
 * **After the token filter, never before it.** A caller presenting an `orkx_` token
 * is somebody in particular, and being told who they are is worth more than being
 * told what everybody is: the audit log then names them rather than `everyone`.
 * Both filters leave an authentication that is already there alone, so a session
 * cookie from an earlier sign-in also wins — running this one first would silently
 * throw both away.
 */
class OpenAccessFilter(private val open: OpenAccess) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (SecurityContextHolder.getContext().authentication == null) {
            open.authentication()?.let { SecurityContextHolder.getContext().authentication = it }
        }
        chain.doFilter(request, response)
    }
}

/**
 * What the one identity is called.
 *
 * `everyone` rather than `nobody` or `anonymous`, because it is what an audit line
 * has to mean: "deleted by everyone" says that any visitor could have done this and
 * the installation was not told which, where "deleted by nobody" reads as an audit
 * entry that failed to record anything.
 */
const val OPEN_ACCESS_USERNAME = "everyone"

/** How the Users screen names it, so nobody has to guess what the row is. */
const val OPEN_ACCESS_DISPLAY_NAME = "Everyone (authentication is off)"

/**
 * The one sentence that says an installation is open.
 *
 * Written here and read by the Doctor, by `/api/auth/method` and — through that —
 * by the strip the interface draws across every page, so the three cannot end up
 * describing the same installation differently. One line, because that is what the
 * interface has room for; `OpenAccessTest` holds it to that.
 */
const val AUTHENTICATION_OFF = "Authentication is off. Anyone who reaches this installation administers it."
