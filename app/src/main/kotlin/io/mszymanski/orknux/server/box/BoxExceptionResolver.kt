package io.mszymanski.orknux.server.box

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/** What the boxes refuse, as GraphQL errors with a code a client can translate. */
@Component
class BoxExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is BoxNameTakenException,
            is BoxNameInvalidException,
            is BoxAddressInvalidException,
            is LocalBoxNotRemovableException,
            is LocalBoxNotEditableException,
            is BoxLabelRequiredException,
            is LocalBoxRuntimeUnknownException,
            is LocalBoxMaxSessionsOutOfRangeException,
            is BoxMaxPortsOutOfRangeException,
            is BoxPortCheckOutOfRangeException,
            is BoxPortCheckWaitOutOfRangeException,
            is BoxPortSessionClosedException,
            is LocalBoxTtlOutOfRangeException,
            is LocalBoxIdleTimeoutOutOfRangeException,
            is BoxCommandWaitOutOfRangeException,
            is BoxCommandTimeoutOutOfRangeException,
            is BoxOutputBytesOutOfRangeException,
            is BoxOutputShownOutOfRangeException,
            is LocalBoxOutputKeptOutOfRangeException,
            is BoxRequestTimeoutOutOfRangeException,
            is BoxEditUndoStepsOutOfRangeException,
            -> ErrorType.BAD_REQUEST

            is BoxNotFoundException, is BoxPortNotFoundException -> ErrorType.NOT_FOUND

            // A box that will not answer is not the caller's mistake, and calling
            // it a bad request would have somebody checking what they typed
            // instead of checking the box.
            is BoxUnreachableException, is BoxPortUnreachableException -> ErrorType.INTERNAL_ERROR

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
