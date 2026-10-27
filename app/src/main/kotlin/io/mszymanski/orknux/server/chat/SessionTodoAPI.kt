package io.mszymanski.orknux.server.chat

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * A session's to-do list, read beside its transcript. Issue #405.
 *
 * Read-only: the list is the agent's plan, written with its own tools, and a
 * person editing it under the agent would be a plan the agent did not make.
 * The check is on the session's own workspace, as for its scratchpads, so naming
 * a session id is not a way into somebody else's workspace.
 */
@Controller
class SessionTodoAPI(
    /** Whose chat a session is; somebody else's is answered as not there. */
    private val visibility: io.mszymanski.orknux.server.llm.SessionVisibility,
    private val todos: TodoTools,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun sessionTodos(@Argument sessionId: Long): List<SessionTodoView> {
        visibility.requireReadable(sessionId)
        return todos.items(sessionId).map { SessionTodoView(it.id, it.text, it.note, it.done) }
    }
}

data class SessionTodoView(
    val id: Int,
    val text: String,
    val note: String?,
    val done: Boolean,
)
