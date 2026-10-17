package io.mszymanski.orknux.server.obj

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

@Component
class ObjectExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is ObjectNameTakenException,
            is ObjectNameInvalidException,
            is ObjectPropertyInvalidException,
            is ObjectInUseException,
            is ObjectSchemaUnreadableException,
            -> ErrorType.BAD_REQUEST

            is ObjectNotFoundException -> ErrorType.NOT_FOUND

            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
