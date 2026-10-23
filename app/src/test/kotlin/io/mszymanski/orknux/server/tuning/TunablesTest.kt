package io.mszymanski.orknux.server.tuning

import io.mszymanski.orknux.connector.ConnectionKnob
import io.mszymanski.orknux.connector.ConnectionTuning
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.chat.AgentRunTools
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.workflow.ExecutionKnob
import org.assertj.core.api.Assertions.assertThat
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
 * the README and the modules' own knobs - because a number added in one place
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

    @BeforeEach
    fun clean() {
        settings.deleteAll(settings.findAll().filter { row -> Tunable.entries.any { it.key == row.name } })
        audit.deleteAll()
    }

    @Test
    fun `every tunable has a unique key, a fallback inside its range, and one line of label`() {
        assertThat(Tunable.entries.map { it.key }).doesNotHaveDuplicates()
        Tunable.entries.forEach {
            assertThat(it.fallback).describedAs(it.key).isBetween(it.least, it.most)
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
    }

    /**
     * The file names each one with its variable and the same default, and the
     * README lists the variable - which is what `DOCKERHUB.md` points at.
     */
    @Test
    fun `the configuration file and the README carry every tunable`() {
        val yml = File("src/main/resources/application.yml").readText()
        val readme = File("../README.md").readText()
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
        graphQlTester.document("""mutation { setTunable(key: "agent.wait.longest.seconds", value: 0) { value } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("Choose between 1 and 3600")
                    assertThat(it.extensions["code"]).isEqualTo("TunableOutOfRange")
                })
            }
        assertThat(settings.existsById("agent.wait.longest.seconds")).isFalse()
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
                value = "99999",
                lastModifiedBy = "somebody",
            ),
        )
        assertThat(tunables[Tunable.HTTP_TIMEOUT_SECONDS]).isEqualTo(30)
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
