package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.llm.TemporaryFileRefusedException
import io.mszymanski.orknux.server.llm.TemporaryFiles
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * The orknux_* tools that keep a file for a few minutes under a key, so it can
 * be handed from one tool to another - a document read from one place and
 * attached by `jira_attach`, say - by its key rather than its content.
 *
 * Where the file is kept is the caller's session store: an agent's
 * conversation, or over MCP the caller's own store in the workspace, which
 * the tools it calls over MCP are run in. See [TemporaryFiles] for the bounds.
 */
@Service
class FileTools(
    private val temporary: TemporaryFiles,
    private val mcpFiles: McpFiles,
    private val audit: WorkspaceAuditRecorder,
    private val web: WebProperties,
    private val mapper: ObjectMapper,
) {

    fun specs(scope: OrknuxScope): List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = KEPT,
                description = "The temporary files kept for you now: each one's key, name, type, size and how many " +
                    "seconds it has left, with how many you may keep at once.",
                parameters = emptyList(),
                summary = "Lists the temporary files you keep",
            ),
        )
        if (!scope.mayWrite) return@buildList
        add(
            ToolSpec(
                name = KEEP,
                description = "Keeps a file for a few minutes under a key, to pass to any tool that takes a contentKey " +
                    "- jira_attach, a Slack upload - instead of its content. Give the content as `text`, or as `base64` " +
                    "for bytes. Over MCP, prefer uploading the file's bytes as they are, which types nothing: leave " +
                    "both out and you are told the command. The file is gone when its time is up.",
                parameters = listOf(
                    ToolParameterSpec(FILENAME, "What the file is called, such as report.pdf; its extension says what it is.", required = true),
                    ToolParameterSpec(TEXT, "The content, where it is text.", required = false),
                    ToolParameterSpec(BASE64, "The content as base64, where it is bytes.", required = false),
                    ToolParameterSpec(CONTENT_TYPE, "What it is, such as application/pdf, where the extension does not say.", required = false),
                    ToolParameterSpec(
                        SECONDS,
                        "How long to keep it, in seconds; the longest allowed when left out. Ask for no longer than you need.",
                        required = false,
                        mapOf("type" to "integer"),
                    ),
                ),
                summary = "Keeps a file for a few minutes under a key",
            ),
        )
        add(
            ToolSpec(
                name = FORGET,
                description = "Lets go of a temporary file before its time, once the tool it was for has taken it, " +
                    "so it stops counting against how many you may keep.",
                parameters = listOf(ToolParameterSpec(KEY, "The file's key, such as ${TemporaryFiles.KEY_PREFIX}12.", required = true)),
                summary = "Lets go of a temporary file",
            ),
        )
    }

    fun handles(name: String): Boolean = name in NAMES

    fun run(scope: OrknuxScope, name: String, arguments: String): String {
        val asked = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return refuse("The arguments are not JSON")
        if (name != KEPT && !scope.mayWrite) return refuse("This conversation may read what is here, but not change it")
        val session = sessionOf(scope, create = name == KEEP)
            ?: return if (name == KEPT) answer(mapOf("files" to emptyList<Any>()) + bounds()) else refuse(
                "There is no session here to keep a file in: temporary files belong to a conversation, or to an MCP client signed in with a token",
            )
        return try {
            when (name) {
                KEPT -> kept(session)
                KEEP -> keep(scope, session, asked)
                FORGET -> forget(session, asked)
                else -> refuse("There is no tool called $name")
            }
        } catch (refused: TemporaryFileRefusedException) {
            refuse(refused.message ?: "The file was not kept")
        }
    }

    private fun sessionOf(scope: OrknuxScope, create: Boolean): Long? =
        scope.session ?: mcpFiles.sessionOf(scope.workspaceId, signedIn(), create)

    private fun kept(session: Long): String = answer(
        mapOf(
            "files" to temporary.held(session).map { file ->
                mapOf(
                    "contentKey" to TemporaryFiles.KEY_PREFIX + file.id,
                    "filename" to file.filename,
                    "contentType" to file.contentType,
                    "size" to file.size,
                    "secondsLeft" to TemporaryFiles.secondsLeft(file),
                )
            },
        ) + bounds(),
    )

    private fun keep(scope: OrknuxScope, session: Long, asked: JsonNode): String {
        val filename = asked.path(FILENAME).stringValue(null)?.trim().orEmpty()
        val text = asked.path(TEXT).stringValue(null)
        val base64 = asked.path(BASE64).stringValue(null)
        val seconds = asked.path(SECONDS).takeIf { it.isNumber || it.isTextual }?.asText()?.trim()?.toIntOrNull()
        if (text == null && base64 == null) return upload(scope, filename, seconds)
        if (text != null && base64 != null) return refuse("Give the content once: as `$TEXT` or as `$BASE64`, not both")
        val bytes = if (base64 != null) {
            runCatching { Base64.getMimeDecoder().decode(base64.trim()) }.getOrNull()
                ?: return refuse("`$BASE64` is not base64. Give text as `$TEXT` instead")
        } else {
            text!!.toByteArray(Charsets.UTF_8)
        }
        val declared = asked.path(CONTENT_TYPE).stringValue(null)
            ?: if (text != null && !filename.contains('.')) "text/plain" else null
        val kept = temporary.keep(session, scope.workspaceId, filename, declared, bytes.inputStream(), bytes.size.toLong(), seconds, who(scope))
        val left = TemporaryFiles.secondsLeft(kept.file)
        audit.record(scope.workspaceId, WorkspaceAuditCategory.CHAT, "Kept ${kept.file.filename} as a temporary file for $left seconds")
        return answer(
            mapOf(
                "contentKey" to kept.key,
                "filename" to kept.file.filename,
                "contentType" to kept.file.contentType,
                "size" to kept.file.size,
                "secondsLeft" to left,
                "note" to (
                    (if (kept.shortened) "Kept for $left seconds, the longest allowed. " else "") +
                        "Pass contentKey to a tool that takes one, and forget it with $FORGET once that is done."
                    ),
            ),
        )
    }

    /** Where a client that can run a command sends the bytes instead of typing them. */
    private fun upload(scope: OrknuxScope, filename: String, seconds: Int?): String {
        val name = filename.ifEmpty { "report.pdf" }
        val address = web.baseUrl.trimEnd('/') + McpFiles.path(scope.workspaceId) + "?filename=" +
            java.net.URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20") + (seconds?.let { "&seconds=$it" } ?: "")
        return answer(
            mapOf(
                "kept" to false,
                "upload" to "curl --data-binary @\"$name\" -H \"Authorization: Bearer <your token>\" \"$address\"",
                "note" to "Nothing was kept: give `$TEXT` or `$BASE64`, or - better, where you can run a command - upload " +
                    "the file's bytes with the command in `upload`. It answers the contentKey.",
            ),
        )
    }

    private fun forget(session: Long, asked: JsonNode): String {
        val key = asked.path(KEY).stringValue(null)?.trim().orEmpty()
        if (key.isEmpty()) return refuse("Say which file: its key, such as ${TemporaryFiles.KEY_PREFIX}12")
        return if (temporary.forget(session, key)) {
            answer(mapOf("forgotten" to key))
        } else {
            refuse("No temporary file of yours is kept under \"$key\"; it may have expired already. $KEPT lists them")
        }
    }

    private fun bounds(): Map<String, Any> = mapOf(
        "mostAtOnce" to temporary.perSession(),
        "longestSeconds" to temporary.longestSeconds(),
        "largestMb" to temporary.mostBytes() / (1024 * 1024),
    )

    private fun who(scope: OrknuxScope): String? = signedIn().ifEmpty { null } ?: scope.actor

    private fun signedIn(): String = SecurityContextHolder.getContext().authentication?.name.orEmpty()

    private fun answer(body: Map<String, Any?>): String = mapper.writeValueAsString(body)

    private fun refuse(says: String): String = mapper.writeValueAsString(mapOf("error" to says))

    companion object {
        const val KEEP = "orknux_keep_file"
        const val KEPT = "orknux_kept_files"
        const val FORGET = "orknux_forget_file"
        val NAMES = setOf(KEEP, KEPT, FORGET)

        private const val FILENAME = "filename"
        private const val TEXT = "text"
        private const val BASE64 = "base64"
        private const val CONTENT_TYPE = "contentType"
        private const val SECONDS = "seconds"
        private const val KEY = "key"
    }
}
