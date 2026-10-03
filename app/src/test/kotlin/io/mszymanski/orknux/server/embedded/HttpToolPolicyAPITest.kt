package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Admin -> Settings -> HTTP tools, through the API the page uses. Issue #602.
 *
 * The tester is asserted against the same rules the tools read, saved and
 * unsaved, because the reason it exists is that it answers the way a tool
 * call would.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class HttpToolPolicyAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationSettingRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    @BeforeEach
    @AfterEach
    fun clean() {
        settings.deleteAll(settings.findAll().filter { it.name.startsWith("http.tools.") })
        audit.deleteAll()
    }

    private val read = "{ httpToolSettings { enabled policy rules { url methods } methods } }"

    private fun save(policy: String, rules: String) = graphQlTester.document(
        """mutation { saveHttpToolPolicy(input: { policy: $policy, rules: [$rules] }) { enabled policy rules { url methods } } }""",
    ).execute()

    private fun check(url: String, method: String, draft: String? = null) = graphQlTester.document(
        """{ httpToolCheck(url: "$url", method: "$method"${draft?.let { ", draft: $it" } ?: ""}) {
             allowed outcome matchedRule urlMatches message } }""",
    ).execute()

    @Test
    fun `an installation nobody has touched has them on and allows any URL`() {
        val answer = graphQlTester.document(read).execute()
        answer.path("httpToolSettings.enabled").entity(Boolean::class.java).isEqualTo(true)
        answer.path("httpToolSettings.policy").entity(String::class.java).isEqualTo("ANY")
        answer.path("httpToolSettings.rules").entityList(Any::class.java).hasSize(0)
        answer.path("httpToolSettings.methods").entityList(String::class.java)
            .containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        check("https://anywhere.example/x", "DELETE").path("httpToolCheck.outcome").entity(String::class.java)
            .isEqualTo("ANY_URL")
    }

    @Test
    fun `a list is saved in order, read back, and audited`() {
        save(
            "LIST",
            """{ url: "https://api\\.example\\.com/.*", methods: ["get", "POST"] },
               { url: "https://status\\.example\\.com/", methods: ["HEAD", "GET"] }""",
        ).path("saveHttpToolPolicy.rules[*].url").entityList(String::class.java)
            .containsExactly("https://api\\.example\\.com/.*", "https://status\\.example\\.com/")

        val answer = graphQlTester.document(read).execute()
        answer.path("httpToolSettings.policy").entity(String::class.java).isEqualTo("LIST")
        // Normalised to upper case and to the order the methods are offered in.
        answer.path("httpToolSettings.rules[0].methods").entityList(String::class.java).containsExactly("GET", "POST")
        answer.path("httpToolSettings.rules[1].methods").entityList(String::class.java).containsExactly("GET", "HEAD")

        val written = audit.findAll().single()
        assertThat(written.message).isEqualTo("HTTP tools set to an allow list of 2 rules")
        assertThat(written.category).isEqualTo(WorkspaceAuditCategory.INTEGRATION)

        // And a shorter list leaves nothing of the longer one behind.
        save("LIST", """{ url: "https://only\\.example/", methods: ["GET"] }""")
        graphQlTester.document(read).execute().path("httpToolSettings.rules[*].url")
            .entityList(String::class.java).containsExactly("https://only\\.example/")
    }

    @Test
    fun `an invalid pattern is refused, names its rule, and changes nothing`() {
        save("LIST", """{ url: "https://ok\\.example/.*", methods: ["GET"] }""")

        save("LIST", """{ url: "https://ok\\.example/.*", methods: ["GET"] }, { url: "https://(broken", methods: ["GET"] }""")
            .errors().satisfy { errors ->
                val refusal = errors.single()
                assertThat(refusal.extensions["code"]).isEqualTo("HttpToolRulePatternInvalid")
                assertThat(refusal.message).startsWith("The URL pattern of rule 2 is not a regular expression")
            }
        save("LIST", """{ url: "https://ok\\.example/", methods: [] }""")
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("HttpToolRuleMethodsMissing") }
        save("LIST", """{ url: "https://ok\\.example/", methods: ["TRACE"] }""")
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("HttpToolRuleMethodUnknown") }
        save("LIST", """{ url: "  ", methods: ["GET"] }""")
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("HttpToolRulePatternMissing") }

        graphQlTester.document(read).execute().path("httpToolSettings.rules[*].url")
            .entityList(String::class.java).containsExactly("https://ok\\.example/.*")
        assertThat(audit.findAll()).hasSize(1)
    }

    @Test
    fun `switching off and on is audited and keeps the list`() {
        save("LIST", """{ url: "https://ok\\.example/.*", methods: ["GET"] }""")
        graphQlTester.document("mutation { setHttpToolsEnabled(enabled: false) { enabled rules { url } } }").execute()
            .path("setHttpToolsEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)
            .path("setHttpToolsEnabled.rules").entityList(Any::class.java).hasSize(1)

        check("https://ok.example/a", "GET").path("httpToolCheck.outcome").entity(String::class.java)
            .isEqualTo("SWITCHED_OFF")

        graphQlTester.document("mutation { setHttpToolsEnabled(enabled: true) { enabled } }").execute()
            .path("setHttpToolsEnabled.enabled").entity(Boolean::class.java).isEqualTo(true)
        assertThat(audit.findAll().map { it.message })
            .contains("HTTP tools switched off", "HTTP tools switched on")
        check("https://ok.example/a", "GET").path("httpToolCheck.allowed").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `the tester says which rule allowed it, or why none did, for the saved rules`() {
        save(
            "LIST",
            """{ url: "https://api\\.example\\.com/.*", methods: ["GET"] },
               { url: "https://api\\.example\\.com/tickets.*", methods: ["POST"] }""",
        )

        check("https://api.example.com/tickets/1", "POST").path("httpToolCheck.outcome").entity(String::class.java)
            .isEqualTo("ALLOWED")
            .path("httpToolCheck.matchedRule").entity(Int::class.java).isEqualTo(2)
            .path("httpToolCheck.urlMatches").entityList(Int::class.java).containsExactly(1, 2)

        check("https://api.example.com/users", "DELETE").path("httpToolCheck.outcome").entity(String::class.java)
            .isEqualTo("METHOD_NOT_LISTED")
            .path("httpToolCheck.allowed").entity(Boolean::class.java).isEqualTo(false)
            .path("httpToolCheck.urlMatches").entityList(Int::class.java).containsExactly(1)
            .path("httpToolCheck.message").entity(String::class.java).satisfies {
                assertThat(it).contains("matches rule 1", "allows GET but not DELETE", "http_allowList")
            }

        // The whole URL, not a part of it: the same host under another scheme is not matched.
        check("http://api.example.com/users", "GET").path("httpToolCheck.outcome").entity(String::class.java)
            .isEqualTo("NO_RULE_MATCHES")
            .path("httpToolCheck.matchedRule").valueIsNull()
        check("https://api.example.com.evil.example/x", "GET").path("httpToolCheck.outcome")
            .entity(String::class.java).isEqualTo("NO_RULE_MATCHES") // a host that merely begins the same way
    }

    @Test
    fun `the tester reads the rules on screen before they are saved, and refuses a draft Save would refuse`() {
        val draft = """{ policy: LIST, rules: [{ url: "https://draft\\.example/.*", methods: ["PUT"] }] }"""
        check("https://draft.example/a", "PUT", draft).path("httpToolCheck.outcome").entity(String::class.java)
            .isEqualTo("ALLOWED")
        // Nothing was saved by asking.
        check("https://draft.example/a", "PUT").path("httpToolCheck.outcome").entity(String::class.java)
            .isEqualTo("ANY_URL")

        check("https://x.example/", "GET", """{ policy: LIST, rules: [{ url: "(", methods: ["GET"] }] }""")
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("HttpToolRulePatternInvalid") }
        check("https://x.example/", "GET", """{ policy: LIST, rules: [] }""").path("httpToolCheck.outcome")
            .entity(String::class.java).isEqualTo("NO_RULE_MATCHES")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who is not an administrator sees and changes nothing`() {
        graphQlTester.document(read).execute().errors().satisfy { assertThat(it).isNotEmpty() }
        check("https://x.example/", "GET").errors().satisfy { assertThat(it).isNotEmpty() }
        save("LIST", """{ url: "https://x\\.example/", methods: ["GET"] }""")
            .errors().satisfy { assertThat(it).isNotEmpty() }
        graphQlTester.document("mutation { setHttpToolsEnabled(enabled: false) { enabled } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        assertThat(settings.findAll().map { it.name }).noneMatch { it.startsWith("http.tools.") }
        assertThat(audit.findAll()).isEmpty()
    }
}
