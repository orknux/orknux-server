package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.orkbox.model.CreateSessionRequest
import io.mszymanski.orknux.orkbox.model.Edit
import io.mszymanski.orknux.orkbox.model.EditRequest
import io.mszymanski.orknux.orkbox.model.ExecuteRequest
import io.mszymanski.orknux.orkbox.model.ExecutionDto
import io.mszymanski.orknux.orkbox.model.FileEntry
import io.mszymanski.orknux.orkbox.model.GitSource
import io.mszymanski.orknux.orkbox.model.MoveRequest
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.model.SearchMatch
import io.mszymanski.orknux.orkbox.model.SearchResult
import io.mszymanski.orknux.orkbox.model.SessionDto
import io.mszymanski.orknux.orkbox.model.Source
import io.mszymanski.orknux.orkbox.model.UpdateSessionRequest
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.workflow.script.StoredKind
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.Base64

/**
 * What an agent can do with a box: open a sandbox session, run commands in it,
 * work with its files, read its log, and close it.
 *
 * orkbox's Session API, one tool per thing a model would reach for, rather
 * than one tool with a verb argument: a model picks a tool by its name and
 * description, and twenty well-named tools are found where one with twenty
 * modes is misused.
 *
 * **Which boxes.** Those whose label the agent was granted - [Agent.boxLabels] -
 * checked when the menu is drawn and again on every call, so a model that
 * guessed a name it was never offered is refused by the thing that would run
 * it, and a grant taken back is taken back for sessions already open.
 *
 * **Which sessions.** An agent names a session by the handle `orkbox_open` gave
 * it - the row this side keeps, [BoxSession] - and only the handles of its own
 * conversation answer. orkbox's own id is never what is asked for, so one
 * conversation cannot reach another's sandbox by guessing it.
 *
 * **What a failure looks like.** A command that exits non-zero is a result,
 * with its code and both streams. What orkbox refuses - no such file, an edit
 * that matched nothing, a session that has stopped - comes back as an error in
 * orkbox's words with orkbox's code beside it, which is the part a model can
 * act on.
 *
 * **What is written down.** Opening and closing a session, in the audit log
 * under the agent's name. The commands themselves are in the box's own log,
 * which a conversation's Box panel reads.
 */
