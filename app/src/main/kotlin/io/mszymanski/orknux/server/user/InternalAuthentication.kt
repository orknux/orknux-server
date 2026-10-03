package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.security.Role
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat

/**
 * Signing in as somebody this installation made up.
 *
 * The front door still belongs to the directory: this exists because an
 * identity that can never act is a poor thing to assign work to, and because
 * something working through the API should be *somebody* rather than borrowing
 * an administrator's session.
 *
 * Only internal users, and only those given a password. An external user is the
 * provider's, and a password here would be a second one to forget.
 */
@Service
class InternalAuthentication(
    private val users: AppUserRepository,
    private val tokens: AppUserTokenRepository,
    private val encoder: PasswordEncoder,
) {

    /** Whoever this is, if the password is theirs, noted as signed in. Null means "not this way". */
    @Transactional
    fun authenticate(username: String, password: String): UsernamePasswordAuthenticationToken? {
        val held = users.findByUsername(username) ?: return null
        if (held.type != UserType.INTERNAL) return null
        val hash = held.passwordHash ?: return null
        if (!encoder.matches(password, hash)) return null
        held.lastSignedInAt = OffsetDateTime.now()
        users.save(held)
        return tokenFor(held)
    }

    /**
     * A token's owner, and a note that it was used.
     *
     * Looked up by hash, because that is all this installation kept - a table
     * that could give a token back would be a password written down.
     */
    @Transactional
    fun authenticateToken(secret: String): UsernamePasswordAuthenticationToken? {
        val held = tokens.findByTokenHash(hash(secret)) ?: return null
        val owner = users.findById(held.userId).orElse(null) ?: return null
        held.lastUsedAt = OffsetDateTime.now()
        tokens.save(held)
        // Marked, so a door that must not be opened by a token can tell. #588.
        val signedIn = tokenFor(owner).apply { details = ByToken(requireNotNull(held.id)) }
        if (!held.readOnly) return signedIn
        // The owner as they are, held to reading. See TokenAccess.
        return UsernamePasswordAuthenticationToken(
            signedIn.name,
            null,
            signedIn.authorities + SimpleGrantedAuthority(io.mszymanski.orknux.server.security.TokenAccess.READ_ONLY),
        ).apply { details = ByToken(requireNotNull(held.id)) }
    }

    /**
     * A new token, returned once.
     *
     * The prefix is deliberate: a secret that says what it is can be recognised
     * in a log or a paste and revoked, rather than sitting there as an
     * anonymous string nobody dares touch.
     */
    @Transactional
    fun mint(user: AppUser, name: String, readOnly: Boolean = false): Pair<AppUserToken, String> {
        val secret = "orkx_" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(random::nextBytes))
        val stored = tokens.save(
            AppUserToken(userId = requireNotNull(user.id), name = name, tokenHash = hash(secret), readOnly = readOnly),
        )
        log.info("Token \"{}\" minted for {}", name, user.username)
        return stored to secret
    }

    /**
     * What the roles grant, in the words the rest of the application reads.
     *
     * The same authority spelling a directory would produce, so everything past
     * the front door - who administers, who sees which workspace - treats an
     * internal user exactly like anybody else rather than through a second path
     * that has to be kept in step.
     */
    private fun tokenFor(user: AppUser): UsernamePasswordAuthenticationToken =
        UsernamePasswordAuthenticationToken(user.username, null, user.roles.map(::authorityOf))

    private fun authorityOf(role: Role): GrantedAuthority =
        SimpleGrantedAuthority("ROLE_" + role.name.trim().replace(Regex("[^A-Za-z0-9]+"), "_").uppercase())

    private fun hash(secret: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))

    private companion object {
        val random = SecureRandom()
        val log = LoggerFactory.getLogger(InternalAuthentication::class.java)
    }
}

/**
 * A caller carrying a token instead of a session.
 *
 * Bearer, because that is what every client already knows how to send - and it
 * is what makes the MCP endpoint reachable by an agent that has no browser to
 * keep a cookie in.
 *
 * No session is created. A token is presented on every request by things that
 * do not keep state, and minting a session for each one would fill the session
 * table with rows nobody returns to.
 *
 * Nor is an arrival announced. The context is set here rather than through an
 * [org.springframework.security.authentication.AuthenticationManager], so no
 * `AuthenticationSuccessEvent` is published and [UserDetection] never hears of
 * this — deliberately, and for the same reason there is no session: a token
 * belongs to a user who is a row already, and every request carrying one is not
 * somebody arriving at the door.
 */
@Component
class TokenAuthenticationFilter(private val internal: InternalAuthentication) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        val secret = header?.takeIf { it.startsWith(BEARER, ignoreCase = true) }?.substring(BEARER.length)?.trim()

        if (secret != null && SecurityContextHolder.getContext().authentication == null) {
            /*
             * A token that is not one leaves the request unauthenticated rather
             * than refusing it here: what comes next decides whether this path
             * needed anybody at all, and a webhook does not.
             */
            internal.authenticateToken(secret)?.let { authenticated ->
                SecurityContextHolder.getContext().authentication = authenticated
            }
        }

        /*
         * A read-only token reads, and nothing else. GraphQL is told and refuses
         * a mutation itself, since a query and a mutation both arrive as a POST;
         * the MCP endpoint offers such a caller only the tools that read; every
         * other request that is not a read is refused here.
         */
        if (io.mszymanski.orknux.server.security.TokenAccess.readOnly()) {
            request.setAttribute(io.mszymanski.orknux.server.security.TokenAccess.READ_ONLY_ATTRIBUTE, true)
            val path = request.requestURI.removePrefix(request.contextPath)
            val reading = request.method.uppercase() in SAFE_METHODS
            if (!reading && path != "/graphql" && !path.startsWith("/mcp/")) {
                response.status = HttpServletResponse.SC_FORBIDDEN
                response.contentType = "application/json"
                response.writer.write("""{"error":"${io.mszymanski.orknux.server.security.TokenAccess.REFUSAL}"}""")
                return
            }
        }

        chain.doFilter(request, response)
    }

    private companion object {
        const val BEARER = "Bearer "
        val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
