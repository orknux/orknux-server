package io.mszymanski.orknux.server.revision

import io.mszymanski.orknux.server.action.FunctionExternal
import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.ScriptImport
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolParam
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * A component written down, and read back onto itself.
 *
 * Both directions are spelled out by hand for the reasons `WorkflowSnapshot`
 * gives — the mapper this application shares has no Kotlin module, so reading
 * into a data class fails, and a shape kept in a database outlives the class it
 * came from. A field absent from an older snapshot reads as the default it
 * always had, which is what lets last month's revision restore after a
 * component gains a property.
 *
 * Two things it is not, and both were tried elsewhere first.
 *
 * It is not `ComponentExporter`'s envelope. That format represents what a
 * component points at *by name*, which is right for carrying work between
 * installations and wrong here: rename the object a parameter is typed against
 * and last week's revision would restore pointing at something else, silently.
 * Everything here is an id, and an id that no longer resolves is a dangling
 * reference the screen reports — the same failure the live row would have.
 *
 * And it is not reflection over the entity. A tool's `params` were missing from
 * the exporter for exactly as long as nobody looked; a format written out field
 * by field is one where an addition has to be typed, and therefore noticed.
 */
object ComponentSnapshot {

    /** What this format is. Read on the way back in, ignored while it is 1. */
    private const val VERSION = 1

    // ------------------------------------------------------------------ write

    fun of(function: WorkflowFunction, mapper: ObjectMapper): String = mapper.writeValueAsString(
        mapOf(
            "version" to VERSION,
            "name" to function.name,
            "description" to function.description,
            "timeoutSeconds" to function.timeoutSeconds,
            "source" to function.source,
            "typescript" to function.typescript,
            "returnType" to function.returnType.name,
            "returnObjectId" to function.returnObjectId,
            "params" to function.params.map {
                mapOf("name" to it.name, "type" to it.type.name, "objectId" to it.objectId)
            },
            // Ids, not names: a variable renamed after this was written is the
            // same variable, and a function restored against its name would be
            // handed a different one or none.
            "externals" to function.externals.map { mapOf("variableId" to it.variableId) },
            /*
             * What it imports: the id it points at, and the name it calls it.
             *
             * Both halves, because neither can be worked out from the other. The
             * id restores against the function that was imported however it has
             * since been renamed; the name restores against the code being put
             * back, which is the code that used it.
             */
            "imports" to function.imports.map {
                mapOf("importedId" to it.importedId, "importName" to it.importName)
            },
            /*
             * The libraries it uses. The id, so a library replaced under the same
             * key restores as the library that is loaded now, and the local name,
             * because it is a word in the code being put back.
             */
            "libraries" to function.libraries.map {
                mapOf("importedId" to it.importedId, "importName" to it.importName)
            },
        ),
    )

    fun of(tool: AgentTool, mapper: ObjectMapper): String = mapper.writeValueAsString(
        mapOf(
            "version" to VERSION,
            "name" to tool.name,
            "description" to tool.description,
            "timeoutSeconds" to tool.timeoutSeconds,
            "source" to tool.source,
            "typescript" to tool.typescript,
            "enabled" to tool.enabled,
            // The half the exporter drops. A tool restored without its
            // parameters is a tool the model is told the wrong signature for.
            "params" to tool.params.map {
                mapOf("name" to it.name, "type" to it.type.name, "objectId" to it.objectId)
            },
            // Ids, not names: a variable renamed after this was written is the
            // same variable, and a tool restored against its name would be
            // handed a different one or none.
            "externals" to tool.externals.map { mapOf("variableId" to it.variableId) },
            /*
             * What it imports: the id it points at, and the name it calls it.
             *
             * Both halves, because neither can be worked out from the other. The
             * id restores against the function that was imported however it has
             * since been renamed; the name restores against the code being put
             * back, which is the code that used it.
             */
            "imports" to tool.imports.map {
                mapOf("importedId" to it.importedId, "importName" to it.importName)
            },
            /*
             * The libraries it uses. The id, so a library replaced under the same
             * key restores as the library that is loaded now, and the local name,
             * because it is a word in the code being put back.
             */
            "libraries" to tool.libraries.map {
                mapOf("importedId" to it.importedId, "importName" to it.importName)
            },
        ),
    )

    fun of(skill: AgentSkill, mapper: ObjectMapper): String = mapper.writeValueAsString(
        mapOf(
            "version" to VERSION,
            "name" to skill.name,
            "description" to skill.description,
            "content" to skill.content,
            "enabled" to skill.enabled,
            "catalogId" to skill.catalogId,
        ),
    )

