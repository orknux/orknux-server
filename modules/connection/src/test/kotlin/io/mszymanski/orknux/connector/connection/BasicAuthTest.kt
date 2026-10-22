package io.mszymanski.orknux.connector.connection

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

/** Basic auth: `username:password` typed plainly is encoded once, and one already encoded is not encoded again. */
class BasicAuthTest {

    private fun sent(secret: String): String? =
        ConnectionTarget("http://mcp.invalid", AuthType.BASIC, secret, emptyList()).requestHeaders().toMap()["Authorization"]

    private val encoded = Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())

    @Test
    fun `username and password typed plainly are encoded`() {
        assertThat(sent("alice:s3cret")).isEqualTo("Basic $encoded")
    }

    @Test
    fun `a secret already encoded is sent as it is`() {
        assertThat(sent(encoded)).isEqualTo("Basic $encoded")
    }

    @Test
    fun `a secret that is neither is encoded as it stands`() {
        assertThat(sent("token-without-colon")).isEqualTo("Basic " + Base64.getEncoder().encodeToString("token-without-colon".toByteArray()))
    }
}
