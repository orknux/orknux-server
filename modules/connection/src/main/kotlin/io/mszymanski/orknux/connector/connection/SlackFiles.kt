package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** One file fetched from Slack, or the sentence saying why none was. */
sealed interface SlackFile {

    /** The bytes, and what Slack says they are. */
    data class Fetched(val bytes: ByteArray, val mimetype: String, val name: String) : SlackFile {

        /** As a data URL, which is the shape a model's request takes a picture in. */
        fun asDataUrl(): String = "data:$mimetype;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    /** Nothing was read, and it is said rather than thrown: a missing picture is not a failed turn. */
    data class NotRead(val reason: String) : SlackFile
}

/**
 * Files a person attached in Slack, fetched with the connection's own token.
 *
 * Here for the reason [SlackThreads] is here: this is where the credentials
 * are. A Slack file is not a public URL - `url_private` answers 403 without the
 * bot token - so the only thing that can fetch one is the thing that holds the
 * token, and that is this module.
 *
 * What it is for is the picture somebody just sent. An agent asked "what is
 * wrong with this screenshot" used to be handed a filename: the event says a
 * file is attached, and the model reads text. A model that can see takes an
 * image part in its request, and the bytes have to come from somewhere.
 *
 * Bounded on purpose. A Slack upload can be a forty-megabyte video, and what is
 * wanted here is a screenshot - so anything past [MOST_BYTES] is refused by
 * name rather than read into this server's memory and then thrown away.
 */
@Component
class SlackFiles(
    private val connections: WorkspaceConnectionRepository,
    private val credentials: ConnectionCredentials,
    proxies: ProxyRouter,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Built from [ProxyRouter.builder], like every other outbound call on this
     * server: the file comes from Slack's own CDN, which is one more host an
     * installation's rules have something to say about.
     */
    private val http: HttpClient = proxies.builder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** The first Slack connection a workspace owns that has a token to read with. */
    private fun ownSlackConnection(workspaceId: Long): WorkspaceConnection? =
        connections.findByWorkspaceId(workspaceId, org.springframework.data.domain.Sort.by("id"))
            .firstOrNull { it.type == ConnectionType.SLACK && credentials.secretOf(it).credential != null }

    /**
     * @param url the file's `url_private`, as an event or a thread reports it.
     * @param on the only workspace whose connections this may reach, or null for
     *   a caller that answers to no one workspace. A boundary, not a filter; see
     *   [SlackThreads.read].
     */
    fun read(connectionId: Long, url: String, on: Long? = null): SlackFile {
        val named = connections.findByIdOrNull(connectionId)?.takeIf { on == null || it.workspaceId == on }
        /*
         * Or this workspace's own Slack connection, where the one the event
         * named is not this workspace's. Issue #562: two workspaces connected to
         * one Slack app share its events - Socket Mode hands each to whichever
         * socket it likes - so a message could arrive on another workspace's
         * connection and start this workspace's workflow, and its screenshot was
         * refused as "deleted". The boundary holds: what is used instead is a
         * connection this workspace owns, with its own token.
         */
        val connection = named ?: on?.let { ownSlackConnection(it) }
            ?: return SlackFile.NotRead("the connection it would read through has been deleted")
        if (connection.type != ConnectionType.SLACK) {
            return SlackFile.NotRead("${connection.type} connections hold no files")
        }

        val token = credentials.secretOf(connection).credential
            ?: return SlackFile.NotRead("${connection.name} has no bot token stored")

        /*
         * Slack's own host, and nowhere else.
         *
         * The url arrives on an event, which is somebody else's JSON, and what
         * this call carries is a bot token. A url pointed at another host would
         * be this server handing that token over - so the host is checked
         * before the request rather than trusted because of where it was read.
         */
        val address = runCatching { URI.create(url) }.getOrNull()
            ?: return SlackFile.NotRead("that is not a url")
        val host = address.host?.lowercase()
        if (address.scheme != "https" || host == null || !SLACK_HOSTS.any { host == it || host.endsWith(".$it") }) {
            return SlackFile.NotRead("a file is only fetched from Slack's own host")
        }

        return try {
            val answer = http.send(
                HttpRequest.newBuilder(address)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )

            if (answer.statusCode() != 200) {
                return SlackFile.NotRead("Slack answered ${answer.statusCode()} for that file")
            }

            val bytes = answer.body() ?: ByteArray(0)
            if (bytes.isEmpty()) return SlackFile.NotRead("that file came back empty")
            if (bytes.size > MOST_BYTES) {
                return SlackFile.NotRead(
                    "that file is ${bytes.size / (1024 * 1024)} MB, and ${MOST_BYTES / (1024 * 1024)} is the most this reads",
                )
            }

            /*
             * What Slack said it is, and what it looks like, held against each
             * other. `content-type` on a private file is Slack's own word and
             * is usually right; the magic bytes are what it actually is, and a
             * request carrying `image/png` over something that is not a PNG is
             * a request a provider refuses in its own words.
             */
            val said = answer.headers().firstValue("content-type").orElse("").substringBefore(';').trim()
            val looks = kindOf(bytes)
            val mimetype = looks ?: said.takeIf { it.startsWith("image/") }
                ?: return SlackFile.NotRead("that file is not a picture (Slack calls it ${said.ifEmpty { "nothing" }})")

            SlackFile.Fetched(bytes, mimetype, address.path.substringAfterLast('/'))
        } catch (failure: Exception) {
            log.warn("A Slack file could not be read on connection {}", connectionId, failure)
            SlackFile.NotRead(failure.message ?: "Slack could not be reached")
        }
    }

    /**
     * What the first bytes say it is, or null for anything this does not draw.
     *
     * The four a person sends. A model's request takes these and a screenshot
     * is always one of them; anything else - a PDF, a zip, a video - is not a
     * picture and is left to the tools that fetch files on purpose.
     */
    private fun kindOf(bytes: ByteArray): String? {
        fun startsWith(vararg head: Int): Boolean =
            bytes.size >= head.size && head.withIndex().all { (at, byte) -> bytes[at] == byte.toByte() }

        return when {
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            startsWith(0x47, 0x49, 0x46, 0x38) -> "image/gif"
            // RIFF....WEBP
            bytes.size > 12 && startsWith(0x52, 0x49, 0x46, 0x46) &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }
    }

    private companion object {
        /**
         * As large a file as this reads.
         *
         * A screenshot is a few hundred kilobytes and a phone photograph a few
         * megabytes; past this it is somebody's video, which no model is going
         * to be shown. Refused by name rather than read and dropped.
         */
        const val MOST_BYTES = 8 * 1024 * 1024

        /** Where a private file lives. Nothing else is handed the bot token. */
        val SLACK_HOSTS = setOf("slack.com", "slack-edge.com", "slack-files.com")
    }
}
