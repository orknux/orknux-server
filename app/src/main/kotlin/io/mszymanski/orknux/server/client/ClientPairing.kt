package io.mszymanski.orknux.server.client

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat

/** A one-time code a signed-in person shows, for a client to trade for its own credential. */
@Entity
@Table(name = "client_pairing_code")
class ClientPairingCode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** Whose it is: the username, which is what every door here deals in. */
    @Column(nullable = false, length = 120)
    val username: String,

    /** SHA-256 of the code, written without its dash and in capitals. The code itself is never kept. */
    @Column(name = "code_hash", nullable = false, length = 64)
    val codeHash: String,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: OffsetDateTime,

    /**
     * What the person held when they asked for the code, one authority a line:
     * for a directory account its groups, which the directory hands over at
     * sign-in and nothing keeps. Carried onto the credential; see [ClientCredential.authorities].
     */
    @Column(length = 4000)
    val authorities: String? = null,
)

/**
 * What a client - the Chrome extension, Orknux for Windows, the Claude Code
 * plugin - signs in with once it has been paired: narrower than an access token,
 * and one any account may have, a directory's included.
 *
 * It opens only the doors a client uses ([ClientPairings.CLIENT_PATHS]); on any
 * other it is refused, so a credential copied off somebody's machine reaches
 * their browser, desktop and Claude Code and nothing else of theirs. Kept as a
 * hash, like a token.
 */
@Entity
@Table(name = "client_credential")
class ClientCredential(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 120)
    val username: String,

    /** What the client called itself: "Chrome on michjak-pc". */
    @Column(nullable = false, length = 200)
    val name: String,

    /** Which kind of client: Chrome, Windows, ClaudeCode. */
    @Column(nullable = false, length = 40)
    val client: String,

    @Column(name = "credential_hash", nullable = false, length = 64)
    val credentialHash: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_used_at")
    var lastUsedAt: OffsetDateTime? = null,

    /**
     * The authorities the person signed in with when they paired, one a line.
     * A directory account's roles come from its groups at each sign-in and are
     * stored nowhere, so without these its client would see no workspace at
     * all. As fresh as the pairing: pairing again takes the directory's word
     * again, and an unused credential expires.
     */
    @Column(length = 4000)
    val authorities: String? = null,
)

interface ClientPairingCodeRepository : JpaRepository<ClientPairingCode, Long> {
    fun findByCodeHash(codeHash: String): ClientPairingCode?
    fun deleteByExpiresAtBefore(at: OffsetDateTime)
}

interface ClientCredentialRepository : JpaRepository<ClientCredential, Long> {
    fun findByCredentialHash(credentialHash: String): ClientCredential?
    fun findByUsernameIgnoreCaseOrderByCreatedAtDesc(username: String): List<ClientCredential>
}

/** A code just issued, said once. */
data class IssuedPairingCode(val code: String, val expiresAt: OffsetDateTime)

/** A credential just made, said once, and whose it is. */
data class IssuedClientCredential(val credential: String, val user: String)

/**
 * Connecting a client with a pairing code instead of an access token.
 *
 * A token is the whole API as somebody, which is why a directory account may
 * not have one: it would go on working after the directory disabled the
 * person. A client needs far less - its own door - so this hands out exactly
 * that, to anybody signed in: a short code shown on screen, typed into the
 * client within [Tunable.CLIENT_PAIRING_CODE_MINUTES], traded once for a
 * credential that opens only the client doors. A directory account's
 * credential stops working when it has gone unused for
 * [Tunable.CLIENT_CREDENTIAL_IDLE_DAYS], so somebody the directory removed does
 * not keep a working client for long.
 */
