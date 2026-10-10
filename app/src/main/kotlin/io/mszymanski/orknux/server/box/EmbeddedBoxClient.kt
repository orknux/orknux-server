package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.orkbox.ApiException
import io.mszymanski.orknux.orkbox.Orkbox
import io.mszymanski.orknux.orkbox.model.CreateSessionRequest
import io.mszymanski.orknux.orkbox.model.DirectoryListing
import io.mszymanski.orknux.orkbox.model.EditRequest
import io.mszymanski.orknux.orkbox.model.EditResult
import io.mszymanski.orknux.orkbox.model.ExecuteRequest
import io.mszymanski.orknux.orkbox.model.ExecutionDto
import io.mszymanski.orknux.orkbox.model.FileEntry
import io.mszymanski.orknux.orkbox.model.Info
import io.mszymanski.orknux.orkbox.model.MoveRequest
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.model.SearchResult
import io.mszymanski.orknux.orkbox.model.SessionDto
import io.mszymanski.orknux.orkbox.model.UpdateSessionRequest
import io.mszymanski.orknux.orkbox.model.WriteResult
import io.mszymanski.orknux.orkbox.runtime.Signal
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

/**
 * The local box: orkbox-core in this process, behind the same [BoxClient] as a
 * remote one.
 *
 * What orkbox's HTTP routes add on top of the library - waiting for an
 * execution, cutting its output to an excerpt, filtering the log - is done here
 * the way `Routes.kt` does it, so a call answers the same on either kind.
 */
class EmbeddedBoxClient(private val orkbox: Orkbox) : BoxClient {

    private val manager get() = orkbox.sessions
    private val files get() = orkbox.files

    override fun info(): Info = call {
        val config = manager.config
        Info(
            name = "orkbox",
            version = VERSION,
            defaultRuntime = config.defaultRuntime,
            runtimes = manager.runtimes.values.map { it.info(it.unavailableReason()) },
            defaultTtlSeconds = config.defaultTtlSeconds,
            maxTtlSeconds = config.maxTtlSeconds,
            defaultIdleTimeoutSeconds = config.defaultIdleTimeoutSeconds,
            maxSessions = config.maxSessions,
        )
    }

    override fun sessions(status: String?): List<SessionDto> = call {
        manager.list(status, null, emptyMap()).map { it.toDto() }
    }

    override fun create(request: CreateSessionRequest): SessionDto = call { manager.create(request).toDto() }

    override fun session(id: String): SessionDto = call { manager.get(id).toDto() }

    override fun update(id: String, request: UpdateSessionRequest): SessionDto =
        call { manager.update(manager.get(id), request).toDto() }

    override fun stop(id: String): SessionDto = call {
        val session = manager.get(id)
        manager.stop(session, "deleted")
        session.toDto()
    }

    override fun execute(id: String, request: ExecuteRequest): ExecutionDto = call {
        val session = manager.get(id)
        val execution = manager.execute(session, request)
        if (request.wait) {
            manager.await(session, execution, (request.waitSeconds ?: DEFAULT_WAIT_SECONDS).coerceIn(0, MAX_WAIT_SECONDS))
        }
        execution.toDto(request.maxOutputBytes ?: DEFAULT_MAX_OUTPUT)
    }

    override fun execution(id: String, executionId: String, waitSeconds: Long?, maxOutputBytes: Int): ExecutionDto = call {
        val session = manager.get(id)
        val execution = session.execution(executionId)
        if (waitSeconds != null) manager.await(session, execution, waitSeconds.coerceIn(0, MAX_WAIT_SECONDS)) else session.touch()
        execution.toDto(maxOutputBytes)
    }

    override fun output(id: String, executionId: String, stream: String, offset: Long, limit: Int): BoxOutput = call {
        val session = manager.get(id)
        session.touch()
        val execution = session.execution(executionId)
        val buffer = when (stream) {
            "stdout" -> execution.stdout
            "stderr" -> execution.stderr
            "combined" -> execution.combined
            else -> throw ApiException.badRequest("invalid_stream", "stream must be stdout, stderr or combined")
        }
        val chunk = buffer.read(offset, limit.coerceAtLeast(0))
        BoxOutput(chunk.offset, chunk.next, String(chunk.bytes, Charsets.UTF_8), execution.status)
    }

