package io.mszymanski.orknux.server.security

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * How to sign in to this installation, asked before anybody has.
 *
 * The sign-in screen cannot draw itself without this. Under LDAP and under INTERNAL
 * it needs a username and password box; under OIDC there is nothing to type — only a
 * button pointing at the provider, named the way the people signing in know it.
 *
 * The two password methods draw the same form and differ only in what the card says
 * underneath it, which is the one place the difference is worth showing: "against the
 * directory" is a promise this installation has to be able to keep, and the all-in-one
 * image cannot. So [displayName] carries a phrase that is true of whichever method is
 * in use, and the screen has a sentence to put there rather than a guess.
 *
 * Open, and deliberately says nothing else. Which provider an installation uses is
 * visible to anyone who reaches the sign-in page anyway, since that is where they are
 * about to be sent.
 */
@RestController
@RequestMapping(AUTH_METHOD_PATH)
class AuthMethodAPI(private val properties: SecurityProperties) {

    @GetMapping
    fun method(): AuthMethodView = AuthMethodView(
        method = properties.authMethod.name,
        /*
         * The provider's name where there is a provider, and where there is a
         * directory — LDAP has always been answered this way and still is. Only
         * INTERNAL says something else, because "single sign-on" is the one thing an
         * installation with no directory and no provider must not be described as.
         */
        displayName = when (properties.authMethod) {
            AuthMethod.INTERNAL -> INTERNAL_DISPLAY_NAME
            AuthMethod.NONE -> NONE_DISPLAY_NAME
            // Its own name, because "single sign-on" is not what anybody calls
            // the button that says GitHub on it. Issue #239.
            AuthMethod.GITHUB -> properties.github.displayName
            else -> properties.oidc.displayName
        },
        // Where the browser flow starts. Spring registers this path for the
        // registration id; naming it here keeps the interface from having to know
        // Spring's URL conventions.
        authorizeUrl = when (properties.authMethod) {
            AuthMethod.OIDC -> OIDC_AUTHORIZE_PATH
            AuthMethod.GITHUB -> GITHUB_AUTHORIZE_PATH
            else -> null
        },
        /*
         * The one thing this endpoint exists to shout rather than to answer.
         *
         * Null on every installation that asks people to sign in, and the sentence
         * on the one that does not - which is what the interface draws across the
         * top of every page. Sent from here rather than worked out in the browser
         * because the server is what knows, and because the Doctor, the startup log
         * and this all read the same constant and so cannot disagree.
         *
         * Open, like the rest of this view, and there is nothing to protect: on an
         * installation where this is set, the caller reading it may already do
         * everything. Saying so is strictly better than letting them find out.
         */
        notice = if (properties.authMethod == AuthMethod.NONE) AUTHENTICATION_OFF else null,
    )
}

data class AuthMethodView @JsonCreator constructor(
    @JsonProperty("method") val method: String,
    /** What the button says, where there is a button. */
    @JsonProperty("displayName") val displayName: String,
    /** Where to send the browser, or null when there is a password box instead. */
    @JsonProperty("authorizeUrl") val authorizeUrl: String?,
    /**
     * What the interface has to say out loud about this installation, wherever
     * somebody is standing in it. Null when there is nothing to say, which is every
     * installation that asks people to sign in.
     */
    @JsonProperty("notice") val notice: String? = null,
)

/**
 * What an installation holding its own accounts calls them.
 *
 * A phrase rather than a name, because there is no third party here to name: it reads
 * as the end of "signs in with …" and as a note under the password box, which are the
 * two places the sign-in card puts it.
 */
const val INTERNAL_DISPLAY_NAME = "an account on this installation"

/**
 * What an installation with no sign-in at all calls it.
 *
 * The screen that would draw a button is unreachable here — the session exists
 * before anybody asks for one — so this is what a caller reading the endpoint
 * directly is told, and it says the thing rather than naming a provider that is
 * not there. [AuthMethodView.notice] carries the warning; this is only the name.
 */
const val NONE_DISPLAY_NAME = "no sign-in at all"

/** Spring's authorization endpoint for the registration this application configures. */
const val OIDC_REGISTRATION_ID = "orknux"

const val OIDC_AUTHORIZE_PATH = "/oauth2/authorization/$OIDC_REGISTRATION_ID"
