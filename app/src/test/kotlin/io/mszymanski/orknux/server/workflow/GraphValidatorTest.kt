package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Whether a graph holds together.
 *
 * The rules are about ports, not kinds: what a node needs has to be produced by
 * something before it. What is asserted here is that the ports come from the
 * catalogue — so editing an action changes what its node needs — and that a
 * graph still being drawn can be saved while an impossible one cannot.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class GraphValidatorTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        nodes.deleteAll()
        edges.deleteAll()
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        triggers.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Wiring" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @Test
    fun `a node is told what it needs, from the catalogue entry it points at`() {
        val functionId = function("summarize")
        val actionId = functionAction(functionId, "Summarize", emptyMap())
        val triggerId = scheduled("nightly", """{ "order": { "id": 7 } }""")

        val problems = save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 },
                { key: "act", kind: ACTION, name: "Summarize", actionId: $actionId, x: 200, y: 0 }
            """,
            edges = """{ source: "start", target: "act" }""",
        )

        // The trigger's payload carries `order`, which is what the action asks for.
        assertThat(problems).isEmpty()

        graphQlTester.document(
            """
            query {
              workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) {
                nodes { key inputs { display } outputs { display } }
              }
            }
            """,
        ).execute()
            .path("workflowGraph.nodes[1].inputs[*].display").entityList(String::class.java)
            .containsExactly("order: string")
            .path("workflowGraph.nodes[1].outputs[*].display").entityList(String::class.java)
            .containsExactly("result: map")
    }

    @Test
    fun `a node asking for something nothing produces is warned about`() {
        val functionId = function("summarize")
        val actionId = functionAction(functionId, "Summarize", emptyMap())
        // Carries something, but not the `order` the function declares.
        val triggerId = scheduled("nightly", """{ "invoice": { "id": 7 } }""")

        val problems = save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 },
                { key: "act", kind: ACTION, name: "Summarize", actionId: $actionId, x: 200, y: 0 }
            """,
            edges = """{ source: "start", target: "act" }""",
        )

        assertThat(problems).anySatisfy {
            assertThat(it).contains("WARNING").contains("needs order: string")
        }
    }

    @Test
    fun `a wait hands on what it was given, so what is after it still sees the trigger`() {
        val triggerId = scheduled("nightly", """{ "order": { "id": 7 } }""")
        val waitId = wait("Pause")
        val functionId = function("summarize")
        val actionId = functionAction(functionId, "Summarize", emptyMap())

        val problems = save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 },
                { key: "hold", kind: ACTION, name: "Pause", actionId: $waitId, x: 200, y: 0 },
                { key: "act", kind: ACTION, name: "Summarize", actionId: $actionId, x: 400, y: 0 }
            """,
            edges = """
                { source: "start", target: "hold" },
                { source: "hold", target: "act" }
            """,
        )

        assertThat(problems).isEmpty()
    }

    @Test
    fun `a node with nothing chosen, or nothing before it, is warned about`() {
        val problems = save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", x: 0, y: 0 },
                { key: "act", kind: ACTION, name: "Orphan", x: 400, y: 0 }
            """,
            edges = "",
        )

        assertThat(problems).anySatisfy { assertThat(it).contains("no trigger chosen") }
        assertThat(problems).anySatisfy { assertThat(it).contains("Orphan has nothing before it") }
    }

    /**
     * Saved into an object node, an answer is spoken for: the object node is
     * where it is read, so the agent stops offering its fields - one answer,
     * one place to read it - and the object node offers them instead.
     */
    @Test
    fun `an agent saving into an object node stops offering its answer`() {
        val shape = shape("Prompt", listOf("intentType", "userMessage"))

        val problems = save(
            nodes = """
                { key: "think", kind: AGENT, name: "Responder", outputNodeKey: "keep",
                  outputName: "llmResult", x: 0, y: 0 },
                { key: "keep", kind: OBJECT, name: "Prompt", objectId: $shape, x: 200, y: 0 }
            """,
            edges = """{ source: "think", target: "keep" }""",
        )

        assertThat(problems).noneSatisfy { assertThat(it).contains("saves its answer") }

        graphQlTester.document(
            """
            query {
              workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) {
                nodes { key outputs { display } }
              }
            }
            """,
        ).execute()
            .path("workflowGraph.nodes[0].outputs").entityList(Any::class.java).hasSize(0)
            .path("workflowGraph.nodes[1].outputs[*].display").entityList(String::class.java)
            .containsExactly("intentType: string", "userMessage: string")
    }

    /**
     * The saved answer is readable downstream of the *agent*, because the
     * agent answers with the object node's voice - the node itself is a
     * declaration, needs no wiring, and is never warned about standing alone.
     */
    @Test
    fun `a saved answer is readable downstream of the agent, and the node needs no wiring`() {
        val shape = shape("Verdict", listOf("cause"))
        val functionId = function("summarize")
        val actionId = functionAction(functionId, "Summarize", emptyMap())

        val problems = save(
            nodes = """
                { key: "think", kind: AGENT, name: "Responder", outputNodeKey: "keep",
                  outputName: "llmResult", x: 0, y: 0 },
                { key: "keep", kind: OBJECT, name: "Verdict", objectId: $shape,
                  outputName: "order", x: 200, y: 200 },
                { key: "act", kind: ACTION, name: "Summarize", actionId: $actionId,
                  mappings: [{ name: "order", expression: "order", mode: REFERENCE }], x: 400, y: 0 }
            """,
            edges = """{ source: "think", target: "act" }""",
        )

        // The action's `order` arrives from the agent's step, though the wire
        // is agent-to-action and the object node stands apart; and the node
        // standing apart is its normal state, not a mistake.
        assertThat(problems).noneSatisfy { assertThat(it).contains("needs order") }
        assertThat(problems).noneSatisfy { assertThat(it).contains("Verdict has nothing before it") }
    }

    /**
     * A named object node is the whole thing and its parts: the name for a
     * parameter that takes the object, and each field as a dotted path under
     * it. It offered only the name once, and only the fields before that -
     * either way, half of what a later node wants to point at.
     */
    @Test
    fun `a named object node offers the whole object and its fields`() {
        val shape = shape("Ticket", listOf("id", "title"))

        save(
            nodes = """
                { key: "keep", kind: OBJECT, name: "Ticket", objectId: $shape,
                  outputName: "ticket", x: 0, y: 0 }
            """,
            edges = "",
        )

        graphQlTester.document(
            """
            query {
              workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) {
                nodes { key outputs { display } }
              }
            }
            """,
        ).execute()
            .path("workflowGraph.nodes[0].outputs[*].display").entityList(String::class.java)
            .containsExactly("ticket: object", "ticket.id: string", "ticket.title: string")
    }

    /** One of the workspace's shapes, made the way the editor makes one. */
    private fun shape(name: String, fields: List<String>): Long = graphQlTester.document(
        """
        mutation {
          createObject(input: {
            workspaceId: $workspaceId, name: "$name",
            properties: [${fields.joinToString(", ") { "{ name: \"$it\", kind: STRING }" }}]
          }) { id }
        }
        """,
    ).execute().path("createObject.id").entity(Long::class.java).get()

    @Test
    fun `nothing can feed a trigger`() {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "act", kind: ACTION, name: "Act", x: 0, y: 0 },
                  { key: "start", kind: TRIGGER, name: "Nightly", x: 200, y: 0 }
                ],
                edges: [{ source: "act", target: "start" }]
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { it.message?.contains("Nothing can feed") == true }.verify()
    }

    /**
     * A trigger cannot be switched off from the graph; the sentence names the
     * node, and an action switched off beside it is kept. Issue #439.
     */
    @Test
    fun `a disabled trigger is refused, and a disabled action is kept`() {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "start", kind: TRIGGER, name: "Nightly", enabled: false, x: 0, y: 0 },
                  { key: "act", kind: ACTION, name: "Act", x: 200, y: 0 }
                ],
                edges: [{ source: "start", target: "act" }]
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { it.message?.contains("Nightly is a trigger and cannot be disabled") == true }.verify()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "start", kind: TRIGGER, name: "Nightly", x: 0, y: 0 },
                  { key: "act", kind: ACTION, name: "Act", enabled: false, x: 200, y: 0 }
                ],
                edges: [{ source: "start", target: "act" }]
              }) { nodes { key enabled } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[*].enabled").entityList(Boolean::class.java).containsExactly(true, false)
    }

    @Test
    fun `a condition node needs what its condition asks about`() {
        val conditionId = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "From alice", type: SLACK, property: MESSAGE_AUTHOR,
                check: IN_LIST, values: ["alice@example.com"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()
        // A schedule carries no message, so the author is not there to ask about.
        val triggerId = scheduled("nightly", null)

        val problems = save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 },
                { key: "gate", kind: CONDITION, name: "From alice", conditionId: $conditionId, x: 200, y: 0 }
            """,
            edges = """{ source: "start", target: "gate" }""",
        )

        assertThat(problems).anySatisfy {
            assertThat(it).contains("From alice needs user: string")
        }
    }

    /**
     * A session node is not in the run, so nothing can lead into it.
     *
     * Stronger than the same rule for a trigger: a run does at least reach a
     * trigger, at the start. It never reaches one of these at all - the agents
     * wired to it read it - so an edge pointing at one draws a step that will
     * not happen, and no version of that graph was meant.
     */
    @Test
    fun `nothing can feed a session`() {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "ask", kind: AGENT, name: "Ask", x: 0, y: 0 },
                  { key: "chat", kind: SESSION, name: "Thread", x: 200, y: 0 }
                ],
                edges: [{ source: "ask", target: "chat" }]
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { it.message?.contains("a session is read, not run") == true }.verify()
    }

    /** An agent is the only thing that talks to a model, so it is the only target. */
    @Test
    fun `a session can only lead to an agent`() {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "chat", kind: SESSION, name: "Thread", x: 0, y: 0 },
                  { key: "act", kind: ACTION, name: "Act", x: 200, y: 0 }
                ],
                edges: [{ source: "chat", target: "act" }]
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { it.message?.contains("can only lead to an agent") == true }.verify()
    }

    /**
     * One agent, one conversation.
     *
     * Two would have to be resolved by picking one, and whichever rule did the
     * picking would be invisible on the canvas - so the graph is refused rather
     * than the rule learned.
     */
    @Test
    fun `two sessions cannot reach one agent`() {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "chatA", kind: SESSION, name: "Thread", x: 0, y: 0 },
                  { key: "chatB", kind: SESSION, name: "Ticket", x: 0, y: 100 },
                  { key: "ask", kind: AGENT, name: "Ask", x: 200, y: 0 }
                ],
                edges: [{ source: "chatA", target: "ask" }, { source: "chatB", target: "ask" }]
              }) { nodes { key } }
            }
            """,
        ).execute()
            .errors().expect { it.message?.contains("a turn belongs to one conversation") == true }.verify()
    }

    /**
     * A session is drawn beside the graph rather than in it.
     *
     * So "nothing before it" is its normal state and not worth saying - and the
     * edge it draws into an agent is not a run reaching that agent either, so
     * the agent is still told that nothing reaches it.
     */
    @Test
    fun `a session is not a step, so neither end of its edge counts as one`() {
        val problems = save(
            nodes = """
                { key: "chat", kind: SESSION, name: "Thread", x: 0, y: 0,
                  mappings: [{ name: "sessionKey", expression: "42", mode: VALUE }] },
                { key: "ask", kind: AGENT, name: "Ask", x: 200, y: 0 }
            """,
            edges = """{ source: "chat", target: "ask" }""",
        )

        assertThat(problems).noneSatisfy { assertThat(it).contains("Thread has nothing before it") }
        assertThat(problems).anySatisfy { assertThat(it).contains("Ask has nothing before it") }
    }

    /** A session with no key names nothing, which is worth saying before a run does. */
    @Test
    fun `a session with no key is warned about`() {
        val problems = save(
            nodes = """
                { key: "chat", kind: SESSION, name: "Thread", x: 0, y: 0 },
                { key: "ask", kind: AGENT, name: "Ask", x: 200, y: 0 }
            """,
            edges = """{ source: "chat", target: "ask" }""",
        )

        assertThat(problems).anySatisfy {
            assertThat(it).contains("WARNING").contains("Thread has no session key")
        }
    }

    /** And a session nobody is wired to is a conversation nobody joins. */
    @Test
    fun `a session leading nowhere is warned about`() {
        val problems = save(
            nodes = """
                { key: "chat", kind: SESSION, name: "Thread", x: 0, y: 0,
                  mappings: [{ name: "sessionKey", expression: "42", mode: VALUE }] },
                { key: "ask", kind: AGENT, name: "Ask", x: 200, y: 0 }
            """,
            edges = "",
        )

        assertThat(problems).anySatisfy { assertThat(it).contains("Thread leads to no agent") }
    }

    /**
     * The key is often read off the run, and it is read where it is used - in
     * the agent - so the session node itself asks nothing of what comes before
     * it. Reporting it as an input here would demand a field reach a node a run
     * never reaches, and every session keyed off a ticket would be a warning.
     */
    @Test
    fun `a session whose key is a reference asks nothing of the graph`() {
        val problems = save(
            nodes = """
                { key: "chat", kind: SESSION, name: "Thread", x: 0, y: 0,
                  mappings: [{ name: "sessionKey", expression: "ticket", mode: REFERENCE }] },
                { key: "ask", kind: AGENT, name: "Ask", x: 200, y: 0 }
            """,
            edges = """{ source: "chat", target: "ask" }""",
        )

        assertThat(problems).noneSatisfy { assertThat(it).contains("Thread needs") }
    }

    /** Saves the graph and answers with its problems, as "SEVERITY message". */
    /**
     * The whole of what the editor sends to turn a fallback on: the flag, the
     * two labels, and one extra edge marked FAILURE. The happy path is the
     * unmarked edge it always was.
     */
    @Test
    fun `an action can be given a failure edge, a retry policy and its own two labels`() {
        val actionId = wait("Post it")
        val problems = save(
            nodes = """
                { key: "post", kind: ACTION, name: "Post", actionId: $actionId, fallbackEnabled: true,
                  yesLabel: "Posted", noLabel: "Could not post", retryAttempts: 4, retryBackoffSeconds: 30,
                  x: 0, y: 0 },
                { key: "onwards", kind: ACTION, name: "Onwards", actionId: $actionId, x: 200, y: 0 },
                { key: "rescue", kind: ACTION, name: "Tell someone", actionId: $actionId, x: 200, y: 200 }
            """,
            edges = """
                { source: "post", target: "onwards" },
                { source: "post", target: "rescue", branch: FAILURE }
            """,
        )
        assertThat(problems).noneMatch { it.startsWith("ERROR") }

        val saved = graphQlTester.document(
            """
            query {
              workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) {
                nodes { key fallbackEnabled retryAttempts retryBackoffSeconds yesLabel noLabel }
                edges { source target branch }
              }
            }
            """,
        ).execute()

        val post = saved.path("workflowGraph.nodes[*]").entityList(Map::class.java).get().first { it["key"] == "post" }
        assertThat(post["fallbackEnabled"]).isEqualTo(true)
        assertThat(post["retryAttempts"]).isEqualTo(4)
        assertThat(post["retryBackoffSeconds"]).isEqualTo(30)
        assertThat(post["yesLabel"]).isEqualTo("Posted")
        assertThat(post["noLabel"]).isEqualTo("Could not post")

        val drawn = saved.path("workflowGraph.edges[*]").entityList(Map::class.java).get()
        assertThat(drawn).contains(mapOf("source" to "post", "target" to "rescue", "branch" to "FAILURE"))
        // The happy path is not marked; nothing had to be rewritten to add the
        // other one.
        assertThat(drawn).contains(mapOf("source" to "post", "target" to "onwards", "branch" to null))
    }

    /**
     * The same two settings on an agent, which is the other node that calls
     * something outside the graph.
     *
     * A model is reached over the network and bills for every call, so both
     * halves matter here: a rate limit is worth waiting out, and a run whose
     * agent could not answer usually has somewhere better to go than stopping.
     */
    @Test
    fun `an agent can be given a failure edge and a retry policy of its own`() {
        val actionId = wait("Tell someone")
        val problems = save(
            nodes = """
                { key: "ask", kind: AGENT, name: "Ask", fallbackEnabled: true,
                  yesLabel: "Answered", noLabel: "Could not answer",
                  retryAttempts: 3, retryBackoffSeconds: 20, retryMultiplier: 2,
                  retryMaxWaitSeconds: 120, retryJitter: 0.5, retryBudgetSeconds: 600, x: 0, y: 0 },
                { key: "onwards", kind: ACTION, name: "Onwards", actionId: $actionId, x: 200, y: 0 },
                { key: "rescue", kind: ACTION, name: "Tell someone", actionId: $actionId, x: 200, y: 200 }
            """,
            edges = """
                { source: "ask", target: "onwards" },
                { source: "ask", target: "rescue", branch: FAILURE }
            """,
        )
        assertThat(problems).noneMatch { it.startsWith("ERROR") }

        val ask = nodes.findByWorkflowId(workflowId).single { it.nodeKey == "ask" }
        assertThat(ask.fallbackEnabled).isTrue()
        assertThat(ask.retryAttempts).isEqualTo(3)
        assertThat(ask.retryBackoffSeconds).isEqualTo(20)
        assertThat(ask.retryMultiplier).isEqualTo(2.0)
        assertThat(ask.retryMaxWaitSeconds).isEqualTo(120)
        assertThat(ask.retryJitter).isEqualTo(0.5)
        assertThat(ask.retryBudgetSeconds).isEqualTo(600)
        assertThat(ask.yesLabel).isEqualTo("Answered")
        assertThat(ask.noLabel).isEqualTo("Could not answer")
    }

    /**
     * Everything past the attempt count describes the gap between two of them,
     * so a node with one attempt has nothing for any of it to describe - and a
     * kind that cannot retry has nothing that would ever read it. Both are
     * dropped on the way in rather than kept as settings whose effect is nil.
     */
    @Test
    fun `a backoff is dropped where there is no second attempt to wait for`() {
        val actionId = wait("Post it")
        save(
            nodes = """
                { key: "once", kind: ACTION, name: "Post", actionId: $actionId,
                  retryMultiplier: 2, retryJitter: 0.5, retryBudgetSeconds: 60, x: 0, y: 0 },
                { key: "shape", kind: OBJECT, name: "Make it",
                  retryAttempts: 4, retryMultiplier: 2, x: 200, y: 0 }
            """,
            edges = """{ source: "once", target: "shape" }""",
        )

        val saved = nodes.findByWorkflowId(workflowId).associateBy { it.nodeKey }
        assertThat(saved.getValue("once").retryMultiplier).isNull()
        assertThat(saved.getValue("once").retryJitter).isNull()
        assertThat(saved.getValue("once").retryBudgetSeconds).isNull()
        // An object node assembles what it was handed, so it never retries and
        // the attempts go with the rest.
        assertThat(saved.getValue("shape").retryMultiplier).isNull()
        assertThat(saved.getValue("shape").retryAttempts).isNull()
    }

    /**
     * A multiplier of one is what no multiplier means, and a node the panel only
     * looked at should come back off it as the row it went in as. The same for
     * jitter of nought, which is the wait exactly.
     */
    @Test
    fun `a flat curve and no jitter are stored as nothing at all`() {
        val actionId = wait("Post it")
        save(
            nodes = """
                { key: "post", kind: ACTION, name: "Post", actionId: $actionId, retryAttempts: 3,
                  retryBackoffSeconds: 10, retryMultiplier: 1, retryJitter: 0, x: 0, y: 0 }
            """,
            edges = "",
        )

        val post = nodes.findByWorkflowId(workflowId).single()
        assertThat(post.retryAttempts).isEqualTo(3)
        assertThat(post.retryBackoffSeconds).isEqualTo(10)
        assertThat(post.retryMultiplier).isNull()
        assertThat(post.retryJitter).isNull()
    }

    /**
     * Under a wait that never grows a ceiling does not bound it, it shortens it -
     * so a fixed wait cannot be quietly halved by a field the panel had greyed
     * out and nobody cleared.
     */
    @Test
    fun `a ceiling is dropped where the wait it caps never grows`() {
        val actionId = wait("Post it")
        save(
            nodes = """
                { key: "post", kind: ACTION, name: "Post", actionId: $actionId, retryAttempts: 3,
                  retryBackoffSeconds: 300, retryMaxWaitSeconds: 30, x: 0, y: 0 },
                { key: "grow", kind: ACTION, name: "Again", actionId: $actionId, retryAttempts: 3,
                  retryBackoffSeconds: 10, retryMultiplier: 2, retryMaxWaitSeconds: 30, x: 200, y: 0 }
            """,
            edges = """{ source: "post", target: "grow" }""",
        )

        val saved = nodes.findByWorkflowId(workflowId).associateBy { it.nodeKey }
        assertThat(saved.getValue("post").retryMaxWaitSeconds).isNull()
        assertThat(saved.getValue("post").retryBackoffSeconds).isEqualTo(300)
        assertThat(saved.getValue("grow").retryMaxWaitSeconds).isEqualTo(30)
    }

    /**
     * Bounded rather than refused, like the attempts and the wait beside it:
     * neither end is a mistake worth arguing with somebody over.
     */
    @Test
    fun `a backoff past what a policy may be set to is held at the edge of it`() {
        val actionId = wait("Post it")
        save(
            nodes = """
                { key: "post", kind: ACTION, name: "Post", actionId: $actionId, retryAttempts: 3,
                  retryBackoffSeconds: 10, retryMultiplier: 40, retryJitter: 9,
                  retryBudgetSeconds: 900000, x: 0, y: 0 }
            """,
            edges = "",
        )

        val post = nodes.findByWorkflowId(workflowId).single()
        assertThat(post.retryMultiplier).isEqualTo(10.0)
        assertThat(post.retryJitter).isEqualTo(1.0)
        assertThat(post.retryBudgetSeconds).isEqualTo(86_400)
    }

    /**
     * The shape that reads as working and cannot: an edge no run will ever take,
     * because the node it leaves has not been told it handles failure.
     */
    @Test
    fun `a failure edge out of a node that does not handle failure is refused`() {
        val actionId = wait("Post it")
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "post", kind: ACTION, name: "Post", actionId: $actionId, x: 0, y: 0 },
                  { key: "rescue", kind: ACTION, name: "Tell someone", actionId: $actionId, x: 200, y: 0 }
                ],
                edges: [{ source: "post", target: "rescue", branch: FAILURE }]
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { it.message?.contains("does not handle failure") == true }.verify()

        assertThat(nodes.findByWorkflowId(workflowId)).isEmpty()
    }

    /** Advice, not a refusal: there is a moment between the handle and the edge. */
    @Test
    fun `a fallback with nothing wired to it says so`() {
        val actionId = wait("Post it")
        val problems = save(
            nodes = """
                { key: "post", kind: ACTION, name: "Post", actionId: $actionId, fallbackEnabled: true, x: 0, y: 0 },
                { key: "onwards", kind: ACTION, name: "Onwards", actionId: $actionId, x: 200, y: 0 }
            """,
            edges = """{ source: "post", target: "onwards" }""",
        )

        assertThat(problems).anyMatch { it.startsWith("WARNING") && it.contains("handles failure but nothing leads out") }
        // Saved all the same.
        assertThat(nodes.findByWorkflowId(workflowId)).hasSize(2)
    }

    /**
     * A condition has two ways out and no failure to handle, and everything
     * else has neither. Kept off the row rather than refused, so an editor
     * sending the same node shape for every kind cannot leave a setting behind
     * that quietly does nothing.
     */
    @Test
    fun `fallback and a retry policy are dropped on a kind that cannot use them`() {
        val triggerId = scheduled("nightly", null)
        save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId,
                  fallbackEnabled: true, retryAttempts: 5, retryBackoffSeconds: 10, x: 0, y: 0 }
            """,
            edges = "",
        )

        val start = nodes.findByWorkflowId(workflowId).single()
        assertThat(start.fallbackEnabled).isFalse()
        assertThat(start.retryAttempts).isNull()
        assertThat(start.retryBackoffSeconds).isNull()
    }

    private fun save(nodes: String, edges: String): List<String> = graphQlTester.document(
        """
        mutation {
          saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
            nodes: [$nodes],
            edges: [$edges]
          }) { problems { severity nodeKey message } }
        }
        """,
    ).execute()
        .path("saveWorkflowGraph.problems[*]").entityList(Map::class.java).get()
        .map { "${it["severity"]} ${it["message"]}" }

    private fun scheduled(name: String, payload: String?): Long = graphQlTester.document(
        """
        mutation(${'$'}payload: String) {
          createTrigger(input: {
            workspaceId: $workspaceId, name: "$name", type: SCHEDULED, cron: "0 2 * * *", payload: ${'$'}payload
          }) { id }
        }
        """,
    ).variable("payload", payload).execute().path("createTrigger.id").entity(Long::class.java).get()

    private fun function(name: String): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: {
            workspaceId: $workspaceId, name: "$name", returnType: MAP, params: [{ name: "order", type: MAP }]
          }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    private fun functionAction(functionId: Long, name: String, mappings: Map<String, String>): Long {
        val mapped = mappings.entries.joinToString(", ") {
            """{ argument: "${it.key}", expression: "${it.value}" }"""
        }
        return graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "$name", type: EXECUTE, subtype: FUNCTION,
                functionId: $functionId, mappings: [$mapped]
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()
    }

    private fun wait(name: String): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: {
            workspaceId: $workspaceId, name: "$name", type: WAIT, subtype: TIME, durationSeconds: 1
          }) { id }
        }
        """,
    ).execute().path("createAction.id").entity(Long::class.java).get()
}
