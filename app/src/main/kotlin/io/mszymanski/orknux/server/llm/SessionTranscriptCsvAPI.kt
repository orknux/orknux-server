package io.mszymanski.orknux.server.llm

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter

/**
 * A session's transcript as a CSV file: every line, oldest first, narrowed by
 * the same search and kinds the session page filters with.
 *
 * Whole rather than a page: the page shows twenty lines at a time, and a file
 * of the twenty on screen is not the session. Read in pages of [CHUNK] so a
 * fortnight's conversation is not one query's worth of entities at once.
 *
 * A session the caller may not see is answered as one that is not there.
 */
@RestController
class SessionTranscriptCsvAPI(
    private val events: LlmSessionEventRepository,
    private val visibility: SessionVisibility,
) {

    @GetMapping("/api/sessions/{id}/transcript.csv")
    @Transactional(readOnly = true)
    fun download(
        @PathVariable id: Long,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) kinds: List<LlmSessionEventKind>?,
    ): ResponseEntity<ByteArray> {
        // Somebody else's chat is a session that is not there, as one in an unseen workspace is.
        val session = visibility.readable(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()

        val wanted = search?.trim().orEmpty()
        val sorted = Sort.by(Sort.Order.asc("at"), Sort.Order.asc("id"))
        val csv = StringBuilder()
        // A byte-order mark, so a spreadsheet opens the file as UTF-8 rather than guessing a code page.
        csv.append('﻿')
        row(csv, HEADER)
        var page = 0
        do {
            val asked = PageRequest.of(page++, CHUNK, sorted)
            val found = if (kinds.isNullOrEmpty()) events.search(id, wanted, asked) else events.searchByKinds(id, kinds, wanted, asked)
            found.content.forEach { event ->
                row(
                    csv,
                    listOf(
                        event.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        event.kind.name,
                        event.actor,
                        event.content.orEmpty(),
                        event.result.orEmpty(),
                        event.millis?.toString().orEmpty(),
                    ),
                )
            }
        } while (found.hasNext())

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName(listOfNotNull(session.keyPrefix, session.sessionKey).joinToString(":")), StandardCharsets.UTF_8).build().toString(),
            )
            .header("X-Content-Type-Options", "nosniff")
            .body(csv.toString().toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        val HEADER = listOf("at", "kind", "actor", "content", "result", "millis")

        /** Lines read per query while the file is written. */
        const val CHUNK = 500

        /** One record, every field quoted, so a comma or a line break inside a message stays inside its cell. */
        fun row(into: StringBuilder, fields: List<String>) {
            fields.joinTo(into, ",", postfix = "\r\n") { "\"" + guarded(it).replace("\"", "\"\"") + "\"" }
        }

        /*
         * What a model wrote is opened in a spreadsheet, which runs a cell that
         * starts like a formula. A leading apostrophe makes it text; the
         * transcript on the page is unchanged.
         */
        private fun guarded(value: String): String =
            if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value

        /** A name a file system takes: the session's key, with what it cannot hold made plain. */
        fun fileName(key: String): String {
            val plain = key.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "-").trim('-', ' ', '.').take(120)
            return "session-" + plain.ifEmpty { "transcript" } + ".csv"
        }
    }
}
