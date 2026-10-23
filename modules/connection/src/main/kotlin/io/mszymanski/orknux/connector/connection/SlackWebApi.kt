package io.mszymanski.orknux.connector.connection

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** What came of one Slack Web API read made through a connection. */
sealed interface SlackRead {

    /** Slack's answer as it sent it, `ok: true` on it. */
    data class Answered(val json: JsonNode) : SlackRead

    /** Nothing was asked: no such connection, no token, a method not on the list. */
    data class NotPossible(val reason: String) : SlackRead

    /** Asked and refused - Slack's own word, and its whole answer where there was one. */
    data class Refused(val reason: String, val json: JsonNode? = null) : SlackRead
}

/** One file fetched from Slack by its id, or why not. */
sealed interface SlackDownload {

    /**
     * @param contentType Slack's `mimetype` for it, or what the download said.
     * @param file the `file` object `files.info` answered, as Slack sent it.
     */
    data class Fetched(val bytes: ByteArray, val contentType: String?, val file: JsonNode) : SlackDownload

    data class NotPossible(val reason: String) : SlackDownload

    data class Refused(val reason: String) : SlackDownload
}

/**
 * Slack Web API reads made through a connection, with the connection's own
 * token, for a caller that wants Slack's answer as Slack wrote it. Issue #647.
 *
 * The Slack plugin listed channels, users and files over its own request door
 * with a copy of the bot token - and of the user token - held in plugin
 * parameters, because nothing on this side made those calls for it. This is
 * that call, here because this is where the tokens are.
 *
 * **A list, not a pass-through.** [READ_METHODS] is every method this makes,
 * and all of them read. A door that took any method name would be a door that
 * posts, deletes and invites with the connection's token, which is what the
 * write doors - each narrow, each its own grant - exist not to be.
 *
 * Made through the SDK's own client - its endpoint, its user agent, the proxy
 * rules [SlackClients] attached to it - but answered raw rather than through
 * the SDK's typed responses: a caller that coded against Slack's JSON keeps
 * every field Slack sends, including the ones the SDK's model has not heard of.
 */
