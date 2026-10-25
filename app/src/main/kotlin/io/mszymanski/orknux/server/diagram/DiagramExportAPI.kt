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
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * A diagram as a download: the address `orknux_diagram_export` hands out, so a
 * person reading a chat has the file a click away.
 *
 * The access check is [DiagramAPI.find]'s, so a diagram the caller may not see
 * is answered as one that is not there. Always an attachment, and an SVG with a
 * sandbox header besides: it is made of what somebody typed.
 */
@RestController
class DiagramExportAPI(
    private val api: DiagramAPI,
    private val export: DiagramExport,
    private val audit: WorkspaceAuditRecorder,
) {

    @GetMapping("/api/diagrams/{id}/export/{format}")
    fun download(@PathVariable id: Long, @PathVariable format: String): ResponseEntity<ByteArray> {
        val asked = DiagramFormat.byKey(format) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val held = api.find(id)
        val file = when (val made = export.export(api.drawing(requireNotNull(held.id)), asked)) {
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
}
