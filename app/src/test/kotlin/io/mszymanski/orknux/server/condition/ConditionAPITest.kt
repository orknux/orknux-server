package io.mszymanski.orknux.server.condition

import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.plugin.Plugin
import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginFunctionRegistry
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.workflow.script.PluginInspection
import io.mszymanski.orknux.workflow.script.PluginRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The condition catalogue, and deciding one.
 *
 * A condition is data — a property, a check, what to check against — so what is
 * asserted here is that the data is refused when it could not be answered, and
 * that answering it gives the answer the definition describes.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ConditionAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val evaluator: ConditionEvaluator,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val plugins: PluginRepository,
    @Autowired val registry: PluginFunctionRegistry,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val pluginRunner: PluginRunner,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        nodes.deleteAll()
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        plugins.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `a condition says in words what it asks`() {
        val id = slackAuthorInList("Is Workspacemate Message", negate = false, values = listOf("alice@example.com"))

        graphQlTester.document("""query { condition(id: $id) { typeLabel description negate } }""").execute()
            .path("condition.typeLabel").entity(String::class.java).isEqualTo("Slack")
            .path("condition.description").entity(String::class.java)
            .satisfies { assertThat(it).contains("message author").contains("one of 1 listed values") }

        assertThat(audit.findAll().map { it.message }).contains("Condition Is Workspacemate Message created")
    }

    @Test
    fun `it decides what a run is carrying, and the negated one answers the opposite`() {
        val plain = conditions.findById(
            slackAuthorInList("Author allowed", negate = false, values = listOf("alice@example.com")),
        ).orElseThrow()
        val negated = conditions.findById(
            slackAuthorInList("Author not allowed", negate = true, values = listOf("alice@example.com")),
        ).orElseThrow()

        val fromAlice = """{"user":"alice@example.com","text":"hello"}"""
        val fromBob = """{"user":"bob@example.com","text":"hello"}"""

        assertThat(evaluator.holds(plain, fromAlice)).isTrue()
        assertThat(evaluator.holds(plain, fromBob)).isFalse()
        assertThat(evaluator.holds(negated, fromAlice)).isFalse()
        assertThat(evaluator.holds(negated, fromBob)).isTrue()
    }

    @Test
    fun `a composite combines the ones it names`() {
        val first = slackAuthorInList("From alice", negate = false, values = listOf("alice@example.com"))
        val second = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "In incidents", type: SLACK, property: MESSAGE_CHANNEL,
                check: EQUALS, values: ["#incidents"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        val anyOf = conditions.findById(
            graphQlTester.document(
                """
                mutation {
                  createCondition(input: {
                    workspaceId: $workspaceId, name: "Either", type: ANY_OF, members: [$first, $second]
                  }) { id description }
                }
                """,
            ).execute().path("createCondition.id").entity(Long::class.java).get(),
        ).orElseThrow()

        assertThat(evaluator.holds(anyOf, """{"user":"alice@example.com","channel":"#other"}""")).isTrue()
        assertThat(evaluator.holds(anyOf, """{"user":"bob@example.com","channel":"#incidents"}""")).isTrue()
        assertThat(evaluator.holds(anyOf, """{"user":"bob@example.com","channel":"#other"}""")).isFalse()
    }

    @Test
    fun `a function condition answers with the function, and has to return a boolean`() {
        val boolean = function("isUrgent", "BOOLEAN", """
            export default function isUrgent(input) {
              return input !== null && input.priority === "Critical";
            }
        """.trimIndent())
        val id = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "Urgent", type: FUNCTION, functionId: $boolean
              }) { id functionName description }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        val condition = conditions.findById(id).orElseThrow()
        assertThat(evaluator.holds(condition, """{"priority":"Critical"}""")).isTrue()
        assertThat(evaluator.holds(condition, """{"priority":"Low"}""")).isFalse()

        // One that answers with anything else is not a condition.
        val objectReturning = function("summarize", "MAP", "export default function summarize(input) { return {}; }")
        graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "Not a question", type: FUNCTION, functionId: $objectReturning
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("returns map") == true }.verify()
    }

    @Test
    fun `a check that does not belong to the property is refused`() {
        graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "Nonsense", type: SLACK, property: MESSAGE_AUTHOR,
                check: BETWEEN, values: ["09:00", "17:00"]
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("cannot be tested with between") == true }.verify()
    }

    @Test
    fun `a composite cannot contain itself`() {
        val first = slackAuthorInList("From alice", negate = false, values = listOf("alice@example.com"))
        val second = slackAuthorInList("From bob", negate = false, values = listOf("bob@example.com"))
        val composite = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "Either", type: ANY_OF, members: [$first, $second]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              updateCondition(id: $composite, input: { members: [$first, $composite] }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("would contain itself") == true }.verify()
    }

    @Test
    fun `a time condition reads the clock rather than the run`() {
        val id = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "All day", type: TIME, property: CURRENT_TIME,
                check: BETWEEN, values: ["00:00", "23:59"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        assertThat(evaluator.holds(conditions.findById(id).orElseThrow(), null)).isTrue()
    }

    private fun slackAuthorInList(name: String, negate: Boolean, values: List<String>): Long =
        graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "$name", type: SLACK, property: MESSAGE_AUTHOR, check: IN_LIST,
                negate: $negate, values: [${values.joinToString(", ") { "\"$it\"" }}]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

    /**
     * A plugin's function is a function a condition may call.
     *
     * It belongs to no workspace by design - `FunctionScope.PLUGIN` says so,
     * the picker offers it in every workspace, and unloading a plugin counts
     * the conditions naming its functions - but the gate here compared its
     * workspace with the condition's, which a null can never match. So the
     * picker offered something that could not be saved. Run as well as saved:
     * the implementation is the plugin's, not a script column this workspace
     * wrote.
     */
    @Test
    fun `a condition can call a function a plugin declared`() {
        val declared = pluginFunction()

        val id = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "From a teammate", type: FUNCTION, functionId: $declared
              }) { id functionName }
            }
            """,
        ).execute()
            .path("createCondition.functionName").entity(String::class.java).isEqualTo("teammates_isTeammate")
            .path("createCondition.id").entity(Long::class.java).get()

        val condition = conditions.findById(id).orElseThrow()
        assertThat(evaluator.holds(condition, """{"user":"alice@example.com"}""")).isTrue()
        assertThat(evaluator.holds(condition, """{"user":"bob@example.com"}""")).isFalse()
    }

    @Test
    fun `another workspace's function is refused, and says so`() {
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        val theirs = function("isUrgent", "BOOLEAN", "export default function isUrgent(input) { return true; }")

        graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $elsewhere, name: "Not mine", type: FUNCTION, functionId: $theirs
              }) { id }
            }
            """,
        ).execute().errors()
            .expect { it.message?.contains("belongs to another workspace") == true }
            .verify()
    }

    /** A plugin loaded from its own source, and its functions materialised. */
    private fun pluginFunction(): Long {
        val read = pluginRunner.inspect(TEAMMATES) as PluginInspection.Read
        val plugin = plugins.save(
            Plugin(
                key = read.id,
                name = "teammates",
                filename = "teammates.js",
                source = TEAMMATES,
                sizeBytes = TEAMMATES.length.toLong(),
                apiVersion = read.apiVersion,
                sha256 = "0".repeat(64),
                declaredFunctions = declarations.validated(read.functions),
                declaredParameters = declarations.validatedParameters(read.parameters),
            ),
        )
        registry.reconcile(plugin)
        return requireNotNull(functions.findAll().first { it.scope == FunctionScope.PLUGIN }.id)
    }

    /**
     * A Value condition switched to Function: the form sends the function and
     * not the check, and the check the Value condition had stayed on the row -
     * which ck_workflow_condition_shape refused, as INTERNAL_ERROR.
     */
    @Test
    fun `a value condition switched to a function condition saves`() {
        val fn = function(
            "containsText",
            "BOOLEAN",
            "export default function containsText(input) { return String(input.text).includes('x'); }",
        )
        val id = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "Is agent mentioned", type: VALUE, check: IN_LIST, values: ["U01"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              updateCondition(id: $id, input: { name: "Is agent mentioned", type: FUNCTION, functionId: $fn }) {
                type check property values functionName
              }
            }
            """,
        ).execute()
            .path("updateCondition.type").entity(String::class.java).isEqualTo("FUNCTION")
            .path("updateCondition.check").valueIsNull()
            .path("updateCondition.property").valueIsNull()
            .path("updateCondition.functionName").entity(String::class.java).isEqualTo("containsText")
    }

    private fun function(name: String, returnType: String, source: String): Long = graphQlTester.document(
        """
        mutation(${'$'}source: String!) {
          createFunction(input: {
            workspaceId: $workspaceId, name: "$name", returnType: $returnType,
            source: ${'$'}source, typescript: ${'$'}source,
            params: [{ name: "input", type: MAP }]
          }) { id }
        }
        """,
    ).variable("source", source).execute().path("createFunction.id").entity(Long::class.java).get()

    private companion object {
        val TEAMMATES = """
            export default class Teammates extends OrknuxPlugin {
              id() { return 'teammates'; }
              apiVersion() { return 1; }

              functions() {
                return [
                  new OrknuxFunction({
                    name: 'isTeammate',
                    params: [{ name: 'input', type: 'map' }],
                    returnType: 'boolean',
                    run: (input) => input !== null && input.user === 'alice@example.com',
                  }),
                ];
              }
            }
        """.trimIndent()
    }
}
