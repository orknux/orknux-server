package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.workflow.script.PluginLibraryFile
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component

/**
 * One library file a plugin ships with, exactly as it was accepted at load.
 *
 * A row per file rather than a JSON column, because a source is not a value to
 * fold into another: these are the plugin's own code, read on every call, and
 * they go with the plugin's row the way its functions do. [position] is the
 * evaluation order the import graph settled on at load - kept so a call never
 * has to settle it again.
 */
@Entity
@Table(name = "plugin_library")
class PluginLibrary(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "plugin_id", nullable = false)
    val pluginId: Long,

    @Column(nullable = false)
    val position: Int,

    /** The declared, normalised path: relative, `/`-joined, no leading `./`. */
    @Column(nullable = false, length = 200)
    val path: String,

    @Column(nullable = false, columnDefinition = "text")
    val source: String,
)

interface PluginLibraryRepository : JpaRepository<PluginLibrary, Long> {

    fun findByPluginIdOrderByPositionAsc(pluginId: Long): List<PluginLibrary>

    fun deleteByPluginId(pluginId: Long)
}

/**
 * The one place a stored plugin's code is read for the runner, so every
 * caller hands the sandbox the same bundle the load accepted - and so the
 * bundle is read by somebody about to run it, and not by whoever happened to
 * look the plugin up. Issue #616.
 */
@Component
class PluginSources(
    private val libraries: PluginLibraryRepository,
    private val code: PluginCodeRepository,
) {

    /** What runs. Read on each call rather than kept: it is the row's, and a re-upload replaces it. */
    fun sourceOf(plugin: Plugin): String =
        checkNotNull(code.sourceOf(requireNotNull(plugin.id))) { "plugin ${plugin.key} has no row" }

    fun librariesOf(plugin: Plugin): List<PluginLibraryFile> =
        libraries.findByPluginIdOrderByPositionAsc(requireNotNull(plugin.id))
            .map { PluginLibraryFile(it.path, it.source) }
}
