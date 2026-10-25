package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.design.DesignDocument
import io.mszymanski.orknux.server.design.DesignDocumentRepository
import io.mszymanski.orknux.server.design.DesignImageAPI
import io.mszymanski.orknux.server.design.DocumentAPI
import io.mszymanski.orknux.server.design.DocumentCatalogRepository
import io.mszymanski.orknux.server.design.DocumentContentInvalidException
import io.mszymanski.orknux.server.design.DocumentMarkdown
import io.mszymanski.orknux.server.design.DocumentDoor
import io.mszymanski.orknux.server.design.DocumentPageSize
import io.mszymanski.orknux.server.design.ImportDesignImageInput
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.plugin.SvgRenderer
import io.mszymanski.orknux.server.security.InstallationAddress
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.Base64

/**
 * The orknux_* tools that read, write, search and export documents.
 *
 * Offered wherever the rest of the surface is - an agent with Orknux access, an
 * MCP client, and the chat in the document editor, which offers the writing
 * ones for the document it has open. Every write goes through [DocumentAPI],
 * so what a model writes is checked, held to the Tunable and audited as the
 * editor's own save is, and the editor shows it the moment it is taken up.
 *
 * A model reads and writes Markdown rather than the editor's JSON - see
 * [DocumentMarkdown] - and addresses a place in a document by the number of a
 * top-level block, which [READ] lists with the first words of each.
 */
