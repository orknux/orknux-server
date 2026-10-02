package io.mszymanski.orknux.server.update

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.plugin.Marketplace
import io.mszymanski.orknux.server.plugin.MarketplaceUnreachableException
import io.mszymanski.orknux.server.plugin.OfferedServerRelease
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import jakarta.servlet.http.HttpServletRequest
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.nio.file.Files
import java.nio.file.Path

/**
 * Server updates, as an administrator sees them. Issue #584.
 *
 * Nothing here updates by itself: an administrator presses Update on a release
 * orknux.ai offers, uploads a jar, or rolls back to one the database still
 * holds. Every one of those is checked against the release key the image
 * carries before it is stored, audited, and followed by a restart into the
 * launcher - which checks it again before it runs it.
 *
 * The upload is REST and its body is the jar itself rather than a multipart
 * form: multipart is capped installation-wide at a size meant for recordings
 * and attachments (ORKNUX_UPLOAD_MAX_FILE_SIZE), a jar is a third of a gigabyte,
 * and raising that cap for one administrator's door would raise it for every
 * door. The body is streamed to a temporary file and counted against this
 * installation's own limit as it arrives.
 */
@RestController
class ServerReleaseAPI(
    private val updates: ServerReleases,
    private val marketplace: Marketplace,
    private val restart: ServerRestart,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
    private val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
) {

    @QueryMapping
    fun serverUpdates(): ServerUpdatesView {
        access.requireAdmin()
        val running = updates.runningReleaseId()
        val floor = updates.schemaFloor()
        val stored = updates.stored()
        if (!updates.enabled()) {
            return ServerUpdatesView(
                enabled = false,
                runningVersion = updates.runningVersion(),
                runningRelease = null,
                restartable = restart.available(),
                offered = false,
                offeredError = null,
                available = emptyList(),
                stored = emptyList(),
                kept = settings.releasesKept(),
                schemaFloor = floor,
                imageVersion = updates.imageVersion(),
                imageActivatable = false,
                imageRefusal = null,
            )
        }
        val imageRefusal = updates.imageRefusal(floor)

        var offeredError: String? = null
        val offered = if (!marketplace.configured) {
            null
        } else {
            try {
                marketplace.serverReleases(after = updates.runningVersion())
            } catch (failure: MarketplaceUnreachableException) {
                offeredError = failure.message
                null
            }
        }
        val storedVersions = stored.map { it.version }.toSet()
        return ServerUpdatesView(
            enabled = true,
            runningVersion = updates.runningVersion(),
            runningRelease = running?.let { id -> stored.firstOrNull { it.id == id } }?.let { view(it, running, floor) },
            restartable = restart.available(),
            offered = offered != null,
            offeredError = offeredError,
            available = offered.orEmpty()
                .filter { ReleaseVersion.newer(it.version, updates.runningVersion()) }
                .map { OfferedServerReleaseView(it.version, it.publishedAt, it.changelog, it.size, it.version in storedVersions) },
            stored = stored.map { view(it, running, floor) },
            kept = settings.releasesKept(),
            schemaFloor = floor,
            imageVersion = updates.imageVersion(),
            imageActivatable = imageRefusal == null,
            imageRefusal = imageRefusal,
        )
    }

    /**
     * Back to the jar the image was built with: the version a platform team
     * approved, and the way back from a stored release with nothing older
     * kept.
     */
    @MutationMapping
    fun activateImageRelease(): ServerUpdateStarted {
        access.requireAdmin()
        updates.useImage()
        audit.record(null, WorkspaceAuditCategory.WORKSPACE, "Server rolled back to the image's own ${updates.imageVersion()}")
        return ServerUpdateStarted(null, restart.soon())
    }

    /**
     * Downloads [version] from orknux.ai, checks it against the listing and
     * the release key, stores it, and starts it.
     */
    @MutationMapping
    fun installServerRelease(@Argument version: String): ServerUpdateStarted {
        access.requireAdmin()
        updates.requireEnabled()
        val listed: OfferedServerRelease = marketplace.serverReleases(after = null)
            ?.firstOrNull { it.version == version }
            ?: throw ServerReleaseNotOfferedException(version)

        val release = updates.bySha256(listed.sha256) ?: run {
            val limit = settings.releaseMaxMb() * 1024L * 1024L
            if (listed.size > limit) throw ServerReleaseTooLargeException(settings.releaseMaxMb())
            val file = Files.createTempFile("orknux-release-", ".jar")
            try {
                marketplace.downloadServerJar(listed.jarUrl, file, limit)
                if (Files.size(file) != listed.size || ReleaseJarVerifier.sha256(file) != listed.sha256) {
                    throw ServerReleaseDownloadMismatchException(version)
                }
                updates.store(file, ServerReleaseSource.ORKNUX_AI, currentUser())
            } finally {
                Files.deleteIfExists(file)
            }
        }
        return started(release.id!!)
    }

    /** Starts a release the database holds: a rollback, or going forward again. */
    @MutationMapping
    fun activateServerRelease(@Argument id: Long): ServerUpdateStarted {
        access.requireAdmin()
        return started(id)
    }

    /**
     * A jar from the administrator's own disk: a hotfix build, or an
     * air-gapped installation's only way to receive one. Stored, not started -
     * starting is the same button as for any other stored release.
     */
    @PostMapping("/api/server-releases")
    fun upload(request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        access.requireAdmin()
        updates.requireEnabled()
        val limit = settings.releaseMaxMb() * 1024L * 1024L
        if (request.contentLengthLong > limit) throw ServerReleaseTooLargeException(settings.releaseMaxMb())

        val file: Path = Files.createTempFile("orknux-upload-", ".jar")
        try {
            request.inputStream.use { body ->
                Files.newOutputStream(file).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val read = body.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > limit) throw ServerReleaseTooLargeException(settings.releaseMaxMb())
                        out.write(buffer, 0, read)
                    }
                }
            }
            val release = updates.store(file, ServerReleaseSource.UPLOAD, currentUser())
            audit.record(null, WorkspaceAuditCategory.WORKSPACE, "Server release ${release.version} uploaded")
            return ResponseEntity.status(HttpStatus.CREATED).body(
                mapOf("id" to release.id, "version" to release.version, "sha256" to release.sha256, "size" to release.size),
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun started(id: Long): ServerUpdateStarted {
        val before = updates.runningVersion()
        val release = updates.activate(id, currentUser())
        val wording = when {
            ReleaseVersion.newer(release.version, before) -> "Server updated to ${release.version}"
            release.version == before -> "Server release ${release.version} re-applied"
            else -> "Server rolled back to ${release.version}"
        }
        audit.record(null, WorkspaceAuditCategory.WORKSPACE, wording)
        val restarting = restart.soon()
        return ServerUpdateStarted(view(release, updates.runningReleaseId(), updates.schemaFloor()), restarting)
    }

    private fun view(release: ServerRelease, running: Long?, floor: Int): StoredServerReleaseView {
        val refusal = updates.refusalFor(release, floor)
        return StoredServerReleaseView(
            id = release.id!!,
            version = release.version,
            source = release.source,
            size = release.size,
            sha256 = release.sha256,
            schemaVersion = release.schemaVersion,
            schemaFloor = release.schemaFloor,
            storedAt = release.storedAt.toString(),
            storedBy = release.storedBy,
            state = release.state,
            bootAttempts = release.bootAttempts,
            failure = release.failure,
            running = release.id == running,
            activatable = refusal == null,
            refusal = refusal,
        )
    }

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
}

