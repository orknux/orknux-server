package io.mszymanski.orknux.server.database

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * V313 on an installation whose date plugin had a function returning an object.
 *
 * What stopped a production server on upgrade to 0.9.9: V313 cleared the
 * function's object and left its type OBJECT, which the return-object check
 * refuses. Migrated to the version before, given that row, and migrated the
 * rest of the way - once without [DatePluginRetype], to show this is the fault,
 * and once with it. Its own database, for the reason RetryBackoffMigrationTest
 * gives.
 */
class DatePluginRetypeTest {

    @Test
    fun `V313 fails on an object-returning date function, and runs with the retype`() {
        assertThatThrownBy { upgrade(withRetype = false) }
            .hasStackTraceContaining("ck_workflow_function_return_object")

        val after = upgrade(withRetype = true)
        assertThat(after).isEqualTo("EMBEDDED MAP null")
    }

    /** Upgrades a database holding the row, and answers how the function ended up. */
    private fun upgrade(withRetype: Boolean): String =
        PostgreSQLContainer("postgres:18")
            .withDatabaseName("orknux")
            .withUsername("orknux")
            .withPassword("orknux")
            .use { postgres ->
                postgres.start()
                val flyway = { target: String ->
                    Flyway.configure()
                        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                        .locations("classpath:db/migration/postgresql")
                        .target(target)
                        .apply { if (withRetype) callbacks(DatePluginRetype()) }
                        .load()
                }
                flyway("312").migrate()
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
                    db.createStatement().use { sql ->
                        sql.execute(
                            """
                            INSERT INTO plugin (id, plugin_key, name, api_version, size_bytes, filename, source, sha256)
                            VALUES (900, 'date', 'Date', '1', 1, 'date.js', '', 'x')
                            """,
                        )
                        sql.execute(
                            """
                            INSERT INTO workflow_object (id, plugin_id, name, created_at, created_by, last_modified_at, last_modified_by)
                            VALUES (901, 900, 'DateDescription', now(), 'plugin', now(), 'plugin')
                            """,
                        )
                        sql.execute(
                            """
                            INSERT INTO workflow_function (id, scope, plugin_id, name, source, return_type, return_object_id,
                                                           last_modified_at, last_modified_by)
                            VALUES (902, 'PLUGIN', 900, 'date_describe', '', 'OBJECT', 901, now(), 'plugin date')
                            """,
                        )
                    }
                }
                flyway("latest").migrate()
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
                    db.createStatement().use { sql ->
                        sql.executeQuery("SELECT scope, return_type, return_object_id FROM workflow_function WHERE id = 902").use {
                            it.next()
                            "${it.getString(1)} ${it.getString(2)} ${it.getString(3)}"
                        }
                    }
                }
            }
}
