package io.mszymanski.orknux.server.memory

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/** A duplicate name or an empty field is the caller's to fix; a missing id is a 404. */
@Component
class MemoryExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is MemoryCatalogNameTakenException,
            is MemoryCatalogNameInvalidException,
            is MemoryCatalogInUseException,
            is MemoryCatalogNotRemovableException,
            is MemoryTitleInvalidException,
            is MemoryContentInvalidException,
            -> ErrorType.BAD_REQUEST

            is MemoryCatalogNotFoundException,
            is MemoryNotFoundException,
            -> ErrorType.NOT_FOUND

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
