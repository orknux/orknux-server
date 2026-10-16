package io.mszymanski.orknux.server.database

import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.zip.CRC32

/**
 * Mends a SQLite database made by 1.0.4, whose baseline was edited.
 *
 * 1.0.4 added two columns to SQLite's `V1__baseline.sql` instead of in numbered
 * migrations. An installation already past V1 never reads the baseline again,
 * and Flyway refuses to start one whose applied V1 no longer matches its
 * checksum - so every SQLite installation that upgraded to 1.0.4 stopped at
 * start-up. The baseline is back as 1.0.3 had it, and the two columns arrive in
 * V40 and V41.
 *
 * That leaves the installations made fresh on 1.0.4: their V1 is the edited one
 * and they already have both columns, so V40 and V41 would fail on them, and
 * their recorded checksum is the edited file's. This finds them by their shape -
 * the column V40 adds, present before V40 has run - and puts them where an
 * upgrade from 1.0.3 is: the values in the two columns set aside, the columns
 * dropped for V40 and V41 to add, the checksum the restored baseline's, and the
 * values written back once the migrations have run.
 */
@Component
class SqliteBaselineMend : Callback {

    private val log = LoggerFactory.getLogger(javaClass)

    /** What was in the two columns, kept between validation and the end of the migration. */
    private var thinking: Map<Long, Boolean?> = emptyMap()
    private var said: Map<Long, String> = emptyMap()

    override fun supports(event: Event, context: Context?): Boolean =
        event == Event.BEFORE_VALIDATE || event == Event.AFTER_MIGRATE

    override fun canHandleInTransaction(event: Event, context: Context?): Boolean = true

    override fun getCallbackName(): String = "SqliteBaselineMend"

    override fun handle(event: Event, context: Context) {
        val db = context.connection
        if (!db.metaData.databaseProductName.contains("SQLite", ignoreCase = true)) return
        when (event) {
            Event.BEFORE_VALIDATE -> if (madeBy104(db)) mend(db)
            Event.AFTER_MIGRATE -> restore(db)
            else -> Unit
        }
    }

    private fun madeBy104(db: Connection): Boolean =
        hasTable(db, "flyway_schema_history") && applied(db, "1") && !applied(db, "40") &&
            hasColumn(db, "workspace", "voice_barge_in_thinking")

    /** A new file has no history yet, and nothing to mend. */
    private fun hasTable(db: Connection, table: String): Boolean =
        db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { query ->
            query.setString(1, table)
            query.executeQuery().use { it.next() }
        }

    private fun mend(db: Connection) {
        db.createStatement().use { sql ->
            thinking = buildMap {
                sql.executeQuery("SELECT id, voice_barge_in_thinking FROM workspace").use { rows ->
                    while (rows.next()) {
                        val held = rows.getObject(2)
                        if (held != null) put(rows.getLong(1), rows.getBoolean(2))
                    }
                }
            }
            said = buildMap {
                if (hasColumn(db, "execution_step", "agent_said")) {
                    sql.executeQuery("SELECT id, agent_said FROM execution_step WHERE agent_said IS NOT NULL").use { rows ->
                        while (rows.next()) put(rows.getLong(1), rows.getString(2))
                    }
                }
            }
            sql.execute("ALTER TABLE workspace DROP COLUMN voice_barge_in_thinking")
            if (hasColumn(db, "execution_step", "agent_said")) sql.execute("ALTER TABLE execution_step DROP COLUMN agent_said")
        }
        db.prepareStatement("UPDATE flyway_schema_history SET checksum = ? WHERE version = '1'").use { update ->
            update.setInt(1, checksum(BASELINE))
            update.executeUpdate()
        }
        log.warn(
            "This SQLite database was made by 1.0.4, whose baseline was edited; it is put where an upgrade " +
                "from 1.0.3 is ({} workspace setting(s) and {} step text(s) kept across).",
            thinking.size,
            said.size,
        )
    }

    private fun restore(db: Connection) {
        if (thinking.isEmpty() && said.isEmpty()) return
        db.prepareStatement("UPDATE workspace SET voice_barge_in_thinking = ? WHERE id = ?").use { update ->
            thinking.forEach { (id, value) ->
                if (value == null) update.setNull(1, java.sql.Types.BOOLEAN) else update.setBoolean(1, value)
                update.setLong(2, id)
                update.executeUpdate()
            }
        }
        db.prepareStatement("UPDATE execution_step SET agent_said = ? WHERE id = ?").use { update ->
            said.forEach { (id, value) ->
                update.setString(1, value)
                update.setLong(2, id)
                update.executeUpdate()
            }
        }
        thinking = emptyMap()
        said = emptyMap()
    }

    private fun applied(db: Connection, version: String): Boolean =
        db.prepareStatement("SELECT 1 FROM flyway_schema_history WHERE version = ? AND success = 1").use { query ->
            query.setString(1, version)
            query.executeQuery().use { it.next() }
        }

    private fun hasColumn(db: Connection, table: String, column: String): Boolean =
        db.createStatement().use { sql ->
            sql.executeQuery("PRAGMA table_info($table)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }.any { it == column }
            }
        }

    companion object {
        const val BASELINE = "db/migration/sqlite/V1__baseline.sql"

        /**
         * A migration's checksum as Flyway records it: CRC32 over its lines,
         * each without its line ending, the first without a byte-order mark.
         * `SqliteBaselineMendTest` holds this to what Flyway itself writes.
         */
        fun checksum(resource: String): Int {
            val crc = CRC32()
            ClassPathResource(resource).inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { at, line ->
                    val text = if (at == 0) line.removePrefix("﻿") else line
                    crc.update(text.toByteArray(Charsets.UTF_8))
                }
            }
            return crc.value.toInt()
        }
    }
}
