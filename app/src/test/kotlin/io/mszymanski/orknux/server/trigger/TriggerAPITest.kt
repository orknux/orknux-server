package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The workspace's trigger catalogue. The list shows where an event comes from and
 * what it is, so most of what is asserted here is that those read correctly for
 * both kinds of trigger. Nothing here names a workflow — that is the trigger
 * node's job, and `IncomingTriggerListenerTest` covers it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TriggerAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val runner: TriggerRunner,
) {

    private var workspaceId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun reset() {
        triggers.deleteAll()
        workspaceConnections.deleteAll()
        connections.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Slack", type: SLACK, url: "https://hooks.slack.com"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
    }

    @Test
    fun `an incoming trigger names its connection and its event`() {
        createIncoming("Slack Mention Handler")

        graphQlTester.document(
            """
            query {
              workspaceTriggers(workspaceId: $workspaceId) {
                content { name type source event enabled }
                totalElements
              }
            }
            """,
        ).execute()
            .path("workspaceTriggers.content[0].type").entity(String::class.java).isEqualTo("INCOMING_CONNECTION")
            .path("workspaceTriggers.content[0].source").entity(String::class.java).isEqualTo("Slack")
            .path("workspaceTriggers.content[0].event").entity(String::class.java).isEqualTo("Mention")
            .path("workspaceTriggers.content[0].enabled").entity(Boolean::class.java).isEqualTo(true)

        assertThat(audit.findAll().map { it.message }).contains("Trigger Slack Mention Handler created")
    }

    @Test
    fun `a scheduled trigger reads as cron, with the expression as its event`() {
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Nightly Data Sync",
                type: SCHEDULED, cron: "0 2 * * *", timezone: "UTC"
              }) { source event type }
            }
            """,
        ).execute()
            .path("createTrigger.type").entity(String::class.java).isEqualTo("SCHEDULED")
            .path("createTrigger.source").entity(String::class.java).isEqualTo("Cron")
            .path("createTrigger.event").entity(String::class.java).isEqualTo("0 2 * * *")
    }

    @Test
    fun `refuses a cron expression it could not schedule`() {
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Broken", type: SCHEDULED, cron: "not a cron"
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("is not a cron expression") == true }.verify()
    }

    /**
     * A cron that parses and never happens.
     *
     * The thirtieth of February is a well-formed expression, so it saved, sat
     * in the list looking like a schedule, and was skipped on every tick for
     * ever. Refused at the door, because the only other way to find out is to
     * wait - and it is refused in different words from a typo, since there is
     * nothing here to correct.
     */
    @Test
    fun `refuses a cron that is well formed and never comes round`() {
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Never", type: SCHEDULED, cron: "0 0 30 2 *"
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("never comes round") == true }.verify()
    }

    /** Six fields is a schedule of seconds, and it saves like any other. */
    @Test
    fun `accepts a cron with seconds`() {
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Often", type: SCHEDULED,
                cron: "*/10 * * * * *", timezone: "UTC"
              }) { id event }
            }
            """,
        ).execute().path("createTrigger.event").entity(String::class.java).isEqualTo("*/10 * * * * *")
    }

    @Test
    fun `refuses an incoming trigger with no connection`() {
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Nowhere", type: INCOMING_CONNECTION
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("needs a connection") == true }.verify()
    }

    @Test
    fun `refuses a duplicate name inside a workspace`() {
        createIncoming("Slack Mention Handler")

        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Slack Mention Handler",
                type: INCOMING_CONNECTION, connectionId: $connectionId, action: MENTION
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("already exists") == true }.verify()
    }

    @Test
    fun `the toggle disables and re-enables it`() {
        val id = createIncoming("Slack Mention Handler")

        graphQlTester.document("""mutation { setTriggerEnabled(id: $id, enabled: false) { enabled } }""")
            .execute().path("setTriggerEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)
        graphQlTester.document("""mutation { setTriggerEnabled(id: $id, enabled: true) { enabled } }""")
            .execute().path("setTriggerEnabled.enabled").entity(Boolean::class.java).isEqualTo(true)

        assertThat(audit.findAll().map { it.message })
            .contains("Trigger Slack Mention Handler disabled", "Trigger Slack Mention Handler enabled")
    }

    @Test
    fun `the form can make one that is switched off`() {
        val id = graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Not Yet",
                type: INCOMING_CONNECTION, connectionId: $connectionId, action: MENTION, enabled: false
              }) { id enabled }
            }
            """,
        ).execute().path("createTrigger.enabled").entity(Boolean::class.java).isEqualTo(false)
            .path("createTrigger.id").entity(Long::class.java).get()

        assertThat(triggers.findByIdOrNull(id)?.enabled).isFalse()
        // The same words the toggle writes, so the log reads the same either way.
        assertThat(audit.findAll().map { it.message }).contains("Trigger Not Yet disabled")
    }

    @Test
    fun `the form on the trigger's own page switches it, and says so`() {
        val id = createIncoming("Slack Mention Handler")

        graphQlTester.document(
            """
            mutation {
              updateTrigger(id: $id, input: { name: "Slack Mention Handler", enabled: false }) { enabled }
            }
            """,
        ).execute().path("updateTrigger.enabled").entity(Boolean::class.java).isEqualTo(false)

        assertThat(triggers.findByIdOrNull(id)?.enabled).isFalse()
        assertThat(audit.findAll().map { it.message })
            .contains("Trigger Slack Mention Handler updated", "Trigger Slack Mention Handler disabled")
    }

    @Test
    fun `a save that does not mention the switch leaves it where it was`() {
        val id = createIncoming("Slack Mention Handler")
        graphQlTester.document("""mutation { setTriggerEnabled(id: $id, enabled: false) { enabled } }""").execute()

        // What every other door sends: the settings, and nothing about firing.
        graphQlTester.document(
            """mutation { updateTrigger(id: $id, input: { name: "Mentions" }) { enabled } }""",
        ).execute().path("updateTrigger.enabled").entity(Boolean::class.java).isEqualTo(false)

        assertThat(triggers.findByIdOrNull(id)?.enabled).isFalse()
        // Nothing changed about it, so nothing is claimed to have.
        assertThat(audit.findAll().map { it.message }).doesNotContain("Trigger Mentions enabled")
    }

    @Test
    fun `renaming and deleting are recorded`() {
        val id = createIncoming("Slack Mention Handler")

        graphQlTester.document(
            """mutation { updateTrigger(id: $id, input: { name: "Mentions" }) { name } }""",
        ).execute().path("updateTrigger.name").entity(String::class.java).isEqualTo("Mentions")

        graphQlTester.document("""mutation { deleteTrigger(id: $id) }""")
            .execute().path("deleteTrigger").entity(Boolean::class.java).isEqualTo(true)

        assertThat(triggers.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message })
            .contains("Trigger Slack Mention Handler renamed to Mentions", "Trigger Mentions deleted")
    }

    @Test
    fun `only the events waiting on a connection are found`() {
        val id = createIncoming("Slack Mention Handler")
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Slack Reply Watcher",
                type: INCOMING_CONNECTION, connectionId: $connectionId, action: REPLY
              }) { id }
            }
            """,
        ).execute()

        // What an arriving mention asks for.
        val waiting = triggers.findByConnectionIdAndActionAndEnabledTrue(connectionId, TriggerAction.MENTION)
        assertThat(waiting.map { it.id }).containsExactly(id)

        graphQlTester.document("""mutation { setTriggerEnabled(id: $id, enabled: false) { enabled } }""").execute()
        assertThat(triggers.findByConnectionIdAndActionAndEnabledTrue(connectionId, TriggerAction.MENTION)).isEmpty()
    }

    /** Last fired orders by the trigger's firing log, newest first, with one that never fired last. */
    @Test
    fun `the list orders by when each trigger last fired`() {
        val never = createIncoming("Never fired")
        val earlier = createIncoming("Fired earlier")
        val later = createIncoming("Fired later")
        runner.note(triggers.findById(earlier).orElseThrow(), FiringOutcome.STARTED, "one")
        Thread.sleep(20)
        runner.note(triggers.findById(later).orElseThrow(), FiringOutcome.STARTED, "two")

        fun ordered(ascending: Boolean) = graphQlTester.document(
            """query { workspaceTriggers(workspaceId: $workspaceId, order: "LAST_FIRED", ascending: $ascending) { content { name } } }""",
        ).execute().path("workspaceTriggers.content[*].name").entityList(String::class.java)

        ordered(false).containsExactly("Fired later", "Fired earlier", "Never fired")
        ordered(true).containsExactly("Fired earlier", "Fired later", "Never fired")
        assertThat(never).isPositive()
    }

    private fun createIncoming(name: String): Long = graphQlTester.document(
        """
        mutation {
          createTrigger(input: {
            workspaceId: $workspaceId, name: "$name",
            type: INCOMING_CONNECTION, connectionId: $connectionId, action: MENTION
          }) { id }
        }
        """,
    ).execute().path("createTrigger.id").entity(Long::class.java).get()
}
