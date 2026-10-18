package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.orkbox.model.Info
import io.mszymanski.orknux.orkbox.model.RuntimeInfo
import io.mszymanski.orknux.orkbox.model.SessionDto
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
 * The boxes, for the Boxes page, the local box's Admin settings, and a
 * conversation's Box panel.
 *
 * Administrators only, except what a conversation shows - which is that
 * conversation's workspace's - and the label suggestions an agent's form makes.
 * A box is where agents run commands, and somebody who could add one could
 * point this installation's agents at a machine of their choosing.
 *
 * Nothing here returns a token: [BoxView] says only whether one is stored.
 */
@Controller
class BoxAPI(
    private val boxes: Boxes,
    private val rows: BoxRepository,
    private val sessionRows: BoxSessionRepository,
    private val llmSessions: LlmSessionRepository,
    private val settings: BoxSettings,
    private val local: LocalBox,
    private val installationSettings: InstallationSettingsAPI,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
    private val sessionLog: BoxSessionLog,
    private val editHistory: BoxEditHistory,
    private val secrets: BoxSecrets,
    private val exposed: ExposedPorts,
    private val grants: BoxGrantsByName,
    /** A conversation's box sessions are its family's: an asker's and the subagents' it asked. */
    private val family: io.mszymanski.orknux.server.llm.SessionFamily,
) {

    // ------------------------------------------------------------------ boxes

    @QueryMapping
    fun boxes(): List<BoxView> {
        access.requireAdmin()
        return boxes.all().map(::view)
    }

    @QueryMapping
    fun box(@Argument id: String): BoxView? {
        access.requireAdmin()
        return boxes.byId(id)?.let(::view)
    }

    @QueryMapping
    fun boxInfo(@Argument id: String): BoxInfoView {
        access.requireAdmin()
        val box = boxes.byId(id) ?: throw BoxNotFoundException(id)
        val info = try {
            boxes.client(box).info()
        } catch (failed: BoxCallException) {
            throw BoxUnreachableException(box.name, failed.message)
        }
        return BoxInfoView.of(info)
    }

    /**
     * The sessions this installation recorded on [id], with the box's own word
     * for each where it can be asked, and the sessions the box holds that
     * nobody here opened - another client of a remote box. The local box holds
     * its sessions in memory, so after a restart the record is all there is.
     */
    @QueryMapping
    fun boxSessions(@Argument id: String): List<BoxRemoteSessionView> {
        access.requireAdmin()
        val box = boxes.byId(id) ?: throw BoxNotFoundException(id)
        val recorded = sessionRows.findTop200ByBoxKindAndBoxIdOrderByCreatedAtDescIdDesc(box.kind, box.row?.id)
        // The local box is not started to be told it holds nothing.
        val held = boxes.clientIfRunning(box)?.let { client ->
            try {
                client.sessions(null)
            } catch (failed: BoxCallException) {
                // With nothing recorded to show, that it could not be asked is the answer.
                if (recorded.isEmpty()) throw BoxUnreachableException(box.name, failed.message)
                null
            }
        }
        val byRemoteId = held.orEmpty().associateBy { it.id }
        val ours = recorded.map { row ->
            val live = byRemoteId[row.remoteSessionId]
            val status = when {
                live != null -> remembered(row, live.status)
                !row.open -> row.status
                row.boxKind == BoxKind.LOCAL && !localHolds(row) -> remembered(row, "stopped")
                // Asked, and it no longer lists it.
                held != null -> remembered(row, "stopped")
                else -> row.status
            }
            BoxRemoteSessionView.of(row, status, live, mapper)
        }
        val known = recorded.map { it.remoteSessionId }.toSet()
        val others = held.orEmpty().filter { it.id !in known }.map { BoxRemoteSessionView.of(it, mapper) }
        return (ours + others)
            .sortedByDescending { runCatching { OffsetDateTime.parse(it.createdAt).toInstant() }.getOrNull() }
            .take(BOX_SESSIONS_LISTED)
    }

    /** What an agent's form offers: every box's name, which is what a grant names. */
    @QueryMapping
    fun boxLabels(): List<String> {
        access.requireSignedIn()
        return boxes.all().map { it.name }.sorted()
    }

    @SchemaMapping(typeName = "Box", field = "health")
    fun health(box: BoxView): BoxHealthView? {
        val row = box.known.row ?: return localHealth(box.known)
        val (up, detail) = boxes.remote(row).health()
        return BoxHealthView(if (up) BoxHealthStatus.UP else BoxHealthStatus.DOWN, detail)
    }

    /**
     * The local box is healthy while the runtime it is set to can run a
     * session - the docker runtime with no daemon reachable is the usual way
     * it is not. Asking starts it, which is cheap, and is what the first
     * session would do anyway.
     */
    private fun localHealth(box: KnownBox): BoxHealthView = runCatching {
        val info = boxes.client(box).info()
        val runtime = info.runtimes.firstOrNull { it.name == info.defaultRuntime }
        when {
            runtime == null -> BoxHealthView(BoxHealthStatus.DOWN, "It offers no runtime")
            runtime.available -> BoxHealthView(BoxHealthStatus.UP, null)
            else -> BoxHealthView(BoxHealthStatus.DOWN, runtime.unavailableReason ?: "The ${runtime.name} runtime is not available")
        }
    }.getOrElse { BoxHealthView(BoxHealthStatus.DOWN, it.message ?: "It did not start") }

    @SchemaMapping(typeName = "Box", field = "capacity")
    fun capacity(box: BoxView): BoxCapacity? = boxes.capacity(box.known)

    @SchemaMapping(typeName = "Box", field = "activeSessions")
    fun activeSessions(box: BoxView): Int? {
        // A local box nobody has started holds none, and is not started to say so.
        val client = boxes.clientIfRunning(box.known) ?: return 0
        return try {
            client.sessions("ready").size
        } catch (_: BoxCallException) {
            null
        }
    }

    @MutationMapping
    fun createBox(@Argument input: BoxInput): BoxView {
        access.requireAdmin()
        val name = validName(input.name, null)
        val address = validAddress(input.address)
        val row = rows.save(
            Box(
                name = name,
                // Kept equal to the name for a jar older than this one, which grants by label.
                label = name,
                address = address,
                token = input.token?.trim()?.ifEmpty { null },
                lastModifiedBy = currentUser(),
            ),
        )
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Box $name added at $address")
        return view(boxes.byId(row.id.toString())!!)
    }

    @MutationMapping
    fun updateBox(@Argument id: String, @Argument input: BoxInput): BoxView {
        access.requireAdmin()
        if (id == LOCAL_BOX_ID) throw LocalBoxNotEditableException()

        val row = id.toLongOrNull()?.let { rows.findByIdOrNull(it) } ?: throw BoxNotFoundException(id)
        val before = row.name to row.address
        val name = validName(input.name, row.id)
        val address = validAddress(input.address ?: row.address)
        val tokenChanged = input.token != null && input.token.trim().ifEmpty { null } != row.token

        row.name = name
        row.address = address
        row.label = name
        if (input.token != null) row.token = input.token.trim().ifEmpty { null }
        row.lastModifiedAt = OffsetDateTime.now()
        row.lastModifiedBy = currentUser()
        rows.save(row)

        if (before.first != name) {
            // A grant names the box, so the agents granted it are granted it under its new name too.
            val following = grants.rename(before.first, name)
            auditRecorder.record(
                null,
                WorkspaceAuditCategory.INTEGRATION,
                "Box ${before.first} renamed to $name" + if (following > 0) ", and the $following agents granted it with it" else "",
            )
        }
        if (before.second != address) {
            auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Box $name moved to $address")
        }
        if (tokenChanged) {
            auditRecorder.record(
                null,
                WorkspaceAuditCategory.INTEGRATION,
                if (row.token == null) "Box $name token cleared" else "Box $name token changed",
            )
        }
        return view(boxes.byId(id)!!)
    }

    /** The stored token, for the eye on the token box; written down, as every reveal is. */
    @MutationMapping
    fun revealBoxToken(@Argument id: String): String? {
        access.requireAdmin()
        val row = id.toLongOrNull()?.let { rows.findByIdOrNull(it) } ?: throw BoxNotFoundException(id)
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Token for box ${row.name} revealed")
        return row.token
    }

    @MutationMapping
    fun deleteBox(@Argument id: String): Boolean {
        access.requireAdmin()
        if (id == LOCAL_BOX_ID) throw LocalBoxNotRemovableException()
        val row = id.toLongOrNull()?.let { rows.findByIdOrNull(it) } ?: return false
        rows.delete(row)
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Box ${row.name} removed")
        return true
    }

    /** A read: a request to /v1/info, which is not audited. */
    @MutationMapping
    fun testBoxConnection(@Argument address: String, @Argument token: String?, @Argument id: String?): BoxConnectionTestView {
        access.requireAdmin()
        val target = validAddress(address)
        val stored = if (token == null) id?.toLongOrNull()?.let { rows.findByIdOrNull(it) }?.token else null
        return try {
            val info = boxes.remote(target, token?.trim()?.ifEmpty { null } ?: stored).info()
            BoxConnectionTestView(true, "Connected to ${info.name} ${info.version}", info.version)
        } catch (failed: BoxCallException) {
            val message = when {
                failed.status == 401 -> "The box refused the token: ${failed.message}"
                failed.code == BoxCallException.UNREACHABLE -> "Could not reach the box: ${failed.message}"
                failed.code == BoxCallException.REFUSED -> "The Networking rules do not let this server call it: ${failed.message}"
                else -> "The box answered ${failed.status ?: ""} ${failed.code}: ${failed.message}".replace("  ", " ")
            }
            BoxConnectionTestView(false, message, null)
        }
    }

    // ------------------------------------------------------- a conversation

    /** The ports a conversation's agents asked to expose, oldest first, with whether each answers. */
    @QueryMapping
    fun sessionBoxPorts(@Argument sessionId: Long): List<BoxPortView> {
        val session = llmSessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        return exposed.of(sessionId).map(::portView)
    }

    @MutationMapping
    fun enableBoxPort(@Argument id: Long): BoxPortView = decide(id) { mapping, by -> exposed.enable(mapping, by) }

    @MutationMapping
    fun disableBoxPort(@Argument id: Long): BoxPortView = decide(id) { mapping, by -> exposed.disable(mapping, by) }

    @MutationMapping
    fun deleteBoxPort(@Argument id: Long): Long {
        val mapping = exposed.find(id)
        access.requireVisible(mapping.workspaceId)
        exposed.delete(mapping, currentUser())
        return id
    }

    private fun decide(id: Long, act: (BoxPortMapping, String) -> Unit): BoxPortView {
        val mapping = exposed.find(id)
        access.requireVisible(mapping.workspaceId)
        act(mapping, currentUser())
        return portView(exposed.find(id))
    }

    private fun portView(mapping: BoxPortMapping): BoxPortView {
        val row = sessionRows.findByIdOrNull(mapping.boxSessionId)
        val (alive, checkedAt) = exposed.alive(mapping)
        return BoxPortView(
            id = requireNotNull(mapping.id),
            sessionBoxId = mapping.boxSessionId,
            boxName = row?.boxName ?: "",
            boxLabel = row?.let(::labelOf),
            port = mapping.containerPort,
            reason = mapping.reason,
            state = mapping.state,
            url = mapping.url,
            note = mapping.note,
            requestedBy = mapping.requestedBy,
            createdAt = mapping.createdAt.toString(),
            decidedAt = mapping.decidedAt?.toString(),
            decidedBy = mapping.decidedBy,
            alive = alive,
            checkedAt = checkedAt?.toString(),
        )
    }

    @QueryMapping
    fun sessionBoxes(@Argument sessionId: Long): List<SessionBoxView> {
        val session = llmSessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        // The family's, as the agents see them: a subagent works in the session its asker opened.
        return sessionRows.findByLlmSessionIdInOrderByCreatedAtDescIdDesc(family.of(sessionId)).map { row ->
            SessionBoxView.of(row, liveStatus(row), labelOf(row))
        }
    }

    /**
     * A remote box's log, read from the box; unavailable once it cannot be
     * asked. The local box's as this side recorded it, brought up to date first
     * where the box can still be asked - and where it cannot, what was recorded
     * is still the log, with a message saying why it may be short; unavailable
     * only when nothing was ever recorded.
     */
    @QueryMapping
    fun sessionBoxLog(@Argument sessionId: Long, @Argument sessionBoxId: Long, @Argument after: Int?): BoxLogView {
        val session = llmSessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        val row = sessionRows.findByIdOrNull(sessionBoxId)?.takeIf { it.llmSessionId in family.of(sessionId) }
            ?: throw BoxNotFoundException(sessionBoxId.toString())
        val since = (after ?: 0).toLong().coerceAtLeast(0)
        if (row.boxKind != BoxKind.LOCAL) return remoteLog(row, since)
        val id = requireNotNull(row.id)
        val gone = recordLive(row)
        val events = sessionLog.after(id, since, BOX_LOG_PAGE).map { BoxEventView(it.seq.toInt(), it.at, it.type, it.data) }
        val next = events.lastOrNull()?.seq ?: since.toInt()
        return when {
            gone == null -> BoxLogView(events, next, true, null)
            sessionLog.any(id) -> BoxLogView(events, next, true, "${gone.reason}; this is the log as last recorded")
            else -> BoxLogView(emptyList(), since.toInt(), false, gone.alone)
        }
    }

    private fun remoteLog(row: BoxSession, since: Long): BoxLogView {
        val (client, unreachable) = reach(row)
        if (client == null) return BoxLogView(emptyList(), since.toInt(), false, requireNotNull(unreachable).alone)
        return try {
            val page = client.logs(row.remoteSessionId, since, BOX_LOG_PAGE, null)
            BoxLogView(page.events.map { BoxEventView(it.seq.toInt(), it.at, it.type, secrets.mask(row, it.data)) }, page.nextSeq.toInt(), true, null)
        } catch (failed: BoxCallException) {
            BoxLogView(emptyList(), since.toInt(), false, goneFrom(row, failed).alone)
        }
    }

    /** Why a session's box cannot be asked for its log: [reason] beside a recorded log, [alone] where there is none. */
    private class Gone(val reason: String, val alone: String = reason)

    /** Copies what the box has of the session's log; null when that worked, why not when it did not. */
    private fun recordLive(row: BoxSession): Gone? {
        val (client, gone) = reach(row)
        if (client == null) return gone
        return try {
            sessionLog.record(row, client)
            null
        } catch (failed: BoxCallException) {
            goneFrom(row, failed)
        }
    }

    /** A client for the box [row] is on where it can still hold the session; why not where it cannot. */
    private fun reach(row: BoxSession): Pair<BoxClient?, Gone?> {
        val name = row.boxName
        val box = boxes.byId(row.boxRef) ?: return null to if (row.boxKind == BoxKind.LOCAL) {
            Gone("Box $name is switched off")
        } else {
            Gone("Box $name is no longer configured")
        }
        if (row.boxKind == BoxKind.LOCAL && !localHolds(row)) return null to restarted(row)
        val client = boxes.clientIfRunning(box) ?: return null to restarted(row)
        return client to null
    }

    private fun restarted(row: BoxSession) =
        Gone("Box ${row.boxName} has restarted since", "Box ${row.boxName} has restarted since this session")

    private fun goneFrom(row: BoxSession, failed: BoxCallException): Gone = when {
        failed.code == "execution_not_found" -> Gone("Box ${row.boxName} no longer has this command")
        failed.code != "session_not_found" -> Gone("Box ${row.boxName} could not be asked: ${failed.message}")
        // Started within the second the session was opened, which localHolds
        // cannot tell apart.
        row.boxKind == BoxKind.LOCAL && local.runningSince()?.isAfter(row.createdAt) == true -> restarted(row)
        else -> Gone("Box ${row.boxName} no longer has this session")
    }

    /**
     * What one command printed, the combined stream: from the box while it can
     * be asked, and on the local box from what [BoxSessionLog] kept of it once
     * it cannot. A remote box's is unavailable once it cannot be asked.
     */
    @QueryMapping
    fun sessionBoxOutput(
        @Argument sessionId: Long,
        @Argument sessionBoxId: Long,
        @Argument executionId: String,
        @Argument offset: Int?,
        @Argument limit: Int?,
    ): BoxOutputView = readOutput(sessionId, sessionBoxId, executionId, offset, limit).copy(shownLimit = settings.outputShownBytes())

    private fun readOutput(sessionId: Long, sessionBoxId: Long, executionId: String, offset: Int?, limit: Int?): BoxOutputView {
        val session = llmSessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        val row = sessionRows.findByIdOrNull(sessionBoxId)?.takeIf { it.llmSessionId in family.of(sessionId) }
            ?: throw BoxNotFoundException(sessionBoxId.toString())
        val from = (offset ?: 0).coerceAtLeast(0).toLong()
        val most = (limit ?: BOX_OUTPUT_PAGE).coerceIn(1, settings.outputBytes())
        val (client, unreachable) = reach(row)
        val gone = if (client == null) {
            requireNotNull(unreachable)
        } else {
            try {
                val chunk = client.output(row.remoteSessionId, executionId, BoxSessionLog.COMBINED, from, most)
                val read = chunk.next - chunk.offset
                return BoxOutputView(
                    // Masked as it is read: a secret a command was given and printed shows as ***.
                    text = secrets.mask(row, chunk.text),
                    offset = chunk.offset.toInt(),
                    nextOffset = chunk.next.toInt(),
                    done = chunk.status != null && chunk.status != RUNNING && read < most,
                    available = true,
                    message = null,
                )
            } catch (failed: BoxCallException) {
                goneFrom(row, failed)
            }
        }
        if (row.boxKind != BoxKind.LOCAL) return BoxOutputView("", from.toInt(), from.toInt(), false, false, gone.alone)
        val kept = sessionLog.output(requireNotNull(row.id), executionId)
            ?: return BoxOutputView("", from.toInt(), from.toInt(), false, false, "${gone.reason}, and this command's output was not kept")
        val bytes = kept.text.toByteArray(Charsets.UTF_8)
        val start = maxOf(from, kept.startOffset)
        val index = (start - kept.startOffset).coerceAtMost(bytes.size.toLong()).toInt()
        val slice = bytes.copyOfRange(index, minOf(bytes.size, index + most))
        val end = index + slice.size >= bytes.size
        return BoxOutputView(
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
     * Whether the running local box can hold [row]: it keeps its sessions in
     * memory, so one that is not running, or was started after the session was
     * opened, holds none from before. To the second, as a stored time may have
     * lost the rest; a restart within the same second is caught by the box
     * answering that it has no such session.
     */
    private fun localHolds(row: BoxSession): Boolean {
        val since = local.runningSince() ?: return false
        return !since.truncatedTo(ChronoUnit.SECONDS).isAfter(row.createdAt)
    }

    /**
     * The box's own word for a session, where it can be had; the one last
     * recorded where it cannot. A word that changed is written back, so the
     * row is as fresh as the last time anybody looked. A session on the local
     * box that the running instance cannot hold is stopped, whatever was
     * recorded: nothing it had in memory survived.
     */
    private fun liveStatus(row: BoxSession): String {
        if (!row.open) return row.status
        if (row.boxKind == BoxKind.LOCAL && !localHolds(row)) return remembered(row, "stopped")
        val box = boxes.byId(row.boxRef) ?: return row.status
        val client = boxes.clientIfRunning(box) ?: return row.status
        val live = try {
            client.session(row.remoteSessionId).status
        } catch (failed: BoxCallException) {
            if (failed.code == "session_not_found") "stopped" else return row.status
        }
        return remembered(row, live)
    }

    /** The box's name as it is now: `local` for the local box, null for a remote one since removed. */
    private fun labelOf(row: BoxSession): String? =
        if (row.boxKind == BoxKind.LOCAL) LOCAL_BOX_NAME else row.boxId?.let { rows.findByIdOrNull(it) }?.name

    private fun remembered(row: BoxSession, live: String): String {
        if (live != row.status) {
            row.status = live
            if (live !in OPEN_STATUSES && row.closedAt == null) row.closedAt = OffsetDateTime.now()
            if (live !in OPEN_STATUSES) editHistory.forget(row)
            sessionRows.save(row)
        }
        return live
    }

    // ------------------------------------------------------------- settings

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxEnabled")
    fun localBoxEnabled(source: InstallationSettingsView): Boolean = settings.localEnabled()

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxLocation")
    fun localBoxLocation(source: InstallationSettingsView): String? = settings.localLocation()

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxLocationConfigured")
    fun localBoxLocationConfigured(source: InstallationSettingsView): String = settings.localLocationConfigured()

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxRuntime")
    fun localBoxRuntime(source: InstallationSettingsView): String = settings.localRuntime()

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxMaxSessions")
    fun localBoxMaxSessions(source: InstallationSettingsView): Int = settings.localMaxSessions()

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxMaxSessionsConfigured")
    fun localBoxMaxSessionsConfigured(source: InstallationSettingsView): Int = DEFAULT_LOCAL_BOX_SESSIONS

    @SchemaMapping(typeName = "InstallationSettings", field = "boxMaxPorts")
    fun boxMaxPorts(source: InstallationSettingsView): Int = settings.maxPorts()

    @SchemaMapping(typeName = "InstallationSettings", field = "boxMaxPortsConfigured")
    fun boxMaxPortsConfigured(source: InstallationSettingsView): Int = DEFAULT_BOX_PORTS

    @SchemaMapping(typeName = "InstallationSettings", field = "boxPortCheckSeconds")
    fun boxPortCheckSeconds(source: InstallationSettingsView): Int = settings.portCheckSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "boxPortCheckSecondsConfigured")
    fun boxPortCheckSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_BOX_PORT_CHECK_SECONDS

    @SchemaMapping(typeName = "InstallationSettings", field = "boxPortCheckWaitMillis")
    fun boxPortCheckWaitMillis(source: InstallationSettingsView): Int = settings.portCheckWaitMillis()

    @SchemaMapping(typeName = "InstallationSettings", field = "boxPortCheckWaitMillisConfigured")
    fun boxPortCheckWaitMillisConfigured(source: InstallationSettingsView): Int = DEFAULT_BOX_PORT_CHECK_WAIT

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxDefaultTtlSeconds")
    fun localBoxDefaultTtlSeconds(source: InstallationSettingsView): Int = settings.localDefaultTtlSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxDefaultTtlSecondsConfigured")
    fun localBoxDefaultTtlSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_LOCAL_BOX_TTL

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxMaxTtlSeconds")
    fun localBoxMaxTtlSeconds(source: InstallationSettingsView): Int = settings.localMaxTtlSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxMaxTtlSecondsConfigured")
    fun localBoxMaxTtlSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_LOCAL_BOX_MAX_TTL

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxIdleTimeoutSeconds")
    fun localBoxIdleTimeoutSeconds(source: InstallationSettingsView): Int = settings.localIdleTimeoutSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxIdleTimeoutSecondsConfigured")
    fun localBoxIdleTimeoutSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_LOCAL_BOX_IDLE

    @SchemaMapping(typeName = "InstallationSettings", field = "boxCommandWaitSeconds")
    fun boxCommandWaitSeconds(source: InstallationSettingsView): Int = settings.commandWaitSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "boxCommandWaitSecondsConfigured")
    fun boxCommandWaitSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_BOX_COMMAND_WAIT

    @SchemaMapping(typeName = "InstallationSettings", field = "boxCommandTimeoutSeconds")
    fun boxCommandTimeoutSeconds(source: InstallationSettingsView): Int = settings.commandTimeoutSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "boxCommandTimeoutSecondsConfigured")
    fun boxCommandTimeoutSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_BOX_COMMAND_TIMEOUT

    @SchemaMapping(typeName = "InstallationSettings", field = "boxOutputBytes")
    fun boxOutputBytes(source: InstallationSettingsView): Int = settings.outputBytes()

    @SchemaMapping(typeName = "InstallationSettings", field = "boxOutputBytesConfigured")
    fun boxOutputBytesConfigured(source: InstallationSettingsView): Int = DEFAULT_BOX_OUTPUT_BYTES

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxOutputKeptBytes")
    fun localBoxOutputKeptBytes(source: InstallationSettingsView): Int = settings.localOutputKeptBytes()

    @SchemaMapping(typeName = "InstallationSettings", field = "localBoxOutputKeptBytesConfigured")
    fun localBoxOutputKeptBytesConfigured(source: InstallationSettingsView): Int = DEFAULT_LOCAL_BOX_OUTPUT_KEPT

    @SchemaMapping(typeName = "InstallationSettings", field = "boxOutputShownBytes")
    fun boxOutputShownBytes(source: InstallationSettingsView): Int = settings.outputShownBytes()

    @SchemaMapping(typeName = "InstallationSettings", field = "boxOutputShownBytesConfigured")
    fun boxOutputShownBytesConfigured(source: InstallationSettingsView): Int = DEFAULT_BOX_OUTPUT_SHOWN

    @SchemaMapping(typeName = "InstallationSettings", field = "boxRequestTimeoutSeconds")
    fun boxRequestTimeoutSeconds(source: InstallationSettingsView): Int = settings.requestTimeoutSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "boxRequestTimeoutSecondsConfigured")
    fun boxRequestTimeoutSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_BOX_REQUEST_TIMEOUT

    @SchemaMapping(typeName = "InstallationSettings", field = "boxEditUndoSteps")
    fun boxEditUndoSteps(source: InstallationSettingsView): Int = settings.editUndoSteps()

    @SchemaMapping(typeName = "InstallationSettings", field = "boxEditUndoStepsConfigured")
    fun boxEditUndoStepsConfigured(source: InstallationSettingsView): Int = DEFAULT_BOX_EDIT_UNDO_STEPS

    @SchemaMapping(typeName = "InstallationSettings", field = "boxSessionIdleSeconds")
    fun boxSessionIdleSeconds(source: InstallationSettingsView): Int = settings.sessionIdleSeconds()

    @SchemaMapping(typeName = "InstallationSettings", field = "boxSessionIdleSecondsConfigured")
    fun boxSessionIdleSecondsConfigured(source: InstallationSettingsView): Int = DEFAULT_BOX_SESSION_IDLE

    @MutationMapping
    fun setLocalBoxEnabled(@Argument enabled: Boolean): InstallationSettingsView =
        changed(if (enabled) "The local box switched on" else "The local box switched off") {
            settings.setLocalEnabled(enabled, it)
        }

    @MutationMapping
    fun setLocalBoxLocation(@Argument location: String?): InstallationSettingsView {
        val given = location?.trim()?.ifEmpty { null }
        return changed(given?.let { "The local box makes its sessions in $it" } ?: "The local box makes its sessions in the default location") {
            settings.setLocalLocation(given, it)
        }
    }

    @MutationMapping
    fun setLocalBoxRuntime(@Argument runtime: String): InstallationSettingsView =
        changed("The local box runs sessions with the ${runtime.trim().lowercase()} runtime") { settings.setLocalRuntime(runtime, it) }

    @MutationMapping
    fun setLocalBoxMaxSessions(@Argument count: Int): InstallationSettingsView =
        changed("The local box lets up to $count sessions work at once") { settings.setLocalMaxSessions(count, it) }

    @MutationMapping
    fun setBoxMaxPorts(@Argument count: Int): InstallationSettingsView =
        changed(
            if (count == 0) "Agents may not ask to expose box ports" else "A box session may expose up to $count ports",
        ) { settings.setMaxPorts(count, it) }

    @MutationMapping
    fun setBoxPortCheckSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("Whether an exposed port answers is looked at every $seconds seconds at most") { settings.setPortCheckSeconds(seconds, it) }

    @MutationMapping
    fun setBoxPortCheckWaitMillis(@Argument millis: Int): InstallationSettingsView =
        changed("A look at an exposed port waits $millis milliseconds") { settings.setPortCheckWaitMillis(millis, it) }

    @MutationMapping
    fun setLocalBoxDefaultTtlSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("A local box session lasts $seconds seconds unless it asks otherwise") {
            settings.setLocalDefaultTtlSeconds(seconds, it)
        }

    @MutationMapping
    fun setLocalBoxMaxTtlSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("A local box session lasts at most $seconds seconds") { settings.setLocalMaxTtlSeconds(seconds, it) }

    @MutationMapping
    fun setLocalBoxIdleTimeoutSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("An idle local box session is stopped after $seconds seconds") {
            settings.setLocalIdleTimeoutSeconds(seconds, it)
        }

    @MutationMapping
    fun setBoxCommandWaitSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("An agent's box command is waited for up to $seconds seconds") { settings.setCommandWaitSeconds(seconds, it) }

    @MutationMapping
    fun setBoxCommandTimeoutSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("An agent's box command is stopped after $seconds seconds") { settings.setCommandTimeoutSeconds(seconds, it) }

    @MutationMapping
    fun setBoxOutputBytes(@Argument bytes: Int): InstallationSettingsView =
        changed("An agent is handed up to $bytes bytes of a box command's output at once") { settings.setOutputBytes(bytes, it) }

    @MutationMapping
    fun setLocalBoxOutputKeptBytes(@Argument bytes: Int): InstallationSettingsView =
        changed(
            if (bytes == 0) "A local box command's output is not kept" else "Up to $bytes bytes of a finished local box command's output are kept",
        ) { settings.setLocalOutputKeptBytes(bytes, it) }

    @MutationMapping
    fun setBoxOutputShownBytes(@Argument bytes: Int): InstallationSettingsView =
        changed("A conversation shows up to $bytes bytes of one box command's output") { settings.setOutputShownBytes(bytes, it) }

    @MutationMapping
    fun setBoxRequestTimeoutSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("A request to a box is given up on after $seconds seconds") { settings.setRequestTimeoutSeconds(seconds, it) }

    @MutationMapping
    fun setBoxSessionIdleSeconds(@Argument seconds: Int): InstallationSettingsView =
        changed("A box session an agent opens stops after $seconds seconds with no command") {
            settings.setSessionIdleSeconds(seconds, it)
        }

    @MutationMapping
    fun setBoxEditUndoSteps(@Argument steps: Int): InstallationSettingsView =
        changed(
            if (steps == 0) "An agent's box edits are not kept for undoing" else "An agent can undo its last $steps box edits",
        ) { settings.setEditUndoSteps(steps, it) }

    /** Checks, sets, applies to a running local box at once, and writes the entry - in that order. */
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
        if (name.isEmpty() || name.length > 255) throw BoxNameInvalidException(given)
        if (name.equals(LOCAL_BOX_NAME, ignoreCase = true)) throw BoxNameTakenException(name)
        rows.findByName(name)?.takeIf { it.id != self }?.let { throw BoxNameTakenException(name) }
        return name
    }

    private fun validAddress(given: String?): String =
        given?.let(Boxes::normalisedAddress) ?: throw BoxAddressInvalidException(given.orEmpty())

    private fun view(box: KnownBox) = BoxView(box)

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
}

