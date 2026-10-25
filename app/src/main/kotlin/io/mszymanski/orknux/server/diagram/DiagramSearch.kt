package io.mszymanski.orknux.server.diagram

import org.springframework.stereotype.Service

/**
 * One place a search found its words: a node's label or notes, an edge's label,
 * or a diagram's own name. [nodeId] and [edgeId] are the keys the tools and the
 * editor name them by.
 */
data class DiagramHit(
    val diagramId: Long,
    val diagramName: String,
    val catalogName: String,
    /** label, notes, edge or name. */
    val field: String,
    val nodeId: String?,
    val nodeLabel: String?,
    val edgeId: String?,
    val snippet: String,
)

/**
 * Searching diagrams, across a workspace or within one, for the tools, the list
 * page and nothing else.
 *
 * Narrowed in the database by the longest word - a LIKE both engines answer the
 * same way - and ranked here by all of them: what a node is called beats what
 * its notes say, a whole phrase beats words scattered, and among equals the
 * diagram worked on last comes first. A model asked "how does billing reach the
 * ledger" sends a phrase; somebody at the list types a word. Both work.
 */
@Service
class DiagramSearch(
    private val catalogs: DiagramCatalogRepository,
    private val diagrams: DiagramRepository,
    private val nodes: DiagramNodeRepository,
    private val edges: DiagramEdgeRepository,
) {

    fun search(workspaceId: Long, query: String, diagramId: Long?, most: Int): List<DiagramHit> {
        val phrase = query.trim().lowercase().replace(Regex("\\s+"), " ")
        if (phrase.isEmpty()) return emptyList()
        val words = phrase.split(' ').filter { it.isNotEmpty() }.distinct()
        val held = diagrams.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId)
            .filter { diagramId == null || it.id == diagramId }
        if (held.isEmpty()) return emptyList()
        val order = held.mapIndexed { at, it -> requireNotNull(it.id) to at }.toMap()
        val byId = held.associateBy { requireNotNull(it.id) }
        val catalogNames = catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).associate { it.id to it.name }
        val narrowing = "%" + like(words.maxBy { it.length }) + "%"
        val ids = order.keys

        val ranked = mutableListOf<Pair<Double, DiagramHit>>()
        fun hit(score: Double, diagram: Long, field: String, snippet: String, node: DiagramNode? = null, edge: DiagramEdge? = null) {
            val of = byId.getValue(diagram)
            ranked += (score + order.getValue(diagram) / 10_000.0) to DiagramHit(
                diagramId = diagram,
                diagramName = of.name,
                catalogName = catalogNames[of.catalogId].orEmpty(),
                field = field,
                nodeId = node?.key,
                nodeLabel = node?.label,
                edgeId = edge?.key,
                snippet = snippet,
            )
        }

        nodes.matching(ids, narrowing).forEach { node ->
            val label = node.label.lowercase()
            val notes = node.notes?.lowercase().orEmpty()
            when {
                label == phrase -> hit(0.0, node.diagramId, "label", node.label, node)
                label.startsWith(phrase) -> hit(1.0, node.diagramId, "label", node.label, node)
                label.contains(phrase) -> hit(2.0, node.diagramId, "label", node.label, node)
                words.all { label.contains(it) } -> hit(3.0, node.diagramId, "label", node.label, node)
                notes.contains(phrase) -> hit(4.0, node.diagramId, "notes", around(node.notes.orEmpty(), phrase), node)
                words.all { label.contains(it) || notes.contains(it) } ->
                    hit(5.0, node.diagramId, "notes", around(node.notes.orEmpty(), words.first { notes.contains(it) }), node)
            }
        }
        edges.matching(ids, narrowing).forEach { edge ->
            val label = edge.label?.lowercase() ?: return@forEach
            when {
                label.contains(phrase) -> hit(2.5, edge.diagramId, "edge", edge.label.orEmpty(), edge = edge)
                words.all { label.contains(it) } -> hit(4.5, edge.diagramId, "edge", edge.label.orEmpty(), edge = edge)
            }
        }
        held.forEach { diagram ->
            val name = diagram.name.lowercase()
            if (name.contains(phrase) || words.all { name.contains(it) }) {
                hit(if (name.contains(phrase)) 1.5 else 3.5, requireNotNull(diagram.id), "name", diagram.name)
            }
        }
        return ranked.sortedBy { it.first }.take(most.coerceAtLeast(1)).map { it.second }
    }

    /** The words around the first place [found] is, with an ellipsis where the notes go on. */
    private fun around(notes: String, found: String): String {
        val at = notes.lowercase().indexOf(found).coerceAtLeast(0)
        val start = (at - SNIPPET / 3).coerceAtLeast(0)
        val end = (start + SNIPPET).coerceAtMost(notes.length)
        val cut = notes.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + cut + (if (end < notes.length) "…" else "")
    }

    /** A word as a LIKE pattern takes it: its own % and _ meant literally is beyond both engines' defaults, so they match anything. */
    private fun like(word: String): String = word.replace("%", "").replace("_", "")

    private companion object {
        /** How many characters of notes a hit shows around what it found. A format, not a behaviour. */
        const val SNIPPET = 160
    }
}
