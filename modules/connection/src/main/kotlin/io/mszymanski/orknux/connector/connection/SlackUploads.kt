package io.mszymanski.orknux.connector.connection

import com.slack.api.methods.SlackFilesUploadV2Exception
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** What became of a file something asked to put on Slack. */
sealed interface Upload {

    /** On Slack, with the id and permalink a later message hangs it on by. */
    data class Hosted(val id: String?, val permalink: String?) : Upload

    /** Nothing happened, and it was not a failure: no such connection, no token. */
    data class NotPossible(val reason: String) : Upload

    /** Asked and refused. Slack's own word for it, passed through. */
    data class Refused(val reason: String) : Upload
}

/**
 * Puts files on Slack through a connection: bytes uploaded for Slack to host,
 * or a url registered as a remote file.
 *
 * Here for the reason [OutgoingMessages] and [SlackReactions] are here: this is
 * where the bot token is. Until this existed the Slack plugin uploaded over its
 * own HTTP door with a second copy of the token in a plugin parameter, because
 * nothing on this side could put a file on Slack - so every workspace that
 * wanted uploads kept the same credential twice, once encrypted on the
 * connection and once in a variable, and the two could disagree.
 *
 * Both halves go through the SDK, not through hand-built requests: the external
 * upload flow is three calls whose shapes Slack has already changed once
 * (`files.upload` was retired for it), and `filesUploadV2` is maintained by the
 * people who changed them. Its middle step - the bytes put to the upload url -
 * goes out on the same OkHttp client as the Web API calls, so the proxy rules
 * reach it; see [SlackClients].
 *
 * Needs `files:write` on the bot token, and `remote_files:write` and
 * `remote_files:share` for a remote file.
 */
@Component
class SlackUploads(
    private val connections: WorkspaceConnectionRepository,
    private val credentials: ConnectionCredentials,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Not `Slack.getInstance()`, for the reason [OutgoingMessages] gives. */
    private val slack = slackClients.webApi

    /**
     * Bytes put on Slack as a file it hosts.
     *
     * @param channel where to share it, or null to only upload. Uploading
     *   without sharing is how a file is hung on a message written afterwards:
     *   sharing at upload time makes Slack post the file as a message of its own.
     * @param comment what the sharing message says; ignored without a channel.
     * @param threadTs shares into that thread rather than the channel.
     * @param on the only workspace whose connections this may reach, or null for
     *   a caller that answers to no one workspace. A boundary, not a filter; see
     *   [SlackThreads.read].
     */
    fun upload(
        connectionId: Long,
        filename: String,
        bytes: ByteArray,
        title: String? = null,
        channel: String? = null,
        comment: String? = null,
        threadTs: String? = null,
        on: Long? = null,
    ): Upload {
        val token = when (val held = tokenFor(connectionId, on)) {
            is Held.Token -> held.token
            is Held.Not -> return held.why
        }
        if (filename.isBlank()) return Upload.Refused("an upload needs a filename")
        if (bytes.isEmpty()) return Upload.Refused("there is nothing to upload")

        return try {
            val answer = slack.methods(token).filesUploadV2 { request ->
                request.filename(filename).title(title?.takeIf { it.isNotBlank() } ?: filename).fileData(bytes)
                if (!channel.isNullOrBlank()) {
                    request.channel(channel)
                    if (!comment.isNullOrBlank()) request.initialComment(comment)
                    if (!threadTs.isNullOrBlank()) request.threadTs(threadTs)
                }
                request
            }

            val file = answer.file ?: answer.files?.firstOrNull()
            when {
                answer.isOk && file != null -> Upload.Hosted(file.id, file.permalink)
                else -> Upload.Refused(answer.error ?: "Slack refused the upload")
            }
        } catch (failure: SlackFilesUploadV2Exception) {
            // Thrown with every step's answer on it and no message of its own,
            // so the step that said no is the one quoted.
            Upload.Refused(refusalOf(failure))
        } catch (failure: Exception) {
            log.warn("Could not upload to Slack on connection {}", connectionId, failure)
            Upload.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    /**
     * A url registered with Slack as a remote file, and shared where a channel
     * was named.
     *
     * The url is its own `external_id`: registration is idempotent per id, so
     * registering the same document twice updates it rather than making a
     * second, and sharing needs no id kept anywhere.
     */
    fun remote(
        connectionId: Long,
        url: String,
        title: String? = null,
        filetype: String? = null,
        channel: String? = null,
        on: Long? = null,
    ): Upload {
        val token = when (val held = tokenFor(connectionId, on)) {
            is Held.Token -> held.token
            is Held.Not -> return held.why
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Upload.Refused("a remote file needs the http(s) url it lives at")
        }

        return try {
            val methods = slack.methods(token)
            val added = methods.filesRemoteAdd { request ->
                request.externalId(url).externalUrl(url).title(title?.takeIf { it.isNotBlank() } ?: url)
                if (!filetype.isNullOrBlank()) request.filetype(filetype)
                request
            }
            if (!added.isOk) return Upload.Refused(added.error ?: "Slack refused the remote file")

            if (!channel.isNullOrBlank()) {
                val shared = methods.filesRemoteShare { request -> request.externalId(url).channels(listOf(channel)) }
                if (!shared.isOk) return Upload.Refused(shared.error ?: "Slack refused to share the remote file")
            }
            Upload.Hosted(added.file?.id, added.file?.permalink)
        } catch (failure: Exception) {
            log.warn("Could not add a remote file to Slack on connection {}", connectionId, failure)
            Upload.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    private sealed interface Held {
        data class Token(val token: String) : Held
        data class Not(val why: Upload) : Held
    }

    private fun tokenFor(connectionId: Long, on: Long?): Held {
        val connection = connections.findByIdOrNull(connectionId)
            ?.takeIf { on == null || it.workspaceId == on }
            // Said as though it were not there, which is what another
            // workspace's connection is to this caller.
            ?: return Held.Not(Upload.NotPossible("the connection it would upload through has been deleted"))

        if (connection.type != ConnectionType.SLACK) {
            return Held.Not(Upload.NotPossible("${connection.type} connections hold no files"))
        }

        val token = credentials.secretOf(connection).credential
            ?: return Held.Not(Upload.NotPossible("${connection.name} has no bot token stored"))

        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return Held.Not(
                Upload.Refused(
                    "${connection.name} has an app-level token where its bot token belongs; " +
                        "uploading needs the xoxb- token",
                ),
            )
        }
        return Held.Token(token)
    }

    /** Slack's own word from whichever step refused, in the order they run. */
    private fun refusalOf(failure: SlackFilesUploadV2Exception): String {
        failure.getURLResponses.orEmpty().firstOrNull { !it.isOk }?.let {
            return it.error ?: "Slack refused to open an upload"
        }
        failure.uploadResponses.orEmpty().values.firstOrNull { it.code >= 400 }?.let {
            return "Slack's upload url answered ${it.code}"
        }
        failure.completeResponse?.takeIf { !it.isOk }?.let {
            return it.error ?: "Slack refused to complete the upload"
        }
        return failure.message ?: "Slack refused the upload"
    }

    private companion object {
        const val APP_TOKEN_PREFIX = "xapp-"
    }
}
