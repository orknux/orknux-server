package io.mszymanski.orknux.server.logging

import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * Log levels from Admin -> Settings, without a restart. Issue #591.
 *
 * What is asserted is what a logger does, not what the screen says: a level is
 * set when `isDebugEnabled` flips on a logger nobody touched directly. The
 * stored half is asserted by taking this JVM's copy away and calling the hook a
 * start runs, which is all a restart does to it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LogLevelAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val levels: LogLevels,
    @Autowired val settings: InstallationSettingRepository,
    @Autowired val loggingSystem: LoggingSystem,
    @Autowired val audit: WorkspaceAuditRepository,
) {
    /** A logger of its own, so turning it up cannot make the rest of the suite louder. */
    private val name = "io.mszymanski.orknux.loglevelprobe"
    private val logger = LoggerFactory.getLogger("$name.Inner")

    @BeforeEach
    fun clean() {
        levels.reset()
        settings.deleteAll(settings.findAll().filter { it.name.startsWith("logging.") })
        audit.deleteAll()
    }

    @AfterEach
    fun restore() {
        levels.reset()
    }

    private fun set(logger: String, level: String) = graphQlTester
        .document("""mutation { setLogLevel(name: "$logger", level: "$level") { loggers { name level effectiveLevel } } }""")
        .execute()

    @Test
    fun `setting a level changes what the logger logs, and clearing gives it back to its parent`() {
        assertThat(logger.isDebugEnabled).isFalse()

        set(name, "DEBUG").path("setLogLevel.loggers[?(@.name == '$name')].effectiveLevel")
            .entityList(String::class.java).containsExactly("DEBUG")
        assertThat(logger.isDebugEnabled).isTrue()

        val left = graphQlTester.document("""mutation { clearLogLevel(name: "$name") { loggers { name } } }""").execute()
            .path("clearLogLevel.loggers[*].name").entityList(String::class.java).get()
        assertThat(left).doesNotContain(name)
        assertThat(logger.isDebugEnabled).isFalse()
        assertThat(loggingSystem.getLoggerConfiguration(name)!!.configuredLevel).isNull()

        assertThat(audit.findAll().map { it.message })
            .contains("Log level of $name set to DEBUG", "Log level of $name back to the configuration's")
    }

    @Test
    fun `inherit takes the parent's level even where the configuration names the logger`() {
        // The configuration names io.mszymanski.orknux; INHERIT is the screen saying "not that".
        set("io.mszymanski.orknux", "WARN").errors().verify()
        assertThat(LoggerFactory.getLogger("io.mszymanski.orknux.Some").isInfoEnabled).isFalse()

        set("io.mszymanski.orknux", "INHERIT").errors().verify()
        assertThat(loggingSystem.getLoggerConfiguration("io.mszymanski.orknux")!!.configuredLevel).isNull()
        assertThat(audit.findAll().map { it.message }).contains("Log level of io.mszymanski.orknux set to inherit")

        graphQlTester.document("mutation { resetLogLevels { loggers { name level defaultLevel } } }").execute()
            .path("resetLogLevels.loggers[?(@.name == 'io.mszymanski.orknux')].defaultLevel")
            .entityList(String::class.java).containsExactly("INFO")
        assertThat(loggingSystem.getLoggerConfiguration("io.mszymanski.orknux")!!.configuredLevel).isEqualTo(LogLevel.INFO)
        assertThat(audit.findAll().map { it.message }).contains("Log levels reset to the configuration's defaults")
    }

    @Test
    fun `a stored level is applied again by a server that starts`() {
        set(name, "TRACE").errors().verify()

        // What a restart does to this JVM's copy: the logger is back to nothing.
        loggingSystem.setLogLevel(name, null)
        assertThat(logger.isTraceEnabled).isFalse()

        levels.applyAtStart()
        assertThat(logger.isTraceEnabled).isTrue()

        graphQlTester.document("{ logLevels { loggers { name level } } }").execute()
            .path("logLevels.loggers[?(@.name == '$name')].level").entityList(String::class.java).containsExactly("TRACE")
    }

    @Test
    fun `a level another server stored is picked up by the next refresh`() {
        val row = io.mszymanski.orknux.server.attachment.InstallationSetting(
            name = "log.level.$name",
            value = "DEBUG",
            lastModifiedBy = "another-replica",
        )
        settings.save(row)
        assertThat(logger.isDebugEnabled).isFalse()

        levels.refresh()
        assertThat(logger.isDebugEnabled).isTrue()

        settings.deleteById("log.level.$name")
        levels.refresh()
        assertThat(logger.isDebugEnabled).isFalse()
    }

    @Test
    fun `a root below INFO goes back on its own once the limit has passed`() {
        graphQlTester.document("mutation { setLogRootRevertMinutes(minutes: 1) { rootRevertMinutes } }").execute()
            .path("setLogRootRevertMinutes.rootRevertMinutes").entity(Int::class.java).isEqualTo(1)

        set("root", "DEBUG").path("setLogLevel.loggers[0].effectiveLevel").entity(String::class.java).isEqualTo("DEBUG")
        graphQlTester.document("{ logLevels { loggers { name revertsAt } } }").execute()
            .path("logLevels.loggers[0].revertsAt").entity(String::class.java).satisfies { assertThat(it).isNotBlank() }

        // Not yet: a refresh inside the limit leaves it alone.
        levels.refresh()
        assertThat(loggingSystem.getLoggerConfiguration("ROOT")!!.effectiveLevel).isEqualTo(LogLevel.DEBUG)

        // The limit passes.
        val held = settings.findById("log.level.ROOT").get()
        held.lastModifiedAt = OffsetDateTime.now().minusMinutes(2)
        settings.save(held)
        levels.refresh()

        assertThat(loggingSystem.getLoggerConfiguration("ROOT")!!.effectiveLevel).isEqualTo(LogLevel.INFO)
        assertThat(settings.findById("log.level.ROOT")).isEmpty()
        assertThat(audit.findAll().map { it.message })
            .contains("Log level of ROOT set to DEBUG", "Log level of ROOT went back to INFO after 1 minutes")
    }

    @Test
    fun `a named logger at DEBUG is not reverted, only the root is`() {
        set(name, "DEBUG").errors().verify()
        val held = settings.findById("log.level.$name").get()
        held.lastModifiedAt = OffsetDateTime.now().minusDays(2)
        settings.save(held)

        levels.refresh()
        assertThat(logger.isDebugEnabled).isTrue()
    }

    @Test
    fun `an unknown level, a name that is not a logger's and an inheriting root are refused`() {
        set(name, "LOUD").errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("LogLevelUnknown") }
        set("org.hibernate SQL", "DEBUG").errors()
            .satisfy { assertThat(it.single().extensions["code"]).isEqualTo("LoggerNameInvalid") }
        set("org..hibernate", "DEBUG").errors()
            .satisfy { assertThat(it.single().extensions["code"]).isEqualTo("LoggerNameInvalid") }
        set("a".repeat(101), "DEBUG").errors()
            .satisfy { assertThat(it.single().extensions["code"]).isEqualTo("LoggerNameInvalid") }
        set("ROOT", "INHERIT").errors()
            .satisfy { assertThat(it.single().extensions["code"]).isEqualTo("RootLogLevelInherit") }
        graphQlTester.document("mutation { setLogRootRevertMinutes(minutes: 0) { rootRevertMinutes } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("LogRootRevertOutOfRange") }
        graphQlTester.document("mutation { setLogFollowSeconds(seconds: 1) { followSeconds } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("LogFollowOutOfRange") }

        // A name with a dollar and an underscore is a class's, and is fine.
        set("io.mszymanski.orknux.Outer\$Inner_1", "WARN").errors().verify()
        assertThat(settings.findAll().map { it.name }).doesNotContain("log.level.org.hibernate SQL")
    }

    @Test
    fun `the list carries the root, the configuration's loggers and suggestions not yet on it`() {
        val answer = graphQlTester.document("{ logLevels { loggers { name defaultLevel } suggestions levels followSeconds } }")
            .execute()
        answer.path("logLevels.loggers[0].name").entity(String::class.java).isEqualTo("ROOT")
        assertThat(answer.path("logLevels.loggers[*].name").entityList(String::class.java).get())
            .contains("io.mszymanski.orknux")
        assertThat(answer.path("logLevels.suggestions").entityList(String::class.java).get())
            .contains("org.hibernate.SQL").doesNotContain("io.mszymanski.orknux")
        answer.path("logLevels.levels").entityList(String::class.java)
            .containsExactly("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "INHERIT")
            .path("logLevels.followSeconds").entity(Int::class.java).isEqualTo(30)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who is not an administrator sees and changes nothing`() {
        graphQlTester.document("{ logLevels { followSeconds } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        set(name, "DEBUG").errors().satisfy { assertThat(it).isNotEmpty() }
        graphQlTester.document("mutation { resetLogLevels { followSeconds } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        assertThat(logger.isDebugEnabled).isFalse()
        assertThat(settings.findAll().map { it.name }).noneMatch { it.startsWith("log.level.") }
    }
}
