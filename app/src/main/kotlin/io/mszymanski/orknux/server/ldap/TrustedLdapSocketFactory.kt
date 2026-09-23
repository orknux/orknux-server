package io.mszymanski.orknux.server.ldap

import io.mszymanski.orknux.connector.proxy.OutboundTrust
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Makes LDAPS connections trust what the rest of this installation trusts.
 *
 * ### The gap this closes
 *
 * An administrator who pastes their internal certificate authority into the
 * trusted certificates screen is telling this installation to trust it. Until
 * now that reached everything built through `ProxyRouter` - connections, MCP
 * servers, plugins, the marketplace - and did not reach the directory, because
 * the directory is spoken to over JNDI and JNDI uses the JVM's own truststore
 * and nothing else.
 *
 * So an installation whose domain controllers present a certificate from the
 * company's own authority could not sign anybody in, the screen that exists to
 * fix exactly that did nothing, and the failure looked like "invalid username or
 * password" to every person in the company. The fix was to get a PEM into the
 * JVM's cacerts inside a container, which is not a thing an administrator should
 * have to do twice.
 *
 * ### How JNDI is told
 *
 * By class name, in `java.naming.ldap.factory.socket`, which the provider loads
 * reflectively and asks for a socket. That is why the trust arrives through a
 * static field rather than a constructor: nothing Spring builds is reachable
 * from a class name, and JNDI will not be handed an instance.
 *
 * It is read on every connection rather than captured once, so a certificate
 * added on the screen reaches the next sign-in without a restart - which is the
 * behaviour the screen already promises everywhere else.
 *
 * ### What it does not change
 *
 * The context this asks for is the JVM's own roots **plus** whatever was pasted,
 * never instead of them, and null where nothing was - in which case this hands
 * back the same factory JNDI would have used on its own. Hostname checking is
 * set on every socket here rather than assumed, because a custom socket factory
 * is exactly the situation where a provider might not set it, and a certificate
 * for another host is not a certificate for this one.
 */
class TrustedLdapSocketFactory : SSLSocketFactory() {

    /**
     * What to make sockets with right now; see the note about reading it late.
     *
     * The fallback is spelled out in full because this class has a `getDefault`
     * of its own, and the short form would ask itself.
     */
    internal fun delegate(): SSLSocketFactory =
        trust.get()?.context()?.socketFactory ?: javax.net.ssl.SSLSocketFactory.getDefault() as SSLSocketFactory

    /**
     * Hostname checking, set here rather than relied upon.
     *
     * JNDI sets it on the sockets it creates itself. A socket from somebody
     * else's factory is the case where that is easiest to lose, and losing it
     * means any certificate this installation trusts is accepted for any host -
     * which is most of the value of checking the certificate at all.
     */
    private fun checked(socket: Socket): Socket {
        if (socket is SSLSocket) {
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "LDAPS" }
        }
        return socket
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate().defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate().supportedCipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        checked(delegate().createSocket(socket, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        checked(delegate().createSocket(host, port))

    override fun createSocket(host: String, port: Int, from: InetAddress, fromPort: Int): Socket =
        checked(delegate().createSocket(host, port, from, fromPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        checked(delegate().createSocket(host, port))

    override fun createSocket(host: InetAddress, port: Int, from: InetAddress, fromPort: Int): Socket =
        checked(delegate().createSocket(host, port, from, fromPort))

    companion object {

        /**
         * What this installation trusts, put here by [TrustedLdapSockets].
         *
         * Static because JNDI is handed a class name and constructs the factory
         * itself. Null until the application has started, which is before
         * anything can sign in.
         */
        private val trust = AtomicReference<OutboundTrust?>(null)

        /**
         * What JNDI calls, reflectively, to get a factory.
         *
         * Returns this type rather than `SocketFactory`, which is not a
         * decoration: `SSLSocketFactory` has a static `getDefault` of its own and
         * the two would be the same method to the JVM, which Kotlin refuses. A
         * narrower return type makes them different methods, and reflection
         * finds the one declared here first - which is the one JNDI has to get.
         */
        @JvmStatic
        fun getDefault(): TrustedLdapSocketFactory = TrustedLdapSocketFactory()

        internal fun trustWith(outbound: OutboundTrust?) = trust.set(outbound)

        /**
         * Whether these URLs are ones this factory should be used for.
         *
         * Only where every one of them is LDAPS. The socket factory property is
         * not "how to do TLS", it is "how to make the socket" - hand it to a
         * plain `ldap://` connection and the server gets a TLS handshake on port
         * 389 and hangs up, which is a worse fault than the one being fixed.
         *
         * All rather than any, for the same reason: a list half of which is
         * plain would be half broken either way, and this is not the place to
         * decide that a directory is misconfigured.
         */
        internal fun wantedFor(urls: List<String>): Boolean =
            urls.isNotEmpty() && urls.all { it.trim().startsWith("ldaps://", ignoreCase = true) }

        /** What JNDI reads the class name from. */
        const val JNDI_SOCKET_FACTORY = "java.naming.ldap.factory.socket"
    }
}

/**
 * Hands the installation's trust to [TrustedLdapSocketFactory].
 *
 * A bean whose whole job is to cross from Spring into a static field, because
 * JNDI constructs the factory from a class name and there is no other way in.
 * Kept apart from the factory so the factory stays a factory.
 */
@Component
class TrustedLdapSockets(trust: OutboundTrust) {
    init {
        TrustedLdapSocketFactory.trustWith(trust)
    }
}