class BoxView(val known: KnownBox) {
    val id: String get() = known.id
    val name: String get() = known.name
    val kind: BoxKind get() = known.kind
    /** The name again, for a client older than this answer: a box no longer has a label of its own. */
    val label: String? get() = known.name
    val address: String? get() = known.row?.address
    val hasToken: Boolean get() = !known.row?.token.isNullOrBlank()
    val createdAt: String? get() = known.row?.createdAt?.toString()
}

enum class BoxHealthStatus { UP, DOWN }

data class BoxHealthView(val status: BoxHealthStatus, val detail: String?)

data class BoxRuntimeView(
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
        fun of(runtime: RuntimeInfo) = BoxRuntimeView(
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

data class BoxInfoView(
    val name: String,
    val version: String,
    val defaultRuntime: String,
    val defaultTtlSeconds: Int,
    val maxTtlSeconds: Int,
    val defaultIdleTimeoutSeconds: Int,
    val maxSessions: Int,
    val runtimes: List<BoxRuntimeView>,
) {
    companion object {
        fun of(info: Info) = BoxInfoView(
            name = info.name,
            version = info.version,
            defaultRuntime = info.defaultRuntime,
            defaultTtlSeconds = info.defaultTtlSeconds.toInt(),
            maxTtlSeconds = info.maxTtlSeconds.toInt(),
            defaultIdleTimeoutSeconds = info.defaultIdleTimeoutSeconds.toInt(),
            maxSessions = info.maxSessions,
            runtimes = info.runtimes.map(BoxRuntimeView::of),
        )
    }
}

data class BoxRemoteSessionView(
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
    /** The conversation's page with this box session open; relative, since the browser follows it. */
    val orknuxSessionUrl: String? = null,
    val agentName: String? = null,
    val active: Boolean = status in OPEN_STATUSES,
) {
    companion object {
        /** A session recorded here: the box's own account of it where [live] is one, the record otherwise. */
        fun of(row: BoxSession, status: String, live: SessionDto?, mapper: ObjectMapper) = BoxRemoteSessionView(
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
            orknuxSessionUrl = "/workspace/${row.workspaceId}/sessions/${row.llmSessionId}?box=${row.id}",
            agentName = row.agentName,
        )

        /** A session the box holds that nobody here opened. */
        fun of(session: SessionDto, mapper: ObjectMapper) = BoxRemoteSessionView(
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

data class BoxConnectionTestView(val ok: Boolean, val message: String, val version: String?)

data class BoxPortView(
    val id: Long,
    val sessionBoxId: Long,
    val boxName: String,
    val boxLabel: String?,
    val port: Int,
    val reason: String?,
    val state: BoxPortState,
    val url: String?,
    val note: String?,
    val requestedBy: String?,
    val createdAt: String,
    val decidedAt: String?,
    val decidedBy: String?,
    val alive: Boolean,
    val checkedAt: String?,
)

data class SessionBoxView(
    val id: Long,
    val boxId: String,
    val boxName: String,
    val boxKind: BoxKind,
    val remoteSessionId: String,
    val active: Boolean,
    val status: String,
    val createdAt: String,
    val lastUsedAt: String?,
    val boxLabel: String?,
    /** The conversation session that opened it - another one of the family where it is shared. */
    val openedIn: Long,
    /** The agent that opened it, where it was recorded. */
    val openedBy: String?,
) {
    companion object {
        fun of(row: BoxSession, status: String, boxLabel: String?) = SessionBoxView(
            id = requireNotNull(row.id),
            boxId = row.boxRef,
            boxName = row.boxName,
            boxKind = row.boxKind,
            remoteSessionId = row.remoteSessionId,
            active = status in OPEN_STATUSES,
            status = status,
            createdAt = row.createdAt.toString(),
            lastUsedAt = row.lastUsedAt?.toString(),
            boxLabel = boxLabel,
            openedIn = row.llmSessionId,
            openedBy = row.agentName,
        )
    }
}

data class BoxEventView(val seq: Int, val at: String, val type: String, val data: String)

/** orkbox's word for an execution that has not finished. */
private const val RUNNING = "running"

/** How many sessions the Boxes page lists for one box, newest first. */
const val BOX_SESSIONS_LISTED = 200

/** How many bytes of a command's output one read of the conversation's Box panel asks for when it does not say. */
const val BOX_OUTPUT_PAGE = 16384

data class BoxOutputView(
    val text: String,
    val offset: Int,
    val nextOffset: Int,
    val done: Boolean,
    val available: Boolean,
    val message: String?,
    val shownLimit: Int = 0,
)

data class BoxLogView(val events: List<BoxEventView>, val nextSeq: Int, val available: Boolean, val message: String?)

/** What the Boxes page sends; see the schema for which fields a local box reads. */
data class BoxInput(
    val name: String,
    val label: String? = null,
    val address: String? = null,
    val token: String? = null,
)
