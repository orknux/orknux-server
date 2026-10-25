package io.mszymanski.orknux.server.diagram

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.context.annotation.Lazy
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * A workspace's diagrams and their catalogs, and the one door every change to
 * them comes through.
 *
 * Anybody who can see the workspace may draw in it, as anybody may draw a
 * wireframe. The orknux_* diagram tools save through this same class
 * ([io.mszymanski.orknux.server.mcp.DiagramTools]), so what a model draws is
 * checked by the same [DiagramDocument], held to the same Tunables - the note
 * cap among them - and written in the audit log in the same words, under the
 * name of whoever asked. What the tools add is [DiagramAgentAccess]: a diagram
 * set to Read only is refused to them here, by [requireAgentMayChange].
 */
@Controller
class DiagramAPI(
    private val catalogs: DiagramCatalogRepository,
    private val diagrams: DiagramRepository,
    private val nodes: DiagramNodeRepository,
    private val edges: DiagramEdgeRepository,
    private val audit: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
    mapper: ObjectMapper,
    /** Lazy: the chat reaches the orknux_* tools, which save through here. */
    @param:Lazy private val chat: DiagramChat,
    private val search: DiagramSearch,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    val document = DiagramDocument(mapper)

    // ---- Catalogs ----

    @QueryMapping
    @Transactional(readOnly = true)
    fun diagramCatalogs(@Argument workspaceId: Long): List<DiagramCatalogView> {
        access.requireVisible(workspaceId)
        return catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map(::view)
    }

    @MutationMapping
    @Transactional
    fun createDiagramCatalog(@Argument workspaceId: Long, @Argument name: String): DiagramCatalogView {
        access.requireVisible(workspaceId)
        val called = catalogName(name)
        if (catalogs.findByWorkspaceIdAndName(workspaceId, called) != null) throw DiagramCatalogNameTakenException(called)
        val made = catalogs.save(DiagramCatalog(workspaceId = workspaceId, name = called, createdBy = who()))
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Diagram catalog $called added")
        return view(made)
    }

    @MutationMapping
    @Transactional
    fun renameDiagramCatalog(@Argument id: Long, @Argument name: String): DiagramCatalogView {
        val held = catalog(id)
        val was = held.name
        val called = catalogName(name)
        if (called == was) return view(held)
        catalogs.findByWorkspaceIdAndName(held.workspaceId, called)?.let { throw DiagramCatalogNameTakenException(called) }
        held.name = called
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Diagram catalog $was renamed to $called")
        return view(catalogs.save(held))
    }

    /** Only an empty one: the diagrams in a catalog are moved or deleted first, each on purpose. */
    @MutationMapping
    @Transactional
    fun deleteDiagramCatalog(@Argument id: Long): Boolean {
        val held = catalog(id)
        val count = diagrams.countByCatalogId(id)
        if (count > 0) throw DiagramCatalogNotEmptyException(held.name, count)
        catalogs.delete(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Diagram catalog ${held.name} removed")
        return true
    }

    // ---- Diagrams ----

    /** A workspace's diagrams, the one worked on last first; with [catalogId], that catalog's. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun diagrams(@Argument workspaceId: Long, @Argument catalogId: Long?): List<DiagramView> {
        access.requireVisible(workspaceId)
        val names = catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).associate { it.id to it.name }
        return diagrams.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId)
            .filter { catalogId == null || it.catalogId == catalogId }
            .map { view(it, names[it.catalogId], withDocument = false) }
    }

    /** One diagram with its drawing, or null where there is none this person may see. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun diagram(@Argument id: Long): DiagramView? {
        val held = diagrams.findByIdOrNull(id) ?: return null
        access.requireVisible(held.workspaceId)
        return view(held)
    }

    /** Every node of every diagram by its label, for the Quick actions box to find them by. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun diagramNodeIndex(@Argument workspaceId: Long): List<DiagramIndexEntry> {
        access.requireVisible(workspaceId)
        return diagrams.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId).flatMap { held ->
            val id = requireNotNull(held.id)
            nodes.findByDiagramIdOrderByPositionAsc(id).filter { it.label.isNotBlank() }.map {
                DiagramIndexEntry(diagramId = id, diagramName = held.name, nodeId = it.key, label = it.label)
            }
        }
    }

    /** Node labels, notes and edge labels across the workspace, or in one diagram, best match first. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun searchDiagrams(@Argument workspaceId: Long, @Argument query: String, @Argument diagramId: Long?): List<DiagramHit> {
        access.requireVisible(workspaceId)
        if (diagramId != null && find(diagramId).workspaceId != workspaceId) throw DiagramNotFoundException(diagramId)
        return search.search(workspaceId, query, diagramId, tuning[Tunable.DIAGRAM_SEARCH_HITS])
    }

    @MutationMapping
    @Transactional
    fun createDiagram(
        @Argument workspaceId: Long,
        @Argument name: String,
        @Argument catalogId: Long?,
        @Argument document: String?,
        @Argument description: String?,
    ): DiagramView = create(workspaceId, name, catalogId, this.document.read(document), description)

    /** The same create, from the tools, which hold a drawing rather than text. */
    @Transactional
    fun create(workspaceId: Long, name: String, catalogId: Long?, drawing: DiagramDrawing, description: String? = null): DiagramView {
        access.requireVisible(workspaceId)
        val catalog = catalogFor(workspaceId, catalogId)
        held(drawing)
        val who = who()
        val made = diagrams.save(
            Diagram(
                workspaceId = workspaceId,
                catalogId = requireNotNull(catalog.id),
                name = cleanName(name),
                description = cleanDescription(description),
                createdBy = who,
                updatedBy = who,
            ),
        )
        writeRows(requireNotNull(made.id), drawing)
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Diagram ${made.name} added")
        return view(made)
    }

    @MutationMapping
    @Transactional
    fun renameDiagram(@Argument id: Long, @Argument name: String): DiagramView {
        val held = find(id)
        val was = held.name
        val called = cleanName(name)
        if (called == was) return view(held)
        held.name = called
        touch(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Diagram $was renamed to $called")
        return view(diagrams.save(held))
    }

    /** What the diagram is for; blank takes it away. From the editor and from the tools alike. */
    @MutationMapping
    @Transactional
    fun describeDiagram(@Argument id: Long, @Argument description: String?): DiagramView {
        val held = find(id)
        val said = cleanDescription(description)
        if (said == held.description) return view(held)
        held.description = said
        touch(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Diagram ${held.name} description changed")
        return view(diagrams.save(held))
    }

    @MutationMapping
    @Transactional
    fun moveDiagram(@Argument id: Long, @Argument catalogId: Long): DiagramView {
        val held = find(id)
        val to = catalog(catalogId)
        if (to.workspaceId != held.workspaceId) throw DiagramCatalogNotFoundException(catalogId)
        if (held.catalogId == catalogId) return view(held)
        held.catalogId = catalogId
        touch(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Diagram ${held.name} moved to ${to.name}")
        return view(diagrams.save(held))
    }

    /** Whether agents may change it, or only read it. A person's decision, so a person's door only. */
    @MutationMapping
    @Transactional
    fun setDiagramAgentAccess(@Argument id: Long, @Argument access: DiagramAgentAccess): DiagramView {
        val held = find(id)
        if (held.agentAccess == access) return view(held)
        held.agentAccess = access
        touch(held)
        audit.record(
            held.workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            if (access == DiagramAgentAccess.READ) "Diagram ${held.name} made read only for agents" else "Diagram ${held.name} opened to changes by agents",
        )
        return view(diagrams.save(held))
    }

    /**
     * Writes a whole drawing over the one held.
     *
     * @param revision the revision the drawing was made from; where it is given
     *   and is not the one held, somebody - or a model - saved in between, and
     *   writing this over it would lose what they did, so it is refused.
     */
    @MutationMapping
    @Transactional
    fun saveDiagram(@Argument id: Long, @Argument document: String, @Argument revision: Int?): DiagramView {
        val held = find(id)
        if (revision != null && revision != held.revision) throw DiagramChangedException(held.name)
        return write(held, this.document.read(document))
    }

    /** The same save, from the tools. */
    @Transactional
    fun saveDrawing(id: Long, drawing: DiagramDrawing): DiagramView = write(find(id), document.checked(drawing))

    @MutationMapping
    @Transactional
    fun deleteDiagram(@Argument id: Long): Boolean {
        val held = find(id)
        diagrams.delete(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Diagram ${held.name} removed")
        return true
    }

    /**
     * Asks a chat model to work on the diagram: the conversation so far, oldest
     * first, ending with what is asked now. What it changes is saved as it goes.
     * Not transactional, as the wireframe chat is not: a round can take a minute.
     */
    @MutationMapping
    fun askDiagram(@Argument id: Long, @Argument modelId: Long, @Argument messages: List<DiagramChatTurnInput>): DiagramAnswerView {
        val held = find(id)
        val turns = messages
            .filter { it.content.isNotBlank() }
            .map { ChatTurn(role = if (it.role.equals("assistant", ignoreCase = true)) "assistant" else "user", content = it.content) }
        if (turns.none { it.role == "user" }) throw DiagramAnswerFailedException("there is nothing to answer")
        val answered = chat.answer(view(held), modelId, turns, who())
        val now = find(id)
        return DiagramAnswerView(answer = answered, changed = now.revision != held.revision, diagram = view(now))
    }

    // ---- For the tools ----

    /** The diagram by id, checked to be one this caller may see. */
    fun find(id: Long): Diagram {
        val held = diagrams.findByIdOrNull(id) ?: throw DiagramNotFoundException(id)
        access.requireVisible(held.workspaceId)
        return held
    }

    /** Refused where the diagram is set to Read only for agents. */
    fun requireAgentMayChange(held: Diagram) {
        if (held.agentAccess != DiagramAgentAccess.CHANGE) throw DiagramReadOnlyException(held.name)
    }

    /** The drawing as rows are held, in the order they were drawn. */
    fun drawing(diagramId: Long): DiagramDrawing = DiagramDrawing(
        nodes = nodes.findByDiagramIdOrderByPositionAsc(diagramId).map {
            DiagramShape(
                id = it.key,
                kind = DiagramNodeKind.byKey(it.kind) ?: DiagramNodeKind.BOX,
                label = it.label,
                x = it.x, y = it.y, w = it.w, h = it.h,
                color = it.color, notes = it.notes, parent = it.parentKey,
            )
        },
        edges = edges.findByDiagramIdOrderByPositionAsc(diagramId).map {
            DiagramLink(
                id = it.key, from = it.sourceKey, to = it.targetKey, label = it.label,
                kind = DiagramEdgeKind.byKey(it.kind) ?: DiagramEdgeKind.ARROW,
            )
        },
    )

    fun catalogNameOf(id: Long): String? = catalogs.findByIdOrNull(id)?.name

    fun view(diagram: Diagram, catalogName: String? = null, withDocument: Boolean = true): DiagramView {
        val id = requireNotNull(diagram.id)
        val drawn = if (withDocument) drawing(id) else null
        return DiagramView(
            id = id,
            workspaceId = diagram.workspaceId,
            catalogId = diagram.catalogId,
            catalogName = catalogName ?: catalogNameOf(diagram.catalogId).orEmpty(),
            name = diagram.name,
            description = diagram.description,
            descriptionCharactersMost = tuning[Tunable.DIAGRAM_DESCRIPTION_CHARACTERS],
            agentAccess = diagram.agentAccess,
            document = drawn?.let(document::write) ?: "",
            nodeCount = drawn?.nodes?.size ?: nodes.countByDiagramId(id).toInt(),
            edgeCount = drawn?.edges?.size ?: edges.countByDiagramId(id).toInt(),
            revision = diagram.revision,
            noteCharactersMost = tuning[Tunable.DIAGRAM_NOTE_CHARACTERS],
            createdBy = diagram.createdBy,
            createdAt = diagram.createdAt.toString(),
            updatedBy = diagram.updatedBy,
            updatedAt = diagram.updatedAt.toString(),
        )
    }

    /**
     * The catalog a new diagram goes in: the one named, or the workspace's
     * first, made where there is none - a diagram is always in a catalog, and
     * having to make one before the first diagram is setup nobody is told about.
     */
    fun catalogFor(workspaceId: Long, catalogId: Long?): DiagramCatalog {
        if (catalogId != null) {
            val held = catalog(catalogId)
            if (held.workspaceId != workspaceId) throw DiagramCatalogNotFoundException(catalogId)
            return held
        }
        val all = catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId)
        return all.firstOrNull { it.name == Diagrams.FIRST_CATALOG } ?: all.firstOrNull()
            ?: catalogs.save(DiagramCatalog(workspaceId = workspaceId, name = Diagrams.FIRST_CATALOG, createdBy = who()))
    }

    /** A catalog by its name or id, in a workspace, for the tools. */
    fun catalogNamed(workspaceId: Long, named: String): DiagramCatalog? =
        catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).firstOrNull { it.name.equals(named, ignoreCase = true) || it.id?.toString() == named }

    private fun write(held: Diagram, drawing: DiagramDrawing): DiagramView {
        held(drawing)
        val id = requireNotNull(held.id)
        writeRows(id, drawing)
        held.revision += 1
        touch(held)
        val saved = diagrams.save(held)
        audit.record(
            held.workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Diagram ${held.name} saved with ${drawing.nodes.size} nodes and ${drawing.edges.size} connections",
        )
        return view(saved)
    }

    /** The rows replaced whole; the bulk deletes run first, so a kept key is not inserted beside itself. */
    private fun writeRows(diagramId: Long, drawing: DiagramDrawing) {
        nodes.deleteByDiagram(diagramId)
        edges.deleteByDiagram(diagramId)
        nodes.saveAll(
            drawing.nodes.mapIndexed { at, node ->
                DiagramNode(
                    diagramId = diagramId, key = node.id, position = at, kind = node.kind.key, label = node.label,
                    x = node.x, y = node.y, w = node.w, h = node.h, color = node.color, notes = node.notes, parentKey = node.parent,
                )
            },
        )
        edges.saveAll(
            drawing.edges.mapIndexed { at, edge ->
                DiagramEdge(
                    diagramId = diagramId, key = edge.id, position = at, sourceKey = edge.from, targetKey = edge.to,
                    label = edge.label, kind = edge.kind.key,
                )
            },
        )
    }

    /** The installation's two numbers, on every save from every door: how much it holds, and how long notes are. */
    private fun held(drawing: DiagramDrawing) {
        val most = tuning[Tunable.DIAGRAM_ITEMS_MOST]
        if (drawing.size > most) throw DiagramTooLargeException(drawing.size, most)
        val notes = tuning[Tunable.DIAGRAM_NOTE_CHARACTERS]
        drawing.nodes.forEach { node ->
            val length = node.notes?.length ?: 0
            if (length > notes) throw DiagramNotesTooLongException(node.label.ifBlank { node.id }, length, notes)
        }
    }

    private fun catalog(id: Long): DiagramCatalog {
        val held = catalogs.findByIdOrNull(id) ?: throw DiagramCatalogNotFoundException(id)
        access.requireVisible(held.workspaceId)
        return held
    }

    private fun view(catalog: DiagramCatalog) = DiagramCatalogView(
        id = requireNotNull(catalog.id),
        workspaceId = catalog.workspaceId,
        name = catalog.name,
        diagramCount = diagrams.countByCatalogId(requireNotNull(catalog.id)).toInt(),
    )

    private fun touch(held: Diagram) {
        held.updatedBy = who()
        held.updatedAt = OffsetDateTime.now()
    }

    private fun cleanName(name: String): String =
        name.trim().takeIf { it.isNotEmpty() && it.length <= Diagrams.NAME_LENGTH } ?: throw DiagramNameInvalidException()

    /** Trimmed, blank as none, and held to the Tunable. */
    private fun cleanDescription(description: String?): String? {
        val said = description?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val most = tuning[Tunable.DIAGRAM_DESCRIPTION_CHARACTERS]
        if (said.length > most) throw DiagramDescriptionTooLongException(said.length, most)
        return said
    }

    private fun catalogName(name: String): String =
        name.trim().takeIf { it.isNotEmpty() && it.length <= Diagrams.CATALOG_NAME_LENGTH } ?: throw DiagramCatalogNameInvalidException()

    /** Whoever is signed in; an agent drawing through the orknux_* tools with nobody signed in, by its name. */
    private fun who(): String {
        val signedIn = SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        return signedIn?.name ?: ActingIn.actor() ?: "orknux"
    }
}

data class DiagramCatalogView(val id: Long, val workspaceId: Long, val name: String, val diagramCount: Int)

/** A diagram as the list and the editor read it; [document] is empty on the list. Times are ISO-8601. */
data class DiagramView(
    val id: Long,
    val workspaceId: Long,
    val catalogId: Long,
    val catalogName: String,
    val name: String,
    /** What it is for; null where nobody has said. */
    val description: String?,
    /** How long a description may be on this installation. */
    val descriptionCharactersMost: Int,
    val agentAccess: DiagramAgentAccess,
    val document: String,
    val nodeCount: Int,
    val edgeCount: Int,
    val revision: Int,
    /** How long a node's notes may be on this installation, for the editor's counter. */
    val noteCharactersMost: Int,
    val createdBy: String,
    val createdAt: String,
    val updatedBy: String,
    val updatedAt: String,
)

data class DiagramIndexEntry(val diagramId: Long, val diagramName: String, val nodeId: String, val label: String)

data class DiagramAnswerView(val answer: String, val changed: Boolean, val diagram: DiagramView)

data class DiagramChatTurnInput @JsonCreator constructor(
    @JsonProperty("role") val role: String,
    @JsonProperty("content") val content: String,
)
