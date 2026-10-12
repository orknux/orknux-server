package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.ldap.userdetails.InetOrgPerson
import org.springframework.security.test.context.support.WithMockUser

/**
 * The people this installation knows: listed, made, edited - and the line
 * between the two kinds.
 *
 * An INTERNAL user is this installation's own and changes here; an EXTERNAL
 * one is recorded at sign-in and refused edits, because the provider would
 * overwrite them at the next arrival anyway.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class UserAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val detection: UserDetection,
    @Autowired val internal: InternalAuthentication,
) {

    @BeforeEach
    fun reset() {
        users.deleteAll()
        roles.findAll().filter { !it.builtin }.forEach(roles::delete)
    }

    @Test
    fun `an internal user is made with roles and can be renamed`() {
        val role = roles.save(Role(name = "reviewers"))

        val made = graphQlTester.document(
            """mutation { createUser(input: {
                 username: "helpdesk", displayName: "The Helpdesk", roleIds: [${role.id}]
               }) { id username displayName type editable roles { name } } }""",
        ).execute()
            .path("createUser.type").entity(String::class.java).isEqualTo("INTERNAL")
            .path("createUser.editable").entity(Boolean::class.java).isEqualTo(true)
            .path("createUser.roles[0].name").entity(String::class.java).isEqualTo("reviewers")
            .path("createUser.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateUser(id: $made, input: { displayName: "Front Desk", roleIds: [] })
               { displayName roles { name } } }""",
        ).execute()
            .path("updateUser.displayName").entity(String::class.java).isEqualTo("Front Desk")
            .path("updateUser.roles").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `signing in writes somebody down, once`() {
        val arrival = AuthenticationSuccessEvent(TestingAuthenticationToken("bob", "n/a", "ROLE_USERS"))
        detection.onSignIn(arrival)
        detection.onSignIn(arrival)

        val held = users.findByUsername("bob")
        assertThat(held?.type).isEqualTo(UserType.EXTERNAL)
        assertThat(users.findAll().count { it.username == "bob" }).isEqualTo(1)
    }

    @Test
    fun `a password lets an internal user sign in, and a token stands in for them`() {
        val id = graphQlTester.document(
            """mutation { createUser(input: { username: "claude", displayName: "Claude" }) { id hasPassword } }""",
        ).execute()
            // Made without one: an identity that cannot sign in is still useful.
            .path("createUser.hasPassword").entity(Boolean::class.java).isEqualTo(false)
            .path("createUser.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { setUserPassword(id: $id, password: "correct horse battery") { hasPassword } }""",
        ).execute()
            .path("setUserPassword.hasPassword").entity(Boolean::class.java).isEqualTo(true)

        assertThat(internal.authenticate("claude", "correct horse battery")).isNotNull()
        assertThat(internal.authenticate("claude", "not it")).isNull()
        // However it was typed, they are the same person - but the password is not.
        assertThat(internal.authenticate("CLAUDE", "correct horse battery")).isNotNull()

        val secret = graphQlTester.document(
            """mutation { createUserToken(id: $id, name: "Claude Code") { secret token { name lastUsedAt } } }""",
        ).execute()
            .path("createUserToken.token.lastUsedAt").valueIsNull()
            .path("createUserToken.secret").entity(String::class.java).get()

        // Says what it is, so it can be recognised in a log and revoked.
        assertThat(secret).startsWith("orkx_")

        val carried = internal.authenticateToken(secret)
        assertThat(carried?.name).isEqualTo("claude")
        assertThat(internal.authenticateToken("orkx_nonsense")).isNull()
    }

    @Test
    fun `a short password is refused, and an external user has none to set`() {
        val id = graphQlTester.document(
            """mutation { createUser(input: { username: "helpdesk" }) { id } }""",
        ).execute().path("createUser.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { setUserPassword(id: $id, password: "short") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("at least 12") == true }
            .verify()

        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("frank", "n/a", "ROLE_USERS")))
        val external = requireNotNull(users.findByUsername("frank"))
        graphQlTester.document(
            """mutation { setUserPassword(id: ${external.id}, password: "correct horse battery") { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("identity provider") == true }
            .verify()
    }

    @Test
    fun `an external user is not this installation's to edit`() {
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("carol", "n/a", "ROLE_USERS")))
        val held = requireNotNull(users.findByUsername("carol"))

        graphQlTester.document(
            """mutation { updateUser(id: ${held.id}, input: { displayName: "Someone Else" }) { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("identity provider") == true }
            .verify()
    }

    @Test
    fun `one person per name, whichever kind`() {
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("dave", "n/a", "ROLE_USERS")))

        graphQlTester.document(
            """mutation { createUser(input: { username: "DAVE" }) { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("already exists") == true }
            .verify()
    }

    @Test
    fun `the search reads both names`() {
        graphQlTester.document("""mutation { createUser(input: { username: "helpdesk", displayName: "Front Desk" }) { id } }""")
            .execute().path("createUser.id").entity(Long::class.java).get()
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("erin", "n/a", "ROLE_USERS")))

        graphQlTester.document("""{ users(search: "front") { username } }""")
            .execute()
            .path("users").entityList(Any::class.java).hasSize(1)
            .path("users[0].username").entity(String::class.java).isEqualTo("helpdesk")
    }

    @Test
    fun `the directory's address is written down at the first sign-in`() {
        detection.onSignIn(
            AuthenticationSuccessEvent(
                TestingAuthenticationToken(inetOrgPerson("gita", "gita@example.com"), "n/a", "ROLE_USERS"),
            ),
        )

        val held = requireNotNull(users.findByUsername("gita"))
        assertThat(held.email).isEqualTo("gita@example.com")
        // Inherited, not typed: the directory may still refresh it.
        assertThat(held.emailChosen).isFalse()
    }

    @Test
    fun `an administrator sets somebody else's address`() {
        val id = graphQlTester.document(
            """mutation { createUser(input: { username: "helpdesk" }) { id email emailChosen } }""",
        ).execute()
            .path("createUser.email").valueIsNull()
            .path("createUser.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { setUserEmail(id: $id, email: "  desk@example.com ") { email emailChosen } }""",
        ).execute()
            // Trimmed on the way in, so a stray space is not part of the address.
            .path("setUserEmail.email").entity(String::class.java).isEqualTo("desk@example.com")
            .path("setUserEmail.emailChosen").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("""mutation { setUserEmail(id: $id, email: "not an address") { email } }""")
            .execute()
            .errors().expect { it.message?.contains("does not look like an email address") == true }
            .verify()
    }

    @Test
    @WithMockUser(username = "hana", roles = ["USERS"])
    fun `somebody sets their own address without an id`() {
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("hana", "n/a", "ROLE_USERS")))

        graphQlTester.document("""mutation { setUserEmail(email: "hana@example.com") { email emailChosen } }""")
            .execute()
            .path("setUserEmail.email").entity(String::class.java).isEqualTo("hana@example.com")
            .path("setUserEmail.emailChosen").entity(Boolean::class.java).isEqualTo(true)

        // Cleared, and handed back to the provider to seed again.
        graphQlTester.document("""mutation { setUserEmail(email: "") { email emailChosen } }""")
            .execute()
            .path("setUserEmail.email").valueIsNull()
            .path("setUserEmail.emailChosen").entity(Boolean::class.java).isEqualTo(false)
    }

    /**
     * The language somebody reads the product in is theirs, and null is a real
     * answer rather than "English".
     *
     * The distinction is what lets a browser open in the language it is already
     * set to. Once somebody has said English, this holds `en` and their Polish
     * browser has to leave them in English - so clearing it has to put the
     * column back to null rather than to a tag.
     */
    @Test
    @WithMockUser(username = "kasia", roles = ["USERS"])
    fun `somebody chooses the language they read the product in`() {
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("kasia", "n/a", "ROLE_USERS")))

        graphQlTester.document("""mutation { setMyLanguage(language: "PL") { language } }""")
            .execute()
            .path("setMyLanguage.language").entity(String::class.java).isEqualTo("pl")

        assertThat(requireNotNull(users.findByUsername("kasia")).language).isEqualTo("pl")

        // Chosen English is not the same as having chosen nothing.
        graphQlTester.document("""mutation { setMyLanguage(language: "en") { language } }""")
            .execute()
            .path("setMyLanguage.language").entity(String::class.java).isEqualTo("en")

        graphQlTester.document("""mutation { setMyLanguage(language: null) { language } }""")
            .execute()
            .path("setMyLanguage.language").valueIsNull()

        assertThat(requireNotNull(users.findByUsername("kasia")).language).isNull()
    }

    @Test
    @WithMockUser(username = "tomek", roles = ["USERS"])
    fun `somebody chooses the time zone they read times in, and a name that is no zone is refused`() {
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("tomek", "n/a", "ROLE_USERS")))

        graphQlTester.document("""mutation { setMyTimeZone(timeZone: " Europe/Warsaw ") { timeZone } }""")
            .execute()
            .path("setMyTimeZone.timeZone").entity(String::class.java).isEqualTo("Europe/Warsaw")
        assertThat(requireNotNull(users.findByUsername("tomek")).timeZone).isEqualTo("Europe/Warsaw")

        graphQlTester.document("""mutation { setMyTimeZone(timeZone: "Mars/Olympus") { timeZone } }""")
            .execute()
            .errors().expect { it.message?.contains("no time zone named") == true }
        assertThat(requireNotNull(users.findByUsername("tomek")).timeZone).isEqualTo("Europe/Warsaw")

        graphQlTester.document("""mutation { setMyTimeZone(timeZone: null) { timeZone } }""")
            .execute()
            .path("setMyTimeZone.timeZone").valueIsNull()
        assertThat(requireNotNull(users.findByUsername("tomek")).timeZone).isNull()
    }

    /**
     * A tag no catalogue was shipped for is stored rather than refused.
     *
     * The words live in the interface's bundle and the server has no list of
     * what shipped in it, so a whitelist here would be a second list to keep in
     * step and would refuse a language the day it was added. An unknown tag
     * reads as English, which is what an interface with no catalogue for it
     * does anyway.
     */
    @Test
    @WithMockUser(username = "lars", roles = ["USERS"])
    fun `a language the interface has no words for is kept rather than refused`() {
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("lars", "n/a", "ROLE_USERS")))

        graphQlTester.document("""mutation { setMyLanguage(language: "sv") { language } }""")
            .execute()
            .path("setMyLanguage.language").entity(String::class.java).isEqualTo("sv")
    }

    @Test
    @WithMockUser(username = "ivan", roles = ["USERS"])
    fun `somebody without the administrator role cannot set another person's`() {
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("ivan", "n/a", "ROLE_USERS")))
        detection.onSignIn(AuthenticationSuccessEvent(TestingAuthenticationToken("jo", "n/a", "ROLE_USERS")))
        val other = requireNotNull(users.findByUsername("jo"))

        graphQlTester.document(
            """mutation { setUserEmail(id: ${other.id}, email: "ivan@example.com") { email } }""",
        ).execute()
            .errors().expect { it.message?.contains("administrator role") == true }
            .verify()

        assertThat(users.findByUsername("jo")?.email).isNull()

        // Their own row by id is still theirs, which is what the id is for.
        val mine = requireNotNull(users.findByUsername("ivan"))
        graphQlTester.document(
            """mutation { setUserEmail(id: ${mine.id}, email: "ivan@example.com") { email } }""",
        ).execute()
            .path("setUserEmail.email").entity(String::class.java).isEqualTo("ivan@example.com")
    }

    @Test
    fun `the directory refreshes an inherited address and leaves a chosen one alone`() {
        detection.onSignIn(
            AuthenticationSuccessEvent(
                TestingAuthenticationToken(inetOrgPerson("kim", "kim@old.example.com"), "n/a", "ROLE_USERS"),
            ),
        )
        val held = requireNotNull(users.findByUsername("kim"))

        // Nobody has typed one, so what the directory now says is what is true.
        detection.onSignIn(
            AuthenticationSuccessEvent(
                TestingAuthenticationToken(inetOrgPerson("kim", "kim@new.example.com"), "n/a", "ROLE_USERS"),
            ),
        )
        assertThat(users.findByUsername("kim")?.email).isEqualTo("kim@new.example.com")

        graphQlTester.document(
            """mutation { setUserEmail(id: ${held.id}, email: "kim@chosen.example.com") { emailChosen } }""",
        ).execute()
            .path("setUserEmail.emailChosen").entity(Boolean::class.java).isEqualTo(true)

        detection.onSignIn(
            AuthenticationSuccessEvent(
                TestingAuthenticationToken(inetOrgPerson("kim", "kim@new.example.com"), "n/a", "ROLE_USERS"),
            ),
        )
        assertThat(users.findByUsername("kim")?.email).isEqualTo("kim@chosen.example.com")
    }

    /**
     * A directory entry as the LDAP context mapper hands one over: the mail
     * attribute on the principal, which is where sign-in reads an address from.
     */
    private fun inetOrgPerson(username: String, mail: String): InetOrgPerson {
        val essence = InetOrgPerson.Essence()
        essence.setUsername(username)
        essence.setDn("uid=$username,ou=people,dc=example,dc=com")
        // An inetOrgPerson insists on a name; the address is what is being tested.
        essence.setCn(arrayOf(username))
        essence.setSn(username)
        essence.setMail(mail)
        essence.setPassword("n/a")
        essence.setAuthorities(listOf(SimpleGrantedAuthority("ROLE_USERS")))
        return essence.createUserDetails() as InetOrgPerson
    }
}
