package io.mszymanski.orknux.server.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.jdbc.JdbcIndexedSessionRepository

/**
 * A password an administrator sets ends the sessions the old one opened. #588.
 *
 * A password is reset because the old one may be known to somebody, and that
 * is worth nothing while that somebody is still signed in on it. The mailed
 * reset already ended them; the screen did not.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PasswordChangeSessionsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val users: AppUserRepository,
    @Autowired val sessions: JdbcIndexedSessionRepository,
    @Autowired val encoder: PasswordEncoder,
) {

    @AfterEach
    fun clear() {
        users.findByUsername(NAME)?.let(users::delete)
        sessions.findByPrincipalName(NAME).keys.forEach(sessions::deleteById)
    }

    @Test
    fun `setting somebody's password ends their sessions`() {
        val user = users.save(AppUser(username = NAME, displayName = NAME, type = UserType.INTERNAL, passwordHash = encoder.encode("an-old-long-password")))
        @Suppress("UNCHECKED_CAST")
        val store = sessions as org.springframework.session.SessionRepository<org.springframework.session.Session>
        val open = store.createSession()
        open.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, NAME)
        store.save(open)
        assertThat(sessions.findByPrincipalName(NAME)).hasSize(1)

        graphQlTester.document("""mutation { setUserPassword(id: ${user.id}, password: "a-new-long-password") { id } }""")
            .execute().errors().verify()

        assertThat(sessions.findByPrincipalName(NAME)).isEmpty()
    }

    private companion object {
        const val NAME = "password-change-check"
    }
}
