package io.mszymanski.orknux.server.diagram

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

@Component
class DiagramExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is DiagramNotFoundException,
            is DiagramCatalogNotFoundException,
            -> ErrorType.NOT_FOUND
            is DiagramNameInvalidException,
            is DiagramCatalogNameInvalidException,
            is DiagramCatalogNameTakenException,
            is DiagramCatalogNotEmptyException,
            is DiagramDocumentInvalidException,
            is DiagramNotesTooLongException,
            is DiagramTooLargeException,
            is DiagramChangedException,
            is DiagramReadOnlyException,
            is DiagramAnswerFailedException,
            -> ErrorType.BAD_REQUEST
            else -> return null
        }
        return refused(exception, errorType, environment)
    }
}
