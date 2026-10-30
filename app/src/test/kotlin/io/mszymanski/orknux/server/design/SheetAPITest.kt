package io.mszymanski.orknux.server.design

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.mcp.SheetTools
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sheets in the Design perspective.
 *
 * Pinned: the editor's own door (make, rename, save, remove, each audited), what
 * a cell comes to - formulas across tabs, errors with their reason, the
 * apostrophe that keeps text as typed - the shapes a save refuses, a save over
 * a sheet changed since it was opened, the Tunable that caps a sheet, the
 * orknux_* tools that let an agent or a model fill one in through the same door,
 * the files a sheet goes out as and comes in from, and the editor's chat - a
 * stub model that writes a total, which has to be in the stored sheet after.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SheetAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sheets: SheetRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val tunables: Tunables,
    @Autowired val tools: OrknuxTools,
    @Autowired val chat: SheetChat,
    @Autowired val mapper: ObjectMapper,
    @Autowired val context: WebApplicationContext,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        sheets.deleteAll()
        audit.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "sheets-${System.nanoTime()}")).id)
        tunables.set(Tunable.SHEET_CELLS_MOST, null, "alice")
        received.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        tunables.set(Tunable.SHEET_CELLS_MOST, null, "alice")
    }

    @Test
    fun `the functions offered are the ones the engine works out`() {
        val names = graphQlTester.document("query { sheetFunctions(workspaceId: $workspaceId) }").execute()
            .path("sheetFunctions").entityList(String::class.java).get()
        assertThat(names).contains("SUM", "VLOOKUP", "IFERROR")
        assertThat(names).isSorted()
    }

    @Test
    fun `a sheet is made, listed, renamed, saved and removed, and each is audited`() {
        val id = create("Budget")
        graphQlTester.document("""{ sheets(workspaceId: $workspaceId) { name tabCount cellCount revision createdBy } }""")
            .execute()
            .path("sheets[*].name").entityList(String::class.java).containsExactly("Budget")
            .path("sheets[0].tabCount").entity(Int::class.java).isEqualTo(1)
            .path("sheets[0].cellCount").entity(Int::class.java).isEqualTo(0)
            .path("sheets[0].createdBy").entity(String::class.java).isEqualTo("alice")

        graphQlTester.document("""mutation { renameSheet(id: $id, name: "Costs") { name } }""")
            .execute().path("renameSheet.name").entity(String::class.java).isEqualTo("Costs")

        save(id, """{"tabs":[{"name":"Sheet1","cells":{"A1":"Rent","B1":1200,"A2":"Food","B2":"300","C9":""},"widths":{"a":180}}]}""", revision = 1)
            .path("saveSheet.revision").entity(Int::class.java).isEqualTo(2)
            .path("saveSheet.cellCount").entity(Int::class.java).isEqualTo(4)

        // A number arrives as one, an empty cell is not kept, and a column is named in capitals.
        val tab = tabsOf(id).get(0)
        assertThat(tab.path("cells").path("B1").stringValue()).isEqualTo("1200")
        assertThat(tab.path("cells").has("C9")).isFalse()
        assertThat(tab.path("widths").path("A").asInt()).isEqualTo(180)

        graphQlTester.document("""mutation { deleteSheet(id: $id) }""")
            .execute().path("deleteSheet").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("""{ sheet(id: $id) { name } }""").execute().path("sheet").valueIsNull()

        assertThat(audit.findAll().map { it.message }).contains(
            "Sheet Budget added",
            "Sheet Budget renamed to Costs",
            "Sheet Costs saved with 4 cells",
            "Sheet Costs removed",
        )
    }

    /** What each kind of cell comes to, worked out by the one engine the editor, the tools and the export share. */
    @Test
    fun `a cell comes to what a spreadsheet would make of it, formulas across tabs included`() {
        val values = values(
            """{"tabs":[
                 {"name":"Sheet1","cells":{"A1":"2","A2":"3","A3":"=A1+A2","A4":"=SUM(A1:A3)/0","A5":"'0012","A6":"true",
                                          "A7":"=A1/3","A8":"=Costs!B1*2","A9":"=NOSUCHFUNCTION(1)","A10":"=SUM(","A11":"=0.1+0.2",
                                          "A12":"=UPPER(\"x\")&A1","A13":"=Z99"}},
                 {"name":"Costs","cells":{"B1":"=Sheet1!A3+1"}}]}""",
        )
        val first = values.path("tabs").get(0).path("values")
        fun shown(cell: String) = first.path(cell).path("f").stringValue()
        fun type(cell: String) = first.path(cell).path("t").stringValue()

        assertThat(shown("A3")).isEqualTo("5")
        assertThat(first.path("A3").path("v").isIntegralNumber).isTrue()
        assertThat(shown("A4")).isEqualTo("#DIV/0!")
        assertThat(type("A4")).isEqualTo("e")
        assertThat(shown("A5")).isEqualTo("0012")
        assertThat(type("A5")).isEqualTo("s")
        assertThat(shown("A6")).isEqualTo("TRUE")
        assertThat(type("A6")).isEqualTo("b")
        assertThat(shown("A7")).isEqualTo("0.666666666666667")
        // Through a tab that comes after this one, and back.
        assertThat(shown("A8")).isEqualTo("12")
        assertThat(values.path("tabs").get(1).path("values").path("B1").path("f").stringValue()).isEqualTo("6")
        assertThat(shown("A9")).isEqualTo("#NAME?")
        assertThat(shown("A10")).isEqualTo("#NAME?")
        assertThat(first.path("A10").path("m").stringValue()).isNotBlank()
        assertThat(shown("A11")).isEqualTo("0.3")
        assertThat(shown("A12")).isEqualTo("X2")
        assertThat(shown("A13")).isEqualTo("0")
    }

    @Test
    fun `a save made against an older revision is refused rather than written over the newer one`() {
        val id = create("Plan")
        save(id, """{"tabs":[{"name":"Sheet1","cells":{"A1":"1"}}]}""", revision = 1)
            .path("saveSheet.revision").entity(Int::class.java).isEqualTo(2)
        save(id, """{"tabs":[{"name":"Sheet1","cells":{}}]}""", revision = 1).errors()
            .expect { it.message == "Sheet Plan was changed since it was opened; open it again before saving" }.verify()
        assertThat(tabsOf(id).get(0).path("cells").size()).isEqualTo(1)
    }

    @Test
    fun `a workbook that is not one is refused, saying what is wrong with it`() {
        val id = create("Broken")
        save(id, "not json").errors().expect { it.message == "The sheet cannot be saved: it is not JSON" }.verify()
        save(id, """{"tabs":[]}""").errors().expect { it.message == "The sheet cannot be saved: a workbook needs at least one tab" }.verify()
        save(id, """{"tabs":[{"name":"A","cells":{"1A":"x"}}]}""").errors()
            .expect { it.message?.contains("1A in tab A is not a cell") == true }.verify()
        save(id, """{"tabs":[{"name":"Same"},{"name":"same"}]}""").errors()
            .expect { it.message == "The sheet cannot be saved: two tabs are called same" }.verify()
        save(id, """{"tabs":[{"name":"What?"}]}""").errors()
            .expect { it.message?.contains("tab What? has a name with one of") == true }.verify()
        save(id, """{"tabs":[{"name":"A","cells":{"A1":{"x":1}}}]}""").errors()
            .expect { it.message?.contains("A1 in tab A has to hold text or a number") == true }.verify()
        graphQlTester.document("""mutation { createSheet(workspaceId: $workspaceId, name: "  ") { id } }""")
            .execute().errors().expect { it.message?.contains("needs a name") == true }.verify()
    }

    @Test
    fun `a sheet past the cells an installation allows is refused with the count`() {
        tunables.set(Tunable.SHEET_CELLS_MOST, 100, "alice")
        val id = create("Crowded")
        val cells = (1..101).joinToString(",") { "\"A$it\":\"$it\"" }
        save(id, """{"tabs":[{"name":"Sheet1","cells":{$cells}}]}""").errors()
            .expect { it.message == "A sheet holds at most 100 filled cells, and this one would hold 101" }.verify()
    }

    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `somebody who cannot see the workspace cannot see or change its sheets`() {
        val id = requireNotNull(
            sheets.save(Sheet(workspaceId = workspaceId, name = "Secret", workbook = """{"tabs":[{"name":"Sheet1","cells":{}}]}""", createdBy = "alice", updatedBy = "alice")).id,
        )
        graphQlTester.document("""{ sheets(workspaceId: $workspaceId) { name } }""").execute().errors().expect { true }.verify()
        save(id, """{"tabs":[{"name":"Sheet1","cells":{"A1":"mine"}}]}""").errors().expect { true }.verify()
        assertThat(sheets.findById(id).get().revision).isEqualTo(1)
        mvc().perform(get("/api/sheets/$id/export/xlsx")).andReturn().response.let { assertThat(it.status).isEqualTo(404) }
    }

    /** An agent with nobody signed in fills a sheet in through the orknux_* tools, under its own name. */
    @Test
    fun `the orknux tools make, fill, read and re-tab a sheet through the same door`() {
        SecurityContextHolder.clearContext()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Accountant")
        fun call(name: String, arguments: String): JsonNode = mapper.readTree(tools.run(scope, name, arguments))

        assertThat(tools.specs(scope).map { it.name }).containsAll(SheetTools.NAMES)
        assertThat(tools.specs(scope.copy(mayWrite = false)).map { it.name }.filter { it in SheetTools.NAMES })
            .containsExactlyInAnyOrder(SheetTools.LIST, SheetTools.READ)

        val made = call(SheetTools.CREATE, """{"name":"Quarter","tabs":"[{\"name\":\"Costs\",\"cells\":{\"A1\":\"Item\"}}]"}""")
        assertThat(made.has("error")).describedAs(made.toString()).isFalse()
        val id = made.path("id").asLong()
        assertThat(made.path("url").stringValue()).endsWith("/workspace/$workspaceId/design/sheets/$id")

        val wrote = call(
            SheetTools.WRITE,
            """{"sheet":"quarter","rows":[["Item","Cost"],["Rent",1200],["Food","300"],["Total","=SUM(B2:B3)"],["Bad","=B2/0"]]}""",
        )
        assertThat(wrote.has("error")).describedAs(wrote.toString()).isFalse()
        assertThat(wrote.path("wrote").path("B4").stringValue()).isEqualTo("1500")
        assertThat(wrote.path("errors").path("B5").stringValue()).startsWith("#DIV/0!")

        val cleared = call(SheetTools.WRITE, """{"sheet":"$id","tab":"Costs","cells":{"A5":"","B5":null}}""")
        assertThat(cleared.path("cells").asInt()).describedAs(cleared.toString()).isEqualTo(8)

        val read = call(SheetTools.READ, """{"sheet":"Quarter","range":"B1:B4"}""")
        val cells = read.path("tabs").get(0).path("cells")
        assertThat(cells.propertyNames()).containsExactly("B1", "B2", "B3", "B4")
        assertThat(cells.path("B4").path("shows").stringValue()).isEqualTo("1500")
        assertThat(cells.path("B4").path("typed").stringValue()).isEqualTo("=SUM(B2:B3)")
        assertThat(cells.path("B2").has("typed")).isFalse()
        assertThat(read.path("xlsx").stringValue()).endsWith("/api/sheets/$id/export/xlsx")

        val tabbed = call(
            SheetTools.TABS_TOOL,
            """{"sheet":"$id","add":["Notes"],"rename":[{"from":"Costs","to":"Spend"}],"widths":{"tab":"Spend","A":220}}""",
        )
        assertThat(texts(tabbed.path("tabs"))).describedAs(tabbed.toString()).containsExactly("Spend", "Notes")
        assertThat(tabsOf(id).get(0).path("widths").path("A").asInt()).isEqualTo(220)
        assertThat(call(SheetTools.TABS_TOOL, """{"sheet":"$id","remove":["Spend","Notes"]}""").path("error").stringValue())
            .contains("keeps at least one tab")
        assertThat(call(SheetTools.WRITE, """{"sheet":"$id","tab":"Nope","cells":{"A1":"1"}}""").path("error").stringValue())
            .contains("has no tab Nope")
        assertThat(texts(call(SheetTools.LIST, "{}").path("sheets").get(0).path("tabs"))).containsExactly("Spend", "Notes")

        assertThat(audit.findAll().filter { it.message.startsWith("Sheet Quarter") }.map { it.userId }.toSet())
            .containsExactly("Accountant")
    }

    /** A read past what a model is handed at once stops at a whole cell and says how to read the rest. */
    @Test
    fun `a long read stops and says where, rather than handing a model the whole sheet`() {
        tunables.set(Tunable.SHEET_ANSWER_CHARACTERS, 1_000, "alice")
        try {
            val cells = (1..200).joinToString(",") { "\"A$it\":\"row number $it\"" }
            val id = create("Long", """{"tabs":[{"name":"Sheet1","cells":{$cells}}]}""")
            val read = mapper.readTree(tools.run(OrknuxScope(workspaceId = workspaceId), SheetTools.READ, """{"sheet":"$id"}"""))
            assertThat(read.path("tabs").get(0).path("cells").size()).isBetween(1, 199)
            assertThat(read.path("truncated").stringValue()).contains("read the rest with range")
        } finally {
            tunables.set(Tunable.SHEET_ANSWER_CHARACTERS, null, "alice")
        }
    }

    /** Out as .xlsx with its formulas, and back in again with them; a tab out as .csv carries values and defuses formulas. */
    @Test
    fun `a sheet goes out as xlsx and csv, comes back in from both, and each is audited`() {
        val id = create(
            "Ledger",
            """{"tabs":[{"name":"Main","cells":{"A1":"Item","B1":"Cost","A2":"=cmd","B2":"5","B3":"=B2*2","A4":"'=not a formula"}},
                        {"name":"Other","cells":{"A1":"=Main!B3"}}]}""",
        )
        val xlsx = download("/api/sheets/$id/export/xlsx")
        assertThat(xlsx.status).isEqualTo(200)
        assertThat(xlsx.getHeader("Content-Disposition")).contains("Ledger.xlsx")
        XSSFWorkbook(ByteArrayInputStream(xlsx.contentAsByteArray)).use { book ->
            assertThat(book.getSheetAt(0).getRow(2).getCell(1).cellFormula).isEqualTo("B2*2")
            assertThat(book.getSheetAt(0).getRow(2).getCell(1).numericCellValue).isEqualTo(10.0)
            assertThat(book.getSheetName(1)).isEqualTo("Other")
        }

        val csv = download("/api/sheets/$id/export/csv").contentAsString
        // Quoted where Commons CSV quotes - a leading # and an empty first field - which every reader of CSV takes.
        assertThat(csv.lines().take(4)).containsExactly("Item,Cost", "\"#NAME?\",5", "\"\",10", "'=not a formula,")

        val again = import("Ledger copy.xlsx", xlsx.contentAsByteArray)
        val tabs = tabsOf(again)
        assertThat(texts(tabs, "name")).containsExactly("Main", "Other")
        assertThat(tabs.get(0).path("cells").path("B3").stringValue()).isEqualTo("=B2*2")
        assertThat(tabs.get(1).path("cells").path("A1").stringValue()).isEqualTo("=Main!B3")
        assertThat(sheets.findById(again).get().name).isEqualTo("Ledger copy")

        val fromCsv = import("people.csv", "name;age\nAda;36\n".toByteArray(StandardCharsets.UTF_8))
        assertThat(tabsOf(fromCsv).get(0).path("cells").path("B2").stringValue()).isEqualTo("36")

        graphQlTester.document(
            """mutation { importSheet(workspaceId: $workspaceId, filename: "notes.xlsx", file: "${Base64.getEncoder().encodeToString("hello".toByteArray())}") { id } }""",
        ).execute().errors().expect { it.message?.startsWith("That file cannot be made into a sheet") == true }.verify()

        assertThat(audit.findAll().map { it.message }).contains(
            "Sheet Ledger exported as xlsx",
            "Sheet Ledger exported as csv",
            "Sheet Ledger copy imported from Ledger copy.xlsx",
        )
    }

    /** The briefing names every tool it mentions off what is offered, and the chat is offered working in the sheet alone. */
    @Test
    fun `the chat names only the tools it is offered`() {
        val view = SheetView(1, workspaceId, "Budget", "{}", 1, 0, 1, "alice", "", "alice", "")
        val offered = chat.offered(OrknuxScope(workspaceId = workspaceId, mayWrite = true))
        assertThat(offered.map { it.name }).containsExactlyInAnyOrderElementsOf(SheetTools.EDITING)
        val briefing = chat.briefing(view, offered)
        SheetTools.EDITING.forEach { assertThat(briefing).contains("`$it`") }
        assertThat(briefing).contains("never link to it")
    }

    /** A model asked in the editor writes a total; it is in the stored sheet, and the answer says the sheet changed. */
    @Test
    fun `a model asked in the editor writes into the stored sheet`() {
        val id = create("Sums", """{"tabs":[{"name":"Sheet1","cells":{"A1":"4","A2":"6"}}]}""")
        val modelId = model(
            serve { body ->
                if (received.size == 1) {
                    val arguments = mapper.writeValueAsString(mapOf("sheet" to id.toString(), "cells" to mapOf("A3" to "=SUM(A1:A2)")))
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                       {"id":"call_1","type":"function","function":{"name":"${SheetTools.WRITE}","arguments":${mapper.writeValueAsString(arguments)}}}
                     ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                } else {
                    assertThat(body).contains("10")
                    """{"choices":[{"message":{"role":"assistant","content":"Added the total."}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                }
            },
        )

        graphQlTester.document(
            """mutation { askSheet(id: $id, modelId: $modelId, messages: [{role: "user", content: "Add a total"}]) {
                 answer changed sheet { revision cellCount values } } }""",
        ).execute()
            .path("askSheet.answer").entity(String::class.java).isEqualTo("Added the total.")
            .path("askSheet.changed").entity(Boolean::class.java).isEqualTo(true)
            .path("askSheet.sheet.cellCount").entity(Int::class.java).isEqualTo(3)
            .path("askSheet.sheet.values").entity(String::class.java).satisfies { values ->
                assertThat(mapper.readTree(values).path("tabs").get(0).path("values").path("A3").path("f").stringValue()).isEqualTo("10")
            }

        assertThat(received.first()).contains(SheetTools.WRITE).doesNotContain("orknux_workflows")
        assertThat(tabsOf(id).get(0).path("cells").path("A3").stringValue()).isEqualTo("=SUM(A1:A2)")
    }

    private fun create(name: String, workbook: String? = null): Long = graphQlTester.document(
        """mutation(${'$'}w: String) { createSheet(workspaceId: $workspaceId, name: "$name", workbook: ${'$'}w) { id } }""",
    ).variable("w", workbook).execute().path("createSheet.id").entity(Long::class.java).get()

    private fun save(id: Long, workbook: String, revision: Int? = null) = graphQlTester.document(
        """mutation(${'$'}id: ID!, ${'$'}w: String!, ${'$'}r: Int) { saveSheet(id: ${'$'}id, workbook: ${'$'}w, revision: ${'$'}r) { revision cellCount } }""",
    ).variable("id", id).variable("w", workbook).variable("r", revision).execute()

    private fun values(workbook: String): JsonNode = mapper.readTree(
        graphQlTester.document("""query(${'$'}w: String!) { sheetValues(workspaceId: $workspaceId, workbook: ${'$'}w) }""")
            .variable("w", workbook).execute().path("sheetValues").entity(String::class.java).get(),
    )

    private fun import(filename: String, bytes: ByteArray): Long = graphQlTester.document(
        """mutation(${'$'}f: String!) { importSheet(workspaceId: $workspaceId, filename: "$filename", file: ${'$'}f) { id } }""",
    ).variable("f", "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes))
        .execute().path("importSheet.id").entity(Long::class.java).get()

    private fun texts(list: JsonNode, field: String? = null): List<String?> =
        (0 until list.size()).map { (if (field == null) list.get(it) else list.get(it).path(field)).stringValue() }

    private fun tabsOf(id: Long): JsonNode = mapper.readTree(sheets.findById(id).get().workbook).path("tabs")

    private fun mvc() = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

    /** A download through the REST door; MockMvc clears the signed-in context after it, so it is put back. */
    private fun download(path: String): org.springframework.mock.web.MockHttpServletResponse {
        val signedIn = SecurityContextHolder.getContext()
        try {
            return mvc().perform(get(path)).andReturn().response
        } finally {
            SecurityContextHolder.setContext(signedIn)
        }
    }

    private fun serve(answer: (String) -> String): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val bytes = answer(body).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        started.start()
        server = started
        return "http://${started.address.hostString}:${started.address.port}"
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub ${System.nanoTime()}", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
