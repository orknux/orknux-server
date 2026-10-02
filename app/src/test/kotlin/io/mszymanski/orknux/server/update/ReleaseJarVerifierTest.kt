package io.mszymanski.orknux.server.update

import io.mszymanski.orknux.server.update.TestReleaseJars.Shape
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * What a server jar has to be before it is stored or run. Issue #584.
 *
 * Every jar here is built and signed in the test with keys made for it, so each
 * refusal is the verifier's answer to a real signature rather than to a stub of
 * one.
 */
class ReleaseJarVerifierTest {

    private val verifier = TestReleaseJars.verifier()

    @Test
    fun `a release signed with the trusted key is accepted, and says what it is`() {
        val verified = verifier.verify(TestReleaseJars.signed(Shape(version = "1.2.3", schemaVersion = 340, schemaFloor = 335)))

        assertThat(verified).isEqualTo(VerifiedReleaseJar("1.2.3", 340, 335))
    }

    @Test
    fun `an unsigned jar is refused`() {
        assertThatThrownBy { verifier.verify(TestReleaseJars.unsigned()) }
            .isInstanceOf(ReleaseJarRefusedException::class.java)
            .hasMessage("This jar cannot be used: it is not signed.")
    }

    @Test
    fun `a jar changed after it was signed is refused`() {
        val tampered = TestReleaseJars.temporary(TestReleaseJars.tampered(TestReleaseJars.signed()))

        assertThatThrownBy { verifier.verify(tampered) }
            .isInstanceOf(ReleaseJarRefusedException::class.java)
            .hasMessageContaining("OrknuxServerKt.class was changed after the jar was signed")
    }

    @Test
    fun `a jar with an entry nobody signed is refused`() {
        val extended = TestReleaseJars.withUnsignedEntry(TestReleaseJars.signed())

        assertThatThrownBy { verifier.verify(extended) }
            .hasMessageContaining("Extra.class is not covered by its signature")
    }

    @Test
    fun `a jar signed with another key is refused`() {
        assertThatThrownBy { verifier.verify(TestReleaseJars.signed(by = TestReleaseJars.other)) }
            .hasMessage("This jar cannot be used: it is signed, but not with the Orknux release key.")
    }

    @Test
    fun `a signed jar that does not start Orknux is refused`() {
        assertThatThrownBy { verifier.verify(TestReleaseJars.signed(Shape(startClass = null))) }
            .hasMessageContaining("its Start-Class is not ${ReleaseJarVerifier.START_CLASS}")
        assertThatThrownBy { verifier.verify(TestReleaseJars.signed(Shape(startClass = "com.example.Other"))) }
            .hasMessageContaining("Start-Class")
    }

    @Test
    fun `a signed jar without the interface is refused`() {
        assertThatThrownBy { verifier.verify(TestReleaseJars.signed(Shape(withInterface = false))) }
            .hasMessage("This jar cannot be used: it carries no interface.")
    }

    @Test
    fun `a signed jar without a readable version or rollback floor is refused`() {
        assertThatThrownBy { verifier.verify(TestReleaseJars.signed(Shape(version = "latest"))) }
            .hasMessageContaining("no version")
        assertThatThrownBy { verifier.verify(TestReleaseJars.signed(Shape(schemaFloor = null))) }
            .hasMessageContaining("rolled back to")
    }

    /**
     * A release this server could be moved onto and never update back from,
     * without an image change. Refused here, so from every source and for the
     * launcher too.
     */
    @Test
    fun `a release older than in-place updates is refused, by its number or by a missing launcher`() {
        val reason = "This jar cannot be used: it predates in-place updates (0.9.9.8), so this server could not update back from it."
        assertThatThrownBy { verifier.verify(TestReleaseJars.signed(Shape(version = "0.9.9.7"))) }.hasMessage(reason)
        assertThatThrownBy { verifier.verify(TestReleaseJars.signed(Shape(version = "0.9.8"))) }.hasMessage(reason)
        assertThatThrownBy { verifier.verify(TestReleaseJars.signed(Shape(version = "1.0", withLauncher = false))) }.hasMessage(reason)

        assertThat(verifier.verify(TestReleaseJars.signed(Shape(version = "0.9.9.8"))).version).isEqualTo("0.9.9.8")
        assertThat(verifier.verify(TestReleaseJars.signed(Shape(version = "0.9.9.8-SNAPSHOT"))).version).isEqualTo("0.9.9.8-SNAPSHOT")
        assertThat(verifier.verify(TestReleaseJars.signed(Shape(version = "0.9.10"))).version).isEqualTo("0.9.10")
    }

    @Test
    fun `a file that is not a jar is refused in a sentence`() {
        val garbage = TestReleaseJars.temporary("not a jar at all".toByteArray())

        assertThatThrownBy { verifier.verify(garbage) }.hasMessage("This jar cannot be used: it is not a jar.")
    }

    @Test
    fun `the certificate this build pins is the committed one and reads`() {
        // The image's own; a jar signed with a test key is not one it trusts.
        assertThatThrownBy { ReleaseJarVerifier.pinned().verify(TestReleaseJars.signed()) }
            .hasMessageContaining("not with the Orknux release key")
    }

    @Test
    fun `versions compare positionally, a qualifier below the same numbers`() {
        assertThat(ReleaseVersion.newer("1.0", "0.9.9.7")).isTrue()
        assertThat(ReleaseVersion.newer("0.9.9.10", "0.9.9.9")).isTrue()
        assertThat(ReleaseVersion.newer("0.9.9.7", "0.9.9.7")).isFalse()
        assertThat(ReleaseVersion.newer("0.9.9.8", "0.9.9.8-SNAPSHOT")).isTrue()
        assertThat(ReleaseVersion.newer("garbage", "0.9")).isFalse()
        assertThat(Files.exists(TestReleaseJars.trusted.pem)).isTrue()
    }
}
