package io.mszymanski.orknux.server.diagram

import java.net.URI
import java.net.URISyntaxException

/**
 * What a code shape shows: one file of a GitHub repository at a ref, and
 * perhaps a stretch of its lines - read off the address a person copies from
 * the file's page, `https://github.com/owner/repo/blob/<ref>/<path>#L10-L40`.
 *
 * A ref with a slash in it (`feature/x`) cannot be told from a path by the
 * address alone - GitHub itself works it out against the repository's
 * branches - so the first segment after `blob` is taken as the ref. Copy the
 * link with a sha (`y` on the file's page) when that is wrong.
 *
 * Only github.com, for the reason [io.mszymanski.orknux.server.issue.GitHubAddress]
 * gives: an Enterprise host wears a name nothing here can recognise.
 */
data class DiagramCodeSource(
    val owner: String,
    val repo: String,
    val ref: String,
    val path: String,
    /** The first line shown, from 1; null is the file's first. */
    val fromLine: Int?,
    /** The last line shown, inclusive; null is to the end, or [fromLine] alone where only it was given. */
    val toLine: Int?,
) {
    /** What highlights it: the file's extension, lower case, or its name where it has none (`Dockerfile`). */
    val language: String
        get() {
            val name = path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(dot + 1).lowercase() else name.lowercase()
        }

    companion object {
        /** How long an address may be; a format - the column holding a link is the same width. */
        const val LENGTH = Diagrams.LINK_LENGTH

        /** How many lines one shape may name; a format - past it a shape is a file viewer, not a diagram. */
        const val MOST_LINES = 2_000

        private val RANGE = Regex("^L(\\d+)(?:-L?(\\d+))?$", RegexOption.IGNORE_CASE)

        /** The source an address names, or null when it is not a GitHub file link. */
        fun of(address: String): DiagramCodeSource? {
            val said = address.trim()
            if (said.isEmpty() || said.length > LENGTH) return null
            val uri = try {
                URI(said)
            } catch (_: URISyntaxException) {
                return null
            }
            if (uri.scheme?.lowercase() != "https" && uri.scheme?.lowercase() != "http") return null
            val host = uri.host?.lowercase() ?: return null
            if (host != "github.com" && host != "www.github.com") return null
            val parts = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
            // owner, repo, blob, ref, path...
            if (parts.size < 5 || parts[2] != "blob") return null
            val path = parts.drop(4).joinToString("/")
            var from: Int? = null
            var to: Int? = null
            uri.fragment?.takeIf { it.isNotBlank() }?.let { fragment ->
                val range = RANGE.matchEntire(fragment.trim()) ?: return null
                val first = range.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
                val last = range.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: first
                from = minOf(first, last)
                to = maxOf(first, last)
                if (to!! - from!! + 1 > MOST_LINES) return null
            }
            return DiagramCodeSource(parts[0], parts[1].removeSuffix(".git"), parts[3], path, from, to)
        }
    }
}
