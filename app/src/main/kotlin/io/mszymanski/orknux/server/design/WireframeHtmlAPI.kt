package io.mszymanski.orknux.server.design

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * A wireframe's HTML page as a download: the address `orknux_wireframe_html`
 * hands out, so a person reading a chat has the file a click away.
 *
 * REST because what crosses is a file and what asks for it is a link. The
 * access check and the audit entry are [WireframeAPI]'s, the same as the
 * editor's export, and a wireframe the caller may not see is answered as one
 * that is not there. Always an attachment and never rendered on this origin:
 * the page is made of what somebody drew, and it is a file to keep, not a page
 * of the product - the sandbox header says so to a browser that opens it anyway.
 */
@RestController
class WireframeHtmlAPI(private val api: WireframeAPI) {

    @GetMapping("/api/wireframes/{id}/html")
    fun download(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val page = api.html(id)
        return ResponseEntity.ok()
            .contentType(MediaType("text", "html", StandardCharsets.UTF_8))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(WireframeHtml.fileName(page.name, "html"), StandardCharsets.UTF_8).build().toString(),
            )
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'")
            .body(page.html.toByteArray(StandardCharsets.UTF_8))
    }
}
