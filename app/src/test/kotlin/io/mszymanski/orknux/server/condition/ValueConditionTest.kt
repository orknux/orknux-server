package io.mszymanski.orknux.server.condition

import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.workflow.MappingMode
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.NodeBinding
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
 * A condition about a value from the run, checked against a list. Issue #378.
 *
 * The typed conditions know where their subject is - a Slack condition knows
 * where a message's author is. This one is told: the node it sits on picks
 * the field with the same reference picker its parameters use, and the
 * condition holds the check and the values. So a workflow can branch on "is
 * this thing the previous node produced one of these" without a function.
 *
 * Makes a workspace and conditions, and removes them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ValueConditionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val evaluator: ConditionEvaluator,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    private fun inList(vararg values: String, negate: Boolean = false) = conditions.save(
        WorkflowCondition(
            workspaceId = workspaceId,
            name = "is one of ours",
            type = ConditionType.VALUE,
            check = ConditionCheck.IN_LIST,
            negate = negate,
            values = values.toMutableList(),
        ),
    )

    /** The node picked the field, the way it picks a function's parameters. */
    private fun picked(path: String) = mapOf(VALUE_SUBJECT to NodeBinding(expression = path, reference = true))

    /* ---------------------------------------------------- what it decides -- */

    @Test
    fun `a value the node picks off the run is checked against the list`() {
        val condition = inList("U01", "U02")

        assertThat(evaluator.holds(condition, """{"author":"U02"}""", picked("author"), null)).isTrue()
        assertThat(evaluator.holds(condition, """{"author":"U09"}""", picked("author"), null)).isFalse()
    }

    @Test
    fun `and a field of the trigger, the way a parameter can`() {
        val condition = inList("ops", "dev")

        assertThat(
            evaluator.holds(condition, """{"replies":1}""", picked("trigger.channel"), """{"channel":"ops"}"""),
        ).isTrue()
    }

    @Test
    fun `a value that is not a string is compared as its JSON`() {
        val condition = inList("42", "7")

        assertThat(evaluator.holds(condition, """{"count":42}""", picked("count"), null)).isTrue()
    }

    @Test
    fun `negated, the same list means not one of these`() {
        val condition = inList("U01", negate = true)

        assertThat(evaluator.holds(condition, """{"author":"U01"}""", picked("author"), null)).isFalse()
        assertThat(evaluator.holds(condition, """{"author":"U02"}""", picked("author"), null)).isTrue()
    }

    @Test
    fun `the other checks a value can take work the same way`() {
        val contains = conditions.save(
            WorkflowCondition(
                workspaceId = workspaceId, name = "mentions ops", type = ConditionType.VALUE,
                check = ConditionCheck.CONTAINS, values = mutableListOf("ops"),
            ),
        )
        val matches = conditions.save(
            WorkflowCondition(
                workspaceId = workspaceId, name = "looks like an id", type = ConditionType.VALUE,
                check = ConditionCheck.MATCHES, values = mutableListOf("^U[0-9]+$"),
            ),
        )

        assertThat(evaluator.holds(contains, """{"text":"page ops now"}""", picked("text"), null)).isTrue()
        assertThat(evaluator.holds(matches, """{"author":"U0123"}""", picked("author"), null)).isTrue()
        assertThat(evaluator.holds(matches, """{"author":"alice"}""", picked("author"), null)).isFalse()
    }

    /**
     * A condition made on the settings page has no graph to pick from, so it
     * may carry the reference itself; the node's choice wins where there is one.
     */
    @Test
    fun `the condition's own argument is the fallback where the node picked nothing`() {
        val condition = inList("U01").apply {
            arguments = mutableListOf(ConditionArgument(name = VALUE_SUBJECT, expression = "author", mode = MappingMode.REFERENCE))
        }.let(conditions::save)

        assertThat(evaluator.holds(condition, """{"author":"U01"}""", emptyMap(), null)).isTrue()
        assertThat(evaluator.holds(condition, """{"author":"U01","other":"U09"}""", picked("other"), null)).isFalse()
    }

    /* ------------------------------------------------ what it cannot ------- */

    @Test
    fun `nothing picked anywhere is not decidable, and says where to pick`() {
        val condition = inList("U01")

        assertThatThrownBy { evaluator.holds(condition, """{"author":"U01"}""", emptyMap(), null) }
            .isInstanceOf(ConditionNotDecidableException::class.java)
            .hasMessageContaining("pick one on the node")
    }

    @Test
    fun `a field the run is not carrying is not decidable rather than false`() {
        val condition = inList("U01")

        assertThatThrownBy { evaluator.holds(condition, """{"text":"hi"}""", picked("author"), null) }
            .isInstanceOf(ConditionNotDecidableException::class.java)
            .hasMessageContaining("not carrying")
    }

    /* ------------------------------------------------------ the API ------- */

    @Test
    fun `it is made with a check and values, and described as the value the node picks`() {
        val id = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "Is one of ours", type: VALUE, check: IN_LIST,
                values: ["U01", "U02"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        graphQlTester.document("""query { condition(id: $id) { type property description } }""").execute()
            .path("condition.type").entity(String::class.java).isEqualTo("VALUE")
            .path("condition.property").valueIsNull()
            .path("condition.description").entity(String::class.java)
            .isEqualTo("Matches when the value the node picks is one of 2 listed values")
    }

    @Test
    fun `a check a value cannot take is refused`() {
        graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "Is between", type: VALUE, check: BETWEEN, values: ["1", "2"]
              }) { id }
            }
            """,
        ).execute().errors().satisfy { errors ->
            assertThat(errors).singleElement().satisfies({ assertThat(it.message).contains("cannot be tested with between") })
        }
    }

    @Test
    fun `and so is one with nothing to check against`() {
        graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "Is one of none", type: VALUE, check: IN_LIST, values: []
              }) { id }
            }
            """,
        ).execute().errors().satisfy { errors -> assertThat(errors).isNotEmpty() }
    }
}
