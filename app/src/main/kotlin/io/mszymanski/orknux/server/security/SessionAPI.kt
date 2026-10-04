package io.mszymanski.orknux.server.security

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.InternalAuthenticationServiceException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.ldap.userdetails.InetOrgPerson
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import org.springframework.security.authentication.AuthenticationManager

/**
 * Username/password sign-in. An account this installation holds itself is tried
 * first, whatever the configured method; under LDAP anybody else is checked by the
 * LDAP [AuthenticationManager], and under INTERNAL there is nobody else. The
 * resulting authentication is stored in the HTTP session, so subsequent GraphQL
 * calls are attributed to that user.
 *
 * This is the one door anybody may knock on, so it counts the knocking. See
 * [SignInThrottle] for what a wrong password costs the second and the tenth
 * time. It is asked here rather than in a filter for one reason: the throttle
 * needs the username, and the username is in the body - a filter would have to
 * read and parse the request to find out what the endpoint is handed anyway.
 */
@RestController
@RequestMapping(LOGIN_PATH)
class SessionAPI(
    private val authenticationManager: AuthenticationManager,
    private val properties: SecurityProperties,
    private val resolver: RoleResolver,
    private val internal: InternalAuthentication,
    private val users: AppUserRepository,
    private val throttle: SignInThrottle,
) {

    private val securityContextRepository = HttpSessionSecurityContextRepository()

    @PostMapping
    fun login(
        @RequestBody credentials: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): SessionUser {
        /*
         * Whoever is knocking, before anything is checked for them.
         *
         * The address is the one the connection came from and not a header,
         * because a header is written by the caller: honouring `X-Forwarded-For`
         * here would hand an attacker a fresh address per request and leave the
         * per-address count counting nothing. Behind a proxy every caller shares
         * one address, and it is the per-username count that does the work.
         */
        val from = request.remoteAddr ?: "unknown"
        throttle.check(credentials.username, from)

        /*
         * Somebody this installation made up, checked first.
         *
         * Before the directory, and whatever the configured method is: an
         * internal user exists precisely because the provider does not know
         * them, so an installation signing in with OIDC still has to let them
         * in. Everybody else falls through to the door below.
         *
         * Nothing is announced for them. This is not an `AuthenticationManager`
         * and publishes no event, which is right: the password was checked
         * against a row in `app_user`, so there is nobody here to write down.
         * UserDetection lists the doors that do announce, and why these do not.
         */
        internal.authenticate(credentials.username, credentials.password)?.let { authenticated ->
            throttle.succeeded(credentials.username, from)
            request.getSession(false)?.invalidate()
            val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authenticated }
            SecurityContextHolder.setContext(context)
            securityContextRepository.saveContext(context, request, response)
            return sessionUser(authenticated)
        }

        /*
         * There is no password to check where the provider holds them. Refused
         * rather than quietly failing against a directory this installation does not
         * use, because the answer is not "wrong password" — it is "not this way".
         *
         * Counted as a failure all the same. The password was already checked
         * against an internal user on the way here, and that is real work an
         * unlimited caller would be spending.
         */
        if (properties.authMethod == AuthMethod.OIDC) {
            throttle.failed(credentials.username, from)
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "This installation signs in with ${properties.oidc.displayName}, not with a password.",
            )
        }

        /*
         * Under INTERNAL the check above was the whole of the door, and it said no.
         *
         * Stopping here is the point of the method rather than an optimisation: the
         * manager below binds to `spring.ldap.urls`, which under INTERNAL is nothing
         * but a default nobody set. Falling through would spend a connection attempt
         * on localhost:389 per wrong password and answer with whatever the directory
         * failure happened to look like — so the plain, honest 401 is given here, and
         * it is the same sentence a wrong password gets anywhere else.
         *
         * NONE stops here too, and for the same reason rather than a new one: there
         * is no directory configured under it either. Note what is *not* done — the
         * endpoint is not refused outright. An internal account with a password is
         * checked above whatever the method says, which is a rule this product has
         * held since the first internal user existed, and it is worth something
         * here: somebody who signs in on an open installation is themselves in the
         * audit log rather than "everyone". They simply have no way to reach this
         * form, because the interface never draws it when a session already exists.
         */
        if (properties.authMethod == AuthMethod.INTERNAL || properties.authMethod == AuthMethod.NONE) {
            throttle.failed(credentials.username, from)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password")
        }

        val authentication = try {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken(credentials.username, credentials.password),
            )
        } catch (cause: RuntimeException) {
            /*
             * Every way the directory can refuse, in one place.
             *
             * An AuthenticationException is the expected shape, and for a wrong
             * password it is the only one. A fault in the *group* search is not:
             * the groups are read after the password has been checked, outside
             * the part of Spring that turns directory failures into
             * authentication ones, so a group search base that does not exist
             * came back as a naming error nothing caught - and somebody whose
             * password was right got a 500 with no explanation anywhere.
             *
             * Anything else is rethrown, because a fault that is not the
             * directory's should not be dressed up as a wrong password.
             */
            if (cause !is AuthenticationException && cause !is org.springframework.ldap.NamingException) throw cause
            /*
             * Said in the log, not to the caller.
             *
             * The sentence below stays one sentence on purpose: telling somebody
             * at the door that the username exists and only the password was
             * wrong is telling an attacker which half to keep. But it is the
             * only thing this endpoint used to say, to anybody, and a directory
             * that cannot be reached at all arrives here as the same
             * AuthenticationException a wrong password does - so an installation
             * whose bind DN was mistyped reported "Invalid username or password"
             * to every person in the company and nothing at all to the person
             * who could fix it. See [refusalFor] for what the difference is.
             */
            when (refusalFor(cause)) {
                Refusal.DIRECTORY -> log.warn(
                    "Sign-in for \"{}\" could not be checked against the directory, so it was refused: {}. " +
                        "This is not a wrong password - the directory could not answer. Check " +
                        "ORKNUX_LDAP_URLS, ORKNUX_LDAP_BASE, ORKNUX_LDAP_BIND_DN and " +
                        "ORKNUX_LDAP_BIND_PASSWORD - and, where the name above is one that exists, " +
                        "ORKNUX_LDAP_GROUP_SEARCH_BASE, which is read after the password has been " +
                        "checked and is relative to the base rather than a whole DN.",
                    credentials.username,
                    describe(cause),
                )
                /*
                 * Debug rather than warn: on a public installation a wrong
                 * password is a daily event, and a line per attempt at warn
                 * would be a log anybody could fill from the sign-in form.
                 * Loud enough to find with ORKNUX_LOG_LEVEL=DEBUG, which is
                 * what somebody debugging a sign-in sets.
                 */
                Refusal.CREDENTIALS -> log.debug(
                    "Sign-in for \"{}\" was refused by the directory: {}. Either no entry matched " +
                        "ORKNUX_LDAP_USER_SEARCH_FILTER under ORKNUX_LDAP_USER_SEARCH_BASE, or the " +
                        "password was wrong - the directory is not asked to tell those apart.",
                    credentials.username,
                    describe(cause),
                )
            }
            throttle.failed(credentials.username, from)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password")
        }

        throttle.succeeded(credentials.username, from)

        // Drop any pre-login session so the authenticated user gets a fresh id.
        request.getSession(false)?.invalidate()

        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)

        return sessionUser(authentication)
    }

    @GetMapping
    fun current(authentication: Authentication): SessionUser = sessionUser(authentication)

    companion object {

        private val log = LoggerFactory.getLogger(SessionAPI::class.java)

        /**
         * Which kind of refusal this was, which the caller is never told.
         *
         * Spring's LDAP provider hands back one exception type for two very
         * different things. A user search that matched nothing becomes
         * `BadCredentialsException`, deliberately, so the door does not say
         * whether the name exists - and a wrong password becomes the same. But
         * every `NamingException` underneath becomes
         * [InternalAuthenticationServiceException]: the server unreachable, a
         * mistyped bind DN, a base that does not exist, TLS refused. Those are
         * not somebody typing their password wrong, they are an installation
         * that cannot sign anybody in, and they are worth saying out loud.
         */
        internal fun refusalFor(cause: Throwable): Refusal =
            if (cause is InternalAuthenticationServiceException || cause is org.springframework.ldap.NamingException) {
                Refusal.DIRECTORY
            } else {
                Refusal.CREDENTIALS
            }

        /**
         * The exception and the deepest thing that caused it.
         *
         * Both, because the useful sentence is usually at the bottom -
         * `InternalAuthenticationServiceException` carries the message of the
         * `NamingException` it wrapped, which in turn carries the one from JNDI
         * that names the host or the DN.
         */
        internal fun describe(cause: Throwable): String {
            var deepest: Throwable = cause
            var left = CAUSES
            // Bounded: a cycle in a cause chain would otherwise be a hang at the
            // one moment somebody is trying to read a log.
            while (left > 0) {
                deepest = deepest.cause ?: break
                left -= 1
            }
            val said = "${cause::class.simpleName}: ${cause.message}"
            return if (deepest === cause) said else "$said (caused by ${deepest::class.simpleName}: ${deepest.message})"
        }

        /** How far down a cause chain is followed; see [describe]. */
        private const val CAUSES = 10
    }

    /** What a refused sign-in actually was; see [refusalFor]. */
    internal enum class Refusal {
        /** No entry matched, or the password was wrong. The directory answered. */
        CREDENTIALS,

        /** The directory could not answer at all, so nobody can sign in. */
        DIRECTORY,
    }

    /**
     * Who this is, and whether they administer.
     *
     * Asked of the resolver rather than by looking for one configured authority, so
     * that a role carrying the administrator scope counts — which is the whole point
     * of roles having scopes. The configured authority still counts on its own,
     * inside the resolver, so nothing that worked before stops working.
     */
    private fun sessionUser(authentication: Authentication): SessionUser {
        val user = SessionUser(authentication)
        /*
         * The recorded address wins over the one on the principal. It started
         * as that same directory attribute, so usually they agree - but where
         * somebody has typed their own it is the answer, and the top bar
         * showing the directory's while the preferences page shows theirs would
         * be this installation disagreeing with itself about where their mail
         * goes.
         */
        val held = users.findByUsername(user.username)
        val recorded = held?.email?.takeIf(String::isNotBlank)
        return user.copy(
            /*
             * The provider's groups, and anything an administrator assigned
             * here. The second is what lets somebody the directory has no group
             * for administer at all, and a flag that ignored it would draw a
             * product without its Admin section for a person who has it.
             */
            admin = resolver.administers(user.roles.toSet()) || held?.roles.orEmpty().any { it.administers },
            email = recorded ?: user.email,
            /*
             * Sent with the session because the preferences page is where it is
             * changed and the session is what that page already has. True for
             * somebody with no row yet, which is what the column defaults to -
             * the row appears the first time they sign in.
             */
            emailNotifications = held?.emailNotifications ?: true,
            /*
             * The language comes with the session for a harder reason than the
             * address does: it decides what the first screen says. The browser
             * has already opened in whatever it last remembered, and finding a
             * different answer here costs it a reload - so this arrives with
             * the session rather than in a question of its own, and the reload
             * happens once, on a machine somebody has not signed in on before.
             * Null where they have never chosen, and the browser's own locale
             * decides.
             */
            language = held?.language,
            /*
             * And whether their chats print what an answer cost. Here for the
             * same reason and by the same route: the chat window needs it on
             * every render and the preferences page is where it is turned on,
             * and both of them already hold the session. False for somebody
             * with no row yet, which is what the column defaults to.
             */
            chatCostShown = held?.chatCostShown ?: false,
            /*
             * Whether Preferences offers them access tokens of their own. Only
             * an internal user may hold one - see `UserAPI.createUserToken` -
             * and a section that can only answer with a refusal is worse than
             * one that is not drawn. Issue #2.
             */
            tokensAllowed = held?.type == UserType.INTERNAL,
        )
    }

    @DeleteMapping
    fun logout(request: HttpServletRequest) {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
    }
}

