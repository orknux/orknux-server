package io.mszymanski.orknux.server.widget

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.chat.ChatOwnership
import io.mszymanski.orknux.server.chat.ChatSession
import io.mszymanski.orknux.server.chat.ChatSessionNotFoundException
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/**
 * A chat's widgets for the person who owns the chat: read, paused, resumed,
 * reset, lapped and removed from the page. Somebody else's chat reads as one
 * that is not there, as everywhere else chats are concerned.
 */
@Controller
class WidgetAPI(
    private val chats: ChatSessionRepository,
    private val ownership: ChatOwnership,
    private val widgets: Widgets,
    private val widgetRows: ChatWidgetRepository,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun chatWidgets(@Argument chatId: Long): List<WidgetView> = widgets.list(requireNotNull(owned(chatId).id))

    @MutationMapping
    fun actOnChatWidget(@Argument id: Long, @Argument action: String): WidgetView {
        val chat = chatOf(id)
        val done = widgets.act(chat, id, action.trim().lowercase())
        auditRecorder.record(chat.workspaceId, WorkspaceAuditCategory.CHAT, "Widget '${done.label}' in chat ${chat.id}: ${action.lowercase()}")
        return done
    }

    @MutationMapping
    fun removeChatWidget(@Argument id: Long): Boolean {
        val chat = chatOf(id)
        val gone = widgets.remove(chat, id)
        auditRecorder.record(chat.workspaceId, WorkspaceAuditCategory.CHAT, "Widget '${gone.label}' removed from chat ${chat.id}")
        return true
    }

    private fun owned(chatId: Long): ChatSession {
        val chat = chats.findByIdOrNull(chatId) ?: throw ChatSessionNotFoundException(chatId)
        ownership.requireOwn(chat)
        return chat
    }

    /** The chat a widget is in, if the caller owns it; another's widget reads as one that is not there. */
    private fun chatOf(widgetId: Long): ChatSession {
        val widget = widgetRows.findByIdOrNull(widgetId) ?: throw ChatWidgetNotFoundException(widgetId)
        val chat = chats.findByIdOrNull(widget.chatSessionId) ?: throw ChatWidgetNotFoundException(widgetId)
        if (!ownership.owns(chat)) throw ChatWidgetNotFoundException(widgetId)
        return chat
    }
}

@Component
class WidgetExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val type = when (exception) {
            is WidgetRefusedException -> ErrorType.BAD_REQUEST
            is ChatWidgetNotFoundException -> ErrorType.NOT_FOUND
            else -> return null
        }
        return refused(exception, type, environment)
    }
}
