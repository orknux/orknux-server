package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * A session's transcript downloaded as CSV: every line rather than the page on
 * screen, oldest first, in cells a spreadsheet keeps whole, and narrowed by the
 * same search and kinds the page filters with.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionTranscriptCsvTest(
    @Autowired val api: SessionTranscriptCsvAPI,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private fun session(): Long {
        val workspaceId = requireNotNull((workspaces.findByName("csv") ?: workspaces.save(Workspace(name = "csv"))).id)
        return recorder.open(workspaceId, "ticket", "csv-${System.nanoTime()}")
    }

    private fun line(session: Long, kind: LlmSessionEventKind, content: String, at: OffsetDateTime, result: String? = null) =
        events.save(LlmSessionEvent(sessionId = session, kind = kind, actor = "Desk", content = content, result = result, at = at))

    private fun text(body: ByteArray?): String = String(requireNotNull(body), Charsets.UTF_8).removePrefix("﻿")

    @Test
    fun `every line comes out oldest first, past one page of reading`() {
        val id = session()
        val start = OffsetDateTime.now().minusHours(1)
        repeat(SessionTranscriptCsvAPI.CHUNK + 3) { line(id, LlmSessionEventKind.AGENT, "line $it", start.plusSeconds(it.toLong())) }

        val answer = api.download(id, null, null)

        assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(answer.headers.contentType.toString()).startsWith("text/csv")
        assertThat(answer.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains(".csv")
        val rows = text(answer.body).trimEnd().split("\r\n")
        assertThat(rows.first()).isEqualTo("\"at\",\"kind\",\"actor\",\"content\",\"result\",\"millis\"")
        assertThat(rows).hasSize(SessionTranscriptCsvAPI.CHUNK + 4)
        assertThat(rows[1]).contains("\"line 0\"")
        assertThat(rows.last()).contains("\"line ${SessionTranscriptCsvAPI.CHUNK + 2}\"")
    }

    @Test
    fun `commas, quotes and line breaks stay inside their cell, and a formula is kept as text`() {
        val id = session()
        line(id, LlmSessionEventKind.TOOL, "a, \"quoted\"\nsecond line", OffsetDateTime.now(), result = "=HYPERLINK(\"x\")")

        val csv = text(api.download(id, null, null).body)

        assertThat(csv).contains("\"a, \"\"quoted\"\"\nsecond line\"")
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"")
    }

    @Test
    fun `the page's search and kinds narrow the file`() {
        val id = session()
        val now = OffsetDateTime.now()
        line(id, LlmSessionEventKind.USER, "where is the invoice", now)
        line(id, LlmSessionEventKind.AGENT, "the invoice is attached", now.plusSeconds(1))
        line(id, LlmSessionEventKind.AGENT, "anything else?", now.plusSeconds(2))

        val csv = text(api.download(id, "invoice", listOf(LlmSessionEventKind.AGENT)).body)

        assertThat(csv).contains("the invoice is attached").doesNotContain("where is the invoice").doesNotContain("anything else?")
    }

    @Test
    fun `a session that is not there is not found`() {
        assertThat(api.download(Long.MAX_VALUE, null, null).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
