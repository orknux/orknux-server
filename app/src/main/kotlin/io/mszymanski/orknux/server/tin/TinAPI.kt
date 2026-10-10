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
import java.time.temporal.ChronoUnit

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
    private val sessionLog: TinSessionLog,
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

    /**
     * The sessions this installation recorded on [id], with the tin's own word
     * for each where it can be asked, and the sessions the tin holds that
     * nobody here opened - another client of a remote tin. The local tin holds
     * its sessions in memory, so after a restart the record is all there is.
     */
    @QueryMapping
    fun tinSessions(@Argument id: String): List<TinRemoteSessionView> {
        access.requireAdmin()
        val tin = tins.byId(id) ?: throw TinNotFoundException(id)
        val recorded = sessionRows.findTop200ByTinKindAndTinIdOrderByCreatedAtDescIdDesc(tin.kind, tin.row?.id)
        // The local tin is not started to be told it holds nothing.
        val held = tins.clientIfRunning(tin)?.let { client ->
            try {
                client.sessions(null)
            } catch (failed: TinCallException) {
                // With nothing recorded to show, that it could not be asked is the answer.
                if (recorded.isEmpty()) throw TinUnreachableException(tin.name, failed.message)
                null
            }
        }
        val byRemoteId = held.orEmpty().associateBy { it.id }
        val ours = recorded.map { row ->
            val live = byRemoteId[row.remoteSessionId]
            val status = when {
                live != null -> remembered(row, live.status)
                !row.open -> row.status
                row.tinKind == TinKind.LOCAL && !localHolds(row) -> remembered(row, "stopped")
                // Asked, and it no longer lists it.
                held != null -> remembered(row, "stopped")
                else -> row.status
            }
            TinRemoteSessionView.of(row, status, live, mapper)
        }
        val known = recorded.map { it.remoteSessionId }.toSet()
        val others = held.orEmpty().filter { it.id !in known }.map { TinRemoteSessionView.of(it, mapper) }
        return (ours + others)
            .sortedByDescending { runCatching { OffsetDateTime.parse(it.createdAt).toInstant() }.getOrNull() }
            .take(TIN_SESSIONS_LISTED)
    }

    /** What an agent's form suggests: every label a tin has, once each. */
    @QueryMapping
    fun tinLabels(): List<String> {
        access.requireSignedIn()
        return tins.all().mapNotNull { it.label }.distinct().sorted()
    }

    @SchemaMapping(typeName = "Tin", field = "health")
    fun health(tin: TinView): TinHealthView? {
        val row = tin.known.row ?: return localHealth(tin.known)
        val (up, detail) = tins.remote(row).health()
        return TinHealthView(if (up) TinHealthStatus.UP else TinHealthStatus.DOWN, detail)
    }

    /**
     * The local tin is healthy while the runtime it is set to can run a
     * session - the docker runtime with no daemon reachable is the usual way
     * it is not. Asking starts it, which is cheap, and is what the first
     * session would do anyway.
     */
    private fun localHealth(tin: KnownTin): TinHealthView = runCatching {
        val info = tins.client(tin).info()
        val runtime = info.runtimes.firstOrNull { it.name == info.defaultRuntime }
        when {
            runtime == null -> TinHealthView(TinHealthStatus.DOWN, "It offers no runtime")
            runtime.available -> TinHealthView(TinHealthStatus.UP, null)
            else -> TinHealthView(TinHealthStatus.DOWN, runtime.unavailableReason ?: "The ${runtime.name} runtime is not available")
        }
    }.getOrElse { TinHealthView(TinHealthStatus.DOWN, it.message ?: "It did not start") }

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
                label = validLabel(input.label),
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
        if (id == LOCAL_TIN_ID) throw LocalTinNotEditableException()

        val row = id.toLongOrNull()?.let { rows.findByIdOrNull(it) } ?: throw TinNotFoundException(id)
        val before = Triple(row.name, row.label, row.address)
        val name = validName(input.name, row.id)
        val address = validAddress(input.address ?: row.address)
        val label = validLabel(input.label)
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

    /** The stored token, for the eye on the token box; written down, as every reveal is. */
    @MutationMapping
    fun revealTinToken(@Argument id: String): String? {
        access.requireAdmin()
        val row = id.toLongOrNull()?.let { rows.findByIdOrNull(it) } ?: throw TinNotFoundException(id)
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Token for tin ${row.name} revealed")
        return row.token
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
            SessionTinView.of(row, liveStatus(row), labelOf(row))
        }
    }

    /**
     * A remote tin's log, read from the tin; unavailable once it cannot be
     * asked. The local tin's as this side recorded it, brought up to date first
     * where the tin can still be asked - and where it cannot, what was recorded
     * is still the log, with a message saying why it may be short; unavailable
     * only when nothing was ever recorded.
     */
    @QueryMapping
    fun sessionTinLog(@Argument sessionId: Long, @Argument sessionTinId: Long, @Argument after: Int?): TinLogView {
        val session = llmSessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        val row = sessionRows.findByIdOrNull(sessionTinId)?.takeIf { it.llmSessionId == sessionId }
            ?: throw TinNotFoundException(sessionTinId.toString())
        val since = (after ?: 0).toLong().coerceAtLeast(0)
        if (row.tinKind != TinKind.LOCAL) return remoteLog(row, since)
        val id = requireNotNull(row.id)
        val gone = recordLive(row)
        val events = sessionLog.after(id, since, TIN_LOG_PAGE).map { TinEventView(it.seq.toInt(), it.at, it.type, it.data) }
        val next = events.lastOrNull()?.seq ?: since.toInt()
        return when {
            gone == null -> TinLogView(events, next, true, null)
            sessionLog.any(id) -> TinLogView(events, next, true, "${gone.reason}; this is the log as last recorded")
            else -> TinLogView(emptyList(), since.toInt(), false, gone.alone)
        }
    }

    private fun remoteLog(row: TinSession, since: Long): TinLogView {
        val (client, unreachable) = reach(row)
        if (client == null) return TinLogView(emptyList(), since.toInt(), false, requireNotNull(unreachable).alone)
        return try {
            val page = client.logs(row.remoteSessionId, since, TIN_LOG_PAGE, null)
            TinLogView(page.events.map { TinEventView(it.seq.toInt(), it.at, it.type, it.data) }, page.nextSeq.toInt(), true, null)
        } catch (failed: TinCallException) {
            TinLogView(emptyList(), since.toInt(), false, goneFrom(row, failed).alone)
        }
    }

    /** Why a session's tin cannot be asked for its log: [reason] beside a recorded log, [alone] where there is none. */
    private class Gone(val reason: String, val alone: String = reason)

    /** Copies what the tin has of the session's log; null when that worked, why not when it did not. */
    private fun recordLive(row: TinSession): Gone? {
        val (client, gone) = reach(row)
        if (client == null) return gone
        return try {
            sessionLog.record(row, client)
            null
        } catch (failed: TinCallException) {
            goneFrom(row, failed)
        }
    }

    /** A client for the tin [row] is on where it can still hold the session; why not where it cannot. */
    private fun reach(row: TinSession): Pair<TinClient?, Gone?> {
        val name = row.tinName
        val tin = tins.byId(row.tinRef) ?: return null to if (row.tinKind == TinKind.LOCAL) {
            Gone("Tin $name is switched off")
        } else {
            Gone("Tin $name is no longer configured")
        }
        if (row.tinKind == TinKind.LOCAL && !localHolds(row)) return null to restarted(row)
        val client = tins.clientIfRunning(tin) ?: return null to restarted(row)
        return client to null
    }

    private fun restarted(row: TinSession) =
        Gone("Tin ${row.tinName} has restarted since", "Tin ${row.tinName} has restarted since this session")

    private fun goneFrom(row: TinSession, failed: TinCallException): Gone = when {
        failed.code == "execution_not_found" -> Gone("Tin ${row.tinName} no longer has this command")
        failed.code != "session_not_found" -> Gone("Tin ${row.tinName} could not be asked: ${failed.message}")
        // Started within the second the session was opened, which localHolds
        // cannot tell apart.
        row.tinKind == TinKind.LOCAL && local.runningSince()?.isAfter(row.createdAt) == true -> restarted(row)
        else -> Gone("Tin ${row.tinName} no longer has this session")
    }

    /**
     * What one command printed, the combined stream: from the tin while it can
     * be asked, and on the local tin from what [TinSessionLog] kept of it once
     * it cannot. A remote tin's is unavailable once it cannot be asked.
     */
    @QueryMapping
    fun sessionTinOutput(
        @Argument sessionId: Long,
        @Argument sessionTinId: Long,
        @Argument executionId: String,
        @Argument offset: Int?,
        @Argument limit: Int?,
    ): TinOutputView = readOutput(sessionId, sessionTinId, executionId, offset, limit).copy(shownLimit = settings.outputShownBytes())

    private fun readOutput(sessionId: Long, sessionTinId: Long, executionId: String, offset: Int?, limit: Int?): TinOutputView {
        val session = llmSessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        val row = sessionRows.findByIdOrNull(sessionTinId)?.takeIf { it.llmSessionId == sessionId }
            ?: throw TinNotFoundException(sessionTinId.toString())
        val from = (offset ?: 0).coerceAtLeast(0).toLong()
        val most = (limit ?: TIN_OUTPUT_PAGE).coerceIn(1, settings.outputBytes())
        val (client, unreachable) = reach(row)
        val gone = if (client == null) {
            requireNotNull(unreachable)
        } else {
            try {
                val chunk = client.output(row.remoteSessionId, executionId, TinSessionLog.COMBINED, from, most)
                val read = chunk.next - chunk.offset
                return TinOutputView(
                    text = chunk.text,
                    offset = chunk.offset.toInt(),
                    nextOffset = chunk.next.toInt(),
                    done = chunk.status != null && chunk.status != RUNNING && read < most,
                    available = true,
                    message = null,
                )
            } catch (failed: TinCallException) {
                goneFrom(row, failed)
            }
        }
        if (row.tinKind != TinKind.LOCAL) return TinOutputView("", from.toInt(), from.toInt(), false, false, gone.alone)
        val kept = sessionLog.output(requireNotNull(row.id), executionId)
            ?: return TinOutputView("", from.toInt(), from.toInt(), false, false, "${gone.reason}, and this command's output was not kept")
        val bytes = kept.text.toByteArray(Charsets.UTF_8)
        val start = maxOf(from, kept.startOffset)
        val index = (start - kept.startOffset).coerceAtMost(bytes.size.toLong()).toInt()
        val slice = bytes.copyOfRange(index, minOf(bytes.size, index + most))
        val end = index + slice.size >= bytes.size
        return TinOutputView(
            text = String(slice, Charsets.UTF_8),
            offset = start.toInt(),
            nextOffset = (start + slice.size).toInt(),
            // Kept only once it finished, so the end of what was kept is the end.
            done = end,
            available = true,
            message = if (end && kept.truncated) {
                "${gone.reason}; this is the output as last recorded, and the rest of it was not kept"
            } else {
                "${gone.reason}; this is the output as last recorded"
            },
        )
    }

    /**
     * Whether the running local tin can hold [row]: it keeps its sessions in
     * memory, so one that is not running, or was started after the session was
     * opened, holds none from before. To the second, as a stored time may have
     * lost the rest; a restart within the same second is caught by the tin
     * answering that it has no such session.
     */
    private fun localHolds(row: TinSession): Boolean {
        val since = local.runningSince() ?: return false
        return !since.truncatedTo(ChronoUnit.SECONDS).isAfter(row.createdAt)
    }

    /**
     * The tin's own word for a session, where it can be had; the one last
     * recorded where it cannot. A word that changed is written back, so the
     * row is as fresh as the last time anybody looked. A session on the local
     * tin that the running instance cannot hold is stopped, whatever was
     * recorded: nothing it had in memory survived.
     */
    private fun liveStatus(row: TinSession): String {
        if (!row.open) return row.status
        if (row.tinKind == TinKind.LOCAL && !localHolds(row)) return remembered(row, "stopped")
        val tin = tins.byId(row.tinRef) ?: return row.status
        val client = tins.clientIfRunning(tin) ?: return row.status
        val live = try {
            client.session(row.remoteSessionId).status
        } catch (failed: TinCallException) {
            if (failed.code == "session_not_found") "stopped" else return row.status
        }
        return remembered(row, live)
    }

    /** The tin's label as it is now: `local` for the local tin, null for a remote one since removed. */
    private fun labelOf(row: TinSession): String? =
        if (row.tinKind == TinKind.LOCAL) LOCAL_TIN_LABEL else row.tinId?.let { rows.findByIdOrNull(it) }?.label

    private fun remembered(row: TinSession, live: String): String {
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

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinOutputKeptBytes")
    fun localTinOutputKeptBytes(source: InstallationSettingsView): Int = settings.localOutputKeptBytes()

    @SchemaMapping(typeName = "InstallationSettings", field = "localTinOutputKeptBytesConfigured")
    fun localTinOutputKeptBytesConfigured(source: InstallationSettingsView): Int = DEFAULT_LOCAL_TIN_OUTPUT_KEPT

    @SchemaMapping(typeName = "InstallationSettings", field = "tinOutputShownBytes")
    fun tinOutputShownBytes(source: InstallationSettingsView): Int = settings.outputShownBytes()

    @SchemaMapping(typeName = "InstallationSettings", field = "tinOutputShownBytesConfigured")
    fun tinOutputShownBytesConfigured(source: InstallationSettingsView): Int = DEFAULT_TIN_OUTPUT_SHOWN

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
    fun setLocalTinOutputKeptBytes(@Argument bytes: Int): InstallationSettingsView =
        changed(
            if (bytes == 0) "A local tin command's output is not kept" else "Up to $bytes bytes of a finished local tin command's output are kept",
        ) { settings.setLocalOutputKeptBytes(bytes, it) }

    @MutationMapping
    fun setTinOutputShownBytes(@Argument bytes: Int): InstallationSettingsView =
        changed("A conversation shows up to $bytes bytes of one tin command's output") { settings.setOutputShownBytes(bytes, it) }

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

    /** A tin with no label is no agent's, so one is required rather than allowed to be forgotten. */
    private fun validLabel(given: String?): String =
        given?.trim()?.takeIf { it.isNotEmpty() && it.length <= 255 } ?: throw TinLabelRequiredException()

    private fun validAddress(given: String?): String =
        given?.let(Tins::normalisedAddress) ?: throw TinAddressInvalidException(given.orEmpty())

    private fun view(tin: KnownTin) = TinView(tin)

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
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
    val runtime: String?,
    val image: String?,
    val labels: String,
    val createdAt: String,
    val lastActivityAt: String,
    val expiresAt: String?,
    val runningExecutions: Int,
    val totalExecutions: Int,
    val orknuxSessionId: Long? = null,
    /** The conversation's page with this tin session open; relative, since the browser follows it. */
    val orknuxSessionUrl: String? = null,
    val agentName: String? = null,
    val active: Boolean = status in OPEN_STATUSES,
) {
    companion object {
        /** A session recorded here: the tin's own account of it where [live] is one, the record otherwise. */
        fun of(row: TinSession, status: String, live: SessionDto?, mapper: ObjectMapper) = TinRemoteSessionView(
            id = row.remoteSessionId,
            status = status,
            runtime = live?.runtime,
            image = live?.image,
            labels = live?.let { mapper.writeValueAsString(it.labels) } ?: "{}",
            createdAt = row.createdAt.toString(),
            lastActivityAt = live?.lastActivityAt ?: (row.lastUsedAt ?: row.createdAt).toString(),
            expiresAt = live?.expiresAt,
            runningExecutions = live?.executions?.running ?: 0,
            totalExecutions = live?.executions?.total ?: 0,
            orknuxSessionId = row.llmSessionId,
            orknuxSessionUrl = "/workspace/${row.workspaceId}/sessions/${row.llmSessionId}?tin=${row.id}",
            agentName = row.agentName,
        )

        /** A session the tin holds that nobody here opened. */
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
    val tinLabel: String?,
) {
    companion object {
        fun of(row: TinSession, status: String, tinLabel: String?) = SessionTinView(
            id = requireNotNull(row.id),
            tinId = row.tinRef,
            tinName = row.tinName,
            tinKind = row.tinKind,
            remoteSessionId = row.remoteSessionId,
            active = status in OPEN_STATUSES,
            status = status,
            createdAt = row.createdAt.toString(),
            lastUsedAt = row.lastUsedAt?.toString(),
            tinLabel = tinLabel,
        )
    }
}

data class TinEventView(val seq: Int, val at: String, val type: String, val data: String)

/** orktin's word for an execution that has not finished. */
private const val RUNNING = "running"

/** How many sessions the Tins page lists for one tin, newest first. */
const val TIN_SESSIONS_LISTED = 200

/** How many bytes of a command's output one read of the conversation's Tin panel asks for when it does not say. */
const val TIN_OUTPUT_PAGE = 16384

data class TinOutputView(
    val text: String,
    val offset: Int,
    val nextOffset: Int,
    val done: Boolean,
    val available: Boolean,
    val message: String?,
    val shownLimit: Int = 0,
)

data class TinLogView(val events: List<TinEventView>, val nextSeq: Int, val available: Boolean, val message: String?)

/** What the Tins page sends; see the schema for which fields a local tin reads. */
data class TinInput(
    val name: String,
    val label: String? = null,
    val address: String? = null,
    val token: String? = null,
)
