package io.mszymanski.orknux.server.update

import jdk.security.jarsigner.JarSigner
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Release jars for the tests, built and signed here with throwaway Ed25519 keys.
 *
 * The keys come from the running JDK's own keytool - there is no public API for
 * making a certificate - and the signing is `jdk.security.jarsigner`, the API
 * the jarsigner tool is built on, so what is verified is a jar signed the way
 * CI signs one rather than a stand-in for a signature. Two keys: [trusted],
 * which the tests hand the server as its release certificate, and [other],
 * which signs perfectly well and is nobody this server trusts.
 */
object TestReleaseJars {

    class Key(val privateKey: PrivateKey, val chain: List<X509Certificate>, val pem: Path) {
        val certificate: X509Certificate get() = chain.first()
    }

    private val directory: Path by lazy {
        Path.of("target", "test-release-keys").toAbsolutePath().also { dir ->
            if (Files.exists(dir)) dir.toFile().deleteRecursively()
            Files.createDirectories(dir)
        }
    }

    val trusted: Key by lazy { key("trusted") }
    val other: Key by lazy { key("other") }

    private fun key(name: String): Key {
        val store = directory.resolve("$name.p12")
        val password = "changeit"
        keytool(
            "-genkeypair", "-keyalg", "Ed25519", "-alias", name, "-dname", "CN=Orknux Test $name",
            "-validity", "30", "-keystore", store.toString(), "-storetype", "PKCS12",
            "-storepass", password, "-keypass", password,
        )
        val pem = directory.resolve("$name.pem")
        keytool("-exportcert", "-rfc", "-alias", name, "-keystore", store.toString(), "-storepass", password, "-file", pem.toString())
        val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, password.toCharArray()) } }
        val chain = keys.getCertificateChain(name).map { it as X509Certificate }
        return Key(keys.getKey(name, password.toCharArray()) as PrivateKey, chain, pem)
    }

    private fun keytool(vararg arguments: String) {
        val tool = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "keytool.exe" else "keytool")
        val process = ProcessBuilder(listOf(tool.toString()) + arguments).redirectErrorStream(true).start()
        val said = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { "keytool failed: $said" }
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    /** What a test jar carries, each part switchable so a test can leave one out. */
    data class Shape(
        val version: String = "9.9.9",
        val startClass: String? = ReleaseJarVerifier.START_CLASS,
        val mainClass: String? = ReleaseJarVerifier.BOOT_LAUNCHER,
        val withInterface: Boolean = true,
        /** The launcher class, without which a release could not update itself back. */
        val withLauncher: Boolean = true,
        val schemaVersion: Int = 333,
        val schemaFloor: Int? = 333,
        /** Bytes of a stored library, to make a jar bigger than one piece. */
        val padding: Int = 0,
    )

    /** An unsigned jar of [shape]. */
    fun unsigned(shape: Shape = Shape(), into: Path = Files.createTempFile(directory, "unsigned-", ".jar")): Path {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            shape.mainClass?.let { mainAttributes[Attributes.Name.MAIN_CLASS] = it }
            shape.startClass?.let { mainAttributes.putValue("Start-Class", it) }
            mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = shape.version
        }
        JarOutputStream(Files.newOutputStream(into), manifest).use { jar ->
            fun put(name: String, bytes: ByteArray) {
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
            put("org/springframework/boot/loader/launch/JarLauncher.class", byteArrayOf(1, 2, 3))
            put("BOOT-INF/classes/io/mszymanski/orknux/server/OrknuxServerKt.class", byteArrayOf(4, 5, 6))
            // Random, so a padded jar really is that big; stored rather than
            // deflated, so a jar of a hundred megabytes is quick to build.
            val library = ByteArray(shape.padding.coerceAtLeast(16)).also { java.util.Random(7).nextBytes(it) }
            jar.putNextEntry(
                JarEntry("BOOT-INF/lib/library.jar").apply {
                    method = ZipEntry.STORED
                    size = library.size.toLong()
                    compressedSize = library.size.toLong()
                    crc = java.util.zip.CRC32().also { it.update(library) }.value
                },
            )
            jar.write(library)
            jar.closeEntry()
            put("BOOT-INF/classes/db/migration/postgresql/V1__first.sql", "SELECT 1;".toByteArray())
            put("BOOT-INF/classes/db/migration/postgresql/V${shape.schemaVersion}__latest.sql", "SELECT 2;".toByteArray())
            shape.schemaFloor?.let { put("BOOT-INF/classes/db/migration/rollback-floor", "$it\n".toByteArray()) }
            if (shape.withInterface) put("BOOT-INF/classes/static/index.html", "<!doctype html>".toByteArray())
            if (shape.withLauncher) put(ReleaseJarVerifier.LAUNCHER, byteArrayOf(8, 8, 8))
        }
        return into
    }

    /** A jar of [shape] signed with [by]. */
    fun signed(shape: Shape = Shape(), by: Key = trusted): Path = sign(unsigned(shape), by)

    fun sign(jar: Path, by: Key): Path {
        val signed = Files.createTempFile(directory, "signed-", ".jar")
        val signer = JarSigner.Builder(
            by.privateKey,
            CertificateFactory.getInstance("X.509").generateCertPath(by.chain),
        ).build()
        ZipFile(jar.toFile()).use { zip -> FileOutputStream(signed.toFile()).use { signer.sign(zip, it) } }
        return signed
    }

    /**
     * [jar] with one entry's content replaced and everything else - its
     * signature files included - copied as it was: tampering after signing.
     */
    fun tampered(jar: Path, entry: String = "BOOT-INF/classes/io/mszymanski/orknux/server/OrknuxServerKt.class"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipInputStream(Files.newInputStream(jar)).use { input ->
            ZipOutputStream(out).use { zip ->
                while (true) {
                    val next = input.nextEntry ?: break
                    val bytes = input.readAllBytes()
                    zip.putNextEntry(ZipEntry(next.name))
                    zip.write(if (next.name == entry) byteArrayOf(9, 9, 9, 9) else bytes)
                    zip.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    /** [jar] with an extra entry nobody signed. */
    fun withUnsignedEntry(jar: Path): Path {
        val into = Files.createTempFile(directory, "extended-", ".jar")
        ZipInputStream(Files.newInputStream(jar)).use { input ->
            ZipOutputStream(Files.newOutputStream(into)).use { zip ->
                while (true) {
                    val next = input.nextEntry ?: break
                    val bytes = input.readAllBytes()
                    zip.putNextEntry(ZipEntry(next.name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("BOOT-INF/classes/io/mszymanski/orknux/server/Extra.class"))
                zip.write(byteArrayOf(7, 7, 7))
                zip.closeEntry()
            }
        }
        return into
    }

    fun temporary(bytes: ByteArray): Path =
        Files.createTempFile(directory, "bytes-", ".jar").also { Files.write(it, bytes) }

    fun verifier(): ReleaseJarVerifier = ReleaseJarVerifier(trusted.certificate)
}
