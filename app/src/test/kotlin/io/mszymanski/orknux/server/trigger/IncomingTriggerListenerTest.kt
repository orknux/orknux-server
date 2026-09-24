package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.IncomingAction
import io.mszymanski.orknux.connector.connection.IncomingEvent
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * What happens when a mention arrives: the connection module publishes it, the
 * definitions waiting on that connection match, and every workflow whose trigger
 * node instances one of them runs. The websocket itself is Slack's to open, so
 * what is tested here starts one step later, with the event already published.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IncomingTriggerListenerTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val publisher: ApplicationEventPublisher,
    @Autowired val runs: ExecutionService,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun reset() {
        triggers.deleteAll()
        workspaceConnections.deleteAll()
        connections.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        assignments.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Slack", type: SLACK, url: "https://slack.com/api"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
    }

    @Test
    fun `a mention starts every workflow that instances the trigger`() {
        val trigger = createTrigger("Slack Mention Handler", "MENTION")
        instance(workflowId, trigger)

        publisher.publishEvent(mention())

        val started = runs.executions(workspaceId, null, null, null, null, null, null)
        assertThat(started.content).singleElement().satisfies({
            assertThat(it.workflowName).isEqualTo("Incident Response")
            assertThat(it.trigger).isEqualTo(ExecutionTrigger.WEBHOOK)
        })
        assertThat(audit.findAll().map { it.message })
            .contains("Workflow Incident Response run started by trigger Slack Mention Handler")
    }

    /**
     * One message, one run of a workflow - however many events Slack makes of
     * it.
     *
     * A file uploaded with a mention in the comment arrives as a message *and*
     * as an app_mention; a message in a thread is both a message and a reply.
     * Each is a real event and a workflow waiting on either is entitled to it,
     * but a workflow waiting on two of them ran twice for one upload.
     *
     * Two triggers here, on the same connection, watching the two actions one
     * upload raises - which is the shape that was reported.
     */
    @Test
    fun `two events from one message start a workflow once`() {
        instance(workflowId, createTrigger("On a mention", "MENTION"))
        instance(workflowId, createTrigger("On a message", "MESSAGE"))

        publisher.publishEvent(mention())
        publisher.publishEvent(mention().copy(action = IncomingAction.MESSAGE))

        assertThat(executions.findAll()).hasSize(1)
    }

    /**
     * And a different message still runs it. The guard is about one message
     * wearing two names, not about a workflow being run once and then quiet.
     */
    @Test
    fun `a second message starts the workflow again`() {
        instance(workflowId, createTrigger("On a mention", "MENTION"))

        publisher.publishEvent(mention())
        publisher.publishEvent(
            mention().copy(context = mention().context + ("ts" to "1699999999.000200")),
        )

        assertThat(executions.findAll()).hasSize(2)
    }

    @Test
    fun `what the workflow is handed is the message and where it came from`() {
        instance(workflowId, createTrigger("Slack Mention Handler", "MENTION"))

        publisher.publishEvent(mention())

        // The run keeps what it was handed, so a step can read who said what.
        assertThat(executions.findAll().single().input)
            .contains("\"text\":\"<@U123> deploy please\"")
            .contains("\"channel\":\"C42\"")
            .contains("\"threadTs\":\"1699999999.000100\"")
    }

    /**
     * The listener matches on the action, and a definition watching a different
     * one is left alone.
     *
     * Saved directly rather than through the API, which now refuses an event
     * nothing publishes. The row is still reachable — a definition saved before
     * that guard existed, or one whose publisher was removed — and what the
     * listener does with it is the thing under test.
     */
    @Test
    fun `a trigger watching another event on the same connection stays put`() {
        val watcher = requireNotNull(
            triggers.save(
                WorkflowTrigger(
                    workspaceId = workspaceId,
                    name = "Slack Reply Watcher",
                    type = TriggerType.INCOMING_CONNECTION,
                    connectionId = connectionId,
                    action = TriggerAction.REPLY,
                ),
            ).id,
        )
        instance(workflowId, watcher)

        publisher.publishEvent(mention())

        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content).isEmpty()
    }

    @Test
    fun `a disabled trigger does not fire`() {
        val id = createTrigger("Slack Mention Handler", "MENTION")
        instance(workflowId, id)
        graphQlTester.document("""mutation { setTriggerEnabled(id: $id, enabled: false) { enabled } }""").execute()

        publisher.publishEvent(mention())

        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content).isEmpty()
    }

    @Test
    fun `a definition no workflow instances starts nothing`() {
        // It is a catalogue entry until a workflow points a trigger node at it.
        createTrigger("Slack Mention Handler", "MENTION")

        publisher.publishEvent(mention())

        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content).isEmpty()
    }

    @Test
    fun `two workflows can instance the same definition, and both run`() {
        val trigger = createTrigger("Slack Mention Handler", "MENTION")
        val second = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Page On-Call" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        instance(workflowId, trigger)
        instance(second, trigger)

        publisher.publishEvent(mention())

        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content.map { it.workflowName })
            .containsExactlyInAnyOrder("Incident Response", "Page On-Call")
    }

    @Test
    fun `the connection module's actions are the trigger actions, name for name`() {
        // They are declared apart, because the module cannot see this one, and
        // the listener maps them by name.
        assertThat(IncomingAction.entries.map { it.name })
            .isEqualTo(TriggerAction.entries.map { it.name })
    }

    /**
     * A firing that starts nothing still leaves a record.
     *
     * This is the whole point of the log: "the trigger does not work" and "the
     * trigger was never asked" look identical everywhere else, because the
     * executions list only holds runs that began. Every outcome is written,
     * especially the ones that are not runs.
     */
    @Test
    fun `what a trigger did is recorded, run or no run`() {
        val trigger = createTrigger("Slack Mention Handler", "MENTION")

        // Nothing instances it yet, so this firing starts nothing at all.
        publisher.publishEvent(mention())

        graphQlTester.document("""query { triggerFirings(triggerId: $trigger) { content { outcome detail runsStarted } } }""")
            .execute()
            .path("triggerFirings.content[0].outcome").entity(String::class.java).isEqualTo("NO_INSTANCE")
            .path("triggerFirings.content[0].runsStarted").entity(Int::class.java).isEqualTo(0)

        // Wired up, the next one starts a run and says so.
        instance(workflowId, trigger)
        publisher.publishEvent(mention())

        graphQlTester.document(
            """query { workspaceTriggers(workspaceId: $workspaceId) { content { name lastFiring { outcome runsStarted } } } }""",
        ).execute()
            .path("workspaceTriggers.content[0].lastFiring.outcome").entity(String::class.java).isEqualTo("STARTED")
            .path("workspaceTriggers.content[0].lastFiring.runsStarted").entity(Int::class.java).isEqualTo(1)

        /*
         * And which one it started, by name.
         *
         * "started 2 workflow(s)" answers the question nobody was asking:
         * somebody reading it wants to know what the message set off, and a
         * count sent them to Executions to match on the clock. Asked for on
         * 2026-09-06.
         */
        val detail = graphQlTester
            .document("""query { triggerFirings(triggerId: $trigger) { content { outcome detail } } }""")
            .execute()
            .path("triggerFirings.content[0].detail").entity(String::class.java).get()
        assertThat(detail).contains("Incident Response (#$workflowId)")
    }

    /**
     * A firing that started some of what it fired at still says which.
     *
     * Only the all-started branch was logged, so a trigger pointing at three
     * workflows of which two are unpublished printed a line per refusal and
     * nothing at all about the one that ran - a run that happened looked like a
     * run that did not. Reported from a real log on 2026-09-06.
     */
    @Test
    fun `a partly started firing names what it did start`() {
        val trigger = createTrigger("Slack Partial Handler", "MENTION")
        instance(workflowId, trigger)

        // A second workflow it also fires at, switched off in this workspace, so
        // the firing can only ever start one of the two - which is the shape a
        // real log showed: three assigned, two of them not startable.
        val off = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Switched Off" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        instance(off, trigger)

        val assignment = graphQlTester.document(
            """query { workspaceWorkflows(workspaceId: $workspaceId, page: 0, size: 50) { content { id workflowId } } }""",
        ).execute().path("workspaceWorkflows.content").entityList(Map::class.java).get()
            .first { (it["workflowId"] as String).toLong() == off }["id"] as String
        graphQlTester.document(
            """mutation { setWorkflowEnabled(id: $assignment, enabled: false) { id } }""",
        ).execute().path("setWorkflowEnabled.id").hasValue()

        publisher.publishEvent(mention())

        val detail = graphQlTester
            .document("""query { triggerFirings(triggerId: $trigger) { content { detail runsStarted } } }""")
            .execute()
            .path("triggerFirings.content[0].detail").entity(String::class.java).get()

        // The one that ran carries its id, beside the reason the other did not.
        // A name alone is not an identity: two workflows may share one, and a
        // rename makes an old record describe something that no longer exists.
        assertThat(detail).contains("Incident Response (#$workflowId)")
        assertThat(detail).contains("Started 1 of 2")
        assertThat(detail).contains("switched off")
    }

    /**
     * A condition on the trigger decides before a run exists.
     *
     * The alternative is a condition node inside the workflow, which only
     * decides after the run has started, been audited and appeared in the
     * executions list — by which point an unwanted mention is indistinguishable
     * from real work. Nothing is started here at all.
     */
    @Test
    fun `a trigger asking a condition only fires when it holds`() {
        val conditionId = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "From alice", type: SLACK, property: MESSAGE_AUTHOR,
                check: IN_LIST, values: ["U7"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        val trigger = createTrigger("Slack Mention Handler", "MENTION")
        graphQlTester.document(
            """
            mutation {
              updateTrigger(id: $trigger, input: {
                name: "Slack Mention Handler", connectionId: $connectionId, action: MENTION,
                conditionId: $conditionId
              }) { conditionId conditionName }
            }
            """,
        ).execute()
            .path("updateTrigger.conditionName").entity(String::class.java).isEqualTo("From alice")
        instance(workflowId, trigger)

        // U7 is who the mention came from, so this one is wanted.
        publisher.publishEvent(mention())
        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content).hasSize(1)

        // The same trigger, a mention from somebody else: no run, and nothing
        // in the executions list to explain away.
        publisher.publishEvent(mention().copy(context = mention().context + ("user" to "U99")))
        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content).hasSize(1)
    }

    /** A condition still being asked is not one to delete out from under. */
    @Test
    fun `a condition a trigger asks cannot be deleted`() {
        val conditionId = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "From alice", type: SLACK, property: MESSAGE_AUTHOR,
                check: IN_LIST, values: ["U7"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        val trigger = createTrigger("Slack Mention Handler", "MENTION")
        graphQlTester.document(
            """
            mutation {
              updateTrigger(id: $trigger, input: {
                name: "Slack Mention Handler", connectionId: $connectionId, action: MENTION,
                conditionId: $conditionId
              }) { id }
            }
            """,
        ).execute()

        graphQlTester.document("""mutation { deleteCondition(id: $conditionId) }""")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("Slack Mention Handler")
            }
    }

    /**
     * The commands in the message travel with it, as a list: every word that
     * starts with the workspace's marker. Issue #381.
     */
    @Test
    fun `the commands in the message are handed on as a list`() {
        instance(workflowId, createTrigger("Slack Mention Handler", "MENTION"))

        publisher.publishEvent(mention("<@U123> !review !security PR 12, please"))

        assertThat(executions.findAll().single().input)
            .contains("\"commands\":[\"review\",\"security\"]")
    }

    @Test
    fun `and the marker is the workspace's own`() {
        graphQlTester.document("""mutation { setWorkspaceCommandMarker(workspaceId: $workspaceId, marker: "::") { commandMarker } }""")
            .execute().path("setWorkspaceCommandMarker.commandMarker").entity(String::class.java).isEqualTo("::")
        instance(workflowId, createTrigger("Slack Mention Handler", "MENTION"))

        publisher.publishEvent(mention("<@U123> !review ::deploy now"))

        assertThat(executions.findAll().single().input).contains("\"commands\":[\"deploy\"]")
    }

    @Test
    fun `a marker that is a letter, or too long, is refused`() {
        graphQlTester.document("""mutation { setWorkspaceCommandMarker(workspaceId: $workspaceId, marker: "x") { commandMarker } }""")
            .execute().errors().expect { it.message!!.contains("cannot mark a command") }.verify()
        graphQlTester.document("""mutation { setWorkspaceCommandMarker(workspaceId: $workspaceId, marker: "!!!!") { commandMarker } }""")
            .execute().errors().expect { it.message!!.contains("cannot mark a command") }.verify()
    }

    private fun mention(text: String = "<@U123> deploy please") = IncomingEvent(
        connectionId = connectionId,
        workspaceId = workspaceId,
        action = IncomingAction.MENTION,
        text = text,
        context = mapOf(
            "channel" to "C42",
            "user" to "U7",
            "ts" to "1699999999.000100",
            "threadTs" to "1699999999.000100",
        ),
    )

    private fun createTrigger(name: String, action: String): Long = graphQlTester.document(
        """
        mutation {
          createTrigger(input: {
            workspaceId: $workspaceId, name: "$name",
            type: INCOMING_CONNECTION, connectionId: $connectionId, action: $action
          }) { id }
        }
        """,
    ).execute().path("createTrigger.id").entity(Long::class.java).get()

    /** Gives a workflow a trigger node instancing [triggerId] — the wiring. */
    private fun instance(workflow: Long, triggerId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflow, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Slack Mention", triggerId: $triggerId, x: 0, y: 0 }
                ],
                edges: []
              }) { nodes { key triggerId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].triggerId").entity(Long::class.java).isEqualTo(triggerId)

        // Published, because a trigger runs the published copy: a graph that
        // was only ever saved is one somebody is still drawing.
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflow) { status } }""",
        ).execute()
    }
}
