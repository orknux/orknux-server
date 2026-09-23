package io.mszymanski.orknux.server.ldap

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.ldap.core.support.BaseLdapPathContextSource
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.ldap.LdapBindAuthenticationManagerFactory
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator
import org.springframework.security.ldap.userdetails.InetOrgPersonContextMapper
import org.springframework.security.ldap.userdetails.LdapAuthoritiesPopulator

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LdapProperties::class)
class LdapAuthenticationConfig {

    /**
     * The directory connection, built here rather than by Spring Boot.
     *
     * Everything below the first line is what Boot's own auto-configuration
     * does, and it is copied rather than customised because there is no hook to
     * customise: the one thing that has to be added - which socket factory LDAPS
     * connections are made with - can only be set before the source is
     * initialised, and Boot hands it back already built. Boot stands aside
     * because it makes this bean only where there is not one already.
     *
     * The addition is [TrustedLdapSocketFactory], and only for an all-LDAPS
     * directory: it makes the connection trust what the trusted certificates
     * screen says this installation trusts, which until now reached everything
     * outbound *except* the directory - so an administrator could paste their
     * company's certificate authority into the product and still not sign in
     * against their own domain controllers.
     */
    @Bean
    fun ldapContextSource(
        connection: org.springframework.boot.ldap.autoconfigure.LdapConnectionDetails,
        boot: org.springframework.boot.ldap.autoconfigure.LdapProperties,
        strategy: org.springframework.beans.factory.ObjectProvider<
            org.springframework.ldap.core.support.DirContextAuthenticationStrategy,
            >,
    ): org.springframework.ldap.core.support.LdapContextSource =
        org.springframework.ldap.core.support.LdapContextSource().apply {
            strategy.ifUnique(::setAuthenticationStrategy)
            connection.username?.let(::setUserDn)
            connection.password?.let(::setPassword)
            boot.anonymousReadOnly?.let(::setAnonymousReadOnly)
            boot.referral?.let { setReferral(it.name.lowercase()) }
            connection.base?.let(::setBase)
            connection.urls?.let(::setUrls)

            val urls = connection.urls?.toList().orEmpty()
            setBaseEnvironmentProperties(
                if (TrustedLdapSocketFactory.wantedFor(urls)) {
                    boot.baseEnvironment +
                        (TrustedLdapSocketFactory.JNDI_SOCKET_FACTORY to TrustedLdapSocketFactory::class.java.name)
                } else {
                    // Never on a plain connection: the property says how to make
                    // the socket, not how to do TLS, so a `ldap://` connection
                    // would open a handshake on a port that answers none.
                    boot.baseEnvironment
                },
            )
        }

    @Bean
    fun ldapAuthoritiesPopulator(
        contextSource: BaseLdapPathContextSource,
        properties: LdapProperties,
    ): LdapAuthoritiesPopulator =
        DefaultLdapAuthoritiesPopulator(contextSource, properties.groupSearchBase).apply {
            setGroupSearchFilter(properties.groupSearchFilter)
            /*
             * One level by default, which is Spring's own and what this has
             * always done - see [LdapProperties.groupSearchSubtree] for why it
             * is a trap worth a setting, and why the setting is off.
             */
            setSearchSubtree(properties.groupSearchSubtree)
        }

    /**
     * The directory door, and — since it is built here rather than by Spring —
     * the announcement that somebody came through it.
     *
     * `createAuthenticationManager()` hands back a bare [ProviderManager]. Spring
     * Boot puts an event publisher on the manager it assembles itself, through
     * `AuthenticationManagerBuilder`, and a manager constructed directly is not
     * that manager: it keeps the null publisher a `ProviderManager` starts with
     * and every successful bind is silent. That silence is not a detail. It is
     * the only signal an LDAP sign-in gives off — `UserDetection` listens for it
     * to write the person down — so for as long as it was missing, an
     * installation signing in against a directory recorded nobody, and the Users
     * page could only ever list the accounts it had made itself.
     *
     * The publisher is given the context's own [ApplicationEventPublisher] so the
     * events land in the same place every other event in this application does.
     */
    @Bean
    fun authenticationManager(
        contextSource: BaseLdapPathContextSource,
        authoritiesPopulator: LdapAuthoritiesPopulator,
        properties: LdapProperties,
        events: ApplicationEventPublisher,
    ): AuthenticationManager {
        val manager = LdapBindAuthenticationManagerFactory(contextSource).apply {
            setUserSearchBase(properties.userSearchBase)
            setUserSearchFilter(properties.userSearchFilter)
            setLdapAuthoritiesPopulator(authoritiesPopulator)
            // Keeps the inetOrgPerson attributes, mail among them, on the principal.
            setUserDetailsContextMapper(InetOrgPersonContextMapper())
        }.createAuthenticationManager()

        /*
         * Asserted rather than assumed. The factory's return type says only
         * `AuthenticationManager`, and a silent `as?` that missed would put the
         * bug back exactly as it was — invisible, and only findable by noticing
         * that a page is empty. Failing at startup is the loud version.
         */
        check(manager is ProviderManager) {
            "LdapBindAuthenticationManagerFactory no longer builds a ProviderManager, so a sign-in " +
                "against the directory can no longer be published. See UserDetection."
        }
        manager.setAuthenticationEventPublisher(DefaultAuthenticationEventPublisher(events))
        return manager
    }
}
