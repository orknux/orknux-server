package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.mcp.IssueTools
import io.mszymanski.orknux.server.mcp.NewsTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * Who else wants to hear about an issue.
 *
 * The tracker told exactly two audiences: whoever has an issue and whoever
 * filed it. That is the right pair for work somebody has been handed, and it is
 * nobody at all for work that has not - an assistant filed ten security issues,
 * assigned them to no one because handing out work is not its judgement, wrote
 * carefully on each, and reached an audience of itself. What these pin down is
 * that an observer closes that hole and that the two rules on it hold: anybody
 * may watch, only somebody who administers the workspace may make somebody else
 * watch.
 *
 * The dedup test is the one worth reading twice. The reporter, the assignee and
 * the observers are three lists that overlap, and a person on two of them who
 * gets two copies of every comment learns to stop reading them - which is the
 * same failure as never being told, arrived at from the other side.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueObserverTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val observers: IssueObserverRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val marks: IssueNewsReadRepository,
    @Autowired val newsTools: NewsTools,
    @Autowired val issueTools: IssueTools,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var aliceId: Long = 0
    private var bobId: Long = 0

    @BeforeEach
    fun reset() {
        news.deleteAll()
        marks.deleteAll()
        observers.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        users.deleteAll()
        // Everything but the built-in role, which is this installation's and
        // not any test's to remove.
        roles.deleteAll(roles.findAll().filterNot { it.builtin })

        /*
         * The workspace carries a role, because half of these are asked by
         * somebody who is not an administrator - and a workspace with no roles
         * is administrators only, so a member would be refused before the
         * observer rules were ever reached.
         */
        val support = roles.save(Role(name = "support"))
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support", roles = mutableSetOf(support))).id)

        val admin = roles.findAll().first { it.administers }
        aliceId = requireNotNull(
            users.save(
                AppUser(
                    username = "alice",
                    displayName = "Alice",
                    type = UserType.INTERNAL,
                    roles = mutableSetOf(admin),
                ),
            ).id,
        )
        bobId = requireNotNull(
            users.save(AppUser(username = "bob", displayName = "Bob", type = UserType.INTERNAL)).id,
        )
        users.save(AppUser(username = "claude", displayName = "Claude", type = UserType.INTERNAL))
    }

    private fun file(title: String = "The reply is late"): Long =
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "$title" }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    /**
     * Whoever is asking has to be settable per call rather than per test: half
     * of what these check is that one person's doing is another person's news.
     */
    private fun <T> asUser(name: String, block: () -> T): T {
        val held = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(name, "n/a", emptyList())
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = held
        }
    }

    private fun readAs(name: String): List<Map<String, Any?>> {
        val answer = asUser(name) {
            newsTools.news(OrknuxScope(workspaceId = workspaceId, mayWrite = true), """{"wait": 0}""").join()
        }
        val parsed = mapper.readValue(answer, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        return parsed["news"] as List<Map<String, Any?>>
    }

    @Test
    fun `somebody watches an issue themselves, with nobody named and no role needed`() {
        val id = file()

        graphQlTester.document("""mutation { observeIssue(id: $id) { observers { name hint mine addedBy } } }""")
            .execute()
            .path("observeIssue.observers").entityList(Any::class.java).hasSize(1)
            .path("observeIssue.observers[0].name").entity(String::class.java).isEqualTo("Alice")
            // Answered by the server, so the button and the refusal agree.
            .path("observeIssue.observers[0].mine").entity(Boolean::class.java).isEqualTo(true)
            .path("observeIssue.observers[0].addedBy").entity(String::class.java).isEqualTo("alice")

        // And still there when the issue is read again by its number.
        graphQlTester.document("""{ workspaceIssue(workspaceId: $workspaceId, number: 1) { observers { name } } }""")
            .execute()
            .path("workspaceIssue.observers[0].name").entity(String::class.java).isEqualTo("Alice")

        // Pressing it twice is the same subscription, not an error and not two rows.
        graphQlTester.document("""mutation { observeIssue(id: $id) { observers { name } } }""")
            .execute()
            .path("observeIssue.observers").entityList(Any::class.java).hasSize(1)
    }

    @Test
    fun `an administrator puts somebody else on the list`() {
        val id = file()

        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "$bobId") {
                 observers { name mine addedBy }
               } }""",
        ).execute()
            .path("observeIssue.observers[0].name").entity(String::class.java).isEqualTo("Bob")
            // Not alice's own row, so the page draws the other button.
            .path("observeIssue.observers[0].mine").entity(Boolean::class.java).isEqualTo(false)
            // Who decided, which is what makes the audit line readable afterwards.
            .path("observeIssue.observers[0].addedBy").entity(String::class.java).isEqualTo("alice")

        assertThat(audit.findAll().map { it.message })
            .anySatisfy { assertThat(it).contains("Bob is now an observer") }
    }

    @Test
    @WithMockUser(username = "bob", roles = ["SUPPORT"])
    fun `somebody who does not administer the workspace cannot put anybody else on the list`() {
        val id = issues.save(
            Issue(workspaceId = workspaceId, number = 1, title = "The reply is late", reporter = "alice"),
        ).id

        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "$aliceId") { id } }""",
        ).execute()
            .errors().satisfy { found ->
                // Names the workspace, which is safe here: bob is looking at it,
                // so there is nothing left to give away and a role to go and ask
                // for.
                assertThat(found.first().message).startsWith("This action needs a role that administers support")
            }

        // Themselves, though, which is the whole point of the other half of the rule.
        graphQlTester.document("""mutation { observeIssue(id: $id) { observers { name } } }""")
            .execute()
            .path("observeIssue.observers[0].name").entity(String::class.java).isEqualTo("Bob")

        assertThat(observers.findAll().map { it.observerId }).containsExactly(bobId.toString())
    }

    @Test
    fun `an observer hears what is said on an issue that was never theirs`() {
        val id = file()
        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "$bobId") { id } }""",
        ).execute().path("observeIssue.id").hasValue()

        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "This is worse than it looks") { id } }""")
            .execute().path("commentOnIssue.id").hasValue()

        val told = readAs("bob")
        // Told he is watching it, and then told what was said on it. Neither
        // reaches him without the observer row: it is not his and he did not
        // file it.
        assertThat(told.map { it["what"] }).containsExactly("made you an observer", "commented")
        assertThat(told.last()["said"]).isEqualTo("This is worse than it looks")
        assertThat(told.last()["issue"]).isEqualTo(1)
    }

    @Test
    fun `somebody who is both reporter and observer is told once`() {
        // Filed by bob, so he is already an audience; then made an observer as
        // well, which is the ordinary way two lists come to overlap.
        val id = requireNotNull(
            issues.save(
                Issue(workspaceId = workspaceId, number = 1, title = "The reply is late", reporter = "bob"),
            ).id,
        )
        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "$bobId") { id } }""",
        ).execute().path("observeIssue.id").hasValue()

        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "Looking now") { id } }""")
            .execute().path("commentOnIssue.id").hasValue()

        val told = readAs("bob")
        assertThat(told.filter { it["what"] == "commented" }).hasSize(1)
        assertThat(news.findAll().filter { it.kind == IssueNewsKind.COMMENT }).hasSize(1)
    }

    @Test
    fun `an observer is still told when a closed issue is reopened`() {
        val id = file()
        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "$bobId") { id } }""",
        ).execute().path("observeIssue.id").hasValue()

        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "CLOSED" }) { id } }""").execute()
        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "OPEN" }) { id } }""").execute()

        // Closing does not end a subscription. A closed issue reopening is
        // exactly the thing an observer put themselves on the list to hear.
        assertThat(readAs("bob").map { it["what"] })
            .containsExactly("made you an observer", "closed", "reopened")
    }

    @Test
    fun `a model cannot observe an issue, having nowhere to read its news`() {
        val id = file()

        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: MODEL, observerId: "7") { id } }""",
        ).execute()
            .errors().satisfy { found ->
                assertThat(found.first().message)
                    .isEqualTo("A model is not something in this workspace that can observe an issue")
            }

        assertThat(observers.findAll()).isEmpty()
    }

    @Test
    fun `an administrator takes somebody off the list again`() {
        val id = file()
        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "$bobId") { id } }""",
        ).execute().path("observeIssue.id").hasValue()

        graphQlTester.document(
            """mutation { unobserveIssue(id: $id, observerKind: USER, observerId: "$bobId") { observers { name } } }""",
        ).execute()
            .path("unobserveIssue.observers").entityList(Any::class.java).hasSize(0)

        assertThat(observers.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message })
            .anySatisfy { assertThat(it).contains("Bob is no longer an observer") }
    }

    @Test
    fun `an issue an assistant files with nobody named is put in front of the administrators`() {
        val answer = asUser("claude") {
            issueTools.open(
                OrknuxScope(workspaceId = workspaceId, mayWrite = true),
                """{"title": "Secrets are logged in plain text", "labels": "p1, security"}""",
            )
        }

        val parsed = mapper.readValue(answer, Map::class.java)
        assertThat(parsed["observers"]).isEqualTo(listOf("alice"))
        // The failure this exists for: filed, assigned to nobody, and reaching
        // somebody who can act on it regardless. It reads as "opened" rather than
        // as "made you an observer" because at this moment the news is the issue,
        // not the subscription.
        assertThat(readAs("alice").map { it["what"] }).containsExactly("opened")
        assertThat(observers.findAll().map { it.observerId }).containsExactly(aliceId.toString())
    }

    @Test
    fun `an assistant naming who should see it is taken at its word`() {
        val answer = asUser("claude") {
            issueTools.open(
                OrknuxScope(workspaceId = workspaceId, mayWrite = true),
                """{"title": "The reply is late", "observers": "Bob"}""",
            )
        }

        // Named observers replace the default rather than adding to it: saying
        // who should hear is saying who should hear.
        assertThat(mapper.readValue(answer, Map::class.java)["observers"]).isEqualTo(listOf("bob"))
        assertThat(readAs("bob").map { it["what"] }).containsExactly("opened")
        assertThat(readAs("alice")).isEmpty()
    }

    /**
     * Issue #97.
     *
     * Filing used to tell the assignee and nobody else, so an observer named at
     * the moment an issue was created heard only that they had been made an
     * observer - a sentence about a subscription, arriving in place of the one
     * about the thing worth looking at. Somebody added to an issue that already
     * exists still hears that, which is when it is the true sentence.
     */
    @Test
    fun `an observer named while filing hears that the issue was opened`() {
        asUser("claude") {
            issueTools.open(
                OrknuxScope(workspaceId = workspaceId, mayWrite = true),
                """{"title": "The export writes an empty file", "observers": "Bob"}""",
            )
        }

        val told = readAs("bob")
        assertThat(told.map { it["what"] }).containsExactly("opened")
        assertThat(told.single()["title"]).isEqualTo("The export writes an empty file")
    }

    /** And added afterwards, it is still the subscription that is the news. */
    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `somebody put on an issue that already exists is told they now observe it`() {
        val id = file()

        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "$bobId") { observers { name } } }""",
        ).execute().path("observeIssue.observers[0].name").entity(String::class.java).isEqualTo("Bob")

        assertThat(readAs("bob").map { it["what"] }).containsExactly("made you an observer")
    }

    @Test
    fun `an assistant naming somebody who is not here is told so rather than filing quietly`() {
        val answer = asUser("claude") {
            issueTools.open(
                OrknuxScope(workspaceId = workspaceId, mayWrite = true),
                """{"title": "The reply is late", "observers": "Whoever"}""",
            )
        }

        assertThat(mapper.readValue(answer, Map::class.java)["error"] as String).contains("Whoever")
        // Nothing filed, because an issue that says it went to somebody who was
        // never told is worse than one that was refused.
        assertThat(issues.findAll()).isEmpty()
    }
}
