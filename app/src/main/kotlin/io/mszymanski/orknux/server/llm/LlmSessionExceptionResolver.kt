package io.mszymanski.orknux.server.llm

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * A transcript that is not there, said as such.
 *
 * Beside the code that raises it, the way the tracker's and the agent's are. A
 * session asked for by an id that names nothing is not a failure of the server:
 * a session is deleted with the workspace that held it, so a page opened from a
 * stale link needs to be told the conversation is gone rather than shown
 * something went wrong.
 */
@Component
class LlmSessionExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is LlmSessionNotFoundException -> ErrorType.NOT_FOUND
            // A key nobody can store, and a prefix with nothing after it. Both
            // are what was asked for rather than what went wrong.
            is LlmSessionKeyMissingException, is LlmSessionKeyTooLongException -> ErrorType.BAD_REQUEST
            // A scratchpad create, write or delete the service refused: a name
            // taken, a write over the byte budget, an inherited pad nobody here
            // may delete. What was asked for, not a failure. Issue #429.
            is ScratchpadRefusedException -> ErrorType.BAD_REQUEST
            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
