package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.Delivery
import io.mszymanski.orknux.connector.connection.LinkedMessage
import io.mszymanski.orknux.connector.connection.Mention
import io.mszymanski.orknux.connector.connection.OutgoingMessages
import io.mszymanski.orknux.connector.connection.Reaction
import io.mszymanski.orknux.connector.connection.SlackMentions
import io.mszymanski.orknux.connector.connection.SlackMessages
import io.mszymanski.orknux.connector.connection.SlackReactions
import io.mszymanski.orknux.connector.connection.SlackSearch
import io.mszymanski.orknux.connector.connection.SlackDirectory
import io.mszymanski.orknux.connector.connection.SlackSearched
import io.mszymanski.orknux.connector.connection.SlackTargetKind
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.connection.SlackThreads
import io.mszymanski.orknux.connector.connection.SlackUser
import io.mszymanski.orknux.connector.connection.SlackUsers
import io.mszymanski.orknux.connector.connection.Thread
import io.mszymanski.orknux.workflow.script.PluginCapability
import io.mszymanski.orknux.workflow.script.PluginHost
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * What the server does on a plugin's behalf.
 *
 * The far side of the one door a plugin has out of its sandbox. It is here
 * rather than in the execution module because this is where the connections and
 * their credentials are, and the execution module goes on knowing nothing about
 * Slack — it knows only that a capability was granted and that something answers
 * it.
 *
 * **Everything crosses as JSON, both ways.** A plugin handed a live object could
 * walk from it to a class loader; a plugin handed a string can read the string.
 * That is what keeps this a door rather than a hole, and it is why nothing here
 * returns anything richer than text.
 *
 * A refusal is data rather than an exception. A plugin asking about a connection
 * that has been deleted needs to be able to say so — "that connection is gone"
 * is a sentence a workflow can act on, and an exception here would come out as a
 * plugin that failed for reasons nobody can read. Issue #316.
 */
