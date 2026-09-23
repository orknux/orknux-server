package io.mszymanski.orknux.server.security

import jakarta.servlet.DispatcherType
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authorization.AuthenticatedAuthorizationManager
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.user.OAuth2User
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.user.TokenAuthenticationFilter
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties::class, SecurityProperties::class)
class SecurityConfig {

    /**
     * How a password is stored, for the few users this installation keeps one for.
     *
     * Delegating, so the hash says which algorithm made it: the day bcrypt is
     * not the answer, what is already stored still verifies and what is written
     * next is stronger, with nothing to migrate by hand.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
        settings: InstallationSettings,
        tokens: TokenAuthenticationFilter,
        /** Who this installation acts as when nobody signs in; idle under every other method. */
        open: OpenAccess,
        /**
         * Absent unless this installation signs in with OIDC, which is why it is
         * nullable rather than required: under LDAP there is no provider to
         * reach and no bean to inject.
         */
        transport: OidcTransport?,
        /**
         * Who may sign in with GitHub, and what they may do. Issue #239.
         *
         * Absent under every other method, for the reason `transport` is. It is
         * this method's security boundary rather than a detail of it: an OAuth
         * app authenticates every GitHub account there is, and this is what
         * decides which of them belong here.
         */
        githubUsers: OAuth2UserService<OAuth2UserRequest, OAuth2User>?,
    ): SecurityFilterChain {
        http {
            cors { }
            // The API is cookie-session based; a CSRF token flow still needs adding
            // before this is exposed anywhere but a dev machine.
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
            authorizeHttpRequests {
                /*
                 * The error page, which is a second dispatch of a request that
                 * already passed this chain.
                 *
                 * Without this, anything thrown by a REST endpoint is forwarded
                 * to `/error`, that forward is treated as a fresh unauthenticated
                 * request, and the caller is answered 401 with an empty body. So
                 * asking for a workspace that does not exist said "sign in", a
                 * failed upload said "sign in", and every real fault arrived
                 * wearing the one costume guaranteed to send somebody looking in
                 * the wrong place. GraphQL was unaffected, which is why it went
                 * unnoticed: it answers its own errors with 200.
                 *
                 * Permitting the dispatch does not expose anything — the request
                 * that produced the error was authorised on its way in, and the
                 * error page carries a status and a message, not data.
                 */
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)
                /*
                 * The async dispatch, which is the back half of a request that
                 * answered with a promise rather than a value.
                 *
                 * The same argument as the error dispatch above, and for the
                 * same reason it has to be said: the container comes back to
                 * finish writing the response and that arrives here looking
                 * like a fresh request, with nobody on it - the filters that
                 * establish who is calling all decline to run twice, by
                 * design. Refusing it answers 401 to a caller who was
                 * authorised on the way in and whose answer is already
                 * computed.
                 *
                 * Nothing is opened by this. A request only reaches an async
                 * dispatch by having passed the check above on its first, and
                 * what happens here is serialising an answer that was decided
                 * while somebody was still known to be there. `orknux_news` is
                 * what made it necessary; see [McpAPI].
                 */
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ASYNC), permitAll)
                authorize(HttpMethod.POST, LOGIN_PATH, permitAll)
                /*
                 * How to sign in is not itself a secret, and the sign-in screen has
                 * to ask before anybody can: with OIDC there is no password box to
                 * draw, only a button pointing at the provider.
                 */
                authorize(HttpMethod.GET, AUTH_METHOD_PATH, permitAll)
                /*
                 * Forgetting a password is the one thing that cannot be done
                 * signed in, so this pair has to be open. What stands in for
                 * authentication is a link mailed to an address the account
                 * already had, a throttle counting how often anybody knocks, and
                 * an answer that is the same sentence whether or not the address
                 * belongs to anybody.
                 */
                authorize(HttpMethod.POST, PASSWORD_RESET_PATH, permitAll)
                authorize(HttpMethod.POST, "$PASSWORD_RESET_PATH/**", permitAll)
                /*
                 * A webhook is called by whatever is out there — a build server,
                 * a form, another product — and none of them can sign in here.
                 * What answers is a path nothing else knows and a shape it has
                 * to match; anything else is a 404. Proving who the caller is
                 * comes later, and will be the trigger's own business.
                 */
                authorize(HttpMethod.POST, "$WEBHOOK_PATH/**", permitAll)
                /*
                 * The metrics, for a scraper with nowhere to keep a session.
                 *
                 * Asked rather than decided, because this one is a switch on the
                 * Admin screen and everything else here is a fact about the
                 * application. See [metricsAccess].
                 */
                authorize(HttpMethod.GET, PROMETHEUS_PATH, metricsAccess(settings))
                authorize(anyRequest, authenticated)
            }
            // Answer unauthenticated calls with 401 instead of redirecting to a login page.
            exceptionHandling {
                authenticationEntryPoint = HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
            }
        }

        /*
         * Both OIDC flows, together, and only where OIDC is what this installation
         * uses.
         *
         * The browser flow ends in the same session cookie password sign-in issues,
         * so everything past the front door — the GraphQL API, the audit log, the
         * workspace checks — is unchanged and does not know which one happened. The
         * bearer flow is for callers that hold a token already and have nowhere to
         * keep a cookie; it validates per request and starts no session.
         *
         * PKCE is not configured here because Spring Security sends it by default
         * for public clients and for confidential ones where the provider advertises
         * it. Turning it on explicitly would be describing what already happens.
         */
        if (properties.authMethod == AuthMethod.OIDC) {
            http {
                oauth2Login {
                    /*
                     * The two calls the browser flow makes to the provider after
                     * the redirect comes back: the code is exchanged for a token,
                     * and the token is spent on userinfo. Both are Spring
                     * Security's own clients, and both went out direct until they
                     * were given one built from the proxy rules - so on a proxied
                     * network the sign-in failed at the exchange, after the person
                     * had already typed their password at the provider, which is
                     * the least explicable place for it to fail.
                     *
                     * Configured here rather than left to bean lookup: an
                     * accidental rename would silently restore the direct client
                     * and nothing would look different until somebody behind a
                     * proxy tried to sign in.
                     */
                    transport?.let { routed ->
                        tokenEndpoint {
                            accessTokenResponseClient = RestClientAuthorizationCodeTokenResponseClient()
                                .apply { setRestClient(routed.restClient()) }
                        }
                        userInfoEndpoint {
                            val plain = DefaultOAuth2UserService().apply { setRestOperations(routed.restOperations()) }
                            userService = plain
                            // The OIDC service delegates the actual fetch to the
                            // one above, so the route is set once for both.
                            oidcUserService = OidcUserService().apply { setOauth2UserService(plain) }
                        }
                    }
                }
                // The decoder this uses is the bean in OidcSecurityConfig, which
                // already fetches its keys through the rules.
                oauth2ResourceServer { jwt { } }
            }
        }

        /*
         * The same browser flow for GitHub, and only the browser flow. Issue #239.
         *
         * No `oauth2ResourceServer` beside it, unlike OIDC: a GitHub token is
         * opaque rather than a JWT, so there is nothing to decode and validating
         * one would mean asking GitHub on every request. An installation that
         * wants programmatic callers has API tokens, which work under every
         * method and are this installation's own to revoke.
         *
         * The user service is where who-may-sign-in is decided, which is why it
         * is a bean rather than configured inline: it is the security boundary
         * of this method, and it belongs beside the reasoning for it.
         */
        if (properties.authMethod == AuthMethod.GITHUB) {
            http {
                oauth2Login {
                    transport?.let { routed ->
                        tokenEndpoint {
                            accessTokenResponseClient = RestClientAuthorizationCodeTokenResponseClient()
                                .apply { setRestClient(routed.restClient()) }
                        }
                    }
                    githubUsers?.let { service ->
                        userInfoEndpoint { userService = service }
                    }
                }
            }
        }

        /*
         * A token is read before anything asks who this is.
         *
         * Ahead of the session filter, so a caller carrying one is somebody by
         * the time authorisation runs - and behind nothing that matters, since
         * a request without the header passes straight through. This is what
         * makes the API and the MCP endpoint reachable by something with no
         * browser to keep a cookie in.
         */
        http.addFilterBefore(tokens, UsernamePasswordAuthenticationFilter::class.java)

        /*
         * The one place authentication is turned off, and it is an `if` on a bound
         * enum rather than a condition on a bean.
         *
         * Nothing above this line changes: the chain still ends in `authenticated`,
         * still answers 401 to a request carrying nobody, and still opens exactly
         * the paths it opened before. What changes is that under NONE a request
         * carrying nobody is given somebody on the way in - see [OpenAccess] for
         * who, and why that identity administers.
         *
         * Decided here so that there is a single reading of the switch. A
         * `@ConditionalOnProperty` matching the raw string would say yes to `NONE`
         * and no to `none`, both of which bind to the same enum constant, so the
         * two spellings would behave differently and only one of them would look
         * like it had worked.
         */
        if (properties.authMethod == AuthMethod.NONE) {
            http.addFilterAfter(OpenAccessFilter(open), TokenAuthenticationFilter::class.java)
        }

        return http.build()
    }

    /**
     * Who may read the metrics, asked on every scrape rather than answered once.
     *
     * Authenticated is the default and the right one: a scrape describes the
     * installation rather than the machine, so an open one publishes how many
     * workspaces there are, how often workflows run and how often they fail. A
     * scraper that can carry an Authorization header needs nothing opened — an
     * API token is read on the way in like anybody's — and merely being somebody
     * is enough, since a scrape is aggregate counters rather than anybody's data
     * and a token that had to administer would be a far stronger credential to
     * leave sitting in a scrape configuration.
     *
     * **Why a manager and not an `if`.** Every other rule above is settled when
     * this chain is built, because every other rule is about what a path *is*.
     * This one is a switch an administrator can press, and a rule read once at
     * startup would mean the press took a restart to mean anything — which is
     * the whole of what was asked for. So the question is put to
     * [InstallationSettings] per request: one lookup by primary key on a path
     * that is scraped every fifteen seconds, against a decision that would
     * otherwise be a deployment.
     *
     * Off is still off unless somebody says otherwise, and what it opens cannot
     * grow: one method, one path, and every other Actuator endpoint unexposed
     * rather than merely protected.
     */
    private fun metricsAccess(settings: InstallationSettings): AuthorizationManager<RequestAuthorizationContext> {
        val closed = AuthenticatedAuthorizationManager.authenticated<RequestAuthorizationContext>()
        return AuthorizationManager { authentication, request ->
            if (settings.metricsAnonymous()) OPEN else closed.authorize(authentication, request)
        }
    }

    @Bean
    fun corsConfigurationSource(properties: WebProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = properties.allowedOrigins
            allowedMethods = listOf("GET", "POST", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }

    private companion object {
        /** What [metricsAccess] answers once an administrator has opened the door. */
        val OPEN = AuthorizationDecision(true)
    }
}

const val LOGIN_PATH = "/api/session"

/** Open: what the sign-in screen has to know before anybody has signed in. */
const val AUTH_METHOD_PATH = "/api/auth/method"

/** Open, necessarily: whoever is asking has no password to sign in with. */
const val PASSWORD_RESET_PATH = "/api/password-reset"

/** Where a webhook trigger answers; open, because its callers cannot sign in. */
const val WEBHOOK_PATH = "/api/webhooks"

/** Where a scrape reads. Authenticated unless an administrator has said otherwise. */
const val PROMETHEUS_PATH = "/actuator/prometheus"
