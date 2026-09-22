package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.MAX_CHAT_ROUNDS
import io.mszymanski.orknux.server.attachment.MIN_CHAT_ROUNDS
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * How many rounds of tool calls an agent gets, and who decides.
 *
 * Eight was written into the code. An agent holding twenty tools spent three of
 * them listing and loading before the work began and was stopped with "kept
 * looking things up without reaching an answer" - with everything it had
 * gathered thrown away. The number is a setting now: the installation's, which
 * every agent follows, and an agent's own where its work is longer than the
 * rest.
 *
 * What is pinned here is the decision, not the loop: which number a given agent
 * resolves to, that the bounds are enforced at both doors, and that emptying the
 * agent's own puts it back to the installation's.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatRoundsSettingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationSettings,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var agentId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        /*
         * Found rather than made again. A workspace name is unique and the table
         * is not emptied between classes, so a second `save` of the same name is
         * a constraint violation rather than a fixture.
         */
        workspaceId = requireNotNull(
            (workspaces.findByName("rounds") ?: workspaces.save(Workspace(name = "rounds"))).id,
        )
        agentId = requireNotNull(
            agents.save(Agent(workspaceId = workspaceId, name = "Support", type = AgentType.LLM)).id,
        )
        // Back to the file's own number, so a test that raised it leaves nothing
        // behind for the next one: the setting is a row and rows outlive a class.
        settings.setChatMaxRounds(settings.chatMaxRoundsConfigured(), "alice")
    }

    @Test
    fun `an installation with nothing set follows its configuration`() {
        assertThat(settings.chatMaxRounds()).isEqualTo(settings.chatMaxRoundsConfigured())
    }

    @Test
    fun `the installation's number is what the screen sets and reads back`() {
        graphQlTester.document("""mutation { setChatMaxRounds(rounds: 24) { chatMaxRounds chatMaxRoundsConfigured } }""")
            .execute()
            .path("setChatMaxRounds.chatMaxRounds").entity(Int::class.java).isEqualTo(24)
            // What the file says is unchanged: the screen stores an answer beside
            // it rather than over it, so an operator can see the two differ.
            .path("setChatMaxRounds.chatMaxRoundsConfigured").entity(Int::class.java)
            .isEqualTo(settings.chatMaxRoundsConfigured())

        assertThat(settings.chatMaxRounds()).isEqualTo(24)
    }

    @Test
    fun `a number outside the bounds is refused rather than stored`() {
        listOf(1, 101).forEach { asked ->
            graphQlTester.document("mutation { setChatMaxRounds(rounds: $asked) { chatMaxRounds } }")
                .execute().errors().satisfy { errors ->
                    assertThat(errors).singleElement()
                        .satisfies({ assertThat(it.message).contains("not a number of tool rounds") })
                }
        }

        assertThat(settings.chatMaxRounds()).isEqualTo(settings.chatMaxRoundsConfigured())
        assertThatThrownBy { settings.setChatMaxRounds(MIN_CHAT_ROUNDS - 1, "alice") }
            .hasMessageContaining("not a number of tool rounds")
        assertThatThrownBy { settings.setChatMaxRounds(MAX_CHAT_ROUNDS + 1, "alice") }
            .hasMessageContaining("not a number of tool rounds")
    }

    /**
     * The agent's own number, and the way back to the installation's.
     *
     * Sent on every save, so null means "follow the installation" rather than
     * "leave it alone" - which is what lets somebody empty the box.
     */
    @Test
    fun `an agent may carry its own, and emptying it goes back to the installation's`() {
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Support", maxRounds: 40 }) { maxRounds } }""",
        ).execute().path("updateAgent.maxRounds").entity(Int::class.java).isEqualTo(40)

        assertThat(agents.findById(agentId).orElseThrow().maxRounds).isEqualTo(40)

        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Support", maxRounds: null }) { maxRounds } }""",
        ).execute().path("updateAgent.maxRounds").valueIsNull()

        assertThat(agents.findById(agentId).orElseThrow().maxRounds).isNull()
    }

    @Test
    fun `an agent's own number is held to the same bounds`() {
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Support", maxRounds: 500 }) { maxRounds } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).singleElement()
                .satisfies({ assertThat(it.message).contains("not a number of tool rounds") })
        }

        assertThat(agents.findById(agentId).orElseThrow().maxRounds).isNull()
    }
}
