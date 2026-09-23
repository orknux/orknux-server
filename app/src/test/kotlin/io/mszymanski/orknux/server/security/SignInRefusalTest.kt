package io.mszymanski.orknux.server.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ldap.CommunicationException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.InternalAuthenticationServiceException

/**
 * Why a sign-in was refused, which the person at the door is not told.
 *
 * The sentence a caller gets is one sentence on purpose: saying that the
 * username exists and only the password was wrong tells an attacker which half
 * to keep. What that cost was the log - an installation whose bind DN was
 * mistyped told every person in the company "Invalid username or password" and
 * told the person who could fix it nothing at all, because Spring hands back one
 * exception type for two entirely different situations.
 *
 * A user search that matched nothing becomes `BadCredentialsException`,
 * deliberately, and so does a wrong password. Everything underneath - the host
 * unreachable, a mistyped bind DN, a base that does not exist, TLS refused -
 * arrives as [InternalAuthenticationServiceException]. The first is somebody
 * getting their password wrong; the second is an installation that cannot sign
 * anybody in.
 *
 * Plain unit test: the classification is a decision about an exception, and
 * standing a directory up to produce each kind would test Spring rather than
 * this.
 */
class SignInRefusalTest {

    @Test
    fun `a directory that cannot answer is not a wrong password`() {
        val cause = InternalAuthenticationServiceException(
            "orknux.io:389",
            CommunicationException(javax.naming.CommunicationException("Connection refused")),
        )

        assertThat(SessionAPI.refusalFor(cause)).isEqualTo(SessionAPI.Refusal.DIRECTORY)
    }

    /**
     * The one that used to be a 500.
     *
     * The groups are read after the password has been checked, outside the part
     * of Spring that turns directory failures into authentication ones - so a
     * group search base that does not exist came back as a bare naming error,
     * nothing caught it, and somebody whose password was right got a 500 with no
     * explanation anywhere.
     */
    @Test
    fun `a group search that could not run is the directory, not the password`() {
        val cause = org.springframework.ldap.NameNotFoundException(
            javax.naming.NameNotFoundException("OU=Grupy,OU=Nowhere"),
        )

        assertThat(SessionAPI.refusalFor(cause)).isEqualTo(SessionAPI.Refusal.DIRECTORY)
    }

    @Test
    fun `a refusal the directory gave is credentials, whichever half was wrong`() {
        assertThat(SessionAPI.refusalFor(BadCredentialsException("Bad credentials")))
            .isEqualTo(SessionAPI.Refusal.CREDENTIALS)
    }

    /**
     * Because the useful sentence is at the bottom of the chain: the wrapper
     * carries a host and port, and the JNDI exception underneath carries what
     * actually happened to the connection.
     */
    @Test
    fun `what is logged names the exception and what caused it`() {
        val said = SessionAPI.describe(
            InternalAuthenticationServiceException(
                "orknux.io:389",
                CommunicationException(javax.naming.CommunicationException("Connection refused")),
            ),
        )

        assertThat(said).contains("InternalAuthenticationServiceException")
        assertThat(said).contains("orknux.io:389")
        assertThat(said).contains("Connection refused")
    }

    @Test
    fun `an exception that caused itself nothing is said plainly`() {
        val said = SessionAPI.describe(BadCredentialsException("Bad credentials"))

        assertThat(said).isEqualTo("BadCredentialsException: Bad credentials")
        assertThat(said).doesNotContain("caused by")
    }

    /**
     * A cause chain that loops would otherwise hang the one thread somebody is
     * waiting on to read a log line.
     */
    @Test
    fun `a cause chain that circles back is followed only so far`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        // Whichever way round, the walk has to stop of its own accord.
        first.initCause(second)

        assertThat(SessionAPI.describe(second)).contains("second")
    }
}
