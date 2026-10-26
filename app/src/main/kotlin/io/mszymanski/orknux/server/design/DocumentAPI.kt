package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.attachment.EditorChatFiles
import io.mszymanski.orknux.server.attachment.EditorChatTurn
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/**
 * A workspace's documents and their catalogs, and the one door every change to
 * them comes through.
 *
 * Anybody who can see the workspace may write in it, as anybody may draw a
 * wireframe there. The orknux_* document tools save through this same class
 * ([io.mszymanski.orknux.server.mcp.DocumentTools]), so what a model writes is
 * checked by the same [DocumentContent], held to the same Tunable and written
 * in the audit log in the same words, under the name of whoever asked.
 *
 * **Versioned through ComponentRevisionRecorder**, as a function or an agent
 * is: every write of its content - the editor's Save, an import saved there,
 * the document chat, the orknux_* tools, a restore - goes through [write],
 * which hands the recorder the state it displaces and the door that state was
 * saved through. Nothing runs an older version of a document, but people want
 * to see what changed and go back, and the many doors are exactly what that
 * recorder is for. A document is saved far more often than a function, so its
 * history is kept by count (`Tunable.DOCUMENT_VERSIONS_KEPT`) rather than by
 * days, and the saves one turn of the document chat makes keep one version:
 * the state before the turn. [DesignDocument.revision] still refuses a save
 * over a change somebody else made.
 */
