package io.mszymanski.orknux.server.condition

import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper

/**
 * A condition that asks a function: what the sandbox is told, and what counts
 * as an answer.
 */
@SpringBootTest
class ConditionEvaluatorTest(
    @Autowired val evaluator: ConditionEvaluator,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        // Whatever holds a function goes first: an action or a condition may name one.
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `the asking function is told when now is, since the sandbox has no clock of its own`() {
        val condition = condition(
            "is this century",
            """
            export default async function isThisCentury() {
              // `context` is what the run knows about where this is running.
              return new Date(context.now).getUTCFullYear() > 2000;
            }
            """.trimIndent(),
        )

        assertThat(evaluator.holds(condition, null)).isTrue()
    }

    @Test
    fun `the function is handed what the run is carrying`() {
        val condition = condition(
            "is urgent",
            """
            export default async function isUrgent(input) {
              return input.priority === 'high';
            }
            """.trimIndent(),
        )

        assertThat(evaluator.holds(condition, """{"priority":"high"}""")).isTrue()
        assertThat(evaluator.holds(condition, """{"priority":"low"}""")).isFalse()
    }

    @Test
    fun `negate turns the answer round`() {
        val condition = condition(
            "never",
            "export default async function never() {\n  return false;\n}",
        )
        condition.negate = true
        conditions.save(condition)

        assertThat(evaluator.holds(condition, null)).isTrue()
    }

    @Test
    fun `an answer that is neither true nor false is refused rather than guessed at`() {
        val condition = condition(
            "maybe",
            "export default async function maybe() {\n  return 'maybe';\n}",
        )

        assertThatThrownBy { evaluator.holds(condition, null) }
            .isInstanceOf(ConditionNotDecidableException::class.java)
            .hasMessageContaining("which is not true or false")
    }

    @Test
    fun `a pattern is run against a value of the size a real message is`() {
        val condition = matching("looks like a ticket", """[A-Z]{2,10}-\d+""")

        assertThat(evaluator.holds(condition, """{"text":"please look at ORKX-114 today"}""")).isTrue()
        assertThat(evaluator.holds(condition, """{"text":"please look at it today"}""")).isFalse()
        // Longer than anything a chat renders, and still under the bound.
        val long = "x".repeat(9_000) + " ORKX-114"
        assertThat(evaluator.holds(condition, mapper.writeValueAsString(mapOf("text" to long)))).isTrue()
    }

    @Test
    fun `a value past the bound is not matched, since that regex runs on this thread`() {
        val condition = matching("looks like a ticket", """[A-Z]{2,10}-\d+""")

        // The pattern would match; the value is more than MATCHES will take from
        // whoever sent it, so the answer is no rather than however long it takes.
        val enormous = "x".repeat(20_000) + " ORKX-114"
        assertThat(evaluator.holds(condition, mapper.writeValueAsString(mapOf("text" to enormous)))).isFalse()
    }

    /**
     * The bounds above are on length, and `(a|a?){60}b` is eleven characters
     * that never finish failing against forty a's - a value well inside them,
     * and one a stranger's Slack message can carry. The match is stopped at
     * its time limit and answers no.
     */
    @Test
    fun `a pattern that would backtrack for ever is stopped and does not match`() {
        val condition = matching("never finishes", "(a|a?){60}b")
        val answer = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively<Boolean>(java.time.Duration.ofSeconds(20)) {
            evaluator.holds(condition, mapper.writeValueAsString(mapOf("text" to "a".repeat(40))))
        }
        assertThat(answer).isFalse()
    }

    private fun matching(name: String, pattern: String): WorkflowCondition = conditions.save(
        WorkflowCondition(
            workspaceId = workspaceId,
            name = name,
            type = ConditionType.SLACK,
            property = ConditionProperty.MESSAGE_TEXT,
            check = ConditionCheck.MATCHES,
            values = mutableListOf(pattern),
        ),
    )

    /**
     * What a node fills in wins, and it is read the way every other node reads
     * a reference.
     *
     * Arguments used to live on the condition, which made them one shared list:
     * two nodes asking "is this the first reply" about different threads had to
     * be two conditions. They are the node's now, so the same question can be
     * asked of different fields.
     */
    @Test
    fun `a node says what to pass, and its references read the run and the trigger`() {
        val condition = condition(
            "is over",
            """
            export default async function isOver(count, limit) {
              return count > limit;
            }
            """.trimIndent(),
        )
        withParams(condition, "count", "limit")

        val passed = mapOf(
            "count" to NodeBinding(expression = "replies", reference = true),
            "limit" to NodeBinding(expression = "trigger.allowed", reference = true),
        )

        assertThat(
            evaluator.holds(condition, """{"replies":5}""", passed, """{"allowed":3}"""),
        ).isTrue()
        assertThat(
            evaluator.holds(condition, """{"replies":1}""", passed, """{"allowed":3}"""),
        ).isFalse()
    }

    /**
     * A node that fills nothing in changes nothing.
     *
     * This is what every graph drawn before the rows existed does, and what the
     * condition's own arguments are still there for: the fallback is the whole
     * reason nothing had to be migrated.
     */
    @Test
    fun `a node that passes nothing is handed what the run carries, as before`() {
        val condition = condition(
            "is urgent still",
            """
            export default async function isUrgentStill(input) {
              return input.priority === 'high';
            }
            """.trimIndent(),
        )

        assertThat(evaluator.holds(condition, """{"priority":"high"}""", emptyMap(), null)).isTrue()
        assertThat(evaluator.holds(condition, """{"priority":"low"}""", emptyMap(), null)).isFalse()
    }

    /**
     * A parameter the node leaves out is null, not a shift.
     *
     * The arguments are positional and the node's are named, so a missing one
     * has to hold its place: filling the second parameter with the third
     * parameter's value is the bug this is here to keep out.
     */
    @Test
    fun `a parameter the node did not fill in arrives as null`() {
        val condition = condition(
            "second is null",
            """
            export default async function secondIsNull(first, second) {
              return first === 'here' && second === null;
            }
            """.trimIndent(),
        )
        withParams(condition, "first", "second")

        val passed = mapOf("first" to NodeBinding(expression = "here"))
        assertThat(evaluator.holds(condition, "null", passed, null)).isTrue()
    }

    /** Declares the parameters the function takes, in order. */
    private fun withParams(condition: WorkflowCondition, vararg names: String) {
        val function = functions.findById(requireNotNull(condition.functionId)).orElseThrow()
        function.params = names.map { FunctionParam(name = it, type = ValueType.MAP) }.toMutableList()
        functions.save(function)
    }

    private fun condition(name: String, source: String): WorkflowCondition {
        val function = functions.save(
            WorkflowFunction(workspaceId = workspaceId, name = name.replace(" ", ""), source = source, returnType = ValueType.BOOLEAN),
        )
        return conditions.save(
            WorkflowCondition(
                workspaceId = workspaceId,
                name = name,
                type = ConditionType.FUNCTION,
                functionId = function.id,
            ),
        )
    }
}
