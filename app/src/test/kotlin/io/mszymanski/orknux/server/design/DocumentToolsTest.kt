package io.mszymanski.orknux.server.design

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.database.isSqlite
import io.mszymanski.orknux.server.database.jdbcUrlOf
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.mcp.DocumentTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.StoredKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The orknux_* document tools, the editor's chat that writes through them, and
 * the inline suggestion while typing.
 *
 * Pinned: an agent with nobody signed in makes, reads, inserts into, appends
 * to, rewrites part of, searches, exports and puts pictures in a document - by
 * artifact id and by a key its conversation holds - through the same door the
 * editor saves through, audited under its own name; a read-only conversation
 * is offered only the reading tools; the editor's chat is offered the writing
 * tools and what a stub model writes is in the stored document; the suggestion
 * comes from the workspace's completion model, shown the document's title and
 * the text around the caret; and the migration marks the tools Always.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DocumentToolsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val documents: DesignDocumentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val tools: OrknuxTools,
    @Autowired val chat: DocumentChat,
    @Autowired val sessions: LlmSessionRecorder,
    @Autowired val scratch: LlmSessionStore,
    @Autowired val agents: AgentRepository,
    @Autowired val builtIns: BuiltInTools,
    @Autowired val jdbc: JdbcTemplate,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        audit.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "document-tools-${System.nanoTime()}")).id)
        received.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun picture(): String {
        val png = Base64.getEncoder().encodeToString(DocumentAPITest.png())
        return graphQlTester.document(
            """mutation { importDesignImage(input: { workspaceId: $workspaceId, image: "$png", name: "Red" }) { id } }""",
        ).execute().path("importDesignImage.id").entity(String::class.java).get()
    }

    private fun markdownOf(id: Long): String = DocumentMarkdown(mapper).write(mapper.readTree(documents.findById(id).get().content))

    @Test
    fun `an agent writes a document through the tools, through the same door, under its own name`() {
        val artifact = picture()
        val session = sessions.open(workspaceId, "test", "tools-${System.nanoTime()}")
        scratch.put(session, "picture.1", mapper.writeValueAsString(Base64.getEncoder().encodeToString(DocumentAPITest.png())), StoredKind("image/png", true))
        SecurityContextHolder.clearContext()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Writer", session = session)
        fun call(name: String, arguments: String): JsonNode = mapper.readTree(tools.run(scope, name, arguments)).also {
            assertThat(it.has("error")).describedAs("$name: $it").isFalse()
        }

        assertThat(tools.specs(scope).map { it.name }).containsAll(DocumentTools.NAMES)
        assertThat(tools.specs(scope.copy(mayWrite = false)).map { it.name }.filter { it in DocumentTools.NAMES })
            .containsExactlyInAnyOrder(DocumentTools.LIST, DocumentTools.READ, DocumentTools.SEARCH, DocumentTools.FIND, DocumentTools.EXPORT)

        val made = call(DocumentTools.CREATE, """{"name":"Quarterly report","catalog":"Reports","markdown":"# Summary\n\nRevenue grew.\n\n## Risks\n\nHiring."}""")
        val id = made.path("id").asLong()
        assertThat(made.path("url").stringValue()).endsWith("/workspace/$workspaceId/design/documents/$id")
        assertThat(made.path("blocks").size()).isEqualTo(4)

        call(DocumentTools.INSERT, """{"document":"quarterly report","at":2,"markdown":"- North\n- South"}""")
        call(DocumentTools.APPEND, """{"document":"$id","heading":"Next steps","markdown":"Hire **two** people.","level":2}""")
        call(DocumentTools.WRITE, """{"document":"$id","from":1,"to":1,"markdown":"Revenue grew by a tenth."}""")
        call(DocumentTools.ADD_IMAGE, """{"document":"$id","artifact":"$artifact","caption":"Sales","at":1}""")
        call(DocumentTools.ADD_IMAGE, """{"document":"$id","key":"picture.1","caption":"Drawn"}""")

        val md = markdownOf(id)
        assertThat(md).contains(
            "# Summary\n\n![Sales](artifact:$artifact \"Sales\")\n\nRevenue grew by a tenth.\n\n- North\n- South\n\n## Risks",
            "## Next steps\n\nHire **two** people.",
            "\"Drawn\")",
        )
        val stored = documents.findById(id).get()
        assertThat(stored.plainText).contains("Revenue grew by a tenth.")

        val read = call(DocumentTools.READ, """{"document":"$id"}""")
        assertThat(read.path("markdown").stringValue()).isEqualTo(md)
        assertThat(read.path("catalog").stringValue()).isEqualTo("Reports")
        assertThat(read.path("blocks").get(1).path("type").stringValue()).isEqualTo("image")

        val found = call(DocumentTools.SEARCH, """{"text":"tenth"}""")
        assertThat(found.path("documents").get(0).path("snippet").stringValue()).contains("by a tenth")
        assertThat(call(DocumentTools.LIST, """{"catalog":"reports"}""").path("documents").size()).isEqualTo(1)

        // A code block, written with its language as a fence, and found again with the words in the document.
        call(DocumentTools.APPEND, """{"document":"$id","heading":"Appendix","markdown":"<!-- pagebreak -->\n\n```kt\nval tenth = 0.1\n```"}""")
        assertThat(markdownOf(id)).contains("```kotlin\nval tenth = 0.1\n```")
        val inDocument = call(DocumentTools.FIND, """{"document":"$id","text":"Tenth","wholeWord":true}""")
        assertThat(inDocument.path("count").asInt()).describedAs(inDocument.toString()).isEqualTo(2)
        assertThat(inDocument.path("matches").get(0).path("page").asInt()).isEqualTo(1)
        assertThat(inDocument.path("matches").get(0).path("context").stringValue()).contains("by a tenth")
        assertThat(inDocument.path("matches").get(1).path("page").asInt()).isEqualTo(2)
        assertThat(call(DocumentTools.FIND, """{"document":"$id","text":"Tenth","matchCase":true}""").path("count").asInt()).isEqualTo(0)

        val exported = call(DocumentTools.EXPORT, """{"document":"$id","format":"pdf"}""")
        assertThat(exported.path("download").stringValue()).endsWith("/api/documents/$id/export/pdf")
        val key = exported.path("contentKey").stringValue()
        assertThat(scratch.kindOf(session, key)?.contentType).isEqualTo("application/pdf")

        // A refusal says why, in words a model can act on.
        assertThat(mapper.readTree(tools.run(scope, DocumentTools.INSERT, """{"document":"$id","at":99,"markdown":"x"}""")).path("error").stringValue())
            .contains("has blocks 0 to")
        assertThat(mapper.readTree(tools.run(scope, DocumentTools.ADD_IMAGE, """{"document":"$id","artifact":"DESIGN-999999999"}""")).path("error").stringValue())
            .contains("no artifact DESIGN-999999999")

        assertThat(audit.findAll().filter { it.message.startsWith("Document Quarterly report") }.map { it.userId }.toSet())
            .containsExactly("Writer")
    }

    /**
     * Colours and highlights reach a model as the editor's tags, come back as marks when it writes them, survive a
     * rewrite on the words it left alone when it does not, and a highlight that could not survive is named.
     */
    @Test
    fun `a model reads colours and highlights, writes them back, and a rewrite keeps them on unchanged words`() {
        val marked = """{"type":"doc","content":[
          {"type":"paragraph","content":[{"type":"text","text":"Intro"}]},
          {"type":"paragraph","content":[
            {"type":"text","text":"The "},
            {"type":"text","text":"red words","marks":[{"type":"textStyle","attrs":{"color":"#c0392b"}}]},
            {"type":"text","text":" stay, and "},
            {"type":"text","text":"this note","marks":[{"type":"note","attrs":{"note":"Check this","id":"nkeep1"}}]},
            {"type":"text","text":" too."}
          ]},
          {"type":"paragraph","content":[
            {"type":"text","text":"Old "},
            {"type":"text","text":"gone words","marks":[{"type":"note","attrs":{"note":"Lost","id":"ngone1"}}]},
            {"type":"text","text":" here."}
          ]}
        ]}"""
        val id = graphQlTester.document(
            """mutation(${'$'}w: ID!, ${'$'}d: String) { createDocument(workspaceId: ${'$'}w, name: "Marked", content: ${'$'}d) { id } }""",
        ).variable("w", workspaceId).variable("d", marked).execute().path("createDocument.id").entity(Long::class.java).get()
        SecurityContextHolder.clearContext()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Writer")
        fun call(name: String, arguments: Map<String, Any>): JsonNode = mapper.readTree(tools.run(scope, name, mapper.writeValueAsString(arguments))).also {
            assertThat(it.has("error")).describedAs("$name: $it").isFalse()
        }
        fun marks(): List<String> {
            val blocks = mapper.readTree(documents.findById(id).get().content).path("content")
            return (0 until blocks.size()).flatMap { b ->
                val inner = blocks.get(b).path("content")
                (0 until inner.size()).map { inner.get(it) }.filter { it.has("marks") }.map { node ->
                    val mark = node.path("marks").get(0)
                    "$b:" + node.path("text").asText() + "=" + mark.path("attrs").path("color").asText(mark.path("attrs").path("id").asText())
                }
            }
        }

        val read = call(DocumentTools.READ, mapOf("document" to id.toString()))
        assertThat(read.path("markdown").stringValue()).contains(
            "The <span style=\"color:#c0392b\">red words</span> stay, and <mark data-note=\"Check this\" data-note-id=\"nkeep1\">this note</mark> too.",
        )
        assertThat(read.path("blocks").get(1).path("text").stringValue()).isEqualTo("The red words stay, and this note too.")
        assertThat(tools.specs(scope).first { it.name == DocumentTools.WRITE }.description).contains(DocumentMarkdown.MARKS_SAID)

        // Written with the tags: they are the marks.
        call(
            DocumentTools.INSERT,
            mapOf("document" to id.toString(), "at" to 3, "markdown" to "Now <span style=\"color:#2e86c1\">blue</span> and <mark data-note=\"New one\">fresh</mark>."),
        )
        assertThat(marks()).contains("3:blue=#2e86c1")
        assertThat(marks().first { it.startsWith("3:fresh=") }.substringAfter('=')).matches(DocumentContent.NOTE_ID.pattern)

        // Rewritten without them: what stayed keeps its marks, and the highlight whose words went is named.
        val answer = call(
            DocumentTools.WRITE,
            mapOf("document" to id.toString(), "from" to 1, "to" to 2, "markdown" to "The red words stay, and this note too, now longer.\n\nCompletely different text."),
        )
        assertThat(marks()).contains("1:red words=#c0392b", "1:this note=nkeep1", "3:blue=#2e86c1").noneMatch { it.endsWith("=ngone1") }
        assertThat((0 until answer.path("dropped").size()).map { answer.path("dropped").get(it).stringValue() }).containsExactly("highlight 'gone words' was removed with its words")
        assertThat(answer.path("note").stringValue()).contains("highlight 'gone words' was removed with its words")

        // A hostile tag leaves nothing that runs.
        call(
            DocumentTools.WRITE,
            mapOf("document" to id.toString(), "from" to 2, "markdown" to "<span style=\"color:#fff;background:url(javascript:x)\" onclick=\"x()\">a</span> <script>x()</script> <mark data-note=\"${"y".repeat(5000)}\">b</mark>"),
        )
        val stored = documents.findById(id).get().content
        assertThat(stored).doesNotContain("onclick", "javascript").contains("<script>")
        assertThat(marks()).contains("2:a=#ffffff")
        val note = mapper.readTree(stored).path("content").get(2).path("content").last().path("marks").get(0).path("attrs").path("note").stringValue()
        assertThat(note).hasSize(Documents.NOTE_LENGTH)

        // A colour whose words went in a sentence rewritten round them is named beside the highlights.
        val recoloured = call(
            DocumentTools.WRITE,
            mapOf("document" to id.toString(), "from" to 1, "markdown" to "The scarlet text stays, and this note too, now longer."),
        )
        assertThat(marks()).contains("1:this note=nkeep1").noneMatch { it.startsWith("1:") && it.endsWith("=#c0392b") }
        assertThat((0 until recoloured.path("dropped").size()).map { recoloured.path("dropped").get(it).stringValue() })
            .containsExactly("colour #c0392b on 'red words' was removed with its words")
        assertThat(recoloured.path("note").stringValue()).contains("colour #c0392b on 'red words' was removed with its words")
    }

    /** The briefing names every tool it mentions off what is offered, the chat is offered writing alone, and it is told not to link the document the person has open. */
    @Test
    fun `the chat names only the tools it is offered`() {
        val view = DesignDocumentView(1, workspaceId, 1, "General", "Brief", "{}", "A4", 1, 0, "", emptyList(), "alice", "", "alice", "")
        val offered = chat.offered(OrknuxScope(workspaceId = workspaceId, mayWrite = true))
        assertThat(offered.map { it.name }).containsExactlyInAnyOrderElementsOf(DocumentTools.EDITING)
        val briefing = chat.briefing(view, offered)
        DocumentTools.EDITING.forEach { assertThat(briefing).contains("`$it`") }
        assertThat(briefing).contains("never link to it", DocumentMarkdown.MARKS_SAID)
    }

    /** A model asked in the editor appends a section; it is in the stored document, and the answer says it changed. */
    @Test
    fun `a model asked in the editor writes into the stored document`() {
        val id = graphQlTester.document(
            """mutation(${'$'}w: ID!, ${'$'}d: String) { createDocument(workspaceId: ${'$'}w, name: "Brief", content: ${'$'}d) { id } }""",
        ).variable("w", workspaceId).variable("d", DocumentAPITest.PARAGRAPH("Intro"))
            .execute().path("createDocument.id").entity(Long::class.java).get()
        val modelId = model(
            serve { body ->
                if (received.size == 1) {
                    val arguments = mapper.writeValueAsString(mapOf("document" to id.toString(), "heading" to "Goals", "markdown" to "Ship it."))
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                       {"id":"call_1","type":"function","function":{"name":"${DocumentTools.APPEND}","arguments":${mapper.writeValueAsString(arguments)}}}
                     ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                } else {
                    assertThat(body).contains("Goals")
                    """{"choices":[{"message":{"role":"assistant","content":"Added a Goals section."}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                }
            },
        )
        graphQlTester.document(
            """mutation { askDocument(id: $id, modelId: $modelId, messages: [{role: "user", content: "Add goals"}]) {
                 answer changed document { revision words } } }""",
        ).execute()
            .path("askDocument.answer").entity(String::class.java).isEqualTo("Added a Goals section.")
            .path("askDocument.changed").entity(Boolean::class.java).isEqualTo(true)
        assertThat(received.first()).contains(DocumentTools.APPEND).doesNotContain("orknux_workflows")
        assertThat(markdownOf(id)).isEqualTo("Intro\n\n## Goals\n\nShip it.\n")
        assertThat(audit.findAll().filter { it.message == "Document Brief saved" }.map { it.userId }).containsExactly("alice")
    }

    /**
     * Explain and Summarise ask about the document rather than in it: offered only the tools that read,
     * told so, and the document is as it was - however hard the model tries to write.
     */
    @Test
    fun `a read-only ask is offered only the reading tools and changes nothing`() {
        val id = graphQlTester.document(
            """mutation(${'$'}w: ID!, ${'$'}d: String) { createDocument(workspaceId: ${'$'}w, name: "Brief", content: ${'$'}d) { id revision } }""",
        ).variable("w", workspaceId).variable("d", DocumentAPITest.PARAGRAPH("Intro"))
            .execute().path("createDocument.id").entity(Long::class.java).get()
        val modelId = model(
            serve { _ ->
                if (received.size == 1) {
                    val arguments = mapper.writeValueAsString(mapOf("document" to id.toString(), "heading" to "Goals", "markdown" to "Ship it."))
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                       {"id":"call_1","type":"function","function":{"name":"${DocumentTools.APPEND}","arguments":${mapper.writeValueAsString(arguments)}}}
                     ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                } else {
                    """{"choices":[{"message":{"role":"assistant","content":"It introduces the brief."}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                }
            },
        )
        graphQlTester.document(
            """mutation { askDocument(id: $id, modelId: $modelId, readOnly: true, messages: [{role: "user", content: "Explain: Intro"}]) {
                 answer changed document { revision } } }""",
        ).execute()
            .path("askDocument.answer").entity(String::class.java).isEqualTo("It introduces the brief.")
            .path("askDocument.changed").entity(Boolean::class.java).isEqualTo(false)
        val sentTools = mapper.readTree(received.first()).path("tools")
        val offeredNames = (0 until sentTools.size()).map { sentTools.get(it).path("function").path("name").stringValue().orEmpty() }
        assertThat(offeredNames).containsExactlyInAnyOrder(*DocumentTools.READING.toTypedArray())
        assertThat(received.first()).contains("nothing you say is written into the document")
        assertThat(received[1]).describedAs("the write it asked for was not run").contains("There is no tool called ${DocumentTools.APPEND} here")
        assertThat(markdownOf(id)).isEqualTo("Intro\n")
        assertThat(audit.findAll().map { it.message }).noneMatch { it == "Document Brief saved" }
    }

    /** Translate: one call, no tools, the words around the selection as context, the translation alone back. */
    @Test
    fun `a selection is translated into the language asked, with the words around it, and nothing is written`() {
        val id = graphQlTester.document(
            """mutation(${'$'}w: ID!, ${'$'}d: String) { createDocument(workspaceId: ${'$'}w, name: "Brief", content: ${'$'}d) { id } }""",
        ).variable("w", workspaceId).variable("d", DocumentAPITest.PARAGRAPH("We ship the editor in May."))
            .execute().path("createDocument.id").entity(Long::class.java).get()
        val modelId = model(serve { """{"choices":[{"message":{"role":"assistant","content":"\"edytor\""}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""" })
        fun translate(text: String, language: String) = graphQlTester.document(
            """mutation(${'$'}t: String!, ${'$'}l: String!) { translateDocumentText(id: $id, modelId: $modelId, text: ${'$'}t, language: ${'$'}l,
                 before: "We ship the ", after: " in May.") }""",
        ).variable("t", text).variable("l", language).execute()

        translate("editor", "pl").path("translateDocumentText").entity(String::class.java).isEqualTo("edytor")
        val sent = mapper.readTree(received.single())
        assertThat(sent.path("tools").isMissingNode || sent.path("tools").isEmpty).isTrue()
        assertThat(received.single()).contains("Translate into Polish", "<text>\\neditor\\n</text>", "We ship the ", " in May.")
        assertThat(markdownOf(id)).isEqualTo("We ship the editor in May.\n")

        translate("editor", "xx").errors().expect { it.message?.startsWith("A selection is translated into en, pl,") == true && it.message!!.endsWith("not xx") }.verify()
        translate("w".repeat(8_001), "pl").errors()
            .expect { it.message == "A selection sent to the model holds at most 8000 characters, and this one holds 8001" }.verify()
        assertThat(received).hasSize(1)

        // With a companion model the workspace's companion translates, not the model chosen to write with.
        val companion = model(serve { """{"choices":[{"message":{"role":"assistant","content":"\"edytor\""}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""" }, "companion-stub")
        workspaces.save(workspaces.findById(workspaceId).get().apply { companionModelId = companion })
        translate("editor", "pl").path("translateDocumentText").entity(String::class.java).isEqualTo("edytor")
        assertThat(mapper.readTree(received.last()).path("model").asText()).isEqualTo("companion-stub")
    }

    /** The editor's suggestion: the workspace's completion model, the title and the text around the caret, only the new words. */
    @Test
    fun `the editor's suggestion comes from the completion model, shown the text around the caret`() {
        val id = graphQlTester.document(
            """mutation(${'$'}w: ID!) { createDocument(workspaceId: ${'$'}w, name: "Launch plan") { id } }""",
        ).variable("w", workspaceId).execute().path("createDocument.id").entity(Long::class.java).get()
        fun ask() = graphQlTester.document(
            """{ documentCompletion(id: $id, before: "We will ship the editor", after: "Then we rest.", page: 2) }""",
        ).execute().errors().verify().path("documentCompletion")

        // No completion model chosen: nothing is asked and nothing suggested.
        val modelId = model(serve { """{"choices":[{"message":{"role":"assistant","content":"the editor in May."}}],"usage":{"prompt_tokens":4,"completion_tokens":3}}""" })
        ask().valueIsNull()
        assertThat(received).isEmpty()

        graphQlTester.document("""mutation { setWorkspaceCompletionModel(workspaceId: $workspaceId, modelId: $modelId) { completionModelId } }""")
            .execute().errors().verify()
        ask().entity(String::class.java).isEqualTo(" in May.")
        assertThat(received.single()).contains("Launch plan", "page 2", "We will ship the editor", "Then we rest.")
    }

    /** An agent from before the document tools carries them after the migration, Always; one without the grant gets none. */
    @Test
    fun `the migration marks the document tools Always on agents with Orknux access`() {
        fun made(name: String, access: Boolean): Long {
            val id = graphQlTester.document(
                """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM, orknuxAccess: $access }) { id } }""",
            ).execute().path("createAgent.id").entity(Long::class.java).get()
            // Put back the way an agent from before the document tools was left.
            val agent = requireNotNull(agents.findByIdOrNull(id))
            agent.requiredTools = BuiltInTools.GRANTED.toMutableList()
            agents.save(agent)
            return id
        }
        val with = made("With access", true)
        val without = made("Without", false)
        val sqlite = isSqlite(jdbc.dataSource?.let { jdbcUrlOf(it) })
        val path = if (sqlite) "db/migration/sqlite/V121__orknux_document_tools_always.sql" else "db/migration/postgresql/V443__orknux_document_tools_always.sql"
        val migration = ClassPathResource(path).inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
        val written = Regex("'(orknux_[a-z_]+)'").findAll(migration).map { it.groupValues[1] }.toList()
        assertThat(written).containsExactlyInAnyOrderElementsOf(DocumentTools.NAMES)
        assertThat(builtIns.orknuxNames()).containsAll(written)

        jdbc.execute(migration)
        jdbc.execute(migration)
        assertThat(requireNotNull(agents.findByIdOrNull(with)).requiredTools).containsAll(DocumentTools.NAMES).doesNotHaveDuplicates()
        assertThat(requireNotNull(agents.findByIdOrNull(without)).requiredTools).noneMatch { it.startsWith("orknux_document") }
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

    private fun model(endpoint: String, served: String = "stub"): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub ${System.nanoTime()}", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "$served", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
