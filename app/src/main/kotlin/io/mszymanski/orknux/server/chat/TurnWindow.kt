package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.workflow.script.StoredKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

/**
 * What a provider said when a request did not fit: how many tokens it was sent
 * and how many it takes, where it said either.
 *
 * Read off the words because that is all there is - llama.cpp's
 * `request (452827 tokens) exceeds the available context size (262144 tokens)`,
 * OpenAI's `maximum context length is 128000 tokens. However, your messages
 * resulted in 130000 tokens`, Anthropic's `prompt is too long: 208310 tokens >
 * 200000 maximum`. Both numbers matter: the window is the model's real one
 * whatever its row says, and the two together are how many characters this
 * conversation turned out to cost per token, which is what a shrink has to aim
 * at - an SVG's path data is close to one character a token, prose nearer four.
 */
data class ContextOverflow(val requested: Int?, val window: Int?) {
    companion object {
        private val LLAMA = Regex("""request \((\d+) tokens\) exceeds the available context size \((\d+) tokens\)""")
        private val OPENAI_WINDOW = Regex("""maximum context length is (\d+) tokens""")
        private val OPENAI_SENT = Regex("""(?:resulted in|requested) (\d+) tokens""")
        private val ANTHROPIC = Regex("""prompt is too long: (\d+) tokens > (\d+)""")

        fun of(reason: String): ContextOverflow {
            val said = reason.lowercase()
            LLAMA.find(said)?.let { return ContextOverflow(it.int(1), it.int(2)) }
            ANTHROPIC.find(said)?.let { return ContextOverflow(it.int(1), it.int(2)) }
            return ContextOverflow(OPENAI_SENT.find(said)?.int(1), OPENAI_WINDOW.find(said)?.int(1))
        }

        private fun MatchResult.int(group: Int): Int? = groupValues[group].toIntOrNull()?.takeIf { it > 0 }
    }
}

/**
 * How much of a model's window one turn may spend, and the cut that holds a tool
 * result to it.
 *
 * Chat 981 is why this exists. An agent read a 476,668-character KiCad SVG with
 * `svg_read`, the answer went into the turn whole - 523,768 characters once it
 * was JSON, just under the 512 KB [io.mszymanski.orknux.server.agent.Bulkheads]
 * lets one result be, a wall about the server's memory and nothing to do with
 * the model - and llama.cpp refused the next request: 452,827 tokens against a
 * window of 262,144. Compaction summarised the earlier steps twice and kept the
 * newest "word for word", which was the SVG, and the turn failed.
 *
 * So a result is held to a share of the window the moment it arrives, and a
 * shrink aims at a number of characters derived from the window rather than at
 * "fewer steps".
 */
