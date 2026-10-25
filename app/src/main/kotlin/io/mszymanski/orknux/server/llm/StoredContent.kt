package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.workflow.script.StoredKind
import java.io.InputStream
import java.nio.file.Path

/**
 * What a key in a session's store holds, as bytes to read rather than as the
 * JSON the store keeps.
 *
 * A value a tool wrote is decoded once, as its [StoredKind] says. A file a
 * person attached is not in the store at all - the row names the attachment -
 * so it is read from the attachment store when somebody opens it, and a reader
 * that can work from a file in place ([path]) need not hold it in memory at
 * all. See [LlmSessionStore.content].
 */
class StoredContent(
    val kind: StoredKind?,
    /** How many bytes it is. */
    val size: Long,
    /** Where it lies on this machine's disk, for a reader that can read a file in place; null where it does not. */
    val path: Path?,
    /**
     * What tells this content apart from any other in the session without
     * reading it: the attachment it is, or null for a value whose bytes are
     * the only identity it has.
     */
    val identity: String?,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()

    fun bytes(): ByteArray = open().use { it.readAllBytes() }

    companion object {
        fun of(bytes: ByteArray, kind: StoredKind?): StoredContent =
            StoredContent(kind, bytes.size.toLong(), null, null) { bytes.inputStream() }
    }
}
