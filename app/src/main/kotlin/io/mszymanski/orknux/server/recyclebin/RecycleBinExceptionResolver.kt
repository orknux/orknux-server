package io.mszymanski.orknux.server.recyclebin

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * An entry that is not there is a 404; a workflow that cannot be put back is
 * the caller's to hear about. A name taken on restore is answered by the
 * resolver of the kind whose exception it is.
 */
@Component
class RecycleBinExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is RecycleBinWorkflowGoneException -> ErrorType.BAD_REQUEST
            is RecycleBinReferenceGoneException -> ErrorType.BAD_REQUEST
            is RecycleBinEntryNotFoundException -> ErrorType.NOT_FOUND
            else -> return null
        }
        return refused(exception, errorType, environment)
    }
}
