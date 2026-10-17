package io.mszymanski.orknux.server.update

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * A release built for a newer Java than the runtime carries is not started:
 * the desktop app ships its own runtime, and a release it cannot load would
 * otherwise be a crash counted against the release.
 */
class ReleaseJavaFloorTest {

    @TempDir
    lateinit var dir: Path

    /** A server jar whose starting class says it was compiled for [feature]. */
    private fun jarFor(feature: Int?, name: String = "server.jar"): Path {
        val jar = dir.resolve(name)
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (feature != null) mainAttributes.putValue("Start-Class", "io.example.Start")
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { out ->
            out.putNextEntry(JarEntry("BOOT-INF/classes/io/example/Start.class"))
            val major = (feature ?: 25) + 44
            out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, (major shr 8).toByte(), major.toByte()))
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `the Java a jar needs is read off the class it starts with`() {
        assertThat(ReleaseJarVerifier.requiredJava(jarFor(25))).isEqualTo(25)
        assertThat(ReleaseJarVerifier.requiredJava(jarFor(27, "newer.jar"))).isEqualTo(27)
        // Naming no Start-Class says nothing, which is left to the start to find out.
        assertThat(ReleaseJarVerifier.requiredJava(jarFor(null, "plain.jar"))).isNull()
    }

    @Test
    fun `a jar needing a newer Java than the runtime is refused, and one it can run is not`() {
        assertThat(javaRefusal(27, 25)).isEqualTo("needs Java 27, and this runtime is Java 25")
        assertThat(javaRefusal(25, 25)).isNull()
        assertThat(javaRefusal(21, 25)).isNull()
        assertThat(javaRefusal(null, 25)).isNull()
    }
}
