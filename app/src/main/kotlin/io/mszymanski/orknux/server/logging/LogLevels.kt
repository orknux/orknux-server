package io.mszymanski.orknux.server.logging

import io.mszymanski.orknux.server.attachment.InstallationSetting
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.SettingNames
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * The server's log levels, as an administrator set them from the screen. Issue #591.
 *
 * Each choice is one `installation_setting` row, `log.level.<logger>`, rather
 * than a table of its own: they are few, they are the installation's, and the
 * table is already what every replica reads. The row is the truth and the
 * logging system is a copy of it - [refresh] makes this JVM's copy agree, on a
 * mutation here, at start-up, and every [followSeconds] after that, which is
 * how a replica nobody spoke to follows the one somebody did.
 *
 * What a logger goes back to when its row goes is the configuration's answer -
 * `logging.level.*`, which is where `ORKNUX_LOG_LEVEL` and its siblings land -
 * so "reset" means the file again, not some default this class invented.
 *
 * One rule is not the administrator's to forget: a root logger below INFO
 * reverts after [rootRevertMinutes]. DEBUG on everything, Hibernate and Tomcat
 * included, fills a disk in an afternoon, and the person who turned it on to
 * chase one problem has usually gone home by then.
 */
@Service
class LogLevels(
    private val settings: InstallationSettingRepository,
    private val rows: LogLevelRows,
    private val loggingSystem: LoggingSystem,
    private val environment: Environment,
    private val audit: WorkspaceAuditRecorder,
    /** False in the suite, which calls [refresh] itself rather than racing a clock. */
    @Value("\${orknux.logging.levels.follow-enabled:true}") private val followEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** What this JVM last applied, by logger: the row's value, as it was read. */
    private val applied = ConcurrentHashMap<String, String>()

    @Volatile
    private var follower: Thread? = null

    /**
     * Applies what is stored, as a fresh JVM must: everything, whatever this one
     * thinks it already did. Then starts following the other replicas.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun applyAtStart() {
        applied.clear()
        try {
            refresh()
        } catch (failure: Exception) {
            // A server that cannot read its log levels still serves; it says so.
            log.warn("Could not apply the stored log levels: {}", failure.message)
        }
        if (followEnabled && follower == null) {
            follower = Thread.ofVirtual().name("orknux-log-levels").start {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(followSeconds() * 1000L)
                        refresh()
                    } catch (_: InterruptedException) {
                        return@start
                    } catch (failure: Exception) {
                        log.warn("Could not refresh the log levels: {}", failure.message)
                    }
                }
            }
        }
    }

    @jakarta.annotation.PreDestroy
    fun stop() {
        follower?.interrupt()
    }

    /**
     * Makes this JVM's loggers agree with the rows, after reverting a root
     * level that has been below INFO for longer than it may be.
     */
    @Synchronized
    fun refresh() {
        revertRootIfDue()
        val desired = stored()
        for (name in applied.keys + desired.keys) {
            val wanted = desired[name]
            if (applied[name] == wanted && wanted != null) continue
            // No row is the configuration's level; a row of INHERIT is none at all.
            loggingSystem.setLogLevel(name, if (wanted == null) defaultOf(name) else levelOf(wanted))
            if (wanted == null) applied.remove(name) else applied[name] = wanted
        }
    }

    /** Every logger worth a row on the screen: the root, what was set, and what the file names. */
    fun view(): LogLevelsView {
        val stored = stored()
        val revertsAt = rootRevertsAt()
        val names = (stored.keys + fileLevels().keys - ROOT).sortedBy { it.lowercase() }
        val loggers = (listOf(ROOT) + names).map { name ->
            val now = loggingSystem.getLoggerConfiguration(name)
            LoggerLevelView(
                name = name,
                level = stored[name],
                configuredLevel = now?.configuredLevel?.name,
                effectiveLevel = (now?.effectiveLevel ?: effectiveOf(name)).name,
                defaultLevel = defaultOf(name)?.name,
                revertsAt = if (name == ROOT) revertsAt?.toString() else null,
            )
        }
        return LogLevelsView(
            loggers = loggers,
            suggestions = SUGGESTED_LOGGERS.filter { it !in names },
            levels = LEVELS,
            rootRevertMinutes = rootRevertMinutes(),
            rootRevertMinutesConfigured = DEFAULT_ROOT_REVERT_MINUTES,
            followSeconds = followSeconds(),
            followSecondsConfigured = DEFAULT_LOG_FOLLOW_SECONDS,
        )
    }

    /** Sets [name] to [level] - one of [LEVELS] - and answers the name as it is stored. */
    @Transactional
    fun set(name: String, level: String, by: String): Pair<String, String> {
        val logger = nameOf(name)
        val token = level.trim().uppercase()
        if (token !in LEVELS) throw LogLevelUnknownException(level)
        if (logger == ROOT && token == INHERIT) throw RootLogLevelInheritException()
        val held = settings.findByIdOrNull(keyOf(logger)) ?: InstallationSetting(name = keyOf(logger))
        held.value = token
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
        settings.flush()
        refresh()
        return logger to token
    }

    /** Drops what the screen said about [name], so the configuration decides again. */
    @Transactional
    fun clear(name: String): String {
        val logger = nameOf(name)
        settings.findByIdOrNull(keyOf(logger))?.let { settings.delete(it) }
        settings.flush()
        refresh()
        return logger
    }

    /** Drops every level the screen set. */
    @Transactional
    fun reset() {
        settings.deleteAll(settings.findAll().filter { it.name.startsWith(SettingNames.LOG_LEVEL_PREFIX) })
        settings.flush()
        refresh()
    }

    fun rootRevertMinutes(): Int = ranged(
        SettingNames.LOG_ROOT_REVERT_MINUTES,
        MIN_ROOT_REVERT_MINUTES..MAX_ROOT_REVERT_MINUTES,
        DEFAULT_ROOT_REVERT_MINUTES,
    )

    @Transactional
    fun setRootRevertMinutes(minutes: Int, by: String) {
        if (minutes !in MIN_ROOT_REVERT_MINUTES..MAX_ROOT_REVERT_MINUTES) throw LogRootRevertOutOfRangeException(minutes)
        write(SettingNames.LOG_ROOT_REVERT_MINUTES, minutes.toString(), by)
    }

    fun followSeconds(): Int = ranged(
        SettingNames.LOG_FOLLOW_SECONDS,
        MIN_LOG_FOLLOW_SECONDS..MAX_LOG_FOLLOW_SECONDS,
        DEFAULT_LOG_FOLLOW_SECONDS,
    )

    @Transactional
    fun setFollowSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_LOG_FOLLOW_SECONDS..MAX_LOG_FOLLOW_SECONDS) throw LogFollowOutOfRangeException(seconds)
        write(SettingNames.LOG_FOLLOW_SECONDS, seconds.toString(), by)
    }

    /**
     * Removes a root row below INFO that is older than the limit, and says so in
     * the audit log - once, from whichever replica's delete matched the row, so
     * three replicas noticing together write one entry and not three.
     */
    private fun revertRootIfDue() {
        val root = settings.findByIdOrNull(keyOf(ROOT)) ?: return
        if (root.value !in BELOW_INFO) return
        val minutes = rootRevertMinutes()
        val cutoff = OffsetDateTime.now().minusMinutes(minutes.toLong())
        if (!root.lastModifiedAt.isBefore(cutoff)) return
        if (rows.deleteIfStill(root.name, root.value) == 0) return
        val back = defaultOf(ROOT)?.name ?: "INFO"
        audit.recordAutomated(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Log level of ROOT went back to $back after $minutes minutes",
            "server",
        )
        log.info("The root log level was {} for {} minutes and went back to {}", root.value, minutes, back)
    }

    private fun rootRevertsAt(): OffsetDateTime? {
        val root = settings.findByIdOrNull(keyOf(ROOT)) ?: return null
        if (root.value !in BELOW_INFO) return null
        return root.lastModifiedAt.plusMinutes(rootRevertMinutes().toLong())
    }

    private fun stored(): Map<String, String> = settings.findAll()
        .filter { it.name.startsWith(SettingNames.LOG_LEVEL_PREFIX) }
        .mapNotNull { row ->
            val name = row.name.removePrefix(SettingNames.LOG_LEVEL_PREFIX)
            val token = row.value.trim().uppercase()
            if (NAME.matches(name) && token in LEVELS) name to token else null
        }
        .toMap()

    /** What the configuration says, by logger: `logging.level.*`, bound the way Spring binds it. */
    private fun fileLevels(): Map<String, LogLevel> =
        Binder.get(environment)
            .bind("logging.level", Bindable.mapOf(String::class.java, LogLevel::class.java))
            .orElse(emptyMap())
            .orEmpty()
            .mapKeys { (name, _) -> if (name.equals(ROOT, ignoreCase = true)) ROOT else name }

    /** The configuration's level for [name]; null is "inherit", which the root never is. */
    private fun defaultOf(name: String): LogLevel? =
        fileLevels()[name] ?: if (name == ROOT) LogLevel.INFO else null

    /** Where a logger nothing has created yet would land: its nearest configured ancestor. */
    private fun effectiveOf(name: String): LogLevel {
        var at = name
        while (true) {
            loggingSystem.getLoggerConfiguration(at)?.configuredLevel?.let { return it }
            if (!at.contains('.')) break
            at = at.substringBeforeLast('.')
        }
        return loggingSystem.getLoggerConfiguration(ROOT)?.effectiveLevel ?: LogLevel.INFO
    }

    private fun levelOf(token: String): LogLevel? = if (token == INHERIT) null else LogLevel.valueOf(token)

    private fun ranged(name: String, range: IntRange, default: Int): Int =
        settings.findByIdOrNull(name)?.value?.trim()?.toIntOrNull()?.takeIf { it in range } ?: default

    private fun write(name: String, value: String, by: String) {
        val held = settings.findByIdOrNull(name) ?: InstallationSetting(name = name)
        held.value = value
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    companion object {
        const val ROOT = LoggingSystem.ROOT_LOGGER_NAME
        const val INHERIT = "INHERIT"

        /** What may be chosen, loudest first. FATAL is not offered: Logback has none, and maps it to ERROR. */
        val LEVELS = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", INHERIT)

        /** What the root is not left at. */
        private val BELOW_INFO = setOf("TRACE", "DEBUG")

        /**
         * A logger's name: what a class or a package is called, and nothing a
         * key, a log line or an audit entry would have to escape. Dots between
         * parts, never two together or at an end.
         */
        private val NAME = Regex("""[A-Za-z0-9_$]+(\.[A-Za-z0-9_$]+)*""")

        /** Long enough for any package; short enough that the row's key fits its column. */
        const val MAX_LOGGER_NAME = 100

        /** The ones somebody chasing a problem here reaches for first. */
        val SUGGESTED_LOGGERS = listOf(
            "io.mszymanski.orknux",
            "io.mszymanski.orknux.script",
            "io.mszymanski.orknux.plugin",
            "org.hibernate.SQL",
            "org.hibernate.orm.jdbc.bind",
            "org.springframework.web",
            "org.springframework.security",
            "org.springframework.ai",
            "io.temporal",
            "org.flywaydb",
            "com.zaxxer.hikari",
        )

        private fun keyOf(logger: String) = SettingNames.LOG_LEVEL_PREFIX + logger

        /** [name] checked, with any spelling of the root's name made the one. */
        fun nameOf(name: String): String {
            val trimmed = name.trim()
            if (trimmed.equals(ROOT, ignoreCase = true)) return ROOT
            if (trimmed.length > MAX_LOGGER_NAME || !NAME.matches(trimmed)) throw LoggerNameInvalidException(trimmed)
            return trimmed
        }
    }
}

