package io.mszymanski.orknux.server.update

import io.mszymanski.orknux.server.graphql.Refusal
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.CodeSigner
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.JarFile
import java.util.zip.ZipException

/*
 * Everything in this file runs without Spring: the launcher (ReleaseLauncher.kt)
 * uses it before any context exists, from the image's own jar. Keep it that way.
 */

/** What a jar said about itself, once it was shown to be a release of this server. */
data class VerifiedReleaseJar(
    /** `Implementation-Version`, as the build wrote it. */
    val version: String,
    /** The newest Postgres migration the jar carries. */
    val schemaVersion: Int,
    /** The newest migration a database cannot be rolled back past once it ran this jar. */
    val schemaFloor: Int,
)

/**
 * Whether a jar may be stored, and later run: a release of this server, signed
 * with the Orknux release key. Issue #584.
 *
 * The signature is the only proof. A sha256 in the database is checked too, but
 * as integrity and nothing more - whoever can change a stored jar can change the
 * hash beside it, and the point of this is that being able to write the
 * database is not enough to have modified classes run. So [trusted] is never
 * read from the database: it is the certificate the image carries
 * (`release/orknux-release.pem`), handed in here so a test can use its own.
 *
 * Every entry is read in full, because [JarFile] only compares an entry with its
 * digest while it is being read - an entry never read is an entry never checked.
 * Every file outside the signature's own must then be covered by a signer whose
 * key is the trusted one: unsigned, partly signed, tampered after signing and
 * signed by another key are all refused, each in its own words.
 */
class ReleaseJarVerifier(private val trusted: List<X509Certificate>) {

    constructor(trusted: X509Certificate) : this(listOf(trusted))

    fun verify(jar: Path): VerifiedReleaseJar {
        val opened = try {
            JarFile(jar.toFile(), true)
        } catch (_: ZipException) {
            throw ReleaseJarRefusedException("it is not a jar")
        } catch (failure: java.io.IOException) {
            throw ReleaseJarRefusedException("it could not be read (${failure.message})")
        }
        opened.use { file ->
            val names = mutableListOf<String>()
            var signed = 0
            var unsigned: String? = null
            var foreign = false
            for (entry in file.entries()) {
                names += entry.name
                try {
                    file.getInputStream(entry).use { it.transferTo(OutputStream.nullOutputStream()) }
                } catch (_: SecurityException) {
                    throw ReleaseJarRefusedException("${entry.name} was changed after the jar was signed")
                } catch (failure: java.io.IOException) {
                    throw ReleaseJarRefusedException("${entry.name} could not be read (${failure.message})")
                }
                if (entry.isDirectory || isSignatureFile(entry.name)) continue
                val signers: Array<CodeSigner>? = entry.codeSigners
                when {
                    signers.isNullOrEmpty() -> if (unsigned == null) unsigned = entry.name
                    signers.any(::isTrusted) -> signed++
                    else -> foreign = true
                }
            }
            if (signed == 0 && !foreign) throw ReleaseJarRefusedException("it is not signed")
            if (foreign) throw ReleaseJarRefusedException("it is signed, but not with the Orknux release key")
            if (unsigned != null) throw ReleaseJarRefusedException("$unsigned is not covered by its signature")

            return structureOf(file, names)
        }
    }

    private fun isTrusted(signer: CodeSigner): Boolean {
        val certificate = signer.signerCertPath.certificates.firstOrNull() as? X509Certificate ?: return false
        return trusted.any { certificate.publicKey.encoded.contentEquals(it.publicKey.encoded) }
    }

