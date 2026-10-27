package io.mszymanski.orknux.server.mail

import io.mszymanski.orknux.connector.connection.MailDelivery
import io.mszymanski.orknux.connector.connection.MailMessage
import io.mszymanski.orknux.server.action.RecordingMailTransport
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.graphql.test.tester.GraphQlTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser

/**
 * Admin -> Settings -> Mail: a server stored from the browser wins over the
 * environment's, its password is kept and never shown, and clearing it goes back
 * to ORKNUX_MAIL_*.
 *
 * The environment here is set as properties, and the mail server is
 * [RecordingMailTransport] - nothing in this class reaches a network.
 */
@SpringBootTest(
    properties = [
        "orknux.mail.host=env-relay.invalid",
        "orknux.mail.port=2525",
        "orknux.mail.username=env-user",
        "orknux.mail.password=env-secret",
        "orknux.mail.from=env@orknux.invalid",
        "orknux.mail.security=NONE",
    ],
)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class InstallationMailAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationMailSettings,
    @Autowired val mail: InstallationMail,
    @Autowired val transport: RecordingMailTransport,
    @Autowired val audits: WorkspaceAuditRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

    /*
     * After as well as before: the database is shared with every other context
     * in the suite, and a stored server left behind would be what a password
     * reset test elsewhere found in force instead of its own properties.
     */
    @BeforeEach
    @AfterEach
    fun reset() {
        settings.clear()
        transport.forget()
        audits.deleteAll()
        users.deleteAll()
    }

    private val view = "source host port username from security passwordSet updatedBy updatedAt"

    private fun save(password: String?): GraphQlTester.Response {
        val given = password?.let { """, password: "$it"""" } ?: ""
        return graphQlTester.document(
            """
            mutation {
              setInstallationMail(input: {
                host: " smtp.orknux.invalid ", port: 465, username: "mailer", from: "orknux@orknux.invalid",
                security: TLS$given
              }) { $view }
            }
            """,
        ).execute()
    }

    private fun audited(): List<String> = audits.findAll().map { it.message }

    @Test
    fun `with nothing stored the environment's server is shown, without its password`() {
        graphQlTester.document("{ installationMail { $view } }").execute()
            .path("installationMail.source").entity(String::class.java).isEqualTo("ENVIRONMENT")
            .path("installationMail.host").entity(String::class.java).isEqualTo("env-relay.invalid")
            .path("installationMail.port").entity(Int::class.java).isEqualTo(2525)
            .path("installationMail.username").entity(String::class.java).isEqualTo("env-user")
            .path("installationMail.from").entity(String::class.java).isEqualTo("env@orknux.invalid")
            .path("installationMail.security").entity(String::class.java).isEqualTo("NONE")
            .path("installationMail.passwordSet").entity(Boolean::class.java).isEqualTo(true)
            .path("installationMail.updatedBy").valueIsNull()
    }

    @Test
    fun `a stored server wins, its password is kept when saved empty and never returned`() {
        save("s3cret")
            .path("setInstallationMail.source").entity(String::class.java).isEqualTo("STORED")
            .path("setInstallationMail.host").entity(String::class.java).isEqualTo("smtp.orknux.invalid")
            .path("setInstallationMail.passwordSet").entity(Boolean::class.java).isEqualTo(true)
            .path("setInstallationMail.updatedBy").entity(String::class.java).isEqualTo("alice")

        // Encrypted in the column, and in no answer.
        val column = jdbc.queryForObject("SELECT password FROM installation_mail", String::class.java)
        assertThat(column).isNotEqualTo("s3cret").startsWith("orkx")
        val answered = graphQlTester.document("{ installationMail { $view } }").execute()
            .path("installationMail").entity(Map::class.java).get()
        assertThat(answered.values.map { it.toString() }).noneMatch { it.contains("s3cret") }

        // Saved again with the box left empty: the password stays.
        save("").path("setInstallationMail.passwordSet").entity(Boolean::class.java).isEqualTo(true)
        save(null).path("setInstallationMail.passwordSet").entity(Boolean::class.java).isEqualTo(true)

        val server = requireNotNull(mail.server())
        assertThat(server.host).isEqualTo("smtp.orknux.invalid")
        assertThat(server.port).isEqualTo(465)
        assertThat(server.password).isEqualTo("s3cret")

        assertThat(audited()).contains("Installation mail server set to smtp.orknux.invalid:465")
    }

    @Test
    fun `clearing goes back to the environment's server, at the next send`() {
        save("s3cret")
        assertThat(mail.send(MailMessage(listOf("x@orknux.invalid"), "s", "b"))).isInstanceOf(MailDelivery.Sent::class.java)
        assertThat(transport.sent.last().first.host).isEqualTo("smtp.orknux.invalid")

        graphQlTester.document("mutation { clearInstallationMail { source host } }").execute()
            .path("clearInstallationMail.source").entity(String::class.java).isEqualTo("ENVIRONMENT")
            .path("clearInstallationMail.host").entity(String::class.java).isEqualTo("env-relay.invalid")

        mail.send(MailMessage(listOf("x@orknux.invalid"), "s", "b"))
        assertThat(transport.sent.last().first.host).isEqualTo("env-relay.invalid")
        assertThat(transport.sent.last().first.password).isEqualTo("env-secret")
        assertThat(audited()).contains("Installation mail server cleared")
    }

    @Test
    fun `a test mail goes through the server in force, and its refusal is the relay's words`() {
        save("s3cret")
        graphQlTester.document("""mutation { sendInstallationTestMail(to: "bob@orknux.invalid") { sent to message } }""")
            .execute()
            .path("sendInstallationTestMail.sent").entity(Boolean::class.java).isEqualTo(true)
            .path("sendInstallationTestMail.to").entity(String::class.java).isEqualTo("bob@orknux.invalid")
        assertThat(transport.sent.single().first.host).isEqualTo("smtp.orknux.invalid")
        assertThat(transport.sent.single().second.to).containsExactly("bob@orknux.invalid")

        transport.answer = { _, _ -> MailDelivery.Refused("550 relaying denied", permanent = true) }
        graphQlTester.document("""mutation { sendInstallationTestMail(to: "bob@orknux.invalid") { sent message } }""")
            .execute()
            .path("sendInstallationTestMail.sent").entity(Boolean::class.java).isEqualTo(false)
            .path("sendInstallationTestMail.message").entity(String::class.java).isEqualTo("550 relaying denied")

        assertThat(audited()).contains(
            "Test mail sent to bob@orknux.invalid",
            "Test mail to bob@orknux.invalid refused: 550 relaying denied",
        )
    }

    @Test
    fun `a test mail with no address goes to the caller's own`() {
        users.save(AppUser(username = "alice", displayName = "alice", type = UserType.INTERNAL, email = "alice@orknux.invalid"))
        graphQlTester.document("mutation { sendInstallationTestMail { sent to } }").execute()
            .path("sendInstallationTestMail.to").entity(String::class.java).isEqualTo("alice@orknux.invalid")
        assertThat(transport.sent.single().second.to).containsExactly("alice@orknux.invalid")
    }

    @Test
    fun `a test mail with no address and none of their own is refused`() {
        graphQlTester.document("mutation { sendInstallationTestMail { sent } }").execute()
            .errors().expect { it.extensions["code"] == "InstallationMailRecipientMissing" }.verify()
        assertThat(transport.sent).isEmpty()
    }

    @Test
    fun `a server that could not send is refused`() {
        graphQlTester.document(
            """mutation { setInstallationMail(input: { host: "", from: "a@b.invalid", security: STARTTLS }) { source } }""",
        ).execute().errors().expect { it.extensions["code"] == "InstallationMailIncomplete" }.verify()
        graphQlTester.document(
            """mutation { setInstallationMail(input: { host: "smtp.invalid", port: 70000, from: "a@b.invalid", security: STARTTLS }) { source } }""",
        ).execute().errors().expect { it.extensions["code"] == "InstallationMailPortInvalid" }.verify()
        graphQlTester.document(
            """mutation { setInstallationMail(input: { host: "smtp.invalid", from: "nobody", security: STARTTLS }) { source } }""",
        ).execute().errors().expect { it.extensions["code"] == "EmailInvalid" }.verify()
        assertThat(settings.stored()).isNull()
    }

    @Test
    @WithMockUser(username = "bob")
    fun `only an administrator reads, sets, clears or tests it`() {
        listOf(
            "{ installationMail { source } }",
            """mutation { setInstallationMail(input: { host: "evil.invalid", from: "a@b.invalid", security: NONE }) { source } }""",
            "mutation { clearInstallationMail { source } }",
            """mutation { sendInstallationTestMail(to: "a@b.invalid") { sent } }""",
        ).forEach { document ->
            graphQlTester.document(document).execute().errors().satisfy { assertThat(it).isNotEmpty() }
        }
        assertThat(settings.stored()).isNull()
        assertThat(transport.sent).isEmpty()
    }

    @TestConfiguration
    class FakeTransport {

        @Bean
        @Primary
        fun recordingMailTransport(): RecordingMailTransport = RecordingMailTransport()
    }
}
