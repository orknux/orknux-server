package io.mszymanski.orknux.server.workflow

import org.assertj.core.api.Assertions.assertThat
import io.mszymanski.orknux.workflow.execution.NodeBinding
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** A value's `{{field}}` placeholders, filled from what the run carries when the step runs. */
class PlaceholdersTest {

    private val mapper = JsonMapper.builder().build()
    private val expressions = NodeExpressions(mapper)

    @Test
    fun `a written value's placeholders are filled from the run and the event`() {
        val input = mapper.readTree("""{ "result": { "total": 12, "note": "done" }, "reply": "Sure" }""")
        val trigger = mapper.readTree("""{ "user": "alice" }""")
        val value = NodeBinding(expression = "Hi {{ trigger.user }}: {{reply}} - {{result.total}} items, {{result}}")

        assertThat(expressions.textOf(value, input, trigger))
            .isEqualTo("""Hi alice: Sure - 12 items, {"total":12,"note":"done"}""")
        // As JSON for a function, the filled text is still one string.
        assertThat(expressions.jsonOf(value, input, trigger))
            .isEqualTo(mapper.writeValueAsString("""Hi alice: Sure - 12 items, {"total":12,"note":"done"}"""))
        // A typed field reads the filled text as what it spells.
        assertThat(expressions.jsonOf(NodeBinding(expression = "{{result.total}}", type = "NUMBER"), input, trigger)).isEqualTo("12")
    }

    @Test
    fun `what is placeholder-shaped and is not a placeholder is told apart`() {
        assertThat(Placeholders.fieldsIn("{{a}} {{ b.c }} {{a}}")).containsExactly("a", "b.c")
        assertThat(Placeholders.malformedIn("{{a}} {{ 1x }} {{open")).containsExactly("{{ 1x }}", "{{open")
        assertThat(Placeholders.malformedIn("no braces here")).isEmpty()
    }
}
