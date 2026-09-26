package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.llm.ScratchpadResult
import io.mszymanski.orknux.server.llm.SessionScratchpadService
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * An agent's working files, for the span of one session. Issue #411.
 *
 * ### What it is for
 *
 * A scratchpad is where an agent assembles something too large to carry in its
 * head across a long job - an HTML page it is drafting, a file of code, a set
 * of notes it is organising. It writes to one a piece at a time, reads back a
 * fragment or the whole, searches across them, and deletes when done.
 *
 * ### Why not a note, and why not the transcript
 *
 * A note ([NoteTools]) is a short line put back to the agent whole on every
 * turn - it must stay small because it is paid for every round. A scratchpad is
 * never put in front of the model unless the model reads it, so it can be a
 * document. The transcript is trimmed to fit; a scratchpad is not, and the
 * agent decides when to look at it.
 *
 * ### Why a shed, and only where there is a session
 *
 * Like notes, these have nowhere to keep anything without a session, and a tool
 * that takes a document and drops it is worse than no tool. So they are lent
 * for the round wherever a session-keeping agent runs - a workflow node, a
 * task - and are absent everywhere else. The bounds are the service's; this is
 * only the model's way in.
 */
@Service
class ScratchpadTools(
    private val pads: SessionScratchpadService,
    private val mapper: ObjectMapper,
) {

    /** The shed for one turn, or null where there is no session to keep files in. */
    fun shed(session: Long?): ToolShed? = if (session == null) null else Shed(session)

    private inner class Shed(private val session: Long) : ToolShed {

        /**
         * That the agent has working files, and when to use them.
         *
         * Eight tool descriptions said what each did and nothing said the agent
         * had any, so a model drafting a long page did it in its head, badly,
         * and never wrote a file - the same silence that kept memory search
         * unused before the briefing named the memory. Said once, with the
         * when: the trigger for a scratchpad is length or several turns, and a
         * model told only "you may" reaches for it never. Issue #445.
         */
        override fun briefing(): String = buildString {
            append("You have scratchpads: working files kept for this whole session, never shown to you unless ")
            append("you read them, so they can be long. Use one whenever what you are making is longer than a ")
            append("message, is built up over several turns, or is something you will revise - a document, ")
            append("a page of HTML, a file of code, notes you are organising. Write it with $WRITE, grow it ")
            append("with $APPEND, change a piece with $REPLACE, and read it back with $READ before you hand it ")
            append("on; $LIST says what you have already started. Working in a scratchpad and then posting the ")
            append("result beats composing the whole of it in one answer.")
        }

        override fun specs(): List<ToolSpec> = listOf(
            ToolSpec(
                name = LIST,
                description = "Lists your scratchpads in this session - their names, what each is for, and " +
                    "how large it is. A scratchpad is a working file you keep across the whole session; use " +
                    "one to build up something long that you cannot hold in a single turn. Read this before " +
                    "assuming you have or have not started one.",
                parameters = emptyList(),
            ),
            ToolSpec(
                name = READ,
                description = "Reads a scratchpad - the whole of it, or a fragment when it is large. Give " +
                    "$FROM and $LENGTH to read a slice starting at a character offset; leave them out for the " +
                    "whole file.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "Which scratchpad to read.", required = true),
                    ToolParameterSpec(FROM, "The character offset to start at; 0 is the beginning.", required = false),
                    ToolParameterSpec(LENGTH, "How many characters to read from there.", required = false),
                ),
            ),
            ToolSpec(
                name = WRITE,
                description = "Creates a scratchpad, or replaces the whole content of one that exists. Use " +
                    "$APPEND or $REPLACE to change part of a large file rather than resending it. You may set " +
                    "$DESCRIPTION to say what the file is for.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "The scratchpad's name, like a filename.", required = true),
                    ToolParameterSpec(CONTENT, "The whole content to write.", required = true),
                    ToolParameterSpec(DESCRIPTION, "What this scratchpad is for, in a line.", required = false),
                ),
            ),
            ToolSpec(
                name = APPEND,
                description = "Adds text to the end of a scratchpad, without resending what is already there. " +
                    "The cheap way to grow a file a piece at a time.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "Which scratchpad to add to.", required = true),
                    ToolParameterSpec(TEXT, "The text to add at the end.", required = true),
                ),
            ),
            ToolSpec(
                name = REPLACE,
                description = "Replaces one piece of text inside a scratchpad, in place - the cheap way to " +
                    "edit a large file. The old text must appear exactly once; give a longer piece if it does not.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "Which scratchpad to edit.", required = true),
                    ToolParameterSpec(OLD, "The exact text to replace.", required = true),
                    ToolParameterSpec(NEW, "What to put in its place.", required = true),
                ),
            ),
            ToolSpec(
                name = SEARCH,
                description = "Finds where a piece of text appears across your scratchpads - which file and " +
                    "which line - so you can go to a fragment without reading each file whole.",
                parameters = listOf(
                    ToolParameterSpec(QUERY, "The text to look for; matched anywhere, ignoring case.", required = true),
                ),
            ),
            ToolSpec(
                name = SHARE,
                description = "Lets the agents you ask in this conversation read and add to a scratchpad, or " +
                    "stops them. Share a file when a subagent should work on the same document; only the file's " +
                    "own session can share it.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "Which scratchpad to share.", required = true),
                    ToolParameterSpec(SHARED, "true to share it with the agents you ask, false to stop. Default true.", required = false),
                ),
            ),
            ToolSpec(
                name = DELETE,
                description = "Removes a scratchpad and everything in it. Only the file's own session may.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "Which scratchpad to remove.", required = true),
                ),
            ),
        )

        override fun handles(name: String): Boolean = name in NAMES

        override fun run(call: ToolCall): String {
            val args = runCatching { mapper.readTree(call.arguments) }.getOrNull() ?: mapper.createObjectNode()
            return when (call.name) {
                LIST -> listed()
                READ -> read(args)
                WRITE -> written(args)
                APPEND -> appended(args)
                REPLACE -> replaced(args)
                SEARCH -> searched(args)
                SHARE -> shared(args)
                DELETE -> deleted(args)
                else -> refusal("There is no scratchpad tool called ${call.name}.")
            }
        }

        private fun listed(): String {
            val held = pads.list(session).map {
                mapOf(
                    "name" to it.name,
                    "description" to it.description,
                    "bytes" to it.bytes,
                    "shared" to it.shared,
                    "ownedHere" to (it.sessionId == session),
                )
            }
            return mapper.writeValueAsString(mapOf("scratchpads" to held))
        }

        private fun read(args: JsonNode): String {
            val name = text(args, NAME) ?: return refusal("Say which scratchpad to read.")
            val pad = pads.find(session, name) ?: return refusal("There is no scratchpad named \"$name\" in this session.")
            val from = int(args, FROM)?.coerceAtLeast(0) ?: 0
            val length = int(args, LENGTH)
            val whole = pad.content
            val slice = when {
                from >= whole.length -> ""
                length == null -> whole.substring(from)
                else -> whole.substring(from, (from + length).coerceAtMost(whole.length))
            }
            return mapper.writeValueAsString(
                mapOf(
                    "name" to pad.name,
                    "description" to pad.description,
                    "totalChars" to whole.length,
                    "from" to from,
                    "content" to slice,
                ),
            )
        }

        private fun written(args: JsonNode): String {
            val name = text(args, NAME) ?: return refusal("Say the scratchpad's name.")
            val content = text(args, CONTENT) ?: return refusal("Say what to write.")
            val description = text(args, DESCRIPTION)
            val existing = pads.find(session, name)
            val result = if (existing == null || existing.sessionId != session) {
                pads.create(session, name, description, content)
            } else {
                val written = pads.write(session, name, content)
                if (written is ScratchpadResult.Ok && description != null) {
                    pads.describe(session, name, description)
                } else {
                    written
                }
            }
            return report(result, "written")
        }

        private fun appended(args: JsonNode): String {
            val name = text(args, NAME) ?: return refusal("Say which scratchpad to add to.")
            val add = text(args, TEXT) ?: return refusal("Say what to add.")
            return report(pads.append(session, name, add), "appended")
        }

        private fun replaced(args: JsonNode): String {
            val name = text(args, NAME) ?: return refusal("Say which scratchpad to edit.")
            val old = text(args, OLD) ?: return refusal("Say the text to replace.")
            val new = text(args, NEW) ?: ""
            return report(pads.replace(session, name, old, new), "replaced")
        }

        private fun searched(args: JsonNode): String {
            val query = text(args, QUERY) ?: return refusal("Say what to look for.")
            val hits = pads.search(session, query).map { mapOf("name" to it.name, "line" to it.line, "text" to it.text) }
            return mapper.writeValueAsString(mapOf("hits" to hits))
        }

        private fun shared(args: JsonNode): String {
            val name = text(args, NAME) ?: return refusal("Say which scratchpad to share.")
            val on = bool(args, SHARED) ?: true
            return report(pads.share(session, name, on), if (on) "shared" else "unshared")
        }

        private fun deleted(args: JsonNode): String {
            val name = text(args, NAME) ?: return refusal("Say which scratchpad to remove.")
            return report(pads.delete(session, name), "deleted")
        }

        private fun report(result: ScratchpadResult, verb: String): String = when (result) {
            is ScratchpadResult.Ok -> mapper.writeValueAsString(
                mapOf(
                    verb to true,
                    "name" to result.pad.name,
                    "bytes" to result.pad.bytes,
                    "shared" to result.pad.shared,
                ),
            )
            is ScratchpadResult.No -> refusal(result.why)
        }

        private fun text(node: JsonNode, field: String): String? =
            node.path(field).takeIf { it.isTextual }?.stringValue()?.takeIf { it.isNotEmpty() }

        private fun int(node: JsonNode, field: String): Int? =
            node.path(field).let { if (it.isNumber) it.asInt() else it.takeIf { n -> n.isTextual }?.stringValue()?.toIntOrNull() }

        private fun bool(node: JsonNode, field: String): Boolean? =
            node.path(field).let { if (it.isBoolean) it.asBoolean() else it.takeIf { n -> n.isTextual }?.stringValue()?.toBooleanStrictOrNull() }

        private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))
    }

    companion object {
        const val LIST = "scratchpad_list"
        const val READ = "scratchpad_read"
        const val WRITE = "scratchpad_write"
        const val APPEND = "scratchpad_append"
        const val REPLACE = "scratchpad_replace"
        const val SEARCH = "scratchpad_search"
        const val SHARE = "scratchpad_share"
        const val DELETE = "scratchpad_delete"

        val NAMES = setOf(LIST, READ, WRITE, APPEND, REPLACE, SEARCH, SHARE, DELETE)

        const val NAME = "name"
        const val CONTENT = "content"
        const val TEXT = "text"
        const val DESCRIPTION = "description"
        const val FROM = "from"
        const val LENGTH = "length"
        const val OLD = "old"
        const val NEW = "new"
        const val QUERY = "query"
        const val SHARED = "shared"
    }
}