@Controller
class DocumentAPI(
    private val documents: DesignDocumentRepository,
    private val workspaces: io.mszymanski.orknux.server.workspace.WorkspaceRepository,
    private val catalogs: DocumentCatalogRepository,
    private val images: DesignDocumentImageRepository,
    private val artifacts: WorkspaceArtifactAPI,
    private val audit: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
    private val mapper: ObjectMapper,
    /** Lazy: the chat reaches the orknux_* tools, which save through here. */
    @param:Lazy private val chat: DocumentChat,
    /** What a turn's files say, for the model. */
    private val files: EditorChatFiles,
    /** Puts the current names on links to other documents, and checks a new one goes somewhere. */
    private val links: DocumentLinks,
    /** The inline suggestion while typing, the chat composer's own. */
    @param:Lazy private val sentences: io.mszymanski.orknux.server.chat.ChatSentences,
    private val tuning: Tuning = Tuning.DEFAULTS,
    /** Keeps what each save displaces; see the class's note. */
    private val revisions: io.mszymanski.orknux.server.revision.ComponentRevisionRecorder? = null,
    /** Who has it open, told when it is saved from any door; and the audit's once-per-visit. Issue #683. */
    private val published: org.springframework.context.ApplicationEventPublisher? = null,
    private val rooms: io.mszymanski.orknux.server.live.LiveRooms? = null,
) {

    val content = DocumentContent(mapper)

    // -- Catalogs -------------------------------------------------------------

    @QueryMapping
    @Transactional(readOnly = true)
    fun documentCatalogs(@Argument workspaceId: Long): List<DocumentCatalogView> {
        access.requireVisible(workspaceId)
        return catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map(::catalogView)
    }

    @MutationMapping
    @Transactional
    fun createDocumentCatalog(@Argument workspaceId: Long, @Argument name: String): DocumentCatalogView {
        access.requireVisible(workspaceId)
        val called = catalogName(name)
        if (catalogs.findByWorkspaceIdAndName(workspaceId, called) != null) throw DocumentCatalogNameTakenException(called)
        val made = catalogs.save(DocumentCatalog(workspaceId = workspaceId, name = called, createdBy = who()))
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document catalog ${made.name} added")
        return catalogView(made)
    }

    @MutationMapping
    @Transactional
    fun renameDocumentCatalog(@Argument id: Long, @Argument name: String): DocumentCatalogView {
        val held = catalog(id)
        val called = catalogName(name)
        if (called == held.name) return catalogView(held)
        catalogs.findByWorkspaceIdAndName(held.workspaceId, called)?.takeIf { it.id != held.id }
            ?.let { throw DocumentCatalogNameTakenException(called) }
        val was = held.name
        held.name = called
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document catalog $was renamed to $called")
        return catalogView(catalogs.save(held))
    }

    @MutationMapping
    @Transactional
    fun deleteDocumentCatalog(@Argument id: Long): Boolean {
        val held = catalog(id)
        val count = documents.countByCatalogId(id)
        if (count > 0) throw DocumentCatalogNotEmptyException(held.name, count)
        catalogs.delete(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document catalog ${held.name} removed")
        return true
    }

    // -- Documents ------------------------------------------------------------

    /**
     * A workspace's documents, the one worked on last first.
     *
     * @param catalogId only that catalog's.
     * @param search only those whose name or words hold it, ignoring case.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun documents(@Argument workspaceId: Long, @Argument catalogId: Long?, @Argument search: String?): List<DesignDocumentView> {
        access.requireVisible(workspaceId)
        val named = catalogNames(workspaceId)
        return documents.findAll(
            documentFilter(workspaceId, catalogId, search),
            Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")),
        ).map { view(it, named) }
    }

    /** One document, or null where there is none this person may see. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun document(@Argument id: Long): DesignDocumentView? {
        val held = documents.findByIdOrNull(id) ?: return null
        if (!access.canSee(held.workspaceId)) return null
        return view(held)
    }

    /**
     * Documents holding some words, each with the words around where they were
     * found: what Quick actions and orknux_search_documents show. A name that
     * matches comes before words that do; then the one worked on last.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun searchDocuments(@Argument workspaceId: Long, @Argument text: String, @Argument limit: Int?): List<DocumentHitView> {
        access.requireVisible(workspaceId)
        return search(workspaceId, text, limit)
    }

    /** The search itself, for the tool, which has checked access its own way. */
    fun search(workspaceId: Long, text: String, limit: Int?): List<DocumentHitView> {
        val needle = text.trim()
        if (needle.isEmpty()) return emptyList()
        val most = tuning[Tunable.DOCUMENT_SEARCH_RESULTS]
        val named = catalogNames(workspaceId)
        return documents.findAll(
            documentFilter(workspaceId, null, needle),
            Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")),
        )
            .sortedBy { if (it.name.contains(needle, ignoreCase = true)) 0 else 1 }
            .take((limit ?: most).coerceIn(1, most))
            .map { DocumentHitView(view(it, named), snippet(it.plainText, needle)) }
    }

    /**
     * Makes a document, empty or holding [content], in [catalogId] - or, with
     * none named, in the workspace's first catalog, which is made here the
     * first time it is needed.
     */
    @MutationMapping
    @Transactional
    fun createDocument(
        @Argument workspaceId: Long,
        @Argument catalogId: Long?,
        @Argument name: String,
        @Argument content: String?,
        @Argument pageSize: String?,
    ): DesignDocumentView {
        access.requireVisible(workspaceId)
        val doc = this.content.read(content, workspaceId)
        return create(workspaceId, catalogId, name, doc, pageSize)
    }

    /**
     * The same make, from the orknux_* tools, which hold a checked tree rather than text.
     *
     * @param turn the document chat's turn, where it is the chat that made it.
     */
    @Transactional
    fun create(
        workspaceId: Long,
        catalogId: Long?,
        name: String,
        doc: ObjectNode,
        pageSize: String?,
        door: DocumentDoor = DocumentDoor.EDITOR,
        turn: String? = null,
    ): DesignDocumentView {
        access.requireVisible(workspaceId)
        val catalog = catalogId?.let { catalog(it) }?.also { if (it.workspaceId != workspaceId) throw DocumentCatalogNotFoundException(catalogId) }
            ?: firstCatalog(workspaceId)
        val written = stored(doc)
        picturesHere(workspaceId, this.content.images(doc))
        documentsHere(workspaceId, links.linksIn(doc))
        val who = who()
        val made = documents.save(
            DesignDocument(
                workspaceId = workspaceId,
                catalogId = requireNotNull(catalog.id),
                name = cleanName(name),
                content = written,
                plainText = this.content.plainText(doc),
                pageSize = pageSize(pageSize)?.name ?: documentPageSizeDefault(workspaceId),
                createdBy = who,
                updatedBy = who,
                updatedVia = door.name,
                updatedTurn = turn,
            ),
        )
        keepImages(made, doc)
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document ${made.name} added")
        return view(made)
    }

    @MutationMapping
    @Transactional
    fun renameDocument(@Argument id: Long, @Argument name: String): DesignDocumentView {
        val held = find(id)
        val was = held.name
        val called = cleanName(name)
        if (called == was) return view(held)
        held.name = called
        touch(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document $was renamed to $called")
        return view(documents.save(held))
    }

    @MutationMapping
    @Transactional
    fun moveDocument(@Argument id: Long, @Argument catalogId: Long): DesignDocumentView {
        val held = find(id)
        val into = catalog(catalogId)
        if (into.workspaceId != held.workspaceId) throw DocumentCatalogNotFoundException(catalogId)
        if (into.id == held.catalogId) return view(held)
        held.catalogId = catalogId
        touch(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document ${held.name} moved to ${into.name}")
        return view(documents.save(held))
    }

    /**
     * Writes a whole document over the one held.
     *
     * @param revision the revision it was written from. Where it is given and
     *   is not the one held, somebody - or a model - saved in between, and
     *   writing this over it would lose what they did, so it is refused.
     * @param pageSize a [DocumentPageSize]; this and the settings after it are left as they are when not given.
     * @param language `en` or `pl`; an empty one is the reader's own.
     * @param via `IMPORT` where what is saved came in by the editor's Import; anything else is the editor's own Save.
     */
    @MutationMapping
    @Transactional
    fun saveDocument(
        @Argument id: Long,
        @Argument content: String,
        @Argument revision: Int?,
        @Argument pageSize: String?,
        @Argument orientation: String?,
        @Argument margins: String?,
        @Argument language: String?,
        @Argument via: String?,
        @Argument viewer: String?,
    ): DesignDocumentView {
        // Claimed before it is read, so two saves over one revision cannot both pass; see DiagramRepository.holdRevision.
        if (revision != null && documents.holdRevision(id, revision) == 0) throw DocumentChangedException(find(id).name)
        val held = find(id)
        if (revision != null && revision != held.revision) throw DocumentChangedException(held.name)
        pageSize?.let { held.pageSize = (pageSize(it) ?: throw DocumentPageSizeInvalidException(it)).name }
        orientation?.let {
            held.orientation = (DocumentOrientation.of(it) ?: throw DocumentSettingInvalidException("orientation", it, "PORTRAIT or LANDSCAPE")).name
        }
        margins?.let {
            held.margins = (DocumentMargins.of(it) ?: throw DocumentSettingInvalidException("margins", it, "NORMAL, NARROW or WIDE")).name
        }
        language?.let {
            held.language = if (it.isBlank()) null else DocumentLanguages.of(it) ?: throw DocumentSettingInvalidException("language", it, DocumentLanguages.ALL.joinToString(" or "))
        }
        return write(held, this.content.read(content, held.workspaceId), DocumentDoor.fromEditor(via), viewer = viewer?.takeIf { it.isNotBlank() })
    }

    /** The page a new document in this workspace starts on. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun documentPageSizeDefault(@Argument workspaceId: Long): String {
        val workspace = access.requireVisible(workspaceId)
        return DocumentPageSize.of(workspace.documentPageSize)?.name ?: DocumentPageSize.A4.name
    }

    /** Sets the page a new document in this workspace starts on: a workspace setting, so its administrators'. */
    @MutationMapping
    @Transactional
    fun setDocumentPageSizeDefault(@Argument workspaceId: Long, @Argument pageSize: String): String {
        val workspace = access.requireAdministers(workspaceId)
        val size = pageSize(pageSize) ?: throw DocumentPageSizeInvalidException(pageSize)
        workspace.documentPageSize = size.name
        workspaces.save(workspace)
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "New documents start on ${size.name} pages")
        return size.name
    }

    /**
     * The same save, from the orknux_* tools.
     *
     * @param turn the document chat's turn, where it is the chat saving: the
     *   saves one turn makes one after another keep one version between them.
     */
    @Transactional
    fun saveContent(id: Long, doc: ObjectNode, turn: String? = null): DesignDocumentView {
        val held = find(id)
        return write(held, content.checked(doc, held.workspaceId), if (turn == null) DocumentDoor.TOOL else DocumentDoor.CHAT, turn)
    }

    /**
     * Puts one of a document's versions back, as a save of its own: what it
     * replaces is kept, so a restore is undone by restoring again. The content
     * and the page settings come back; the name and the catalog stay as they
     * are. Its pictures are not asked about - they were this workspace's when
     * the version was saved, and one deleted since leaves a gap, as it would in
     * the live document.
     *
     * Called by `ComponentRevisionAPI`, which has checked that the version is
     * one the caller may see and writes the audit line.
     */
    @Transactional
    fun restore(id: Long, snapshot: String): DesignDocumentView {
        val held = find(id)
        val was = io.mszymanski.orknux.server.revision.ComponentSnapshot.documentIn(snapshot, mapper)
        was.pageSize?.let { DocumentPageSize.of(it) }?.let { held.pageSize = it.name }
        was.orientation?.let { DocumentOrientation.of(it) }?.let { held.orientation = it.name }
        was.margins?.let { DocumentMargins.of(it) }?.let { held.margins = it.name }
        if (was.languageKept) held.language = DocumentLanguages.of(was.language)
        val doc = content.read(was.content, held.workspaceId)
        return write(held, doc, DocumentDoor.RESTORE, checkPictures = false, said = false)
    }

    @MutationMapping
    @Transactional
    fun deleteDocument(@Argument id: Long): Boolean {
        val held = find(id)
        images.deleteByDocumentId(requireNotNull(held.id))
        documents.delete(held)
        revisions?.forget(io.mszymanski.orknux.server.revision.ComponentRevisionKind.DOCUMENT, requireNotNull(held.id))
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document ${held.name} removed")
        return true
    }

    /**
     * Asks a chat model to write in a document, and answers with what it said
     * and the document as it now stands.
     *
     * Not transactional: a round can take a minute, and every change the model
     * makes is its own save through [saveContent], which the editor takes up
     * while it waits and when the answer comes back.
     */
    @MutationMapping
    fun askDocument(
        @Argument id: Long,
        @Argument modelId: Long,
        @Argument messages: List<DocumentChatTurnInput>,
        @Argument readOnly: Boolean?,
    ): DocumentAnswerView {
        val held = find(id)
        // What was attached goes with the turn it was sent on: pictures as pictures, the rest as text.
        val turns = files.turns(held.workspaceId, who(), messages.map { EditorChatTurn(it.role, it.content, it.attachmentIds.orEmpty()) })
        if (turns.none { it.role == "user" }) throw DocumentAnswerFailedException("there is nothing to answer")
        val answered = chat.answer(view(held), modelId, turns, who(), readOnly = readOnly == true)
        val now = find(id)
        return DocumentAnswerView(answer = answered, changed = now.revision != held.revision, document = view(now))
    }

    /**
     * A selection put into [language] by a chat model, and answered rather than written: the editor
     * puts it in place of the selection as one step undo goes back over, and it is saved - and
     * audited - as every other edit is, by Save. So nothing is recorded here; nothing changed.
     *
     * @param before the words before the selection; only their end is sent.
     * @param after the words after it; only their start is.
     */
    @MutationMapping
    fun translateDocumentText(
        @Argument id: Long,
        @Argument modelId: Long,
        @Argument text: String,
        @Argument language: String,
        @Argument before: String?,
        @Argument after: String?,
    ): String {
        val held = find(id)
        val into = TranslationLanguages.of(language) ?: throw DocumentTranslationLanguageException(language)
        if (text.isBlank()) throw DocumentAnswerFailedException("there is nothing to translate")
        val most = tuning[Tunable.DOCUMENT_SELECTION_CHARACTERS]
        if (text.length > most) throw DocumentSelectionTooLongException(text.length, most)
        val room = tuning[Tunable.DOCUMENT_SELECTION_CONTEXT_CHARACTERS]
        val shown = view(held)
        val context = before.orEmpty().takeLast(room) to after.orEmpty().take(room)
        /*
         * The workspace's companion model, where it has one: a short job with no
         * tools, which the model a person chose to write with need not be held
         * for. That model is what is asked where there is no companion, or where
         * the companion fails - as a chat's title is named.
         */
        val companion = workspaces.findByIdOrNull(held.workspaceId)?.companionModelId?.takeIf { it != modelId }
            ?: return chat.translate(shown, modelId, text, context.first, context.second, into, who())
        return runCatching { chat.translate(shown, companion, text, context.first, context.second, into, who()) }
            .getOrElse { chat.translate(shown, modelId, text, context.first, context.second, into, who()) }
    }

    /**
     * How the sentence being written at the caret might go on: the editor's
     * inline suggestion, from the workspace's completion model, as the chat
     * composer's is. A query - nothing is written. Null where there is nothing
     * to suggest.
     *
     * @param before the text before the caret; only its end is sent on.
     * @param after the text after the caret; only its start is.
     * @param page the page the caret is on, from 1.
     */
    @QueryMapping
    fun documentCompletion(@Argument id: Long, @Argument before: String, @Argument after: String?, @Argument page: Int?): String? {
        val held = find(id)
        val room = tuning[Tunable.DOCUMENT_COMPLETION_CONTEXT_CHARACTERS]
        return sentences.inDocument(held.workspaceId, requireNotNull(held.id), held.name, page, before.takeLast(room), after.orEmpty().take(room / 4))
    }

    // -- For the tools and the files ------------------------------------------

    /** The document by id, checked to be one this caller may see. */
    fun find(id: Long): DesignDocument {
        val held = documents.findByIdOrNull(id) ?: throw DocumentNotFoundException(id)
        if (!access.canSee(held.workspaceId)) throw DocumentNotFoundException(id)
        return held
    }

    fun catalog(id: Long): DocumentCatalog {
        val held = catalogs.findByIdOrNull(id) ?: throw DocumentCatalogNotFoundException(id)
        access.requireVisible(held.workspaceId)
        return held
    }

    /** The catalog a document is filed in when none is named; made the first time it is needed. */
    fun firstCatalog(workspaceId: Long): DocumentCatalog =
        catalogs.findByWorkspaceIdAndName(workspaceId, Documents.FIRST_CATALOG)
            ?: catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).firstOrNull()
            ?: catalogs.save(DocumentCatalog(workspaceId = workspaceId, name = Documents.FIRST_CATALOG, createdBy = who())).also {
                audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document catalog ${it.name} added")
            }

    /** The stored content of a document, as a tree. */
    fun tree(held: DesignDocument): ObjectNode = links.named(content.read(held.content, held.workspaceId), held.workspaceId)

    /** Said in the audit log: a file leaving the server is a copy somebody now holds. */
    fun exported(held: DesignDocument, format: String) {
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document ${held.name} exported as $format")
    }

    fun view(held: DesignDocument, named: Map<Long, String>? = null): DesignDocumentView {
        val catalogName = named?.get(held.catalogId) ?: catalogs.findByIdOrNull(held.catalogId)?.name.orEmpty()
        val words = held.plainText.split(Regex("\\s+")).count { it.isNotBlank() }
        return DesignDocumentView(
            id = requireNotNull(held.id),
            workspaceId = held.workspaceId,
            catalogId = held.catalogId,
            catalogName = catalogName,
            name = held.name,
            content = linked(held),
            pageSize = held.pageSize,
            revision = held.revision,
            words = words,
            excerpt = held.plainText.replace(Regex("\\s+"), " ").take(tuning[Tunable.DOCUMENT_SNIPPET_CHARACTERS]),
            images = images.findByDocumentId(requireNotNull(held.id)).map { it.artifactId },
            createdBy = held.createdBy,
            createdAt = held.createdAt.toString(),
            updatedBy = held.updatedBy,
            updatedAt = held.updatedAt.toString(),
            orientation = held.orientation,
            margins = held.margins,
            language = held.language,
            page = DocumentPage.of(held),
            updatedVia = held.updatedVia,
        )
    }

    /**
     * The stored content, with each link to another document showing that
     * document's name as it is now. Read and written again only where there is
     * a link to name, which most documents do not hold.
     */
    private fun linked(held: DesignDocument): String =
        if (!held.content.contains(DocumentLinks.NODE)) held.content else content.write(tree(held))

    /**
     * The words around the first place [needle] is found, on one line; the
     * start of the document where it is only in the name.
     */
    fun snippet(text: String, needle: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val room = tuning[Tunable.DOCUMENT_SNIPPET_CHARACTERS]
        val at = flat.indexOf(needle, ignoreCase = true)
        if (at < 0) return flat.take(room).let { if (flat.length > room) "$it…" else it }
        val start = (at - room / 3).coerceAtLeast(0)
        val end = (start + room).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }

    /**
     * Every write of a document's content, whichever door it came through.
     *
     * The state it displaces is handed to the recorder first - unless that
     * state was itself written by this same turn of the document chat, which
     * makes it the middle of what the model is doing rather than a version
     * anybody chose: the turn keeps the state before it, and its last save is
     * the live one.
     *
     * @param said whether the save is said in the audit log here; a restore is said by its own line.
     */
    private fun write(
        held: DesignDocument,
        doc: ObjectNode,
        door: DocumentDoor,
        turn: String? = null,
        checkPictures: Boolean = true,
        said: Boolean = true,
        viewer: String? = null,
    ): DesignDocumentView {
        val before = images.findByDocumentId(requireNotNull(held.id)).map { it.artifactId }.toSet()
        if (checkPictures) picturesHere(held.workspaceId, content.images(doc).filter { it !in before })
        if (checkPictures) {
            val now = links.linksIn(doc)
            if (now.isNotEmpty()) documentsHere(held.workspaceId, now - links.linksIn(content.read(held.content, held.workspaceId)))
        }
        val written = stored(doc)
        /*
         * An editor saves as it is typed in, so the version it displaces and
         * the audit line are kept once per visit rather than once a second;
         * every save from any other door keeps both, as before. Issue #683.
         */
        val id = requireNotNull(held.id)
        val key = io.mszymanski.orknux.server.live.LiveRooms.Key(io.mszymanski.orknux.server.live.LiveKind.DOCUMENT, id)
        val firstOfVisit = viewer == null || rooms?.firstSave(key, viewer) != false
        if (firstOfVisit && (turn == null || held.updatedTurn != turn)) revisions?.saved(held)
        held.content = written
        held.plainText = content.plainText(doc)
        held.revision += 1
        held.updatedVia = door.name
        held.updatedTurn = turn
        touch(held)
        val saved = documents.save(held)
        keepImages(saved, doc)
        if (said && firstOfVisit) audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document ${held.name} saved")
        // From every door, so whoever has it open sees it.
        published?.publishEvent(
            io.mszymanski.orknux.server.live.ItemSaved(io.mszymanski.orknux.server.live.LiveKind.DOCUMENT, id, saved.revision.toLong(), viewer, who()),
        )
        return view(saved)
    }

    /**
     * A picture newly put in a document has to be one of this workspace's. Only
     * the new ones are asked about: one deleted since it was put in leaves a gap
     * in the page, not a document that can no longer be saved.
     */
    private fun picturesHere(workspaceId: Long, added: List<String>) {
        added.forEach { if (artifacts.storedOf(workspaceId, it) == null) throw DocumentContentInvalidException("there is no picture $it in this workspace") }
    }

    /**
     * A link newly put in has to go to one of this workspace's documents, for
     * the reason a picture does; one whose document was deleted since it was
     * put in is shown as gone, and does not stop the document being saved.
     */
    private fun documentsHere(workspaceId: Long, added: Set<Long>) {
        val found = links.here(workspaceId, added)
        added.firstOrNull { it !in found }?.let { throw DocumentContentInvalidException("there is no document $it in this workspace to link to") }
    }

    private fun keepImages(held: DesignDocument, doc: ObjectNode) {
        val id = requireNotNull(held.id)
        images.deleteByDocumentId(id)
        images.flush()
        content.images(doc).forEach { images.save(DesignDocumentImage(documentId = id, artifactId = it)) }
    }

    /** The content as stored, held to the Tunable. */
    private fun stored(doc: ObjectNode): String {
        val written = content.write(doc)
        val most = tuning[Tunable.DOCUMENT_CHARACTERS_MOST]
        if (written.length > most) throw DocumentTooLargeException(written.length, most)
        return written
    }

    private fun catalogNames(workspaceId: Long): Map<Long, String> =
        catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).associate { requireNotNull(it.id) to it.name }

    private fun catalogView(catalog: DocumentCatalog) = DocumentCatalogView(
        id = requireNotNull(catalog.id),
        workspaceId = catalog.workspaceId,
        name = catalog.name,
        documentCount = documents.countByCatalogId(requireNotNull(catalog.id)).toInt(),
        createdBy = catalog.createdBy,
        createdAt = catalog.createdAt.toString(),
    )

    private fun touch(held: DesignDocument) {
        held.updatedBy = who()
        held.updatedAt = OffsetDateTime.now()
    }

    private fun pageSize(asked: String?): DocumentPageSize? =
        if (asked.isNullOrBlank()) null else DocumentPageSize.of(asked) ?: throw DocumentPageSizeInvalidException(asked)

    private fun cleanName(name: String): String =
        name.trim().takeIf { it.isNotEmpty() && it.length <= Documents.NAME_LENGTH } ?: throw DocumentNameInvalidException()

    private fun catalogName(name: String): String =
        name.trim().takeIf { it.isNotEmpty() && it.length <= Documents.CATALOG_NAME_LENGTH } ?: throw DocumentCatalogNameInvalidException()

    /** Whoever is signed in; an agent writing through the orknux_* tools with nobody signed in, by its name. */
    private fun who(): String {
        val signedIn = SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        return signedIn?.name ?: ActingIn.actor() ?: "orknux"
    }
}

