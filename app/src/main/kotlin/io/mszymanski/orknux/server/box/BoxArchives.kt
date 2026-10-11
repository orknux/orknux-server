package io.mszymanski.orknux.server.box

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A zip turned into the tar a box unpacks.
 *
 * A box takes a tar and nothing else, and an agent's archives are as often zips:
 * one saved a zip as an artifact, orkbox_import answered `archive_invalid`, and
 * it went on to try unzip inside a box the file had never reached. Converted
 * here rather than in the box so every box, local or remote, takes both.
 */
internal object BoxArchives {

    /** The type bits of a tar mode; a zip made off Unix carries no mode, and the tar's defaults stand. */
    private const val DIRECTORY = 0x4000
    private const val FILE = 0x8000

    /** Whether [bytes] open the way a zip does: a local file header, or the end record of an empty one. */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            ((bytes[2].toInt() == 3 && bytes[3].toInt() == 4) || (bytes[2].toInt() == 5 && bytes[3].toInt() == 6))

    /**
     * The same entries as a tar, folders and Unix modes kept where the zip has them.
     *
     * A name that climbs out of the directory it is unpacked into, or starts at
     * the root, is refused rather than dropped: an archive built to write
     * outside its target is not one to unpack partly.
     */
    fun zipToTar(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            ZipArchiveInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith("/") && name.split('/').none { it == ".." }) {
                        "The zip holds ${entry.name}, which would land outside the directory it is unpacked into"
                    }
                    val permissions = entry.unixMode and 0xFFF
                    if (entry.isDirectory) {
                        val held = TarArchiveEntry(if (name.endsWith("/")) name else "$name/")
                        if (permissions != 0) held.mode = DIRECTORY or permissions
                        tar.putArchiveEntry(held)
                    } else {
                        val content = zip.readAllBytes()
                        val held = TarArchiveEntry(name)
                        held.size = content.size.toLong()
                        if (permissions != 0) held.mode = FILE or permissions
                        tar.putArchiveEntry(held)
                        tar.write(content)
                    }
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
