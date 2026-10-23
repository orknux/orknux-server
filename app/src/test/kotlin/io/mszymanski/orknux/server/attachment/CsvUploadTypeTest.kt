package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.embedded.SheetCapability
import io.mszymanski.orknux.server.llm.LlmSession
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream

/**
 * A .csv reaches the agent as CSV text whatever the browser called it.
 *
 * The type an upload is filed under is the multipart part's own Content-Type,
 * which a browser fills from `File.type` - and on Windows that is the
 * registry's mapping, `application/vnd.ms-excel` wherever Excel is installed,
 * with `text/plain` or nothing at all (sent as `application/octet-stream`)
 * elsewhere. Filed as it came, the first and the last were kept as base64 under
 * a type no tool reads, and the agent was handed a key to bytes it could not
 * open.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-csv-upload"])
@WithMockUser(username = "alice", roles = ["ADMINS"])
class CsvUploadTypeTest(
    @Autowired val files: AttachmentAPI,
    @Autowired val rows: ChatAttachmentRepository,
    @Autowired val shelf: ChatAttachmentShelf,
    @Autowired val scratch: LlmSessionStore,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val sheets: SheetCapability,
    @Autowired val mapper: ObjectMapper,
) {

    private val csv = "name;amount\nZażółć;12,50\n"
    private var workspaceId = 0L

    @BeforeEach
    fun workspace() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "csv-upload-${System.nanoTime()}")).id)
    }

    @ParameterizedTest
    @ValueSource(strings = ["application/vnd.ms-excel", "text/plain", "application/octet-stream", "", "text/csv"])
    fun `a csv is filed as text csv and kept as its text, whatever the browser said`(declared: String) {
        val answer = files.upload(workspaceId, listOf(MockMultipartFile("files", "budżet.csv", declared, csv.toByteArray())))
        @Suppress("UNCHECKED_CAST")
        val id = ((answer.body as Map<String, Any>)["attachments"] as List<Map<String, Any>>).single()["id"].toString().toLong()
        val row = rows.findById(id).orElseThrow()
        assertThat(row.contentType).isEqualTo("text/csv")

        val session = requireNotNull(sessions.save(LlmSession(workspaceId = workspaceId, sessionKey = "csv-${System.nanoTime()}")).id)
        val note = requireNotNull(shelf.shelve(session, listOf(row)))
        val key = "${ChatAttachmentShelf.KEY_PREFIX}$id"
        assertThat(scratch.kindOf(session, key)?.binary).isFalse()
        assertThat(mapper.readTree(scratch.get(session, key)).stringValue()).isEqualTo(csv)
        assertThat(note).contains("sheet_read")

        val read = mapper.readTree(sheets.run(SheetCapability.READ, """{"key":"$key"}""", workspaceId, session))
        assertThat(read.path("delimiter").stringValue()).isEqualTo(";")
        assertThat(read.path("rows").toString()).isEqualTo("""[["name","amount"],["Zażółć","12,50"]]""")
    }

    @Test
    fun `an xlsx the browser had no type for is read by sheet_read through its note`() {
        val book = XSSFWorkbook().use { workbook ->
            workbook.createSheet("Costs").createRow(0).createCell(0).setCellValue("rent")
            ByteArrayOutputStream().also(workbook::write).toByteArray()
        }
        val answer = files.upload(workspaceId, listOf(MockMultipartFile("files", "costs.xlsx", "", book)))
        @Suppress("UNCHECKED_CAST")
        val id = ((answer.body as Map<String, Any>)["attachments"] as List<Map<String, Any>>).single()["id"].toString().toLong()
        val row = rows.findById(id).orElseThrow()
        assertThat(row.contentType).isEqualTo(AttachmentTypes.XLSX)

        val session = requireNotNull(sessions.save(LlmSession(workspaceId = workspaceId, sessionKey = "xlsx-${System.nanoTime()}")).id)
        val note = requireNotNull(shelf.shelve(session, listOf(row)))
        val key = "${ChatAttachmentShelf.KEY_PREFIX}$id"
        assertThat(note).contains("sheet_read {\"${SheetCapability.KEY}\": \"$key\"}")

        val read = mapper.readTree(sheets.run(SheetCapability.READ, """{"key":"$key"}""", workspaceId, session))
        assertThat(read.path("sheets").toString()).isEqualTo("""["Costs"]""")
        assertThat(read.path("rows").path(0).path(0).stringValue()).isEqualTo("rent")
    }

    @Test
    fun `a type the browser got right, or a file that is not a sheet, is left as it came`() {
        assertThat(AttachmentTypes.of("report.pdf", "application/pdf")).isEqualTo("application/pdf")
        assertThat(AttachmentTypes.of("notes.txt", "text/plain")).isEqualTo("text/plain")
        assertThat(AttachmentTypes.of("old.xls", "application/vnd.ms-excel")).isEqualTo("application/vnd.ms-excel")
        assertThat(AttachmentTypes.of("blob", null)).isEqualTo("application/octet-stream")
        assertThat(AttachmentTypes.of("Q3.XLSX", "application/octet-stream"))
            .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        assertThat(AttachmentTypes.of("data.tsv", "")).isEqualTo("text/tab-separated-values")
    }
}
