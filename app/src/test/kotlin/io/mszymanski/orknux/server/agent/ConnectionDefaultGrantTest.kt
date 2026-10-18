package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A connection marked to be granted to new agents: a new agent starts with it,
 * one already made is left as it was, and unticking it on an agent holds.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ConnectionDefaultGrantTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val agents: AgentRepository,
) {

    @Test
    fun `a new agent starts with the connections marked for new agents, and no others`() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "default grants ${System.nanoTime()}")).id)
        fun connection(name: String, granted: Boolean?): Long {
            val flag = granted?.let { ", grantNewAgents: $it" } ?: ""
            return graphQlTester.document(
                """mutation { createWorkspaceConnection(input: {
                     workspaceId: $workspaceId, name: "$name", type: HTTP, url: "https://example.invalid"$flag
                   }) { id grantNewAgents } }""",
            ).execute()
                .path("createWorkspaceConnection.grantNewAgents").entity(Boolean::class.java).isEqualTo(granted == true)
                .path("createWorkspaceConnection.id").entity(Long::class.java).get()
        }
        fun agent(name: String): Long = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val anki = connection("Anki", true)
        val other = connection("Other", null)
        val before = agent("Made before")
        val later = connection("Later", false)
        graphQlTester.document("""mutation { updateWorkspaceConnection(id: $later, input: { grantNewAgents: true }) { grantNewAgents } }""")
            .execute().path("updateWorkspaceConnection.grantNewAgents").entity(Boolean::class.java).isEqualTo(true)

        // Made after both were marked: both granted, the unmarked one not.
        assertThat(agents.findById(agent("Made after")).get().connections).containsExactlyInAnyOrder(anki, later)
        // Made before the second was marked: left as it was, with only the first.
        assertThat(agents.findById(before).get().connections).containsExactly(anki)
        // A default and nothing more: unticked on the agent, it stays unticked.
        val unticked = agent("Unticked")
        graphQlTester.document("""mutation { updateAgent(id: $unticked, input: { name: "Unticked", connectionIds: [$other] }) { id } }""").execute().path("updateAgent.id").hasValue()
        assertThat(agents.findById(unticked).get().connections).containsExactly(other)
    }
}
