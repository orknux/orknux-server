package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import org.springframework.stereotype.Service

/**
 * Which tools change nothing, said in one place. Issue #653.
 *
 * A chat in plan mode offers its agent only the tools that only look, and
 * this is where that is decided - never by a list of names somebody keeps
 * beside the tools, because a list kept beside them is out of date the day a
 * tool is added. It is read off what each tool says of itself:
 *
 * - [ToolSpec.readOnly], which a built-in declares on its own spec, an
 *   embedded tool on its declaration, and an MCP server through the tool's
 *   `readOnlyHint` annotation;
 * - and for the `orknux_*` tools, whether a scope that may not write is
 *   offered the tool at all, which is how that surface has always told the
 *   two apart for the MCP endpoint.
 *
 * Everything else changes something until it says otherwise: a workspace's
 * own JavaScript tool, a plugin's, and a server's tool with no annotation. A
 * tool wrongly kept back costs the agent a look it could have had; one wrongly
 * let through does what the person asked not to happen yet.
 */
@Service
class ToolEffects(private val orknux: OrknuxTools) {

    /** The orknux_* tools a reading scope is offered, by name. Fixed per release. */
    private val orknuxReading: Set<String> by lazy {
        orknux.specs(OrknuxScope(workspaceId = 0, mayWrite = false)).map { it.name }.toSet()
    }

    /** Whether calling [spec] changes nothing outside the conversation. */
    fun readOnly(spec: ToolSpec): Boolean = spec.readOnly || spec.name in orknuxReading

    /** The same split applied to an agent's offering: what it carries and what it finds, each less what changes something. */
    fun readOnly(offering: Offering): Offering =
        Offering(core = offering.core.filter(::readOnly), searchable = offering.searchable.filter(::readOnly))

    /**
     * A lent shed as plan mode lends it: its read-only tools declared and
     * answered, the rest neither - a name it no longer handles falls through
     * to the agent's own tools, which refuse it there. The briefing is passed
     * on, and so is a picture, since a drawing tool that only looks still
     * draws into the conversation.
     */
    fun readOnly(shed: ToolShed?): ToolShed? {
        if (shed == null) return null
        return object : ToolShed {
            override fun specs(): List<ToolSpec> = shed.specs().filter(::readOnly)

            override fun briefing(): String? = shed.briefing()

            override fun handles(name: String): Boolean =
                shed.handles(name) && shed.specs().any { it.name == name && readOnly(it) }

            override fun run(call: ToolCall): String = shed.run(call)

            override fun pictured(tool: String, picture: AgentTools.Companion.Picture): String? = shed.pictured(tool, picture)

            override fun settle() = shed.settle()
        }
    }
}
