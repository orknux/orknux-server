package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.llm.SessionScratchpadService
import io.mszymanski.orknux.server.plugin.PdfRenderer
import io.mszymanski.orknux.server.plugin.PdfSource
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
    private val pictures: SessionPictures,
    private val mapper: ObjectMapper,
    private val texts: PdfText,
    private val tuning: io.mszymanski.orknux.server.tuning.Tuning,
) : EmbeddedCapability {

    private val log = LoggerFactory.getLogger(javaClass)

    override val key = CAPABILITY
    override val name = "PDF"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = FROM_HTML,
            readOnly = true,
            summary = "Lays HTML out as a PDF, drawing diagrams and charts in.",
            description = "Lays a page of HTML out as a PDF on A4 and answers a key for it. Real CSS, tables, " +
                "lists and page breaks all work. A diagram goes in as <pre class=\"mermaid\">…</pre> and a " +
                "chart as <pre class=\"chart\">{\"kind\":\"pie\",\"values\":{\"Rent\":45}}</pre>; both are drawn " +
                "onto the page as vectors. No JavaScript runs, so a <canvas>, Chart.js or mermaid.js loaded " +
                "from a web address draws nothing. A picture goes in as <img src=\"name\"> naming a scratchpad in this " +
                "session or a key something handed you. Where the page is already in a scratchpad, pass " +
                "$PAD with its name and leave $HTML out - the page is read here rather than typed through " +
                "you, which is what stops a long report being cut off at your output limit. A stylesheet the " +
                "page links by the name of a scratchpad - style.css - is read from that pad. A report " +
                "already zipped with its pictures goes to $FROM_HTML_ZIP instead. The answer " +
                "carries a $KEY, not the document: pass it to whatever uploads or saves a file.",
            params = listOf(
                EmbeddedParam(HTML, ValueType.STRING, "The page itself. Leave out when passing $PAD or $KEY."),
                EmbeddedParam(PAD, ValueType.STRING, "A scratchpad in this session holding the page."),
                EmbeddedParam(KEY, ValueType.STRING, "A key something handed you, holding the page."),
                EmbeddedParam(TITLE, ValueType.STRING, "What the document is called."),
            ),
        ),
        EmbeddedTool(
            name = FROM_HTML_ZIP,
            readOnly = true,
            summary = "Lays an HTML report zipped with its pictures out as a PDF.",
            description = "Lays out as a PDF an HTML page kept in a zip with its stylesheet and pictures - " +
                "the archive zip_files made for a report - and answers a key for it. Stylesheets the page " +
                "links and pictures it names by a relative path are read from inside the zip, so nothing has " +
                "to be rebuilt. Pass the zip's $KEY, and $FILE for the page when it is not index.html. The " +
                "same rules as $FROM_HTML apply: no JavaScript runs, and <pre class=\"mermaid\"> and " +
                "<pre class=\"chart\"> blocks are drawn.",
            params = listOf(
                EmbeddedParam(KEY, ValueType.STRING, "The zip's key.", required = true),
                EmbeddedParam(FILE, ValueType.STRING, "The page inside it. Left out, index.html."),
                EmbeddedParam(TITLE, ValueType.STRING, "What the document is called."),
            ),
        ),
        EmbeddedTool(
            name = TO_PNG,
            readOnly = true,
            summary = "Draws a page of HTML as a picture.",
            description = "Draws HTML as a picture you can look at or send - a chart, a table, a small " +
                "report, anything you would otherwise have to describe. Takes the same page $FROM_HTML does, " +
                "including <pre class=\"mermaid\"> and <pre class=\"chart\"> blocks, and the same $PAD and " +
                "$KEY shortcuts so a long page is not typed through you. Answers both the picture and a $KEY " +
                "for it, so you can see it and still send it; for a document somebody keeps, use " +
                "$FROM_HTML instead. Only you see the picture unless you pass $SHOW true, which puts it in the " +
                "conversation for the person - do that when they asked to see it.",
            params = listOf(
                EmbeddedParam(HTML, ValueType.STRING, "The page itself. Leave out when passing $PAD or $KEY."),
                EmbeddedParam(PAD, ValueType.STRING, "A scratchpad in this session holding the page."),
                EmbeddedParam(KEY, ValueType.STRING, "A key something handed you, holding the page."),
                EmbeddedParam(WIDTH, ValueType.NUMBER, "How wide the picture should be, in pixels."),
                EmbeddedParam(SHOW, ValueType.BOOLEAN, "true to put the picture in the conversation, where the person sees it. Left out, only you see it."),
            ),
        ),
        EmbeddedTool(
            name = READ,
            readOnly = true,
            summary = "Reads what a PDF says, as text you can act on.",
            description = "Reads a PDF and answers its text - to find a total, quote a clause, or decide " +
                "whether a report is worth passing on. Takes a $KEY: the one fromHtml answered, or the one " +
                "an attachment reader gave you. There is deliberately no base64 argument, because a " +
                "document does not survive being written out through you. To find something in a long document, " +
                "search it with $SEARCH_TOOL first and read only the pages it names, with $FROM and $TO.",
            params = listOf(
                EmbeddedParam(KEY, ValueType.STRING, "The key the document is kept under.", required = true),
                EmbeddedParam(FROM, ValueType.NUMBER, "First page, counting from 1. Left out, the start."),
                EmbeddedParam(TO, ValueType.NUMBER, "Last page. Left out, the end."),
            ),
            reads = StoredReader(setOf("application/pdf"), KEY, range = FROM to TO),
        ),
        EmbeddedTool(
            name = SEARCH,
            readOnly = true,
            summary = "Finds words in a PDF and answers the pages they are on, without reading it whole.",
            description = "Searches a PDF kept under a $KEY for words, a phrase or a regular expression, without " +
                "the document entering the conversation: only the matches do. Use it instead of reading a long " +
                "document whole with $READ_TOOL. Answers how many matches there are in all and on which pages, and " +
                "for each listed one its page and the text around it with the match between « and ». Plain text and " +
                "case-insensitive unless told otherwise; a phrase matches across line breaks. Then read just those " +
                "pages with $READ_TOOL's $FROM and $TO. A scanned document has no text to search, and says so.",
            params = listOf(
                EmbeddedParam(KEY, ValueType.STRING, "The key the document is kept under.", required = true),
                EmbeddedParam(QUERY, ValueType.STRING, "What to find.", required = true),
                EmbeddedParam(REGEX, ValueType.BOOLEAN, "true to read $QUERY as a regular expression."),
                EmbeddedParam(WHOLE_WORD, ValueType.BOOLEAN, "true to match only whole words: \"net\" then does not match \"network\"."),
                EmbeddedParam(CASE_SENSITIVE, ValueType.BOOLEAN, "true to tell capitals from small letters."),
                EmbeddedParam(LIMIT, ValueType.NUMBER, "How many matches to list. The total is counted either way."),
            ),
            asks = StoredReader(setOf("application/pdf"), KEY),
        ),
        EmbeddedTool(
            name = PREVIEW,
            readOnly = true,
            summary = "Draws one page of a PDF so you can look at it.",
            description = "Draws one page of a PDF as a picture, so you can see what a document you made " +
                "actually looks like rather than assuming it worked, or read what text alone does not carry - " +
                "music, a drawing, a scanned page. Takes a $KEY, and answers both the picture " +
                "and a $KEY of its own - so you can see it and still pass it on to be sent. Only you see the " +
                "picture: it is not put in the conversation.",
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
            FROM_HTML_ZIP -> fromHtmlZip(asked, sessionId)
            TO_PNG -> toPng(asked, sessionId)
            READ -> read(asked, sessionId)
            SEARCH -> search(asked, sessionId)
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

        /*
         * And the stylesheet it links, where a scratchpad has that name. Issue
         * #563: an agent builds a report as index.html and style.css in two pads,
         * and handed the page alone the layout lost every style - so a model set
         * about pasting the stylesheet in by hand, which is the retyping the
         * pads exist to avoid.
         */
        val linked = if (sessionId == null) page else styled(page, "") { href ->
            pads.find(sessionId, href)?.takeIf { it.contentType == null }?.content?.toByteArray()
        }
        val made = writer.fromHtml(linked, text(asked, TITLE)) { named -> picture(named, sessionId) }
        return documentAnswer(made, asked, sessionId)
    }

    /**
     * A report zipped with its pictures, as a PDF. Issue #563.
     *
     * An agent building an HTML report now delivers it as an archive - the
     * page, its stylesheet, its pictures under images/ - and turning that into a
     * PDF meant pulling it apart again, because [fromHtml] takes one page and
     * finds pictures only by session key. Here the page's linked stylesheets
     * are put inline and every relative src is read from the zip, falling back
     * to the session's keys. Bounded the way [io.mszymanski.orknux.server.chat.ZipTools]
     * bounds what it packs, so a key cannot unpack into more than a zip could hold.
     */
    private fun fromHtmlZip(asked: JsonNode, sessionId: Long?): String {
        val zipKey = text(asked, KEY)
        val bytes = bytesFor(zipKey, sessionId) ?: return refusal(keyRefusal(zipKey, sessionId))
        val entries = unzipped(bytes)
            ?: return refusal(
                "\"$zipKey\" is not a zip this can open: it is not an archive, or it holds more than " +
                    "${io.mszymanski.orknux.server.chat.ZipTools.MOST_FILES} files or " +
                    "${io.mszymanski.orknux.server.chat.ZipTools.MOST_BYTES / (1024 * 1024)} MB.",
            )
        val file = text(asked, FILE)?.trim()?.ifEmpty { null }
        val pageName = file?.let { normalised(it) }
            ?: entries.keys.firstOrNull { it.equals("index.html", ignoreCase = true) }
            ?: entries.keys.firstOrNull { it.endsWith(".html", true) || it.endsWith(".htm", true) }
            ?: return refusal("That archive holds no HTML page.")
        val page = entries[pageName]?.toString(Charsets.UTF_8)
            ?: return refusal(
                "That archive has no $pageName. It holds: " + entries.keys.sorted().joinToString(", ") + ".",
            )
        val base = pageName.substringBeforeLast('/', "")

        val made = writer.fromHtml(styled(page, base) { entries[it] }, text(asked, TITLE)) { named ->
            entries[resolved(base, named)]?.let { PageBlocks.Picture(it, null) } ?: picture(named, sessionId)
        }
        return documentAnswer(made, asked, sessionId)
    }

    /** Every file in the archive by its path; see [io.mszymanski.orknux.server.chat.ZipTools.unpacked]. */
    private fun unzipped(bytes: ByteArray): Map<String, ByteArray>? =
        io.mszymanski.orknux.server.chat.ZipTools.unpacked(bytes)

    private fun normalised(path: String): String = io.mszymanski.orknux.server.chat.ZipTools.normalised(path)

    /** Where a relative src or href points, from the page's own folder. */
    private fun resolved(base: String, src: String): String {
        val bare = src.substringBefore('?').substringBefore('#')
        return normalised(if (base.isEmpty() || bare.startsWith("/")) bare else "$base/$bare")
    }

    /** The page with every stylesheet it links put inline, from wherever [find] reads a path. */
    private fun styled(page: String, base: String, find: (String) -> ByteArray?): String {
        val parsed = org.jsoup.Jsoup.parse(page)
        var changed = false
        parsed.select("link[rel~=(?i)stylesheet][href]").forEach { link ->
            val href = link.attr("href").trim()
            if (href.contains("://")) return@forEach
            val css = find(resolved(base, href)) ?: return@forEach
            link.after("<style>" + css.toString(Charsets.UTF_8) + "</style>")
            link.remove()
            changed = true
        }
        return if (changed) parsed.outerHtml() else page
    }

    /** What a laid-out document is answered as: a key in a session, its bytes outside one. */
    private fun documentAnswer(made: PdfWriter.Written, asked: JsonNode, sessionId: Long?): String {
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
            scratch.put(
                sessionId, named, mapper.writeValueAsString(Base64.getEncoder().encodeToString(document.pdf)),
                io.mszymanski.orknux.workflow.script.StoredKind("application/pdf", true),
            )
            answer[KEY] = named
            answer["note"] = "Pass $KEY to whatever uploads or saves a file. The bytes are not text."
        }
        if (document.problems.isNotEmpty()) answer["problems"] = document.problems
        return mapper.writeValueAsString(answer)
    }

    /**
     * A page as a picture. Issue #424.
     *
     * Two steps that were already here: lay the HTML out as a document, then
     * draw its first page. Written as one call because a model that has to
     * discover the pairing will not - the issue was filed asking for exactly
     * this and parked, with a note saying the server had no HTML engine and
     * the only route was those two steps by hand. openhtmltopdf is the engine
     * now, so the note is out of date and the wrapper is the whole of the work.
     *
     * The picture comes back rather than a key, the way `preview` does and for
     * the same reason: somebody asked to *see* it. A document to keep is what
     * `fromHtml` is for.
     */
    private fun toPng(asked: JsonNode, sessionId: Long?): String {
        val page = pageFrom(asked, sessionId)
            ?: return refusal(
                "Give the page: $HTML written out, $PAD naming a scratchpad in this session, or $KEY for " +
                    "one something handed you.",
            )

        val made = writer.fromHtml(page, text(asked, TITLE)) { named -> picture(named, sessionId) }
        if (made is PdfWriter.Written.Refused) return refusal(made.reason)
        val document = made as PdfWriter.Written.Made

        return when (val drawn = renderer.png(document.pdf, 1, number(asked, WIDTH))) {
            is PdfRenderer.Drawing.Refused -> refusal(drawn.reason)
            is PdfRenderer.Drawing.Drawn -> pictureAnswer(
                "page",
                drawn.png,
                drawn.width,
                drawn.height,
                sessionId,
                forModel = !shown(asked),
                also = linkedMapOf(
                    /*
                     * How many pages it came to, because a page that ran to
                     * three and was drawn as one is a picture missing two
                     * thirds of what was asked for - and nothing else would
                     * say so.
                     */
                    "pages" to drawn.pages,
                    "problems" to document.problems.takeIf { it.isNotEmpty() },
                ),
            )
        }
    }

    private fun read(asked: JsonNode, sessionId: Long?): String {
        val pdf = sourceFor(text(asked, KEY), sessionId) ?: return refusal(keyRefusal(text(asked, KEY), sessionId))
        return when (val said = renderer.html(pdf, number(asked, FROM), number(asked, TO))) {
            is PdfRenderer.Reading.Refused -> refusal(
                // Too much text at once: the pages worth reading are the ones a search names.
                said.reason + if (said.tooLong) ", or find the pages that matter with $SEARCH_TOOL" else "",
            )
            is PdfRenderer.Reading.Read -> mapper.writeValueAsString(
                linkedMapOf(
                    "html" to said.html,
                    "pages" to said.pages,
                    FROM to said.from,
                    TO to said.to,
                    "characters" to said.characters,
                ).apply {
                    // A scan reads as nothing; say so rather than answer empty pages as if they were blank paper.
                    if (said.textless) put("note", "$NO_TEXT_LAYER Look at a page with $PREVIEW_TOOL.")
                },
            )
        }
    }

    /**
     * Finding things in a long document without reading it into the turn.
     * The text is [PdfText]'s, kept per session so the next search is free.
     */
    private fun search(asked: JsonNode, sessionId: Long?): String {
        val query = text(asked, QUERY)?.takeIf { it.isNotBlank() } ?: return refusal("Give the $QUERY to find.")
        val pdf = sourceFor(text(asked, KEY), sessionId) ?: return refusal(keyRefusal(text(asked, KEY), sessionId))
        val pages = when (val read = texts.pages(pdf, sessionId)) {
            is PdfText.Pages.Refused -> return refusal(read.reason)
            is PdfText.Pages.Read -> read
        }
        if (pages.textless) {
            return mapper.writeValueAsString(
                linkedMapOf(
                    "count" to 0,
                    "pages" to pages.pages.size,
                    "textLayer" to false,
                    "note" to "$NO_TEXT_LAYER There is nothing to search. Look at a page with $PREVIEW_TOOL.",
                ),
            )
        }
        val most = tuning[io.mszymanski.orknux.server.tuning.Tunable.PDF_MATCHES_MOST]
        val limit = (number(asked, LIMIT) ?: most).coerceIn(1, most)
        val found = texts.search(
            pages.pages, query,
            regex = flag(asked, REGEX), wholeWord = flag(asked, WHOLE_WORD), caseSensitive = flag(asked, CASE_SENSITIVE),
            most = limit,
        ).getOrElse { return refusal(it.message ?: "that could not be searched") }
        val blank = pages.pages.count { it.isBlank() }
        return mapper.writeValueAsString(
            linkedMapOf<String, Any?>(
                "count" to found.total,
                "pages" to pages.pages.size,
                "onPages" to found.perPage.map { (page, count) -> listOf(page, count) },
                "matches" to found.listed.map { linkedMapOf("page" to it.page, "text" to it.snippet) },
            ).apply {
                if (found.total > found.listed.size) put("more", "${found.total - found.listed.size} more matches not listed; onPages counts them all")
                if (blank > 0) put("pagesWithoutText", blank)
                if (found.total > 0) put("next", "read a page with $READ_TOOL's $FROM and $TO")
            },
        )
    }

    private fun flag(node: JsonNode, name: String): Boolean = node.path(name).let { said ->
        (said.isBoolean && said.booleanValue()) || (said.isTextual && said.stringValue().trim().equals("true", ignoreCase = true))
    }

    private fun preview(asked: JsonNode, sessionId: Long?): String {
        val pdf = sourceFor(text(asked, KEY), sessionId) ?: return refusal(keyRefusal(text(asked, KEY), sessionId))
        return when (val drawn = renderer.png(pdf, number(asked, PAGE) ?: 1, number(asked, WIDTH))) {
            is PdfRenderer.Drawing.Refused -> refusal(drawn.reason)
            is PdfRenderer.Drawing.Drawn ->
                pictureAnswer("preview", drawn.png, drawn.width, drawn.height, sessionId, forModel = true, also = mapOf("pages" to drawn.pages))
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
     * A picture, answered so it can be both seen and sent. Issue #513.
     *
     * The bytes and a key, deliberately both. The round lifts the bytes out to
     * show the model and then takes them back out of the answer - base64 is the
     * largest thing that can land in a context window and no model can read it -
     * so without a key a picture could be seen and not used.
     *
     * That happened: an answer came back `picture: ""` with `pictureBytes:
     * 9051`, which reads as a drawing that exists somewhere out of reach, and
     * the agent called the tool again looking for the key, reasoned about where
     * one might be hiding, and gave up having drawn the thing correctly three
     * times.
     *
     * No session means nowhere to keep it, so the bytes travel alone - and
     * there is no model there to spend a context window on.
     */
    private fun pictureAnswer(
        named: String,
        png: ByteArray,
        width: Int,
        height: Int,
        sessionId: Long?,
        forModel: Boolean,
        also: Map<String, Any?> = emptyMap(),
    ): String {
        val base64 = Base64.getEncoder().encodeToString(png)
        val answer = linkedMapOf<String, Any?>(
            AgentTools.PICTURE to base64,
            AgentTools.PICTURE_TYPE to "image/png",
            "width" to width,
            "height" to height,
        )
        // A page drawn so the model can look at it is not posted to the person; see AgentTools.PICTURE_FOR_MODEL.
        if (forModel) answer[AgentTools.PICTURE_FOR_MODEL] = true
        answer.putAll(also)
        if (sessionId != null) {
            val key = named + "." + java.lang.Long.toString(System.nanoTime(), 36)
            scratch.put(sessionId, key, mapper.writeValueAsString(base64), io.mszymanski.orknux.workflow.script.StoredKind("image/png", true))
            answer[KEY] = key
            answer["note"] = "Pass $KEY to whatever sends, uploads or saves a file."
        }
        return mapper.writeValueAsString(answer)
    }

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
    private fun picture(named: String, sessionId: Long?): PageBlocks.Picture? = pictures.find(named, sessionId)

    /**
     * The document a key holds, which is how a document reaches the next call.
     * Opened where it lies: an attachment is read from the attachment store in
     * place, so a page range of a long one is that range and not the file held
     * in memory several times over.
     */
    private fun sourceFor(named: String?, sessionId: Long?): PdfSource? {
        val key = named?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (sessionId == null) return null
        pads.find(sessionId, key)?.takeIf { it.contentType != null }?.let { pad ->
            return runCatching { Base64.getDecoder().decode(pad.content) }.getOrNull()?.let(PdfSource::of)
        }
        val held = scratch.content(sessionId, key) ?: return null
        val identity = held.identity
        return when {
            held.path != null && identity != null -> PdfSource.of(held.path, held.size, identity)
            identity != null -> PdfSource.of(held.size, identity, held::open)
            else -> PdfSource.of(held.bytes())
        }
    }

    /** The bytes a key holds, for a reader that needs them all - a zip. */
    private fun bytesFor(named: String?, sessionId: Long?): ByteArray? {
        val key = named?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (sessionId == null) return null
        pads.find(sessionId, key)?.takeIf { it.contentType != null }?.let { pad ->
            return runCatching { Base64.getDecoder().decode(pad.content) }.getOrNull()
        }
        return scratch.content(sessionId, key)?.bytes()
    }

    private fun keyRefusal(named: String?, sessionId: Long?): String =
        if (named.isNullOrBlank()) {
            "Give the $KEY of a document - the one fromHtml answered, or the one an attachment reader gave you."
        } else {
            scratch.nothingUnder(sessionId, named)
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

    /** Whether the person asked to see it: `show` true, sent as a boolean or as its text. */
    private fun shown(asked: JsonNode): Boolean = asked.path(SHOW).let { said ->
        (said.isBoolean && said.booleanValue()) || (said.isTextual && said.stringValue().trim().equals("true", ignoreCase = true))
    }

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

    companion object {
        const val CAPABILITY = "pdf"
        const val SEARCH = "search"

        /** What a model calls to find words in a PDF, as the tool list names it. */
        const val SEARCH_TOOL = CAPABILITY + "_" + SEARCH

        /** What a model calls to read a PDF's text, as the tool list names it. */
        const val READ_TOOL = CAPABILITY + "_read"

        /** What a model calls to look at a page, as the tool list names it. */
        const val PREVIEW_TOOL = CAPABILITY + "_preview"

        const val QUERY = "query"
        const val REGEX = "regex"
        const val WHOLE_WORD = "whole_word"
        const val CASE_SENSITIVE = "case_sensitive"
        const val LIMIT = "limit"

        const val NO_TEXT_LAYER = "This PDF has no text layer: its pages are pictures, as a scan's are, so no text can be read from them."

        const val FROM_HTML = "fromHtml"
        const val FROM_HTML_ZIP = "fromHtmlZip"
        const val FILE = "file"
        const val READ = "read"
        const val PREVIEW = "preview"
        const val TO_PNG = "toPng"

        const val HTML = "html"
        const val PAD = "scratchpad"
        const val KEY = "contentKey"
        const val TITLE = "title"
        const val BASE64 = "base64"
        const val FROM = "from"
        const val TO = "to"
        const val PAGE = "page"
        const val WIDTH = "width"
        const val SHOW = "show"
    }
}
