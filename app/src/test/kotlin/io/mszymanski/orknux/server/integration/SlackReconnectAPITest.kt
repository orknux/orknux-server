package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.SlackReconnectRequestRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
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
import org.springframework.security.test.context.support.WithMockUser

/**
 * The Reconnect press on a Slack connection's page, through the API. #592.
 *
 * The suite runs with Slack switched off, so no socket opens here and the state
 * comes back DISABLED - which is also what an installation answers with
 * `ORKNUX_SLACK_ENABLED=false`. What is tested is the app's half: who may press
 * it, that the press is recorded where every replica reads it, and that it is
 * written in the audit trail. The socket's half is [SlackReconnectTest].
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class SlackReconnectAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val requests: SlackReconnectRequestRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var slackId: Long = 0
    private var mailId: Long = 0

    @BeforeEach
    fun reset() {
        requests.deleteAll()
        workspaceConnections.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        roles.deleteAll(roles.findAll().filterNot { it.builtin })
        val backend = roles.save(Role(name = "backend"))
        roles.save(Role(name = "frontend"))
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "Backend", roles = mutableSetOf(backend))).id)
        slackId = connection(workspaceId, "Support Slack", ConnectionType.SLACK)
        mailId = connection(workspaceId, "Outbound mail", ConnectionType.SMTP)
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `a reconnect is recorded for every server and written in the audit trail`() {
        reconnect(slackId).execute()
            .path("reconnectSlackConnection.status").entity(String::class.java).isEqualTo("DISABLED")
        reconnect(slackId).execute()

        // Two presses are two generations, which is what another replica compares.
        assertThat(requests.findById(slackId).map { it.generation }).contains(2L)
        assertThat(audit.findAll().map { it.message })
            .contains("Slack connection Support Slack reconnected")
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `the connection answers its socket's state, and only a Slack one has one`() {
        graphQlTester.document("query { workspaceConnection(id: $slackId) { slackSocket { status sharedWithOthers } } }")
            .execute()
            .path("workspaceConnection.slackSocket.status").entity(String::class.java).isEqualTo("DISABLED")
            .path("workspaceConnection.slackSocket.sharedWithOthers").entity(Boolean::class.java).isEqualTo(false)
        graphQlTester.document("query { workspaceConnection(id: $mailId) { slackSocket { status } } }")
            .execute()
            .path("workspaceConnection.slackSocket").valueIsNull()
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `a connection that is not Slack is refused, and nothing is recorded`() {
        reconnect(mailId).execute().errors().satisfy { errors ->
            assertThat(errors.map { it.extensions["code"] }).containsExactly("ConnectionNotSlack")
            assertThat(errors.map { it.errorType.toString() }).containsExactly("BAD_REQUEST")
        }

        assertThat(requests.findAll()).isEmpty()
        assertThat(audit.findAll()).isEmpty()
    }

    /** Somebody who cannot see the workspace is told the connection is not there. */
    @Test
    @WithMockUser(username = "bob", roles = ["FRONTEND"])
    fun `a member of another workspace cannot reconnect it`() {
        reconnect(slackId).execute().errors().satisfy { errors ->
            assertThat(errors.map { it.errorType.toString() }).containsExactly("NOT_FOUND")
        }

        assertThat(requests.findAll()).isEmpty()
        assertThat(audit.findAll()).isEmpty()
    }

    private fun reconnect(id: Long) =
        graphQlTester.document("mutation { reconnectSlackConnection(id: $id) { status lastEventAt sharedWithOthers } }")

    private fun connection(workspaceId: Long, name: String, type: ConnectionType): Long =
        requireNotNull(
            workspaceConnections.save(
                WorkspaceConnection(
                    workspaceId = workspaceId,
                    name = name,
                    type = type,
                    url = if (type == ConnectionType.SLACK) "https://slack.com/api" else "smtp.invalid",
                    secret = "not-a-real-token",
                ),
            ).id,
        )
}
