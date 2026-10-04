package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Access tokens a person makes for themselves, from Preferences. Issue #2.
 *
 * The mutations were already there for an administrator; what this pins is
 * that somebody with no administrator role can make, list and revoke their own,
 * that what they do is written to the audit log the way the admin page's is,
 * and that one person's tokens are invisible and untouchable to another.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class UserTokenAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val users: AppUserRepository,
    @Autowired val tokens: AppUserTokenRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var bobId: Long = 0
    private var carolId: Long = 0

    @BeforeEach
    fun reset() {
        tokens.deleteAll()
        users.deleteAll()
        bobId = requireNotNull(users.save(AppUser(username = "bob", displayName = "Bob", type = UserType.INTERNAL)).id)
        carolId = requireNotNull(users.save(AppUser(username = "carol", displayName = "Carol", type = UserType.INTERNAL)).id)
        users.save(AppUser(username = "erin", displayName = "Erin", type = UserType.EXTERNAL))
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `a person makes, lists and revokes their own token without being an administrator`() {
        val made = graphQlTester.document(
            """mutation { createUserToken(name: "  laptop  ") { secret token { id name lastUsedAt } } }""",
        ).execute()
        val secret = made.path("createUserToken.secret").entity(String::class.java).get()
        made.path("createUserToken.token.name").entity(String::class.java).isEqualTo("laptop")
        val id = made.path("createUserToken.token.id").entity(String::class.java).get()

        assertThat(secret).startsWith("orkx_")
        // Held as a hash, never as itself.
        assertThat(tokens.findAll().map { it.tokenHash }).doesNotContain(secret)
        assertThat(internal.authenticateToken(secret)?.name).isEqualTo("bob")

        graphQlTester.document("{ myTokens { id name } }").execute()
            .path("myTokens[*].name").entityList(String::class.java).containsExactly("laptop")
        // The list never carries a secret, because there is no field to carry one in.
        graphQlTester.document("{ myTokens { secret } }").execute()
            .errors().expect { true }.verify()

        graphQlTester.document("""mutation { deleteUserToken(id: $id) }""").execute()
            .path("deleteUserToken").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("{ myTokens { id } }").execute()
            .path("myTokens").entityList(Any::class.java).hasSize(0)
        assertThat(internal.authenticateToken(secret)).isNull()

        val written = audit.findAll().filter { it.workspaceId == null }.map { it.message }
        assertThat(written).contains("Access token laptop created for bob", "Access token laptop revoked for bob")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `one person can neither see nor revoke another's tokens`() {
        val carol = users.findById(carolId).get()
        val (theirs, secret) = internal.mint(carol, "carol's")
        internal.mint(users.findById(bobId).get(), "bob's")
        val writtenBefore = audit.count()

        graphQlTester.document("{ myTokens { name } }").execute()
            .path("myTokens[*].name").entityList(String::class.java).containsExactly("bob's")

        graphQlTester.document("""{ userTokens(id: $carolId) { name } }""").execute()
            .errors().expect { it.message?.contains("administrator") == true }.verify()

        // Answered as though there were no such token, so ids cannot be probed.
        graphQlTester.document("""mutation { deleteUserToken(id: ${theirs.id}) }""").execute()
            .errors().expect { it.extensions["code"] == "TokenNotFound" }.verify()
        assertThat(tokens.findById(requireNotNull(theirs.id))).isPresent
        assertThat(internal.authenticateToken(secret)?.name).isEqualTo("carol")

        // Nor can one be made in somebody else's name.
        graphQlTester.document("""mutation { createUserToken(id: $carolId, name: "mine now") { secret } }""").execute()
            .errors().expect { it.message?.contains("administrator") == true }.verify()
        assertThat(tokens.findAll().map { it.name }).containsExactlyInAnyOrder("carol's", "bob's")
        // Nothing happened, so nothing was written.
        assertThat(audit.count()).isEqualTo(writtenBefore)
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `an administrator still revokes anybody's, and it is written down`() {
        val (theirs, _) = internal.mint(users.findById(carolId).get(), "build server")

        graphQlTester.document("""mutation { deleteUserToken(id: ${theirs.id}) }""").execute()
            .path("deleteUserToken").entity(Boolean::class.java).isEqualTo(true)

        assertThat(audit.findAll().map { it.message }).contains("Access token build server revoked for carol")
    }

    @Test
    @WithMockUser(username = "erin", roles = ["USERS"])
    fun `somebody the identity provider owns is told why there is no token for them`() {
        graphQlTester.document("""mutation { createUserToken(name: "laptop") { secret } }""").execute()
            .errors().expect {
                it.extensions["code"] == "TokenNotIssuable" && it.message?.contains("access token") == true
            }.verify()
        assertThat(tokens.count()).isZero()
    }
}
