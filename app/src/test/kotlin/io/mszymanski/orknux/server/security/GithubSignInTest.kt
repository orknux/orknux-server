package io.mszymanski.orknux.server.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Signing in with GitHub, and who may. Issue #239.
 *
 * GitHub is not an OpenID Connect provider - no discovery document, no ID token,
 * an opaque token rather than a JWT - so it cannot be configured as though it
 * were, and it gets a method of its own.
 *
 * ### What this test is and is not
 *
 * It does not sign anybody in. That takes a GitHub OAuth app, a callback address
 * GitHub can reach, and a person at a browser, and a test that faked all three
 * would be a test of the fake. **The redirect, the code exchange and the call to
 * `api.github.com` are unverified here** and are the part to try by hand against
 * a real app before this is relied on.
 *
 * What it pins is the part that is this installation's own and the part that is
 * dangerous: an OAuth app authenticates every GitHub account in existence, so
 * "who may sign in" is not the provider's answer the way it is under OIDC. It is
 * this configuration's, and the failure mode is an installation that looks set
 * up and is open to the internet.
 *
 * So what is asserted is that such an installation cannot start.
 */
class GithubSignInTest {

    private val config = GithubSecurityConfig()

    private fun properties(
        clientId: String = "abc",
        clientSecret: String = "shh",
        organisation: String = "",
        allowedLogins: List<String> = emptyList(),
    ) = SecurityProperties(
        authMethod = AuthMethod.GITHUB,
        github = GithubProperties(
            clientId = clientId,
            clientSecret = clientSecret,
            organisation = organisation,
            allowedLogins = allowedLogins,
        ),
    )

    /**
     * The one that matters.
     *
     * Under OIDC the provider is the boundary - a realm holds the people an
     * installation employs. A GitHub OAuth app is a boundary around nothing, so
     * an installation naming neither an organisation nor a list would have a
     * front door open to every GitHub account there is, and would look exactly
     * like one that is configured correctly.
     */
    @Test
    fun `an installation that says nobody in particular may sign in does not start`() {
        assertThatThrownBy { config.clientRegistrationRepository(properties()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("says nobody in particular may")
            // And it names both ways out, because the next thing somebody does
            // is look for which one to set.
            .hasMessageContaining("organisation")
            .hasMessageContaining("allowed-logins")
    }

    @Test
    fun `an organisation is enough to start`() {
        val registrations = config.clientRegistrationRepository(properties(organisation = "orknux"))

        assertThat(registrations.findByRegistrationId(GITHUB_REGISTRATION_ID)).isNotNull()
    }

    @Test
    fun `a list of logins is enough on its own, for an installation with no organisation`() {
        val registrations = config.clientRegistrationRepository(properties(allowedLogins = listOf("alice")))

        assertThat(registrations.findByRegistrationId(GITHUB_REGISTRATION_ID)).isNotNull()
    }

    /** A list of empty strings is not a list of logins, whatever it looks like. */
    @Test
    fun `a list holding nothing is not an answer`() {
        assertThatThrownBy { config.clientRegistrationRepository(properties(allowedLogins = listOf("", "  "))) }
            .hasMessageContaining("says nobody in particular may")
    }

    @Test
    fun `the client has to be configured, and each half is named`() {
        assertThatThrownBy { config.clientRegistrationRepository(properties(clientId = "", organisation = "o")) }
            .hasMessageContaining("client-id")
        assertThatThrownBy { config.clientRegistrationRepository(properties(clientSecret = "", organisation = "o")) }
            .hasMessageContaining("client-secret")
    }

    /**
     * Built from constants rather than discovered, which is the one respect in
     * which this is simpler than OIDC: GitHub's three addresses are not an
     * installation's to change.
     */
    @Test
    fun `it points at GitHub, and asks for what it needs to check membership`() {
        val registration = config.clientRegistrationRepository(properties(organisation = "orknux"))
            .findByRegistrationId(GITHUB_REGISTRATION_ID)

        assertThat(registration.providerDetails.authorizationUri).isEqualTo("https://github.com/login/oauth/authorize")
        assertThat(registration.providerDetails.tokenUri).isEqualTo("https://github.com/login/oauth/access_token")
        assertThat(registration.providerDetails.userInfoEndpoint.uri).isEqualTo("https://api.github.com/user")
        // `login` is what GitHub calls the account and what this installation
        // will know somebody by from here on.
        assertThat(registration.providerDetails.userInfoEndpoint.userNameAttributeName).isEqualTo("login")
        /*
         * read:org is what makes a private organisation membership visible. An
         * installation that declined it would find exactly the people it most
         * wants to admit unable to sign in.
         */
        assertThat(registration.scopes).contains("read:org")
    }

    @Test
    fun `the sign-in path is the one the button sends a browser to`() {
        assertThat(GITHUB_AUTHORIZE_PATH).isEqualTo("/oauth2/authorization/$GITHUB_REGISTRATION_ID")
    }
}
