package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Links between a workspace's documents, read off the documents themselves.
 *
 * A link is a `documentLink` node naming the document it goes to by id
 * ([DocumentContent]), so a rename is followed: the name it is drawn with is
 * put on it here, from the linked document as it is now, every time content is
 * read out. The name stored with it is only what was true when it was put in,
 * and is what is shown once the document it named is gone.
 *
 * Who mentions a document is derived from the documents rather than kept in a
 * table of its own: a second copy of the links is one more thing a save, a
 * restore, an import and every orknux_* tool would have to remember to write,
 * and the one of them that forgot would make the warning before a delete say
 * nothing. A workspace's documents are few enough to read for it.
 */
@Component
class DocumentLinks(private val documents: DesignDocumentRepository) {

    /**
     * Each link in [doc] given the current name of the document it goes to;
     * one whose document is gone, or is not this workspace's, keeps the name it
     * was put in with and says `missing`, which no save keeps.
     */
    fun named(doc: ObjectNode, workspaceId: Long): ObjectNode {
        val ids = linksIn(doc)
        if (ids.isEmpty()) return doc
        val names = here(workspaceId, ids)
        fun walk(node: JsonNode) {
            if (node.path("type").asText("") == "documentLink") {
                val attrs = node.path("attrs") as? ObjectNode ?: return
                val name = names[attrs.path("document").asLong(0)]
                if (name != null) attrs.put("name", name).remove("missing") else attrs.put("missing", true)
                return
            }
            val inner = node.path("content")
            for (at in 0 until inner.size()) walk(inner.get(at))
        }
        walk(doc)
        return doc
    }

    /** The documents of this workspace among [ids], by id, with their names. */
    fun here(workspaceId: Long, ids: Collection<Long>): Map<Long, String> =
        if (ids.isEmpty()) emptyMap() else documents.findAllById(ids).filter { it.workspaceId == workspaceId }.associate { requireNotNull(it.id) to it.name }

    /** The workspace's other documents that link to [id], by name. */
    fun mentionedBy(workspaceId: Long, id: Long, read: (DesignDocument) -> JsonNode): List<DesignDocument> =
        documents.findByWorkspaceIdAndContentContaining(workspaceId, NODE)
            .filter { it.id != id && id in linksIn(read(it)) }
            .sortedBy { it.name.lowercase() }

    /** The ids of the documents [doc] links to, each once. */
    fun linksIn(doc: JsonNode): Set<Long> {
        val found = linkedSetOf<Long>()
        fun walk(node: JsonNode) {
            if (node.path("type").asText("") == "documentLink") node.path("attrs").path("document").asLong(0).takeIf { it > 0 }?.let { found += it }
            val inner = node.path("content")
            for (at in 0 until inner.size()) walk(inner.get(at))
        }
        walk(doc)
        return found
    }

    companion object {
        /** What a document holding a link has in its stored content: what narrows the documents read for one. */
        const val NODE = "\"documentLink\""
    }
}

/** A document named by another: what a link goes to, or what mentions one. */
data class DocumentReferenceView(val id: Long, val name: String, val catalogName: String)

/**
 * The two sides of a link on a document, asked for only where they are shown:
 * the editor's Mentioned by list, and the warning before a delete.
 */
@Controller
class DocumentLinksAPI(
    private val links: DocumentLinks,
    private val api: DocumentAPI,
    private val catalogs: DocumentCatalogRepository,
    private val access: WorkspaceAccess,
) {

    /** The documents that link to this one. */
    @SchemaMapping(typeName = "DesignDocument", field = "mentionedBy")
    @Transactional(readOnly = true)
    fun mentionedBy(document: DesignDocumentView): List<DocumentReferenceView> {
        access.requireVisible(document.workspaceId)
        val named = catalogs.findByWorkspaceIdOrderByNameAsc(document.workspaceId).associate { requireNotNull(it.id) to it.name }
        return links.mentionedBy(document.workspaceId, document.id) { api.content.read(it.content, it.workspaceId) }
            .map { DocumentReferenceView(requireNotNull(it.id), it.name, named[it.catalogId].orEmpty()) }
    }
}