@Component
class TurnWindow(
    private val models: ModelService,
    private val tuning: Tuning,
    private val store: LlmSessionStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Windows a provider told us in a refusal, by model, for a model whose row
     * names none. Kept while the server runs rather than written to the row: the
     * row is what somebody configured, and a number that appeared there by itself
     * would be one nobody chose.
     */
    private val learned = ConcurrentHashMap<Long, Int>()

    /**
     * The model's window in tokens: its own row - which discovery fills from
     * llama.cpp's `n_ctx` - or what its provider said in a refusal, or null.
     */
    fun tokens(modelId: Long): Int? =
        runCatching { models.model(modelId)?.contextWindow }.getOrNull()?.takeIf { it > 0 } ?: learned[modelId]

    /** Remembers the window a refusal named. */
    fun learn(modelId: Long, overflow: ContextOverflow) {
        val window = overflow.window ?: return
        if (learned.put(modelId, window) != window) log.info("Model {} said its window is {} tokens", modelId, window)
    }

    /** The most characters one tool result may hand this model, or null where its window is not known. */
    fun longestResult(modelId: Long): Int? = tokens(modelId)?.let { window ->
        (window.toLong() * tuning[Tunable.TOOL_RESULT_WINDOW_PERCENT] / 100 * tuning[Tunable.TURN_WINDOW_CHARS_PER_TOKEN])
            .coerceIn(SHORTEST_CUT.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * How many characters a turn is shrunk to after the provider refused it.
     *
     * From the refusal's own numbers where it gave both - the characters sent,
     * scaled by window over tokens requested, which is this conversation's real
     * rate - and from the window where it did not; halved for every attempt
     * after the first, because a shrink that did not fit was aimed too high.
     * With neither, half of what was sent: it converges, which is the point.
     */
    fun budget(modelId: Long, sentChars: Int, overflow: ContextOverflow?, attempt: Int): Int {
        val room = 100 - tuning[Tunable.TURN_WINDOW_RESERVE_PERCENT]
        val requested = overflow?.requested
        val window = overflow?.window ?: tokens(modelId)
        val whole = when {
            requested != null && window != null && requested > window ->
                sentChars.toLong() * window / requested * room / 100
            window != null -> window.toLong() * tuning[Tunable.TURN_WINDOW_CHARS_PER_TOKEN] * room / 100
            else -> sentChars.toLong() / 2
        }
        return (whole shr attempt.coerceIn(0, 20)).coerceIn(SHORTEST_CUT.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * [said] as the model may be handed it: as it was where it fits, and cut to
     * [longest] where it does not, with a note saying how long it was and how to
     * read the rest.
     *
     * The way to read the rest is read off what the agent holds rather than
     * written here: the called tool's own paging parameters where it declares
     * any, and otherwise the whole answer kept in the session's store and the
     * scratchpad tools that can read a stored value in slices, named from their
     * declarations. A note naming a tool or a parameter the agent does not have
     * is a model told it can do something it cannot.
     *
     * JSON stays JSON where it can: the longest values are cut and the short
     * fields beside them - a `characters` count, a key - are kept.
     */
    fun fit(said: String, call: ToolCall, held: List<ToolSpec>, session: Long?, longest: Int): String {
        if (said.length <= longest) return said
        cutPages(said, call, held, longest)?.let { return it }
        val how = wayToTheRest(said, call, held, session)
        val note = { length: Int -> "… (trimmed from $length characters; $how)" }
        return cutJson(said, longest, note) ?: (said.take((longest - note(said.length).length).coerceAtLeast(0)) + note(said.length))
    }

    /**
     * What the note says after the length: how to read the rest, and for a
     * drawing how better to look at it. Never a summary - a copper layer's
     * path data has no gist - only what is there and what reaches the rest.
     */
    internal fun wayToTheRest(said: String, call: ToolCall, held: List<ToolSpec>, session: Long?): String {
        val parts = mutableListOf<String>()
        val paged = held.firstOrNull { it.name == call.name }?.let { declared -> paging(declared)?.let { declared.name to it } }
        if (paged != null) {
            parts += "read it in parts with ${paged.first}'s ${paged.second}"
        } else {
            val key = session?.let { keep(it, call, said) }
            val reader = held.firstOrNull { it.name == ScratchpadTools.READ }?.let { spec -> paging(spec)?.let { spec.name to it } }
            val appender = held.firstOrNull { it.name == ScratchpadTools.APPEND }
                ?.let { spec -> spec.parameters.firstOrNull { it.name == ScratchpadTools.KEY }?.let { spec.name to it.name } }
            val searcher = held.firstOrNull { it.name == ScratchpadTools.SEARCH }?.name
            parts += when {
                key != null && reader != null && appender != null ->
                    "the whole answer is kept under the key \"$key\": put it in a scratchpad with ${appender.first}'s " +
                        "${appender.second}, then read it in parts with ${reader.first}'s ${reader.second}" +
                        (searcher?.let { ", or search it with $it" } ?: "")
                key != null -> "the whole answer is kept under the key \"$key\" for any tool that takes a key"
                else -> "ask ${call.name} for a smaller part to read the rest"
            }
        }
        if (drawing(said)) {
            seeIt(call, held)?.let { parts += it }
            askIt(call, held)?.let { parts += it }
        }
        if (call.name == io.mszymanski.orknux.server.embedded.PdfCapability.READ_TOOL) {
            held.firstOrNull { it.name == io.mszymanski.orknux.server.embedded.PdfCapability.SEARCH_TOOL }?.let {
                parts += "to find a passage rather than read it all, search the document with ${it.name}"
            }
        }
        return parts.joinToString("; ")
    }

    /**
     * The tools that answer questions about a large SVG without reading it,
     * named from their declarations and only where held. Chat 981: the agent
     * that read a copper layer whole wanted counts and spacings, which these
     * answer in a few lines each.
     */
    private fun askIt(call: ToolCall, held: List<ToolSpec>): String? {
        val query = held.firstOrNull { it.name == io.mszymanski.orknux.server.embedded.XmlCapability.QUERY_TOOL }
        val measure = held.firstOrNull { it.name == io.mszymanski.orknux.server.embedded.SvgCapability.MEASURE_TOOL }
        if (query == null && measure == null) return null
        val keyParam = (query ?: measure)!!.parameters.firstOrNull { it.name == io.mszymanski.orknux.server.embedded.XmlCapability.KEY }?.name
        val given = keyParam?.let { name ->
            runCatching { MAPPER.readTree(call.arguments).path(name) }.getOrNull()?.takeIf { it.isString }?.stringValue()
        }
        val asks = listOfNotNull(
            query?.let { "${it.name} counts and lists its elements with XPath" },
            measure?.let { "${it.name} measures its shapes' sizes, spacing and groups" },
        ).joinToString(" and ")
        return "to answer a question about it, ask rather than read: $asks" +
            (if (keyParam != null && given != null) ", with $keyParam \"$given\"" else "") +
            ", and only the answer comes back"
    }

    /** Whether the answer is SVG markup, which is drawn to be looked at rather than read. */
    private fun drawing(said: String): Boolean = said.take(DRAWING_SNIFF).contains("<svg")

    /**
     * The render tool, and the viewing tool where the agent has it, named from
     * their declarations and only where they are held; null where it holds no
     * renderer. The key is the one this call was given, where it was given one.
     */
    private fun seeIt(call: ToolCall, held: List<ToolSpec>): String? {
        val render = held.firstOrNull { it.name == io.mszymanski.orknux.server.embedded.SvgCapability.RENDER_TOOL } ?: return null
        val keyParam = render.parameters.firstOrNull { it.name == io.mszymanski.orknux.server.embedded.SvgCapability.KEY }?.name
        val given = keyParam?.let { name ->
            runCatching { MAPPER.readTree(call.arguments).path(name) }.getOrNull()?.takeIf { it.isString }?.stringValue()
        }
        val view = held.firstOrNull { it.name == AgentTools.VIEW }
        return "it is an SVG, which is better looked at than read: ${render.name}" +
            (if (keyParam != null && given != null) " with $keyParam \"$given\"" else "") +
            " draws it as a picture" + (view?.let { ", and ${it.name} shows you a picture by its key" } ?: "")
    }

    /** The whole answer in the session's store, as text that says what it is; null where it would not go in. */
    private fun keep(session: Long, call: ToolCall, said: String): String? {
        val key = RESULT_PREFIX + call.id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80).ifEmpty { "answer" }
        val json = runCatching { MAPPER.readTree(said) }.getOrNull()?.isContainer == true
        val refused = runCatching {
            store.put(session, key, MAPPER.writeValueAsString(said), StoredKind(if (json) "application/json" else "text/plain", false))
        }.getOrElse { it.message ?: "it failed" }
        if (refused != null) log.info("A long answer of {} could not be kept whole: {}", call.name, refused)
        return key.takeIf { refused == null }
    }

    /**
     * Where a JSON answer can be held to [longest] by shortening its longest
     * strings; null where it is not JSON, or cutting values was not enough.
     */
    private fun cutJson(said: String, longest: Int, note: (Int) -> String): String? {
        val tree = runCatching { MAPPER.readTree(said) }.getOrNull()?.takeIf { it.isContainer } ?: return null
        val strings = mutableListOf<Place>()
        collect(tree, strings)
        var excess = said.length - longest
        for (place in strings.sortedByDescending { it.value.length }) {
            if (excess <= 0) break
            val value = place.value
            // Counted as written, escapes and all, and cut as read: an SVG's \r\n is four characters on the wire and two here.
            val written = MAPPER.writeValueAsString(value).length - 2
            val marker = note(value.length)
            val keepWritten = (written - excess - marker.length * 2).coerceAtLeast(SHORTEST_KEPT)
            if (keepWritten >= written) continue
            val keep = (value.length.toLong() * keepWritten / written).toInt()
            val cut = value.take(keep) + marker
            place.set(cut)
            excess -= written - (MAPPER.writeValueAsString(cut).length - 2)
        }
        return MAPPER.writeValueAsString(tree).takeIf { it.length <= longest }
    }

    /**
     * An answer read in pages - JSON whose `html` is a `<section data-page>`
     * per page, with `to` the last page in it - held to [longest] by dropping
     * whole pages from the end, and saying which page to read on from; null
     * where it is not one, or not even its first page fits.
     *
     * Issue #664: `pdf_read` cut by [cutJson] stopped in the middle of a
     * sentence on some page, and the note told the model to read the rest from
     * a stored copy - when the tool it had just called reads from any page. A
     * page cut part-way also reads as a page that ended there. Known by its
     * shape, not by the tool's name, and the parameter to read on with is
     * read off the called tool's declaration.
     */
    private fun cutPages(said: String, call: ToolCall, held: List<ToolSpec>, longest: Int): String? {
        val tree = runCatching { MAPPER.readTree(said) }.getOrNull() as? ObjectNode ?: return null
        val html = tree.get(PAGED_TEXT)?.takeIf { it.isString }?.stringValue() ?: return null
        if (!tree.path(PAGED_TO).isNumber) return null
        val pages = PAGE_START.findAll(html).toList()
        if (pages.size < 2) return null
        val start = held.firstOrNull { it.name == call.name }
            ?.let { declared -> paging(declared)?.let { declared.name to it.substringBefore(" and ") } }
        val earlier = tree.get(PAGED_NOTE)?.takeIf { it.isString }?.stringValue()
        // Begun where the text alone would fit, since writing it out only adds to it: a long document is not re-written page by page.
        val most = pages.indexOfLast { it.range.first <= longest }.coerceAtMost(pages.size - 1)
        for (kept in most downTo 1) {
            val last = pages[kept - 1].groupValues[1].toInt()
            val next = pages[kept].groupValues[1].toInt()
            val reading = start?.let { "read on with ${it.first}'s ${it.second} $next" } ?: "ask ${call.name} for page $next onward"
            val note = "Stopped after page $last to fit the model's window; $reading."
            val cut = tree.deepCopy().apply {
                put(PAGED_TEXT, html.substring(0, pages[kept].range.first))
                put(PAGED_TO, last)
                put(PAGED_NEXT, next)
                put(PAGED_NOTE, listOfNotNull(earlier, note).joinToString(" "))
            }
            val written = MAPPER.writeValueAsString(cut)
            if (written.length <= longest) return written
        }
        return null
    }

    private class Place(val value: String, val set: (String) -> Unit)

    private fun collect(node: JsonNode, into: MutableList<Place>) {
        when (node) {
            is ObjectNode -> node.properties().map { it.key }.forEach { name ->
                val child = node.get(name) ?: return@forEach
                if (child.isString) into += Place(child.stringValue()) { node.put(name, it) } else collect(child, into)
            }
            is ArrayNode -> for (index in 0 until node.size()) {
                val child = node.get(index)
                if (child.isString) into += Place(child.stringValue()) { node.set(index, MAPPER.nodeFactory.stringNode(it)) } else collect(child, into)
            }
            else -> Unit
        }
    }

    companion object {
        /** Where a cut never goes below, so a small window still leaves a result something to say. */
        const val SHORTEST_CUT = 2_000

        /** How far into an answer a drawing is looked for: past an XML prolog and a doctype. */
        private const val DRAWING_SNIFF = 4_000

        /** What a cut JSON value keeps at least, so it still shows what it was. */
        private const val SHORTEST_KEPT = 200

        /** An answer read in pages: its text, a section per page, and the fields that say where it stopped. */
        private const val PAGED_TEXT = "html"
        private const val PAGED_TO = "to"
        private const val PAGED_NEXT = "next"
        private const val PAGED_NOTE = "note"
        private val PAGE_START = Regex("""<section data-page="(\d+)">""")

        /** What a whole answer kept for reading in parts is stored under, before its call's id. */
        const val RESULT_PREFIX = "result."

        /** Words that say a parameter starts a read part-way, and that say how much to read. */
        private val STARTS = setOf("from", "offset", "start", "startline", "fromline", "page", "cursor", "after", "skip")
        private val AMOUNTS = setOf("length", "limit", "count", "lines", "size", "pagesize", "max", "maxchars", "take")

        /** The tool's own parameters for reading part of an answer, as a phrase; null where it declares no start. */
        fun paging(spec: ToolSpec): String? {
            fun plain(name: String) = name.lowercase().filter { it.isLetter() }
            val start = spec.parameters.filter { plain(it.name) in STARTS }
            if (start.isEmpty()) return null
            val amount = spec.parameters.filter { plain(it.name) in AMOUNTS }
            return (start + amount).joinToString(" and ") { it.name }
        }

        private val MAPPER = ObjectMapper()
    }
}
