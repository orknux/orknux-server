package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.llm.LlmSessionNotFoundException
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.data.repository.findByIdOrNull
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
    private val sessions: LlmSessionRepository,
    private val access: WorkspaceAccess,
    private val todos: TodoTools,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun sessionTodos(@Argument sessionId: Long): List<SessionTodoView> {
        val session = sessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        return todos.items(sessionId).map { SessionTodoView(it.id, it.text, it.note, it.done) }
    }
}

data class SessionTodoView(
    val id: Int,
    val text: String,
    val note: String?,
    val done: Boolean,
)