/**
 * The one delete here that has to know whether it was the one that happened.
 *
 * Matched on the value and not on the time: the age is judged before this is
 * called, and a timestamp compared in SQL is compared in whatever form the
 * engine stored it, which is not the same form on SQLite as on Postgres.
 */
interface LogLevelRows : JpaRepository<InstallationSetting, String> {

    @Modifying
    @Transactional
    @Query("delete from InstallationSetting s where s.name = :name and s.value = :value")
    fun deleteIfStill(@Param("name") name: String, @Param("value") value: String): Int
}

data class LogLevelsView(
    /** The root first, then every logger set here or named by the configuration. */
    val loggers: List<LoggerLevelView>,
    /** Loggers worth offering that are not on the list yet. */
    val suggestions: List<String>,
    /** What a level may be set to; INHERIT clears the logger's own level. */
    val levels: List<String>,
    val rootRevertMinutes: Int,
    val rootRevertMinutesConfigured: Int,
    val followSeconds: Int,
    val followSecondsConfigured: Int,
)

data class LoggerLevelView(
    val name: String,
    /** What the screen set, or null where the configuration decides. */
    val level: String?,
    /** What the logger itself carries in this server now; null inherits. */
    val configuredLevel: String?,
    /** What it actually logs at. */
    val effectiveLevel: String,
    /** What the configuration gives it; null inherits. */
    val defaultLevel: String?,
    /** When a root set below INFO goes back on its own, as ISO-8601. */
    val revertsAt: String?,
)