@Component
class SlackWebApi(
    private val connections: WorkspaceConnectionRepository,
    private val credentials: ConnectionCredentials,
    private val mapper: ObjectMapper,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Not `Slack.getInstance()`, for the reason [OutgoingMessages] gives. */
    private val slack = slackClients.webApi

    /**
     * One read method, with the connection's bot token or its user token.
     *
     * @param args form arguments, as Slack takes them; a `token` among them is
     *   dropped, because the connection's is the one used.
     * @param asUser the connection's user token rather than its bot token, for
     *   what Slack answers only to a person - with no fallback to the bot, so
     *   the refusal names the token that is missing.
     * @param on the only workspace whose connections this may reach, or null for
     *   a caller that answers to no one workspace. A boundary, not a filter; see
     *   [SlackThreads.read].
     */
    fun read(connectionId: Long, method: String, args: Map<String, String>, asUser: Boolean, on: Long? = null): SlackRead {
        if (method !in READ_METHODS) {
            return SlackRead.NotPossible(
                "${method.ifBlank { "that" }} is not a Slack method this reads; it reads ${READ_METHODS.joinToString(", ")}",
            )
        }
        val token = when (val held = tokenFor(connectionId, asUser, on)) {
            is Held.Token -> held.token
            is Held.Not -> return SlackRead.NotPossible(held.why)
        }
        return call(connectionId, method, args, token)
    }

    /**
     * A file, by its id: `files.info` for where it is, then its bytes from
     * `url_private_download` with the bot token.
     *
     * @param mostBytes the largest file read; refused by its reported size
     *   before anything is fetched, and again while reading, since a size is
     *   only what Slack said.
     */
    fun download(connectionId: Long, fileId: String, mostBytes: Int, on: Long? = null): SlackDownload {
        if (fileId.isBlank()) return SlackDownload.NotPossible("a download needs the file's id")
        val token = when (val held = tokenFor(connectionId, asUser = false, on)) {
            is Held.Token -> held.token
            is Held.Not -> return SlackDownload.NotPossible(held.why)
        }

        val file = when (val described = call(connectionId, "files.info", mapOf("file" to fileId), token)) {
            is SlackRead.Answered -> described.json.path("file")
            is SlackRead.Refused -> return SlackDownload.Refused(described.reason)
            is SlackRead.NotPossible -> return SlackDownload.NotPossible(described.reason)
        }
        fun text(field: String): String? = file.path(field).takeIf { it.isTextual }?.asString()?.takeIf { it.isNotBlank() }

        val reported = file.path("size").takeIf { it.isNumber }?.asLong()
        if (reported != null && reported > mostBytes) return SlackDownload.Refused(tooLarge(reported, mostBytes))

        val url = text("url_private_download") ?: text("url_private")
            ?: return SlackDownload.NotPossible("Slack gave no address to download that file from")
        if (!fromSlack(url)) return SlackDownload.Refused("a file is only fetched from Slack's own host")

        return try {
            slack.httpClient.get(url, null, token).use { answer ->
                if (answer.code != 200) return SlackDownload.Refused("Slack answered ${answer.code} for that file")
                val bytes = answer.body?.byteStream()?.use { it.readNBytes(mostBytes + 1) } ?: ByteArray(0)
                if (bytes.size > mostBytes) return SlackDownload.Refused(tooLarge(null, mostBytes))
                val said = answer.header("content-type")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
                SlackDownload.Fetched(bytes, text("mimetype") ?: said, file)
            }
        } catch (failure: Exception) {
            log.warn("A Slack file could not be downloaded on connection {}", connectionId, failure)
            SlackDownload.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    private fun call(connectionId: Long, method: String, args: Map<String, String>, token: String): SlackRead {
        val form = FormBody.Builder()
        args.filterKeys { it != "token" }.forEach { (name, value) -> form.add(name, value) }
        return try {
            slack.methods(token).runPostFormWithToken(form, method, token).use { answer ->
                val body = answer.body?.string().orEmpty()
                val json = runCatching { mapper.readTree(body) }.getOrNull()?.takeIf { it.isObject }
                    ?: return SlackRead.Refused("Slack answered ${answer.code} to $method, and not in JSON")
                when {
                    json.path("ok").asBoolean(false) -> SlackRead.Answered(json)
                    else -> SlackRead.Refused(
                        json.path("error").takeIf { it.isTextual }?.asString() ?: "Slack answered ${answer.code} to $method",
                        json,
                    )
                }
            }
        } catch (failure: Exception) {
            log.warn("Could not call Slack's {} on connection {}", method, connectionId, failure)
            SlackRead.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    private sealed interface Held {
        data class Token(val token: String) : Held
        data class Not(val why: String) : Held
    }

    private fun tokenFor(connectionId: Long, asUser: Boolean, on: Long?): Held {
        val connection = connections.findByIdOrNull(connectionId)
            ?.takeIf { on == null || it.workspaceId == on }
            // Said as though it were not there, which is what another
            // workspace's connection is to this caller.
            ?: return Held.Not("the connection it would read through has been deleted")

        if (connection.type != ConnectionType.SLACK) {
            return Held.Not("${connection.type} connections are not Slack")
        }
        if (asUser) {
            val token = credentials.userTokenOf(connection).credential
                ?: return Held.Not("${connection.name} has no user token stored")
            return Held.Token(token)
        }
        val token = credentials.secretOf(connection).credential
            ?: return Held.Not("${connection.name} has no bot token stored")
        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return Held.Not("${connection.name} has an app-level token where its bot token belongs; reading needs the xoxb- token")
        }
        return Held.Token(token)
    }

    /**
     * Slack's own hosts over https, or the host this server's Slack API is
     * configured at - the bot token goes nowhere else.
     *
     * The address comes from Slack's answer rather than from a caller, but it is
     * still an address a bearer token is about to be sent to, so it is checked
     * rather than trusted for where it was read. The configured host is on the
     * list because that is who answered `files.info` in the first place.
     */
    private fun fromSlack(url: String): Boolean {
        val address = url.toHttpUrlOrNull() ?: return false
        val host = address.host.lowercase()
        if (address.scheme == "https" && SLACK_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val api = slack.config.methodsEndpointUrlPrefix?.toHttpUrlOrNull() ?: return false
        return address.scheme == api.scheme && host == api.host.lowercase() && address.port == api.port
    }

    private fun tooLarge(size: Long?, mostBytes: Int): String {
        val most = "${mostBytes / (1024 * 1024)} MB"
        return if (size != null) "that file is $size bytes, and at most $most are fetched" else "that file is larger than $most"
    }

    companion object {
        /**
         * Every method [read] makes, all of them reads.
         *
         * What the Slack plugin's readers call, and nothing they do not: who the
         * token is, which conversations there are and what is in them, who the
         * members are, and what a file is.
         */
        val READ_METHODS: List<String> = listOf(
            "auth.test",
            "conversations.list",
            "users.conversations",
            "conversations.history",
            "conversations.replies",
            "users.list",
            "users.lookupByEmail",
            "files.info",
        )

        private const val APP_TOKEN_PREFIX = "xapp-"

        /** Where a private file lives, as [SlackFiles] has it. */
        private val SLACK_HOSTS = setOf("slack.com", "slack-edge.com", "slack-files.com")
    }
}
