package io.mszymanski.orknux.server.revision

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * One thing a save changed.
 *
 * [field] is the snapshot's own key, which the interface turns into words in
 * the reader's language. A list says what came and went in [added] and
 * [removed]; a short value says what it was and what it became; prose - a
 * prompt, a skill, code - says only that it was edited, because the version
 * itself is one press away and a diff of a page is not a line.
 */
data class RevisionChange(
    val field: String,
    val before: String? = null,
    val after: String? = null,
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
)

/**
 * What changed between two states of a component, read off their snapshots.
 *
 * A history entry that shows only the prompt is no help to somebody asking
 * when an agent lost a grant: the grant is not in the prompt. So each save is
 * said as what it changed, with ids put back into the names a person knows -
 * an agent, a connection, a model, a skill - and a name that no longer
 * resolves said as `#id`, which is what the live row would show too.
 *
 * A key one of the two snapshots does not hold is skipped rather than read as
 * empty: a version written before the field was recorded says nothing about
 * it, and "removed everything" would be untrue.
 */
@Component
class RevisionChanges(
    private val mapper: ObjectMapper,
    private val agents: AgentRepository,
    private val skills: AgentSkillRepository,
    private val catalogs: SkillCatalogRepository,
    private val models: LlmModelRepository,
    private val connections: WorkspaceConnectionService,
) {

    fun between(older: String, newer: String): List<RevisionChange> {
        val was = mapper.readTree(older)
        val became = mapper.readTree(newer)
        val keys = (was.propertyNames() + became.propertyNames()).distinct()
            .filter { it != "version" && was.has(it) && became.has(it) }
        val prose = keys.filter { it in PROSE }
        return keys.mapNotNull { key ->
            val a = was.path(key)
            val b = became.path(key)
            if (a == b) return@mapNotNull null
            when {
                // The JavaScript a TypeScript edit compiled to is the same edit, said once.
                key == "source" && "typescript" in prose && was.path("typescript") != became.path("typescript") -> null
                key in PROSE -> RevisionChange(key)
                a.isArray && b.isArray && (a.all { it.isValueNode } && b.all { it.isValueNode }) -> {
                    val before: List<String> = a.values().map { shown(key, it) }
                    val after: List<String> = b.values().map { shown(key, it) }
                    val added: List<String> = after.filter { it !in before }
                    val removed: List<String> = before.filter { it !in after }
                    if (added.isEmpty() && removed.isEmpty()) RevisionChange(key) else RevisionChange(key, added = added, removed = removed)
                }
                a.isContainer || b.isContainer -> RevisionChange(key)
                else -> RevisionChange(key, before = scalar(key, a), after = scalar(key, b))
            }
        }
    }

    /**
     * A snapshot as a person reads it: pretty JSON with every id said as the
     * name behind it, `Summarizer (#784)`, so two versions compare by what
     * they pointed at. The format's own version number is left out; it is
     * not a property of the component.
     */
    fun readable(snapshot: String): String {
        val held = mapper.readTree(snapshot)
        val shown = mapper.createObjectNode()
        held.propertyNames().filter { it != "version" }.forEach { key ->
            val value = held.path(key)
            when {
                key !in NAMED -> shown.set(key, value)
                value.isArray -> shown.putArray(key).also { list -> value.forEach { list.add(labelled(key, it)) } }
                value.isNull -> shown.putNull(key)
                else -> shown.put(key, labelled(key, value))
            }
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(shown)
    }

    private fun labelled(key: String, node: JsonNode): String {
        val raw = if (node.isString) node.stringValue() else node.toString()
        val name = shown(key, node)
        return if (name == raw || name == "#$raw") name else "$name (#$raw)"
    }

    private fun scalar(key: String, node: JsonNode): String? = if (node.isNull || node.isMissingNode) null else shown(key, node)

    /** A value as a person would recognise it: the name behind an id. */
    private fun shown(key: String, node: JsonNode): String {
        val text = if (node.isString) node.stringValue() else node.toString()
        val id = text.toLongOrNull() ?: return text
        val name = when (key) {
            "agentIds" -> agents.findByIdOrNull(id)?.name
            "connectionIds" -> connections.workspaceConnection(id)?.name
            "modelId" -> models.findByIdOrNull(id)?.name
            "hiddenSkills", "requiredSkills" -> skills.findByIdOrNull(id)?.name
            "catalogId" -> catalogs.findByIdOrNull(id)?.name
            else -> return text
        }
        return name ?: "#$id"
    }

    private companion object {
        /** What is read rather than compared: a change to it is "edited". */
        val PROSE = setOf("systemPrompt", "content", "source", "typescript")

        /** The keys whose values are ids of something with a name. */
        val NAMED = setOf("agentIds", "connectionIds", "modelId", "hiddenSkills", "requiredSkills", "catalogId")
    }
}