@Component
class SlackPluginHost(
    private val threads: SlackThreads,
    private val messages: OutgoingMessages,
    private val reactions: SlackReactions,
    private val linked: SlackMessages,
    private val users: SlackUsers,
    private val mentions: SlackMentions,
    private val searches: SlackSearch,
    /** The editor's own ranking of members and channels for a partial name. Issue #377. */
    private val directory: SlackDirectory,
    private val connections: WorkspaceConnectionService,
    private val mapper: ObjectMapper,
    /**
     * The other thing the server does on a caller's behalf; see
     * [NetworkPluginHost].
     *
     * One host object answers every capability, so this is where the second one
     * is reached from. A host per capability would mean the runner holding a
     * list and deciding which to ask, which is a decision with nothing in it.
     */
    private val network: NetworkPluginHost,
    /**
     * The third thing the server does on a caller's behalf, and the only one
     * that reaches nothing at all: see [SvgRenderer].
     */
    private val renderer: SvgRenderer,
    /**
     * And the fourth, which reaches nothing either: a page of a document,
     * drawn - see [PdfRenderer] for why something that writes a PDF needs it.
     */
    private val pdfs: PdfRenderer,
    /**
     * And the fifth: which connections the run's workspace holds, with no
     * credential among them. See [ConnectionsPluginHost]. Issue #597.
     */
    private val connectionList: ConnectionsPluginHost,
) : PluginHost {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun ask(capability: PluginCapability, argument: String, on: Long?): String = when (capability) {
        PluginCapability.SLACK_READ_THREAD -> readThread(argument, on)
        PluginCapability.SLACK_POST_MESSAGE -> postMessage(argument, on)
        PluginCapability.SLACK_ADD_REACTION -> addReaction(argument, on)
        PluginCapability.SLACK_READ_MESSAGE -> readMessage(argument, on)
        PluginCapability.SLACK_READ_USER -> readUser(argument, on)
        PluginCapability.SLACK_MENTION -> mention(argument, on)
        PluginCapability.SLACK_SEARCH -> search(argument, on)
        PluginCapability.SLACK_SUGGEST -> suggest(argument, on)
        /*
         * No workspace scoping, and the reason is not that it was forgotten: a
         * request names an address rather than one of the workspace's own
         * things, so there is nothing here for a workspace to be the boundary
         * of. What bounds this is the proxy rules - and, for a plugin, the
         * grant as well; a workspace's own function has it without asking,
         * which is said at length on the capability and on NetworkPluginHost.
         */
        PluginCapability.NETWORK_REQUEST -> network.request(argument)

        /*
         * No workspace scoping either, and for a plainer reason than a
         * request's: what goes in is a string the caller already had and what
         * comes back is computed from it. There is no connection, no address
         * and no credential anywhere in it, so there is nothing for a
         * workspace to be the boundary of.
         */
        PluginCapability.RENDER_PNG -> renderPng(argument)

        /*
         * Its own door and its own grant. The reach is the same as the SVG
         * one's - nothing at all - but the parser is not, and an operator may
         * reasonably draw markup without handing documents to PDFBox. See the
         * note on the capability.
         */
        PluginCapability.RENDER_PDF -> renderPdf(argument)

        /*
         * Scoped like the Slack doors, and more strictly: there is no
         * connection id in the call at all, so the run's workspace is the
         * whole of what decides what is answered.
         */
        PluginCapability.CONNECTIONS_QUERY -> connectionList.query(argument, on)
    }

    /**
     * `[base64, page, width]`, as the contract's helper sends it.
     *
     * The document arrives as base64 for the reason the picture leaves as
     * base64: what crosses this door is JSON, and bytes are not JSON. A plugin
     * that has just made a PDF already holds it in that shape.
     *
     * The answer carries more than the SVG door's does - the picture's size,
     * and the document's page count. `RENDERING.md` proposed reusing
     * `OrknuxDrawnPng` unchanged; what that misses is that the caller here is
     * checking a layout, and "how wide did it come out" and "is there a page
     * two" are the two questions it has. A refusal can carry the page count
     * for the caller that asked past the end, but not for the one that asked
     * correctly and now wants to look at the rest.
     */
    private fun renderPdf(argument: String): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")

        /*
         * Two calls through one door, told apart by the shape of what arrives.
         *
         * `pngFromPdf` has always sent an array; `htmlFromPdf` sends an object
         * saying so. They share a grant because they share the thing the grant
         * is about - handing an untrusted document to PDFBox - and a second
         * capability would ask an administrator to weigh a distinction that is
         * not there.
         */
        if (given.isObject && given.path("op").takeIf { it.isTextual }?.asString() == "html") {
            return readPdf(given)
        }

        if (!given.isArray || given.isEmpty) return refusal("that call takes a pdf")

        val base64 = given.get(0)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the pdf has to be base64 text")
        val pdf = runCatching { java.util.Base64.getDecoder().decode(base64.trim()) }.getOrNull()
            ?: return refusal("that is not a pdf: the bytes are not valid base64")

        // Absent and null mean the first page; a number out of range is
        // refused by name rather than rounded into one, as the contract asks.
        val asked = given.get(1)
        val page = when {
            asked == null || asked.isNull -> 1
            asked.isNumber -> asked.asInt()
            asked.isTextual -> asked.asString().trim().toIntOrNull() ?: 1
            else -> 1
        }

        val wide = given.get(2)
        val width = when {
            wide == null || wide.isNull -> null
            wide.isNumber -> wide.asInt().takeIf { it > 0 }
            wide.isTextual -> wide.asString().trim().toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }

        return when (val drawn = pdfs.png(pdf, page, width)) {
            is PdfRenderer.Drawing.Refused -> refusal(drawn.reason)
            is PdfRenderer.Drawing.Drawn -> mapper.writeValueAsString(
                mapOf(
                    "base64" to java.util.Base64.getEncoder().encodeToString(drawn.png),
                    "bytes" to drawn.png.size,
                    // What a caller checking a layout wants next: how large the
                    // picture came out, and whether there is a page two.
                    "width" to drawn.width,
                    "height" to drawn.height,
                    "pages" to drawn.pages,
                ),
            )
        }
    }

    /**
     * `{op, pdf, from, to}`, as the contract's helper sends it.
     *
     * The document as base64 for the reason every door here takes it that way:
     * what crosses is JSON, and bytes are not JSON.
     *
     * The answer carries the range that was read as well as the document's own
     * page count, because the caller that asked for "the beginning" needs to
     * know where the beginning ended - a long document is refused by size, and
     * the way out of that refusal is to ask for a range.
     */
    private fun readPdf(given: tools.jackson.databind.JsonNode): String {
        val base64 = given.path("pdf").takeIf { it.isTextual }?.asString()
            ?: return refusal("the pdf has to be base64 text")
        val pdf = runCatching { java.util.Base64.getDecoder().decode(base64.trim()) }.getOrNull()
            ?: return refusal("that is not a pdf: the bytes are not valid base64")

        fun page(field: String): Int? = given.path(field).let { asked ->
            when {
                asked.isNumber -> asked.asInt()
                asked.isTextual -> asked.asString().trim().toIntOrNull()
                else -> null
            }
        }

        return when (val read = pdfs.html(pdf, page("from"), page("to"))) {
            is PdfRenderer.Reading.Refused -> refusal(read.reason)
            is PdfRenderer.Reading.Read -> mapper.writeValueAsString(
                mapOf(
                    "html" to read.html,
                    "pages" to read.pages,
                    "from" to read.from,
                    "to" to read.to,
                    "characters" to read.characters,
                ),
            )
        }
    }

    /**
     * `[svg, width]`, as the contract's helper sends it.
     *
     * Answered as base64, because what crosses this door is JSON and bytes are
     * not JSON. That is the one place the caller has to encode - and it does
     * not have to *retype* it: a plugin hands the answer straight to whatever
     * takes bytes, and the model never sees it.
     */
    private fun renderPng(argument: String): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")

        if (!given.isArray || given.isEmpty) return refusal("that call takes an svg")

        val svg = given.get(0)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the svg has to be text")

        // Absent, null and 0 all mean "the size the document declares".
        val asked = given.get(1)
        val width = when {
            asked == null || asked.isNull -> null
            asked.isNumber -> asked.asInt().takeIf { it > 0 }
            asked.isTextual -> asked.asString().trim().toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }

        return when (val drawn = renderer.png(svg, width)) {
            is SvgRenderer.Drawing.Refused -> refusal(drawn.reason)
            is SvgRenderer.Drawing.Drawn -> mapper.writeValueAsString(
                // The size as well as the bytes, so a plugin holding a blank
                // or a giant can tell which it is holding. `pngFromPdf`
                // answers this way and there was no reason these differed.
                mapOf(
                    "base64" to java.util.Base64.getEncoder().encodeToString(drawn.png),
                    "bytes" to drawn.png.size,
                    "width" to drawn.width,
                    "height" to drawn.height,
                ),
            )
        }
    }

    /**
     * `[connectionId, channel, threadTs, limit]`, as the contract's helper sends
     * it.
     *
     * Read defensively and refused in words. This is the boundary a plugin
     * writes to, so what arrives is whatever somebody's JavaScript passed, and
     * every shape of wrong has to come back as something they can act on.
     */
    private fun readThread(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 3) {
            return refusal("that call takes a connection, a channel and a thread")
        }

        /*
         * A number or a string of one, because both are what actually arrive.
         *
         * A trigger publishes its connection as `"7"` - everything on a payload
         * is text, since that is what a payload is - so a function handed
         * `trigger.connection` and passing it straight on was refused for giving
         * the very thing the product told it to give. Accepting only the shape
         * the plugin template happens to declare would have made the documented
         * path the one that does not work.
         */
        val connectionId = given.get(0)?.let { held ->
            when {
                held.isNumber -> held.asLong()
                held.isTextual -> held.asString().trim().toLongOrNull()
                else -> null
            }
        } ?: return refusal("the first argument has to be a Slack connection")
        val channel = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a channel")
        val threadTs = given.get(2)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the third argument has to be a thread")
        val limit = given.get(3)?.takeIf { it.isNumber }?.asInt()

        return when (val read = threads.read(connectionId, channel, threadTs, limit ?: DEFAULT_LIMIT, on)) {
            is Thread.Read -> {
                val answer = mapper.createObjectNode()
                val messages = answer.putArray("messages")
                read.messages.forEach { held ->
                    val line = messages.addObject()
                        .put("ts", held.ts)
                        .put("user", held.user)
                        .put("text", held.text)
                        .put("parent", held.parent)
                    /*
                     * What came with it, so an agent reading a thread can see
                     * that a file is in it - and has the id that fetches one.
                     * Always an array, empty where nothing was attached: a
                     * script iterating this needs no branch for "has no files".
                     */
                    val files = line.putArray("files")
                    held.files.forEach { file ->
                        files.addObject()
                            .put("id", file.id)
                            .put("name", file.name)
                            .put("mimetype", file.mimetype)
                            .put("size", file.size)
                    }
                }
                answer.put("replies", read.replies)
                mapper.writeValueAsString(answer)
            }

            // Both refusals, said in the words they came with. A plugin deciding
            // what to do about "not_in_channel" needs to be told that and not a
            // sentence somebody rewrote.
            is Thread.NotPossible -> refusal(read.reason)
            is Thread.Refused -> refusal(read.reason)
        }.also { log.debug("A plugin read thread {} in {} on connection {}", threadTs, channel, connectionId) }
    }

    /**
     * `[connectionId, channel, text, threadTs?]`, and answers `{ ts }` where it
     * sent or `{ error }` where it did not.
     *
     * `ts` is the message's own timestamp, which is what a reply threads onto and
     * what a reaction hangs on - so a function that posts and then reacts has the
     * one value it needs from the first call.
     */
    private fun postMessage(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 3) {
            return refusal("that call takes a connection, a channel and some text")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val channel = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a channel")
        val text = given.get(2)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the third argument has to be the message text")
        val threadTs = given.get(3)?.takeIf { it.isTextual }?.asString()

        return when (val sent = messages.send(connectionId, channel, text, threadTs, on)) {
            is Delivery.Sent -> mapper.writeValueAsString(
                mapper.createObjectNode().put("channel", sent.channel).put("ts", sent.ts),
            )
            is Delivery.NotPossible -> refusal(sent.reason)
            is Delivery.Refused -> refusal(sent.reason)
        }.also { log.debug("A script posted to {} on connection {}", channel, connectionId) }
    }

    /**
     * `[connectionId, channel, ts, emoji]`, and answers `{ ok: true }` where it
     * reacted or `{ error }` where it did not.
     */
    private fun addReaction(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 4) {
            return refusal("that call takes a connection, a channel, a message and an emoji")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val channel = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a channel")
        val ts = given.get(2)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the third argument has to be a message timestamp")
        val emoji = given.get(3)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the fourth argument has to be an emoji name")

        return when (val reacted = reactions.add(connectionId, channel, ts, emoji, on)) {
            is Reaction.Added -> mapper.writeValueAsString(mapper.createObjectNode().put("ok", true))
            is Reaction.NotPossible -> refusal(reacted.reason)
            is Reaction.Refused -> refusal(reacted.reason)
        }.also { log.debug("A script reacted on {} on connection {}", channel, connectionId) }
    }

    /**
     * `[connectionId, link]`, and answers the message the permalink points at,
     * or `{ error }` where it could not be read.
     */
    private fun readMessage(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 2) {
            return refusal("that call takes a connection and a message link")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val link = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a message link")

        return when (val read = linked.read(connectionId, link, on)) {
            is LinkedMessage.Found -> mapper.writeValueAsString(
                mapper.createObjectNode()
                    .put("channel", read.channel)
                    .put("ts", read.ts)
                    .put("user", read.user)
                    .put("text", read.text)
                    .put("threadTs", read.threadTs),
            )
            is LinkedMessage.NotPossible -> refusal(read.reason)
            is LinkedMessage.Refused -> refusal(read.reason)
        }.also { log.debug("A script followed a message link on connection {}", connectionId) }
    }

    /**
     * `[connectionId, userId]`, and answers who that is, or `{ error }`. The id
     * may arrive wrapped as the mention notation it came from.
     */
    private fun readUser(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 2) {
            return refusal("that call takes a connection and a user id")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val userId = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a user id")

        return when (val found = users.info(connectionId, userId, on)) {
            is SlackUser.Found -> mapper.writeValueAsString(
                mapper.createObjectNode()
                    .put("id", found.id)
                    .put("name", found.name)
                    .put("realName", found.realName)
                    .put("displayName", found.displayName)
                    .put("bot", found.bot),
            )
            is SlackUser.NotPossible -> refusal(found.reason)
            is SlackUser.Refused -> refusal(found.reason)
        }.also { log.debug("A script looked up a user on connection {}", connectionId) }
    }

    /**
     * `[connectionId, name]`, and answers `{ mention, id, label }` - the text to
     * put in a message - or `{ error }`. A name nothing answers to is an error in
     * words rather than a guess, because a mention that pings the wrong person is
     * worse than one that asks again.
     */
    private fun mention(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 2) {
            return refusal("that call takes a connection and a name")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val name = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a name")

        return when (val resolved = mentions.of(connectionId, name, on)) {
            is Mention.Resolved -> mapper.writeValueAsString(
                mapper.createObjectNode()
                    .put("mention", resolved.mention)
                    .put("id", resolved.id)
                    .put("label", resolved.label),
            )
            is Mention.NobodyCalled -> refusal("nobody in that Slack answers to \"${resolved.name}\"")
            is Mention.NotPossible -> refusal(resolved.reason)
            is Mention.Refused -> refusal(resolved.reason)
        }.also { log.debug("A script resolved a mention on connection {}", connectionId) }
    }

    /** `[connectionId, query, limit]` - searched in Slack's own syntax. */
    private fun search(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 2) {
            return refusal("that call takes a connection and a query")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val query = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a query")
        val limit = given.get(2)?.takeIf { it.isNumber }?.asInt()

        return when (val found = searches.search(connectionId, query, limit ?: DEFAULT_LIMIT, on)) {
            is SlackSearched.Found -> {
                val answer = mapper.createObjectNode()
                answer.put("total", found.total)
                val matches = answer.putArray("matches")
                found.matches.forEach { match ->
                    matches.addObject()
                        .put("channel", match.channel)
                        .put("channelName", match.channelName)
                        .put("ts", match.ts)
                        .put("user", match.user)
                        .put("text", match.text)
                        .put("permalink", match.permalink)
                }
                mapper.writeValueAsString(answer)
            }
            is SlackSearched.NotPossible -> refusal(found.reason)
            is SlackSearched.Refused -> refusal(found.reason)
        }.also { log.debug("A script searched Slack on connection {}", connectionId) }
    }

    /**
     * A number or a string of one, because both are what actually arrive: a
     * trigger publishes its connection as `"7"`, since everything on a payload is
     * text, and a plugin declares it as a number. See [readThread].
     */
    /**
     * `[connectionId, typed, kind?, limit?]`, and answers what the workflow
     * editor's target box would: `{ outcome, message, complete, matches: [{ id,
     * name, kind, realName }] }`. Issue #377.
     *
     * Through the directory the editor uses, so a plugin's `SlackUser` type
     * offers exactly what the Slack target box offers - one ranking, one idea
     * of what a partial handle matches. The connection has to be the asking
     * workspace's own, checked here because the directory does not take a
     * workspace: a variable cannot be pointed at another workspace's Slack.
     */
    private fun suggest(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 2) {
            return refusal("that call takes a connection and what was typed")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val typed = given.get(1)?.takeIf { it.isTextual }?.asString() ?: ""
        val kind = given.get(2)?.takeIf { it.isTextual }?.asString()?.let { asked ->
            SlackTargetKind.entries.firstOrNull { it.name.equals(asked, ignoreCase = true) }
                ?: return refusal("the kind has to be CHANNEL or USER")
        }
        val limit = given.get(3)?.takeIf { it.isNumber }?.asInt()?.coerceIn(1, MOST_SUGGESTED) ?: MOST_SUGGESTED

        val connection = connections.workspaceConnection(connectionId)
        if (connection == null || (on != null && connection.workspaceId != on)) {
            return refusal("the connection it would ask through has been deleted")
        }

        val offered = directory.suggest(connectionId, kind, typed)
        val answer = mapper.createObjectNode()
        answer.put("outcome", offered.outcome.name)
        answer.put("message", offered.message)
        answer.put("complete", offered.complete && offered.matches.size <= limit)
        val matches = answer.putArray("matches")
        offered.matches.take(limit).forEach { one ->
            matches.addObject()
                .put("id", one.id)
                .put("name", one.name)
                .put("kind", one.kind.name)
                .put("realName", one.realName)
        }
        return mapper.writeValueAsString(answer)
            .also { log.debug("A script asked for Slack suggestions on connection {}", connectionId) }
    }

    private fun connectionOf(node: tools.jackson.databind.JsonNode?): Long? = node?.let { held ->
        when {
            held.isNumber -> held.asLong()
            held.isTextual -> held.asString().trim().toLongOrNull()
            else -> null
        }
    }

    private fun refusal(why: String): String =
        mapper.writeValueAsString(mapper.createObjectNode().put("error", why))

    private companion object {
        /** A picker is a picker, not a directory. */
        const val MOST_SUGGESTED = 50

        /** What the connector uses when nothing says otherwise; repeated so the door has its own answer. */
        const val DEFAULT_LIMIT = 50
    }
}
