package io.mszymanski.orknux.server.database

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.callback.Callback
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * SQLite installations across the 1.0.4 baseline edit.
 *
 * 1.0.4 changed SQLite's `V1__baseline.sql`, so an installation past V1 refused
 * to start on it: Flyway will not run a history whose applied V1 no longer
 * matches. The baseline is back as 1.0.3 had it and the columns arrive in
 * numbered migrations; [SqliteBaselineMend] puts a database made fresh on 1.0.4
 * where an upgrade from 1.0.3 is. Each case is a real file, migrated by Flyway
 * the way the application migrates it.
 */
class SqliteBaselineMendTest {

    private val current: Path = ClassPathResource("db/migration/sqlite").file.toPath()

    /** This release's SQLite migrations up to [last], with [baseline] as V1, in a directory of their own. */
    private fun history(last: Int, baseline: Path = current.resolve("V1__baseline.sql")): Path {
        val dir = Files.createTempDirectory("orknux-sqlite-history-")
        Files.list(current).use { files ->
            files.filter { file ->
                val version = Regex("^V(\\d+)__").find(file.fileName.toString())?.groupValues?.get(1)?.toInt()
                version != null && version in 2..last
            }.forEach { Files.copy(it, dir.resolve(it.fileName)) }
        }
        Files.copy(baseline, dir.resolve("V1__baseline.sql"))
        return dir
    }

    private val shipped104: Path = ClassPathResource("sqlite-1.0.4/V1__baseline.sql").file.toPath()

    private fun flyway(file: Path, location: String, vararg callbacks: Callback): Flyway =
        Flyway.configure()
            .dataSource("jdbc:sqlite:$file", null, null)
            .locations(location)
            .callbacks(*callbacks)
            .load()

    private fun database(): Path = Files.createTempFile("orknux-mend-", ".db")

    private fun columns(file: Path, table: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().executeQuery("PRAGMA table_info($table)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }.toList()
            }
        }

    @Test
    fun `an installation from 1_0_3 migrates to this release`() {
        val file = database()
        flyway(file, "filesystem:${history(39)}").migrate()

        flyway(file, "classpath:db/migration/sqlite", SqliteBaselineMend()).migrate()

        assertThat(columns(file, "workspace")).contains("voice_barge_in_thinking", "widget_timer_sound")
        assertThat(columns(file, "execution_step")).contains("agent_said")
    }

    /** What 1.0.4 did to one: the edited baseline is refused over the applied one. */
    @Test
    fun `1_0_4 as shipped could not start an installation from 1_0_3`() {
        val file = database()
        flyway(file, "filesystem:${history(39)}").migrate()

        assertThatThrownBy { flyway(file, "filesystem:${history(39, shipped104)}").migrate() }
            .hasMessageContaining("checksum")
    }

    @Test
    fun `an installation made on 1_0_4 is mended, and keeps what it had`() {
        val file = database()
        flyway(file, "filesystem:${history(39, shipped104)}").migrate()
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().execute("INSERT INTO workspace (name, voice_barge_in_thinking) VALUES ('kept', 0)")
        }

        // Without the mend, this release cannot run on it either.
        assertThatThrownBy { flyway(file, "classpath:db/migration/sqlite").migrate() }.hasMessageContaining("checksum")

        flyway(file, "classpath:db/migration/sqlite", SqliteBaselineMend()).migrate()

        assertThat(columns(file, "workspace")).contains("voice_barge_in_thinking", "widget_timer_sound")
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().executeQuery("SELECT voice_barge_in_thinking FROM workspace WHERE name = 'kept'").use { row ->
                assertThat(row.next()).isTrue()
                assertThat(row.getObject(1)).describedAs("the setting it had was set aside and put back").isNotNull()
                assertThat(row.getBoolean(1)).isFalse()
            }
        }
        // And it validates from now on, mend or no mend.
        flyway(file, "classpath:db/migration/sqlite").validate()
    }

    @Test
    fun `a new installation migrates with the mend in place, which finds nothing to do`() {
        val file = Files.createTempDirectory("orknux-new-").resolve("new.db")
        flyway(file, "classpath:db/migration/sqlite", SqliteBaselineMend()).migrate()
        assertThat(columns(file, "workspace")).contains("voice_barge_in_thinking", "widget_timer_sound")
    }

    /**
     * The baseline is frozen: an installation past V1 refuses to start once it
     * changes. A new column is the next numbered migration under sqlite/, never
     * an edit here - which is the mistake this file exists to clean up after.
     */
    @Test
    fun `the baseline is the one every SQLite installation already ran`() {
        assertThat(SqliteBaselineMend.checksum(SqliteBaselineMend.BASELINE))
            .describedAs("V1__baseline.sql was edited; put the change in the next numbered SQLite migration instead")
            .isEqualTo(-403350891)
    }

    @Test
    fun `the checksum the mend writes is the one Flyway records`() {
        val file = database()
        flyway(file, "classpath:db/migration/sqlite").migrate()
        val recorded = DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().executeQuery("SELECT checksum FROM flyway_schema_history WHERE version = '1'").use {
                it.next()
                it.getInt(1)
            }
        }
        assertThat(SqliteBaselineMend.checksum(SqliteBaselineMend.BASELINE)).isEqualTo(recorded)
    }
}
