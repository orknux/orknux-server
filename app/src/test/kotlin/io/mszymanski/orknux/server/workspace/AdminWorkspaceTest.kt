package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.RoleScope
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * Admin workspaces: a workspace whose agents read other workspaces and the
 * installation's admin pages, which only installation administrators see.
 *
 * What is pinned is the security of it. Nobody but an administrator sees one,
 * whatever roles are written on it; it cannot be switched on while somebody
 * else could open it, nor opened to somebody else while it is one; an ordinary
 * workspace's agent is offered none of its tools and is refused them by name;
 * the reach list and every switch are read at the call, from the workspace's
 * own row; a session id from outside the workspace named is not read even when
 * an administrator is the one chatting; and no credential crosses.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class AdminWorkspaceTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val settings: AdminWorkspaceRepository,
    @Autowired val adminUsers: WorkspaceAdminUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val agentTools: AgentTools,
    @Autowired val orknux: OrknuxTools,
    @Autowired val access: WorkspaceAccess,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val tunables: io.mszymanski.orknux.server.tuning.Tunables,
) {

    private var backendId = 0L
    private var frontendId = 0L
    private var opsId = 0L
    private lateinit var backendRole: Role
    private lateinit var opsAdmins: Role

    @BeforeEach
    fun seed() {
        events.deleteAll()
        sessions.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        agents.deleteAll()
        adminUsers.deleteAll()
        settings.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        users.findByUsername(DANA)?.let(users::delete)
        users.findByUsername(BOB)?.let(users::delete)
        roles.deleteAll(roles.findAll().filterNot { it.builtin })

        backendRole = roles.save(Role(name = "backend"))
        val frontendRole = roles.save(Role(name = "frontend"))
        opsAdmins = roles.save(Role(name = "ops-admins", scopes = mutableSetOf(RoleScope.ADMIN, RoleScope.USER)))
        backendId = requireNotNull(workspaces.save(Workspace(name = "backend", roles = mutableSetOf(backendRole))).id)
        frontendId = requireNotNull(workspaces.save(Workspace(name = "frontend", roles = mutableSetOf(frontendRole))).id)
        opsId = requireNotNull(workspaces.save(Workspace(name = "ops")).id)
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    // ---- Who sees one ----

    /**
     * Bob holds the role written on it - written by hand, past every guard -
     * and still does not see it: the access check decides on the flag.
     */
    @Test
    @WithMockUser(username = BOB, roles = ["BACKEND"])
    fun `somebody who is not an administrator cannot list, open or reach an admin workspace`() {
        makeAdmin(opsId, roles = mutableSetOf(backendRole))

        graphQlTester.document("""query { workspaces { content { name } totalElements } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java).containsExactly("backend")
        graphQlTester.document("""query { workspace(id: $opsId) { name } }""")
            .execute().path("workspace").valueIsNull()
        graphQlTester.document("""query { workspaceWorkflows(workspaceId: $opsId) { totalElements } }""")
            .execute().errors().satisfy { assertThat(it.single().message).isEqualTo("No workspace with id $opsId") }
        // Nor its tool rows, which would say it is one.
        graphQlTester.document("""query { builtInTools(workspaceId: $opsId) { name } }""")
            .execute().path("builtInTools[*].name").entityList(String::class.java)
            .get().let { names -> assertThat(names).noneMatch { it.startsWith("admin_") } }
    }

    /** Asked again on every call, so the role going is sight going - there is nothing to sign out of. */
    @Test
    fun `an administrator sees it, and losing the administrator role loses sight of it at once`() {
        makeAdmin(opsId, roles = mutableSetOf(backendRole))
        val ops = requireNotNull(workspaces.findByIdOrNull(opsId))

        signIn("carol", "ROLE_ADMINS")
        assertThat(access.canSee(ops)).isTrue()

        signIn("carol", "ROLE_BACKEND")
        assertThat(access.canSee(ops)).isFalse()
        assertThat(access.canSee(requireNotNull(workspaces.findByIdOrNull(backendId)))).isTrue()
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `an administrator sees it in the list, marked`() {
        makeAdmin(opsId)
        graphQlTester.document("""query { workspace(id: $opsId) { name adminWorkspace } }""")
            .execute().path("workspace.adminWorkspace").entity(Boolean::class.java).isEqualTo(true)
    }

    // ---- Switching one on, and keeping it closed ----

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `switching on is refused while a role that is not the administrator's opens it`() {
        val ops = requireNotNull(workspaces.findByIdOrNull(opsId))
        ops.roles = mutableSetOf(backendRole)
        workspaces.save(ops)

        graphQlTester.document(switch(opsId, enabled = true))
            .execute().errors().satisfy { errors ->
                val error = errors.single()
                assertThat(error.message).contains("ops cannot be an admin workspace").contains("take backend off it first")
                assertThat(error.extensions["code"]).isEqualTo("AdminWorkspaceOpenToOthers")
            }
        assertThat(workspaces.findByIdOrNull(opsId)?.adminWorkspace).isFalse()

        // A role that makes its holders administrators is no obstacle.
        ops.roles = mutableSetOf(opsAdmins)
        workspaces.save(ops)
        graphQlTester.document(switch(opsId, enabled = true))
            .execute().path("setAdminWorkspace.enabled").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `switching on is refused while somebody who is not an administrator administers it`() {
        users.save(AppUser(username = BOB, displayName = "Bob", type = UserType.INTERNAL))
        adminUsers.save(WorkspaceAdminUser(id = WorkspaceAdminUserId(opsId, BOB), grantedBy = "alice"))

        graphQlTester.document(switch(opsId, enabled = true))
            .execute().errors().satisfy { assertThat(it.single().message).contains("stop bob administering it first") }
        assertThat(workspaces.findByIdOrNull(opsId)?.adminWorkspace).isFalse()
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `while it is one, a role that is not the administrator's cannot be put on it`() {
        makeAdmin(opsId)

        graphQlTester.document("""mutation { updateWorkspace(id: $opsId, input: { name: "ops", roleIds: [${backendRole.id}] }) { id } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().message).startsWith("ops is an admin workspace, and only installation administrators may open one: backend")
                assertThat(errors.single().extensions["code"]).isEqualTo("AdminWorkspaceMemberRefused")
            }
        assertThat(workspaces.findByIdOrNull(opsId)?.roles).isEmpty()

        graphQlTester.document("""mutation { updateWorkspace(id: $opsId, input: { name: "ops", roleIds: [${opsAdmins.id}] }) { id } }""")
            .execute().errors().verify()
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `the Admin scope cannot be taken off a role that opens an admin workspace`() {
        makeAdmin(opsId, roles = mutableSetOf(opsAdmins))

        graphQlTester.document("""mutation { updateRole(id: ${opsAdmins.id}, input: { name: "ops-admins", scopes: [USER] }) { id } }""")
            .execute().errors().satisfy { assertThat(it.single().message).contains("ops-admins does not make somebody an administrator") }
        assertThat(roles.findByIdOrNull(requireNotNull(opsAdmins.id))?.administers).isTrue()
    }

    @Test
    @WithMockUser(username = BOB, roles = ["BACKEND"])
    fun `only an administrator reads or changes what an admin workspace may do`() {
        graphQlTester.document("""query { adminWorkspace(workspaceId: $backendId) { enabled } }""")
            .execute().errors().satisfy { assertThat(it.single().message).isEqualTo("This action requires the administrator role") }
        graphQlTester.document(switch(backendId, enabled = true))
            .execute().errors().satisfy { assertThat(it.single().message).isEqualTo("This action requires the administrator role") }
        assertThat(workspaces.findByIdOrNull(backendId)?.adminWorkspace).isFalse()
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `every switch that changes writes an audit line`() {
        graphQlTester.document(switch(opsId, enabled = true)).execute().errors().verify()
        graphQlTester.document(
            switch(opsId, enabled = true, reachAll = false, reach = listOf(backendId), scopes = "[SESSIONS, AGENTS]", tools = "[DOCTOR, USERS]"),
        ).execute().errors().verify()
        graphQlTester.document(switch(opsId, enabled = false, reachAll = false, reach = listOf(backendId), scopes = "[SESSIONS, AGENTS]", tools = "[DOCTOR, USERS]"))
            .execute().errors().verify()

        val said = audit.findAll().filter { it.workspaceId == opsId }.map { it.message }
        assertThat(said).contains(
            "Admin workspace switched on",
            "Admin workspace reaches backend",
            "Admin workspace may no longer read chats in other workspaces",
            "Admin workspace may no longer read reports in other workspaces",
            "Admin workspace tool admin_monitoring switched off",
            "Admin workspace tool admin_users switched on",
            "Admin workspace switched off",
        )
    }

    // ---- The tools ----

    @Test
    fun `an ordinary workspace's agent is offered none of them and is refused them by name`() {
        val agent = agent(backendId)
        assertThat(agentTools.specsFor(agent).map { it.name }).noneMatch { it in AdminWorkspaceTools.NAMES }

        AdminWorkspaceTools.NAMES.forEach { name ->
            assertThat(call(agent, name, """{"workspace":"frontend","kind":"sessions"}""")).isEqualTo("""{"error":"There is no tool called $name"}""")
        }
    }

    @Test
    fun `an admin workspace's agent is offered the readers and the admin pages switched on, every one read only`() {
        makeAdmin(opsId)
        val offered = agentTools.specsFor(agent(opsId))
        val names = offered.map { it.name }

        assertThat(names).contains(AdminWorkspaceTools.WORKSPACES, AdminWorkspaceTools.LIST, AdminWorkspaceTools.READ)
        assertThat(names).contains("admin_doctor", "admin_monitoring", "admin_reports", "admin_templates", "admin_plugins")
        // About people, so off until somebody decides.
        assertThat(names).doesNotContain("admin_users", "admin_roles", "admin_audit")
        assertThat(offered.filter { it.name in AdminWorkspaceTools.NAMES }).allMatch { it.readOnly }
    }

    @Test
    fun `the reach list bounds what is read, and out of reach answers as not there`() {
        makeAdmin(opsId, reachAll = false, reach = mutableSetOf(backendId))
        conversation(backendId, "in-reach")
        conversation(frontendId, "out-of-reach")
        val agent = agent(opsId)

        val listed = mapper.readTree(call(agent, AdminWorkspaceTools.LIST, """{"workspace":"backend","kind":"sessions"}"""))
        assertThat(listed.path("sessions").toList().map { it.path("key").stringValue() }).containsExactly("slack_:in-reach")

        assertThat(call(agent, AdminWorkspaceTools.LIST, """{"workspace":"frontend","kind":"sessions"}"""))
            .isEqualTo("""{"error":"There is no workspace frontend within this admin workspace's reach."}""")
        assertThat(call(agent, AdminWorkspaceTools.LIST, """{"workspace":"$frontendId","kind":"sessions"}"""))
            .isEqualTo("""{"error":"There is no workspace $frontendId within this admin workspace's reach."}""")
        assertThat(call(agent, AdminWorkspaceTools.LIST, """{"workspace":"nowhere","kind":"sessions"}"""))
            .isEqualTo("""{"error":"There is no workspace nowhere within this admin workspace's reach."}""")

        val reached = mapper.readTree(call(agent, AdminWorkspaceTools.WORKSPACES, "{}"))
        assertThat(reached.path("workspaces").toList().map { it.path("name").stringValue() }).containsExactly("backend")
    }

    /** How many rows a read answers is Admin Settings', and the tool says the numbers as they are set. */
    @Test
    fun `how many rows a read answers is set in Admin Settings, and the tool says so`() {
        makeAdmin(opsId, reachAll = false, reach = mutableSetOf(backendId))
        repeat(3) { conversation(backendId, "rows-$it") }
        val agent = agent(opsId)
        val most = io.mszymanski.orknux.server.tuning.Tunable.ADMIN_WORKSPACE_ROWS_MOST
        val default = io.mszymanski.orknux.server.tuning.Tunable.ADMIN_WORKSPACE_ROWS_DEFAULT
        try {
            tunables.set(default, 1, "alice")
            tunables.set(most, 2, "alice")
            fun count(arguments: String) =
                mapper.readTree(call(agent, AdminWorkspaceTools.LIST, arguments)).path("sessions").toList().size
            assertThat(count("""{"workspace":"backend","kind":"sessions"}""")).isEqualTo(1)
            assertThat(count("""{"workspace":"backend","kind":"sessions","limit":50}""")).isEqualTo(2)
            val described = agentTools.specsFor(agent).single { it.name == AdminWorkspaceTools.LIST }
                .parameters.single { it.name == "limit" }.description
            assertThat(described).contains("1 by default").contains("2 at most")
        } finally {
            tunables.set(default, null, "alice")
            tunables.set(most, null, "alice")
        }
    }

    /**
     * An administrator chatting sees every workspace, and the readers ask
     * whoever is signed in. Read as the workspace named and nobody else, a
     * session of another workspace is not there whoever is at the screen.
     */
    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `a session of another workspace is not read through the one named, even with an administrator chatting`() {
        makeAdmin(opsId, reachAll = false, reach = mutableSetOf(backendId))
        val elsewhere = conversation(frontendId, "out-of-reach")

        val answer = call(agent(opsId), AdminWorkspaceTools.READ, """{"workspace":"backend","kind":"sessions","id":"$elsewhere"}""")
        assertThat(answer).isEqualTo("""{"error":"There is no conversation $elsewhere here"}""")
        // And whoever was signed in is signed in still.
        assertThat(SecurityContextHolder.getContext().authentication?.name).isEqualTo("alice")
    }

    @Test
    fun `a scope switched off is refused in words`() {
        makeAdmin(opsId, scopes = (AdminWorkspaceScope.entries - AdminWorkspaceScope.SESSIONS).toMutableSet())
        val id = conversation(backendId, "in-reach")

        assertThat(call(agent(opsId), AdminWorkspaceTools.READ, """{"workspace":"backend","kind":"sessions","id":"$id"}"""))
            .isEqualTo("""{"error":"Reading sessions in other workspaces is switched off for this admin workspace, in Admin > Workspaces."}""")
        // The kind the model is offered no longer names it.
        val read = agentTools.specsFor(agent(opsId)).single { it.name == AdminWorkspaceTools.READ }
        assertThat(read.parameters.single { it.name == "kind" }.description).doesNotContain("sessions")
    }

    @Test
    fun `an admin page switched off is refused, and switched on it reads`() {
        makeAdmin(opsId)
        users.save(AppUser(username = DANA, displayName = "Dana Scully", type = UserType.INTERNAL))
        val agent = agent(opsId)

        assertThat(call(agent, "admin_users", "{}"))
            .isEqualTo("""{"error":"admin_users is switched off for this admin workspace, in Admin > Workspaces."}""")

        val held = requireNotNull(settings.findByIdOrNull(opsId))
        held.tools.add(AdminWorkspaceTool.USERS)
        held.tools.add(AdminWorkspaceTool.ROLES)
        settings.save(held)

        assertThat(call(agent, "admin_users", """{"search":"dana"}""")).contains("Dana Scully")
        assertThat(call(agent, "admin_roles", "{}")).contains("ops-admins")
        // Read as an administrator for that one query, and nobody afterwards.
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    /** The frame is frozen; the switch is not. Off is refused from the next call. */
    @Test
    fun `switching the workspace off takes the tools away from the next call`() {
        makeAdmin(opsId)
        val agent = agent(opsId)
        assertThat(call(agent, AdminWorkspaceTools.WORKSPACES, "{}")).contains("backend")

        val ops = requireNotNull(workspaces.findByIdOrNull(opsId))
        ops.adminWorkspace = false
        workspaces.save(ops)

        assertThat(agentTools.specsFor(agent).map { it.name }).noneMatch { it in AdminWorkspaceTools.NAMES }
        assertThat(call(agent, AdminWorkspaceTools.WORKSPACES, "{}")).isEqualTo("""{"error":"There is no tool called admin_workspaces"}""")
    }

    /**
     * A secret read by an agent in the workspace being read is in its log -
     * that is what a granted secret is for. It does not leave: not by value,
     * though nothing about its shape says it is one.
     */
    @Test
    fun `no secret crosses into an admin workspace`() {
        makeAdmin(opsId)
        val catalog = catalogs.save(VariableCatalog(workspaceId = backendId, name = "keys"))
        variables.save(
            WorkspaceVariable(workspaceId = backendId, catalogId = requireNotNull(catalog.id), name = "deploy_key", kind = VariableKind.SECRET).also {
                it.value = SECRET
            },
        )
        val id = recorder.open(backendId, "slack_", "leaky")
        recorder.userSaid(id, "alice", "The deploy key is $SECRET, keep it safe")
        recorder.toolReturned(recorder.toolCalled(id, "variable_get", """{"name":"deploy_key"}"""), """{"value":"$SECRET"}""")
        recorder.agentSaid(id, "Deployer", "Deployed with $SECRET")

        val answer = call(agent(opsId), AdminWorkspaceTools.READ, """{"workspace":"backend","kind":"sessions","id":"$id"}""")

        assertThat(answer).doesNotContain(SECRET).contains("[secret]")
        assertThat(mapper.readTree(answer).path("lines").size()).isEqualTo(3)
    }

    /** The MCP surface is the orknux_* tools, and these are not among them, whoever asks. */
    @Test
    fun `the orknux surface the MCP endpoint serves neither offers nor runs them`() {
        makeAdmin(opsId)
        val scope = OrknuxScope(workspaceId = opsId, mayWrite = true)
        assertThat(orknux.specs(scope).map { it.name }).noneMatch { it in AdminWorkspaceTools.NAMES }
        AdminWorkspaceTools.NAMES.forEach { name ->
            assertThat(orknux.run(scope, name, """{"workspace":"backend","kind":"sessions"}""")).contains("There is no tool called $name")
        }
    }

    // ---- Helpers ----

    private fun makeAdmin(
        id: Long,
        roles: MutableSet<Role> = mutableSetOf(),
        reachAll: Boolean = true,
        reach: MutableSet<Long> = mutableSetOf(),
        scopes: MutableSet<AdminWorkspaceScope> = AdminWorkspaceScope.STARTS_ON.toMutableSet(),
    ) {
        val workspace = requireNotNull(workspaces.findByIdOrNull(id))
        workspace.adminWorkspace = true
        workspace.roles = roles
        workspaces.save(workspace)
        settings.save(AdminWorkspace(workspaceId = id, reachAll = reachAll, reach = reach, scopes = scopes))
    }

    private fun agent(workspaceId: Long) =
        agents.findAll().firstOrNull { it.workspaceId == workspaceId }
            ?: agents.save(Agent(workspaceId = workspaceId, name = "Overseer $workspaceId", type = AgentType.LLM))

    private fun call(agent: Agent, name: String, arguments: String) = agentTools.run(agent, ToolCall(id = "c1", name = name, arguments = arguments))

    private fun conversation(workspaceId: Long, key: String): Long {
        val id = recorder.open(workspaceId, "slack_", key)
        recorder.userSaid(id, "alice", "Is the sync broken?")
        recorder.agentSaid(id, "Support", "It was fixed on Monday.")
        return id
    }

    private fun signIn(name: String, authority: String) {
        val token = TestingAuthenticationToken(name, "", authority)
        token.isAuthenticated = true
        SecurityContextHolder.getContext().authentication = token
    }

    private fun switch(
        id: Long,
        enabled: Boolean,
        reachAll: Boolean = true,
        reach: List<Long> = emptyList(),
        scopes: String = "[SESSIONS, CHATS, AGENTS, ACTIONS, WORKFLOWS, RUNS, REPORTS]",
        tools: String = "[REPORTS, DOCTOR, MONITORING, TEMPLATES, PLUGINS]",
    ) = """mutation { setAdminWorkspace(workspaceId: $id, input: { enabled: $enabled, reachAll: $reachAll, reach: [${reach.joinToString(", ")}], scopes: $scopes, tools: $tools }) { enabled } }"""

    private companion object {
        const val BOB = "bob"
        const val DANA = "dana-admin-workspace"
        const val SECRET = "plum-harbour-48213"
    }
}