    fun of(agent: Agent, mapper: ObjectMapper): String = mapper.writeValueAsString(
        mapOf(
            "version" to VERSION,
            "name" to agent.name,
            "description" to agent.description,
            "type" to agent.type.name,
            "systemPrompt" to agent.systemPrompt,
            "enabled" to agent.enabled,
            "modelId" to agent.modelId,
            // Its share of that model's window. Null is the built-in default,
            // and a restore has to be able to put it back to that.
            "memoryShare" to agent.memoryShare,
            "orknuxAccess" to agent.orknuxAccess,
            "mayAskForTools" to agent.mayAskForTools,
            // Which boxes it may use, by label - a grant like the ones below.
            "boxLabels" to agent.boxLabels,
            "icon" to agent.icon,
            /*
             * The grants, as the agent holds them: by name.
             *
             * The one place a name is the right reference, because it is what
             * the column holds. An agent is configured against what the
             * workspace calls things, and a grant naming a catalog that has
             * since been renamed is already a grant that reaches nothing —
             * restoring it restores exactly the state that was saved.
             */
            "mcpServers" to agent.mcpServers.toList(),
            "memoryCatalogs" to agent.memoryCatalogs.toList(),
            "secretCatalogs" to agent.secretCatalogs.toList(),
            "variableCatalogs" to agent.variableCatalogs.toList(),
            "skillCatalogs" to agent.skillCatalogs.toList(),
            "tools" to agent.tools.toList(),
            // By id, which is what the column holds; a grant whose connection
            // has since been deleted restores as exactly the reach it has.
            "connectionIds" to agent.connections.toList(),
        ),
    )

    // ------------------------------------------------------------------- read

    /**
     * What it was called when this was written.
     *
     * Read off the snapshot rather than off the row beside it so that a caller
     * holding only the JSON — a preview, a test — asks one thing.
     */
    fun nameIn(snapshot: String, mapper: ObjectMapper): String =
        text(mapper.readTree(snapshot), "name").orEmpty()

    /**
     * The part of a component somebody actually reads: its code, or its prose.
     *
     * The TypeScript for anything that has some, because that is what was
     * written; the markdown for a skill; the system prompt for an agent. Null
     * where there is none — a function a plugin declared has no TypeScript, and
     * an agent may have no prompt at all.
     */
    fun contentIn(kind: ComponentRevisionKind, snapshot: String, mapper: ObjectMapper): RevisionContent {
        val held = mapper.readTree(snapshot)
        return when (kind) {
            ComponentRevisionKind.FUNCTION ->
                RevisionContent(text(held, "typescript") ?: text(held, "source"), "typescript")

            ComponentRevisionKind.TOOL ->
                RevisionContent(text(held, "typescript") ?: text(held, "source"), "typescript")

            ComponentRevisionKind.SKILL -> RevisionContent(text(held, "content"), "markdown")
            ComponentRevisionKind.AGENT -> RevisionContent(text(held, "systemPrompt"), "markdown")
            // Its versions are its publications and never reach this table.
            ComponentRevisionKind.WORKFLOW -> RevisionContent(null, "json")
        }
    }

    // --------------------------------------------------------------- restore

    /**
     * Puts a snapshot back onto the live row.
     *
     * Every field the snapshot holds is written, including the ones that
     * happen to match: a restore is "make it what it was", and a partial one
     * would leave a component that is neither state. What is deliberately not
     * touched is the identity — the row id, the workspace, and for a function
     * the plugin scope, none of which a revision has any business moving.
     *
     * The last-modified stamps are the caller's to set afterwards, because the
     * restore is itself a save and it was made by whoever pressed the button,
     * not by whoever wrote the code being put back.
     */
    fun restore(function: WorkflowFunction, snapshot: String, mapper: ObjectMapper) {
        val held = mapper.readTree(snapshot)
        function.name = text(held, "name") ?: function.name
        function.description = text(held, "description")
        function.timeoutSeconds = number(held, "timeoutSeconds")?.toInt()
        function.source = text(held, "source") ?: function.source
        function.typescript = text(held, "typescript")
        function.returnType = enumOf(held, "returnType", function.returnType)
        function.returnObjectId = number(held, "returnObjectId")
        function.params = held.path("params").values().map { param ->
            FunctionParam(
                name = text(param, "name").orEmpty(),
                type = enumOf(param, "type", ValueType.STRING),
                objectId = number(param, "objectId"),
            )
        }.toMutableList()
        function.externals = held.path("externals").values().mapNotNull { external ->
            number(external, "variableId")?.let { FunctionExternal(variableId = it) }
        }.toMutableList()
        function.imports = held.path("imports").values().mapNotNull { imported ->
            val id = number(imported, "importedId") ?: return@mapNotNull null
            val name = text(imported, "importName") ?: return@mapNotNull null
            ScriptImport(importedId = id, importName = name)
        }.toMutableList()
        function.libraries = held.path("libraries").values().mapNotNull { imported ->
            val id = number(imported, "importedId") ?: return@mapNotNull null
            val name = text(imported, "importName") ?: return@mapNotNull null
            ScriptImport(importedId = id, importName = name)
        }.toMutableList()
    }

