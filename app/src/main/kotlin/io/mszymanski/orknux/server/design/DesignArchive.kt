package io.mszymanski.orknux.server.design

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An .orkx file: a picture and what Orknux knows about it, in one archive.
 *
 * A ZIP holding `project.json` - the name, the prompt and what it was drawn
 * with - beside the picture's own file, byte for byte as it was stored. A ZIP
 * so that anything can open one and find an ordinary picture inside, and so
 * that a later version can add files beside these two without the reader of
 * this one minding. Read and written here and nowhere else, so the format has
 * one definition.
 *
 * Version 2 adds the picture's layers from the image editor: a PNG each under
 * `layers/`, listed in `project.json` in the order they stack with their names
 * and whether each is shown. The picture's own file is still the layers
 * flattened, so a reader of version 1 - or anything that opens a ZIP - finds
 * the picture as it looks.
 */
object DesignArchive {

    /** What `project.json` says it is; anything else is not one of these. */
    const val FORMAT = "orknux-picture"
    const val VERSION = 2
    const val PROJECT = "project.json"

    /** What a project says about its picture: everything but the bytes. */
    data class Project(
        val name: String?,
        val prompt: String?,
        val model: String?,
        val size: String?,
        val quality: String?,
        val style: String?,
        val drawnBy: String?,
        val drawnAt: String?,
    )

    /** One layer, the picture itself marked [picture]: its name, whether it is shown, and its PNG. */
    data class Layer(val name: String?, val visible: Boolean, val picture: Boolean, val png: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class Read(val project: Project, val image: ByteArray, val layers: List<Layer> = emptyList()) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun write(mapper: ObjectMapper, project: Project, image: ByteArray, contentType: String, layers: List<Layer> = emptyList()): ByteArray {
        val picture = "picture." + extensionOf(contentType)
        val files = layers.indices.map { "layers/$it.png" }
        val json = mapper.writeValueAsBytes(
            linkedMapOf(
                "format" to FORMAT,
                "version" to VERSION,
                "picture" to picture,
                "contentType" to contentType,
                "name" to project.name,
                "prompt" to project.prompt,
                "drawnWith" to linkedMapOf(
                    "model" to project.model,
                    "size" to project.size,
                    "quality" to project.quality,
                    "style" to project.style,
                    "drawnBy" to project.drawnBy,
                    "drawnAt" to project.drawnAt,
                ),
                "layers" to layers.mapIndexed { at, layer ->
                    linkedMapOf("file" to files[at], "name" to layer.name, "visible" to layer.visible, "picture" to layer.picture)
                },
            ),
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(PROJECT))
            zip.write(json)
            zip.closeEntry()
            stored(zip, picture, image)
            layers.forEachIndexed { at, layer -> stored(zip, files[at], layer.png) }
        }
        return out.toByteArray()
    }

    /** Stored, not deflated: a picture is compressed already, and squeezing it again only costs time. */
    private fun stored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = java.util.zip.CRC32().apply { update(bytes) }.value
            },
        )
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * The project and its picture, or why not. Nothing in it is trusted: each
     * entry is read up to [maxBytes] and no further, so an archive that
     * claims little and unpacks to a great deal is refused rather than held,
     * and it holds at most [mostLayers] layers, the picture itself counted.
     */
    fun read(mapper: ObjectMapper, archive: ByteArray, maxBytes: Long, mostLayers: Int): Read {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entries.size >= mostLayers + ROOM) throw DesignImageRefusedException("That .orkx file holds more than a picture, its layers and its project.")
                entries[entry.name] = bounded(zip, maxBytes)
            }
        }
        val json = entries[PROJECT] ?: throw DesignImageRefusedException("That file is not an .orkx archive: it has no $PROJECT.")
        val tree = runCatching { mapper.readTree(json) }.getOrNull()
            ?: throw DesignImageRefusedException("The $PROJECT in that .orkx file could not be read.")
        if (text(tree, "format") != FORMAT) throw DesignImageRefusedException("That .orkx file is not an Orknux picture.")
        val image = text(tree, "picture")?.let { entries[it] }
            ?: throw DesignImageRefusedException("That .orkx file holds no picture.")
        val listed = tree.path("layers").takeIf { it.isArray }?.toList().orEmpty()
        if (listed.size > mostLayers) throw DesignImageRefusedException("That .orkx file holds more layers than this installation keeps.")
        val layers = listed.map { layer ->
            val png = text(layer, "file")?.let { entries[it] }
                ?: throw DesignImageRefusedException("A layer that .orkx file lists is not in it.")
            Layer(
                name = text(layer, "name"),
                visible = layer.path("visible").let { !it.isBoolean || it.booleanValue() },
                picture = layer.path("picture").let { it.isBoolean && it.booleanValue() },
                png = png,
            )
        }
        val drawn = tree.path("drawnWith")
        return Read(
            Project(
                name = text(tree, "name"),
                prompt = text(tree, "prompt"),
                model = text(drawn, "model"),
                size = text(drawn, "size"),
                quality = text(drawn, "quality"),
                style = text(drawn, "style"),
                drawnBy = text(drawn, "drawnBy"),
                drawnAt = text(drawn, "drawnAt"),
            ),
            image,
            layers,
        )
    }

    private fun bounded(zip: ZipInputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw DesignImageRefusedException("That .orkx file is larger than this installation keeps.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** A string field, or null where it is missing, null, blank or not a string. */
    private fun text(node: JsonNode, field: String): String? =
        node.path(field).takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() }

    private fun extensionOf(contentType: String): String = when (contentType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }

    /** Beside the layers: a project and a picture, and room for what a later version adds. */
    private const val ROOM = 16
}
