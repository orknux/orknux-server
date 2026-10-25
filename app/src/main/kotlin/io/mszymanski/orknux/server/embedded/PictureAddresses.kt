package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.llm.StoredTypes
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.workflow.script.StoredKind
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.Base64

/**
 * A picture at an address, brought into the session's store so it can be
 * looked at and handed on by key.
 *
 * An agent searched for reference photos, was answered their addresses, and
 * could not see one: `picture_view` read keys only, said nothing was kept
 * under the address it was given, and the agent - which held no HTTP tool -
 * concluded it could not look at pictures at all. The address is now enough,
 * and what comes back is a key, because a key is what every tool that
 * attaches or uploads a file takes.
 *
 * **Which addresses.** Two kinds, for two reasons:
 *
 * - One a picture search answered in this conversation. The search is a grant
 *   an administrator gave, its answer is the index's and not something a page
 *   suggested, and looking at what it found is the point of having searched.
 *   Recorded per session by [record], so a URL the model composed itself is
 *   not one of them.
 * - Any other, for an agent that holds an HTTP tool, under exactly the fence
 *   those tools meet: Admin Settings -> HTTP tools switched on and its policy
 *   allowing a GET to the URL. Without an HTTP tool an agent cannot reach an
 *   address of its own choosing here either; `picture_view` is on for every
 *   agent, and must not become the unattended fetch the HTTP tools are off by
 *   default to avoid.
 *
 * Both go through [HttpCapability.bytes] - the proxy rules, the address guard
 * at every hop, a bounded read - and both are held to [Tunable.PICTURE_URL_MB].
 */
@Component
class PictureAddresses(
    private val http: HttpCapability,
    private val scratch: LlmSessionStore,
    private val mapper: ObjectMapper,
    /** The numbers this reads from Admin -> Settings. */
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    sealed interface Fetched {
        /** The picture is kept under [key], as [contentType]. */
        class Kept(val key: String, val contentType: String) : Fetched

        class Refused(val why: String) : Fetched
    }

    /** That a picture search answered these addresses in [sessionId], so it may fetch them. */
    fun record(sessionId: Long, addresses: List<String>) {
        val given = addresses.map { it.trim() }.filter(::isAddress)
        if (given.isEmpty()) return
        val held = recorded(sessionId)
        val kept = (held - given.toSet() + given).takeLast(MOST_RECORDED)
        scratch.put(sessionId, RECORD, mapper.writeValueAsString(kept), StoredKind("application/json", false))
    }

    /** Whether a picture search answered [address] in [sessionId]. */
    fun searched(sessionId: Long, address: String): Boolean = address.trim() in recorded(sessionId)

    /**
     * The picture at [address], kept in [sessionId]'s store. [agentMayFetch] is
     * whether the agent holds an HTTP tool, which is what lets it fetch an
     * address no search answered.
     */
    fun fetch(address: String, sessionId: Long, agentMayFetch: Boolean): Fetched {
        val url = address.trim()
        if (!isAddress(url)) return Fetched.Refused("\"$url\" is not an http or https address.")
        val key = keyFor(url)
        scratch.kindOf(sessionId, key)?.contentType?.let { return Fetched.Kept(key, it) }

        val follow = when {
            searched(sessionId, url) -> true
            agentMayFetch -> {
                http.mayFetch(url)?.let { return Fetched.Refused(it) }
                http.followsForAgents()
            }
            else -> return Fetched.Refused(
                "\"$url\" is not an address a picture search answered in this conversation, and you hold no " +
                    "HTTP tool to fetch an address of your own with, so nothing was fetched. Look at a picture a " +
                    "search answered, or ask for the picture to be attached.",
            )
        }

        val most = minOf(tuning[Tunable.PICTURE_URL_MB] * 1024L * 1024L, scratch.mostBinaryBytes())
        val got = when (val fetched = http.bytes(url, most, followRedirects = follow)) {
            is HttpCapability.Bytes.Refused -> return Fetched.Refused("\"$url\" was not fetched: ${fetched.why}")
            is HttpCapability.Bytes.Fetched -> fetched
        }
        if (got.status >= 400) {
            return Fetched.Refused("\"$url\" answered ${got.status}, so there is no picture there to look at.")
        }
        if (got.status >= 300) {
            return Fetched.Refused(
                "\"$url\" answered ${got.status}, a redirect, which is not followed for an address of your own " +
                    "under this installation's HTTP allow list; ask for the address it points to instead.",
            )
        }
        val type = got.contentType?.takeIf { it in StoredTypes.SHOWN_PICTURES || it == SVG }
        if (type == null) {
            val what = when (val said = got.contentType) {
                "text/html" -> "a web page"
                null -> "bytes that are no picture this reads"
                else -> said
            }
            return Fetched.Refused(
                "\"$url\" is $what, not a picture, so nothing was kept. A picture search's url is the picture " +
                    "itself; the page it came from is not.",
            )
        }
        scratch.put(sessionId, key, mapper.writeValueAsString(Base64.getEncoder().encodeToString(got.bytes)), StoredKind(type, true))
            ?.let { return Fetched.Refused("The picture at \"$url\" could not be kept: $it.") }
        return Fetched.Kept(key, type)
    }

    private fun recorded(sessionId: Long): List<String> {
        val held = scratch.get(sessionId, RECORD) ?: return emptyList()
        return runCatching { mapper.readTree(held) }.getOrNull()
            ?.takeIf { it.isArray }
            ?.values()?.mapNotNull { node -> node.takeIf { it.isTextual }?.stringValue() }
            .orEmpty()
    }

    companion object {
        /** Where a session's searched picture addresses are kept: one value, read by nobody but this. */
        const val RECORD = "web.searchedPictures"

        /** How many addresses a session remembers; the oldest are let go. */
        private const val MOST_RECORDED = 500

        private const val SVG = "image/svg+xml"

        /** Whether [text] is an http or https address, which no key a tool answers ever is. */
        fun isAddress(text: String?): Boolean {
            val given = text?.trim() ?: return false
            return given.startsWith("http://", ignoreCase = true) || given.startsWith("https://", ignoreCase = true)
        }

        /**
         * One key per address, so the same picture looked at twice is fetched
         * once. A digest rather than the address, which may be longer than a
         * key may be and should not be handed back as though it were one.
         */
        fun keyFor(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            return "picture.web." + java.util.HexFormat.of().formatHex(digest, 0, 8)
        }

        /**
         * What a tool reading a key says when it was handed an address instead.
         *
         * "Nothing in this session is kept under https://..." is true and
         * teaches the wrong thing: an agent told it went on to say it could
         * not see pictures at all. Said instead is that an address is not a
         * key, and how one becomes one - names read off the tools' own
         * declarations, so a renamed tool cannot leave this pointing nowhere.
         */
        fun notAKey(address: String): String =
            "\"${address.trim()}\" is an address, not a key: this session keeps files under keys a tool answered. " +
                "To bring a picture at an address in, pass it to ${AgentTools.VIEW_TOOL.name} as " +
                "${AgentTools.VIEW_URL}, which shows it to you and answers the key it is kept under; any other " +
                "file at an address comes in through ${BuiltInTools.HTTP_DOWNLOAD}, where you hold it."

        /** The sentence for a key nothing is kept under: [notAKey] where it is an address, else [plain]. */
        fun nothingUnder(key: String, plain: String = "Nothing in this session is kept under \"$key\"."): String =
            if (isAddress(key)) notAKey(key) else plain
    }
}
