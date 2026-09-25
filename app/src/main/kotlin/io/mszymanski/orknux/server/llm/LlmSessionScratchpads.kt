package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.workflow.script.SessionScratchpads
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The server's half of `orknux.scratchpad` - see [SessionScratchpads] for what
 * a script reaches through it and why it is one method.
 *
 * The request names an operation and its arguments; this parses it and calls
 * [SessionScratchpadService], the same service the agent's tools call, so a
 * plugin working inside a session works on the very files the agent does and
 * against the very same bounds. The answer is `{ "ok": ... }` or
 * `{ "error": "..." }`, the shape the other doors answer in.
 */
@Component
class LlmSessionScratchpads(
    private val pads: SessionScratchpadService,
    private val mapper: ObjectMapper,
) : SessionScratchpads {

    override fun act(sessionId: Long, request: String): String {
        val node = runCatching { mapper.readTree(request) }.getOrNull()
            ?: return error("the request was not readable JSON")
        val op = text(node, "op") ?: return error("say which scratchpad operation to run in \"op\"")
        return when (op) {
            "list" -> listed(sessionId)
            "read" -> read(sessionId, node)
            "write" -> written(sessionId, node)
            "append" -> reported(pads.append(sessionId, name(node), text(node, "text").orEmpty()))
            "replace" -> reported(pads.replace(sessionId, name(node), text(node, "old").orEmpty(), text(node, "new").orEmpty()))
            "describe" -> reported(pads.describe(sessionId, name(node), text(node, "description")))
            "delete" -> reported(pads.delete(sessionId, name(node)))
            "share" -> reported(pads.share(sessionId, name(node), bool(node, "shared") ?: true))
            "search" -> searched(sessionId, node)
            else -> error("there is no scratchpad operation called \"$op\"")
        }
    }

    private fun listed(session: Long): String {
        val held = pads.list(session).map {
            mapOf(
                "name" to it.name,
                "description" to it.description,
                "bytes" to it.bytes,
                "shared" to it.shared,
                "ownedHere" to (it.sessionId == session),
            )
        }
        return mapper.writeValueAsString(mapOf("ok" to held))
    }

    private fun read(session: Long, node: JsonNode): String {
        val name = name(node)
        val pad = pads.find(session, name) ?: return error("there is no scratchpad named \"$name\" in this session")
        val from = int(node, "from")?.coerceAtLeast(0) ?: 0
        val length = int(node, "length")
        val whole = pad.content
        val slice = when {
            from >= whole.length -> ""
            length == null -> whole.substring(from)
            else -> whole.substring(from, (from + length).coerceAtMost(whole.length))
        }
        return mapper.writeValueAsString(
            mapOf(
                "ok" to mapOf(
                    "name" to pad.name,
                    "description" to pad.description,
                    "totalChars" to whole.length,
                    "from" to from,
                    "content" to slice,
                ),
            ),
        )
    }

    private fun written(session: Long, node: JsonNode): String {
        val name = name(node)
        val content = text(node, "content").orEmpty()
        val description = text(node, "description")
        val existing = pads.find(session, name)
        val result = if (existing == null || existing.sessionId != session) {
            pads.create(session, name, description, content)
        } else {
            val written = pads.write(session, name, content)
            if (written is ScratchpadResult.Ok && description != null) pads.describe(session, name, description) else written
        }
        return reported(result)
    }

    private fun searched(session: Long, node: JsonNode): String {
        val hits = pads.search(session, text(node, "query").orEmpty())
            .map { mapOf("name" to it.name, "line" to it.line, "text" to it.text) }
        return mapper.writeValueAsString(mapOf("ok" to hits))
    }

    private fun reported(result: ScratchpadResult): String = when (result) {
        is ScratchpadResult.Ok -> mapper.writeValueAsString(
            mapOf("ok" to mapOf("name" to result.pad.name, "bytes" to result.pad.bytes, "shared" to result.pad.shared)),
        )
        is ScratchpadResult.No -> error(result.why)
    }

    private fun name(node: JsonNode): String = text(node, "name").orEmpty()

    private fun text(node: JsonNode, field: String): String? =
        node.path(field).takeIf { it.isTextual }?.stringValue()?.takeIf { it.isNotEmpty() }

    private fun int(node: JsonNode, field: String): Int? =
        node.path(field).let { if (it.isNumber) it.asInt() else it.takeIf { n -> n.isTextual }?.stringValue()?.toIntOrNull() }

    private fun bool(node: JsonNode, field: String): Boolean? =
        node.path(field).let { if (it.isBoolean) it.asBoolean() else it.takeIf { n -> n.isTextual }?.stringValue()?.toBooleanStrictOrNull() }

    private fun error(said: String): String = mapper.writeValueAsString(mapOf("error" to said))
}
