package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.security.WEBHOOK_PATH
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/**
 * How much a webhook caller may send.
 *
 * A webhook body is a build result, a form, another product's event - kilobytes
 * of JSON. Nothing legitimate approaches a megabyte, and the default is there to
 * be a bound rather than a decision anybody has to make.
 */
@ConfigurationProperties(prefix = "orknux.webhook")
data class WebhookProperties(
    /**
     * The most a webhook body may be, in bytes however it is written: `1MB`,
     * `512KB`, `2000000`.
     *
     * Raise it where a real sender genuinely sends more - some products post a
     * whole record, and an installation that knows its senders knows better than
     * a default does.
     */
    val maxBodySize: DataSize = DataSize.ofMegabytes(1),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties::class)
class WebhookBodyLimitConfig {

    /**
     * Registered by hand rather than component-scanned, because it applies to
     * one path and a filter that ran on every request would be reading and
     * re-buffering the interface's own traffic for nothing.
     *
     * Early, since the whole point is to refuse before anything larger than the
     * limit has been kept.
     */
    @Bean
    fun webhookBodyLimit(properties: WebhookProperties): FilterRegistrationBean<WebhookBodyLimit> =
        FilterRegistrationBean(WebhookBodyLimit(properties.maxBodySize.toBytes())).apply {
            addUrlPatterns("$WEBHOOK_PATH/*")
            order = Ordered.HIGHEST_PRECEDENCE + 10
        }
}

/**
 * A bound on what an anonymous caller can make this server hold.
 *
 * The webhook endpoint is open to the internet by necessity - a build server
 * cannot sign in - and its body used to be whatever arrived. What arrived became
 * a String, then a Jackson tree, then a copy of that tree, then a serialisation
 * of the copy, then a row in the run's input: five things the size of the
 * request, from a caller who proved nothing, and the caller decided the size.
 *
 * **The bound belongs here rather than in the endpoint.** By the time
 * `WebhookAPI.receive` is called the body is already a String, so a check there
 * would be a check on something already spent. It is not configuration on the
 * container either - Boot caps multipart uploads and nothing else, and a JSON
 * POST is not multipart - and putting it there would bound every endpoint
 * including the ones that legitimately take a large upload. So: a filter, on
 * this path, ahead of everything.
 *
 * **Refused with 413, uniformly.** The endpoint's rule is that a caller must not
 * be able to tell an armed webhook path from an empty one, and this keeps it: it
 * runs before anything is routed, so every path answers a too-large body the
 * same way whether it exists or not. Nothing is written to the trigger's
 * history, because at this point there is no trigger yet - which is the price of
 * refusing early, and the right way round.
 *
 * The declared length is checked first and the stream is counted anyway, since a
 * chunked request declares nothing and an honest length is not something the
 * caller can be trusted for.
 */
class WebhookBodyLimit(private val most: Long) : Filter {

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val asking = request as? HttpServletRequest
        val answering = response as? HttpServletResponse
        if (asking == null || answering == null) {
            chain.doFilter(request, response)
            return
        }

        val body = if (asking.contentLengthLong > most) null else read(asking)
        if (body == null) {
            refuse(asking, answering)
            return
        }

        chain.doFilter(Replayed(asking, body), answering)
    }

    /**
     * The body, or null if there is more of it than is allowed.
     *
     * One byte past the limit is enough to know, and the rest is never read -
     * so what is held is bounded by the limit however much the caller meant to
     * send. It is held at all because the endpoint has to read it afterwards
     * and a stream can only be read once.
     */
    private fun read(request: HttpServletRequest): ByteArray? {
        val held = request.inputStream.readNBytes(most.coerceAtMost(MOST_THERE_CAN_BE).toInt() + 1)
        return held.takeIf { it.size <= most }
    }

    private fun refuse(request: HttpServletRequest, response: HttpServletResponse) {
        // Not the path: it is what guards the webhook, and logs travel. #588.
        log.warn("A webhook call sent more than the {} bytes allowed", most)
        response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write("""{"error":"The request body is larger than this server accepts."}""")
        response.writer.flush()
    }

    /**
     * The request again, over the bytes already read.
     *
     * Both doors, because Spring picks one of them depending on how the
     * argument was declared, and a request that answered only one of them would
     * work today and stop working when somebody changed a parameter type.
     */
    private class Replayed(request: HttpServletRequest, private val body: ByteArray) :
        HttpServletRequestWrapper(request) {

        /**
         * One stream for the wrapper, not one per call: a servlet request's
         * body is read once, and handing out a fresh stream each time would
         * make a second read silently succeed with the whole body again.
         */
        private val held = ByteArrayInputStream(body)

        private val stream = object : ServletInputStream() {
            override fun read(): Int = held.read()
            override fun read(into: ByteArray, from: Int, length: Int): Int = held.read(into, from, length)
            override fun available(): Int = held.available()
            override fun isFinished(): Boolean = held.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(listener: ReadListener) = throw UnsupportedOperationException()
        }

        private val replayed by lazy { stream.bufferedReader(charset = charsetOf()) }

        override fun getInputStream(): ServletInputStream = stream

        override fun getReader(): BufferedReader = replayed

        /** What the caller said, where it said anything this machine knows. */
        private fun charsetOf(): Charset =
            characterEncoding?.let { named -> runCatching { charset(named) }.getOrNull() } ?: Charsets.UTF_8

        /** What is there, not what the caller said was coming. */
        override fun getContentLength(): Int = body.size

        override fun getContentLengthLong(): Long = body.size.toLong()
    }

    private companion object {
        val log = LoggerFactory.getLogger(WebhookBodyLimit::class.java)

        /**
         * A configured limit past this is nonsense rather than a choice: an
         * array cannot be longer, so it is where counting stops.
         */
        const val MOST_THERE_CAN_BE = Int.MAX_VALUE - 8L
    }
}
