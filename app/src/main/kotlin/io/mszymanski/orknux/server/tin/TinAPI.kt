package io.mszymanski.orknux.server.tin

import io.mszymanski.orknux.orktin.model.Info
import io.mszymanski.orknux.orktin.model.RuntimeInfo
import io.mszymanski.orknux.orktin.model.SessionDto
import io.mszymanski.orknux.server.attachment.InstallationSettingsAPI
import io.mszymanski.orknux.server.attachment.InstallationSettingsView
import io.mszymanski.orknux.server.llm.LlmSessionNotFoundException
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * The tins, for the Tins page, the local tin's Admin settings, and a
 * conversation's Tin panel.
 *
 * Administrators only, except what a conversation shows - which is that
 * conversation's workspace's - and the label suggestions an agent's form makes.
 * A tin is where agents run commands, and somebody who could add one could
 * point this installation's agents at a machine of their choosing.
 *
 * Nothing here returns a token: [TinView] says only whether one is stored.
 */
@Controller
class TinAPI(
    private val tins: Tins,
    private val rows: TinRepository,
    private val sessionRows: TinSessionRepository,
    private val llmSessions: LlmSessionRepository,
    private val settings: TinSettings,
    private val local: LocalTin,
    private val installationSettings: InstallationSettingsAPI,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
) {

    // ------------------------------------------------------------------ tins

    @QueryMapping
    fun tins(): List<TinView> {
        access.requireAdmin()
        return tins.all().map(::view)
    }

    @QueryMapping
    fun tin(@Argument id: String): TinView? {
        access.requireAdmin()
        return tins.byId(id)?.let(::view)
    }

    @QueryMapping
    fun tinInfo(@Argument id: String): TinInfoView {
        access.requireAdmin()
        val tin = tins.byId(id) ?: throw TinNotFoundException(id)
        val info = try {
            tins.client(tin).info()
        } catch (failed: TinCallException) {
            throw TinUnreachableException(tin.name, failed.message)
        }
        return TinInfoView.of(info)
    }

    @QueryMapping
    fun tinSessions(@Argument id: String): List<TinRemoteSessionView> {
        access.requireAdmin()
        val tin = tins.byId(id) ?: throw TinNotFoundException(id)
        // The local tin is not started to be told it holds nothing.
        val client = tins.clientIfRunning(tin) ?: return emptyList()
        val held = try {
            client.sessions(null)
        } catch (failed: TinCallException) {
            throw TinUnreachableException(tin.name, failed.message)
        }
        return held.map { TinRemoteSessionView.of(it, mapper) }
    }

    /** What an agent's form suggests: every label a tin has, once each. */
    @QueryMapping
    fun tinLabels(): List<String> {
        access.requireSignedIn()
        return tins.all().mapNotNull { it.label }.distinct().sorted()
    }

    @SchemaMapping(typeName = "Tin", field = "health")
    fun health(tin: TinView): TinHealthView? {
        val row = tin.known.row ?: return null
        val (up, detail) = tins.remote(row).health()
        return TinHealthView(if (up) TinHealthStatus.UP else TinHealthStatus.DOWN, detail)
    }

    @SchemaMapping(typeName = "Tin", field = "activeSessions")
    fun activeSessions(tin: TinView): Int? {
        // A local tin nobody has started holds none, and is not started to say so.
        val client = tins.clientIfRunning(tin.known) ?: return 0
        return try {
            client.sessions("ready").size
        } catch (_: TinCallException) {
            null
        }
    }

    @MutationMapping
    fun createTin(@Argument input: TinInput): TinView {
        access.requireAdmin()
        val name = validName(input.name, null)
        val address = validAddress(input.address)
        val row = rows.save(
            Tin(
                name = name,
                label = input.label?.trim()?.ifEmpty { null },
                address = address,
                token = input.token?.trim()?.ifEmpty { null },
                lastModifiedBy = currentUser(),
            ),
        )
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Tin $name added at $address")
        return view(tins.byId(row.id.toString())!!)
    }

    @MutationMapping
    fun updateTin(@Argument id: String, @Argument input: TinInput): TinView {
        access.requireAdmin()
        if (id == LOCAL_TIN_ID) {
            // Only its label is the screen's to change; the name sent back is ignored.
            val before = settings.localLabel()
            val label = input.label?.trim()?.ifEmpty { null }
            settings.setLocalLabel(label, currentUser())
            if (label != before) {
                auditRecorder.record(
                    null,
                    WorkspaceAuditCategory.INTEGRATION,
                    label?.let { "Local tin label set to $it" } ?: "Local tin label cleared",
                )
            }
            return view(tins.byId(LOCAL_TIN_ID) ?: KnownTin(LOCAL_TIN_ID, LOCAL_TIN_NAME, TinKind.LOCAL, label))
        }

        val row = id.toLongOrNull()?.let { rows.findByIdOrNull(it) } ?: throw TinNotFoundException(id)
        val before = Triple(row.name, row.label, row.address)
        val name = validName(input.name, row.id)
        val address = validAddress(input.address ?: row.address)
        val label = input.label?.trim()?.ifEmpty { null }
        val tokenChanged = input.token != null && input.token.trim().ifEmpty { null } != row.token

        row.name = name
        row.address = address
        row.label = label
        if (input.token != null) row.token = input.token.trim().ifEmpty { null }
        row.lastModifiedAt = OffsetDateTime.now()
        row.lastModifiedBy = currentUser()
        rows.save(row)

        if (before.first != name) {
            auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Tin ${before.first} renamed to $name")
        }
        if (before.third != address) {
            auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Tin $name moved to $address")
        }
        if (before.second != label) {
            auditRecorder.record(
                null,
                WorkspaceAuditCategory.INTEGRATION,
                label?.let { "Tin $name label set to $it" } ?: "Tin $name label cleared",
            )
        }
        if (tokenChanged) {
            auditRecorder.record(
                null,
                WorkspaceAuditCategory.INTEGRATION,
                if (row.token == null) "Tin $name token cleared" else "Tin $name token changed",
            )
        }
        return view(tins.byId(id)!!)
    }

    @MutationMapping
    fun deleteTin(@Argument id: String): Boolean {
        access.requireAdmin()
        if (id == LOCAL_TIN_ID) throw LocalTinNotRemovableException()
        val row = id.toLongOrNull()?.let { rows.findByIdOrNull(it) } ?: return false
        rows.delete(row)
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Tin ${row.name} removed")
        return true
    }

    /** A read: a request to /v1/info, which is not audited. */
    @MutationMapping
    fun testTinConnection(@Argument address: String, @Argument token: String?, @Argument id: String?): TinConnectionTestView {
        access.requireAdmin()
        val target = validAddress(address)
        val stored = if (token == null) id?.toLongOrNull()?.let { rows.findByIdOrNull(it) }?.token else null
        return try {
            val info = tins.remote(target, token?.trim()?.ifEmpty { null } ?: stored).info()
            TinConnectionTestView(true, "Connected to ${info.name} ${info.version}", info.version)
        } catch (failed: TinCallException) {
            val message = when {
                failed.status == 401 -> "The tin refused the token: ${failed.message}"
                failed.code == TinCallException.UNREACHABLE -> "Could not reach the tin: ${failed.message}"
                failed.code == TinCallException.REFUSED -> "The Networking rules do not let this server call it: ${failed.message}"
                else -> "The tin answered ${failed.status ?: ""} ${failed.code}: ${failed.message}".replace("  ", " ")
            }
            TinConnectionTestView(false, message, null)
        }
    }

    // ------------------------------------------------------- a conversation

    @QueryMapping
    fun sessionTins(@Argument sessionId: Long): List<SessionTinView> {
        val session = llmSessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        return sessionRows.findByLlmSessionIdOrderByCreatedAtDescIdDesc(sessionId).map { row ->
            SessionTinView.of(row, liveStatus(row))
        }
    }

    @QueryMapping
    fun sessionTinLog(@Argument sessionId: Long, @Argument sessionTinId: Long, @Argument after: Int?): TinLogView {
        val session = llmSessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        val row = sessionRows.findByIdOrNull(sessionTinId)?.takeIf { it.llmSessionId == sessionId }
            ?: throw TinNotFoundException(sessionTinId.toString())
        val since = (after ?: 0).toLong()
        val tin = tins.byId(row.tinRef)
            ?: return TinLogView(emptyList(), since.toInt(), false, "Tin ${row.tinName} is no longer configured")
        val client = tins.clientIfRunning(tin)
            ?: return TinLogView(emptyList(), since.toInt(), false, "The local tin has restarted since this session")
        return try {
            val page = client.logs(row.remoteSessionId, since, LOG_PAGE, null)
            TinLogView(
                events = page.events.map { TinEventView(it.seq.toInt(), it.at, it.type, it.data) },
                nextSeq = page.nextSeq.toInt(),
                available = true,
                message = null,
            )
        } catch (failed: TinCallException) {
            val message = if (failed.code == "session_not_found") {
                "Tin ${row.tinName} no longer has this session"
            } else {
                "Tin ${row.tinName} could not be asked: ${failed.message}"
            }
            TinLogView(emptyList(), since.toInt(), false, message)
        }
    }

    /**
     * The tin's own word for a session, where it can be had; the one last
     * recorded where it cannot. A word that changed is written back, so the
     * row is as fresh as the last time anybody looked.
     */
    private fun liveStatus(row: TinSession): String {
        if (!row.open) return row.status
        val tin = tins.byId(row.tinRef) ?: return row.status
        val client = tins.clientIfRunning(tin) ?: return row.status
        val live = try {
            client.session(row.remoteSessionId).status
        } catch (failed: TinCallException) {
            if (failed.code == "session_not_found") "stopped" else return row.status
        }
        if (live != row.status) {
            row.status = live
            if (live !in OPEN_STATUSES && row.closedAt == null) row.closedAt = OffsetDateTime.now()
            sessionRows.save(row)
        }
        return live
    }

    // ------------------------------------------------------------- settings

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinEnabled")
    fun localTinEnabled(source: InstallationSettingsView): Boolean = settings.localEnabled()

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinLocation")
    fun localTinLocation(source: InstallationSettingsView): String? = settings.localLocation()

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinLocationConfigured")
    fun localTinLocationConfigured(source: InstallationSettingsView): String = settings.localLocationConfigured()

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinRuntime")
    fun localTinRuntime(source: InstallationSettingsView): String = settings.localRuntime()

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinMaxSessions")
    fun localTinMaxSessions(source: InstallationSettingsView): Int = settings.localMaxSessions()

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinMaxSessionsConfigured")
    fun localTinMaxSessionsConfigured(source: InstallationSettingsView): Int = DEFAULT_LOCAL_TIN_SESSIONS

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinDefaultTtlSeconds")
    fun localTinDefaultTtlSeconds(source: InstallationSettingsView): Int = settings.localDefaultTtlSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinDefaultTtlSecondsConfigured")
    fun localTinDefaultTtlSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_LOCAL_TIN_TTL

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinMaxTtlSeconds")
    fun localTinMaxTtlSeconds(source: InstallationSettingsView): Int = settings.localMaxTtlSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinMaxTtlSecondsConfigured")
    fun localTinMaxTtlSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_LOCAL_TIN_MAX_TTL

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinIdleTimeoutSeconds")
    fun localTinIdleTimeoutSeconds(source: InstallationSettingsView): Int = settings.localIdleTimeoutSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinIdleTimeoutSecondsConfigured")
    fun localTinIdleTimeoutSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_LOCAL_TIN_IDLE

    @SchemaMapping(typeName = "InstallationSettings", field = "tinCommandWaitSeconds")
    fun tinCommandWaitSeconds(source: InstallationSettingsView): Int = settings.commandWaitSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "tinCommandWaitSecondsConfigured")
    fun tinCommandWaitSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_TIN_COMMAND_WAIT

    @SchemaMapping(typeName = "InstallationSettings", field = "tinCommandTimeoutSeconds")
    fun tinCommandTimeoutSeconds(source: InstallationSettingsView): Int = settings.commandTimeoutSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "tinCommandTimeoutSecondsConfigured")
    fun tinCommandTimeoutSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_TIN_COMMAND_TIMEOUT

    @SchemaMapping(typeName = "InstallationSettings", field = "tinOutputBytes")
    fun tinOutputBytes(source: InstallationSettingsView): Int = settings.outputBytes()

    @SchemaMapping(typeName = "InstallationSettings", field = "tinOutputBytesConfigured")
    fun tinOutputBytesConfigured(source: InstallationSettingsView): Int = DEFAULT_TIN_OUTPUT_BYTES

    @SchemaMapping(typeName = "InstallationSettings", field = "tinRequestTimeoutSeconds")
    fun tinRequestTimeoutSeconds(source: InstallationSettingsView): Int = settings.requestTimeoutSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "tinRequestTimeoutSecondsConfigured")
    fun tinRequestTimeoutSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_TIN_REQUEST_TIMEOUT

    @MutationMapping
    fun setLocalTinEnabled(@Argument enabled: Boolean): InstallationSettingsView =
        changed(if (enabled) "The local tin switched on" else "The local tin switched off") {
            settings.setLocalEnabled(enabled, it)
        }

    @MutationMapping
    fun setLocalTinLocation(@Argument location: String?): InstallationSettingsView {
        val given = location?.trim()?.ifEmpty { null }
        return changed(given?.let { "The local tin makes its sessions in $it" } ?: "The local tin makes its sessions in the default location") {
            settings.setLocalLocation(given, it)
        }
    }

    @MutationMapping
    fun setLocalTinRuntime(@Argument runtime: String): InstallationSettingsView =
        changed("The local tin runs sessions with the ${runtime.trim().lowercase()} runtime") { settings.setLocalRuntime(runtime, it) }

    @MutationMapping
    fun setLocalTinMaxSessions(@Argument count: Int): InstallationSettingsView =
        changed("The local tin holds up to $count sessions at once") { settings.setLocalMaxSessions(count, it) }

    @MutationMapping
    fun setLocalTinDefaultTtlSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("A local tin session lasts $seconds seconds unless it asks otherwise") {
            settings.setLocalDefaultTtlSeconds(seconds, it)
        }

    @MutationMapping
    fun setLocalTinMaxTtlSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("A local tin session lasts at most $seconds seconds") { settings.setLocalMaxTtlSeconds(seconds, it) }

    @MutationMapping
    fun setLocalTinIdleTimeoutSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("An idle local tin session is stopped after $seconds seconds") {
            settings.setLocalIdleTimeoutSeconds(seconds, it)
        }

    @MutationMapping
    fun setTinCommandWaitSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("An agent's tin command is waited for up to $seconds seconds") { settings.setCommandWaitSeconds(seconds, it) }

    @MutationMapping
    fun setTinCommandTimeoutSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("An agent's tin command is stopped after $seconds seconds") { settings.setCommandTimeoutSeconds(seconds, it) }

    @MutationMapping
    fun setTinOutputBytes(@Argument bytes: Int): InstallationSettingsView =
        changed("An agent is handed up to $bytes bytes of a tin command's output at once") { settings.setOutputBytes(bytes, it) }

    @MutationMapping
    fun setTinRequestTimeoutSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("A request to a tin is given up on after $seconds seconds") { settings.setRequestTimeoutSeconds(seconds, it) }

    /** Checks, sets, applies to a running local tin at once, and writes the entry - in that order. */
    private fun changed(entry: String, set: (String) -> Unit): InstallationSettingsView {
        access.requireAdmin()
        set(currentUser())
        local.refresh()
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, entry)
        return installationSettings.installationSettings()
    }

    // ---------------------------------------------------------------- shared

    private fun validName(given: String, self: Long?): String {
        val name = given.trim()
        if (name.isEmpty() || name.length > 255) throw TinNameInvalidException(given)
        if (name.equals(LOCAL_TIN_NAME, ignoreCase = true)) throw TinNameTakenException(name)
        rows.findByName(name)?.takeIf { it.id != self }?.let { throw TinNameTakenException(name) }
        return name
    }

    private fun validAddress(given: String?): String =
        given?.let(Tins::normalisedAddress) ?: throw TinAddressInvalidException(given.orEmpty())

    private fun view(tin: KnownTin) = TinView(tin)

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        /** How many log events one page of a conversation's Tin panel carries. */
        const val LOG_PAGE = 1000
    }
}