    override fun stdin(id: String, executionId: String, input: String, close: Boolean) = call {
        val session = manager.get(id)
        manager.writeStdin(session, session.execution(executionId), input.toByteArray(), close)
    }

    override fun signal(id: String, executionId: String, signal: String): ExecutionDto = call {
        val session = manager.get(id)
        val execution = session.execution(executionId)
        val name = signal.uppercase().removePrefix("SIG")
        val chosen = Signal.entries.firstOrNull { it.name == name }
            ?: throw ApiException.badRequest("invalid_signal", "signal must be one of ${Signal.entries}")
        manager.signal(session, execution, chosen)
        execution.toDto()
    }

    override fun files(
        id: String,
        path: String?,
        depth: Int?,
        glob: String?,
        includeHidden: Boolean,
        limit: Int,
    ): DirectoryListing = call {
        files.list(manager.get(id), path, depth, glob, includeHidden, false, limit.coerceIn(1, 100_000))
    }

    override fun read(id: String, path: String, startLine: Int?, endLine: Int?): BoxFile = call {
        val content = files.read(manager.get(id), path, startLine, endLine)
        BoxFile(content.bytes, content.text, content.size.toLong(), content.lineCount, content.etag)
    }

    override fun write(id: String, path: String, content: ByteArray, mode: String?): WriteResult =
        call { files.write(manager.get(id), path, content, mode, null) }

    override fun edit(id: String, request: EditRequest): EditResult = call { files.edit(manager.get(id), request) }

    override fun stat(id: String, path: String): FileEntry = call { files.stat(manager.get(id), path) }

    override fun delete(id: String, path: String, recursive: Boolean) = call { files.delete(manager.get(id), path, recursive) }

    override fun move(id: String, request: MoveRequest) = call { files.move(manager.get(id), request) }

    override fun search(id: String, request: SearchRequest): SearchResult = call { files.search(manager.get(id), request) }

    override fun logs(id: String, since: Long, limit: Int, types: Set<String>?): BoxLogPage = call {
        val include: (String) -> Boolean = { type -> if (types != null) type in types else type != "execution.output" }
        val items = manager.get(id).events.after(since, limit.coerceIn(1, 10_000), include)
        BoxLogPage(
            events = items.map { BoxLogEvent(it.seq, it.at, it.type, it.data.toString()) },
            nextSeq = items.lastOrNull()?.seq ?: since,
        )
    }

    override fun exportArchive(id: String, path: String?): ByteArray = call {
        val session = manager.get(id)
        files.stat(session, path ?: "")
        ByteArrayOutputStream().also { files.exportArchive(session, path, it) }.toByteArray()
    }

    override fun importArchive(id: String, path: String?, archive: ByteArray) =
        call { files.importArchive(manager.get(id), path, ByteArrayInputStream(archive)) }

    /** One library call, on this thread, with orkbox's refusal turned into ours. */
    private fun <T> call(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (refused: ApiException) {
        throw BoxCallException(refused.status, refused.code, refused.detail ?: refused.title)
    } catch (refused: BoxCallException) {
        throw refused
    } catch (failed: Exception) {
        // What orkbox's server answers as a 500: something below the API broke.
        throw BoxCallException(500, "internal_error", failed.message ?: failed.javaClass.simpleName)
    }

    companion object {
        /** What orkbox's routes default to, so the two kinds answer alike. */
        const val DEFAULT_WAIT_SECONDS = 300L
        const val MAX_WAIT_SECONDS = 3600L
        const val DEFAULT_MAX_OUTPUT = 65536

        /** orkbox-core's own version, from the jar Maven built it into. */
        val VERSION: String by lazy {
            runCatching {
                Orkbox::class.java.getResourceAsStream("/META-INF/maven/io.mszymanski.orknux/orkbox-core/pom.properties")
                    ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
            }.getOrNull() ?: Orkbox::class.java.`package`?.implementationVersion ?: "unknown"
        }
    }
}
