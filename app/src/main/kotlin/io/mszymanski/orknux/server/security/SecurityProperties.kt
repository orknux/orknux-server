package io.mszymanski.orknux.server.security

import org.springframework.boot.context.properties.ConfigurationProperties

/** How people sign in to this installation. */
enum class AuthMethod {

    /** Username and password, checked against the directory. */
    LDAP,

    /**
     * Username and password, checked against the accounts this installation holds
     * itself. No directory, no provider, nothing to reach.
     *
     * These accounts exist under every method — somebody has to be able to act
     * through the API without borrowing an administrator's session — so this is not
     * a different door, it is the *only* door: the one [InternalAuthentication]
     * already opens first, with nothing behind it to fall through to.
     *
     * It is what the all-in-one image runs on. That image ships no directory, so
     * under LDAP it described itself as reaching one, offered single sign-on, and
     * reported itself degraded for failing to reach a thing nobody had asked for.
     * A dependency that was never configured is absent, not unreachable, and this
     * is the value that lets the rest of the server say so.
     */
    INTERNAL,

    /**
     * An OpenID Connect provider, two ways at once.
     *
     * A browser is sent to the provider and comes back with a code, which this
     * server exchanges and turns into the same session cookie LDAP sign-in issues —
     * so nothing past the front door knows the difference. A programmatic caller
     * instead presents the provider's own token as a bearer, which is validated per
     * request. Both are the same provider, the same claims and the same roles.
     */
    OIDC,

    /**
     * GitHub, which is not OIDC and cannot be configured as though it were.
     *
     * Issue #239. It has no discovery document, mints no ID token, and hands
     * back an opaque token rather than a JWT - so none of the three things the
     * OIDC path is built on is there. What it does have is an authorize
     * endpoint, a token endpoint and `https://api.github.com/user`, which is an
     * OAuth2 login and nothing more.
     *
     * **The browser half only.** A session cookie is issued exactly as the other
     * methods issue one, so nothing past the front door knows the difference.
     * There is no bearer half: a GitHub token is opaque, so validating one means
     * asking GitHub on every request, and an installation that wants
     * programmatic callers has API tokens for that - which work under every
     * method and are this installation's own.
     *
     * Who may sign in is not "anybody with a GitHub account", which is what this
     * would be with nothing else said - so it refuses to start without either an
     * organisation or a list of logins. See [GithubProperties].
     */
    GITHUB,

    /**
     * Nobody signs in. There is no door, because there is no wall.
     *
     * For an installation somebody is trying out, and for one already behind a
     * gate of its own — a VPN, an authenticating proxy, a network nothing else can
     * reach. Everything downstream still has an identity to work with, because
     * there still is one: [io.mszymanski.orknux.server.security.OpenAccess] holds
     * the identity this installation acts as and says why it administers.
     *
     * **Not a degraded LDAP, and never a fallback.** It is reached by writing
     * `ORKNUX_AUTH_METHOD=NONE` and by nothing else: an unset variable is [LDAP],
     * an empty one is [LDAP], and a value that is none of these four names fails
     * the binding and stops the application before it answers a request. There is
     * no spelling of "off" that opens this by accident, which is why it is a value
     * of this enum rather than a second switch beside it — a boolean has a false
     * that something else could compute.
     *
     * Everything that can say so, says so: the log at startup, the Doctor screen,
     * `/api/auth/method`, and a strip across the top of every page. Somebody who
     * inherits this installation must not have to read the environment to find out
     * that it is open.
     */
    NONE,
}

@ConfigurationProperties(prefix = "orknux.security")
data class SecurityProperties(
    /**
     * Which one is in use. One at a time, deliberately.
     *
     * Two at once would mean an installation with an LDAP password for every
     * account its OIDC provider governs — a second way in, that the provider's
     * policies do not reach and its administrators do not know about.
     *
     * The default is LDAP and stays LDAP: this names what an installation *has*,
     * and every installation that had a directory yesterday still has one. It is
     * also the direction to fail in, now that one of the four is [AuthMethod.NONE]
     * — the default of a security switch has to be the closed position, and a
     * misspelt value does not reach a default at all, it stops the application.
     */
    val authMethod: AuthMethod = AuthMethod.LDAP,

    /**
     * Holders administer, whatever else they hold.
     *
     * Kept alongside the roles rather than replaced by them: an installation that
     * has always granted this authority should not need a mapping written before
     * anybody can reach the Admin section — least of all the person who would have
     * to write it.
     */
    val adminRole: String = "ROLE_ADMINS",

    /**
     * Which of the identity provider's names grants which of this installation's roles.
     *
     * The key is what the provider says — an LDAP group DN or its common name, or the
     * value of an OIDC claim. The value is the name of a role, as the Roles screen
     * spells it. Both sides are matched without regard to case.
     *
     * Empty is a working configuration, not an unfinished one: a role with no mapping
     * is granted to whoever holds an authority derived from its own name, which is
     * what LDAP produced before roles existed.
     */
    val roleMapping: Map<String, String> = emptyMap(),

    /** What this installation calls its OIDC provider, where that is in use. */
    val oidc: OidcProperties = OidcProperties(),
    /** How GitHub sign-in is configured, where that is the method. See [AuthMethod.GITHUB]. */
    val github: GithubProperties = GithubProperties(),
)

