package io.mszymanski.orknux.server.client

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.security.SignInThrottle
import io.mszymanski.orknux.server.security.TooManySignInAttempts
import jakarta.servlet.http.HttpServletRequest
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.format.DateTimeFormatter

data class ClientPairingCodeView(val code: String, val expiresAt: String)

data class ClientCredentialView(val id: Long, val name: String, val client: String, val createdAt: String, val lastUsedAt: String?)

/**
 * The signed-in person's half of pairing: a code to type into a client, the
 * clients they have connected, and disconnecting one. Only ever the caller's
 * own, and only in a session or with a token - a client credential reaches
 * none of this, since it opens nothing but the client doors.
 */
@Controller
class ClientPairingAPI(private val pairings: ClientPairings) {

    @MutationMapping
    fun startClientPairing(): ClientPairingCodeView {
        val issued = pairings.start(person())
        return ClientPairingCodeView(issued.code, issued.expiresAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    }

    @QueryMapping
    fun myClients(): List<ClientCredentialView> = pairings.mine(person()).map {
        ClientCredentialView(
            id = requireNotNull(it.id),
            name = it.name,
            client = it.client,
            createdAt = it.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            lastUsedAt = it.lastUsedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
    }

    @MutationMapping
    fun disconnectClient(@Argument id: Long): Boolean = pairings.disconnect(person(), id)

    private fun person(): String = requireNotNull(SecurityContextHolder.getContext().authentication?.name) { "Nobody is signed in." }
}

/**
 * The client's half: a pairing code traded for a credential, with nobody
 * signed in - which is the point of it. Slowed by the sign-in throttle, under a
 * name of its own, since six characters are guessable given enough tries.
 */
@RestController
class ClientPairRestAPI(private val pairings: ClientPairings, private val throttle: SignInThrottle) {

    /** `{"code": "K7P-4QX", "client": "Chrome", "name": "Chrome on michjak-pc"}` → `{"credential", "user"}`. */
    @PostMapping(PAIR_PATH)
    fun pair(@RequestBody body: JsonNode, request: HttpServletRequest): ResponseEntity<Any> {
        val address = request.remoteAddr ?: "unknown"
        try {
            throttle.check(THROTTLE_NAME, address)
        } catch (slowed: TooManySignInAttempts) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(mapOf("error" to slowed.reason))
        }
        return try {
            val issued = pairings.pair(
                body.path("code").asString(""),
                body.path("client").stringValue(null),
                body.path("name").stringValue(null),
            )
            throttle.succeeded(THROTTLE_NAME, address)
            ResponseEntity.ok(mapOf("credential" to issued.credential, "user" to issued.user))
        } catch (refused: ClientPairingCodeInvalidException) {
            throttle.failed(THROTTLE_NAME, address)
            ResponseEntity.badRequest().body(mapOf("error" to refused.message, "code" to "ClientPairingCodeInvalid"))
        }
    }

    companion object {
        const val PAIR_PATH = "/api/clients/pair"
        private const val THROTTLE_NAME = "client pairing"
    }
}

@Component
class ClientPairingExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val type = when (exception) {
            is ClientNotFoundException -> ErrorType.NOT_FOUND
            is ClientPairingCodeInvalidException -> ErrorType.BAD_REQUEST
            else -> return null
        }
        return refused(exception, type, environment)
    }
}
