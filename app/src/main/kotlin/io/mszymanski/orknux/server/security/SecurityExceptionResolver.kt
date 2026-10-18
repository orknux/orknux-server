package io.mszymanski.orknux.server.security

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * What the GraphQL side answers when access says no.
 *
 * A workspace the caller cannot see is answered as one that is not there,
 * which is what the REST side has always done with the same exception and what
 * the message now says. Needing the administrator role is a different answer
 * and stays forbidden: it says nothing about what exists, only about who is
 * asking.
 */
@Component
class SecurityExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is WorkspaceForbiddenException -> ErrorType.NOT_FOUND
            is AdminRequiredException -> ErrorType.FORBIDDEN
            // Unauthorized rather than forbidden: nothing is wrong with the
            // question, there is simply nobody asking it yet.
            is SignInRequiredException -> ErrorType.UNAUTHORIZED
            // Forbidden rather than not-found, and it names the workspace: it is
            // only ever thrown at somebody already looking at that workspace, so
            // there is nothing left to give away and a lot left to explain.
            is WorkspaceAdminRequiredException -> ErrorType.FORBIDDEN
            // Not a desktop server: there is no such switch to set, and the question cannot be put better.
            is DesktopNetworkUnavailableException -> ErrorType.BAD_REQUEST
            else -> return null
        }

        return refused(exception, errorType, environment)
    }
}