data class ServerUpdatesView(
    /** ORKNUX_SELF_UPDATE; false and everything else is empty. */
    val enabled: Boolean,
    val runningVersion: String,
    /** The stored release this server runs; null for the image's own jar. */
    val runningRelease: StoredServerReleaseView?,
    /** Whether this server can restart itself - false outside the image's start loop. */
    val restartable: Boolean,
    /** Whether orknux.ai answered with a list of server releases at all. */
    val offered: Boolean,
    /** Why it could not be asked, where it could not. */
    val offeredError: String?,
    /** What orknux.ai offers newer than what runs, newest first. */
    val available: List<OfferedServerReleaseView>,
    val stored: List<StoredServerReleaseView>,
    /** How many are kept; the Admin Settings value. */
    val kept: Int,
    val schemaFloor: Int,
    /** The jar the image was built with. */
    val imageVersion: String,
    /** Whether going back to it is allowed; see [imageRefusal] where not. */
    val imageActivatable: Boolean,
    val imageRefusal: String?,
)

data class OfferedServerReleaseView(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val size: Long,
    /** Already in the database, so pressing Update only starts it. */
    val stored: Boolean,
)

data class StoredServerReleaseView(
    val id: Long,
    val version: String,
    val source: ServerReleaseSource,
    val size: Long,
    val sha256: String,
    val schemaVersion: Int,
    val schemaFloor: Int,
    val storedAt: String,
    val storedBy: String,
    val state: ServerReleaseState,
    val bootAttempts: Int,
    val failure: String?,
    val running: Boolean,
    val activatable: Boolean,
    val refusal: String?,
)

data class ServerUpdateStarted(
    /** Null when going back to the image's own jar, which is not a stored release. */
    val release: StoredServerReleaseView?,
    /** False where nothing can restart this server, and somebody has to. */
    val restarting: Boolean,
)

@Component
class ServerReleaseExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is ServerUpdatesDisabledException,
            is ServerReleaseNotActivatableException,
            is ServerReleaseAlreadyStoredException,
            is ServerReleaseTooLargeException,
            is ServerReleaseNotOfferedException,
            is ServerReleaseDownloadMismatchException,
            is ReleaseJarRefusedException,
            -> ErrorType.BAD_REQUEST

            is ServerReleaseNotFoundException -> ErrorType.NOT_FOUND
            else -> return null
        }
        return refused(exception, errorType, environment)
    }
}

/** The upload's refusals, as a sentence and the code the interface translates by. */
@RestControllerAdvice(assignableTypes = [ServerReleaseAPI::class])
class ServerReleaseUploadExceptionHandler {
    @ExceptionHandler(
        ServerUpdatesDisabledException::class,
        ServerReleaseAlreadyStoredException::class,
        ServerReleaseTooLargeException::class,
        ReleaseJarRefusedException::class,
    )
    fun refused(failure: RuntimeException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.badRequest().body(
            mapOf(
                "message" to failure.message,
                "code" to io.mszymanski.orknux.server.graphql.codeOf(failure),
                "arguments" to (failure as? io.mszymanski.orknux.server.graphql.Refusal)?.arguments,
            ),
        )
}