    /**
     * A Spring Boot jar of this application, carrying the interface, and
     * saying which schema it was built for. Checked after the signature, so
     * that what is being read is known to be what was signed.
     */
    private fun structureOf(file: JarFile, names: List<String>): VerifiedReleaseJar {
        val main = file.manifest?.mainAttributes ?: throw ReleaseJarRefusedException("it has no manifest")
        if (main.getValue("Start-Class") != START_CLASS) {
            throw ReleaseJarRefusedException("it does not start Orknux (its Start-Class is not $START_CLASS)")
        }
        if (main.getValue("Main-Class") != BOOT_LAUNCHER) {
            throw ReleaseJarRefusedException("it is not started by Spring Boot's launcher")
        }
        val version = main.getValue("Implementation-Version")?.trim().orEmpty()
        if (ReleaseVersion.parse(version) == null) {
            throw ReleaseJarRefusedException("it carries no version this server can read")
        }
        if (names.none { it.startsWith("BOOT-INF/classes/") } || names.none { it.startsWith("BOOT-INF/lib/") }) {
            throw ReleaseJarRefusedException("it is not a Spring Boot jar (BOOT-INF/classes and BOOT-INF/lib)")
        }
        if (INTERFACE !in names) throw ReleaseJarRefusedException("it carries no interface")

        val schema = names.mapNotNull { MIGRATION.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
            ?: throw ReleaseJarRefusedException("it carries no database migrations")
        val floor = file.getEntry(FLOOR)
            ?.let { entry -> file.getInputStream(entry).use { it.readAllBytes().decodeToString().trim().toIntOrNull() } }
            ?: throw ReleaseJarRefusedException("it does not say which schema it can be rolled back to")
        if (floor > schema) throw ReleaseJarRefusedException("its rollback floor, V$floor, is past its own schema")
        return VerifiedReleaseJar(version, schema, floor)
    }

    companion object {
        const val START_CLASS = "io.mszymanski.orknux.server.OrknuxServerKt"
        const val BOOT_LAUNCHER = "org.springframework.boot.loader.launch.JarLauncher"
        private const val INTERFACE = "BOOT-INF/classes/static/index.html"
        private const val FLOOR = "BOOT-INF/classes/db/migration/rollback-floor"
        private val MIGRATION = Regex("""BOOT-INF/classes/db/migration/postgresql/V(\d+)__[^/]*\.sql""")

        /** Where the image keeps the certificate it trusts, on its own classpath. */
        const val PINNED = "release/orknux-release.pem"

        /** The certificate this build carries, read off its own classpath, and the test one where switched on. */
        fun pinned(warn: (String) -> Unit = { System.err.println(it) }): ReleaseJarVerifier {
            val stream = ReleaseJarVerifier::class.java.classLoader.getResourceAsStream(PINNED)
                ?: error("$PINNED is missing from this build")
            return withTestTrust(certificate(stream), warn)
        }

        /**
         * [pinned], and a second certificate for the image's own end-to-end
         * test - TEST ONLY.
         *
         * `scripts/verify-one-image.sh` has to update a real container to a jar
         * it signed itself, and it cannot hold the release key. So a second PEM,
         * named by ORKNUX_RELEASE_TRUST_EXTRA, is trusted - but only beside
         * ORKNUX_RELEASE_TEST=true, both read from the process environment and
         * never from the database or application.yml, and said loudly on every
         * start it is in effect. Neither belongs in a deployment: anybody who
         * can set them can run a jar they signed. That is already true of
         * anybody who can set the image, which is why it is the environment
         * that holds the switch and nothing a screen or a database can reach.
         */
        fun withTestTrust(pinned: X509Certificate, warn: (String) -> Unit): ReleaseJarVerifier {
            val extra = System.getenv("ORKNUX_RELEASE_TRUST_EXTRA")?.ifBlank { null }
            if (extra == null || System.getenv("ORKNUX_RELEASE_TEST") != "true") return ReleaseJarVerifier(pinned)
            warn(
                "TEST ONLY: ORKNUX_RELEASE_TEST is set, so release jars signed by $extra are trusted as well as the " +
                    "Orknux release key. Remove ORKNUX_RELEASE_TEST and ORKNUX_RELEASE_TRUST_EXTRA from any real deployment.",
            )
            return ReleaseJarVerifier(listOf(pinned, Files.newInputStream(Path.of(extra)).let(::certificate)))
        }

        /** The newest Postgres migration a jar carries, read without verifying it; null where none. */
        fun schemaOf(jar: Path): Int? = runCatching {
            JarFile(jar.toFile(), false).use { file ->
                file.entries().asSequence()
                    .mapNotNull { MIGRATION.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
                    .maxOrNull()
            }
        }.getOrNull()

        fun certificate(stream: InputStream): X509Certificate =
            stream.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

        /**
         * The jar's own signature files, which sign the rest and are not signed
         * themselves: the manifest, `*.SF` and the signature blocks.
         */
        fun isSignatureFile(name: String): Boolean {
            val upper = name.uppercase()
            if (!upper.startsWith("META-INF/") || upper.indexOf('/', "META-INF/".length) >= 0) return false
            return upper == "META-INF/MANIFEST.MF" ||
                listOf(".SF", ".RSA", ".DSA", ".EC").any { upper.endsWith(it) } ||
                upper.startsWith("META-INF/SIG-")
        }

        /** The schema floor this build carries, from its own classpath; 0 where it has none. */
        fun ownFloor(): Int =
            ReleaseJarVerifier::class.java.classLoader.getResourceAsStream("db/migration/rollback-floor")
                ?.use { it.readAllBytes().decodeToString().trim().toIntOrNull() } ?: 0

        /** Lowercase hex SHA-256 of a file, read once. */
        fun sha256(file: Path): String = Files.newInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** `Implementation-Version` of a jar, or null where it has none or cannot be read. */
        fun versionOf(jar: Path): String? = runCatching {
            JarFile(jar.toFile(), false).use { it.manifest?.mainAttributes?.getValue("Implementation-Version") }
        }.getOrNull()
    }
}

/** A jar that will not be stored or run, and why, in a sentence the administrator is shown. */
class ReleaseJarRefusedException(val why: String) :
    RuntimeException("This jar cannot be used: $why."), Refusal {
    override val arguments get() = mapOf("why" to why)
}

/**
 * A version as Orknux numbers them: positional, any number of parts, `0.9.9.7`
 * then `1.0`. A qualifier (`-SNAPSHOT`) sorts below the same numbers without one.
 */
data class ReleaseVersion(val parts: List<Int>, val qualifier: String) : Comparable<ReleaseVersion> {

    override fun compareTo(other: ReleaseVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 }
            if (difference != 0) return difference
        }
        return when {
            qualifier == other.qualifier -> 0
            qualifier.isEmpty() -> 1
            other.qualifier.isEmpty() -> -1
            else -> qualifier.compareTo(other.qualifier)
        }
    }

    companion object {
        private val SHAPE = Regex("""(\d+(?:\.\d+)*)(?:-([A-Za-z0-9.]+))?""")

        fun parse(text: String?): ReleaseVersion? {
            val match = SHAPE.matchEntire(text?.trim().orEmpty()) ?: return null
            return ReleaseVersion(match.groupValues[1].split('.').map { it.toInt() }, match.groupValues[2])
        }

        /** Whether [candidate] is a later version than [than]; false where either cannot be read. */
        fun newer(candidate: String?, than: String?): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(than) ?: return false
            return a > b
        }
    }
}
