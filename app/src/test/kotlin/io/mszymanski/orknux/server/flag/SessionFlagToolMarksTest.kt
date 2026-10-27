package io.mszymanski.orknux.server.flag

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.SettingNames
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser

/**
 * The flag tools' rows on an agent's Tools list are the installation's to
 * show, and they are not shown until somebody says so.
 *
 * Off - the default - `builtInTools` lists neither, and every agent holds both
 * at Always: a save that hides them or takes their Always mark off, from any
 * door, is put back rather than refused. On, they are listed and the agent's
 * marks stand. Switched off again, every agent is put back to Always.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionFlagToolMarksTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val marks: SessionFlagToolMarks,
    @Autowired val held: InstallationSettingRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var was: String? = null

    @BeforeEach
    fun make() {
        was = held.findByIdOrNull(SettingNames.SESSION_FLAG_TOOLS_ADJUSTABLE)?.value
        held.deleteById(SettingNames.SESSION_FLAG_TOOLS_ADJUSTABLE)
        // A workspace that lets its agents hide built-ins, so the flag tools could be hidden at all.
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "flag-marks-${System.nanoTime()}", unsafeBuiltInTools = true)).id)
    }

    @AfterEach
    fun restore() {
        val before = was
        if (before == null) held.deleteById(SettingNames.SESSION_FLAG_TOOLS_ADJUSTABLE)
        else marks.set(before.toBoolean(), "alice")
    }

    private fun rows(): List<String> =
        graphQlTester.document("{ builtInTools(workspaceId: $workspaceId) { name } }").execute()
            .path("builtInTools[*].name").entityList(String::class.java).get()

    private fun created(name: String): Long =
        graphQlTester.document("""mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM, orknuxAccess: false }) { id } }""")
            .execute().path("createAgent.id").entity(Long::class.java).get()

    /** A save as the form sends one with no row for either tool: nothing granted for them, nothing marked Always. */
    private fun demote(id: Long, name: String) {
        graphQlTester.document("""mutation { updateAgent(id: $id, input: { name: "$name", tools: [], requiredTools: [] }) { id } }""")
            .execute().errors().verify()
    }

    private fun agent(id: Long) = requireNotNull(agents.findByIdOrNull(id))

    @Test
    fun `off by default, the rows are not listed and a save cannot take the tools off Always`() {
        assertThat(settings.sessionFlagToolsAdjustable()).isFalse()
        graphQlTester.document("{ installationSettings { sessionFlagToolsAdjustable } }")
            .execute().path("installationSettings.sessionFlagToolsAdjustable").entity(Boolean::class.java).isEqualTo(false)
        assertThat(rows()).isNotEmpty().doesNotContain(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)

        val id = created("Held")
        // Nor in what the agent query answers, which the form draws a row for any granted name from.
        val view = graphQlTester.document("{ agent(id: $id) { tools requiredTools } }").execute().path("agent").entity(Map::class.java).get()
        assertThat(view["tools"] as List<*>).isNotEmpty().doesNotContain(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        assertThat(view["requiredTools"] as List<*>).doesNotContain(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        demote(id, "Held")

        val saved = agent(id)
        assertThat(saved.hiddenTools).doesNotContain(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        assertThat(saved.requiredTools).contains(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        // The save did what it said for everything else: the clock is hidden, so it was not ignored wholesale.
        assertThat(saved.hiddenTools).isNotEmpty()
    }

    @Test
    fun `on, the rows are listed and an agent's marks stand, and switched off again every agent is put back to Always`() {
        marks.set(true, "alice")
        assertThat(rows()).contains(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        val listed = created("Listed")
        assertThat(graphQlTester.document("{ agent(id: $listed) { requiredTools } }").execute().path("agent.requiredTools").entityList(String::class.java).get())
            .contains(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)

        val id = created("Adjusted")
        demote(id, "Adjusted")
        val adjusted = agent(id)
        assertThat(adjusted.hiddenTools).contains(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        assertThat(adjusted.requiredTools).doesNotContain(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)

        graphQlTester.document("mutation { setSessionFlagToolsAdjustable(adjustable: false) { sessionFlagToolsAdjustable } }")
            .execute().path("setSessionFlagToolsAdjustable.sessionFlagToolsAdjustable").entity(Boolean::class.java).isEqualTo(false)

        val reset = agent(id)
        assertThat(reset.hiddenTools).doesNotContain(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        assertThat(reset.requiredTools).contains(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        assertThat(rows()).doesNotContain(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
    }

    @Test
    fun `only an administrator switches it, and each switch is audited`() {
        val context = SecurityContextHolder.getContext()
        val before = context.authentication
        context.authentication = UsernamePasswordAuthenticationToken("bob", "n/a", listOf(SimpleGrantedAuthority("ROLE_USERS")))
        try {
            graphQlTester.document("mutation { setSessionFlagToolsAdjustable(adjustable: true) { sessionFlagToolsAdjustable } }")
                .execute().errors().satisfy { assertThat(it).isNotEmpty() }
        } finally {
            context.authentication = before
        }
        assertThat(settings.sessionFlagToolsAdjustable()).isFalse()

        val shown = count(SHOWN)
        val hidden = count(HIDDEN)
        graphQlTester.document("mutation { setSessionFlagToolsAdjustable(adjustable: true) { sessionFlagToolsAdjustable } }")
            .execute().path("setSessionFlagToolsAdjustable.sessionFlagToolsAdjustable").entity(Boolean::class.java).isEqualTo(true)
        assertThat(count(SHOWN)).isEqualTo(shown + 1)
        graphQlTester.document("mutation { setSessionFlagToolsAdjustable(adjustable: false) { sessionFlagToolsAdjustable } }")
            .execute().path("setSessionFlagToolsAdjustable.sessionFlagToolsAdjustable").entity(Boolean::class.java).isEqualTo(false)
        assertThat(count(HIDDEN)).isEqualTo(hidden + 1)
    }

    private fun count(message: String) = audit.findAll().count { it.message == message && it.workspaceId == null }

    private companion object {
        const val SHOWN = "Flag tools shown on agents' Tools lists"
        const val HIDDEN = "Flag tools hidden from agents' Tools lists and held at Always"
    }
}
