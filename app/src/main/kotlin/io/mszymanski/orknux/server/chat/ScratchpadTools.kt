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
    /**
     * Where a pad's content is put when the agent wants a key for it rather
     * than the text. Issue #417: an upload, a Slack post, a mail all take a
     * key, and a model that had written a page into a pad could only get it to
     * one of them by reading the pad and typing it back - which is the very
     * thing the output cap cuts off.
     */
    private val scratch: io.mszymanski.orknux.server.llm.LlmSessionStore,
    /**
     * Putting several of these files into one. Issue #467.
     *
     * Lent with the pads rather than beside them: it reads what the session
     * holds and writes back into the same store, so it exists exactly where
     * they do - in a session - and an agent that has no working files has
     * nothing to zip.
     */
    private val zips: ZipTools,
    /** Whether an HTML page names pictures by key, which is said when it is kept. Issue #545. */
    private val pictures: io.mszymanski.orknux.server.embedded.SessionPictures,
    private val blocks: io.mszymanski.orknux.server.embedded.PageBlocks,
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
            append("result beats composing the whole of it in one answer. ")
            /*
             * And the habit that matters most, said where the pads are
             * introduced. Issue #486: an agent read a 5000-character pad and
             * then wrote 6671 characters back to change part of it - the whole
             * document through the model twice, most of a turn's budget spent
             * retyping text nobody touched, and the place a truncated write
             * loses work. Coding agents edit a file in place; so should this.
             */
            append("Edit a pad in place: once it exists, change it with ").append(REPLACE)
            append(" or add to it with ").append(APPEND).append(", the way you would edit a file. ")
            append("Writing the whole document again is for making one, or for a rewrite that really is total - ")
            append("resending a page to change a paragraph costs the whole page twice and is where a long ")
            append("file gets cut off. ")
            append("Your pads are this conversation's: an agent you ask sees them and writes to the same ")
            append("files, and what it makes is here for you when it answers. Nothing has to be shared. ")
            append("To hand a pad to a tool that takes a key - an upload, a message, a mail - call ")
            append(KEEP).append(" and pass the key it answers with, rather than reading the pad and typing ")
            append("its content into the call.")
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
                description = "Creates a scratchpad. On one that already exists this replaces the whole " +
                    "content, which is rarely what you want: to change part of a pad use $REPLACE, and to add " +
                    "to the end use $APPEND. Sending the whole document again to change a paragraph costs the " +
                    "page twice over and is where a long file gets cut off - edit it in place instead, the way " +
                    "you would edit a file. You may set $DESCRIPTION to say what the file is for.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "The scratchpad's name, like a filename.", required = true),
                    ToolParameterSpec(CONTENT, "The whole content to write.", required = true),
                    ToolParameterSpec(DESCRIPTION, "What this scratchpad is for, in a line.", required = false),
                    ToolParameterSpec(
                        CONTENT_TYPE,
                        "Set this to keep a file rather than text - \"image/png\", \"application/pdf\" - and " +
                            "send the file's base64 as the content. A pad kept this way sits beside your " +
                            "text files, goes into an archive under its own name, and is never read back at " +
                            "you as base64. Leave it out for anything you would read.",
                        required = false,
                    ),
                ),
            ),
            ToolSpec(
                name = APPEND,
                description = "Adds text to the end of a scratchpad, without resending what is already there. " +
                    "The cheap way to grow a file a piece at a time.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "Which scratchpad to add to.", required = true),
                    ToolParameterSpec(TEXT, "The text to add at the end. Leave out when passing $KEY.", required = false),
                    ToolParameterSpec(
                        KEY,
                        "A key something handed you - a drawn SVG, a kept pad, an answer - whose text is added " +
                            "instead of $TEXT, so a drawing goes into a page without being typed through you.",
                        required = false,
                    ),
                ),
            ),
            ToolSpec(
                name = REPLACE,
                description = "Replaces one piece of text inside a scratchpad, in place - the cheap way to " +
                    "edit a large file. The old text must appear exactly once; give a longer piece if it does not.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "Which scratchpad to edit.", required = true),
                    ToolParameterSpec(OLD, "The exact text to replace.", required = true),
                    ToolParameterSpec(NEW, "What to put in its place. Leave out when passing $NEW_KEY.", required = false),
                    ToolParameterSpec(
                        NEW_KEY,
                        "A key something handed you - a drawn SVG, a kept pad, an answer - whose text goes in " +
                            "its place instead of $NEW, so a long piece is not typed through you.",
                        required = false,
                    ),
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
/* scratchpad_share is gone: see NAMES. Issue #498. */
            ToolSpec(
                name = KEEP,
                description = "Puts a scratchpad's content into this session's store and answers with the " +
                    "key it is under. Use it to hand a file you have written to a tool that takes a key - " +
                    "an upload, a message, a mail - instead of reading the pad and typing its content back, " +
                    "which is what the output limit cuts off. The pad is left as it is.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "Which scratchpad to keep.", required = true),
                    ToolParameterSpec(
                        KEY,
                        "What to call it in the store. Left out, the pad's own name is used.",
                        required = false,
                    ),
                ),
            ),
            ToolSpec(
                name = DELETE,
                description = "Removes a scratchpad and everything in it. Only the file's own session may.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "Which scratchpad to remove.", required = true),
                ),
            ),
        ) + zips.descriptors().map { one ->
            // The archive tool, drawn from its own descriptor so its words live
            // with its code. Issue #467.
            ToolSpec(
                name = one.name,
                description = one.description,
                parameters = one.parameters.map { ToolParameterSpec(it.name, it.description, it.required) },
            )
        }

        override fun handles(name: String): Boolean = name in NAMES || zips.handles(name)

        override fun run(call: ToolCall): String {
            val args = runCatching { mapper.readTree(call.arguments) }.getOrNull() ?: mapper.createObjectNode()
            return when (call.name) {
                LIST -> listed()
                READ -> read(args)
                WRITE -> written(args)
                APPEND -> appended(args)
                REPLACE -> replaced(args)
                SEARCH -> searched(args)
                KEEP -> kept(args)
                DELETE -> deleted(args)
                else -> if (zips.handles(call.name)) {
                    zips.run(call.arguments, session)
                } else {
                    refusal("There is no scratchpad tool called ${call.name}.")
                }
            }
        }

        /**
         * A pad's content put into the session store, and the key it went under.
         * Issue #417.
         *
         * The one thing a scratchpad could not do: be handed to something else.
         * Every door that takes a lot of text - an upload, a Slack message, a
         * mail - takes a key into this store rather than the text, precisely so
         * a model never has to type a page back at the server; and a model that
         * had written that page into a pad had no way to turn it into a key. It
         * read the pad and typed it out, and the output cap cut it off, which is
         * the failure the keys exist to prevent.
         *
         * The pad is left where it is: this is a copy into the store, not a move
         * out of the file. Under the pad's own name unless the agent says
         * otherwise, so the key is one it can predict without being told.
         */
        private fun kept(args: JsonNode): String {
            val name = text(args, NAME) ?: return refusal("Say which scratchpad to keep.")
            val held = pads.find(session, name)
                ?: return refusal("You have no scratchpad called \"$name\".")
            val key = text(args, KEY)?.trim()?.ifEmpty { null } ?: name
            /*
             * A JSON-encoded *string*, which is what every reader of this store
             * expects: the sandbox parses what it reads, and an upload hands on
             * what comes out as the content itself.
             */
            // And what it is, so a reader does not guess. Issue #559.
            val kind = held.contentType?.let { io.mszymanski.orknux.workflow.script.StoredKind(it, true) } ?: io.mszymanski.orknux.workflow.script.StoredKind(textTypeOf(name), false)
            val refused = scratch.put(session, key, mapper.writeValueAsString(held.content), kind)
            if (refused != null) return refusal("That could not be kept: $refused.")
            val answer = linkedMapOf<String, Any>(
                "kept" to true, "key" to key, "name" to name, "bytes" to held.content.length,
            )
            if (held.contentType == null &&
                io.mszymanski.orknux.server.embedded.SessionPictures.isHtml(name, held.content)
            ) {
                val keyed = blocks.keyedPictures(held.content) { pictures.find(it, session) != null }
                if (keyed.isNotEmpty()) {
                    answer["warning"] = io.mszymanski.orknux.server.embedded.SessionPictures.keyedWarning(keyed)
                }
            }
            return mapper.writeValueAsString(answer)
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
            /*
             * A pad holding bytes answers with what it is, not with the bytes.
             * Issue #490: a megabyte of base64 spends the turn and tells the
             * model nothing it can act on - what it can act on is the key,
             * which every tool that sends or packs a file takes.
             */
            pad.contentType?.let { type ->
                val key = KEPT_PREFIX + pad.name
                // JSON, as every other writer into this store does: what reads
                // a key parses it. Issue #493.
                scratch.put(session, key, mapper.writeValueAsString(pad.content), io.mszymanski.orknux.workflow.script.StoredKind(type, true))
                return mapper.writeValueAsString(
                    mapOf(
                        "name" to pad.name,
                        "description" to pad.description,
                        "contentType" to type,
                        "bytes" to pad.content.length * 3 / 4,
                        "contentKey" to key,
                        "note" to "This scratchpad holds a file, not text. Pass contentKey to whatever " +
                            "sends, uploads or packs it; zip_files takes the scratchpad's name directly.",
                    ),
                )
            }
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
            /*
             * A file rather than text, where the call says so. Issue #490: a
             * report's pictures belong beside its pages, not in a second place
             * the agent has to remember, and a pad that says what it holds is
             * what lets the read tool, the archive and the screen each do the
             * right thing with it.
             */
            val type = text(args, CONTENT_TYPE)?.trim()?.ifEmpty { null }
            /*
             * A file this session can see is a file this session writes.
             * Issue #497.
             *
             * This used to create a new pad whenever the name resolved to an
             * ancestor's shared file rather than to one of its own - so a
             * subagent asked to rewrite the conversation's stylesheet wrote a
             * stylesheet of its own, said it had done the work, and the
             * conversation went on reading the original. Nothing anywhere said
             * a fork had happened; the bytes were simply in the wrong session.
             *
             * Append and replace have always written through, because they go
             * by the edit path, which resolves a shared ancestor's pad and
             * saves it. Write forking alone made the operation an agent reaches
             * for first the one that quietly did something else.
             *
             * Sharing is what permits it, and sharing is opt-in: a pad is
             * visible below only where the session that owns it said so, which
             * is exactly the statement "the sessions under me may use this
             * file".
             */
            val existing = pads.find(session, name)
            val result = if (existing == null) {
                pads.create(session, name, description, content, type)
            } else {
                val written = pads.write(session, name, content)
                if (written is ScratchpadResult.Ok && description != null) {
                    pads.describe(session, name, description)
                } else {
                    written
                }
            }
            /*
             * And the sweep, where this one was a file. Issue #491: the oldest
             * go once the session is over its budget, and the answer says which
             * - an agent told afterwards is one that packed an archive around a
             * file that is no longer there.
             */
            if (type == null) return report(result, "written")
            val swept = pads.sweepFiles(session)
            if (swept.isEmpty()) return report(result, "written")
            return mapper.writeValueAsString(
                mapOf(
                    "written" to true,
                    "name" to name,
                    "contentType" to type,
                    "removed" to swept,
                    "note" to "This session's files went over their budget, so the oldest were removed. " +
                        "Send or pack anything you still need rather than leaving it here.",
                ),
            )
        }

        private fun appended(args: JsonNode): String {
            val name = text(args, NAME) ?: return refusal("Say which scratchpad to add to.")
            // Or from a key, the way a drawing reaches an HTML page. Issue #555.
            val add = text(args, KEY)?.trim()?.ifEmpty { null }?.let { key -> fromKey(key) ?: return keyRefusal(key) }
                ?: text(args, TEXT) ?: return refusal("Say what to add, or the $KEY to add from.")
            return report(pads.append(session, name, add), "appended")
        }

        private fun replaced(args: JsonNode): String {
            val name = text(args, NAME) ?: return refusal("Say which scratchpad to edit.")
            val old = text(args, OLD) ?: return refusal("Say the text to replace.")
            /*
             * Or from a key. Issue #550: an SVG a drawing tool kept is fifteen
             * thousand characters, and putting it into a page meant typing all
             * of it back - which the output cap cuts off.
             */
            val new = text(args, NEW_KEY)?.trim()?.ifEmpty { null }?.let { key -> fromKey(key) ?: return keyRefusal(key) }
                ?: text(args, NEW) ?: ""
            return report(pads.replace(session, name, old, new), "replaced")
        }

        private fun fromKey(key: String): String? = scratch.get(session, key)?.let(::keptText)

        private fun keyRefusal(key: String): String =
            if (scratch.get(session, key) == null) {
                refusal("Nothing in this session is kept under \"$key\".")
            } else {
                refusal(
                    "\"$key\" holds a picture or a file that is not text, so it cannot go into a page as text. " +
                        "Name it in an <img src> for pdf_fromHtml, or send it beside the page.",
                )
            }

        /**
         * What a key holds, as text: plain text as it is, and base64 that
         * decodes to markup - a kept SVG - decoded. Null for anything binary.
         */
        /** The type a text pad's name says it is. Issue #559. */
        private fun textTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "md", "markdown" -> "text/markdown"
            "svg" -> "image/svg+xml"
            "xml" -> "application/xml"
            "js", "mjs" -> "text/javascript"
            else -> "text/plain"
        }

        private fun keptText(held: String): String? {
            val value = runCatching { mapper.readTree(held) }.getOrNull()?.takeIf { it.isTextual }?.stringValue() ?: held
            val decoded = runCatching { java.util.Base64.getDecoder().decode(value.trim()) }.getOrNull()
                ?: return value
            val text = runCatching {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(decoded)).toString()
            }.getOrNull()
            if (text != null && text.trimStart().startsWith("<")) return text
            // Binary only where it is long enough to be a file; a short word can happen to be valid base64.
            return if (text == null && value.length >= BINARY_AT_LEAST) null else value
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
                buildMap {
                    put(verb, true)
                    put("name", result.pad.name)
                    put("bytes", result.pad.bytes)
                    put("shared", result.pad.shared)
                    /*
                     * Whose file it is. Issue #497: an agent that has just
                     * written a file belonging to the conversation above it
                     * should know that is where the change landed, and one
                     * that expected its own copy should find out here rather
                     * than from somebody reading the old version.
                     */
                    if (!result.ownedHere) {
                        put("ownedHere", false)
                        put(
                            "note",
                            "This file belongs to the conversation that started you, which shared it. " +
                                "The change is there too, which is the point of a shared file.",
                        )
                    }
                },
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

        /** Putting a pad's content where a key reaches it. Issue #417. */
        const val KEEP = "scratchpad_keep"

        /*
         * What the shed answers to. `scratchpad_share` is not among them any
         * more: since #498 a conversation's files are the conversation's,
         * reachable from the session that made them and from every session it
         * asks, so there is nothing left for a share tool to switch. The
         * operation stays on the service and on the script door, where a
         * caller outside a conversation may still have a use for the flag.
         */
        val NAMES = setOf(LIST, READ, WRITE, APPEND, REPLACE, SEARCH, KEEP, DELETE)

        const val NAME = "name"
        const val CONTENT = "content"

        /** What a pad holds where it is not text, so the content is base64. Issue #490. */
        const val CONTENT_TYPE = "contentType"

        /** What a binary pad's bytes are put under when it is read. Issue #490. */
        const val KEPT_PREFIX = "pad:"
        const val TEXT = "text"
        const val DESCRIPTION = "description"
        const val FROM = "from"
        const val LENGTH = "length"
        const val OLD = "old"
        const val NEW = "new"
        const val NEW_KEY = "newKey"

        /** Below this, base64-looking text is taken as the text it is. */
        const val BINARY_AT_LEAST = 64
        const val QUERY = "query"
        const val SHARED = "shared"
        const val KEY = "key"
    }
}
