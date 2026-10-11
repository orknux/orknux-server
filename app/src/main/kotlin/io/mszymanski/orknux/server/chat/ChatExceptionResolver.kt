package io.mszymanski.orknux.server.chat

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * A provider that would not answer is the caller's to act on — pick another
 * model, fix the key — so it is a bad request rather than a server fault.
 */
@Component
class ChatExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is ChatTitleInvalidException,
            is ChatMessageEmptyException,
            is ChatModelNotChosenException,
            is ChatCannotBeToldMidTurnException,
            is ChatModelUnusableException,
            is ChatAgentUnusableException,
            is ChatAgentMissingException,
            is ChatLlmSessionUnusableException,
            is ChatDisabledException,
            is ChatNothingToRegenerateException,
            -> ErrorType.BAD_REQUEST

            is ChatSessionNotFoundException -> ErrorType.NOT_FOUND

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
