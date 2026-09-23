package io.mszymanski.orknux.server.ldap

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "orknux.ldap")
data class LdapProperties(
    val userSearchBase: String = "ou=people",
    val userSearchFilter: String = "(uid={0})",
    val groupSearchBase: String = "ou=groups",
    val groupSearchFilter: String = "(member={0})",

    /**
     * Whether groups below [groupSearchBase] count, or only those directly in it.
     *
     * The user search has always looked at the whole subtree and this one has
     * always looked one level down, which is Spring's default and was never a
     * decision anybody here made. It is a trap in a directory that files its
     * groups by department: the search runs, finds nothing, and an installation
     * where everybody signs in successfully shows every one of them an empty
     * product. Nothing says why, because nothing failed.
     *
     * Off by default all the same. Turning it on can only ever grant more roles
     * than it did yesterday - two groups of the same name in different OUs are
     * one role here - and a switch that decides who gets in defaults to the
     * narrow answer. Set it where the groups are not all in one place.
     */
    val groupSearchSubtree: Boolean = false,
)
