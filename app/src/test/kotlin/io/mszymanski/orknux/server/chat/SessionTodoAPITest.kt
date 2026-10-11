package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The session page's to-do panel: the list an agent keeps with its todo_* tools,
 * read back through the GraphQL the browser calls. Issue #405.
 *
 * And the notes panel beneath it, which reads `llmSession { notes }` - the query
 * the page already makes, and the same rows [NoteTools.recalled] hands back to
 * the agent, in the same order.
 *
 * Both are written here through the agent's own tools rather than into the
 * store, so what is read back is what an agent actually leaves behind.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionTodoAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val todos: TodoTools,
    @Autowired val notes: NoteTools,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var session: Long = 0

    @BeforeEach
    fun make() {
        // A workspace with no roles: an administrator sees it, and nobody else does.
        val workspaceId = requireNotNull(
            (workspaces.findByName("todo-api") ?: workspaces.save(Workspace(name = "todo-api"))).id,
        )
        session = recorder.open(workspaceId, "test", "todo-api-${System.nanoTime()}")
    }

    private fun call(name: String, arguments: String) {
        val answer = requireNotNull(todos.shed(session)).run(ToolCall("t", name, arguments))
        assertThat(answer).doesNotContain("\"error\"")
    }

    @Test
    fun `the list comes back in the agent's order, open and done alike, with notes`() {
        call(TodoTools.ADD, """{"text":"Read the issue"}""")
        call(TodoTools.ADD, """{"text":"Write the fix"}""")
        call(TodoTools.ADD, """{"text":"Run the suite"}""")
        call(TodoTools.REORDER, """{"order":[2,1]}""")
        call(TodoTools.COMPLETE, """{"id":1}""")
        call(TodoTools.NOTE, """{"id":3,"note":"Postgres and SQLite"}""")

        graphQlTester
            .document("query { sessionTodos(sessionId: $session) { id text note done } }")
            .execute()
            .path("sessionTodos[*].id").entityList(Int::class.java).containsExactly(2, 1, 3)
            .path("sessionTodos[*].text").entityList(String::class.java)
            .containsExactly("Write the fix", "Read the issue", "Run the suite")
            .path("sessionTodos[*].done").entityList(Boolean::class.java).containsExactly(false, true, false)
            .path("sessionTodos[0].note").valueIsNull()
            .path("sessionTodos[2].note").entity(String::class.java).isEqualTo("Postgres and SQLite")
    }

    @Test
    fun `a session nothing was planned in has an empty list`() {
        graphQlTester
            .document("query { sessionTodos(sessionId: $session) { id } }")
            .execute()
            .path("sessionTodos").entityList(Any::class.java).hasSize(0)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who cannot see the session's workspace is refused`() {
        call(TodoTools.ADD, """{"text":"A secret plan"}""")

        graphQlTester
            .document("query { sessionTodos(sessionId: $session) { text } }")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.extensions["code"]).isEqualTo("WorkspaceNotFound")
                })
            }
    }

    @Test
    fun `the notes an agent wrote come back oldest first, as the agent is handed them`() {
        val shed = requireNotNull(notes.shed(session, "planner"))
        shed.run(ToolCall("n1", NoteTools.NOTE, """{"note":"Steps 1-3 done"}"""))
        shed.run(ToolCall("n2", NoteTools.NOTE, """{"note":"The third test is the flaky one"}"""))

        graphQlTester
            .document("query { llmSession(id: $session) { notes { note writtenBy } } }")
            .execute()
            .path("llmSession.notes[*].note").entityList(String::class.java)
            .containsExactly("Steps 1-3 done", "The third test is the flaky one")
            .path("llmSession.notes[0].writtenBy").entity(String::class.java).isEqualTo("planner")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who cannot see the session's workspace is not shown its notes`() {
        requireNotNull(notes.shed(session, "planner")).run(ToolCall("n", NoteTools.NOTE, """{"note":"private"}"""))

        graphQlTester
            .document("query { llmSession(id: $session) { notes { note } } }")
            .execute()
            .path("llmSession").valueIsNull()
    }
}
