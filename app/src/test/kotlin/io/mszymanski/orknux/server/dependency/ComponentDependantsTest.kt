package io.mszymanski.orknux.server.dependency

import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.ScriptImport
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.condition.ConditionType
import io.mszymanski.orknux.server.condition.WorkflowCondition
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.library.ScriptLibrary
import io.mszymanski.orknux.server.library.ScriptLibraryRepository
import io.mszymanski.orknux.server.plugin.Plugin
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowPublicationRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
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
 * Where a component is used, and the promise that it is one answer.
 *
 * The list and the refusal are the same set — that is the whole of #258 and #268
 * — so what is worth pinning is not that each works but that they cannot
 * disagree. A screen saying a function is used by nothing, followed by a delete
 * refused for two reasons, would be worse than no screen at all, and that is
 * exactly what a second copy of the question drifts into.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class ComponentDependantsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val libraries: ScriptLibraryRepository,
    @Autowired val plugins: PluginRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val publications: WorkflowPublicationRepository,
) {

    private var backendId: Long = 0
    private var frontendId: Long = 0

    @BeforeEach
    fun seed() {
        // Before the actions: a node holds one, and a publication holds a node.
        publications.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        actions.deleteAll()
        conditions.deleteAll()
        triggers.deleteAll()
        agents.deleteAll()
        tools.deleteAll()
        functions.deleteAll()
        plugins.deleteAll()
        libraries.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        roles.deleteAll(roles.findAll().filterNot { it.builtin })

        val backend = roles.save(Role(name = "backend"))
        val frontend = roles.save(Role(name = "frontend"))
        backendId = requireNotNull(workspaces.save(Workspace(name = "Backend", roles = mutableSetOf(backend))).id)
        frontendId = requireNotNull(workspaces.save(Workspace(name = "Frontend", roles = mutableSetOf(frontend))).id)
    }

    /** Both halves of a function's answer: what calls it, and what imports it. */
    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `a function's dependants are what calls it and what imports it`() {
        val shared = function(backendId, "toUpper")
        val caller = actions.save(
            WorkflowAction(
                workspaceId = backendId,
                name = "Shout",
                type = ActionType.EXECUTE,
                subtype = ActionSubtype.FUNCTION,
                functionId = shared,
            ),
        )
        val importer = function(backendId, "shout", imports = mutableListOf(ScriptImport(shared, "upper")))

        graphQlTester.document(dependants("FUNCTION", shared)).execute()
            .path("componentDependants.entries[*].kind").entityList(String::class.java)
            .containsExactly("ACTION", "FUNCTION")
            .path("componentDependants.entries[*].id").entityList(String::class.java)
            .containsExactly(caller.id.toString(), importer.toString())
            .path("componentDependants.entries[*].name").entityList(String::class.java)
            .containsExactly("Shout", "shout")
            .path("componentDependants.hidden").entity(Int::class.java).isEqualTo(0)
    }

    /**
     * The list and the refusal are one answer.
     *
     * Asserted against each other rather than against two literals, because the
     * bug this guards is the two drifting apart, and two literals written on the
     * same afternoon agree whatever the code does.
     */
    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `what the list names is what the refusal names`() {
        val shared = function(backendId, "slugify")
        function(backendId, "makeSlug", imports = mutableListOf(ScriptImport(shared, "slug")))

        val listed = graphQlTester.document(dependants("FUNCTION", shared)).execute()
            .path("componentDependants.entries[*].name").entityList(String::class.java).get()

        graphQlTester.document("""mutation { deleteFunction(id: $shared) }""").execute()
            .errors().satisfy { errors ->
                val message = requireNotNull(errors.single().message)
                assertThat(listed).isNotEmpty()
                assertThat(listed).allSatisfy { named -> assertThat(message).contains(named) }
            }
    }

    /**
     * An action, which is the kind #258 had a question for and no screen.
     *
     * A workflow node is the only thing that names one, and it names it twice -
     * in the drawn graph and in the copy publishing froze. The published half is
     * the half that matters, because redrawing the canvas does not touch it, and
     * it is what the row has to say so a reader knows whether to redraw or to
     * republish.
     *
     * Written against the refusal rather than against a literal, for the reason
     * `what the list names is what the refusal names` gives: the failure worth
     * guarding is the two drifting, and two literals typed on one afternoon
     * agree whatever the code does.
     */
    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `an action's dependants are the workflows whose nodes run it`() {
        val actionId = act("Shout")
        val workflowId = workflow("Answer")
        graph(workflowId, actionId)

        graphQlTester.document(dependants("ACTION", actionId)).execute()
            .path("componentDependants.entries[*].kind").entityList(String::class.java)
            .containsExactly("WORKFLOW")
            .path("componentDependants.entries[*].name").entityList(String::class.java)
            .containsExactly("Answer")
            .path("componentDependants.entries[*].published").entityList(Boolean::class.java)
            .containsExactly(false)

        // Published, the same row says which copy holds it - a different thing
        // to be rid of, and the only reason the flag is on the row at all.
        publish(workflowId)

        graphQlTester.document(dependants("ACTION", actionId)).execute()
            .path("componentDependants.entries[*].published").entityList(Boolean::class.java)
            .containsExactly(true)

        val listed = graphQlTester.document(dependants("ACTION", actionId)).execute()
            .path("componentDependants.entries[*].name").entityList(String::class.java).get()

        graphQlTester.document("""mutation { deleteAction(id: $actionId) }""").execute()
            .errors().satisfy { errors ->
                val message = requireNotNull(errors.single().message)
                assertThat(listed).isNotEmpty()
                assertThat(listed).allSatisfy { named -> assertThat(message).contains(named) }
            }
    }

    /**
     * Nothing uses it, and that is an answer rather than a failure.
     *
     * The screen says so in a line of its own; an empty box would read as a
     * feature that had not loaded.
     */
    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `a component nothing uses answers an empty list`() {
        val lonely = function(backendId, "unused")

        graphQlTester.document(dependants("FUNCTION", lonely)).execute()
            .path("componentDependants.entries").entityList(Object::class.java).hasSize(0)
            .path("componentDependants.hidden").entity(Int::class.java).isEqualTo(0)
    }

    /**
     * A library is the installation's, and its importers are not all the reader's.
     *
     * Counted rather than named: the name of a function in a workspace somebody
     * cannot open is the name of a workspace they were not told exists, and
     * dropping the row silently would answer "what uses this?" with a list that is
     * missing rows.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a library's importers elsewhere are counted, not named`() {
        val library = requireNotNull(
            libraries.save(
                ScriptLibrary(
                    key = "slug",
                    name = "slug",
                    filename = "slug.js",
                    source = "export default {};",
                    sizeBytes = 18,
                    sha256 = "0".repeat(64),
                ),
            ).id,
        )
        function(backendId, "mine", libraries = mutableListOf(ScriptImport(library, "slug")))
        function(frontendId, "theirs", libraries = mutableListOf(ScriptImport(library, "slug")))

        graphQlTester.document(dependants("LIBRARY", library)).execute()
            .path("componentDependants.entries[*].name").entityList(String::class.java).containsExactly("mine")
            .path("componentDependants.entries[*].workspaceName").entityList(String::class.java)
            .containsExactly("Backend")
            .path("componentDependants.hidden").entity(Int::class.java).isEqualTo(1)
    }

    /** And an administrator, who sees every workspace, is told about both. */
    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `an administrator is told about every workspace a library reaches`() {
        val library = requireNotNull(
            libraries.save(
                ScriptLibrary(
                    key = "slug",
                    name = "slug",
                    filename = "slug.js",
                    source = "export default {};",
                    sizeBytes = 18,
                    sha256 = "0".repeat(64),
                ),
            ).id,
        )
        function(backendId, "mine", libraries = mutableListOf(ScriptImport(library, "slug")))
        function(frontendId, "theirs", libraries = mutableListOf(ScriptImport(library, "slug")))

        graphQlTester.document(dependants("LIBRARY", library)).execute()
            .path("componentDependants.entries[*].name").entityList(String::class.java)
            .containsExactly("mine", "theirs")
            .path("componentDependants.hidden").entity(Int::class.java).isEqualTo(0)
    }

    /**
     * A plugin's function belongs to no workspace, and is called from all of them.
     *
     * The one place scoping the question by workspace gives the wrong answer.
     * `PluginFunctionRegistry` always asked this installation-wide and the
     * workspace's own delete guard asked it scoped, because for a workspace
     * function scoped is right — folding the two together is what made the
     * difference visible. A null workspace answering an empty list would tell an
     * administrator that nothing calls a function two workspaces are calling.
     */
    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `a plugin's function is called from every workspace, not one`() {
        val source = "export default {};"
        val plugin = requireNotNull(
            plugins.save(
                Plugin(
                    key = "tracker",
                    name = "tracker",
                    filename = "tracker.js",
                    source = source,
                    sizeBytes = source.length.toLong(),
                    apiVersion = 1,
                    sha256 = "0".repeat(64),
                ),
            ).id,
        )
        val declared = requireNotNull(
            functions.save(
                WorkflowFunction(
                    workspaceId = null,
                    scope = FunctionScope.PLUGIN,
                    pluginId = plugin,
                    name = "tracker_isTeammate",
                    source = source,
                ),
            ).id,
        )
        conditions.save(condition(backendId, "Backend asks", declared))
        conditions.save(condition(frontendId, "Frontend asks", declared))

        graphQlTester.document(dependants("FUNCTION", declared)).execute()
            .path("componentDependants.entries[*].name").entityList(String::class.java)
            .containsExactly("Backend asks", "Frontend asks")
    }

    /**
     * What guards with a condition and what groups it, and what calls a
     * workspace's function from a condition.
     *
     * Since #616 each is asked of the database - the actions with this
     * condition, the groups with it as a member, the conditions calling this
     * function - rather than sieved out of every action and condition the
     * workspace has, each read with its lists. The neighbours here are what a
     * narrower question could get wrong: a group holding only another
     * condition, an action guarded by another.
     */
    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `a condition's dependants are the actions it guards and the groups that hold it`() {
        val checked = function(backendId, "isUrgent")
        val asked = requireNotNull(conditions.save(condition(backendId, "Urgent", checked)).id)
        val other = requireNotNull(conditions.save(condition(backendId, "Quiet", checked)).id)
        conditions.save(group("Either", other, asked))
        conditions.save(group("Only quiet", other))
        actions.save(guarded("Page", asked))
        actions.save(guarded("Ignore", other))

        graphQlTester.document(dependants("CONDITION", asked)).execute()
            .path("componentDependants.entries[*].kind").entityList(String::class.java)
            .containsExactly("ACTION", "CONDITION")
            .path("componentDependants.entries[*].name").entityList(String::class.java)
            .containsExactly("Page", "Either")

        graphQlTester.document(dependants("FUNCTION", checked)).execute()
            .path("componentDependants.entries[*].name").entityList(String::class.java)
            .containsExactly("Urgent", "Quiet")
    }

    private fun group(name: String, vararg members: Long) = WorkflowCondition(
        workspaceId = backendId,
        name = name,
        type = ConditionType.ANY_OF,
        members = members.toMutableList(),
    )

    private fun guarded(name: String, conditionId: Long) = WorkflowAction(
        workspaceId = backendId,
        name = name,
        type = ActionType.WAIT,
        subtype = ActionSubtype.TIME,
        durationSeconds = 1,
        conditionId = conditionId,
    )

    /**
     * A component in a workspace the reader cannot see is not there.
     *
     * The same answer an id that never existed gets, which is the line
     * `WorkspaceAccess.requireVisible` draws: two answers to one question is a
     * directory of what this installation holds.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a component in another workspace answers as not there`() {
        val theirs = function(frontendId, "theirs")

        graphQlTester.document(dependants("FUNCTION", theirs)).execute()
            .errors().expect { it.message?.lowercase()?.contains("no workspace") == true }
            .verify()
    }

    /**
     * Nothing points at a workflow, so the question has no answer.
     *
     * Refused rather than answered with an empty list: an empty list reads as
     * "nothing uses it yet", which would be a claim rather than a shrug.
     */
    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `asking what uses a workflow is refused rather than answered empty`() {
        graphQlTester.document(dependants("WORKFLOW", 1)).execute()
            .errors().expect { it.message?.contains("Nothing points at") == true }.verify()
    }

    /** An action with no settings to get wrong, so the test is about the arrow. */
    private fun act(name: String): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: {
            workspaceId: $backendId, name: "$name", type: WAIT, subtype: TIME, durationSeconds: 1
          }) { id }
        }
        """,
    ).execute().path("createAction.id").entity(Long::class.java).get()

    private fun workflow(name: String): Long = graphQlTester.document(
        """mutation { createWorkflow(input: { workspaceId: $backendId, name: "$name" }) { workflowId } }""",
    ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

    /** One action node, which is the whole workflow. */
    private fun graph(workflowId: Long, actionId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $backendId, workflowId: $workflowId, input: {
                nodes: [{ key: "act", kind: ACTION, name: "Act", actionId: $actionId, x: 0, y: 0 }], edges: []
              }) { nodes { key actionId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].actionId").entity(Long::class.java).isEqualTo(actionId)
    }

    private fun publish(workflowId: Long) {
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $backendId, workflowId: $workflowId) { status } }""",
        ).execute().path("publishWorkflow.status").entity(String::class.java).isEqualTo("PUBLISHED")
    }

    private fun dependants(kind: String, id: Long) = """
        query {
          componentDependants(kind: $kind, componentId: $id) {
            entries { kind id name workspaceId workspaceName published }
            hidden
          }
        }
    """

    /** A condition that asks a function, which is the shape the CHECK allows. */
    private fun condition(workspaceId: Long, name: String, functionId: Long) = WorkflowCondition(
        workspaceId = workspaceId,
        name = name,
        type = ConditionType.FUNCTION,
        functionId = functionId,
    )

    private fun function(
        workspaceId: Long,
        name: String,
        imports: MutableList<ScriptImport> = mutableListOf(),
        libraries: MutableList<ScriptImport> = mutableListOf(),
    ): Long = requireNotNull(
        functions.save(
            WorkflowFunction(
                workspaceId = workspaceId,
                name = name,
                source = "export default function $name() { return 1; }",
                imports = imports,
                libraries = libraries,
            ),
        ).id,
    )
}
