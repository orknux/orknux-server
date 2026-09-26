package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper

/**
 * An agent planning its work as a to-do list, over one session. Issue #405.
 *
 * This shipped as a plugin an installation had to load; it is a general "plan
 * work before starting it" capability and is a built-in now, beside notes and
 * scratchpads, keyed on the session and kept in the session store. So these are
 * about the five verbs an agent that used the plugin still calls, and about the
 * list being put back into a turn so the plan is in front of the model.
 *
 * Makes its own workspace and session.
 */
@SpringBootTest
class TodoToolsTest(
    @Autowired val todos: TodoTools,
    @Autowired val sessions: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var session: Long = 0

    @BeforeEach
    fun make() {
        val workspaceId = requireNotNull(
            (workspaces.findByName("todos") ?: workspaces.save(Workspace(name = "todos"))).id,
        )
        session = sessions.open(workspaceId, "test", "todos-${System.nanoTime()}")
    }

    private fun run(name: String, args: String): String =
        requireNotNull(todos.shed(session)).run(ToolCall("1", name, args))

    private fun add(text: String) = run(TodoTools.ADD, """{"text":"$text"}""")

    /** The `todos` array of any tool result, as plain maps. */
    private fun todosIn(result: String): List<Map<String, Any?>> {
        val tree = mapper.readValue(result, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        return (tree["todos"] as List<Map<String, Any?>>)
    }

    private fun listed(): List<Map<String, Any?>> = todosIn(run(TodoTools.LIST, "{}"))
    private fun texts(): List<String> = listed().map { it["text"] as String }
    private fun ids(): List<Int> = listed().map { (it["id"] as Number).toInt() }

    /* ------------------------------------------- where it is offered ------- */

    @Test
    fun `a turn with no session to keep a list in is not offered the tool`() {
        assertThat(todos.shed(null)).isNull()
    }

    @Test
    fun `a turn with one is offered five verbs`() {
        val specs = requireNotNull(todos.shed(session)).specs()
        assertThat(specs.map { it.name }).containsExactlyInAnyOrder(
            TodoTools.ADD, TodoTools.LIST, TodoTools.REORDER, TodoTools.NOTE, TodoTools.COMPLETE,
        )
    }

    /* ------------------------------------------- what it keeps ------------- */

    @Test
    fun `an item added comes back on the list, and is put back into the turn`() {
        add("Build the parser")
        add("Wire it up")

        assertThat(texts()).containsExactly("Build the parser", "Wire it up")

        // The plan is put in front of the model each turn, open items and all.
        val recalled = todos.recalled(session)
        assertThat(recalled).contains("Build the parser")
        assertThat(recalled).contains("Wire it up")
    }

    @Test
    fun `a completed item drops out of the recalled plan and is counted`() {
        add("First")
        add("Second")
        val first = ids().first()

        run(TodoTools.COMPLETE, """{"id":$first}""")

        val recalled = todos.recalled(session)
        assertThat(recalled).doesNotContain("First")
        assertThat(recalled).contains("Second")
        assertThat(recalled).contains("1 already done")
        // Still on the list, marked done, for a `list` that asks for everything.
        assertThat(listed().single { (it["id"] as Number).toInt() == first }["done"]).isEqualTo(true)
    }

    @Test
    fun `a note is attached to the item it names`() {
        add("Investigate the flake")
        val id = ids().single()

        run(TodoTools.NOTE, """{"id":$id,"note":"only on release builds"}""")

        assertThat(listed().single()["note"]).isEqualTo("only on release builds")
        assertThat(todos.recalled(session)).contains("only on release builds")
    }

    @Test
    fun `reorder puts the named items first, in the order given`() {
        add("A")
        add("B")
        add("C")
        val order = ids() // A, B, C

        run(TodoTools.REORDER, """{"order":[${order[2]},${order[0]}]}""")

        // C, A first as named; B keeps its place after them.
        assertThat(texts()).containsExactly("C", "A", "B")
    }

    @Test
    fun `reorder understands the numbers as a string too`() {
        add("A")
        add("B")
        val order = ids()

        run(TodoTools.REORDER, """{"order":"${order[1]}, ${order[0]}"}""")

        assertThat(texts()).containsExactly("B", "A")
    }

    /* ------------------------------------------- what it refuses ----------- */

    @Test
    fun `completing an item that is not there is refused, not silent`() {
        add("Only item")
        val answer = mapper.readTree(run(TodoTools.COMPLETE, """{"id":999}"""))
        assertThat(answer.path("error").stringValue()).contains("999")
    }

    @Test
    fun `numbers are not reused as items are completed`() {
        add("One")
        val first = ids().single()
        run(TodoTools.COMPLETE, """{"id":$first}""")
        add("Two")

        // The second item gets a fresh number, not the first's back.
        val numbers = ids()
        assertThat(numbers).doesNotHaveDuplicates()
        assertThat(numbers).hasSize(2)
    }

    @Test
    fun `an empty list puts nothing into the turn`() {
        assertThat(todos.recalled(session)).isEmpty()
    }
}