/**
 * A minute and a day, around an hour.
 *
 * The floor is a minute because less is not time to reproduce anything; the
 * ceiling is a day because the point of the rule is that DEBUG on everything
 * does not outlive the afternoon it was wanted for.
 */
const val MIN_ROOT_REVERT_MINUTES = 1
const val MAX_ROOT_REVERT_MINUTES = 1440
const val DEFAULT_ROOT_REVERT_MINUTES = 60

/** How often every server re-reads the levels: how long a replica lags the one that was asked. */
const val MIN_LOG_FOLLOW_SECONDS = 5
const val MAX_LOG_FOLLOW_SECONDS = 3600
const val DEFAULT_LOG_FOLLOW_SECONDS = 30

class LogLevelUnknownException(val level: String) : RuntimeException(
    "'$level' is not a log level. Choose one of ${LogLevels.LEVELS.joinToString(", ")}.",
), Refusal {
    override val arguments get() = mapOf("level" to level)
}

class LoggerNameInvalidException(val name: String) : RuntimeException(
    "'${name.take(120)}' is not a logger name. Use letters, digits, dots, \$ and _, " +
        "up to ${LogLevels.MAX_LOGGER_NAME} characters.",
), Refusal {
    override val arguments get() = mapOf("name" to name.take(120), "max" to LogLevels.MAX_LOGGER_NAME)
}

class RootLogLevelInheritException : RuntimeException(
    "The root logger has nothing to inherit from. Choose a level, or clear it to go back to the configuration's.",
)

class LogRootRevertOutOfRangeException(val minutes: Int) : RuntimeException(
    "$minutes is not a number of minutes the root may stay below INFO. " +
        "Choose between $MIN_ROOT_REVERT_MINUTES and $MAX_ROOT_REVERT_MINUTES.",
), Refusal {
    override val arguments get() = mapOf("minutes" to minutes)
}

class LogFollowOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds between log level checks. " +
        "Choose between $MIN_LOG_FOLLOW_SECONDS and $MAX_LOG_FOLLOW_SECONDS.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}