@Service
class DocumentTools(
    private val documents: DesignDocumentRepository,
    private val catalogs: DocumentCatalogRepository,
    /** Lazy: the API holds the editor's chat, which reaches these tools through OrknuxTools. */
    @param:Lazy private val api: DocumentAPI,
    /** Lazy: a picture handed over by key is kept as a Design picture, through Design's own import. */
    @param:Lazy private val images: DesignImageAPI,
    @param:Lazy private val artifacts: WorkspaceArtifactAPI,
    /** Lazy: the exports read pictures and lay out PDFs, and are only needed when asked for. */
    @param:Lazy private val exports: io.mszymanski.orknux.server.design.DocumentExports,
    private val web: WebProperties,
    private val address: InstallationAddress,
    private val mapper: ObjectMapper,
    /** Where a picture handed over by key is read from, and where an export is put for a model to send on. */
    private val scratch: LlmSessionStore,
    /** For a picture handed over as SVG, which a page takes as pixels. */
    private val svgs: SvgRenderer,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val markdown = DocumentMarkdown(mapper)

    fun specs(scope: OrknuxScope): List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = LIST,
                description = "The documents in this workspace - written pages, filed in catalogs - the one worked on last first: " +
                    "id, name, catalog, how many words, and where each opens. Narrow it to one catalog by name.",
                parameters = listOf(ToolParameterSpec(CATALOG, "Only this catalog's documents, by name.", required = false)),
                summary = "Lists the workspace's documents",
            ),
        )
        add(
            ToolSpec(
                name = READ,
                description = "One document: its outline - every top-level block numbered from 0 with its first words, which is " +
                    "how $INSERT and $WRITE name a place - and the whole of it as Markdown. $MARKDOWN_SAID " +
                    "Give as json to have the editor's own ProseMirror JSON as well.",
                parameters = listOf(
                    ToolParameterSpec(DOCUMENT, "The document's name, or its id.", required = true),
                    ToolParameterSpec(AS, "markdown, the default, or json.", required = false),
                ),
                summary = "Reads one document as Markdown",
            ),
        )
        add(
            ToolSpec(
                name = SEARCH,
                description = "Finds documents holding some words, in their name or their text, with the words around where " +
                    "each was found; names that match first.",
                parameters = listOf(ToolParameterSpec(TEXT, "What to look for.", required = true)),
                summary = "Searches the workspace's documents",
            ),
        )
        add(
            ToolSpec(
                name = FIND,
                description = "Finds words in one document: each place they are, with the top-level block it is in - as $READ " +
                    "numbers them, for $WRITE and $INSERT - the page, counted by the document's page breaks, and the words around it.",
                parameters = listOf(
                    ToolParameterSpec(DOCUMENT, "The document's name, or its id.", required = true),
                    ToolParameterSpec(TEXT, "What to look for.", required = true),
                    ToolParameterSpec(MATCH_CASE, "true to tell capitals from small letters; false by default.", required = false),
                    ToolParameterSpec(WHOLE_WORD, "true to find it only as a whole word; false by default.", required = false),
                ),
                summary = "Finds words in one document",
            ),
        )
        add(
            ToolSpec(
                name = EXPORT,
                description = "Writes a document out as a file: pdf (its pages, pictures and page breaks laid out), md (Markdown; " +
                    "a zip with its pictures beside it where it has any), odt (an OpenDocument text a word processor opens) or ods " +
                    "(an OpenDocument spreadsheet holding each table as a sheet). Answers a link to download it, and in a " +
                    "conversation a contentKey holding the file, for whatever sends or saves one.",
                parameters = listOf(
                    ToolParameterSpec(DOCUMENT, "The document's name, or its id.", required = true),
                    ToolParameterSpec(FORMAT, "pdf, md, odt or ods.", required = true),
                ),
                summary = "Exports a document as PDF, Markdown, ODT or ODS",
            ),
        )
        if (!scope.mayWrite) return@buildList
        add(
            ToolSpec(
                name = CREATE,
                description = "Writes a new document in this workspace - a report, a brief, notes, a letter - which a person can " +
                    "open and go on writing in the document editor, in pages. Give it its words as Markdown to write it at once. " +
                    "$MARKDOWN_SAID $ANSWERED",
                parameters = listOf(
                    ToolParameterSpec("name", "What it is called.", required = true),
                    ToolParameterSpec(MARKDOWN, "Its content, as Markdown.", required = false),
                    ToolParameterSpec(CATALOG, "The catalog to file it in, by name; made if there is none of that name. The workspace's first when left out.", required = false),
                    ToolParameterSpec(PAGE_SIZE, "A3, A4, A5, LETTER or LEGAL; the workspace's own page for new documents when left out.", required = false),
                ),
                summary = "Writes a new document",
            ),
        )
        add(
            ToolSpec(
                name = WRITE,
                description = "Replaces a document's content with Markdown: the whole of it, or only the top-level blocks from " +
                    "$FROM to $TO as $READ numbers them. Read it first. $MARKDOWN_SAID $ANSWERED",
                parameters = listOf(
                    ToolParameterSpec(DOCUMENT, "The document's name, or its id.", required = true),
                    ToolParameterSpec(MARKDOWN, "What goes there, as Markdown.", required = true),
                    ToolParameterSpec(FROM, "The first block replaced, from 0; with $TO, only those blocks are.", required = false),
                    ToolParameterSpec(TO, "The last block replaced; $FROM when left out.", required = false),
                ),
                summary = "Rewrites a document, or part of it",
            ),
        )
        add(
            ToolSpec(
                name = INSERT,
                description = "Puts Markdown into a document before the top-level block numbered $AT, as $READ numbers them, " +
                    "or at its end. Everything already there stays. $MARKDOWN_SAID $ANSWERED",
                parameters = listOf(
                    ToolParameterSpec(DOCUMENT, "The document's name, or its id.", required = true),
                    ToolParameterSpec(MARKDOWN, "What to put in, as Markdown.", required = true),
                    ToolParameterSpec(AT, "The block it goes before, from 0; the end when left out.", required = false),
                ),
                summary = "Inserts into a document",
            ),
        )
        add(
            ToolSpec(
                name = APPEND,
                description = "Adds a section at the end of a document: a heading, and the Markdown under it. $ANSWERED",
                parameters = listOf(
                    ToolParameterSpec(DOCUMENT, "The document's name, or its id.", required = true),
                    ToolParameterSpec(HEADING, "The section's heading.", required = true),
                    ToolParameterSpec(MARKDOWN, "What the section says, as Markdown.", required = false),
                    ToolParameterSpec(LEVEL, "The heading's level, 1 to 6; 2 when left out.", required = false),
                ),
                summary = "Adds a section to a document",
            ),
        )
        add(
            ToolSpec(
                name = ADD_IMAGE,
                description = "Puts a picture in a document, as a block of its own with an optional caption: a picture this " +
                    "conversation holds under a key - one a tool drew, or a file - or one already on the Artifacts page, by its " +
                    "artifact id. A picture handed over by key is kept as a Design picture first. $ANSWERED",
                parameters = listOf(
                    ToolParameterSpec(DOCUMENT, "The document's name, or its id.", required = true),
                    ToolParameterSpec(KEY, "The key a picture is held under in this conversation.", required = false),
                    ToolParameterSpec(ARTIFACT, "Or the artifact id of a picture on the Artifacts page, such as DESIGN-12.", required = false),
                    ToolParameterSpec(CAPTION, "What is written under it.", required = false),
                    ToolParameterSpec(ALT, "What it shows, for a reader that cannot see it.", required = false),
                    ToolParameterSpec(WIDTH, "How wide it is drawn, in percent of the page's text, 5 to 100.", required = false),
                    ToolParameterSpec(AT, "The block it goes before, from 0; the end when left out.", required = false),
                ),
                summary = "Puts a picture in a document",
            ),
        )
    }

    fun handles(name: String): Boolean = name in NAMES

    fun run(scope: OrknuxScope, name: String, arguments: String): String {
        val asked = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return refuse("The arguments are not JSON")
        return try {
            when (name) {
                LIST -> list(scope, asked)
                READ -> withDocument(scope, asked) { read(it, text(asked.path(AS)) == "json") }
                SEARCH -> search(scope, asked)
                FIND -> withDocument(scope, asked) { find(it, asked) }
                EXPORT -> withDocument(scope, asked) { export(scope, it, asked) }
                else -> if (!scope.mayWrite) refuse("This conversation may read what is here, but not change it") else when (name) {
                    CREATE -> create(scope, asked)
                    WRITE -> withDocument(scope, asked) { write(scope, it, asked) }
                    INSERT -> withDocument(scope, asked) { insert(scope, it, asked) }
                    APPEND -> withDocument(scope, asked) { append(scope, it, asked) }
                    ADD_IMAGE -> withDocument(scope, asked) { addImage(scope, it, asked) }
                    else -> refuse("There is no tool called $name")
                }
            }
        } catch (refused: DocumentContentInvalidException) {
            refuse(refused.message ?: "That cannot be written")
        }
    }

    // -- Reading -------------------------------------------------------------

    private fun list(scope: OrknuxScope, asked: JsonNode): String {
        val catalog = text(asked.path(CATALOG))?.let { named ->
            catalogs.findByWorkspaceIdOrderByNameAsc(scope.workspaceId).firstOrNull { it.name.equals(named, ignoreCase = true) }
                ?: return refuse("There is no document catalog called $named here")
        }
        val held = documents.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(scope.workspaceId).filter { catalog == null || it.catalogId == catalog.id }
        return mapper.writeValueAsString(
            mapOf(
                "documents" to held.map { document ->
                    val view = api.view(document)
                    mapOf(
                        "id" to view.id, "name" to view.name, "catalog" to view.catalogName, "words" to view.words,
                        "updatedAt" to view.updatedAt, "url" to link(document),
                    )
                },
            ),
        )
    }

    private fun read(document: DesignDocument, json: Boolean): String {
        val tree = api.tree(document)
        val written = markdown.write(tree)
        val most = tuning[Tunable.DOCUMENT_MARKDOWN_INLINE_CHARACTERS]
        return mapper.writeValueAsString(
            buildMap {
                put("id", document.id)
                put("name", document.name)
                put("catalog", api.view(document).catalogName)
                put("revision", document.revision)
                put("pageSize", document.pageSize)
                put("url", link(document))
                put("view", link(document) + "/view")
                put("blocks", outline(tree))
                if (written.length <= most) {
                    put("markdown", written)
                } else {
                    put("markdown", written.take(most))
                    put("note", "The document is ${written.length} characters of Markdown, past the $most this installation hands to a model; read the outline and write into the blocks you need.")
                }
                if (json) put("content", tree)
            },
        )
    }

    private fun search(scope: OrknuxScope, asked: JsonNode): String {
        val needle = text(asked.path(TEXT)) ?: return refuse("What should be looked for? Give $TEXT.")
        val hits = api.search(scope.workspaceId, needle, null)
        return mapper.writeValueAsString(
            mapOf(
                "documents" to hits.map { hit ->
                    mapOf(
                        "id" to hit.document.id, "name" to hit.document.name, "catalog" to hit.document.catalogName,
                        "snippet" to hit.snippet, "url" to base() + "/workspace/${hit.document.workspaceId}/design/documents/${hit.document.id}",
                    )
                },
            ),
        )
    }

    /** Every place the words are in one document, in order, with where each is. */
    private fun find(document: DesignDocument, asked: JsonNode): String {
        val needle = text(asked.path(TEXT)) ?: return refuse("What should be looked for? Give $TEXT.")
        val matchCase = asked.path(MATCH_CASE).asBoolean(false)
        val whole = asked.path(WHOLE_WORD).asBoolean(false)
        val pattern = Regex(
            (if (whole) "(?<![\\p{L}\\p{N}_])" else "") + Regex.escape(needle) + (if (whole) "(?![\\p{L}\\p{N}_])" else ""),
            if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE),
        )
        val blocks = api.tree(document).path("content")
        var page = 1
        val found = mutableListOf<Map<String, Any?>>()
        for (at in 0 until blocks.size()) {
            val block = blocks.get(at)
            if (block.path("type").asText("") == "pageBreak") {
                page++
                continue
            }
            val words = api.content.plainText(block).replace('\n', ' ')
            pattern.findAll(words).forEach { match ->
                val from = (match.range.first - CONTEXT).coerceAtLeast(0)
                val to = (match.range.last + 1 + CONTEXT).coerceAtMost(words.length)
                found += mapOf(
                    "block" to at,
                    "page" to page,
                    "offset" to match.range.first,
                    "context" to (if (from > 0) "…" else "") + words.substring(from, to) + (if (to < words.length) "…" else ""),
                )
            }
        }
        return mapper.writeValueAsString(
            mapOf("id" to document.id, "name" to document.name, "count" to found.size, "matches" to found, "url" to link(document)),
        )
    }

    /**
     * The file, where to download it, and - in a conversation - the file
     * itself under a key, so a tool that sends or saves files can take it.
     */
    private fun export(scope: OrknuxScope, document: DesignDocument, asked: JsonNode): String {
        val format = text(asked.path(FORMAT))?.lowercase()?.removePrefix(".")?.let { if (it == "markdown") "md" else it }
            ?: return refuse("Which format? Give $FORMAT: pdf, md, odt or ods.")
        val made = exports.export(requireNotNull(document.id), format)
        return mapper.writeValueAsString(
            buildMap {
                put("id", document.id)
                put("name", document.name)
                put("format", format)
                put("filename", made.filename)
                put("bytes", made.bytes.size)
                put("download", base() + "/api/documents/${document.id}/export/$format")
                put("url", link(document))
                scope.session?.let { session ->
                    val key = "document.${document.id}.${java.lang.Long.toString(System.nanoTime(), 36)}.${made.extension}"
                    val refused = scratch.put(
                        session, key, mapper.writeValueAsString(Base64.getEncoder().encodeToString(made.bytes)),
                        io.mszymanski.orknux.workflow.script.StoredKind(made.contentType, true),
                    )
                    if (refused == null) put("contentKey", key) else put("note", refused)
                }
            },
        )
    }

    // -- Writing -------------------------------------------------------------

    private fun create(scope: OrknuxScope, asked: JsonNode): String {
        val name = text(asked.path("name")) ?: return refuse("What is it called? Give it a name.")
        val pageSize = text(asked.path(PAGE_SIZE))?.let { DocumentPageSize.of(it)?.name ?: return refuse("A page is A3, A4, A5, LETTER or LEGAL, not $it") }
        val catalogId = text(asked.path(CATALOG))?.let { named ->
            val found = catalogs.findByWorkspaceIdOrderByNameAsc(scope.workspaceId).firstOrNull { it.name.equals(named, ignoreCase = true) }
            found?.id ?: api.createDocumentCatalog(scope.workspaceId, named).id
        }
        val doc = text(asked.path(MARKDOWN))?.let { markdown.document(it) } ?: api.content.empty()
        val made = api.create(
            scope.workspaceId, catalogId, name, api.content.checked(doc, scope.workspaceId), pageSize,
            door = if (scope.documentTurn == null) DocumentDoor.TOOL else DocumentDoor.CHAT,
            turn = scope.documentTurn,
        )
        return written(api.find(made.id))
    }

    private fun write(scope: OrknuxScope, document: DesignDocument, asked: JsonNode): String {
        val text = text(asked.path(MARKDOWN)) ?: return refuse("Give the $MARKDOWN to write.")
        val from = int(asked.path(FROM))
        val tree = if (from == null) {
            markdown.document(text)
        } else {
            val blocks = blocksOf(document)
            val to = int(asked.path(TO)) ?: from
            if (from < 0 || to < from || to >= blocks.size) {
                return refuse("${document.name} has blocks 0 to ${blocks.size - 1}; $FROM and $TO have to be among them, $FROM first")
            }
            withBlocks(blocks.subList(0, from) + markdown.read(text) + blocks.subList(to + 1, blocks.size))
        }
        return saved(scope, document, tree)
    }

    private fun insert(scope: OrknuxScope, document: DesignDocument, asked: JsonNode): String {
        val text = text(asked.path(MARKDOWN)) ?: return refuse("Give the $MARKDOWN to put in.")
        val blocks = blocksOf(document)
        val at = int(asked.path(AT)) ?: blocks.size
        if (at < 0 || at > blocks.size) return refuse("${document.name} has blocks 0 to ${blocks.size - 1}; $AT is one of them, or ${blocks.size} for the end")
        return saved(scope, document, withBlocks(blocks.subList(0, at) + markdown.read(text) + blocks.subList(at, blocks.size)))
    }

    private fun append(scope: OrknuxScope, document: DesignDocument, asked: JsonNode): String {
        val heading = text(asked.path(HEADING)) ?: return refuse("Give the section a $HEADING.")
        val level = (int(asked.path(LEVEL)) ?: 2).coerceIn(1, 6)
        val section = markdown.read("#".repeat(level) + " " + heading.replace("\n", " ") + "\n\n" + (text(asked.path(MARKDOWN)) ?: ""))
        return saved(scope, document, withBlocks(blocksOf(document).dropLastWhile(::emptyParagraph) + section))
    }

    private fun addImage(scope: OrknuxScope, document: DesignDocument, asked: JsonNode): String {
        val key = text(asked.path(KEY))
        val given = text(asked.path(ARTIFACT))?.let { DocumentMarkdown.artifactOf(it) ?: return refuse("$it is not an artifact id, such as DESIGN-12") }
        val caption = text(asked.path(CAPTION))
        val artifact = when {
            given != null -> {
                val held = artifacts.storedOf(scope.workspaceId, given) ?: return refuse("There is no artifact $given in this workspace")
                if (!held.contentType.startsWith("image/")) return refuse("$given is ${held.contentType}, not a picture")
                given
            }
            key != null -> when (val kept = kept(scope, key, caption ?: document.name)) {
                is Kept.Picture -> kept.artifact
                is Kept.Refused -> return refuse(kept.why)
            }
            else -> return refuse("Give the $KEY a picture is held under, or the $ARTIFACT id of one.")
        }
        val picture = mapper.createObjectNode().put("type", "image")
        val attrs = picture.putObject("attrs").put("artifact", artifact)
        caption?.let { attrs.put("caption", it) }
        text(asked.path(ALT))?.let { attrs.put("alt", it) }
        int(asked.path(WIDTH))?.let { attrs.put("width", it.coerceIn(5, 100)) }
        val blocks = blocksOf(document)
        val at = int(asked.path(AT)) ?: blocks.size
        if (at < 0 || at > blocks.size) return refuse("${document.name} has blocks 0 to ${blocks.size - 1}; $AT is one of them, or ${blocks.size} for the end")
        return saved(scope, document, withBlocks(blocks.subList(0, at) + listOf(picture) + blocks.subList(at, blocks.size)))
    }

    /** A picture handed over by key, kept; or why it could not be. */
    private sealed interface Kept {
        data class Picture(val artifact: String) : Kept

        data class Refused(val why: String) : Kept
    }

    /** A picture this conversation holds under [key], kept as a Design picture through Design's own import. */
    private fun kept(scope: OrknuxScope, key: String, name: String): Kept {
        val session = scope.session
            ?: return Kept.Refused("There is no conversation here holding pictures under keys; give the $ARTIFACT id of one on the Artifacts page")
        val stored = scratch.get(session, key) ?: return Kept.Refused("Nothing is held under $key in this conversation")
        val kind = scratch.kindOf(session, key)
        val value = runCatching { mapper.readTree(stored) }.getOrNull()?.takeIf { it.isString }?.stringValue() ?: stored
        var bytes = (if (kind?.binary == false) value.toByteArray(Charsets.UTF_8) else runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull())
            ?: return Kept.Refused("What is held under $key is not a picture")
        // A drawing in SVG is laid out as pixels, which a page and a PDF both take.
        val head = String(bytes.copyOf(minOf(bytes.size, 256)), Charsets.UTF_8).trimStart()
        if (kind?.contentType == "image/svg+xml" || head.startsWith("<svg") || head.startsWith("<?xml")) {
            when (val drawn = svgs.png(String(bytes, Charsets.UTF_8), tuning[Tunable.DOCUMENT_SVG_PIXELS])) {
                is SvgRenderer.Drawing.Drawn -> bytes = drawn.png
                is SvgRenderer.Drawing.Refused -> return Kept.Refused("The SVG under $key could not be drawn")
            }
        }
        return try {
            Kept.Picture(
                images.importDesignImage(
                    ImportDesignImageInput(workspaceId = scope.workspaceId.toString(), image = Base64.getEncoder().encodeToString(bytes), name = name.take(120)),
                ).id,
            )
        } catch (refused: Exception) {
            Kept.Refused(refused.message ?: "The picture under $key could not be kept")
        }
    }

    /** Saved through the document's one door, saying which turn of the document chat it is, where it is one. */
    private fun saved(scope: OrknuxScope, document: DesignDocument, doc: ObjectNode): String {
        api.saveContent(requireNotNull(document.id), doc, scope.documentTurn)
        return written(api.find(requireNotNull(document.id)))
    }

    /** What a writing tool answers: where the document opens, and its outline as it now stands. */
    private fun written(document: DesignDocument): String = mapper.writeValueAsString(
        mapOf(
            "id" to document.id,
            "name" to document.name,
            "revision" to document.revision,
            "url" to link(document),
            "blocks" to outline(api.tree(document)),
            "note" to "Saved. Give the person the url, which opens it in the document editor.",
        ),
    )

    // -- Helpers -------------------------------------------------------------

    private fun outline(tree: JsonNode): List<Map<String, Any?>> {
        val written = markdown.blocksOf(tree)
        return written.mapIndexed { at, block ->
            val type = tree.path("content").get(at).path("type").asText("")
            mapOf("at" to at, "type" to type, "text" to block.replace(Regex("\\s+"), " ").trim().take(OUTLINE_WORDS))
        }
    }

    private fun blocksOf(document: DesignDocument): List<ObjectNode> {
        val content = api.tree(document).path("content")
        return (0 until content.size()).map { content.get(it) as ObjectNode }
    }

    private fun withBlocks(blocks: List<ObjectNode>): ObjectNode {
        val doc = mapper.createObjectNode().put("type", "doc")
        val list = doc.putArray("content")
        blocks.forEach { list.add(it) }
        return doc
    }

    private fun emptyParagraph(block: ObjectNode): Boolean = block.path("type").asText() == "paragraph" && block.path("content").isEmpty

    private fun withDocument(scope: OrknuxScope, asked: JsonNode, then: (DesignDocument) -> String): String {
        val named = text(asked.path(DOCUMENT)) ?: return refuse("Which document? Give its name or id; $LIST lists them.")
        val matches = documents.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(scope.workspaceId)
            .filter { it.name.equals(named, ignoreCase = true) || it.id?.toString() == named }
        return when {
            matches.isEmpty() -> refuse("There is no document called $named here; $LIST lists them")
            matches.size > 1 && matches.none { it.id?.toString() == named } -> refuse("More than one document is called $named; use its id")
            else -> then(matches.firstOrNull { it.id?.toString() == named } ?: matches.first())
        }
    }

    private fun text(node: JsonNode): String? = when {
        node.isString -> node.stringValue()?.trim()?.takeIf { it.isNotEmpty() }
        node.isNumber -> node.asText()
        else -> null
    }

    private fun int(node: JsonNode): Int? = when {
        node.isNumber -> node.asInt()
        node.isString -> node.stringValue()?.trim()?.let { if (it.equals("end", ignoreCase = true)) null else it.toIntOrNull() }
        else -> null
    }

    private fun link(document: DesignDocument): String = base() + "/workspace/${document.workspaceId}/design/documents/${document.id}"

    private fun base(): String = address.base().ifEmpty { web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty() }

    private fun refuse(says: String): String = mapper.writeValueAsString(mapOf("error" to says))

    companion object {
        const val LIST = "orknux_documents"
        const val READ = "orknux_document"
        const val SEARCH = "orknux_search_documents"
        const val EXPORT = "orknux_document_export"
        const val CREATE = "orknux_create_document"
        const val WRITE = "orknux_write_document"
        const val INSERT = "orknux_insert_into_document"
        const val APPEND = "orknux_append_document_section"
        const val ADD_IMAGE = "orknux_add_document_image"

        const val FIND = "orknux_find_in_document"

        /** What the editor's own chat is offered: reading and writing in the document it has open. */
        val EDITING = setOf(READ, FIND, WRITE, INSERT, APPEND, ADD_IMAGE)

        /** What it is offered when it is asked about the document rather than to write in it: Explain, Summarise. */
        val READING = setOf(READ, FIND)

        val NAMES = setOf(LIST, READ, SEARCH, FIND, EXPORT, CREATE, WRITE, INSERT, APPEND, ADD_IMAGE)

        private const val MATCH_CASE = "matchCase"
        private const val WHOLE_WORD = "wholeWord"

        /** How many characters either side of a match it is shown with. A format: the words around a place. */
        private const val CONTEXT = 40

        private const val DOCUMENT = "document"
        private const val CATALOG = "catalog"
        private const val MARKDOWN = "markdown"
        private const val AS = "as"
        private const val TEXT = "text"
        private const val FORMAT = "format"
        private const val PAGE_SIZE = "pageSize"
        private const val FROM = "from"
        private const val TO = "to"
        private const val AT = "at"
        private const val HEADING = "heading"
        private const val LEVEL = "level"
        private const val KEY = "key"
        private const val ARTIFACT = "artifact"
        private const val CAPTION = "caption"
        private const val ALT = "alt"
        private const val WIDTH = "width"

        /** How many characters of each block the outline shows. A format, not a behaviour: the outline is a list of places. */
        private const val OUTLINE_WORDS = 80

        private const val MARKDOWN_SAID =
            "Markdown as usual - headings, lists, tables, **bold**, *italic*, ~~struck~~, <u>underlined</u>, `code`, links " +
                "and fenced code; a line holding only <!-- pagebreak --> starts a new page; a picture already in the document is " +
                "![alt](artifact:DESIGN-12 \"caption\"), and a new one goes in through $ADD_IMAGE."

        private const val ANSWERED =
            "Answers the document's url - where it opens in the editor, to give the person - and its outline as it now stands."
    }
}
