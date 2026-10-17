package io.mszymanski.orknux.server.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * The desktop app's server is one person at their machine only when all three
 * hold: the desktop flag, no sign-in, and a loopback address. Any one missing
 * keeps the open-access warning, so the flag can never quiet it on a server
 * somebody else can reach.
 */
class DesktopModeTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(AuthMethodBindingTest.BoundSecurity::class.java, DesktopMode::class.java)

    private fun localOnly(vararg properties: String): Boolean {
        var held = false
        runner.withPropertyValues(*properties).run { context -> held = context.getBean(DesktopMode::class.java).localOnly }
        return held
    }

    @Test
    fun `the desktop flag, no sign-in and a loopback address make it one person's`() {
        assertThat(localOnly("orknux.security.desktop=true", "orknux.security.auth-method=NONE", "server.address=127.0.0.1")).isTrue()
        assertThat(localOnly("orknux.security.desktop=true", "orknux.security.auth-method=NONE", "server.address=::1")).isTrue()
    }

    @Test
    fun `any one of the three missing keeps the warning`() {
        // Not the desktop app.
        assertThat(localOnly("orknux.security.auth-method=NONE", "server.address=127.0.0.1")).isFalse()
        // Exposed on the network, or bound everywhere.
        assertThat(localOnly("orknux.security.desktop=true", "orknux.security.auth-method=NONE", "server.address=0.0.0.0")).isFalse()
        assertThat(localOnly("orknux.security.desktop=true", "orknux.security.auth-method=NONE")).isFalse()
        // Asking people to sign in, where there is no warning to quiet anyway.
        assertThat(localOnly("orknux.security.desktop=true", "orknux.security.auth-method=INTERNAL", "server.address=127.0.0.1")).isFalse()
    }
}