/**
 * Spring Boot 4 ships Jackson 3, which has no Kotlin module on the classpath,
 * so the creator is bound explicitly rather than from constructor parameter names.
 */
data class LoginRequest @JsonCreator constructor(
    @JsonProperty("username") val username: String,
    @JsonProperty("password") val password: String,
)

data class SessionUser @JsonCreator constructor(
    @JsonProperty("username") val username: String,
    @JsonProperty("roles") val roles: List<String>,
    /** Whether the caller holds the configured admin role. */
    @JsonProperty("admin") val admin: Boolean = false,
    /**
     * Where to write to them, shown in the user menu; absent when nobody has
     * said. Their recorded address where there is one - which is the
     * directory's until they change it - and the principal's otherwise, for the
     * moment between arriving at the door and being written down.
     */
    @JsonProperty("email") val email: String? = null,
    /**
     * Whether issue news is posted to that address as well as rung on the bell.
     * Theirs to change on the preferences page, which is why it arrives here
     * rather than being asked for separately.
     */
    @JsonProperty("emailNotifications") val emailNotifications: Boolean = true,
    /**
     * Which language to draw the product in, or null where they have not
     * chosen and the browser's own locale should decide.
     *
     * Here rather than asked for, because it decides what the first screen
     * says and the browser has to know before it draws one.
     */
    @JsonProperty("language") val language: String? = null,
    /**
     * Whether a chat says what an answer cost as well as how long it took.
     * Theirs to turn on, on the same preferences page, and read by the chat
     * window on every answer - so it rides here rather than being a query of
     * its own on a screen that already has one open.
     */
    @JsonProperty("chatCostShown") val chatCostShown: Boolean = false,
    /** Whether they may make access tokens for themselves: internal users only. Issue #2. */
    @JsonProperty("tokensAllowed") val tokensAllowed: Boolean = false,
) {
    constructor(authentication: Authentication) : this(
        username = authentication.name,
        roles = authentication.authorities
            .mapNotNull(GrantedAuthority::getAuthority)
            .filter { it.startsWith("ROLE_") },
        email = (authentication.principal as? InetOrgPerson)?.mail?.takeIf(String::isNotBlank),
    )
}
