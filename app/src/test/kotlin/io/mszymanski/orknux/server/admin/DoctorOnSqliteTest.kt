package io.mszymanski.orknux.server.admin

import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretColumns
import io.mszymanski.orknux.server.attachment.AttachmentProperties
import io.mszymanski.orknux.server.security.SecurityProperties
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.sql.Connection
import java.sql.SQLException

/**
 * The diagnostics page, on the database it was broken on.
 *
 * Every check answered `INTERNAL_ERROR` in the all-in-one image, and the reason
 * was one query: the scan for stored secrets asked `information_schema`, which
 * SQLite does not have, and it was the one part of the page not wrapped in a
 * catch. So the easiest way to try this product shipped with the screen that
 * exists to say what is wrong as the only screen that could not say anything.
 *
 * The suite runs on Postgres, where that query is fine, which is exactly why it
 * never noticed. This starts the application on SQLite - the same properties
 * `SqliteSchemaTest` uses, so the context is built once and shared - and asks
 * the page the question the image asked it.
 */
@SpringBootTest(properties = ["spring.datasource.url=jdbc:sqlite:target/schema-test.db"])
class DoctorOnSqliteTest {

    @Autowired
    lateinit var doctor: DoctorAPI

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var cipher: SecretCipher

    @Autowired
    lateinit var security: SecurityProperties

    @Autowired
    lateinit var web: WebProperties

    @Autowired
    lateinit var access: WorkspaceAccess

    @Autowired
    lateinit var attachments: AttachmentProperties

    @Autowired
    lateinit var secrets: SecretColumns

    @BeforeEach
    fun signInAsAdministrator() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("doctor", "n/a", listOf(SimpleGrantedAuthority("ROLE_ADMINS")))
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `the page answers with checks rather than an error`() {
        val checks = doctor.doctor()

        assertThat(checks.map { it.name })
            .containsExactly(
                "Secret key", "Stored secrets", "Authentication", "Attachments", "Schema", "Allowed origins", "Replicas",
            )

        // Not merely present: actually answered. A check that threw now reports
        // itself as unanswered, and that would pass a test which only counted.
        assertThat(checks.map { it.detail }).noneMatch { it.contains("Could not be checked") }
    }

    @Test
    fun `the scan reads SQLite's catalogue rather than finding nothing`() {
        // A page that answers is not yet a page that is right: a catalogue query
        // returning no columns at all would produce the same six cards, and the
        // stored-secrets card would say there is nothing stored - which is what
        // an empty installation says too. So something is stored, in a column no
        // version of this check has ever named, and the card has to have found
        // it. That is the claim the catalogue query makes on either database.
        jdbc.update(
            "INSERT INTO workspace (name, description) VALUES ('doctor-scan-probe', ?)",
            cipher.encrypt("a credential nobody listed"),
        )
        try {
            val stored = doctor.doctor().first { it.name == "Stored secrets" }

            assertThat(stored.verdict).isEqualTo(DoctorVerdict.OK)
            assertThat(stored.detail).doesNotContain("None stored yet")
            assertThat(stored.detail).containsPattern("All [1-9][0-9]* values readable")
        } finally {
            jdbc.update("DELETE FROM workspace WHERE name = 'doctor-scan-probe'")
        }
    }

    @Test
    fun `a check that cannot run costs its own card and no other`() {
        // The defect underneath the defect. One query against a table that was
        // not there took every other check down with it, so the checks are run
        // one at a time inside their own catch. Here the database itself is
        // gone, which is as broken as the ones that talk to it can get.
        val withoutADatabase = DoctorAPI(cipher, security, web, access, JdbcTemplate(NoDatabase()), attachments, secrets)

        val checks = withoutADatabase.doctor()

        assertThat(checks).hasSize(7)
        assertThat(checks.first { it.name == "Stored secrets" }.detail).contains("Could not be checked")
        // The ones that never needed the database are untouched, which is the
        // whole claim being made.
        assertThat(checks.first { it.name == "Secret key" }.verdict).isEqualTo(DoctorVerdict.OK)
        assertThat(checks.first { it.name == "Authentication" }.verdict).isEqualTo(DoctorVerdict.OK)
    }

    /** A database that is not there, in as few methods as the interface allows. */
    private class NoDatabase : AbstractDataSource() {

        override fun getConnection(): Connection = throw SQLException("there is no database here")

        override fun getConnection(username: String?, password: String?): Connection =
            throw SQLException("there is no database here")
    }
}
