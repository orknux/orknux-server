package io.mszymanski.orknux.server.security

import graphql.ExecutionResult
import graphql.GraphqlErrorBuilder
import graphql.language.OperationDefinition
import graphql.parser.Parser
import org.springframework.graphql.execution.ErrorType
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * What an access token may do: read, or read and write.
 *
 * A read-only token signs its owner in as they are - the same roles, the same
 * workspaces - and is then held to reading, in three places, because a token
 * reaches the server through three doors:
 *
 * - GraphQL, where every mutation is refused ([ReadOnlyTokenGraphQl]);
 * - the MCP endpoint, which offers it only the tools that read;
 * - everything else, where a request that is not a read is refused before it
 *   reaches a controller ([io.mszymanski.orknux.server.user.TokenAuthenticationFilter]).
 *
 * Carried as an authority beside the owner's roles rather than as a role of
 * its own: it narrows what the person may do, and no role mapping should ever
 * be able to grant it or take it away.
 */
object TokenAccess {

    /** The authority a read-only token adds. Spelled so no directory group or role name can produce it. */
    const val READ_ONLY = "orknux:token-read-only"

    /** The request attribute the filter sets, for the GraphQL door, which reads attributes rather than the security context. */
    const val READ_ONLY_ATTRIBUTE = "orknux.token.readOnly"

    /** What a refusal says, in every door. */
    const val REFUSAL = "This access token may only read. Make a read and write token to change anything."

    /** Whether the caller on this thread came in with a read-only token. */
    fun readOnly(): Boolean =
        SecurityContextHolder.getContext().authentication?.authorities?.any { it.authority == READ_ONLY } == true
}

/**
 * Refuses a mutation sent with a read-only token, before anything runs.
 *
 * Here rather than in each controller, so a mutation added tomorrow is refused
 * without anybody remembering to: the operation's type is read off the document
 * itself, and a query is let through untouched.
 */
@Component
class ReadOnlyTokenGraphQl : WebGraphQlInterceptor {

    override fun intercept(request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain): Mono<WebGraphQlResponse> {
        if (request.attributes[TokenAccess.READ_ONLY_ATTRIBUTE] != true || !writes(request)) return chain.next(request)
        val refused = GraphqlErrorBuilder.newError()
            .message(TokenAccess.REFUSAL)
            .errorType(ErrorType.FORBIDDEN)
            .extensions(mapOf("code" to "TokenReadOnly"))
            .build()
        val result = ExecutionResult.newExecutionResult().addError(refused).build()
        return Mono.just(WebGraphQlResponse(DefaultExecutionGraphQlResponse(request.toExecutionInput(), result)))
    }

    /** Whether the operation this request runs is a mutation; one that cannot be parsed is left to fail as it would. */
    private fun writes(request: WebGraphQlRequest): Boolean = runCatching {
        val operations = Parser().parseDocument(request.document).getDefinitionsOfType(OperationDefinition::class.java)
        val running = request.operationName?.let { name -> operations.firstOrNull { it.name == name } }
            ?: operations.singleOrNull()
        running?.operation == OperationDefinition.Operation.MUTATION ||
            (running == null && operations.any { it.operation == OperationDefinition.Operation.MUTATION })
    }.getOrDefault(false)
}
