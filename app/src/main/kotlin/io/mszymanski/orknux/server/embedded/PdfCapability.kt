package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.llm.SessionScratchpadService
import io.mszymanski.orknux.server.plugin.PdfRenderer
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * Making a document, reading one, and looking at one. Issues #506 and #505.
 *
 * The last of the PDF bundle. Reading and rasterising were already JVM code -
 * `PdfRenderer` has done both on PDFBox since the sandbox could do neither -
 * and the bundle only ever passed the call through. Writing is now
 * [PdfWriter], so there is nothing JavaScript left and the bundle goes.
 *
 * **What comes back is a key, never the bytes.** A PDF is hundreds of
 * thousands of characters of base64, and an answer that large reaches the next
 * call only by being written out again by whatever read it - which is the one
 * thing that does not survive. So the document is put in the session store and
 * its key is the answer, for whatever sends, uploads or saves a file. The
 * exception is `preview`, where the picture *is* the point of asking.
 */
@Component
class PdfCapability(
    private val writer: PdfWriter,
    private val renderer: PdfRenderer,
    private val pads: SessionScratchpadService,
    private val scratch: LlmSessionStore,
    private val mapper: ObjectMapper,
) : EmbeddedCapability {

    private val log = LoggerFactory.getLogger(javaClass)

    override val key = "pdf"
    override val name = "PDF"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = FROM_HTML,
            summary = "Lays HTML out as a PDF, drawing diagrams and charts in.",
            description = "Lays a page of HTML out as a PDF on A4 and answers a key for it. Real CSS, tables, " +
                "lists and page breaks all work. A diagram goes in as <pre class=\"mermaid\">…</pre> and a " +
                "chart as <pre class=\"chart\">{\"kind\":\"pie\",\"values\":{\"Rent\":45}}</pre>; both are drawn " +
                "onto the page as vectors. A picture goes in as <img src=\"name\"> naming a scratchpad in this " +
                "session or a key something handed you. Where the page is already in a scratchpad, pass " +
                "$PAD with its name and leave $HTML out - the page is read here rather than typed through " +
                "you, which is what stops a long report being cut off at your output limit. The answer " +
                "carries a $KEY, not the document: pass it to whatever uploads or saves a file.",
            params = listOf(
                EmbeddedParam(HTML, ValueType.STRING, "The page itself. Leave out when passing $PAD or $KEY."),
                EmbeddedParam(PAD, ValueType.STRING, "A scratchpad in this session holding the page."),
                EmbeddedParam(KEY, ValueType.STRING, "A key something handed you, holding the page."),
                EmbeddedParam(TITLE, ValueType.STRING, "What the document is called."),
            ),
        ),
        EmbeddedTool(
            name = READ,
            summary = "Reads what a PDF says, as text you can act on.",
            description = "Reads a PDF and answers its text - to find a total, quote a clause, or decide " +
                "whether a report is worth passing on. Takes a $KEY: the one fromHtml answered, or the one " +
                "an attachment reader gave you. There is deliberately no base64 argument, because a " +
                "document does not survive being written out through you.",
            params = listOf(
                EmbeddedParam(KEY, ValueType.STRING, "The key the document is kept under.", required = true),
                EmbeddedParam(FROM, ValueType.NUMBER, "First page, counting from 1. Left out, the start."),
                EmbeddedParam(TO, ValueType.NUMBER, "Last page. Left out, the end."),
            ),
        ),
        EmbeddedTool(
            name = PREVIEW,
            summary = "Draws one page of a PDF so you can look at it.",
            description = "Draws one page of a PDF as a picture, so you can see what a document you made " +
                "actually looks like rather than assuming it worked. Takes a $KEY. Unlike everything else " +
                "here the picture comes back rather than a key, because looking at it is the point.",
            params = listOf(
                EmbeddedParam(KEY, ValueType.STRING, "The key the document is kept under.", required = true),
                EmbeddedParam(PAGE, ValueType.NUMBER, "Which page, counting from 1. Left out, the first."),
                EmbeddedParam(WIDTH, ValueType.NUMBER, "How wide the picture should be, in pixels."),
            ),
        ),
    )

    /**
     * The workflow half. `fromHtml` takes the page outright here rather than a
     * key, because a graph node has no session to read one from - the same
     * split the bundle had, and for the same reason.
     */
    override fun functions(): List<EmbeddedFunction> = listOf(
        EmbeddedFunction(
            name = FROM_HTML,
            description = "Lays HTML out as a PDF and answers the document as base64, with its page count.",
            params = listOf(
                EmbeddedParam(HTML, ValueType.STRING, "The page itself.", required = true),
                EmbeddedParam(TITLE, ValueType.STRING, "What the document is called."),
            ),
        ),
        EmbeddedFunction(
            name = READ,
            description = "Reads a PDF given as base64 and answers its text.",
            params = listOf(
                EmbeddedParam(BASE64, ValueType.STRING, "The document.", required = true),
                EmbeddedParam(FROM, ValueType.NUMBER, "First page, counting from 1."),
                EmbeddedParam(TO, ValueType.NUMBER, "Last page."),
            ),
        ),
    )

    /* ------------------------------------------------------------- as a tool */

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return refusal("That is not valid JSON.")
        return when (name) {
            FROM_HTML -> fromHtml(asked, sessionId)
            READ -> read(asked, sessionId)
            PREVIEW -> preview(asked, sessionId)
            else -> refusal("There is no tool called $key" + "_" + name + ".")
        }
    }

    private fun fromHtml(asked: JsonNode, sessionId: Long?): String {
        val page = pageFrom(asked, sessionId)
            ?: return refusal(
                "Give the page: $HTML written out, $PAD naming a scratchpad in this session, or $KEY for " +
                    "one something handed you.",
            )

        val made = writer.fromHtml(page, text(asked, TITLE)) { named -> picture(named, sessionId) }
        if (made is PdfWriter.Written.Refused) return refusal(made.reason)
        val document = made as PdfWriter.Written.Made

        val answer = linkedMapOf<String, Any?>("bytes" to document.pdf.size, "pages" to pagesIn(document.pdf))

        /*
         * A key where there is a session to keep one in, and the base64 where
         * there is not - because then it is the only copy there is.
         */
        if (sessionId == null) {
            answer["base64"] = Base64.getEncoder().encodeToString(document.pdf)
        } else {
            val named = (text(asked, TITLE)?.trim()?.ifEmpty { null } ?: "document") + ".pdf"
            scratch.put(sessionId, named, mapper.writeValueAsString(Base64.getEncoder().encodeToString(document.pdf)))
            answer[KEY] = named
            answer["note"] = "Pass $KEY to whatever uploads or saves a file. The bytes are not text."
        }
        if (document.problems.isNotEmpty()) answer["problems"] = document.problems
        return mapper.writeValueAsString(answer)
    }

    private fun read(asked: JsonNode, sessionId: Long?): String {
        val pdf = bytesFor(text(asked, KEY), sessionId) ?: return refusal(keyRefusal(text(asked, KEY)))
        return when (val said = renderer.html(pdf, number(asked, FROM), number(asked, TO))) {
            is PdfRenderer.Reading.Refused -> refusal(said.reason)
            is PdfRenderer.Reading.Read -> mapper.writeValueAsString(
                linkedMapOf(
                    "html" to said.html,
                    "pages" to said.pages,
                    FROM to said.from,
                    TO to said.to,
                    "characters" to said.characters,
                ),
            )
        }
    }

    private fun preview(asked: JsonNode, sessionId: Long?): String {
        val pdf = bytesFor(text(asked, KEY), sessionId) ?: return refusal(keyRefusal(text(asked, KEY)))
        return when (val drawn = renderer.png(pdf, number(asked, PAGE) ?: 1, number(asked, WIDTH))) {
            is PdfRenderer.Drawing.Refused -> refusal(drawn.reason)
            is PdfRenderer.Drawing.Drawn -> mapper.writeValueAsString(
                linkedMapOf(
                    "picture" to Base64.getEncoder().encodeToString(drawn.png),
                    "pictureType" to "image/png",
                    "width" to drawn.width,
                    "height" to drawn.height,
                    "pages" to drawn.pages,
                ),
            )
        }
    }

    /* --------------------------------------------------------- as a function */

    override fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? =
        when (name) {
            FROM_HTML -> {
                val html = unquoted(arguments.getOrNull(0)).orEmpty()
                val title = unquoted(arguments.getOrNull(1))
                when (val made = writer.fromHtml(html, title)) {
                    is PdfWriter.Written.Refused -> ScriptResult.Failed(made.reason, 0)
                    is PdfWriter.Written.Made -> ScriptResult.Returned(
                        mapper.writeValueAsString(
                            linkedMapOf(
                                BASE64 to Base64.getEncoder().encodeToString(made.pdf),
                                "bytes" to made.pdf.size,
                                "pages" to pagesIn(made.pdf),
                                "problems" to made.problems,
                            ),
                        ),
                        0,
                    )
                }
            }

            READ -> {
                val pdf = runCatching { Base64.getDecoder().decode(unquoted(arguments.getOrNull(0)).orEmpty()) }
                    .getOrNull()
                    ?: return ScriptResult.Failed("that is not base64", 0)
                val from = numberOf(arguments.getOrNull(1))
                val to = numberOf(arguments.getOrNull(2))
                when (val said = renderer.html(pdf, from, to)) {
                    is PdfRenderer.Reading.Refused -> ScriptResult.Failed(said.reason, 0)
                    is PdfRenderer.Reading.Read -> ScriptResult.Returned(
                        mapper.writeValueAsString(
                            linkedMapOf("html" to said.html, "pages" to said.pages, "characters" to said.characters),
                        ),
                        0,
                    )
                }
            }

            else -> null
        }

    /* ---------------------------------------------------------------- shared */

    /**
     * How many pages it came to, which a caller wants and the writer does not
     * answer. Read back off the finished document rather than counted while it
     * was written: the layout decides where a page ends, so the document is the
     * only thing that knows.
     */
    private fun pagesIn(pdf: ByteArray): Int? =
        runCatching { org.apache.pdfbox.Loader.loadPDF(pdf).use { it.numberOfPages } }.getOrNull()

    /** The page, from wherever it already is. Issue #495. */
    private fun pageFrom(asked: JsonNode, sessionId: Long?): String? {
        text(asked, HTML)?.takeIf { it.isNotBlank() }?.let { return it }
        if (sessionId == null) return null
        text(asked, PAD)?.trim()?.takeIf { it.isNotEmpty() }?.let { named ->
            return pads.find(sessionId, named)?.content
        }
        text(asked, KEY)?.trim()?.takeIf { it.isNotEmpty() }?.let { named ->
            return scratch.get(sessionId, named)?.let { held -> parsed(held) }
        }
        return null
    }

    /** A picture the page named: a scratchpad first, then a key. */
    private fun picture(named: String, sessionId: Long?): PageBlocks.Picture? {
        if (sessionId == null) return null
        pads.find(sessionId, named)?.let { pad ->
            val bytes = if (pad.contentType == null) {
                pad.content.toByteArray()
            } else {
                runCatching { Base64.getDecoder().decode(pad.content) }.getOrNull()
            }
            if (bytes != null) return PageBlocks.Picture(bytes, pad.contentType)
        }
        val held = scratch.get(sessionId, named) ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(parsed(held)) }.getOrNull() ?: return null
        return PageBlocks.Picture(bytes, null)
    }

    /** The bytes a key holds, which is how a document reaches the next call. */
    private fun bytesFor(named: String?, sessionId: Long?): ByteArray? {
        val key = named?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (sessionId == null) return null
        pads.find(sessionId, key)?.takeIf { it.contentType != null }?.let { pad ->
            return runCatching { Base64.getDecoder().decode(pad.content) }.getOrNull()
        }
        val held = scratch.get(sessionId, key) ?: return null
        return runCatching { Base64.getDecoder().decode(parsed(held)) }.getOrNull()
    }

    private fun keyRefusal(named: String?): String =
        if (named.isNullOrBlank()) {
            "Give the $KEY of a document - the one fromHtml answered, or the one an attachment reader gave you."
        } else {
            "Nothing in this session is kept under \"$named\"."
        }

    /**
     * What the store holds is a JSON value, so it is parsed before it is
     * anything else - issue #499, where reading the raw row put the quotes
     * into the base64 and every picture came out as text.
     */
    private fun parsed(stored: String): String =
        runCatching { mapper.readTree(stored) }.getOrNull()?.takeIf { it.isTextual }?.stringValue() ?: stored

    private fun text(node: JsonNode, name: String): String? =
        node.path(name).takeIf { it.isTextual }?.stringValue()

    private fun number(node: JsonNode, name: String): Int? =
        node.path(name).takeIf { it.isNumber }?.intValue()?.takeIf { it > 0 }

    /** An argument arrives as the JSON a graph wrote, so a string is quoted. */
    private fun unquoted(argument: String?): String? {
        val given = argument?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return runCatching { mapper.readTree(given) }.getOrNull()
            ?.takeIf { it.isTextual }?.stringValue()
            ?: given
    }

    private fun numberOf(argument: String?): Int? = unquoted(argument)?.toIntOrNull()?.takeIf { it > 0 }

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    private companion object {
        const val FROM_HTML = "fromHtml"
        const val READ = "read"
        const val PREVIEW = "preview"

        const val HTML = "html"
        const val PAD = "scratchpad"
        const val KEY = "contentKey"
        const val TITLE = "title"
        const val BASE64 = "base64"
        const val FROM = "from"
        const val TO = "to"
        const val PAGE = "page"
        const val WIDTH = "width"
    }
}
