package io.mszymanski.orknux.server.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

/**
 * The plugins the release brings with it. Issue #474.
 *
 * Making a PDF is something every installation wants, and it used to arrive only
 * if somebody went to the catalogue and installed it. The bundle asks for very
 * little to be shipped this way: the writer, the layout, the fonts and the
 * diagram renderer are all inside the one file, it declares no libraries, and
 * the one capability it wants - drawing a page - has its server half in core
 * already.
 *
 * **Written at boot, through the same door as an upload.** The bundle is read
 * from `resources/plugins/<key>/`, inspected by [PluginRunner] exactly as an
 * uploaded file is, and stored by [PluginUploadAPI.loaded]. Nothing here is a second
 * way of installing a plugin: a bundle that fails the contract is refused the
 * way a bad upload is, and the only two things `builtIn` changes are that its
 * own declarations are taken as accepted - the party who would have been asked
 * is the party that chose to run this release - and that the row cannot be
 * removed.
 *
 * **It writes only its own rows.** A row that is missing is written; a row still
 * marked built in is replaced when the shipped bundle's fingerprint differs,
 * which is how a new release carries a new version. A row somebody has uploaded
 * over is left alone for good, because the upload clears the flag: an
 * administrator who put their own build of the PDF plugin on this installation
 * did that deliberately, and having the next restart quietly undo it would be
 * the worst kind of surprise. `enabled` is never touched, so switching a
 * built-in off is a decision that survives every upgrade.
 *
 * **A failure here never stops the server.** The bundle is a convenience; an
 * installation with a broken one should come up and say so in the log, not
 * refuse to start. The source of truth for the bundles is orknux-extension,
 * where they are built; the copies here are vendored, since this repository has
 * no node.
 */
@Service
class BuiltInPlugins(
    private val plugins: PluginRepository,
    private val upload: PluginUploadAPI,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Its own reader, for the one manifest it parses.
     *
     * Not the application's: that one is configured for the product's own
     * shapes, and asking for a bare ObjectMapper here would be asking for a
     * bean nothing defines.
     */
    private val mapper = ObjectMapper()

    @EventListener(ApplicationReadyEvent::class)
    fun write() {
        val found = runCatching { PathMatchingResourcePatternResolver().getResources(MANIFESTS) }
            .getOrElse { why ->
                log.warn("The plugins this release brings could not be read: {}", why.message)
                return
            }
        if (found.isEmpty()) return

        found.sortedBy { it.description }.forEach { manifest ->
            runCatching { one(manifest) }.onFailure { why ->
                log.warn("The {} plugin this release brings was not written: {}", manifest.description, why.message)
            }
        }
    }

    /**
     * One bundle: read it, decide whether it is ours to write, and write it.
     *
     * Transactional per plugin rather than over the lot, so a second bundle
     * failing does not take the first one's row back out.
     */
    @Transactional
    internal fun one(manifest: Resource) {
        val said = mapper.readTree(manifest.inputStream.use { it.readBytes() })
        val key = said.path("key").asText("").trim()
        val file = said.path("path").asText("").trim().ifEmpty { "$key.js" }
        require(key.isNotEmpty()) { "its manifest names no key" }

        // Siblings of the manifest, resolved from it: a ClassPathResource reads
        // a relative name against its own folder, and asking for "." first
        // resolves one level too high.
        val source = manifest.createRelative(file).let { held ->
            require(held.exists()) { "$file is not beside its manifest" }
            held.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        }
        val fingerprint = digestOf(source)

        val held = plugins.findByKey(key)
        if (held != null && !held.builtIn) {
            log.debug("The {} plugin was uploaded over the one this release brings; leaving it alone", key)
            return
        }
        if (held != null && held.sha256 == fingerprint) return

        val icon = said.path("icon").asText("").trim().ifEmpty { null }
        upload.loaded(
            filename = file,
            source = source,
            files = emptyList(),
            typescript = null,
            // Nothing is being asked: see the class note on what `builtIn` means.
            accept = null,
            icon = drawing(manifest, icon),
            iconDark = drawing(manifest, icon?.let { beside(it) }),
            manifest = PluginManifest(
                name = said.path("name").asText("").trim().ifEmpty { null },
                summary = said.path("summary").asText("").trim().ifEmpty { null },
                author = said.path("author").asText("").trim().ifEmpty { null },
                version = said.path("version").asText("").trim().ifEmpty { null },
                icon = icon,
                iconDark = icon?.let { beside(it) },
            ),
            builtIn = true,
        )
        log.info(
            "The {} plugin this release brings is {}",
            key,
            if (held == null) "installed" else "updated to ${said.path("version").asText("")}",
        )
    }

    /** The face beside the manifest, where it is a drawing and small enough to keep. */
    private fun drawing(manifest: Resource, named: String?): String? {
        val path = named?.removePrefix("./")?.takeIf { it.isNotEmpty() } ?: return null
        val file = runCatching { manifest.createRelative(path) }.getOrNull() ?: return null
        if (!file.exists()) return null
        val text = runCatching { file.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) }.getOrNull()
        return drawingOrNull(text, MOST_ICON_CHARS)
    }

    /** `icon.svg` to `icon-white.svg`: the marketplace's own convention for a dark ground. */
    private fun beside(icon: String): String? {
        val dot = icon.lastIndexOf('.').takeIf { it > 0 } ?: return null
        return icon.substring(0, dot) + "-white" + icon.substring(dot)
    }

    private fun digestOf(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        /** Every bundle ships a manifest beside it, the same one the catalogue reads. */
        const val MANIFESTS = "classpath*:plugins/*/plugin.json"

        /**
         * What an icon may be, in characters. The upload's own bound, quoted
         * rather than shared because that one is private to its door.
         */
        const val MOST_ICON_CHARS = 64 * 1024
    }
}
