package io.mszymanski.orknux.server.tin

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/** What the tins refuse, as GraphQL errors with a code a client can translate. */
@Component
class TinExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is TinNameTakenException,
            is TinNameInvalidException,
            is TinAddressInvalidException,
            is LocalTinNotRemovableException,
            is LocalTinNotEditableException,
            is TinLabelRequiredException,
            is LocalTinRuntimeUnknownException,
            is LocalTinMaxSessionsOutOfRangeException,
            is LocalTinTtlOutOfRangeException,
            is LocalTinIdleTimeoutOutOfRangeException,
            is TinCommandWaitOutOfRangeException,
            is TinCommandTimeoutOutOfRangeException,
            is TinOutputBytesOutOfRangeException,
            is TinOutputShownOutOfRangeException,
            is LocalTinOutputKeptOutOfRangeException,
            is TinRequestTimeoutOutOfRangeException,
            -> ErrorType.BAD_REQUEST

            is TinNotFoundException -> ErrorType.NOT_FOUND

            // A tin that will not answer is not the caller's mistake, and calling
            // it a bad request would have somebody checking what they typed
            // instead of checking the tin.
            is TinUnreachableException -> ErrorType.INTERNAL_ERROR

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
