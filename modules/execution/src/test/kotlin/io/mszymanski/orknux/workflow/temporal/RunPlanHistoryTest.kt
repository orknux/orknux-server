package io.mszymanski.orknux.workflow.temporal

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import io.temporal.common.converter.DefaultDataConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A plan read back from a history an older release wrote.
 *
 * A run's plan is the result of its first activity, so it sits in Temporal's
 * history and is read again on every replay - by whatever release is running
 * then. A field added later is absent from it, and Temporal's own Jackson
 * mapper does not know Kotlin's defaults: it hands the constructor null.
 * Upgrading 0.9.9.7 to 0.9.9.8 left every run parked across the upgrade stuck
 * for ever on "Parameter specified as non-null is null: ... parameter splits".
 */
class RunPlanHistoryTest {

    private fun read(json: String): RunPlan = DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
        Payload.newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
            .setData(ByteString.copyFromUtf8(json))
            .build(),
        RunPlan::class.java,
        RunPlan::class.java,
    )

    @Test
    fun `a plan 0_9_9_7 wrote, without splits or parallelism, reads as the sequential run it was`() {
        val plan = read(
            """{"executionId":10,"workflowName":"Upgrade linear","steps":["first","then"],"input":"{}",""" +
                """"edges":[{"source":"first","target":"then","branch":null,"option":null}],"carried":[],"blocked":[]}""",
        )

        assertThat(plan.splits).isEmpty()
        assertThat(plan.parallelism).isEqualTo(1)
        assertThat(plan.steps).containsExactly("first", "then")
    }

    @Test
    fun `a plan from before edges, exits and blocked triggers existed reads too`() {
        val plan = read("""{"executionId":3,"workflowName":"Old","steps":["a"]}""")

        assertThat(plan.edges).isEmpty()
        assertThat(plan.carried).isEmpty()
        assertThat(plan.blocked).isEmpty()
        assertThat(plan.splits).isEmpty()
        assertThat(plan.parallelism).isEqualTo(1)
        assertThat(plan.input).isNull()
    }

    @Test
    fun `a plan this release writes reads back as itself`() {
        val written = RunPlan(
            executionId = 7, workflowName = "Now", steps = listOf("a", "b", "c"), input = "{}",
            splits = listOf("a"), parallelism = 4,
        )
        val payload = DefaultDataConverter.STANDARD_INSTANCE.toPayload(written).get()

        assertThat(DefaultDataConverter.STANDARD_INSTANCE.fromPayload(payload, RunPlan::class.java, RunPlan::class.java))
            .isEqualTo(written)
    }
}
