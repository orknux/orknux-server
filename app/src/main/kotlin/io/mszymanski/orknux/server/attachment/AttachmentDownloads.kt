package io.mszymanski.orknux.server.attachment

import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Handing an attachment's bytes back to a browser.
 *
 * One class for every kind of attachment there is - a chat's and an issue's -
 * because what is settled here is not presentation. It is which types may be
 * shown rather than downloaded, and the headers that stop a file which is not
 * what it claims to be from running as a page; a second copy of that would
 * drift, and the copy that drifted is the one nobody would be watching.
 *
 * The caller has already decided that whoever is asking may see the workspace
 * the file belongs to. This only writes the answer.
 */
@Component
class AttachmentDownloads(private val store: AttachmentStore) {

    /**
     * A picture is served as itself so a page can show it; a document an agent
     * wrote is served as itself so a tab can read it; everything else is a
     * download.
     *
     * The difference used to be the whole of the rule: a page that renders
     * whatever was uploaded is a page that will one day render somebody's
     * HTML. What changed is not the appetite for risk, it is that the answer
     * already carries `sandbox` - a document served under it is in an opaque
     * origin with no script, no forms and no access to anything of ours, and
     * that is true whatever the bytes turn out to be. An HTML report an agent
     * wrote is then a page somebody can open and read, rather than a file they
     * download and go looking for.
     *
     * SVG stays off the list. It is markup that a browser will treat as an
     * image in contexts this header does not reach - an `<img src>` on our own
     * page, most of all - and "it is an image" is exactly the reasoning that
     * makes that a problem.
     */
    /**
     * @param reading whether the caller asked for the file to be *read* rather
     *   than handed over. A picture is shown either way - a page has to be
     *   able to draw one in an `<img>` - and a document only where somebody
     *   asked, which is its own address: see `SavedArtifactAPI.preview`.
     */
    fun serve(
        filename: String,
        contentType: String,
        sizeBytes: Long,
        location: String,
        reading: Boolean = false,
    ): ResponseEntity<InputStreamResource> {
        val name = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")
        val type = contentType.lowercase().substringBefore(';').trim()
        val readable = reading && type in READABLE
        val shown = type in SHOWABLE || readable
        return ResponseEntity.ok()
            .contentType(if (shown) MediaType.parseMediaType(contentType) else MediaType.APPLICATION_OCTET_STREAM)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                if (shown) "inline; filename*=UTF-8''$name" else "attachment; filename*=UTF-8''$name",
            )
            .header(HttpHeaders.CONTENT_LENGTH, sizeBytes.toString())
            /*
             * Nothing on this page runs and nothing it names is fetched,
             * whatever the type turns out to be.
             *
             * `sandbox` with nothing allowed is the load-bearing word: the
             * document lands in an opaque origin, so there is no script, no
             * form, no storage and no same-origin anything - our cookies are
             * not reachable from it even though it is served from our host.
             *
             * A document gets a little more than a picture does, and all of it
             * is local: styles it wrote inline, and images it carried as data.
             * Without those an agent's report is unstyled text with gaps where
             * its charts were, which is a page nobody opens twice. Nothing may
             * be fetched from anywhere - `default-src 'none'` and no scheme
             * but `data:` - so opening one cannot tell a third party that it
             * was opened.
             */
            .header(
                "Content-Security-Policy",
                if (readable) {
                    "default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:; " +
                        // Framed by this application and nothing else. Without
                        // it the document is framed by nobody at all: the
                        // default below is DENY, and the page that opens one
                        // opens it in a frame - so the reader got Chrome's
                        // "refused to connect" where the report should have
                        // been. 'self' is the whole of the widening; every
                        // deployment serves the interface and the server on
                        // one origin, the development proxy included.
                        "frame-ancestors 'self'; sandbox"
                } else {
                    "default-src 'none'; img-src 'self'; frame-ancestors 'self'; sandbox"
                },
            )
            /*
             * And the older header saying the same thing.
             *
             * Spring Security writes `DENY` on every response that does not
             * already carry this one, which is the right default for an
             * application whose pages should never be framed - and wrong for
             * the one response that exists to be read inside a frame. Set here
             * rather than relaxed in the security configuration, because
             * `SAMEORIGIN` everywhere would be a decision about every page in
             * order to fix one file.
             */
            .header("X-Frame-Options", "SAMEORIGIN")
            .header("X-Content-Type-Options", "nosniff")
            .body(InputStreamResource(store.open(location)))
    }

    companion object {
        /**
         * What may be shown rather than downloaded.
         *
         * Raster pictures, video in the three containers every browser's
         * `<video>` plays, and the three sounds a chat sends a model, which its
         * `<audio>` plays - an upload's type is made one of these words by
         * [AttachmentTypes], so `audio/x-wav` is not a fourth. SVG is deliberately absent: it is a document that
         * can carry script, and "it is an image" is exactly the reasoning that
         * makes that a problem. A video is not that: a media element decodes
         * it and runs nothing, and opened on its own it is still served under
         * the `sandbox` above, so the worst a file lying about its type does
         * is fail to play.
         */
        val SHOWABLE = setOf(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
            "video/mp4", "video/webm", "video/ogg",
            "audio/mpeg", "audio/flac", "audio/wav", "audio/ogg", "audio/mp4", "audio/aac",
        )

        /**
         * What may be read in a tab rather than downloaded.
         *
         * Documents an agent writes, and the reason they are here is that the
         * alternative is worse: a report saved as an artifact was a file
         * somebody downloaded and then opened from their machine, which is the
         * same HTML with more trust around it, not less.
         *
         * Safe because of the `sandbox` above rather than because of what is
         * in the list. PDF is here for the reader every browser has; plain
         * text and markdown because a download is an absurd way to read a
         * paragraph.
         */
        val READABLE = setOf("text/html", "text/plain", "text/markdown", "application/pdf")
    }
}
