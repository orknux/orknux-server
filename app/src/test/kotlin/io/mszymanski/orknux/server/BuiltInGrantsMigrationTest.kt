package io.mszymanski.orknux.server

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

    /**
     * The version whose promise this is. V309 emptied every hidden list - every
     * built-in is offered since - so past it there is nothing of V303's left to
     * read, and `latest` is checked only for that.
     */
    private val theInversion = "303"

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

                migrate(postgres, target = theInversion)

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

                migrate(postgres, target = "latest")

                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
                    // V309: every built-in is offered, whatever V303 kept hidden.
                    assertThat(hidden(db, 900)).isEmpty()
                    assertThat(hidden(db, 901)).isEmpty()
                    assertThat(granted(db, 901)).containsExactly("jira_search")
                    /*
                     * find_connections, which is offered to every agent now
                     * rather than past a number of grants: every existing agent
                     * holds it as Always, as a new agent does - V302 marked the
                     * one with a ceiling, and V336 the one without, which V302
                     * gave no marks at all. Once each.
                     */
                    assertThat(required(db, 901)).containsOnlyOnce("find_connections")
                    assertThat(required(db, 900)).containsOnlyOnce("find_connections")
                    assertThat(positions(db, "agent_required_tool", 900))
                        .isEqualTo((0 until required(db, 900).size).toList())
                }
            }
    }

    /**
     * The list V303 turns round is V302's, draw_picture included - V302 only
     * marked that one, since it was a grant already. Both were once compared
     * with `BuiltInTools.GRANTED`; since V309 every built-in is offered, a new
     * one needs no row, and the two files are history the list has moved past.
     */
    @Test
    fun `the inversion turns round exactly what the grants migration wrote`() {
        fun namesIn(migration: String) = Regex("""UNION ALL SELECT \d+, '([a-z_0-9]+)'""")
            .findAll(javaClass.getResource("/db/migration/postgresql/$migration")!!.readText())
            .map { it.groupValues[1] }
            .toList()
        assertThat(namesIn("V303__built_ins_on_by_default.sql"))
            .containsExactlyInAnyOrderElementsOf(namesIn("V302__built_in_tool_grants.sql") + "draw_picture")
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
