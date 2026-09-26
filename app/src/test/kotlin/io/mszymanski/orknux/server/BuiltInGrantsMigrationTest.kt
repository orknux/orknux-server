package io.mszymanski.orknux.server

import io.mszymanski.orknux.server.chat.BuiltInTools
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * What an agent written before the built-ins were grants holds afterwards.
 * Issues #444, #455.
 *
 * The promise of that migration is that nothing changes for an agent that
 * exists: every built-in it was being handed is now a name on its list, the
 * three that had a switch follow the switch, and an agent with a ceiling has
 * them marked Always so nothing it carried is now found. It is a promise about
 * rows in columns the code can no longer name, so nothing else in the suite can
 * check it - this migrates to the version before, writes the rows an
 * installation on 0.9.9 actually has, and migrates the rest of the way. Its
 * own database, for the reason RetryBackoffMigrationTest gives.
 */
class BuiltInGrantsMigrationTest {

    /** The last version with the three boolean columns. */
    private val beforeTheList = "301"

    @Test
    fun `an existing agent keeps exactly what it was being handed`() {
        PostgreSQLContainer("postgres:18")
            .withDatabaseName("orknux")
            .withUsername("orknux")
            .withPassword("orknux")
            .use { postgres ->
                postgres.start()

                migrate(postgres, target = beforeTheList)
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
                    writeTheOldRows(db)
                }

                migrate(postgres, target = "latest")

                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
                    /*
                     * V302 put every built-in it was handed on its grant list;
                     * V303 turned that round, so what is stored now is what it
                     * does *not* hold - and the answer for this agent is the
                     * same answer, said the other way. Issue #455.
                     */
                    val plainHidden = hidden(db, 900)
                    assertThat(plainHidden)
                        .doesNotContain("note_to_self", "current_time", "scratchpad_write", "save_artifact", "ask_agent")
                    assertThat(plainHidden).contains("finish_answer")
                    assertThat(plainHidden).doesNotContain("picture_link")
                    // draw_picture was a grant before the list and this agent never had it.
                    assertThat(plainHidden).contains("draw_picture")
                    // No built-in is a grant any more, and this agent had nothing else.
                    assertThat(granted(db, 900)).isEmpty()
                    // No ceiling, so no marks: without one nothing is ever dropped.
                    assertThat(required(db, 900)).isEmpty()

                    // The workspace's own tool stays, alone and at nought.
                    assertThat(granted(db, 901)).containsExactly("jira_search")
                    val cappedHidden = hidden(db, 901)
                    assertThat(cappedHidden).doesNotContain("finish_answer", "note_to_self", "draw_picture")
                    // Saving was off, so the three that grant depended on are hidden.
                    assertThat(cappedHidden).contains("save_artifact", "base64_encode", "base64_decode")
                    assertThat(cappedHidden).doesNotHaveDuplicates()
                    // Under a ceiling every built-in it now holds is Always, after the
                    // one it had marked already - draw_picture included, which was a
                    // grant before and was carried all the same.
                    val marked = required(db, 901)
                    assertThat(marked.first()).isEqualTo("jira_search")
                    assertThat(marked).contains("draw_picture", "finish_answer", "note_to_self", "current_time")
                    assertThat(marked).doesNotContain("save_artifact")
                    assertThat(marked).doesNotHaveDuplicates()
                    // The positions are contiguous from nought, which is what an
                    // ordered collection column requires to load at all.
                    assertThat(positions(db, "agent_granted_tool", 901)).isEqualTo(listOf(0))
                    assertThat(positions(db, "agent_hidden_tool", 901)).isEqualTo((0 until cappedHidden.size).toList())
                    assertThat(positions(db, "agent_required_tool", 901)).isEqualTo((0 until marked.size).toList())

                    // And the columns are gone: the list says what they said.
                    assertThat(columns(db)).doesNotContain("artifact_access", "finish_access", "picture_link_access")
                }
            }
    }

    /** The list the migration writes is the list the code declares, name for name. */
    @Test
    fun `the migration names every grant-governed built-in the code knows`() {
        val inThisFile = Regex("UNION ALL SELECT \\d+, '([a-z_0-9]+)'")
            .findAll(javaClass.getResource("/db/migration/postgresql/V302__built_in_tool_grants.sql")!!.readText())
            .map { it.groupValues[1] }
            .toList() + "note_to_self"
        // draw_picture was a grant already and is not inserted, only marked.
        assertThat(inThisFile).containsExactlyInAnyOrderElementsOf(BuiltInTools.GRANTED - "draw_picture")
    }

    /** And the list V303 turns round is the whole of it, draw_picture included. */
    @Test
    fun `the inversion names every grant-governed built-in the code knows`() {
        val inThisFile = Regex("""UNION ALL SELECT \d+, '([a-z_0-9]+)'""")
            .findAll(javaClass.getResource("/db/migration/postgresql/V303__built_ins_on_by_default.sql")!!.readText())
            .map { it.groupValues[1] }
            .toList() + "note_to_self"
        assertThat(inThisFile).containsExactlyInAnyOrderElementsOf(BuiltInTools.GRANTED)
    }

    private fun migrate(postgres: PostgreSQLContainer<*>, target: String) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration/postgresql")
            .target(target)
            .load()
            .migrate()
    }

    /**
     * Two agents as 0.9.9 keeps them: one plain, with finishing switched off;
     * one with a ceiling, a tool of its own already marked Always, the drawing
     * granted, and saving switched off.
     */
    private fun writeTheOldRows(db: Connection) {
        db.createStatement().use { sql ->
            sql.execute("INSERT INTO workspace (id, name) VALUES (900, 'Support')")
            sql.execute(
                """
                INSERT INTO agent (id, workspace_id, name, type, enabled, orknux_access, shell_access,
                                   artifact_access, finish_access, picture_link_access, max_tools,
                                   last_modified_at, last_modified_by)
                VALUES (900, 900, 'Responder', 'LLM', true, false, false, true, false, true, NULL, now(), 'alice'),
                       (901, 900, 'Triager', 'LLM', true, false, false, false, true, true, 10, now(), 'alice')
                """,
            )
            sql.execute("INSERT INTO agent_granted_tool (agent_id, position, name) VALUES (901, 0, 'jira_search'), (901, 1, 'draw_picture')")
            sql.execute("INSERT INTO agent_required_tool (agent_id, position, name) VALUES (901, 0, 'jira_search')")
        }
    }

    private fun granted(db: Connection, agent: Long): List<String> = names(db, "agent_granted_tool", agent)

    /** What V303 stores instead: the built-ins this agent may not use. Issue #455. */
    private fun hidden(db: Connection, agent: Long): List<String> = names(db, "agent_hidden_tool", agent)

    private fun required(db: Connection, agent: Long): List<String> = names(db, "agent_required_tool", agent)

    private fun names(db: Connection, table: String, agent: Long): List<String> = db.createStatement().use { sql ->
        sql.executeQuery("SELECT name FROM $table WHERE agent_id = $agent ORDER BY position").use {
            buildList { while (it.next()) add(it.getString("name")) }
        }
    }

    private fun positions(db: Connection, table: String, agent: Long): List<Int> = db.createStatement().use { sql ->
        sql.executeQuery("SELECT position FROM $table WHERE agent_id = $agent ORDER BY position").use {
            buildList { while (it.next()) add(it.getInt("position")) }
        }
    }

    private fun columns(db: Connection): List<String> = db.createStatement().use { sql ->
        sql.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = 'agent'").use {
            buildList { while (it.next()) add(it.getString("column_name")) }
        }
    }
}