@Service
class ClientPairings(
    private val codes: ClientPairingCodeRepository,
    private val credentials: ClientCredentialRepository,
    private val users: AppUserRepository,
    private val tuning: Tuning,
    private val audit: WorkspaceAuditRecorder,
    /** How a person's roles become authorities, shared with access tokens; lazily, since both sit behind one filter. */
    private val internal: org.springframework.beans.factory.ObjectProvider<io.mszymanski.orknux.server.user.InternalAuthentication>,
) {

    /** A new code for [username], valid for a few minutes and once. */
    @Transactional
    fun start(username: String): IssuedPairingCode {
        val now = OffsetDateTime.now()
        codes.deleteByExpiresAtBefore(now)
        val raw = (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        val expires = now.plusMinutes(tuning[Tunable.CLIENT_PAIRING_CODE_MINUTES].toLong())
        val held = org.springframework.security.core.context.SecurityContextHolder.getContext().authentication?.authorities
            .orEmpty().map { it.authority }.filter { it != CLIENT_AUTHORITY && it != io.mszymanski.orknux.server.security.TokenAccess.READ_ONLY }
        codes.save(
            ClientPairingCode(
                username = username, codeHash = hash(raw), expiresAt = expires,
                authorities = held.joinToString("\n").take(AUTHORITIES_LENGTH).ifEmpty { null },
            ),
        )
        return IssuedPairingCode(raw.substring(0, 3) + "-" + raw.substring(3), expires)
    }

    /**
     * The credential [code] is traded for, once. Refused in the same words
     * whether the code never existed, was used, or ran out, so trying codes
     * says nothing about which ones were real.
     */
    @Transactional
    fun pair(code: String, client: String?, name: String?): IssuedClientCredential {
        val held = codes.findByCodeHash(hash(normal(code) ?: throw ClientPairingCodeInvalidException()))
            ?: throw ClientPairingCodeInvalidException()
        codes.delete(held)
        if (held.expiresAt.isBefore(OffsetDateTime.now())) throw ClientPairingCodeInvalidException()
        val secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val kind = client?.trim()?.take(40)?.ifEmpty { null } ?: "Client"
        val called = name?.trim()?.take(200)?.ifEmpty { null } ?: kind
        credentials.save(
            ClientCredential(
                username = held.username, name = called, client = kind, credentialHash = hash(secret),
                authorities = held.authorities,
            ),
        )
        asPerson(held.username) {
            audit.record(null, WorkspaceAuditCategory.WORKSPACE, "Client $called connected for ${held.username}")
        }
        return IssuedClientCredential(secret, held.username)
    }

    /**
     * Who [secret] signs in as, noted as used - or null where it is no
     * credential, or one a directory account left unused too long, which is
     * then forgotten.
     */
    @Transactional
    fun authenticate(secret: String): UsernamePasswordAuthenticationToken? {
        val held = credentials.findByCredentialHash(hash(secret)) ?: return null
        val person = users.findByUsername(held.username)
        if (person != null && person.type != UserType.INTERNAL) {
            val last = held.lastUsedAt ?: held.createdAt
            if (last.isBefore(OffsetDateTime.now().minusDays(tuning[Tunable.CLIENT_CREDENTIAL_IDLE_DAYS].toLong()))) {
                credentials.delete(held)
                return null
            }
        }
        held.lastUsedAt = OffsetDateTime.now()
        credentials.save(held)
        // What they signed in with when they paired - a directory account's groups - and the roles assigned here.
        val signedInWith = held.authorities.orEmpty().lines().filter { it.isNotBlank() }.map { SimpleGrantedAuthority(it) }
        val roles = (signedInWith + person?.let(internal.getObject()::authoritiesOf).orEmpty()).distinctBy { it.authority }
        return UsernamePasswordAuthenticationToken(held.username, null, roles + SimpleGrantedAuthority(CLIENT_AUTHORITY))
            .apply { details = ByClient(requireNotNull(held.id), held.client) }
    }

    fun mine(username: String): List<ClientCredential> = credentials.findByUsernameIgnoreCaseOrderByCreatedAtDesc(username)

    /** Disconnects one of [username]'s clients; somebody else's answers as not found. */
    @Transactional
    fun disconnect(username: String, id: Long): Boolean {
        val held = credentials.findById(id).orElse(null)?.takeIf { it.username.equals(username, ignoreCase = true) }
            ?: throw ClientNotFoundException(id)
        credentials.delete(held)
        audit.record(null, WorkspaceAuditCategory.WORKSPACE, "Client ${held.name} disconnected for ${held.username}")
        return true
    }

    /** Runs [work] as [username], for an audit line written where nobody is signed in yet. */
    private fun asPerson(username: String, work: () -> Unit) {
        val before = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(username, null, emptyList())
        try {
            work()
        } finally {
            SecurityContextHolder.getContext().authentication = before
        }
    }

    companion object {
        const val PREFIX = "orkxc_"

        /** The authority a client credential carries, so a door can tell it from a session or a token. */
        const val CLIENT_AUTHORITY = "orknux:client-credential"

        /** The column's length; a person in more groups than fit keeps the ones that do. */
        private const val AUTHORITIES_LENGTH = 4000

        /** The doors a client credential opens: the clients' own, and the workspace MCP the Claude Code plugin calls. */
        val CLIENT_PATHS = listOf(Regex("^/api/(browser|desktop|claude|terminal)(/.*)?$"), Regex("^/api/client/compatibility$"), Regex("^/mcp/\\d+$"))

        const val REFUSAL = "This is a client credential: it opens the doors of the Orknux clients and nothing else. Use an access token or sign in."

        /** No 0, O, 1, I or L: a code is read off a screen and typed. */
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val CODE_LENGTH = 6
        private val random = SecureRandom()

        /** A code as typed - any case, with or without its dash or spaces - or null where it cannot be one. */
        fun normal(code: String): String? {
            val bare = code.uppercase().filter { it.isLetterOrDigit() }
            return bare.takeIf { it.length == CODE_LENGTH && it.all { c -> c in ALPHABET } }
        }

        /** Whether [text] has the shape of a pairing code, for a client deciding what it was given. */
        fun looksLikeCode(text: String): Boolean = normal(text) != null && text.trim().length <= 9

        fun clientPath(path: String): Boolean = CLIENT_PATHS.any { it.matches(path) }

        /** What the Shell calls itself when it pairs: [SHELL_PATHS] are its. */
        const val SHELL = "Shell"

        /**
         * The doors a credential paired by Orknux Shell opens besides the clients' own.
         *
         * The Shell is not a device an agent drives but a person chatting from a
         * terminal, so its credential has to reach what the web chat reaches:
         * GraphQL, the chat stream and the files and voice a message carries.
         * That is close to the person's whole API, deliberately - pairing a
         * terminal is signing in on it - and it is still a credential of its
         * own, listed under Connected clients and disconnected there. The other
         * clients keep their narrow door: a credential copied off a Chrome
         * extension still reaches nothing but that extension's work.
         */
        val SHELL_PATHS = listOf(
            Regex("^/graphql$"),
            Regex("^/api/chats(/.*)?$"),
            Regex("^/api/attachments(/.*)?$"),
            Regex("^/api/workspaces/\\d+/(attachments|speech|transcription)(/.*)?$"),
            Regex("^/api/auth/method$"),
            Regex("^/api/session$"),
        )

        /** Whether a credential paired by [client] opens [path]. */
        fun opens(client: String?, path: String): Boolean =
            clientPath(path) || (client == SHELL && SHELL_PATHS.any { it.matches(path) })

        private fun hash(secret: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))
    }
}

/** Marks an authentication as a client credential's. */
data class ByClient(val credentialId: Long, val client: String? = null)

class ClientPairingCodeInvalidException : RuntimeException(
    "That pairing code is not valid: it may have been mistyped, used already, or run out. Make a new one in Orknux.",
)

class ClientNotFoundException(val id: Long) : RuntimeException("No client $id of yours is connected."), Refusal {
    override val arguments get() = mapOf("id" to id)
}