class TinView(val known: KnownTin) {
    val id: String get() = known.id
    val name: String get() = known.name
    val kind: TinKind get() = known.kind
    val label: String? get() = known.label
    val address: String? get() = known.row?.address
    val hasToken: Boolean get() = !known.row?.token.isNullOrBlank()
    val createdAt: String? get() = known.row?.createdAt?.toString()
}

enum class TinHealthStatus { UP, DOWN }

data class TinHealthView(val status: TinHealthStatus, val detail: String?)

data class TinRuntimeView(
    val name: String,
    val available: Boolean,
    val unavailableReason: String?,
    val os: String,
    val workdir: String?,
    val shell: List<String>,
    val defaultImage: String?,
    val images: List<String>?,
    val networkModes: List<String>,
    val caches: List<String>,
    val defaultCpus: Double?,
    val defaultMemoryMb: Int?,
    val maxCpus: Double?,
    val maxMemoryMb: Int?,
    val diskLimitEnforced: Boolean?,
) {
    companion object {
        fun of(runtime: RuntimeInfo) = TinRuntimeView(
            name = runtime.name,
            available = runtime.available,
            unavailableReason = runtime.unavailableReason,
            os = runtime.os,
            workdir = runtime.workdir,
            shell = runtime.shell,
            defaultImage = runtime.defaultImage,
            images = runtime.images,
            networkModes = runtime.networkModes,
            caches = runtime.caches,
            defaultCpus = runtime.defaultResources?.cpus,
            defaultMemoryMb = runtime.defaultResources?.memoryMb?.toInt(),
            maxCpus = runtime.maxResources?.cpus,
            maxMemoryMb = runtime.maxResources?.memoryMb?.toInt(),
            diskLimitEnforced = runtime.diskLimitEnforced,
        )
    }
}