@Service
class BoxTools(
    private val boxes: Boxes,
    private val sessions: BoxSessionRepository,
    private val settings: BoxSettings,
    private val store: LlmSessionStore,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
    private val sessionLog: BoxSessionLog,
    private val web: WebProperties,
) {

    /** Offered while the agent holds any box label. */
    fun offered(agent: Agent): Boolean = agent.boxLabels.isNotEmpty()

    fun handles(name: String): Boolean = name in NAMES

    fun specs(): List<ToolSpec> = SPECS

    /**
     * Runs one call and answers as JSON. Never throws: a failure is something
     * the model is told, in words it can act on.
     */
    fun run(agent: Agent, name: String, arguments: String, sessionId: Long?): String {
        if (!offered(agent)) return refuse("This agent has not been given any boxes")
        val args = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return refuse("The arguments were not a JSON object")
        return try {
            when (name) {
                LIST -> list(agent)
                OPEN -> open(agent, args, sessionId)
                SESSIONS -> listSessions(agent, args, sessionId)
                CLOSE -> close(agent, args, sessionId)
                EXTEND -> extend(agent, args, sessionId)
                EXEC -> exec(agent, args, sessionId)
                EXECUTION -> execution(agent, args, sessionId)
                OUTPUT -> output(agent, args, sessionId)
                STDIN -> stdin(agent, args, sessionId)
                SIGNAL -> signal(agent, args, sessionId)
                FILES -> files(agent, args, sessionId)
                READ -> read(agent, args, sessionId)
                WRITE -> write(agent, args, sessionId)
                EDIT -> edit(agent, args, sessionId)
                STAT -> stat(agent, args, sessionId)
                DELETE -> delete(agent, args, sessionId)
                MOVE -> move(agent, args, sessionId)
                SEARCH -> search(agent, args, sessionId)
                LOG -> log(agent, args, sessionId)
                EXPORT -> export(agent, args, sessionId)
                IMPORT -> import(agent, args, sessionId)
                else -> refuse("There is no tool called $name")
            }
        } catch (refused: Refused) {
            refuse(refused.message, refused.code)
        } catch (failed: BoxCallException) {
            refuse(failed.message ?: "The box refused that", failed.code)
        } catch (failed: BoxNotFoundException) {
            refuse("That box is not there any more")
        } catch (failed: Exception) {
            log.warn("Box tool {} failed for agent {}", name, agent.name, failed)
            refuse(failed.message ?: "That could not be done")
        }
    }

    // ------------------------------------------------------------- the boxes

    private fun list(agent: Agent): String {
        val usable = boxes.usableBy(agent)
        if (usable.isEmpty()) return answer(mapOf("boxes" to emptyList<Any>(), "note" to "No box you may use is available right now."))
        return answer(
            mapOf(
                "boxes" to usable.map { box ->
                    buildMap {
                        put("name", box.name)
                        put("kind", box.kind.name.lowercase())
                        put("label", box.label)
                        val info = runCatching { boxes.client(box).info() }
                        info.onSuccess { said ->
                            put("defaultRuntime", said.defaultRuntime)
                            put("defaultTtlSeconds", said.defaultTtlSeconds)
                            put("maxTtlSeconds", said.maxTtlSeconds)
                            put(
                                "runtimes",
                                said.runtimes.map { runtime ->
                                    buildMap {
                                        put("name", runtime.name)
                                        put("available", runtime.available)
                                        runtime.unavailableReason?.let { put("unavailableReason", it) }
                                        put("os", runtime.os)
                                        put("shell", runtime.shell.joinToString(" "))
                                        runtime.defaultImage?.let { put("defaultImage", it) }
                                        if (runtime.caches.isNotEmpty()) put("caches", runtime.caches)
                                        put("networkModes", runtime.networkModes)
                                    }
                                },
                            )
                        }
                        info.onFailure { put("unreachable", it.message ?: "it did not answer") }
                    }
                },
            ),
        )
    }

    private fun open(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val conversation = sessionId ?: throw Refused("A box session belongs to a conversation, and this call has none")
        val usable = boxes.usableBy(agent)
        val asked = args.text("box")
        val box = when {
            asked != null -> usable.firstOrNull { it.name.equals(asked, ignoreCase = true) }
                ?: throw Refused(
                    "You may not use a box called $asked. The ones you may use: " +
                        usable.joinToString(", ") { it.name }.ifEmpty { "none" },
                )
            usable.size == 1 -> usable.single()
            usable.isEmpty() -> throw Refused("No box you may use is available right now")
            else -> throw Refused("Say which box: " + usable.joinToString(", ") { it.name })
        }
        val gitUrl = args.text("git_url")
        val request = CreateSessionRequest(
            runtime = args.text("runtime"),
            image = args.text("image"),
            env = args.stringMap("variables").orEmpty(),
            network = args.text("network"),
            caches = args.strings("caches").orEmpty(),
            ttlSeconds = args.long("ttl_seconds"),
            idleTimeoutSeconds = args.long("idle_timeout_seconds"),
            labels = args.stringMap("labels").orEmpty() + mapOf(
                "orknux.workspace" to agent.workspaceId.toString(),
                "orknux.session" to conversation.toString(),
                "orknux.agent" to (agent.id?.toString() ?: agent.name),
            ),
            source = gitUrl?.let { Source(GitSource(url = it, ref = args.text("git_ref"), depth = args.long("git_depth")?.toInt())) },
        )
        val client = boxes.client(box)
        val opened = client.create(request)
        val row = sessions.save(
            BoxSession(
                workspaceId = agent.workspaceId,
                llmSessionId = conversation,
                agentId = agent.id,
                agentName = agent.name,
                boxKind = box.kind,
                boxId = box.row?.id,
                boxName = box.name,
                remoteSessionId = opened.id,
                status = opened.status,
                lastUsedAt = OffsetDateTime.now(),
                closedAt = if (opened.status in OPEN_STATUSES) null else OffsetDateTime.now(),
            ),
        )
        auditRecorder.recordAutomated(
            agent.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Box session ${row.id} opened on ${box.name} by ${agent.name}",
            agent.name,
        )
        sessionLog.recordQuietly(row, client)
        return answer(
            described(row, opened) + buildMap {
                if (opened.status == "failed") {
                    put("note", "The session failed to start: ${opened.failure ?: "no reason given"}. Nothing can be run in it.")
                } else {
                    put(
                        "note",
                        "Use session ${row.id} with the other box tools. Paths are relative to the workspace root. " +
                            "Close it with orkbox_close when you are done; it is stopped by itself when it expires or sits idle. " +
                            "In your next message to the person you are working for, once, send them `url` so they can " +
                            "watch the commands as they run: one sentence in their language saying you started a box session " +
                            "and naming in a few words what you will do in it, the words for the session being the link - " +
                            "\"I've started a [box session](url) where I'll implement the requested changes.\", in Polish " +
                            "\"Rozpocząłem [sesję w skrzynce](url), będę tam implementował wymagane zmiany projektowe.\" " +
                            "In the thread they are following, as that place writes a link: in Slack <url|sesję w skrzynce>.",
                    )
                }
            },
        )
    }

    private fun listSessions(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val conversation = sessionId ?: throw Refused("This call has no conversation, so it has no box sessions")
        val closed = args.bool("include_closed") ?: false
        val rows = sessions.findByLlmSessionIdOrderByCreatedAtDescIdDesc(conversation)
            .filter { closed || it.open }
        return answer(
            mapOf(
                "sessions" to rows.map { row ->
                    val live = runCatching { clientFor(agent, row).session(row.remoteSessionId) }.getOrNull()
                    live?.let { remember(row, it.status) }
                    described(row, live)
                },
            ),
        )
    }

    private fun close(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        // Before the stop, while the box still has every event to give.
        sessionLog.recordQuietly(held.row, held.client)
        val stopped = held.client.stop(held.row.remoteSessionId)
        held.row.closedAt = OffsetDateTime.now()
        remember(held.row, stopped.status)
        // And after it, for what stopping it finished.
        sessionLog.recordQuietly(held.row, held.client)
        auditRecorder.recordAutomated(
            agent.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Box session ${held.row.id} on ${held.row.boxName} closed by ${agent.name} " +
                "after ${stopped.executions.total} commands",
            agent.name,
        )
        return answer(mapOf("session" to held.row.id, "closed" to true, "status" to stopped.status, "commandsRun" to stopped.executions.total))
    }

    private fun extend(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val ttl = args.long("ttl_seconds")
        val idle = args.long("idle_timeout_seconds")
        if (ttl == null && idle == null) throw Refused("Say ttl_seconds, idle_timeout_seconds, or both")
        val updated = held.client.update(held.row.remoteSessionId, UpdateSessionRequest(ttlSeconds = ttl, idleTimeoutSeconds = idle))
        remember(held.row, updated.status)
        sessionLog.recordQuietly(held.row, held.client)
        return answer(described(held.row, updated))
    }

    // ----------------------------------------------------------- executions

    private fun exec(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val command = args.text("command")
        val argv = args.strings("argv")?.takeIf { it.isNotEmpty() }
        if ((command == null) == (argv == null)) throw Refused("Give either command or argv, not both and not neither")
        val wait = args.bool("wait") ?: true
        val request = ExecuteRequest(
            command = command,
            argv = argv,
            cwd = args.text("cwd"),
            env = args.stringMap("variables").orEmpty(),
            stdin = args.rawText("stdin"),
            interactive = args.bool("interactive") ?: false,
            timeoutSeconds = args.long("timeout_seconds") ?: settings.commandTimeoutSeconds().toLong(),
            wait = wait,
            waitSeconds = (args.long("wait_seconds") ?: settings.commandWaitSeconds().toLong()).coerceIn(0, MOST_WAIT_SECONDS),
            maxOutputBytes = settings.outputBytes(),
        )
        val ran = held.client.execute(held.row.remoteSessionId, request)
        touch(held)
        return answer(executed(held.row, ran))
    }

    private fun execution(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val wait = args.long("wait_seconds")?.coerceIn(0, MOST_WAIT_SECONDS)
        val seen = held.client.execution(held.row.remoteSessionId, executionOf(args), wait, settings.outputBytes())
        touch(held)
        return answer(executed(held.row, seen))
    }

    private fun output(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val most = settings.outputBytes()
        val stream = args.text("stream") ?: "combined"
        val chunk = held.client.output(
            held.row.remoteSessionId,
            executionOf(args),
            stream,
            args.long("offset")?.coerceAtLeast(0) ?: 0,
            (args.long("limit")?.toInt() ?: most).coerceIn(1, most),
        )
        touch(held)
        return answer(
            mapOf(
                "stream" to stream,
                "offset" to chunk.offset,
                "nextOffset" to chunk.next,
                "status" to chunk.status,
                "text" to chunk.text,
                "note" to "Pass nextOffset as offset to read on. An empty text with a finished status is the end.",
            ),
        )
    }

    private fun stdin(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val text = args.rawText("text") ?: throw Refused("Say what text to send")
        val close = args.bool("close") ?: false
        held.client.stdin(held.row.remoteSessionId, executionOf(args), text, close)
        touch(held)
        return answer(mapOf("sent" to text.toByteArray().size, "closed" to close))
    }

    private fun signal(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val signalled = held.client.signal(held.row.remoteSessionId, executionOf(args), args.text("signal") ?: "TERM")
        touch(held)
        return answer(executed(held.row, signalled))
    }

    // ---------------------------------------------------------------- files

    private fun files(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val listing = held.client.files(
            held.row.remoteSessionId,
            args.text("path"),
            args.long("depth")?.toInt(),
            args.text("glob"),
            args.bool("include_hidden") ?: false,
            (args.long("limit")?.toInt() ?: DEFAULT_LIST).coerceIn(1, MOST_LIST),
        )
        touch(held)
        return answer(
            mapOf(
                "path" to listing.path,
                "entries" to listing.entries.map(::entry),
                "truncated" to listing.truncated,
            ),
        )
    }

    private fun read(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val path = args.text("path") ?: throw Refused("Say which file to read")
        val file = held.client.read(held.row.remoteSessionId, path, args.long("start_line")?.toInt(), args.long("end_line")?.toInt())
        touch(held)
        if (!file.text) {
            return answer(
                mapOf(
                    "path" to path,
                    "size" to file.size,
                    "binary" to true,
                    "note" to "This file is not text. Use orkbox_export to take it out as an archive into the session store.",
                ),
            )
        }
        val most = settings.outputBytes()
        val whole = String(file.bytes, Charsets.UTF_8)
        val cut = file.bytes.size > most
        return answer(
            buildMap {
                put("path", path)
                put("size", file.size)
                file.lineCount?.let { put("lineCount", it) }
                file.etag?.let { put("etag", it) }
                put("content", if (cut) String(file.bytes, 0, most, Charsets.UTF_8) else whole)
                if (cut) {
                    put("truncated", "Only the first $most bytes are here. Read the rest by start_line and end_line.")
                }
            },
        )
    }

    private fun write(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val path = args.text("path") ?: throw Refused("Say which file to write")
        val content = args.rawText("content") ?: throw Refused("Say what to write; an empty string writes an empty file")
        val written = held.client.write(held.row.remoteSessionId, path, content.toByteArray(), args.text("mode"))
        touch(held)
        return answer(mapOf("path" to written.path, "size" to written.size, "etag" to written.etag))
    }

    private fun edit(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val path = args.text("path") ?: throw Refused("Say which file to edit")
        val edits = parsed(args.path("edits"))
            ?.takeIf { it.isArray && !it.isEmpty }
            ?.values()
            ?.map { one ->
                Edit(
                    oldText = one.rawText("old_text") ?: one.rawText("oldText")
                        ?: throw Refused("Every edit needs old_text, the exact text to replace"),
                    newText = one.rawText("new_text") ?: one.rawText("newText") ?: "",
                    replaceAll = one.bool("replace_all") ?: one.bool("replaceAll") ?: false,
                )
            }
            ?: throw Refused("Give edits: an array of {old_text, new_text, replace_all}")
        val result = held.client.edit(held.row.remoteSessionId, EditRequest(path, edits))
        touch(held)
        return answer(mapOf("path" to result.path, "size" to result.size, "etag" to result.etag, "replacements" to result.replacements))
    }

    private fun stat(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val found = held.client.stat(held.row.remoteSessionId, args.text("path") ?: "")
        touch(held)
        return answer(entry(found))
    }

    private fun delete(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val path = args.text("path") ?: throw Refused("Say what to delete")
        held.client.delete(held.row.remoteSessionId, path, args.bool("recursive") ?: false)
        touch(held)
        return answer(mapOf("path" to path, "deleted" to true))
    }

    private fun move(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val from = args.text("from") ?: throw Refused("Say what to move: from")
        val to = args.text("to") ?: throw Refused("Say where to move it: to")
        held.client.move(held.row.remoteSessionId, MoveRequest(from, to, args.bool("overwrite") ?: false))
        touch(held)
        return answer(mapOf("from" to from, "to" to to, "moved" to true))
    }

    /**
     * A search of the whole workspace, made one top-level entry at a time.
     *
     * orkbox 0.1.0 hands ripgrep no path when asked to search the workspace
     * root, and ripgrep given no path reads stdin whenever stdin is not a
     * terminal - which under `docker exec` and a ProcessBuilder it never is - so
     * a root search on any runtime with `rg` came back empty while the same
     * search under a folder found everything. Naming each top-level entry
     * gives ripgrep a path every time. Hidden entries are skipped, as ripgrep
     * skips them itself. Fixed in orkbox after 0.1.0; this stays while the pin
     * and any remote box may still be on 0.1.0.
     */
    private fun searchWorkspace(client: BoxClient, id: String, request: SearchRequest): SearchResult {
        val top = client.files(id, null, 1, null, false, 1000)
        val matches = ArrayList<SearchMatch>()
        var truncated = top.truncated
        for (entry in top.entries) {
            if (entry.path.substringAfterLast('/').startsWith('.')) continue
            if (entry.type != "file" && entry.type != "directory") continue
            val left = request.maxResults - matches.size
            if (left <= 0) {
                truncated = true
                break
            }
            val part = client.search(id, request.copy(path = entry.path, maxResults = left))
            matches += part.matches
            if (part.truncated) truncated = true
        }
        return SearchResult(matches, truncated)
    }

    private fun search(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val pattern = args.rawText("pattern")?.takeIf { it.isNotEmpty() } ?: throw Refused("Say what to search for: pattern")
        val request = SearchRequest(
            pattern = pattern,
            regex = args.bool("regex") ?: false,
            path = args.text("path"),
            glob = args.text("glob"),
            caseSensitive = args.bool("case_sensitive") ?: true,
            contextLines = args.long("context_lines")?.toInt()?.coerceIn(0, 20) ?: 0,
            maxResults = args.long("max_results")?.toInt()?.coerceIn(1, 10_000) ?: 200,
        )
        val found = if (request.path.isNullOrBlank() || request.path!!.trim().trim('/', '.').isEmpty()) {
            searchWorkspace(held.client, held.row.remoteSessionId, request)
        } else {
            held.client.search(held.row.remoteSessionId, request)
        }
        touch(held)
        return answer(
            mapOf(
                "matches" to found.matches.map { match ->
                    buildMap {
                        put("path", match.path)
                        put("line", match.line)
                        put("column", match.column)
                        put("text", match.text)
                        if (match.before.isNotEmpty()) put("before", match.before)
                        if (match.after.isNotEmpty()) put("after", match.after)
                    }
                },
                "truncated" to found.truncated,
            ),
        )
    }

    private fun log(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val since = args.long("since")?.coerceAtLeast(0) ?: 0
        val page = held.client.logs(
            held.row.remoteSessionId,
            since,
            (args.long("limit")?.toInt() ?: DEFAULT_LOG).coerceIn(1, MOST_LOG),
            args.strings("types")?.takeIf { it.isNotEmpty() }?.toSet(),
        )
        touch(held)
        return answer(
            mapOf(
                "events" to page.events.map { event ->
                    mapOf("seq" to event.seq, "at" to event.at, "type" to event.type, "data" to mapper.readTree(event.data))
                },
                "nextSeq" to page.nextSeq,
            ),
        )
    }

    // ------------------------------------------------------------- archives

    private fun export(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val conversation = held.row.llmSessionId
        val path = args.text("path")
        val key = args.text("key") ?: "box.${held.row.id}.archive"
        val bytes = held.client.exportArchive(held.row.remoteSessionId, path)
        touch(held)
        store.put(conversation, key, mapper.writeValueAsString(Base64.getEncoder().encodeToString(bytes)), StoredKind(ARCHIVE_TYPE, true))
            ?.let { throw Refused("The archive could not be kept: $it") }
        return answer(
            mapOf(
                "key" to key,
                "bytes" to bytes.size,
                "contentType" to ARCHIVE_TYPE,
                "note" to "A .tar.gz of ${path ?: "the workspace"}. Hand the key to a tool that takes a file, or to orkbox_import.",
            ),
        )
    }

    private fun import(agent: Agent, args: JsonNode, sessionId: Long?): String {
        val held = held(agent, args, sessionId)
        val key = args.text("key") ?: throw Refused("Say which key holds the archive")
        val stored = store.get(held.row.llmSessionId, key) ?: throw Refused("This conversation holds nothing under $key")
        val kind = store.kindOf(held.row.llmSessionId, key)
        if (kind != null && !kind.binary) throw Refused("$key holds text, not an archive")
        val base64 = runCatching { mapper.readTree(stored) }.getOrNull()?.takeIf { it.isString }?.stringValue() ?: stored
        val bytes = runCatching { Base64.getDecoder().decode(base64.trim()) }.getOrNull()
            ?: throw Refused("$key does not hold the bytes of a file")
        val path = args.text("path")
        held.client.importArchive(held.row.remoteSessionId, path, bytes)
        touch(held)
        return answer(mapOf("key" to key, "bytes" to bytes.size, "unpackedInto" to (path ?: "the workspace root")))
    }

    // -------------------------------------------------------------- sessions

    private class Held(val row: BoxSession, val client: BoxClient)

    /**
     * The session a call names, if it is this conversation's and its box is
     * still one this agent may use.
     */
    private fun held(agent: Agent, args: JsonNode, sessionId: Long?): Held {
        val conversation = sessionId ?: throw Refused("This call has no conversation, so it has no box sessions")
        val handle = args.long("session") ?: throw Refused("Say which session: the number orkbox_open answered with")
        val row = sessions.findByIdOrNull(handle)?.takeIf { it.llmSessionId == conversation }
            ?: throw Refused("This conversation has no box session $handle. orkbox_sessions lists the ones it has.")
        return Held(row, clientFor(agent, row))
    }

    private fun clientFor(agent: Agent, row: BoxSession): BoxClient {
        val box = boxes.byId(row.boxRef) ?: throw Refused("Box ${row.boxName} is no longer configured")
        if (!boxes.mayUse(agent, box)) throw Refused("You may no longer use box ${row.boxName}")
        return boxes.client(box)
    }

    /**
     * Marks the session used, and copies what its log gained into this side's
     * record of it - so a conversation can still show what was run after the
     * box has forgotten the session. A copy that fails fails quietly: the call
     * that ran is the agent's answer, and the next call copies again.
     */
    private fun touch(held: Held) {
        held.row.lastUsedAt = OffsetDateTime.now()
        sessions.save(held.row)
        sessionLog.recordQuietly(held.row, held.client)
    }

    private fun remember(row: BoxSession, status: String) {
        row.status = status
        if (status !in OPEN_STATUSES && row.closedAt == null) row.closedAt = OffsetDateTime.now()
        row.lastUsedAt = OffsetDateTime.now()
        sessions.save(row)
    }

    private fun described(row: BoxSession, live: SessionDto?): Map<String, Any?> = buildMap {
        put("session", row.id)
        put("box", row.boxName)
        put("status", live?.status ?: row.status)
        live?.let { session ->
            put("runtime", session.runtime)
            session.image?.let { put("image", it) }
            session.workdir?.let { put("workdir", it) }
            put("network", session.network)
            put("expiresAt", session.expiresAt)
            put("idleTimeoutSeconds", session.idleTimeoutSeconds)
            session.failure?.let { put("failure", it) }
            put("commandsRun", session.executions.total)
            put("commandsRunning", session.executions.running)
        }
        put("openedAt", row.createdAt.toString())
        put("url", linkOf(row))
    }

    /**
     * The conversation's page with this session open, where a person can
     * watch its commands as they run. Absolute, because it is handed to a
     * model that pastes it somewhere with no address of its own.
     */
    private fun linkOf(row: BoxSession): String =
        web.baseUrl.trim().trimEnd('/').ifEmpty { "http://localhost:5173" } +
            "/workspace/${row.workspaceId}/sessions/${row.llmSessionId}?box=${row.id}"

    private fun executed(row: BoxSession, ran: ExecutionDto): Map<String, Any?> = buildMap {
        put("session", row.id)
        put("execution", ran.id)
        put("status", ran.status)
        ran.exitCode?.let { put("exitCode", it) }
        ran.signal?.let { put("signal", it) }
        ran.durationMs?.let { put("durationMs", it) }
        put("cwd", ran.cwd)
        ran.stdout?.let { put("stdout", it) }
        ran.stderr?.let { put("stderr", it) }
        put("stdoutBytes", ran.stdoutBytes)
        put("stderrBytes", ran.stderrBytes)
        if (ran.truncated == true) {
            put(
                "truncated",
                "The output was longer than you are handed at once, so its middle was cut and a line says how much. " +
                    "Read all of it with orkbox_output.",
            )
        }
        if (ran.status == "running") {
            put(
                "note",
                "Still running. Call orkbox_execution with wait_seconds to wait for it, orkbox_output to read what it has " +
                    "printed so far, or orkbox_signal to stop it.",
            )
        } else if (ran.status == "timed_out") {
            put("note", "It ran past its timeout and was stopped. Give timeout_seconds to allow longer.")
        }
    }

    private fun entry(found: FileEntry): Map<String, Any?> = buildMap {
        put("path", found.path)
        put("type", found.type)
        found.size?.let { put("size", it) }
        found.mode?.let { put("mode", it) }
        found.modifiedAt?.let { put("modifiedAt", it) }
        found.target?.let { put("target", it) }
    }

    private fun executionOf(args: JsonNode): String =
        args.text("execution") ?: throw Refused("Say which execution: the id orkbox_exec answered with")

    // ------------------------------------------------------------ arguments

    /** A value a model may have sent as JSON or as JSON in a string. */
    private fun parsed(node: JsonNode): JsonNode? = when {
        node.isMissingNode || node.isNull -> null
        node.isString -> runCatching { mapper.readTree(node.stringValue()) }.getOrNull()
        else -> node
    }

    private fun JsonNode.rawText(name: String): String? = path(name).let {
        when {
            it.isString -> it.stringValue()
            it.isNumber || it.isBoolean -> it.toString()
            else -> null
        }
    }

    private fun JsonNode.text(name: String): String? = rawText(name)?.trim()?.ifEmpty { null }

    private fun JsonNode.long(name: String): Long? = path(name).let {
        when {
            it.isNumber -> it.asLong()
            it.isString -> it.stringValue().trim().toDoubleOrNull()?.toLong()
            else -> null
        }
    }

    private fun JsonNode.bool(name: String): Boolean? = path(name).let {
        when {
            it.isBoolean -> it.asBoolean()
            it.isString -> it.stringValue().trim().lowercase().toBooleanStrictOrNull()
            else -> null
        }
    }

    private fun JsonNode.strings(name: String): List<String>? {
        val node = path(name)
        if (node.isString && !node.stringValue().trim().startsWith("[")) {
            return node.stringValue().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return parsed(node)?.takeIf { it.isArray }?.values()?.mapNotNull { one ->
            if (one.isString) one.stringValue() else if (one.isNumber) one.toString() else null
        }
    }

    private fun JsonNode.stringMap(name: String): Map<String, String>? =
        parsed(path(name))?.takeIf { it.isObject }?.properties()?.associate { (key, value) ->
            key to (if (value.isString) value.stringValue() else value.toString())
        }

    private fun answer(value: Any): String = mapper.writeValueAsString(value)

    private fun refuse(why: String?, code: String? = null): String =
        mapper.writeValueAsString(buildMap {
            put("error", why ?: "That could not be done")
            code?.let { put("code", it) }
        })

    /** Something this side refuses before a box is asked. */
    private class Refused(override val message: String, val code: String? = null) : RuntimeException(message)

    companion object {
        private val log = LoggerFactory.getLogger(BoxTools::class.java)

        const val LIST = "orkbox_list"
        const val OPEN = "orkbox_open"
        const val SESSIONS = "orkbox_sessions"
        const val CLOSE = "orkbox_close"
        const val EXTEND = "orkbox_extend"
        const val EXEC = "orkbox_exec"
        const val EXECUTION = "orkbox_execution"
        const val OUTPUT = "orkbox_output"
        const val STDIN = "orkbox_stdin"
        const val SIGNAL = "orkbox_signal"
        const val FILES = "orkbox_files"
        const val READ = "orkbox_read"
        const val WRITE = "orkbox_write"
        const val EDIT = "orkbox_edit"
        const val STAT = "orkbox_stat"
        const val DELETE = "orkbox_delete"
        const val MOVE = "orkbox_move"
        const val SEARCH = "orkbox_search"
        const val LOG = "orkbox_log"
        const val EXPORT = "orkbox_export"
        const val IMPORT = "orkbox_import"

        /** What orkbox waits for at most, whatever it is asked. */
        const val MOST_WAIT_SECONDS = 3600L
        const val DEFAULT_LIST = 1000
        const val MOST_LIST = 10_000
        const val DEFAULT_LOG = 200
        const val MOST_LOG = 2000
        const val ARCHIVE_TYPE = "application/gzip"

        private fun number(name: String, description: String, required: Boolean = false) =
            ToolParameterSpec(name, description, required, mapOf("type" to "integer"))

        private fun flag(name: String, description: String) =
            ToolParameterSpec(name, description, false, mapOf("type" to "boolean"))

        private fun text(name: String, description: String, required: Boolean = false) =
            ToolParameterSpec(name, description, required)

        private fun names(name: String, description: String) =
            ToolParameterSpec(name, description, false, mapOf("type" to "array", "items" to mapOf("type" to "string")))

        private fun pairs(name: String, description: String) =
            ToolParameterSpec(name, description, false, mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string")))

        private val SESSION = number("session", "The session number orkbox_open answered with.", required = true)
        private val EXECUTION_ID = text("execution", "The execution id orkbox_exec answered with.", required = true)

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                LIST,
                "Lists the boxes you may use - sandboxes where you can open a session with its own workspace and run " +
                    "commands - with what each offers: its runtimes, their operating system and shell, default " +
                    "image and limits. Call it first to choose where to open a session.",
            ),
            ToolSpec(
                OPEN,
                "Opens a session on a box: an empty workspace directory of your own, or a clone of a git repository, " +
                    "where you can run commands and work with files. Answers with the session number every other " +
                    "box tool takes. Close it with orkbox_close when done; it also stops by itself when it expires or " +
                    "sits idle.",
                listOf(
                    text("box", "Which box, by name from orkbox_list. May be left out when you may use only one."),
                    text("runtime", "docker or local, where the box offers both; its default otherwise."),
                    text("image", "The container image, for the docker runtime; the box's default otherwise."),
                    text("git_url", "A repository to clone into the workspace."),
                    text("git_ref", "The branch, tag or commit to check out; the remote's default otherwise."),
                    number("git_depth", "A shallow clone of this many commits; the full history otherwise."),
                    names("caches", "Shared dependency caches to mount, such as maven, gradle or npm (docker only)."),
                    text("network", "full or none (docker only)."),
                    pairs("variables", "Environment variables set for every command in the session, as name: value."),
                    number("ttl_seconds", "How long the session may live; the box's default otherwise."),
                    number("idle_timeout_seconds", "How long it may sit unused before it is stopped."),
                    pairs("labels", "Labels to put on the session, for your own bookkeeping."),
                ),
            ),
            ToolSpec(
                SESSIONS,
                "Lists the box sessions opened in this conversation, with their state, runtime and when they expire.",
                listOf(flag("include_closed", "Also list the ones that have stopped.")),
            ),
            ToolSpec(
                CLOSE,
                "Closes a session: stops what is running and destroys its workspace and everything in it. Take " +
                    "anything you still need out first - orkbox_read, or orkbox_export for many files.",
                listOf(SESSION),
            ),
            ToolSpec(
                EXTEND,
                "Changes how long a session lives from now, or how long it may sit idle.",
                listOf(
                    SESSION,
                    number("ttl_seconds", "How long it may live from now."),
                    number("idle_timeout_seconds", "How long it may sit unused before it is stopped."),
                ),
            ),
            ToolSpec(
                EXEC,
                "Runs one command in a session, in its workspace, and answers with the exit code and both output " +
                    "streams. A non-zero exit is an answer and not an error - read the code and the output. Waits " +
                    "for it to finish up to wait_seconds; a command still running then comes back with its " +
                    "execution id to follow with orkbox_execution or orkbox_output. Long output is cut in the middle; " +
                    "orkbox_output reads all of it.",
                listOf(
                    SESSION,
                    text("command", "The command line, run through the runtime's shell, so pipes and && work."),
                    names("argv", "Or the program and its arguments, run without a shell."),
                    text("cwd", "The directory to run in, relative to the workspace root; the root otherwise."),
                    pairs("variables", "Environment variables set for this command only, as name: value."),
                    text("stdin", "Text written to its standard input, which is then closed."),
                    flag("interactive", "Keep standard input open for orkbox_stdin."),
                    number("timeout_seconds", "Stop it after this long; the installation's default otherwise."),
                    flag("wait", "false answers at once with the execution id and leaves it running. true by default."),
                    number("wait_seconds", "How long to wait for it before answering; the installation's default otherwise."),
                ),
            ),
            ToolSpec(
                EXECUTION,
                "The state of one execution - running, succeeded, failed, timed_out or killed - with its exit code " +
                    "and output. Give wait_seconds to wait for it to finish first.",
                listOf(SESSION, EXECUTION_ID, number("wait_seconds", "Wait up to this long for it to finish.")),
            ),
            ToolSpec(
                OUTPUT,
                "Reads an execution's output from a byte offset, whole rather than cut: to read what orkbox_exec cut " +
                    "short, or to follow a command that is still running.",
                listOf(
                    SESSION,
                    EXECUTION_ID,
                    text("stream", "stdout, stderr or combined; combined by default."),
                    number("offset", "Where to start, in bytes; 0 by default. Pass the nextOffset you were given to read on."),
                    number("limit", "How many bytes at most."),
                ),
            ),
            ToolSpec(
                STDIN,
                "Writes text to the standard input of a command started with interactive true.",
                listOf(
                    SESSION,
                    EXECUTION_ID,
                    text("text", "What to write; add a newline where the program reads lines.", required = true),
                    flag("close", "Close its standard input afterwards."),
                ),
            ),
            ToolSpec(
                SIGNAL,
                "Sends a signal to a running command: TERM to ask it to stop, KILL to force it, INT or HUP.",
                listOf(SESSION, EXECUTION_ID, text("signal", "INT, TERM, KILL or HUP; TERM by default.")),
            ),
            ToolSpec(
                FILES,
                "Lists a directory in a session's workspace, honouring .gitignore.",
                listOf(
                    SESSION,
                    text("path", "The directory, relative to the workspace root; the root by default."),
                    number("depth", "How deep to go; 1 by default, unlimited with a glob."),
                    text("glob", "Only paths matching this, such as **/*.kt."),
                    flag("include_hidden", "Include hidden files."),
                    number("limit", "At most this many entries."),
                ),
            ),
            ToolSpec(
                READ,
                "Reads a text file from a session's workspace, whole or by line range.",
                listOf(
                    SESSION,
                    text("path", "The file, relative to the workspace root.", required = true),
                    number("start_line", "The first line to read, from 1."),
                    number("end_line", "The last line to read."),
                ),
            ),
            ToolSpec(
                WRITE,
                "Writes a file in a session's workspace, creating it and its directories, replacing what was there.",
                listOf(
                    SESSION,
                    text("path", "The file, relative to the workspace root.", required = true),
                    text("content", "The whole new content.", required = true),
                    text("mode", "Its permissions in octal, such as 755."),
                ),
            ),
            ToolSpec(
                EDIT,
                "Edits a file in a session's workspace by replacing exact text, all edits or none. An old_text that " +
                    "is not found, or found more than once without replace_all, refuses the whole edit.",
                listOf(
                    SESSION,
                    text("path", "The file, relative to the workspace root.", required = true),
                    ToolParameterSpec(
                        "edits",
                        "The replacements, applied in order.",
                        true,
                        mapOf(
                            "type" to "array",
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "old_text" to mapOf("type" to "string", "description" to "The exact text to replace."),
                                    "new_text" to mapOf("type" to "string", "description" to "What to put there."),
                                    "replace_all" to mapOf("type" to "boolean", "description" to "Replace every occurrence."),
                                ),
                                "required" to listOf("old_text", "new_text"),
                            ),
                        ),
                    ),
                ),
            ),
            ToolSpec(
                STAT,
                "Says whether a path exists in a session's workspace, and what it is: its type, size, mode and when it changed.",
                listOf(SESSION, text("path", "The path, relative to the workspace root.", required = true)),
            ),
            ToolSpec(
                DELETE,
                "Deletes a file or directory in a session's workspace.",
                listOf(
                    SESSION,
                    text("path", "The path, relative to the workspace root.", required = true),
                    flag("recursive", "Needed for a directory that is not empty."),
                ),
            ),
            ToolSpec(
                MOVE,
                "Moves or renames a file or directory in a session's workspace.",
                listOf(
                    SESSION,
                    text("from", "Where it is.", required = true),
                    text("to", "Where it goes.", required = true),
                    flag("overwrite", "Replace what is already there."),
                ),
            ),
            ToolSpec(
                SEARCH,
                "Searches the files in a session's workspace for text, like grep, and answers with each match's " +
                    "file, line and column.",
                listOf(
                    SESSION,
                    text("pattern", "What to find.", required = true),
                    flag("regex", "Read pattern as a regular expression."),
                    text("path", "Only under this directory."),
                    text("glob", "Only files matching this, such as **/*.kt."),
                    flag("case_sensitive", "true by default."),
                    number("context_lines", "Lines of context around each match, up to 20."),
                    number("max_results", "At most this many matches; 200 by default."),
                ),
            ),
            ToolSpec(
                LOG,
                "Reads a session's event log: what started and finished, files written, commands run and how they ended.",
                listOf(
                    SESSION,
                    number("since", "Only events after this sequence number; pass the nextSeq you were given to read on."),
                    names("types", "Only these event types, such as execution.finished; execution.output only when named."),
                    number("limit", "At most this many events."),
                ),
            ),
            ToolSpec(
                EXPORT,
                "Takes a directory or file out of a session's workspace as a .tar.gz into this conversation's store, " +
                    "under a key any tool that takes a file can be handed.",
                listOf(
                    SESSION,
                    text("path", "What to take, relative to the workspace root; the whole workspace by default."),
                    text("key", "The key to keep it under; one is made up otherwise."),
                ),
            ),
            ToolSpec(
                IMPORT,
                "Unpacks a .tar or .tar.gz held in this conversation's store into a session's workspace.",
                listOf(
                    SESSION,
                    text("key", "The key the archive is held under.", required = true),
                    text("path", "The directory to unpack into; the workspace root by default."),
                ),
            ),
        )

        val NAMES: Set<String> = SPECS.map { it.name }.toSet()
    }
}