data class OidcProperties(
    /**
     * The claim carrying group or role membership.
     *
     * There is no standard one. Keycloak puts them in `groups` with the right mapper,
     * Entra uses `groups` or `roles`, Okta usually `groups`. Whatever it is called,
     * each value in it is treated the way an LDAP group is: looked up in the role
     * mapping, or matched against a role's own name.
     */
    val rolesClaim: String = "groups",

    /**
     * The claim to show as the person's name.
     *
     * `preferred_username` where the provider sets it, because that is what somebody
     * recognises as themselves; the subject is a stable identifier and an unreadable
     * one, so it is the fallback rather than the default.
     */
    val usernameClaim: String = "preferred_username",

    /** What the sign-in button says. The provider's name, as the people signing in know it. */
    val displayName: String = "single sign-on",

    /**
     * The provider, by its issuer.
     *
     * Enough on its own: the discovery document at this address says where the
     * endpoints are and which keys sign the tokens, so none of that is configured
     * separately and none of it goes stale when the provider rotates something.
     */
    val issuer: String = "",

    val clientId: String = "",

    /**
     * The client secret, where the provider issued one.
     *
     * Empty for a public client, which is the right shape when nothing but a browser
     * ever performs the exchange — the code flow with PKCE does not need one.
     */
    val clientSecret: String = "",

    /**
     * Which audiences a bearer token may name, where the client id is not the one.
     *
     * Empty means the client id, which is what the provider writes into a token minted
     * for this application and what an ID token always carries. It is set when the
     * provider says something else: Keycloak's access tokens name `account` unless an
     * audience mapper is configured against this client, and Entra's name the
     * application's App ID URI rather than its client id. Naming one of these is enough -
     * a token has to match one, not all of them.
     */
    val audiences: List<String> = emptyList(),

    /** What to ask the provider for. `openid` is required; the rest is what is read. */
    val scopes: List<String> = listOf("openid", "profile", "email", "groups"),
)

/**
 * How GitHub sign-in is configured. Issue #239.
 *
 * GitHub is not an OpenID Connect provider and this is not the OIDC settings
 * under another name: there is no issuer to discover, no claims to name, and no
 * token to validate per request. What it takes is an OAuth app's two halves and
 * an answer to the question OIDC answers with a claim - who is allowed in.
 *
 * **That question has no safe default.** An OAuth app will happily authenticate
 * every GitHub account there is, so an installation that named neither an
 * organisation nor a list of logins would have a front door open to the entire
 * internet. It is refused at startup rather than defaulted, because there is no
 * value here that is both useful and safe.
 */
data class GithubProperties(
    val clientId: String = "",

    /**
     * The OAuth app's secret.
     *
     * Required, unlike OIDC's: GitHub issues no public clients for this flow, so
     * an empty one is a misconfiguration rather than a different shape.
     */
    val clientSecret: String = "",

    /**
     * The organisation whose members may sign in.
     *
     * Checked against what GitHub says at the moment of signing in rather than
     * against anything stored here, so somebody removed from the organisation
     * stops being able to sign in without this installation being told.
     *
     * Only members whose membership GitHub will admit to. A private membership
     * is not visible to an OAuth token without `read:org`, which is why that
     * scope is asked for - an installation that declines it will find its
     * private members unable to sign in, and that is better than the opposite
     * mistake.
     */
    val organisation: String = "",

    /**
     * Logins that may sign in whatever [organisation] says, and the whole of the
     * list where no organisation is named.
     *
     * For an installation of a handful of people, and for the first
     * administrator of one that has an organisation but has not set up teams.
     */
    val allowedLogins: List<String> = emptyList(),

    /**
     * Logins that administer this installation.
     *
     * By login rather than by team, which is the shape GitHub would suggest -
     * teams need another call and another scope, and an installation reaching
     * for GitHub sign-in is usually small enough that a list of two names is the
     * honest answer. A team-based mapping is the thing to add when somebody has
     * an organisation big enough to need one.
     */
    val administrators: List<String> = emptyList(),

    /** What the sign-in button says. */
    val displayName: String = "GitHub",

    /**
     * What to ask GitHub for.
     *
     * `read:user` for the login and name, and `read:org` so that a private
     * organisation membership can be seen - without it, exactly the people an
     * installation most wants to admit are the ones it cannot verify.
     */
    val scopes: List<String> = listOf("read:user", "read:org"),
)