data class TinInfoView(
    val name: String,
    val version: String,
    val defaultRuntime: String,
    val defaultTtlSeconds: Int,
    val maxTtlSeconds: Int,
    val defaultIdleTimeoutSeconds: Int,
    val maxSessions: Int,
    val runtimes: List<TinRuntimeView>,
) {
    companion object {
        fun of(info: Info) = TinInfoView(
            name = info.name,
            version = info.version,
            defaultRuntime = info.defaultRuntime,
            defaultTtlSeconds = info.defaultTtlSeconds.toInt(),
            maxTtlSeconds = info.maxTtlSeconds.toInt(),
            defaultIdleTimeoutSeconds = info.defaultIdleTimeoutSeconds.toInt(),
            maxSessions = info.maxSessions,
            runtimes = info.runtimes.map(TinRuntimeView::of),
        )
    }
}

data class TinRemoteSessionView(
    val id: String,
    val status: String,
    val runtime: String,
    val image: String?,
    val labels: String,
    val createdAt: String,
    val lastActivityAt: String,
    val expiresAt: String,
    val runningExecutions: Int,
    val totalExecutions: Int,
) {
    companion object {
        fun of(session: SessionDto, mapper: ObjectMapper) = TinRemoteSessionView(
            id = session.id,
            status = session.status,
            runtime = session.runtime,
            image = session.image,
            labels = mapper.writeValueAsString(session.labels),
            createdAt = session.createdAt,
            lastActivityAt = session.lastActivityAt,
            expiresAt = session.expiresAt,
            runningExecutions = session.executions.running,
            totalExecutions = session.executions.total,
        )
    }
}

data class TinConnectionTestView(val ok: Boolean, val message: String, val version: String?)

data class SessionTinView(
    val id: Long,
    val tinId: String,
    val tinName: String,
    val tinKind: TinKind,
    val remoteSessionId: String,
    val active: Boolean,
    val status: String,
    val createdAt: String,
    val lastUsedAt: String?,
) {
    companion object {
        fun of(row: TinSession, status: String) = SessionTinView(
            id = requireNotNull(row.id),
            tinId = row.tinRef,
            tinName = row.tinName,
            tinKind = row.tinKind,
            remoteSessionId = row.remoteSessionId,
            active = status in OPEN_STATUSES,
            status = status,
            createdAt = row.createdAt.toString(),
            lastUsedAt = row.lastUsedAt?.toString(),
        )
    }
}

data class TinEventView(val seq: Int, val at: String, val type: String, val data: String)

data class TinLogView(val events: List<TinEventView>, val nextSeq: Int, val available: Boolean, val message: String?)

/** What the Tins page sends; see the schema for which fields a local tin reads. */
data class TinInput(
    val name: String,
    val label: String? = null,
    val address: String? = null,
    val token: String? = null,
)
