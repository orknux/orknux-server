package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestOperations
import org.springframework.web.client.RestTemplate
import java.time.Duration

/**
 * Every call this application makes to the identity provider, built in one place
 * and routed by the proxy rules.
 *
 * **Why this exists at all.** Spring Security reaches the provider four times and
 * builds its own HTTP client for each: discovery, the JWKS document, the token
 * endpoint and userinfo. Each of those clients was, until this file, a plain
 * `HttpURLConnection` going wherever the operating system sent it — so on a
 * network where only a proxy can reach the outside, an OIDC installation could
 * not sign anybody in. Worse than that: discovery runs while the application
 * context is being built, so it did not start at all, and the screen where the
 * proxy rules are written is behind the sign-in that was failing.
 *
 * **Why the defaults are copied rather than replaced.** Two of these clients are
 * particular about their message converters — the token endpoint speaks form
 * encoding and reads a token response, and both want Spring Security's own error
 * handler so that an OAuth error arrives as an `OAuth2AuthorizationException`
 * rather than a bare 400. Handing over an empty client would route the call and
 * break the parsing. So each is assembled the way Spring Security assembles it,
 * with one thing changed: where the bytes go.
 *
 * **Why a timeout is set here and not left open.** Discovery blocks the context
 * coming up. A provider that accepts the connection and never answers would
 * otherwise hang the start indefinitely, with no log line to say what it was
 * waiting for — which is a worse failure than a refused connection, because
 * nothing about it looks like a failure.
 * **Why GitHub gets it too.** Issue #239 added a second method that signs people
 * in at somebody else's site, and every word above is as true of it: the token
 * exchange, the call to `api.github.com/user` and the membership check are four
 * more outbound calls that a proxied network only reaches one way. The name
 * still says OIDC because that is where it came from and renaming it would touch
 * more than it explains; what it actually is is the client this application
 * talks to an identity provider with.
 */
@Component
@ConditionalOnExpression("'\${orknux.security.auth-method:LDAP}' == 'OIDC' or '\${orknux.security.auth-method:LDAP}' == 'GITHUB'")
class OidcTransport(proxies: ProxyRouter) {

    /**
     * The seam. Built from the router, so this client attaches the proxy
     * credentials for whichever rule matched, exactly as every other outbound
     * call in the application does.
     */
    private val factory: ClientHttpRequestFactory = JdkClientHttpRequestFactory(
        proxies.builder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build(),
    ).apply { setReadTimeout(READ_TIMEOUT) }

    /**
     * For discovery, for the JWKS document, and for userinfo: JSON in, a map or
     * a decoded key set out, with the OAuth error handler so a provider that
     * refuses says why.
     */
    fun restOperations(): RestOperations = RestTemplate().apply {
        requestFactory = factory
        errorHandler = OAuth2ErrorResponseErrorHandler()
    }

    /**
     * For the token endpoint, which is the one exchange in the flow that is not
     * JSON in both directions: the request is form encoded and the response is
     * read into an `OAuth2AccessTokenResponse`. These are the two converters
     * Spring Security installs for it, and nothing else — a client carrying the
     * application's own converters could negotiate something the provider did
     * not offer.
     */
    fun restClient(): RestClient = RestClient.builder()
        .requestFactory(factory)
        .messageConverters { converters ->
            converters.clear()
            converters.add(FormHttpMessageConverter())
            converters.add(OAuth2AccessTokenResponseHttpMessageConverter())
        }
        .defaultStatusHandler(OAuth2ErrorResponseErrorHandler())
        .build()

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