/** A document as the list, the editor and the view read it. Times are ISO-8601. */
data class DesignDocumentView(
    val id: Long,
    val workspaceId: Long,
    val catalogId: Long,
    val catalogName: String,
    val name: String,
    /** ProseMirror JSON: `{"type":"doc","content":[...]}`. */
    val content: String,
    val pageSize: String,
    val revision: Int,
    val words: Int,
    /** The first words, on one line. */
    val excerpt: String,
    /** The artifact ids of the pictures it shows. */
    val images: List<String>,
    val createdBy: String,
    val createdAt: String,
    val updatedBy: String,
    val updatedAt: String,
    val orientation: String = DocumentOrientation.PORTRAIT.name,
    val margins: String = DocumentMargins.NORMAL.name,
    /** en or pl; null is the reader's own. */
    val language: String? = null,
    /** The page as laid out, in millimetres: its size turned the way it is, and its margin. */
    val page: DocumentPage = DocumentPage(210.0, 297.0, 25.0),
    /** The [DocumentDoor] the live state was saved through; null before doors were recorded. */
    val updatedVia: String? = null,
)

data class DocumentCatalogView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val documentCount: Int,
    val createdBy: String,
    val createdAt: String,
)

/** A document a search found, and the words around where. */
data class DocumentHitView(val document: DesignDocumentView, val snippet: String)

data class DocumentAnswerView(
    val answer: String,
    /** Whether the model saved anything, so the editor knows to take the document up. */
    val changed: Boolean,
    val document: DesignDocumentView,
)

/** One turn of the editor's conversation, which the editor keeps and sends up whole each time. */
data class DocumentChatTurnInput @com.fasterxml.jackson.annotation.JsonCreator constructor(
    @com.fasterxml.jackson.annotation.JsonProperty("role") val role: String,
    @com.fasterxml.jackson.annotation.JsonProperty("content") val content: String,
    /** Files uploaded through the attachments endpoint, sent with this turn. */
    @com.fasterxml.jackson.annotation.JsonProperty("attachmentIds") val attachmentIds: List<Long>? = null,
)
