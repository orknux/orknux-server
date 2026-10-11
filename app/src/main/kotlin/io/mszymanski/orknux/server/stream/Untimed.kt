package io.mszymanski.orknux.server.stream

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.async.AsyncWebRequest
import org.springframework.web.context.request.async.CallableProcessingInterceptor
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.concurrent.Callable

/**
 * A stream that ends when its own work does, and not at the container's
 * timeout for async requests.
 *
 * A `StreamingResponseBody` is an async request, and the container ends every
 * one of those at `spring.mvc.async.request-timeout` - 330 seconds, which is
 * set for `orknux_news`'s five-minute wait and is right for it. A chat's answer
 * is not a wait with a known end: a model that thought for 637 seconds in one
 * round had its stream closed under it at 330, with no `done` and no answer,
 * and the page showed the question until somebody reloaded. The turn itself
 * went on and was kept; only its reader was cut off.
 *
 * So an endpoint carrying this has no async timeout at all. That is safe only
 * because something else bounds it, and every endpoint that carries it has to
 * be able to say what: a chat's relay ends when the answer does, and
 * [ReaderWatch] pings every second and gives up on the first write that fails,
 * so a stream nobody is reading is noticed and ended without a timer. A new
 * number would have been a guess at how long a model may think, and the guess
 * that was there was wrong.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Untimed

/**
 * Where [Untimed] is read and acted on: marked before the handler runs, and the
 * mark turned into "no timeout" in the one moment Spring still allows it -
 * after the handler has returned its body and before the async request starts.
 */
@Configuration
class UntimedStreams : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                if (handler is HandlerMethod && handler.hasMethodAnnotation(Untimed::class.java)) {
                    request.setAttribute(MARK, true)
                }
                return true
            }
        })
    }

    override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
        configurer.registerCallableInterceptors(object : CallableProcessingInterceptor {
            override fun <T : Any> beforeConcurrentHandling(request: NativeWebRequest, task: Callable<T>) {
                if (request.getAttribute(MARK, RequestAttributes.SCOPE_REQUEST) != true) return
                // Nought or less is "never" to the servlet container.
                (request as? AsyncWebRequest)?.setTimeout(NO_TIMEOUT)
            }
        })
    }

    private companion object {
        const val MARK = "io.mszymanski.orknux.server.stream.Untimed"
        const val NO_TIMEOUT = -1L
    }
}
