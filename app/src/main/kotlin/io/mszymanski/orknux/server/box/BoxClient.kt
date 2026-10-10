package io.mszymanski.orknux.server.box

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

/**
 * One box, whichever kind: the local one embedded in this process, or a remote
 * orkbox server over HTTP. The tools and the API speak to this and never ask
 * which.
 *
 * The operations are orkbox's Session API one for one, in orkbox's own model
 * classes, so a field orkbox adds is a field both implementations carry without
 * a translation layer here to forget it. Blocking rather than `suspend`: every
 * caller is a GraphQL resolver or a tool call on a thread that is waiting
 * anyway.
 *
 * A failure orkbox reports - a session that is not there, a path outside the
 * workspace, an edit that matched nothing - is a [BoxCallException] carrying
 * orkbox's own stable code, whichever side it came from. A box that could not
 * be reached at all is one too, with the code `unreachable`.
 */
interface BoxClient {

    /** What the box is: its version, its runtimes, its defaults and limits. */
    fun info(): Info

    /** Its sessions, newest first; [status] narrows them, null is every one it still remembers. */
    fun sessions(status: String?): List<SessionDto>

    /** Opens a session; it comes back ready, or failed with the reason in `failure`. */
    fun create(request: CreateSessionRequest): SessionDto

    fun session(id: String): SessionDto

    /** Extends or shortens its lifetime, its idle timeout, or its labels. */
    fun update(id: String, request: UpdateSessionRequest): SessionDto

    /** Stops it and destroys its workspace; answers with the final session. */
    fun stop(id: String): SessionDto

    /** Runs one command, waiting as the request says; output in the answer is cut to `maxOutputBytes` per stream. */
    fun execute(id: String, request: ExecuteRequest): ExecutionDto

    /** One execution, waiting up to [waitSeconds] for it to finish where that is given. */
    fun execution(id: String, executionId: String, waitSeconds: Long?, maxOutputBytes: Int): ExecutionDto

    /** Part of an execution's output, from a byte [offset]. */
    fun output(id: String, executionId: String, stream: String, offset: Long, limit: Int): BoxOutput

    fun stdin(id: String, executionId: String, input: String, close: Boolean)

    fun signal(id: String, executionId: String, signal: String): ExecutionDto

    fun files(id: String, path: String?, depth: Int?, glob: String?, includeHidden: Boolean, limit: Int): DirectoryListing

    fun read(id: String, path: String, startLine: Int?, endLine: Int?): BoxFile

    fun write(id: String, path: String, content: ByteArray, mode: String?): WriteResult

    fun edit(id: String, request: EditRequest): EditResult

    fun stat(id: String, path: String): FileEntry

    fun delete(id: String, path: String, recursive: Boolean)

    fun move(id: String, request: MoveRequest)

    fun search(id: String, request: SearchRequest): SearchResult

    /** The session's event log after [since]; `execution.output` only where [types] asks for it. */
    fun logs(id: String, since: Long, limit: Int, types: Set<String>?): BoxLogPage

    /** [path], or the whole workspace, as a `.tar.gz`. */
    fun exportArchive(id: String, path: String?): ByteArray

    /** A `.tar` or `.tar.gz` unpacked into [path], or the workspace root. */
    fun importArchive(id: String, path: String?, archive: ByteArray)
}

/** Something a box refused or could not do, in orkbox's own words and code. */
class BoxCallException(val status: Int?, val code: String, message: String) : RuntimeException(message) {
    companion object {
        const val UNREACHABLE = "unreachable"

        /** The address guard would not let a request go to the box at all. */
        const val REFUSED = "address_refused"
    }
}

/** Part of an execution's output; [next] is where to read from next. */
data class BoxOutput(val offset: Long, val next: Long, val text: String, val status: String?)

/** A file's content and what the box said about it. */
class BoxFile(val bytes: ByteArray, val text: Boolean, val size: Long, val lineCount: Int?, val etag: String?)

/** One event of a session's log; [data] is its JSON object as text. */
data class BoxLogEvent(val seq: Long, val at: String, val type: String, val data: String)

data class BoxLogPage(val events: List<BoxLogEvent>, val nextSeq: Long)
