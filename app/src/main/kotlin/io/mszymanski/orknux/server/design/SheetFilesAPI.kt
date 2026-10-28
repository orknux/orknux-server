package io.mszymanski.orknux.server.design

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets

/**
 * A sheet as a file to keep: the whole workbook as .xlsx, or one tab as .csv -
 * the address the editor's Export opens and orknux_sheet hands out.
 *
 * REST because what crosses is a file and what asks for it is a link. The
 * access check and the audit entry are [SheetAPI]'s, and a sheet the caller
 * may not see is answered as one that is not there. Always an attachment: what
 * is in it was typed by people and models.
 */
@RestController
class SheetFilesAPI(private val api: SheetAPI) {

    @GetMapping("/api/sheets/{id}/export/{format}")
    fun export(@PathVariable id: Long, @PathVariable format: String, @RequestParam(required = false) tab: Int?): ResponseEntity<ByteArray> {
        val made = try {
            api.export(id, format, tab)
        } catch (refused: SheetFormatUnknownException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, refused.message)
        } catch (_: SheetNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(made.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(made.filename, StandardCharsets.UTF_8).build().toString(),
            )
            .header("X-Content-Type-Options", "nosniff")
            .body(made.bytes)
    }
}
