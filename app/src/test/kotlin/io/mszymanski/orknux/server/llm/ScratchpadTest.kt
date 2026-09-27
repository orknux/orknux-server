package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * A working file an agent keeps within a session: written a piece at a time,
 * read in fragments, searched, shared with the agents it asks, and bounded.
 * Issue #411.
 *
 * Makes its own workspace and session each time.
 */
@SpringBootTest
class ScratchpadTest(
    @Autowired val pads: SessionScratchpadService,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val settings: InstallationSettings,
    /** The model's way in, and what it is told about it. Issue #445. */
    @Autowired val tools: io.mszymanski.orknux.server.chat.ScratchpadTools,
    /** Where a kept pad lands, for the tool that hands one to a key. Issue #417. */
    @Autowired val store: LlmSessionStore,
    @Autowired val embedded: io.mszymanski.orknux.server.embedded.EmbeddedCapabilities,
) {

    private var session: Long = 0

    /**
     * Nothing told the agent it had scratchpads, so it never used one - the
     * silence that kept memory search unused until the briefing named the
     * memory. The shed says so itself, and says when; two sheds lent together
     * say both. Issue #445.
     */
    @Test
    fun `the shed tells the agent it has scratchpads, and when to use one`() {
        val shed = requireNotNull(tools.shed(session))
        val said = requireNotNull(shed.briefing())
        assertThat(said).contains("You have scratchpads")
        assertThat(said).contains("longer than a message")
        assertThat(said).contains("scratchpad_write")

        assertThat(tools.shed(null)).describedAs("nowhere to keep a file").isNull()

        val silent = object : io.mszymanski.orknux.server.chat.ToolShed {
            override fun specs() = emptyList<io.mszymanski.orknux.connector.model.ToolSpec>()
            override fun handles(name: String) = false
            override fun run(call: io.mszymanski.orknux.connector.model.ToolCall) = ""
        }
        assertThat(silent.briefing()).describedAs("a shed with nothing to say").isNull()
        assertThat(io.mszymanski.orknux.server.chat.sheds(silent, shed)?.briefing()).isEqualTo(said)
    }

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(
            (workspaces.findByName("pads") ?: workspaces.save(Workspace(name = "pads"))).id,
        )
        session = recorder.open(workspaceId, "test", "pads-${System.nanoTime()}")
    }

    /**
     * A pad handed to something that takes a key. Issue #417: an upload, a
     * message, a mail all take a key into the session store, and a model that
     * had written a page into a pad could only reach them by reading it and
     * typing it back - which is the thing the output limit cuts off.
     */
    @Test
    fun `a scratchpad is kept in the session store, and answers with its key`() {
        ok(pads.create(session, "page.html", "The landing page", "<h1>Hello</h1>"))
        val shed = requireNotNull(tools.shed(session))

        val said = shed.run(
            io.mszymanski.orknux.connector.model.ToolCall("1", "scratchpad_keep", """{"name":"page.html"}"""),
        )

        assertThat(said).contains("\"key\":\"page.html\"")
        assertThat(store.get(session, "page.html")).isEqualTo("\"<h1>Hello</h1>\"")
        // The pad is left where it was: this is a copy, not a move.
        assertThat(requireNotNull(pads.find(session, "page.html")).content).isEqualTo("<h1>Hello</h1>")

        // Under a name of the agent's choosing where it gives one.
        shed.run(
            io.mszymanski.orknux.connector.model.ToolCall(
                "2",
                "scratchpad_keep",
                """{"name":"page.html","key":"site"}""",
            ),
        )
        assertThat(store.get(session, "site")).isEqualTo("\"<h1>Hello</h1>\"")

        // And a pad that is not there is said so rather than kept as nothing.
        val missed = shed.run(
            io.mszymanski.orknux.connector.model.ToolCall("3", "scratchpad_keep", """{"name":"nothing"}"""),
        )
        assertThat(missed).contains("no scratchpad called")
    }

    /**
     * A page naming a picture by its key. Issue #545: uploaded as HTML the src
     * points at nothing, and the model is told to host it or zip the two.
     */
    @Test
    fun `keeping a page that names a picture by key warns, and leaves the page as it is`() {
        store.put(session, "picture.30", "\"iVBORw0KGgo=\"")
        val page = "<h1>Mascots</h1><img src=\"picture.30\"><img src=\"https://example.invalid/a.png\">"
        ok(pads.create(session, "report.html", "The report", page))
        val shed = requireNotNull(tools.shed(session))

        val said = shed.run(
            io.mszymanski.orknux.connector.model.ToolCall("1", "scratchpad_keep", """{"name":"report.html"}"""),
        )

        assertThat(said).contains("\"warning\"").contains("picture.30").contains("zip_files")
            .doesNotContain("example.invalid")
        assertThat(store.get(session, "report.html")).contains("src=\\\"picture.30\\\"")
    }

    /** A kept SVG put into a page by its key, not typed. Issue #550. */
    @Test
    fun `a replacement can come from a key, and a kept svg goes in as its markup`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"4\" height=\"4\"/></svg>"
        store.put(session, "chart.1.svg", "\"" + java.util.Base64.getEncoder().encodeToString(svg.toByteArray()) + "\"")
        ok(pads.create(session, "page.html", "The page", "<h1>Report</h1><p>CHART</p>"))
        val shed = requireNotNull(tools.shed(session))

        val said = shed.run(
            io.mszymanski.orknux.connector.model.ToolCall(
                "1", "scratchpad_replace", """{"name":"page.html","old":"CHART","newKey":"chart.1.svg"}""",
            ),
        )

        assertThat(said).contains("\"replaced\"")
        assertThat(requireNotNull(pads.find(session, "page.html")).content).contains("<rect width=\"4\"")
    }

    /** A drawn SVG added to a page by its key. Issue #555. */
    @Test
    fun `an append can come from a key, and a kept svg goes in as its markup`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"3\"/></svg>"
        store.put(session, "diagram.1.svg", "\"" + java.util.Base64.getEncoder().encodeToString(svg.toByteArray()) + "\"")
        ok(pads.create(session, "page2.html", "The page", "<h1>Report</h1>"))
        val shed = requireNotNull(tools.shed(session))

        shed.run(
            io.mszymanski.orknux.connector.model.ToolCall(
                "1", "scratchpad_append", """{"name":"page2.html","key":"diagram.1.svg"}""",
            ),
        )

        assertThat(requireNotNull(pads.find(session, "page2.html")).content).endsWith("<circle r=\"3\"/></svg>")
    }

    /** A kept pad says what it is, and a copy into another session keeps saying it. Issue #559. */
    @Test
    fun `a kept pad is recorded as text of its type, and the kind survives a copy`() {
        ok(pads.create(session, "kinds.html", "A page", "<h1>Kinds</h1>"))
        requireNotNull(tools.shed(session)).run(
            io.mszymanski.orknux.connector.model.ToolCall("1", "scratchpad_keep", """{"name":"kinds.html"}"""),
        )

        assertThat(store.kindOf(session, "kinds.html"))
            .isEqualTo(io.mszymanski.orknux.workflow.script.StoredKind("text/html", false))

        store.put(session, "report.pdf", "\"JVBERi0x\"", io.mszymanski.orknux.workflow.script.StoredKind("application/pdf", true))
        val other = recorder.open(workspaceId, "test", "kinds-${System.nanoTime()}")
        store.copy(from = session, into = other)
        assertThat(store.kindOf(other, "report.pdf"))
            .isEqualTo(io.mszymanski.orknux.workflow.script.StoredKind("application/pdf", true))
        store.put(session, "nobody-said", "\"x\"")
        assertThat(store.kindOf(session, "nobody-said")).isNull()
    }

    /** A pattern, one pad, and the lines around each hit. Issue #560. */
    @Test
    fun `search with regex finds a pattern with its line numbers and context, and refuses a broken pattern`() {
        ok(pads.create(session, "grep.html", "A page", listOf(
            "<h1>Report</h1>",
            "<p>intro</p>",
            "<img src=\"images/orc.png\">",
            "<p>middle</p>",
            "<IMG src=\"images/nuts.png\">",
        ).joinToString("\n")))
        ok(pads.create(session, "other.txt", "Elsewhere", "<img src=\"x.png\">"))
        val shed = requireNotNull(tools.shed(session))
        fun grep(arguments: String) =
            shed.run(io.mszymanski.orknux.connector.model.ToolCall("1", "scratchpad_search", arguments))

        val cased = grep("""{"query":"<img src=\"images/[a-z]+[.]png\"","regex":"true","ignoreCase":"false","name":"grep.html","context":"1"}""")
        assertThat(cased).contains("\"line\":3").contains("\"before\":[\"<p>intro</p>\"]")
            .contains("\"after\":[\"<p>middle</p>\"]").doesNotContain("nuts").doesNotContain("other.txt")

        val folded = grep("""{"query":"<img"}""")
        assertThat(folded).contains("nuts").contains("other.txt")

        assertThat(grep("""{"query":"(unclosed","regex":"true"}""")).contains("not a regular expression")
        // Without regex the same text is a phrase, and a bracket is a bracket.
        assertThat(grep("""{"query":"(unclosed"}""")).contains("\"hits\":[]")
    }

    /**
     * A report zipped with its stylesheet and pictures, laid out as a PDF.
     * Issue #563: the picture is found inside the zip by its relative path, so
     * nothing is reported as not drawn.
     */
    @Test
    fun `pdf_fromHtmlZip lays out a zipped report, reading its picture from the zip`() {
        val dot = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
        val zipped = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zipped).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            entry(
                "index.html",
                ("<html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><h1>Report</h1>" +
                    "<img src=\"./images/dot.png\"></body></html>").toByteArray(),
            )
            entry("style.css", "h1 { color: #aa0000; }".toByteArray())
            entry("images/dot.png", dot)
        }
        store.put(
            session, "report.zip",
            "\"" + java.util.Base64.getEncoder().encodeToString(zipped.toByteArray()) + "\"",
            io.mszymanski.orknux.workflow.script.StoredKind("application/zip", true),
        )

        val said = embedded.run("pdf_fromHtmlZip", """{"contentKey":"report.zip","title":"Zipped"}""", workspaceId, session)
        assertThat(said).contains("\"contentKey\":\"Zipped.pdf\"").doesNotContain("problems")
        // And the picture is in the document, which "no problems" never proved. Issue #565.
        assertThat(imagesIn(store.get(session, "Zipped.pdf")!!)).isGreaterThanOrEqualTo(1)
        assertThat(store.kindOf(session, "Zipped.pdf"))
            .isEqualTo(io.mszymanski.orknux.workflow.script.StoredKind("application/pdf", true))

        val missing = embedded.run("pdf_fromHtmlZip", """{"contentKey":"report.zip","file":"about.html"}""", workspaceId, session)
        assertThat(missing).contains("no about.html").contains("images/dot.png")
    }

    /**
     * A page's linked stylesheet read from the pad of that name. Issue #563:
     * handed index.html alone, the PDF lost style.css and a model pasted it in
     * by hand. Seen through what the PDF says: the stylesheet hides a line.
     */
    @Test
    fun `pdf_fromHtml reads a linked stylesheet from the scratchpad of that name`() {
        ok(pads.create(session, "look.css", "The styles", ".hidden { display: none; }"))
        ok(
            pads.create(
                session, "look.html", "The page",
                "<html><head><link rel=\"stylesheet\" href=\"look.css\"></head><body>" +
                    "<p>Plainly visible</p><p class=\"hidden\">Zanzibarquux</p></body></html>",
            ),
        )

        val made = embedded.run("pdf_fromHtml", """{"scratchpad":"look.html","title":"Looked"}""", workspaceId, session)
        assertThat(made).contains("\"contentKey\":\"Looked.pdf\"")
        val read = embedded.run("pdf_read", """{"contentKey":"Looked.pdf"}""", workspaceId, session)
        assertThat(read).contains("Plainly visible").doesNotContain("Zanzibarquux")
    }

    /** How many pictures a stored PDF actually carries, counted by PDFBox. */
    private fun imagesIn(stored: String): Int {
        val pdf = java.util.Base64.getDecoder().decode(stored.trim('"'))
        return org.apache.pdfbox.Loader.loadPDF(pdf).use { doc ->
            doc.pages.sumOf { page -> page.resources.xObjectNames.count { page.resources.isImageXObject(it) } }
        }
    }

    private fun ok(result: ScratchpadResult): ScratchpadResult.Ok {
        assertThat(result).isInstanceOf(ScratchpadResult.Ok::class.java)
        return result as ScratchpadResult.Ok
    }

    private fun no(result: ScratchpadResult): String {
        assertThat(result).isInstanceOf(ScratchpadResult.No::class.java)
        return (result as ScratchpadResult.No).why
    }

    /* --------------------------------------------------- the basics -------- */

    @Test
    fun `a scratchpad is created and read back whole`() {
        ok(pads.create(session, "draft.html", "The landing page", "<h1>Hello</h1>"))

        val held = requireNotNull(pads.find(session, "draft.html"))
        assertThat(held.content).isEqualTo("<h1>Hello</h1>")
        assertThat(held.description).isEqualTo("The landing page")
    }

    @Test
    fun `two scratchpads with the same name in one session is refused`() {
        ok(pads.create(session, "notes", null, "one"))
        assertThat(no(pads.create(session, "notes", null, "two"))).contains("already has a scratchpad")
    }

    @Test
    fun `append grows a file without resending it`() {
        ok(pads.create(session, "log", null, "line one\n"))
        ok(pads.append(session, "log", "line two\n"))

        assertThat(requireNotNull(pads.find(session, "log")).content).isEqualTo("line one\nline two\n")
    }

    @Test
    fun `replace changes one piece in place, and refuses an absent or ambiguous one`() {
        ok(pads.create(session, "code", null, "val x = 1\nval y = 1\n"))

        // Ambiguous: "= 1" is there twice.
        assertThat(no(pads.replace(session, "code", "= 1", "= 2"))).contains("more than once")
        // Absent.
        assertThat(no(pads.replace(session, "code", "= 9", "= 2"))).contains("not in")
        // Unique.
        ok(pads.replace(session, "code", "val x = 1", "val x = 42"))
        assertThat(requireNotNull(pads.find(session, "code")).content).isEqualTo("val x = 42\nval y = 1\n")
    }

    @Test
    fun `describe sets what a file is for, and the list shows it`() {
        ok(pads.create(session, "plan", null, "steps"))
        ok(pads.describe(session, "plan", "The rollout plan"))

        val listed = pads.list(session).single { it.name == "plan" }
        assertThat(listed.description).isEqualTo("The rollout plan")
        assertThat(listed.bytes).isEqualTo("steps".length)
    }

    @Test
    fun `delete removes a file`() {
        ok(pads.create(session, "temp", null, "throwaway"))
        ok(pads.delete(session, "temp"))
        assertThat(pads.find(session, "temp")).isNull()
    }

    /* --------------------------------------------------- search ------------ */

    @Test
    fun `search says which file and line a piece of text is on`() {
        ok(pads.create(session, "a.txt", null, "alpha\nbeta\ngamma"))
        ok(pads.create(session, "b.txt", null, "delta\nBETA rising"))

        val hits = pads.search(session, "beta")
        assertThat(hits).hasSize(2)
        assertThat(hits.map { it.name }).containsExactlyInAnyOrder("a.txt", "b.txt")
        val inA = hits.single { it.name == "a.txt" }
        assertThat(inA.line).isEqualTo(2)
        assertThat(inA.text).isEqualTo("beta")
    }

    /* --------------------------------------------------- the budget -------- */

    @Test
    fun `a write that would put the session over its byte budget is refused`() {
        val was = settings.scratchpadBudgetBytes()
        try {
            settings.setScratchpadBudgetBytes(1024, "test")
            ok(pads.create(session, "big", null, "a".repeat(1000)))
            // 1000 held, appending 100 would be 1100 > 1024.
            assertThat(no(pads.append(session, "big", "b".repeat(100)))).contains("over the")
            // And it was not written.
            assertThat(requireNotNull(pads.find(session, "big")).bytes).isEqualTo(1000)
        } finally {
            settings.setScratchpadBudgetBytes(was, "test")
        }
    }

    /* --------------------------------------------------- sharing ----------- */

    @Test
    fun `a shared file reaches the sessions started under the one that owns it`() {
        ok(pads.create(session, "shared.md", "Handover", "what the shift found\n"))
        ok(pads.share(session, "shared.md", true))
        val child = recorder.openUnder(session, "Summarise the day")

        // The child sees it and can add to it.
        assertThat(pads.find(child, "shared.md")?.content).isEqualTo("what the shift found\n")
        assertThat(pads.list(child).map { it.name }).contains("shared.md")
        ok(pads.append(child, "shared.md", "and what the child added\n"))

        // Both see the addition - it is one file.
        assertThat(requireNotNull(pads.find(session, "shared.md")).content)
            .isEqualTo("what the shift found\nand what the child added\n")
    }

    /**
     * A subagent rewriting the conversation's file rewrites the conversation's
     * file. Issue #497.
     *
     * The write tool used to make a copy in the child's own session whenever
     * the name resolved to an ancestor's shared pad - so a subagent asked to
     * rewrite a stylesheet did the work, said so, and the conversation went on
     * reading the original, with nothing anywhere saying a fork had happened.
     * Append and replace always wrote through; write forking alone made the
     * first operation anybody reaches for the one that quietly did something
     * else.
     */
    @Test
    fun `a subagent writing a shared file writes the shared file, not a copy`() {
        ok(pads.create(session, "styles.css", "The stylesheet", "body { color: #333; }"))
        ok(pads.share(session, "styles.css", true))
        val child = recorder.openUnder(session, "Make it dark")

        val shed = requireNotNull(tools.shed(child))
        val said = shed.run(
            io.mszymanski.orknux.connector.model.ToolCall(
                id = "call_1",
                name = "scratchpad_write",
                arguments = """{"name":"styles.css","content":"body { color: #eee; }"}""",
            ),
        )

        // Written where the file actually is.
        assertThat(requireNotNull(pads.find(session, "styles.css")).content).isEqualTo("body { color: #eee; }")
        // And no second file of that name under the child, which is the fork
        // this is about.
        assertThat(pads.list(child).count { it.name == "styles.css" }).isEqualTo(1)
        // The answer says whose file it was, so an agent can tell.
        assertThat(said).contains("\"ownedHere\":false")
        assertThat(said).contains("belongs to the conversation that started you")
    }

    /**
     * The files are the conversation's, in both directions. Issue #498.
     *
     * This used to assert the opposite - a pad nobody had shared was invisible
     * below - and the flag was the fault: an agent had to remember to set it,
     * a subagent's work landed where nobody looked, and "did it save?" had two
     * answers. A subagent is not separate work; it is this conversation asking
     * somebody to do part of it.
     */
    @Test
    fun `a conversation's files reach the sessions it asks, and theirs come back`() {
        ok(pads.create(session, "notes.md", null, "what the shift found"))
        val child = recorder.openUnder(session, "A task")

        // Down, with nothing shared.
        assertThat(pads.find(child, "notes.md")?.content).isEqualTo("what the shift found")
        assertThat(pads.list(child).map { it.name }).contains("notes.md")

        // And up: what the subagent makes is there for the conversation.
        ok(pads.create(child, "findings.md", null, "what the subagent found"))
        assertThat(pads.find(session, "findings.md")?.content).isEqualTo("what the subagent found")
        assertThat(pads.list(session).map { it.name }).contains("findings.md")

        // A session of another conversation sees neither, which is the bound
        // that makes this a family rather than a workspace-wide pile.
        val stranger = recorder.open(workspaceId, "unrelated", "stranger-${System.nanoTime()}")
        assertThat(pads.find(stranger, "notes.md")).isNull()
        assertThat(pads.find(stranger, "findings.md")).isNull()
    }

    @Test
    fun `only the owning session may share or delete a file`() {
        ok(pads.create(session, "doc", null, "body"))
        ok(pads.share(session, "doc", true))
        val child = recorder.openUnder(session, "A task")

        // The child inherited it but does not own it, so it cannot re-share or remove it.
        assertThat(no(pads.share(child, "doc", false))).contains("of its own")
        assertThat(no(pads.delete(child, "doc"))).contains("of its own")
        // The owner still has it.
        assertThat(pads.find(session, "doc")).isNotNull()
    }
}