    fun restore(tool: AgentTool, snapshot: String, mapper: ObjectMapper) {
        val held = mapper.readTree(snapshot)
        tool.name = text(held, "name") ?: tool.name
        tool.description = text(held, "description")
        tool.timeoutSeconds = number(held, "timeoutSeconds")?.toInt()
        tool.source = text(held, "source") ?: tool.source
        tool.typescript = text(held, "typescript") ?: tool.typescript
        tool.enabled = held.path("enabled").asBoolean(true)
        tool.params = held.path("params").values().map { param ->
            AgentToolParam(
                name = text(param, "name").orEmpty(),
                type = enumOf(param, "type", ValueType.STRING),
                objectId = number(param, "objectId"),
            )
        }.toMutableList()
        tool.externals = held.path("externals").values().mapNotNull { external ->
            number(external, "variableId")?.let { FunctionExternal(variableId = it) }
        }.toMutableList()
        tool.imports = held.path("imports").values().mapNotNull { imported ->
            val id = number(imported, "importedId") ?: return@mapNotNull null
            val name = text(imported, "importName") ?: return@mapNotNull null
            ScriptImport(importedId = id, importName = name)
        }.toMutableList()
        tool.libraries = held.path("libraries").values().mapNotNull { imported ->
            val id = number(imported, "importedId") ?: return@mapNotNull null
            val name = text(imported, "importName") ?: return@mapNotNull null
            ScriptImport(importedId = id, importName = name)
        }.toMutableList()
    }

    fun restore(skill: AgentSkill, snapshot: String, mapper: ObjectMapper) {
        val held = mapper.readTree(snapshot)
        skill.name = text(held, "name") ?: skill.name
        skill.description = text(held, "description")
        skill.content = text(held, "content") ?: skill.content
        skill.enabled = held.path("enabled").asBoolean(true)
        // The folder it was in. A catalog since deleted is a dangling id, which
        // is what the API checks before it lets a restore through.
        skill.catalogId = number(held, "catalogId") ?: skill.catalogId
    }

    fun restore(agent: Agent, snapshot: String, mapper: ObjectMapper) {
        val held = mapper.readTree(snapshot)
        agent.name = text(held, "name") ?: agent.name
        agent.description = text(held, "description")
        agent.type = enumOf(held, "type", agent.type)
        agent.systemPrompt = text(held, "systemPrompt")
        agent.enabled = held.path("enabled").asBoolean(true)
        agent.modelId = number(held, "modelId")
        // A version written before this existed has no share, which is what an
        // agent saved then was actually running on.
        agent.memoryShare = number(held, "memoryShare")?.toInt()
        agent.orknuxAccess = held.path("orknuxAccess").asBoolean(false)
        // A version written before the switch existed could ask, as every agent then could once it arrived.
        agent.mayAskForTools = held.path("mayAskForTools").asBoolean(true)
        // A version written before boxes held none, which is what it had.
        agent.boxLabels = names(held, "boxLabels")
        agent.icon = text(held, "icon")
        agent.mcpServers = names(held, "mcpServers")
        agent.memoryCatalogs = names(held, "memoryCatalogs")
        // A version written before these grants held none, which is what it had.
        agent.secretCatalogs = names(held, "secretCatalogs")
        agent.variableCatalogs = names(held, "variableCatalogs")
        agent.skillCatalogs = names(held, "skillCatalogs")
        agent.tools = names(held, "tools")
        // A version written before connections existed grants none, which is
        // what an agent saved then actually held.
        agent.connections =
            held.path("connectionIds").values().mapNotNull { if (it.isNumber) it.asLong() else null }.toMutableList()
    }

    // ---------------------------------------------------------------- reading

    private fun text(node: JsonNode, name: String): String? =
        node.path(name).let { if (it.isString) it.stringValue() else null }

    private fun number(node: JsonNode, name: String): Long? =
        node.path(name).let { if (it.isNumber) it.asLong() else null }

    private fun names(node: JsonNode, name: String): MutableList<String> =
        node.path(name).values().mapNotNull { if (it.isString) it.stringValue() else null }.toMutableList()

    /** An unknown or missing value reads as the default, never as a failure. */
    private inline fun <reified E : Enum<E>> enumOf(node: JsonNode, name: String, fallback: E): E =
        text(node, name)?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback
}

/** The readable half of a revision, and what it is written in. */
data class RevisionContent(val text: String?, val language: String)
