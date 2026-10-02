package io.mszymanski.orknux.server.update

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager

/**
 * The launcher's reads on a database that has not met this release yet.
 *
 * The first start after upgrading to the release that brought server updates
 * runs the launcher before the server has migrated the schema. That used to log
 * "ERROR could not choose a release ... relation server_release does not exist"
 * on every installation's upgrade, and leave the marker that says the launcher
 * could not read the releases - both for a table that was simply not there yet.
 */
class JdbcReleaseStoreTest {

    @Test
    fun `a database without the release table has nothing chosen, and is not an error`() {
        val file = Files.createTempFile("orknux-launcher-", ".db")
        try {
            val url = "jdbc:sqlite:$file"
            DriverManager.getConnection(url).use { it.createStatement().execute("CREATE TABLE something_else (id INTEGER)") }

            val store = JdbcReleaseStore { DriverManager.getConnection(url) }

            assertThat(store.current()).isNull()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
