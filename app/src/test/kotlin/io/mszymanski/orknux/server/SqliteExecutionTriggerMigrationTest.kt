package io.mszymanski.orknux.server

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * The SQLite half of V334: a table rebuilt to widen a CHECK, on a database that
 * already holds runs.
 *
 * SQLite cannot alter a CHECK, so V12 copies `workflow_execution` into a new
 * table and drops the old one - and with foreign keys on, which is how every
 * connection here opens, that drop deletes every row first and the cascade
 * takes each run's steps and log with it. SqliteSchemaTest would never see it:
 * it migrates an empty file, where there is nothing to lose. So this writes the
 * rows an existing installation has, migrates over them with foreign keys on,
 * and counts what is left. Its own file, for the reason SqliteSchemaTest gives.
 */
class SqliteExecutionTriggerMigrationTest {

    private val file: Path = Path.of("target", "execution-trigger-migration.db")

    /** As the application opens it: SqliteConfig turns foreign keys on for every connection. */
    private val url = "jdbc:sqlite:$file?foreign_keys=true"

    @Test
    fun `an existing run keeps its log and its id counter, and a connection run can be written`() {
        Files.deleteIfExists(file)

        migrate(target = "11")
        connect().use { db ->
            db.createStatement().use { sql ->
                sql.execute(
                    """
                    INSERT INTO workflow_execution (id, workspace_id, workflow_id, workflow_name, status, trigger_type, started_at)
                    VALUES (7, 1, 1, 'Incident Response', 'COMPLETED', 'WEBHOOK', '2026-09-30T10:00:00Z'),
                           (9, 1, 1, 'Incident Response', 'COMPLETED', 'MANUAL', '2026-09-30T11:00:00Z')
                    """,
                )
                sql.execute("UPDATE workflow_execution SET started_from = 7 WHERE id = 9")
                sql.execute(
                    """
                    INSERT INTO execution_log (execution_id, logged_at, level, message, sequence_no)
                    VALUES (7, '2026-09-30T10:00:01Z', 'INFO', 'Started', 1)
                    """,
                )
                // A run deleted off the top: its id must not be handed out again.
                sql.execute(
                    """
                    INSERT INTO workflow_execution (id, workspace_id, workflow_id, workflow_name, status, trigger_type, started_at)
                    VALUES (12, 1, 1, 'Incident Response', 'FAILED', 'API', '2026-09-30T12:00:00Z')
                    """,
                )
                sql.execute("DELETE FROM workflow_execution WHERE id = 12")
            }
        }

        migrate(target = "latest")

        connect().use { db ->
            assertThat(count(db, "SELECT count(*) FROM workflow_execution")).isEqualTo(2)
            assertThat(count(db, "SELECT count(*) FROM execution_log WHERE execution_id = 7")).isEqualTo(1)
            assertThat(count(db, "SELECT started_from FROM workflow_execution WHERE id = 9")).isEqualTo(7)

            db.createStatement().use { sql ->
                sql.execute(
                    """
                    INSERT INTO workflow_execution (workspace_id, workflow_id, workflow_name, status, trigger_type, started_at)
                    VALUES (1, 1, 'Incident Response', 'RUNNING', 'CONNECTION', '2026-10-01T09:00:00Z')
                    """,
                )
            }
            assertThat(count(db, "SELECT max(id) FROM workflow_execution")).isEqualTo(13)

            // The constraint is still a constraint, and the cascade still a cascade.
            assertThatThrownBy {
                db.createStatement().use {
                    it.execute(
                        """
                        INSERT INTO workflow_execution (workspace_id, workflow_id, workflow_name, status, trigger_type, started_at)
                        VALUES (1, 1, 'x', 'RUNNING', 'CARRIER_PIGEON', '2026-10-01T09:00:00Z')
                        """,
                    )
                }
            }.hasMessageContaining("ck_execution_trigger")
            db.createStatement().use { it.execute("DELETE FROM workflow_execution WHERE id = 7") }
            assertThat(count(db, "SELECT count(*) FROM execution_log")).isZero()
            assertThat(count(db, "SELECT count(*) FROM workflow_execution WHERE id = 9 AND started_from IS NULL")).isEqualTo(1)
            assertThat(count(db, "SELECT count(*) FROM pragma_foreign_key_check")).isZero()
        }
    }

    private fun migrate(target: String) {
        Flyway.configure()
            .dataSource(url, "", "")
            .locations("classpath:db/migration/sqlite")
            .target(target)
            .load()
            .migrate()
    }

    private fun connect(): Connection = DriverManager.getConnection(url)

    private fun count(db: Connection, query: String): Long =
        db.createStatement().use { sql -> sql.executeQuery(query).use { it.next(); it.getLong(1) } }
}
