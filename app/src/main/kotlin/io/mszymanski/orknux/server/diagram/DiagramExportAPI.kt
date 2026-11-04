package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * A diagram as a download: the address `orknux_diagram_export` hands out, so a
 * person reading a chat has the file a click away.
 *
 * The access check is [DiagramAPI.find]'s, so a diagram the caller may not see
 * is answered as one that is not there. Always an attachment, and an SVG with a
 * sandbox header besides: it is made of what somebody typed.
 *
 * `?scale=1|2|3` is the editor's: a PNG at that many pixels for each drawn one.
 * The link an agent hands out carries none and gets the chat picture's size.
 */
@RestController
class DiagramExportAPI(
    private val api: DiagramAPI,
    private val export: DiagramExport,
    private val audit: WorkspaceAuditRecorder,
    /** Where this installation is reached from, for a PDF's links back into it. */
    private val web: io.mszymanski.orknux.server.security.WebProperties,
) {

    @GetMapping("/api/diagrams/{id}/export/{format}")
    fun download(
        @PathVariable id: Long,
        @PathVariable format: String,
        @RequestParam(required = false) scale: Int?,
    ): ResponseEntity<ByteArray> {
        val asked = DiagramFormat.byKey(format) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        if (scale != null && scale !in DiagramExport.SCALES) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                .body("A scale is one of ${DiagramExport.SCALES.joinToString(", ")}.".toByteArray())
        }
        val held = api.find(id)
        val file = when (val made = export.export(api.drawing(requireNotNull(held.id)), asked, scale, held.id, links = { link -> address(held.workspaceId, link) })) {
            is DiagramExport.Made.Written -> made.file
            is DiagramExport.Made.Refused -> return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.TEXT_PLAIN).body(made.reason.toByteArray())
        }
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Diagram ${held.name} exported as ${asked.name}")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(asked.contentType + if (asked.text) ";charset=UTF-8" else ""))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName(held.name, asked.extension), StandardCharsets.UTF_8).build().toString(),
            )
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'")
            .body(file.bytes)
    }

    companion object {
        /** A name a file system takes: the diagram's, with what it cannot hold made plain. */
        fun fileName(name: String, extension: String): String {
            val plain = name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "-").trim('-', ' ', '.').take(120)
            return (plain.ifEmpty { "diagram" }) + "." + extension
        }
    }

    /**
     * Where a box's link leads, as an address that opens from a PDF on
     * somebody's desk: another diagram, a document or a wireframe in this
     * workspace at this installation's own address, and a web or mail address
     * as it is. Anything else leads nowhere and is left out.
     */
    private fun address(workspaceId: Long, link: String): String? {
        val said = link.trim()
        if (Regex("^(https?://|mailto:)\\S+$", RegexOption.IGNORE_CASE).matches(said)) return said
        val inside = Regex("^(diagram|document|wireframe):(\\d+)$", RegexOption.IGNORE_CASE).find(said) ?: return null
        val (kind, id) = inside.destructured
        val path = when (kind.lowercase()) {
            "diagram" -> "explore/diagrams"
            "document" -> "design/documents"
            else -> "design/wireframes"
        }
        return "${web.baseUrl.trimEnd('/')}/workspace/$workspaceId/$path/$id"
    }
}
