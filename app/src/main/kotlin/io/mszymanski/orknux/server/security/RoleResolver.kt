package io.mszymanski.orknux.server.security

import org.springframework.stereotype.Service

/**
 * Turns what the identity provider says about somebody into this installation's roles.
 *
 * This is the one place a provider's vocabulary is translated. LDAP hands over group
 * memberships as authorities; an OIDC token carries claim values; neither means
 * anything to the rest of the application, which only ever deals in roles.
 *
 * Two ways a role is granted, in this order:
 *
 * 1. **Configured.** `orknux.security.role-mapping` says which provider value grants
 *    which role — `"cn=backend,ou=groups,dc=orknux,dc=io": Backend`. That is the way
 *    to set it up, and the only way that works when the provider's names and this
 *    installation's names are different, which they usually are.
 *
 * 2. **By name.** Failing a mapping, a role is granted to anyone holding an authority
 *    derived from its own name the way the LDAP populator derives one from a group's
 *    common name: `Backend` is granted by `ROLE_BACKEND`.
 *
 * The second exists so that upgrading changes nothing. Before roles, a workspace
 * named a group and the check derived `ROLE_<CN>` from it; the migration turned each
 * group into a role of the same name, so the same authority still opens the same
 * workspace with no configuration written. Configure a mapping and it takes over.
 */
@Service
class RoleResolver(
    private val roles: RoleRepository,
    private val properties: SecurityProperties,
) {

    /**
     * The roles this caller holds, given the authorities they arrived with.
     *
     * Everything is compared case-insensitively and without the provider's
     * decoration: a directory that answers `ROLE_BACKEND` and a token that says
     * `backend` are saying the same thing, and an installation should not have to
     * care which one it is talking to.
     */
    fun rolesFor(authorities: Set<String>): Set<Role> {
        if (authorities.isEmpty()) return emptySet()

        val held = authorities.map { it.trim().lowercase() }.toSet()
        val mapped = properties.roleMapping
            .filterKeys { key -> matches(key, held) }
            .values
            .mapNotNull { name -> roles.findByName(name.trim()) }

        /*
         * Its own name, and any directory name an administrator wrote on it.
         *
         * The second is the same decision the configured mapping makes, moved to
         * where somebody can make it: a group called `dev.TL` or `BoarCMS Group`
         * can be named on the role rather than in a file nobody administering
         * this installation can reach.
         */
        val byName = roles.findAll().filter { role ->
            authoritiesOf(role.name).any { it in held } ||
                role.matches.any { matches(it, held) }
        }

        return (mapped + byName).toSet()
    }

    /**
     * Whether the caller holds what this name names, however either is spelled.
     *
     * Used for both kinds of mapping - the one in the configuration file and the
     * one written on the role - because they are the same decision and somebody
     * moving from one to the other should not find the spelling rules changed
     * underneath them.
     */
    private fun matches(key: String, held: Set<String>): Boolean {
        val wanted = key.trim().lowercase()
        if (wanted in held) return true
        // A mapping written as the full group DN matches the authority the LDAP
        // populator derives from it, so both spellings work and neither is wrong.
        return authoritiesOf(commonName(key)).any { it in held }
    }

    /**
     * Whether these authorities administer, which does not depend on any workspace.
     *
     * The configured admin authority still counts on its own. An installation that
     * has always granted `ROLE_ADMINS` should not need a mapping written before
     * anybody can reach the Admin section again — least of all the person who would
     * have to write it.
     */
    fun administers(authorities: Set<String>): Boolean {
        if (properties.adminRole.lowercase() in authorities.map { it.trim().lowercase() }) return true
        return rolesFor(authorities).any { it.administers }
    }

    /**
     * What a group of this name would arrive as, lowercased, in both spellings.
     *
     * There are two because the directory and this application disagree about
     * punctuation, and for years the disagreement was invisible - every group
     * anybody named was one word.
     *
     * Spring's populator writes `ROLE_` and the common name uppercased and
     * otherwise untouched, so `dev.TL` arrives as `ROLE_DEV.TL` and
     * `BoarCMS Group` as `ROLE_BOARCMS GROUP`. This end has always replaced
     * anything that is not a letter or a digit with an underscore, which is
     * where `ROLE_DEV_TL` comes from. A role named after such a group therefore
     * matched nothing at all, and no screen could say why: the group was found,
     * the authority was held, and the name looked right to anybody reading it.
     *
     * Both are accepted rather than the old one being replaced. Installations
     * have roles named for the sanitised form - that is what the migration into
     * roles produced from the groups workspaces used to name - and taking it
     * away would sign people out of their own workspaces.
     */
    private fun authoritiesOf(name: String): Set<String> {
        val trimmed = name.trim()
        return setOf(
            ROLE_PREFIX + trimmed.replace(NON_ROLE_CHARACTERS, "_"),
            ROLE_PREFIX + trimmed,
        ).map { it.lowercase() }.toSet()
    }

    /** "cn=backend,ou=groups,dc=orknux,dc=io" -> "backend"; a bare name is left alone. */
    private fun commonName(value: String): String {
        val relative = value.split(',').firstOrNull()?.trim().orEmpty()
        return if (relative.startsWith("cn=", ignoreCase = true)) relative.substring(3).trim() else relative
    }

    private companion object {
        const val ROLE_PREFIX = "ROLE_"
        val NON_ROLE_CHARACTERS = Regex("[^A-Za-z0-9]+")
    }
}
