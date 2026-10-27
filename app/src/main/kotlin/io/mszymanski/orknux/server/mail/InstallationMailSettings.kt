package io.mszymanski.orknux.server.mail

import io.mszymanski.orknux.connector.connection.MailSecurity
import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretConverter
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.user.EmailInvalidException
import io.mszymanski.orknux.server.user.UserAPI
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * The installation's mail server as Admin Settings -> Mail stored it.
 *
 * One row or none, and none is the ordinary state: it is what every
 * installation configured through ORKNUX_MAIL_* has, and what "Use environment
 * settings" goes back to. Still the installation's and never a workspace's -
 * see [InstallationMail] for why that matters.
 *
 * **The password is encrypted by the column**, through the converter every
 * stored credential goes through, so no code path can forget it and nothing
 * above this has to know.
 */
@Entity
@Table(name = "installation_mail")
class StoredInstallationMail(
    /** Always [ONLY]; the table's `CHECK` holds it to one row. */
    @Id
    @Column(name = "id")
    val id: Long = ONLY,

    @Column(name = "host", nullable = false, length = 255)
    var host: String = "",

    /** Null takes whatever [security] usually listens on. */
    @Column(name = "port")
    var port: Int? = null,

    /** Null sends without authenticating. */
    @Column(name = "username", length = 255)
    var username: String? = null,

    /** Null where there is no user to go with it, or none was given. */
    @Convert(converter = SecretConverter::class)
    @Column(name = "password", length = SECRET_COLUMN_LENGTH)
    var password: String? = null,

    /** `from` is a reserved word in SQL, hence the column's name. */
    @Column(name = "from_address", nullable = false, length = LONGEST_ADDRESS)
    var from: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "security", nullable = false, length = 16)
    var security: MailSecurity = MailSecurity.STARTTLS,

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "orknux",
) {
    companion object {
        const val ONLY: Long = 1
        const val LONGEST_HOST = 255
        const val LONGEST_ADDRESS = UserAPI.LONGEST_EMAIL
    }
}

interface StoredInstallationMailRepository : JpaRepository<StoredInstallationMail, Long>

/** What a save asks for. The password is null or empty to keep the stored one. */
data class InstallationMailInput(
    val host: String,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val from: String,
    val security: MailSecurity = MailSecurity.STARTTLS,
)

/** Reading and writing the stored row; [InstallationMail] decides what it is worth against the environment. */
@Service
class InstallationMailSettings(private val rows: StoredInstallationMailRepository) {

    /** The stored server, or null where Admin Settings holds none. */
    fun stored(): StoredInstallationMail? =
        rows.findByIdOrNull(StoredInstallationMail.ONLY)?.takeIf { it.host.isNotBlank() }

    /**
     * Stores [input], refusing a server that could not send anything: a host
     * and a from address are what a relay needs at the least, and a port has
     * to be one.
     *
     * A password left empty keeps the stored one - what a masked box sends,
     * and what lets the rest of the form be saved without a secret the page was
     * never shown. Without a username there is nobody for a password to belong
     * to, so it is dropped, the same rule [InstallationMail] applies on reading.
     */
    @Transactional
    fun set(input: InstallationMailInput, by: String): StoredInstallationMail {
        val host = input.host.trim()
        val from = input.from.trim()
        if (host.isEmpty() || from.isEmpty()) throw InstallationMailIncompleteException()
        if (host.length > StoredInstallationMail.LONGEST_HOST || host.any { it.isWhitespace() }) {
            throw InstallationMailHostInvalidException(host)
        }
        input.port?.let { if (it !in 1..65535) throw InstallationMailPortInvalidException(it) }
        if (from.length > StoredInstallationMail.LONGEST_ADDRESS || !UserAPI.EMAIL.matches(from)) {
            throw EmailInvalidException(from)
        }

        val held = rows.findByIdOrNull(StoredInstallationMail.ONLY) ?: StoredInstallationMail()
        val username = input.username?.trim()?.ifEmpty { null }?.take(StoredInstallationMail.LONGEST_HOST)
        held.host = host
        held.port = input.port
        held.username = username
        held.password = when {
            username == null -> null
            !input.password.isNullOrBlank() -> input.password.trim()
            else -> held.password
        }
        held.from = from
        held.security = input.security
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        return rows.save(held)
    }

    /** Forgets the stored server, so the environment's is used again. False where there was none. */
    @Transactional
    fun clear(): Boolean {
        if (!rows.existsById(StoredInstallationMail.ONLY)) return false
        rows.deleteById(StoredInstallationMail.ONLY)
        return true
    }
}

/** A mail server saved without a host or a from address, which no relay would take mail from. */
class InstallationMailIncompleteException :
    RuntimeException("A mail server needs a host and a from address.")

class InstallationMailHostInvalidException(val host: String) :
    RuntimeException("\"$host\" is not a mail server's host name."), Refusal {
    override val arguments get() = mapOf("host" to host)
}

class InstallationMailPortInvalidException(val port: Int) :
    RuntimeException("$port is not a port; give one from 1 to 65535, or leave it empty for the usual one."), Refusal {
    override val arguments get() = mapOf("port" to port.toString())
}

/** A test mail asked for with no address typed, by somebody who has none of their own. */
class InstallationMailRecipientMissingException :
    RuntimeException("Say where the test mail goes; your account has no address to send it to.")
