package io.mszymanski.orknux.server.ldap

import io.mszymanski.orknux.connector.proxy.OutboundTrust
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ldap.autoconfigure.LdapConnectionDetails
import org.springframework.ldap.core.support.AbstractContextSource
import org.springframework.ldap.core.support.DirContextAuthenticationStrategy
import java.security.KeyStore
import java.util.Hashtable
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * LDAPS connections trusting what the rest of the installation trusts.
 *
 * An administrator who pastes their company's certificate authority into the
 * trusted certificates screen is telling this installation to trust it. That
 * reached everything built through `ProxyRouter` and did not reach the
 * directory, because the directory is spoken to over JNDI and JNDI reads the
 * JVM's truststore and nothing else - so an installation whose domain
 * controllers present an internal certificate could not sign anybody in, and the
 * screen that exists to fix exactly that did nothing.
 *
 * Two halves are measured, because either alone would pass while the thing
 * stayed broken: that the connection is *told* to use the factory, and that the
 * factory hands back the installation's own trust.
 */
class TrustedLdapSocketsTest {

    @AfterEach
    fun forget() = TrustedLdapSocketFactory.trustWith(null)

    /* --------------------------------------------- when it is asked for ---- */

    @Test
    fun `an all-LDAPS directory wants it`() {
        assertThat(
            TrustedLdapSocketFactory.wantedFor(
                listOf("ldaps://dc6.example.invalid:636", "ldaps://dc7.example.invalid:636"),
            ),
        ).isTrue()
    }

    /**
     * The property says how to make the socket, not how to do TLS. Handed to a
     * plain connection it opens a handshake on a port that answers none, which
     * is a worse fault than the one this fixes.
     */
    @Test
    fun `a plain directory does not`() {
        assertThat(TrustedLdapSocketFactory.wantedFor(listOf("ldap://dc6.example.invalid:389"))).isFalse()
    }

    @Test
    fun `and neither does a list that is only half of them`() {
        assertThat(
            TrustedLdapSocketFactory.wantedFor(
                listOf("ldaps://dc6.example.invalid:636", "ldap://dc7.example.invalid:389"),
            ),
        ).isFalse()
    }

    @Test
    fun `a directory that named no urls at all does not`() {
        assertThat(TrustedLdapSocketFactory.wantedFor(emptyList())).isFalse()
    }

    @Test
    fun `however it is spelled and spaced`() {
        assertThat(TrustedLdapSocketFactory.wantedFor(listOf("  LDAPS://dc6.example.invalid:636 "))).isTrue()
    }

    /* ------------------------------------------- that it is actually set --- */

    private fun sourceFor(vararg urls: String): AbstractContextSource =
        LdapAuthenticationConfig().ldapContextSource(
            connection = object : LdapConnectionDetails {
                override fun getUrls(): Array<String> = arrayOf(*urls)
                override fun getBase(): String = "dc=example,dc=invalid"
            },
            boot = org.springframework.boot.ldap.autoconfigure.LdapProperties(),
            strategy = object : ObjectProvider<DirContextAuthenticationStrategy> {
                override fun getObject(vararg args: Any?): DirContextAuthenticationStrategy = error("none")
                override fun getObject(): DirContextAuthenticationStrategy = error("none")
                override fun getIfAvailable(): DirContextAuthenticationStrategy? = null
                override fun getIfUnique(): DirContextAuthenticationStrategy? = null
            },
        )

    /**
     * Read by reflection, deliberately.
     *
     * What the connection was told is private to Spring's context source and
     * there is no getter. The alternative is not testing the one thing that
     * decides whether any of this runs - a factory that is never named is a
     * factory that is never called, and nothing else here would notice.
     */
    @Suppress("UNCHECKED_CAST")
    private fun environmentOf(source: AbstractContextSource): Map<String, Any> =
        AbstractContextSource::class.java.getDeclaredField("baseEnv")
            .apply { isAccessible = true }
            .get(source)
            .let { it as Hashtable<String, Any> }
            .toMap()

    @Test
    fun `an LDAPS directory is told to make its sockets with ours`() {
        val told = environmentOf(sourceFor("ldaps://dc6.example.invalid:636"))

        assertThat(told[TrustedLdapSocketFactory.JNDI_SOCKET_FACTORY])
            .isEqualTo(TrustedLdapSocketFactory::class.java.name)
    }

    @Test
    fun `a plain one is told nothing of the sort`() {
        val told = environmentOf(sourceFor("ldap://dc6.example.invalid:389"))

        assertThat(told).doesNotContainKey(TrustedLdapSocketFactory.JNDI_SOCKET_FACTORY)
    }

    /* ------------------------------------------------- what it hands back -- */

    /**
     * JNDI is given a class name and builds the factory itself, so this is the
     * call it makes. It has to come back as something a socket can be made from.
     */
    @Test
    fun `JNDI gets a factory from the class name it was given`() {
        val made = Class.forName(TrustedLdapSocketFactory::class.java.name)
            .asSubclass(javax.net.SocketFactory::class.java)
            .getMethod("getDefault")
            .invoke(null)

        assertThat(made).isInstanceOf(javax.net.SocketFactory::class.java)
    }

    /**
     * Which context a factory came from, asked of what it makes.
     *
     * Not by identity: `SSLContext.getSocketFactory` and
     * `SSLSocketFactory.getDefault` both hand back a fresh instance every call,
     * so two references to the same trust are never the same object. What a
     * socket is allowed to speak comes from the context that made it, which is
     * the difference that can actually be seen from here.
     */
    private fun protocolsOf(factory: javax.net.ssl.SSLSocketFactory): List<String> =
        (factory.createSocket() as javax.net.ssl.SSLSocket).use { it.enabledProtocols.toList() }

    /** A context nothing else would produce, so it can be told apart. */
    private fun onlyOldTls() = SSLContext.getInstance("TLSv1.2").apply { init(null, jvmRoots(), null) }

    @Test
    fun `with nothing added, it is the JVM's own factory`() {
        TrustedLdapSocketFactory.trustWith(OutboundTrust { null })

        assertThat(protocolsOf(TrustedLdapSocketFactory().delegate()))
            .isEqualTo(protocolsOf(javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory))
    }

    @Test
    fun `with an authority added, it is the one built from it`() {
        val installation = onlyOldTls()
        TrustedLdapSocketFactory.trustWith(OutboundTrust { installation })

        assertThat(protocolsOf(TrustedLdapSocketFactory().delegate())).isEqualTo(listOf("TLSv1.2"))
        // And that is not what the JVM would have given on its own, which is the
        // whole of what this test is saying.
        assertThat(protocolsOf(javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory))
            .isNotEqualTo(listOf("TLSv1.2"))
    }

    /**
     * Read late rather than captured, so a certificate pasted on the screen
     * reaches the next sign-in without a restart - which is what the screen
     * already promises everywhere else.
     */
    @Test
    fun `and an authority added later is used without anything being rebuilt`() {
        val factory = TrustedLdapSocketFactory()
        TrustedLdapSocketFactory.trustWith(OutboundTrust { null })
        assertThat(protocolsOf(factory.delegate())).isNotEqualTo(listOf("TLSv1.2"))

        TrustedLdapSocketFactory.trustWith(OutboundTrust { onlyOldTls() })

        assertThat(protocolsOf(factory.delegate())).isEqualTo(listOf("TLSv1.2"))
    }

    /** The roots the JVM came with, which is what an empty list resolves to. */
    private fun jvmRoots() = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
}
