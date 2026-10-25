package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.graphql.test.tester.GraphQlTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A model's speed averaged per minute, hour, day, month or year over the usage
 * card's window: `Model.speedOverTime`.
 *
 * The calls are written as rows rather than made, because what is under test is
 * when they happened, and a call made now happens now. Times are the server's
 * own zone, which is the zone its buckets and its usage days are in.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ModelSpeedOverTimeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val callRows: SessionModelCallRepository,
) {

    private var workspaceId: Long = 0
    private var session: Long = 0
    private var model: Long = 0

    @BeforeEach
    fun start() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "speed-chart-${System.nanoTime()}")).id)
        session = recorder.open(workspaceId, "test", "speed-chart-${System.nanoTime()}")
        model = model()
    }

    @AfterEach
    fun stop() {
        graphQlTester.document("""mutation { setTunable(key: "model.speed.points") { value } }""").execute()
    }

    /** One fixture across the units: six minutes, five hours, four days, three months, two years. */
    @Test
    fun `one point per period that had timed calls, oldest first, for every unit`() {
        seed()
        val range = """from: "2025-12-01", to: "2026-02-28""""

        assertThat(at("MINUTE", range)).containsExactly(
            "2025-12-31T23:59", "2026-01-10T10:15", "2026-01-10T10:16", "2026-01-10T11:30", "2026-01-11T09:00", "2026-02-03T08:00",
        )
        assertThat(at("HOUR", range)).containsExactly(
            "2025-12-31T23:00", "2026-01-10T10:00", "2026-01-10T11:00", "2026-01-11T09:00", "2026-02-03T08:00",
        )
        assertThat(at("DAY", range)).containsExactly(
            "2025-12-31T00:00", "2026-01-10T00:00", "2026-01-11T00:00", "2026-02-03T00:00",
        )
        assertThat(at("MONTH", range)).containsExactly("2025-12-01T00:00", "2026-01-01T00:00", "2026-02-01T00:00")
        assertThat(at("YEAR", range)).containsExactly("2025-01-01T00:00", "2026-01-01T00:00")

        val month = chart("MONTH", range)
        month.path("model.speedOverTime.points[*].calls").entityList(Int::class.java).containsExactly(1, 5, 1)
        month.path("model.speedOverTime.unit").entity(String::class.java).isEqualTo("MONTH")
        month.path("model.speedOverTime.from").entity(String::class.java).isEqualTo("2025-12-01")
        month.path("model.speedOverTime.to").entity(String::class.java).isEqualTo("2026-02-28")
        month.path("model.speedOverTime.truncated").entity(Boolean::class.java).isEqualTo(false)
    }

    /**
     * Every token over every millisecond in the period, as `Model.speed` is: 4000
     * prompt tokens over 1.5 s is 2667/s where the mean of the two rates would be
     * 2500, and 200 generated over 3 s is 66.7/s where the mean is 60. A period
     * served whole from cache has no prompt speed rather than an infinite one.
     */
    @Test
    fun `a period's speeds are weighted by tokens`() {
        seed()
        val minute = chart("MINUTE", """from: "2026-01-10", to: "2026-01-10"""")
        minute.path("model.speedOverTime.points[0].at").entity(String::class.java).isEqualTo("2026-01-10T10:15")
        minute.path("model.speedOverTime.points[0].calls").entity(Int::class.java).isEqualTo(2)
        assertThat(minute.path("model.speedOverTime.points[0].promptTokensPerSecond").entity(Double::class.java).get())
            .isCloseTo(2666.67, within(0.01))
        assertThat(minute.path("model.speedOverTime.points[0].generatedTokensPerSecond").entity(Double::class.java).get())
            .isCloseTo(66.67, within(0.01))

        // 11:30 was a prompt served from cache: no prompt milliseconds, so no prompt speed.
        minute.path("model.speedOverTime.points[2].at").entity(String::class.java).isEqualTo("2026-01-10T11:30")
        minute.path("model.speedOverTime.points[2].promptTokensPerSecond").valueIsNull()
        minute.path("model.speedOverTime.points[2].generatedTokensPerSecond").entity(Double::class.java).isEqualTo(50.0)
    }

    /** A call with no timings is no point, and a day with only such calls is a gap, never a zero. */
    @Test
    fun `periods with no timed calls are left out`() {
        seed()
        call(LocalDateTime.of(2026, 1, 20, 12, 0), timed = false)
        assertThat(at("DAY", """from: "2026-01-01", to: "2026-01-31"""")).containsExactly(
            "2026-01-10T00:00", "2026-01-11T00:00",
        )
        graphQlTester.document("""{ model(id: $model) { speedOverTime(unit: DAY, from: "2026-01-12", to: "2026-01-31") {
              points { at } truncated } } }""").execute()
            .path("model.speedOverTime.points").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `the range is the usage card's, either way round, and a date it cannot read is refused`() {
        seed()
        assertThat(at("HOUR", """from: "2026-01-10", to: "2026-01-10"""")).containsExactly(
            "2026-01-10T10:00", "2026-01-10T11:00",
        )
        assertThat(at("HOUR", """from: "2026-01-11", to: "2026-01-10"""")).containsExactly(
            "2026-01-10T10:00", "2026-01-10T11:00", "2026-01-11T09:00",
        )
        // The last day counts to its end: 23:59 on the 31st is in a range that stops on the 31st.
        assertThat(at("MINUTE", """from: "2025-12-31", to: "2025-12-31"""")).containsExactly("2025-12-31T23:59")
        // Nothing asked is the last thirty days, which none of these calls are in.
        graphQlTester.document("{ model(id: $model) { speedOverTime(unit: DAY) { points { at } } } }").execute()
            .path("model.speedOverTime.points").entityList(Any::class.java).hasSize(0)

        graphQlTester.document("""{ model(id: $model) { speedOverTime(unit: DAY, from: "nope") { truncated } } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).anySatisfy { assertThat(it.message).contains("\"nope\" is not a date") }
            }
    }

    /** Past Admin -> Settings' number of points, the newest that many, and the answer says it was cut. */
    @Test
    fun `past the cap the newest periods are answered and the answer says so`() {
        (0 until 12).forEach { minute -> call(LocalDateTime.of(2026, 3, 1, 9, minute, 30)) }
        graphQlTester.document("""mutation { setTunable(key: "model.speed.points", value: 10) { value } }""").execute()
            .path("setTunable.value").entity(Int::class.java).isEqualTo(10)

        val range = """from: "2026-03-01", to: "2026-03-01""""
        val minutes = chart("MINUTE", range)
        minutes.path("model.speedOverTime.truncated").entity(Boolean::class.java).isEqualTo(true)
        minutes.path("model.speedOverTime.points[*].at").entityList(String::class.java)
            .containsExactly(*(2 until 12).map { "2026-03-01T09:%02d".format(it) }.toTypedArray())

        val hours = chart("HOUR", range)
        hours.path("model.speedOverTime.truncated").entity(Boolean::class.java).isEqualTo(false)
        hours.path("model.speedOverTime.points[*].calls").entityList(Int::class.java).containsExactly(12)
    }

    /* ---------------------------------------------------------- the fixtures */

    /**
     * 2025-12-31 23:59; on 2026-01-10 two calls at 10:15, one at 10:16 and one
     * at 11:30 served from cache; 2026-01-11 09:00; 2026-02-03 08:00.
     */
    private fun seed() {
        call(LocalDateTime.of(2025, 12, 31, 23, 59, 10))
        call(LocalDateTime.of(2026, 1, 10, 10, 15, 20), prompt = 1000, promptMillis = 500.0, generated = 40, generatedMillis = 1000.0)
        call(LocalDateTime.of(2026, 1, 10, 10, 15, 40), prompt = 3000, promptMillis = 1000.0, generated = 160, generatedMillis = 2000.0)
        call(LocalDateTime.of(2026, 1, 10, 10, 16, 5))
        call(LocalDateTime.of(2026, 1, 10, 11, 30, 0), prompt = 0, promptMillis = 0.0, generated = 50, generatedMillis = 1000.0)
        call(LocalDateTime.of(2026, 1, 11, 9, 0, 0))
        call(LocalDateTime.of(2026, 2, 3, 8, 0, 0))
    }

    private fun call(
        at: LocalDateTime,
        timed: Boolean = true,
        prompt: Long = 100,
        promptMillis: Double = 100.0,
        generated: Long = 10,
        generatedMillis: Double = 100.0,
    ) {
        callRows.save(
            SessionModelCall(
                sessionId = session,
                modelId = model,
                at = at.atZone(ZoneId.systemDefault()).toOffsetDateTime(),
                inputTokens = prompt,
                cachedInputTokens = 0,
                outputTokens = generated,
                millis = 1000,
                promptTokens = if (timed) prompt else null,
                promptMillis = if (timed) promptMillis else null,
                generatedTokens = if (timed) generated else null,
                generatedMillis = if (timed) generatedMillis else null,
            ),
        )
    }

    private fun chart(unit: String, range: String): GraphQlTester.Response = graphQlTester.document(
        """{ model(id: $model) { speedOverTime(unit: $unit, $range) {
             unit from to truncated points { at calls promptTokensPerSecond generatedTokensPerSecond } } } }""",
    ).execute()

    private fun at(unit: String, range: String): List<String> =
        chart(unit, range).path("model.speedOverTime.points[*].at").entityList(String::class.java).get()

    private fun model(): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "llama ${System.nanoTime()}", type: LLAMA_CPP,
                 endpoint: "http://speed-chart.invalid", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "fast ${System.nanoTime()}", modelId: "fast", kind: CHAT
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
