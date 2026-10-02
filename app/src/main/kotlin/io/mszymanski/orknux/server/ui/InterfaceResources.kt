package io.mszymanski.orknux.server.ui

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.time.Duration

/**
 * The interface, served by the server from its own jar. Issue #585.
 *
 * It used to be a second image: nginx serving the bundle and forwarding the
 * API. An in-place update (#584) has to carry both halves, so they are one
 * artifact, and what nginx did is done here: the bundle's files, every route
 * the page draws answered with `index.html`, and the caching nginx set.
 *
 * The bundle is put on the classpath at `static/` when the jar is built with
 * `-Pwith-ui`, which the images do. A jar built without it serves no interface
 * and every one of these paths is a 404 - which is what the test suite and
 * `spring-boot:run` want, since development has Vite in front.
 */
@Configuration
class InterfaceResources : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Hashed by the build, so they can be kept for a year.
        registry.addResourceHandler("/assets/**")
            .addResourceLocations(BUNDLE + "assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())

        // Everything else: a file of the bundle, or the page that draws the route.
        // Never cached, or a deployment would go on serving the previous bundle.
        registry.addResourceHandler("/**")
            .addResourceLocations(BUNDLE)
            .setCacheControl(CacheControl.noCache())
            .resourceChain(true)
            .addResolver(PageResolver())
    }

    /**
     * A file where there is one, `index.html` where there is not - except under
     * a path the server answers itself, where a missing thing is a 404 rather
     * than a sign-in screen pretending to be an API response.
     */
    private class PageResolver : PathResourceResolver() {
        override fun getResource(resourcePath: String, location: Resource): Resource? {
            if (servedByServer("/$resourcePath")) return null
            super.getResource(resourcePath, location)?.let { return it }
            return location.createRelative("index.html").takeIf { it.exists() && it.isReadable }
        }
    }

    companion object {
        private const val BUNDLE = "classpath:/static/"

        /**
         * Where the server answers rather than the interface. Kept beside the
         * page fallback because the two must agree: the security chain opens
         * everything outside these to anybody, which is only safe while what is
         * out there is the bundle and nothing else.
         */
        private val SERVER_PREFIXES = listOf("/api", "/graphql", "/mcp", "/actuator", "/oauth2", "/login/oauth2", "/error")

        fun servedByServer(path: String): Boolean =
            SERVER_PREFIXES.any { path == it || path.startsWith("$it/") }

        /**
         * What anybody may fetch without signing in: the sign-in page has to load
         * before anybody has. A GET outside the server's own paths reaches nothing
         * but the bundle - see [servedByServer].
         */
        val PAGES = RequestMatcher { request: HttpServletRequest ->
            request.method == "GET" && !servedByServer(request.requestURI.removePrefix(request.contextPath))
        }
    }
}
