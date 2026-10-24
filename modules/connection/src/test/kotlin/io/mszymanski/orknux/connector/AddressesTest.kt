package io.mszymanski.orknux.connector

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An address typed without a scheme is taken as http://, and nothing else is touched. */
class AddressesTest {

    @Test
    fun `an address with no scheme is taken as http`() {
        assertThat(Addresses.withScheme("192.168.0.10:8081")).isEqualTo("http://192.168.0.10:8081")
        assertThat(Addresses.withScheme("  orkbox:8080/v1 ")).isEqualTo("http://orkbox:8080/v1")
        assertThat(Addresses.withScheme("localhost")).isEqualTo("http://localhost")
    }

    @Test
    fun `an address with a scheme is left as written, whatever the scheme`() {
        assertThat(Addresses.withScheme("https://example.com")).isEqualTo("https://example.com")
        assertThat(Addresses.withScheme(" http://10.0.0.1:80 ")).isEqualTo("http://10.0.0.1:80")
        assertThat(Addresses.withScheme("HTTPS://Example.com")).isEqualTo("HTTPS://Example.com")
        assertThat(Addresses.withScheme("ldaps://directory:636")).isEqualTo("ldaps://directory:636")
        assertThat(Addresses.withScheme("wss://socket.example")).isEqualTo("wss://socket.example")
    }

    @Test
    fun `nothing typed stays nothing, so a required field is still refused`() {
        assertThat(Addresses.withScheme("")).isEmpty()
        assertThat(Addresses.withScheme("   ")).isEmpty()
    }
}
