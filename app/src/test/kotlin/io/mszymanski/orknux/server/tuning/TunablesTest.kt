package io.mszymanski.orknux.server.tuning

import io.mszymanski.orknux.connector.ConnectionKnob
import io.mszymanski.orknux.connector.ConnectionTuning
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.chat.AgentRunTools
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.workflow.ExecutionKnob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.io.File

/**
 * The numbers the server behaves by, as settings. Issue #453.
 *
 * Two halves. The list is held to its other halves - the configuration file,
 * docs/reference.md and the modules' own knobs - because a number added in one place
 * and not the others is a box with no variable behind it, or a variable the
 * documentation does not know. And a number changed on the screen is the number
 * the code then uses, which is asserted on a reader rather than on the stored
 * row: the stored row is not what anybody was promised.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TunablesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tunables: Tunables,
    @Autowired val settings: InstallationSettingRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val runTools: AgentRunTools,
    @Autowired val connectionTuning: ConnectionTuning,
) {

    /** Before and after: a test that stores the largest number for every setting must not leave it for the next class. */
    @BeforeEach
    @AfterEach
    fun clean() {
        settings.deleteAll(settings.findAll().filter { row -> Tunable.entries.any { it.key == row.name } })
        audit.deleteAll()
    }

    @Test
    fun `every tunable has a unique key, a fallback inside its range, and one line of label`() {
        assertThat(Tunable.entries.map { it.key }).doesNotHaveDuplicates()
        Tunable.entries.forEach {
            assertThat(it.accepts(it.fallback)).describedAs(it.key).isTrue()
            assertThat(it.fallback).describedAs(it.key).isGreaterThanOrEqualTo(it.least)
            it.most?.let { most -> assertThat(it.fallback).describedAs(it.key).isLessThanOrEqualTo(most) }
            assertThat(it.label).describedAs(it.key).doesNotContain("\n").doesNotEndWith(".")
            assertThat(it.hint).describedAs(it.key).isNotBlank()
        }
    }

    /** A module's knob that the app does not list would be read and never shown. */
    @Test
    fun `every knob a module declares is listed exactly once`() {
        val listed = Tunable.entries.map { it.key }
        (ConnectionKnob.entries.map { it.key } + ExecutionKnob.entries.map { it.key }).forEach { key ->
            assertThat(listed.count { it == key }).describedAs(key).isEqualTo(1)
        }
        ConnectionKnob.entries.forEach { knob ->
            val tunable = Tunable.byKey(knob.key)!!
            assertThat(listOf(tunable.fallback, tunable.least, tunable.most))
                .isEqualTo(listOf(knob.fallback, knob.least, knob.most))
        }
        ExecutionKnob.entries.forEach { knob ->
            val tunable = Tunable.byKey(knob.key)!!
            assertThat(listOf(tunable.fallback, tunable.least, tunable.most))
                .isEqualTo(listOf(knob.fallback, knob.least, knob.most))
        }
    }

    /**
     * The owner's rule: no ceiling that is only a guess at what is sensible. The
     * few kept are a share of a whole or a range something outside this server
     * takes, and each says why beside it in Tunable.kt; a new ceiling is a
     * decision, and this list is where it is made.
     */
    @Test
    fun `only shares of a whole and outside ranges have a ceiling`() {
        assertThat(Tunable.entries.filter { it.most != null }.associate { it.key to it.most })
            .isEqualTo(
                mapOf(
                    "tool.result.window.percent" to 90,
                    "turn.window.reserve.percent" to 75,
                    "speech.speed.percent" to 400,
                    "midi.render.sample.rate" to 48_000,
                    "midi.render.mp3.kbits" to 320,
                    "wireframe.picture.pixels" to 4096,
                    "diagram.picture.pixels" to 4096,
                    "document.svg.pixels" to 4096,
                    "document.import.table.rows.full.percent" to 100,
                    "video.export.gif.fps" to 50,
                    "images.brush.opacity.percent" to 100,
                    "images.brush.hardness.percent" to 100,
                    "images.marker.opacity.percent" to 100,
                    "images.blur.pixels" to 254,
                    "research.step.retries" to 9,
                    "research.step.retry.seconds" to 3600,
                ),
            )
    }

    /** As large as the box takes is a number like any other: stored, and read back by the code. */
    @Test
    fun `every setting with no ceiling takes the largest number there is`() {
        Tunable.entries.filter { it.most == null }.forEach {
            tunables.set(it, Int.MAX_VALUE, "alice")
            assertThat(tunables[it]).describedAs(it.key).isEqualTo(Int.MAX_VALUE)
        }
        assertThat(connectionTuning.valueOf(ConnectionKnob.RETRY_AFTER_LONGEST_SECONDS)).isEqualTo(Int.MAX_VALUE)

        graphQlTester.document("{ tunables { key most } }")
            .execute()
            .path("tunables[?(@.key == 'agent.wait.longest.seconds')].most").entity(List::class.java).isEqualTo(listOf(null))
            .path("tunables[?(@.key == 'tool.result.window.percent')].most").entityList(Int::class.java).containsExactly(90)
    }

    /**
     * The file names each one with its variable and the same default, and the
     * reference lists the variable - which is what `DOCKERHUB.md` points at.
     */
    @Test
    fun `the configuration file and the reference carry every tunable`() {
        val yml = File("src/main/resources/application.yml").readText()
        val readme = File("../docs/reference.md").readText()
        Tunable.entries.forEach {
            assertThat(yml).describedAs(it.key).contains("${it.key}: \${${it.variable}:${it.fallback}}")
            assertThat(readme).describedAs(it.key).contains("`${it.variable}`")
        }
        assertThat(File("../DOCKERHUB.md").readText()).contains("ORKNUX_TUNING_*")
    }

    @Test
    fun `a fresh installation reads what the file says`() {
        Tunable.entries.forEach {
            assertThat(tunables[it]).describedAs(it.key).isEqualTo(it.fallback)
            assertThat(tunables.configured(it)).isEqualTo(it.fallback)
        }

        graphQlTester.document("{ tunables { key value configured set least most variable sectionTitle unit } }")
            .execute()
            .path("tunables[?(@.key == 'agent.wait.default.seconds')].value").entityList(Int::class.java).containsExactly(30)
            .path("tunables[?(@.key == 'agent.wait.default.seconds')].set").entityList(Boolean::class.java).containsExactly(false)
            .path("tunables[?(@.key == 'agent.wait.default.seconds')].variable").entityList(String::class.java)
            .containsExactly("ORKNUX_TUNING_AGENT_WAIT_DEFAULT_SECONDS")
            .path("tunables[*].key").entityList(String::class.java).hasSize(Tunable.entries.size)
    }

    @Test
    fun `a number set on the screen is the number the code uses, and is audited`() {
        assertThat(runTools.waitSecondsIn("{}")).isEqualTo(30)

        graphQlTester.document("""mutation { setTunable(key: "agent.wait.default.seconds", value: 45) { value configured set } }""")
            .execute()
            .path("setTunable.value").entity(Int::class.java).isEqualTo(45)
            .path("setTunable.configured").entity(Int::class.java).isEqualTo(30)
            .path("setTunable.set").entity(Boolean::class.java).isEqualTo(true)

        assertThat(runTools.waitSecondsIn("{}")).isEqualTo(45)
        assertThat(audit.findAll().map { it.message })
            .contains("How long agent_wait waits when not told: set to 45 seconds")
    }

    /** A module reads its knob through the interface it declared, and gets the screen's answer. */
    @Test
    fun `a module's knob answers what the screen stored`() {
        assertThat(connectionTuning.valueOf(ConnectionKnob.RETRY_AFTER_ATTEMPTS)).isEqualTo(2)

        graphQlTester.document("""mutation { setTunable(key: "model.retry.after.attempts", value: 5) { value } }""")
            .execute().path("setTunable.value").entity(Int::class.java).isEqualTo(5)

        assertThat(connectionTuning.valueOf(ConnectionKnob.RETRY_AFTER_ATTEMPTS)).isEqualTo(5)
    }

    @Test
    fun `no value hands it back to the file`() {
        tunables.set(Tunable.SCHEDULES_PER_TICK, 7, "alice")
        assertThat(tunables[Tunable.SCHEDULES_PER_TICK]).isEqualTo(7)

        graphQlTester.document("""mutation { setTunable(key: "schedules.per.tick") { value set } }""")
            .execute()
            .path("setTunable.value").entity(Int::class.java).isEqualTo(60)
            .path("setTunable.set").entity(Boolean::class.java).isEqualTo(false)
        assertThat(audit.findAll().map { it.message })
            .contains("How many runs one schedule may start in one tick: back to the configured 60 items")
    }

    @Test
    fun `a number outside the range is refused by code and nothing is stored`() {
        // No ceiling: only the floor is said, and no `most` is sent for a translation to print.
        graphQlTester.document("""mutation { setTunable(key: "agent.wait.longest.seconds", value: 0) { value } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("Choose 1 or more.")
                    assertThat(it.extensions["code"]).isEqualTo("TunableOutOfRange")
                    @Suppress("UNCHECKED_CAST")
                    assertThat(it.extensions["arguments"] as Map<String, Any?>).containsEntry("least", 1).doesNotContainKey("most")
                })
            }
        assertThat(settings.existsById("agent.wait.longest.seconds")).isFalse()

        // A share of a whole keeps its ceiling, and says both ends.
        graphQlTester.document("""mutation { setTunable(key: "tool.result.window.percent", value: 91) { value } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("Choose between 1 and 90.")
                    assertThat(it.extensions["code"]).isEqualTo("TunableOutOfRange")
                })
            }
        assertThat(settings.existsById("tool.result.window.percent")).isFalse()
    }

    @Test
    fun `an unknown key is refused by name`() {
        graphQlTester.document("""mutation { setTunable(key: "no.such.thing", value: 1) { value } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.extensions["code"]).isEqualTo("TunableUnknown")
                })
            }
    }

    /** A row an older release wrote with a wider range, or by hand, reads as the file's number. */
    @Test
    fun `a stored value outside the range reads as the configured one`() {
        settings.save(
            io.mszymanski.orknux.server.attachment.InstallationSetting(
                name = Tunable.HTTP_TIMEOUT_SECONDS.key,
                value = "0",
                lastModifiedBy = "somebody",
            ),
        )
        assertThat(tunables[Tunable.HTTP_TIMEOUT_SECONDS]).isEqualTo(30)

        settings.save(
            io.mszymanski.orknux.server.attachment.InstallationSetting(
                name = Tunable.TOOL_RESULT_WINDOW_PERCENT.key,
                value = "99",
                lastModifiedBy = "somebody",
            ),
        )
        assertThat(tunables[Tunable.TOOL_RESULT_WINDOW_PERCENT]).isEqualTo(10)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `somebody who is not an administrator can neither read nor change them`() {
        graphQlTester.document("{ tunables { key } }").execute().errors().satisfy { assertThat(it).isNotEmpty() }
        graphQlTester.document("""mutation { setTunable(key: "schedules.per.tick", value: 5) { value } }""")
            .execute().errors().satisfy { assertThat(it).isNotEmpty() }
        assertThat(settings.existsById("schedules.per.tick")).isFalse()
    }
}
