package io.mszymanski.orknux.server.update

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite's V12 builds `server_release` again to widen its CHECK, #589 - on an
 * installation that already holds jars. The suite's SQLite run starts from an
 * empty file, where a rebuild that dropped every stored part would pass just
 * the same; this runs V11 and V12 over rows that exist, with foreign keys on as
 * the application has them, and asks whether the jars are still there and
 * still tied to their releases.
 */
class SqliteReleaseMigrationTest {

    private fun Connection.run(resource: String) {
        val script = ClassPathResource(resource).inputStream.reader().readText()
        val statements = script.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(';').map { it.trim() }.filter { it.isNotEmpty() }
        createStatement().use { statement -> statements.forEach(statement::execute) }
    }

    @Test
    fun `the rebuild keeps every stored jar, its parts and the cascade between them`() {
        val file = Files.createTempFile("orknux-v12-", ".db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            db.run("db/migration/sqlite/V11__server_release.sql")
            db.createStatement().use { s ->
                s.execute(
                    """
                    INSERT INTO server_release (id, version, sha256, size, schema_version, schema_floor, source, state,
                                                stored_at, stored_by)
                    VALUES (7, '0.9.9.8', 'abc', 3, 333, 333, 'UPLOAD', 'ACTIVE', CURRENT_TIMESTAMP, 'alice')
                    """.trimIndent(),
                )
                s.execute("INSERT INTO server_release_part (release_id, part, bytes) VALUES (7, 0, x'0102'), (7, 1, x'03')")
            }

            db.run("db/migration/sqlite/V12__server_release_from_url.sql")

            db.createStatement().use { s ->
                s.executeQuery("SELECT version, state, source_url FROM server_release WHERE id = 7").use { row ->
                    assertThat(row.next()).isTrue()
                    assertThat(row.getString(1)).isEqualTo("0.9.9.8")
                    assertThat(row.getString(2)).isEqualTo("ACTIVE")
                    assertThat(row.getString(3)).isNull()
                }
                s.executeQuery("SELECT COUNT(*) FROM server_release_part WHERE release_id = 7").use { row ->
                    row.next()
                    assertThat(row.getInt(1)).isEqualTo(2)
                }
                // The new source is allowed, and the parts still follow their release out.
                s.execute(
                    """
                    INSERT INTO server_release (version, sha256, size, schema_version, schema_floor, source, source_url, state,
                                                stored_at, stored_by)
                    VALUES ('0.9.9.9', 'def', 1, 334, 333, 'URL', 'https://artifactory.example/orknux/x.jar', 'STORED',
                            CURRENT_TIMESTAMP, 'alice')
                    """.trimIndent(),
                )
                s.execute("DELETE FROM server_release WHERE id = 7")
                s.executeQuery("SELECT COUNT(*) FROM server_release_part").use { row ->
                    row.next()
                    assertThat(row.getInt(1)).isEqualTo(0)
                }
                s.executeQuery("SELECT sql FROM sqlite_master WHERE name = 'server_release_part'").use { row ->
                    row.next()
                    assertThat(row.getString(1)).contains("REFERENCES \"server_release\"").doesNotContain("server_release_new")
                }
            }
        }
        Files.deleteIfExists(file)
    }
}
