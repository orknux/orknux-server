package io.mszymanski.orknux.server.flag

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * A flag asked for that is not there, or cleared twice: what was asked for,
 * not a failure of the server, said in English and as a code the interface
 * translates.
 */
@Component
class SessionFlagExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is SessionFlagNotFoundException -> ErrorType.NOT_FOUND
            is SessionFlagAlreadyClearedException -> ErrorType.BAD_REQUEST
            else -> return null
        }
        return refused(exception, errorType, environment)
    }
}
