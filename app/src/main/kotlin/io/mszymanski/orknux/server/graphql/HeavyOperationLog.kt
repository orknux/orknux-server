package io.mszymanski.orknux.server.graphql

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

/**
 * Logs a GraphQL operation that was slow or answered with a lot, by name, time
 * and size - never what it carried. Issue #616.
 *
 * A page that ran the server out of memory left nothing to say which of the
 * dozen requests it sends was the heavy one, and the data that made it heavy
 * belongs to whoever runs the installation. The operation's name, how long it
 * took and how big the answer was are enough to know where to look, and carry
 * nothing of theirs.
 *
 * Measured once the answer is complete. An operation over either threshold is
 * logged at WARN; zero switches a threshold off. Size is off unless asked for,
 * because measuring it writes every answer out a second time - churn on a
 * server whose trouble may be churn.
 */
@Component
class HeavyOperationLog(
    private val mapper: ObjectMapper,
    @Value("\${orknux.graphql.log-slower-than-ms:3000}") private val slowerThanMs: Long,
    @Value("\${orknux.graphql.log-larger-than-kb:0}") private val largerThanKb: Long,
) : WebGraphQlInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun intercept(request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain): Mono<WebGraphQlResponse> {
        if (slowerThanMs <= 0 && largerThanKb <= 0) return chain.next(request)
        val started = System.nanoTime()
        return chain.next(request).doOnNext { response ->
            val millis = (System.nanoTime() - started) / 1_000_000
            val slow = slowerThanMs in 1..millis
            // Sized only when it could matter: serialising an answer twice is
            // itself a cost, so a fast operation is not measured unless sizes
            // are watched.
            val bytes = if (largerThanKb > 0) runCatching { mapper.writeValueAsBytes(response.toMap()).size.toLong() }.getOrDefault(-1) else -1
            val large = largerThanKb > 0 && bytes >= largerThanKb * 1024
            if (slow || large) {
                log.warn(
                    "GraphQL operation {} took {} ms and answered {} KB{}",
                    request.operationName ?: firstField(request.document),
                    millis,
                    if (bytes < 0) "?" else (bytes / 1024).toString(),
                    if (response.errors.isEmpty()) "" else " with ${response.errors.size} error(s)",
                )
            }
        }
    }

    /** An unnamed operation by its first field, which is what a reader can find in the code. */
    private fun firstField(document: String): String =
        Regex("""\{\s*(\w+)""").find(document)?.groupValues?.get(1) ?: "(unnamed)"
}
