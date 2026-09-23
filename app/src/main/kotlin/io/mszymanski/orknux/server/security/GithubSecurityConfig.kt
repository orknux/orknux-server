package io.mszymanski.orknux.server.security

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.AuthenticationMethod
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User

/**
 * Signing in with GitHub. Issue #239.
 *
 * ### Why it is not the OIDC path with a different issuer
 *
 * GitHub is not an OpenID Connect provider. There is no discovery document to
 * fetch, no ID token to read claims out of, and the token it hands back is
 * opaque rather than a JWT - so all three of the things [OidcSecurityConfig] is
 * built on are missing. What GitHub has is an authorize endpoint, a token
 * endpoint and `https://api.github.com/user`, which is an OAuth2 login and
 * nothing more.
 *
 * The browser half only. A session cookie is issued exactly as every other
 * method issues one, so nothing past the front door knows the difference. There
 * is deliberately no bearer half: validating an opaque token means asking GitHub
 * on every request, and an installation that wants programmatic callers already
 * has API tokens - which work under every method and are this installation's own
 * to revoke.
 *
 * ### Who may sign in, and why it is refused rather than defaulted
 *
 * An OAuth app authenticates every GitHub account in existence. Under OIDC the
 * provider is the boundary - a realm holds the people an installation employs -
 * but a GitHub OAuth app is a boundary around nothing at all. An installation
 * that named neither an organisation nor a list of logins would have a front
 * door open to the internet, and it would look configured.
 *
 * So one of the two is required at startup. There is no value here that is both
 * useful and safe, which is exactly when a default is the wrong answer.
 *
 * Membership is asked of GitHub at the moment of signing in rather than stored,
 * so somebody removed from the organisation stops being able to sign in without
 * anybody telling this installation.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["orknux.security.auth-method"], havingValue = "GITHUB")
class GithubSecurityConfig {

    /**
     * The OAuth app, built from constants rather than discovered.
     *
     * Nothing to fetch and nothing to fail at startup over, which is the one
     * respect in which this is simpler than OIDC: GitHub's three addresses have
     * been the same for a decade and are not an installation's to change.
     */
    @Bean
    fun clientRegistrationRepository(properties: SecurityProperties): ClientRegistrationRepository {
        val github = properties.github
        require(github.clientId.isNotBlank()) {
            "orknux.security.github.client-id is not set, and this installation is configured to sign " +
                "in with GitHub. Set it, or set orknux.security.auth-method to LDAP."
        }
        require(github.clientSecret.isNotBlank()) {
            "orknux.security.github.client-secret is not set, and this installation is configured to " +
                "sign in with GitHub. A GitHub OAuth app always has one."
        }
        /*
         * The whole of the argument above, enforced.
         *
         * Deliberately a startup failure rather than a warning: an installation
         * that starts with this unset is one anybody with a GitHub account can
         * sign in to, and it would look exactly like one that is configured
         * correctly. Nothing downstream can detect the difference afterwards.
         */
        require(github.organisation.isNotBlank() || github.allowedLogins.any { it.isNotBlank() }) {
            "This installation signs in with GitHub but says nobody in particular may. " +
                "Set orknux.security.github.organisation to the organisation whose members may sign " +
                "in, or orknux.security.github.allowed-logins to the logins that may. Without one of " +
                "them any GitHub account in the world could sign in here."
        }

        return InMemoryClientRegistrationRepository(
            ClientRegistration.withRegistrationId(GITHUB_REGISTRATION_ID)
                .clientId(github.clientId)
                .clientSecret(github.clientSecret)
                .clientName(github.displayName)
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(github.scopes)
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userInfoAuthenticationMethod(AuthenticationMethod.HEADER)
                // What GitHub calls the account, and what this installation will
                // know somebody by from here on.
                .userNameAttributeName("login")
                .build(),
        )
    }

    /**
     * Who this is, whether they may be here, and what they may do.
     *
     * Three questions and one call, because the answer to the second is not on
     * the account: it is the organisation's, and asking it is what makes a
     * removed member stop being able to sign in.
     */
    @Bean
    fun githubUserService(
        properties: SecurityProperties,
        transport: OidcTransport,
    ): OAuth2UserService<OAuth2UserRequest, OAuth2User> {
        /*
         * Through the proxy rules, for the reason the OIDC flow is: an
         * installation behind a proxy failed at the exchange, after the person
         * had already signed in at the provider - which is the least explicable
         * place for it to fail. Required rather than nullable here: the
         * transport is built under this method as well as under OIDC, so an
         * absent one is a wiring mistake rather than a shape to tolerate.
         */
        val plain = DefaultOAuth2UserService().apply { setRestOperations(transport.restOperations()) }

        return OAuth2UserService { request ->
            val account = plain.loadUser(request)
            val login = account.getAttribute<String>("login")
                ?: throw refusal("GitHub did not say which account this is.")

            if (!admitted(properties.github, login, request, transport)) {
                throw refusal(
                    "$login is not a member of ${properties.github.organisation} " +
                        "and is not on this installation's list of logins.",
                )
            }

            DefaultOAuth2User(rolesFor(properties, login), account.attributes, "login")
        }
    }

    /**
     * Whether this login may be here at all.
     *
     * The named list first, because it is free and because it is what an
     * installation with no organisation is using. Membership is only asked of
     * GitHub where a list did not already answer.
     */
    private fun admitted(
        github: GithubProperties,
        login: String,
        request: OAuth2UserRequest,
        transport: OidcTransport,
    ): Boolean {
        if (github.allowedLogins.any { it.equals(login, ignoreCase = true) }) return true
        if (github.organisation.isBlank()) return false
        return memberOf(github.organisation, login, request, transport)
    }

    /**
     * Whether GitHub will say this account is in that organisation.
     *
     * `/user/memberships/orgs/{org}` rather than the public members list,
     * because a private membership is invisible to the second and the people an
     * installation most wants to admit are the ones who set theirs private. That
     * is what the `read:org` scope is for.
     *
     * A refusal to answer is a no. An error here is GitHub declining to confirm
     * membership, and reading that as "let them in" would turn every outage into
     * an open door.
     */
    private fun memberOf(
        organisation: String,
        login: String,
        request: OAuth2UserRequest,
        transport: OidcTransport,
    ): Boolean = runCatching {
        /*
         * Through the proxy rules, always. `OutboundClientSeamTest` is what
         * caught this: a `RestClient.create()` fallback sat here, and on a
         * proxied network it would have gone direct - so the membership check
         * would fail and nobody could sign in, which is the failure the OIDC
         * transport was written to stop. The transport is present under this
         * method precisely so there is nothing to fall back to.
         */
        val membership = transport.restClient().get()
            .uri("https://api.github.com/user/memberships/orgs/{org}", organisation)
            .header("Authorization", "Bearer ${request.accessToken.tokenValue}")
            .header("Accept", "application/vnd.github+json")
            .retrieve()
            .body(Map::class.java)

        // GitHub answers `active` for a member and `pending` for somebody who has
        // been invited and has not accepted. An invitation is not membership.
        membership?.get("state") == "active"
    }.getOrElse {
        log.warn("Could not confirm whether {} is in {}: {}", login, organisation, it.message)
        false
    }

    /**
     * What this login may do here.
     *
     * Everybody who gets this far is somebody; the administrators are a list,
     * for the reason [GithubProperties.administrators] gives. Both spellings of
     * each authority are emitted, which is what [OidcAuthorities] does and for
     * the same reason: the rest of the application matches either.
     */
    private fun rolesFor(properties: SecurityProperties, login: String): Set<GrantedAuthority> {
        val administers = properties.github.administrators.any { it.equals(login, ignoreCase = true) }
        val names = if (administers) listOf(ADMINISTRATORS) else listOf(USERS)
        return names.flatMap { listOf(it, "ROLE_$it") }.map { SimpleGrantedAuthority(it) }.toSet()
    }

    private fun refusal(said: String) = OAuth2AuthenticationException(OAuth2Error("access_denied", said, null), said)

    private companion object {
        val log = org.slf4j.LoggerFactory.getLogger(GithubSecurityConfig::class.java)

        /**
         * The two this installation resolves roles against.
         *
         * Named here rather than made configurable, because a GitHub account
         * carries nothing this could map from: OIDC reads a claim the provider
         * fills in, and GitHub's equivalent is a team, which is the thing
         * `administrators` exists to stand in for until somebody needs teams.
         */
        const val ADMINISTRATORS = "ADMINS"
        const val USERS = "USERS"
    }
}

/** The registration id the sign-in URL is built from; see [GITHUB_AUTHORIZE_PATH]. */
const val GITHUB_REGISTRATION_ID = "github"

/** Where the sign-in button sends a browser. */
const val GITHUB_AUTHORIZE_PATH = "/oauth2/authorization/$GITHUB_REGISTRATION_ID"
