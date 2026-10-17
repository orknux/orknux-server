package io.mszymanski.orknux.server.user

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.ldap.userdetails.InetOrgPerson
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Writes down who signs in.
 *
 * The provider knows its people; this installation only meets them one at a
 * time, at the door. Each successful sign-in upserts an EXTERNAL row, which is
 * how the Users page has anything external to list at all — there is no
 * directory query behind it, deliberately: an LDAP tree or an OIDC issuer is
 * not ours to enumerate, and the people who matter here are the ones who have
 * actually been here.
 *
 * **Which doors announce an arrival, and which deliberately do not.** This used
 * to claim Spring published the event for every path, which was never true and
 * was expensive to believe: the listener was correct from the day it was
 * written and recorded nobody, because the one door most installations use said
 * nothing when somebody came through it.
 *
 * - **The directory.** [io.mszymanski.orknux.server.ldap.LdapAuthenticationConfig]
 *   builds its manager itself, so it has to put the publisher on itself. That is
 *   the fix; the comment there says why it was missing.
 * - **OIDC**, both ways in — the browser flow and a bearer token. Spring
 *   Security registers those providers on the manager it assembles for the
 *   filter chain, and that one carries a publisher already.
 * - **A password this installation holds.** No event, and nothing to record: an
 *   internal user is checked against a row in this very table, so they cannot
 *   sign in without already being written down. Announcing them would only mean
 *   looking them up to find nothing had changed.
 * - **An API token.** No event, and that is the intent rather than an omission.
 *   A token is a key its owner left somewhere, not a person arriving; it is
 *   minted for a user who is a row already, and it is presented on every single
 *   request by things that keep no session.
 */
@Component
class UserDetection(
    private val users: AppUserRepository,
    /** What the arriving authorities mean here, written down beside the person. */
    private val resolver: io.mszymanski.orknux.server.security.RoleResolver,
) {

    @EventListener
    @Transactional
    fun onSignIn(event: AuthenticationSuccessEvent) {
        val authentication = event.authentication
        val username = authentication.name?.takeIf { it.isNotBlank() } ?: return

        /*
         * The OIDC principal often carries a human name; LDAP's rarely does.
         * Taken when offered, kept when not: a display name somebody saw once
         * should not turn back into a login the next time they arrive.
         */
        val principal = authentication.principal
        val called = when (principal) {
            is OidcUser -> principal.fullName?.takeIf { it.isNotBlank() }
            is UserDetails -> null
            else -> null
        }

        /*
         * Both doors carry an address. LDAP's is the inetOrgPerson mail
         * attribute, which the context mapper keeps on the principal; OIDC's is
         * the email claim. Same field, so it is read here once rather than
         * twice in two configurations.
         */
        val addressed = when (principal) {
            is OidcUser -> principal.email?.takeIf { it.isNotBlank() }
            is InetOrgPerson -> principal.mail?.takeIf { it.isNotBlank() }
            else -> null
        }

        /*
         * The roles their groups resolve to, kept by id so a workspace can say
         * who its roles let in. Resolved again on every sign-in, because a group
         * membership changes in the directory and nothing here is told.
         */
        val authorities = authentication.authorities.mapNotNull { it.authority }.toSet()
        val resolved = resolver.rolesFor(authorities).mapNotNull { it.id }.sorted().joinToString(",").ifEmpty { null }
        val administers = resolver.administers(authorities)

        val held = users.findByUsername(username)
        if (held == null) {
            users.save(
                AppUser(
                    username = username,
                    displayName = called ?: username,
                    type = UserType.EXTERNAL,
                    email = addressed,
                    resolvedRoles = resolved,
                    resolvedAdmin = administers,
                ),
            )
            log.info("First sign-in recorded for {}", username)
            return
        }

        var changed = false
        if (called != null && held.displayName != called) {
            held.displayName = called
            changed = true
        }
        /*
         * The directory refreshes an address it gave, and leaves alone one
         * somebody typed. Otherwise every sign-in would undo the edit, and the
         * option to set an address here would last exactly until the next
         * morning.
         */
        if (!held.emailChosen && addressed != null && held.email != addressed) {
            held.email = addressed
            changed = true
        }
        if (held.resolvedRoles != resolved || held.resolvedAdmin != administers) {
            held.resolvedRoles = resolved
            held.resolvedAdmin = administers
            changed = true
        }
        if (changed) users.save(held)
    }

    private companion object {
        val log = LoggerFactory.getLogger(UserDetection::class.java)
    }
}
