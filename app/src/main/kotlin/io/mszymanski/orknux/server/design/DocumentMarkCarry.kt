package io.mszymanski.orknux.server.design

import com.github.difflib.DiffUtils
import com.github.difflib.patch.AbstractDelta
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.util.IdentityHashMap

/**
 * Carries a document's colours and highlights across a model's rewrite of it.
 *
 * A model rewrites blocks in Markdown, and is told how the two marks read there
 * ([DocumentMarkdown]) - but one that leaves the tags out still hands back most
 * of the words it was given, and the person's highlights were on those words.
 * So the words before and after are compared with Myers' diff (java-diff-utils),
 * in order across the replaced blocks, and a colour or highlight on words that
 * came back unchanged is put back on them. What the model wrote a tag for itself
 * is left as it wrote it.
 *
 * A highlight none of whose words survived is moved onto the words that replaced
 * them only when that is unambiguous: one rewritten stretch replaced exactly its
 * words and no other highlight's. Otherwise it is dropped, and [Carried.dropped]
 * says so in words the model and the person can both read, because a highlight
 * is a person's note and losing one silently is losing what they wrote.
 *
 * @param mostCharacters how long the replaced text may be, either side, and still
 *   be compared; past it nothing is carried, and the highlights the model did not
 *   keep are said to be dropped.
 */
class DocumentMarkCarry(private val mapper: ObjectMapper, private val mostCharacters: Int) {

    /** The new blocks with what was carried put on them, and the highlights that could not be. */
    data class Carried(val blocks: List<ObjectNode>, val dropped: List<String>)

    fun carry(before: List<JsonNode>, after: List<ObjectNode>): Carried {
        val old = Flat.of(before)
        if (old.colour.all { it == null } && old.note.all { it == null }) return Carried(after, emptyList())
        val blocks = after.map { it.deepCopy() }
        val new = Flat.of(blocks)
        val addColour = arrayOfNulls<ObjectNode>(new.text.length)
        val addNote = arrayOfNulls<ObjectNode>(new.text.length)
        val highlights = old.highlights()
        val dropped = mutableListOf<String>()

        if (old.text.length > mostCharacters || new.text.length > mostCharacters) {
            highlights.filterNot { new.holds(it) }.forEach { dropped += removed(old, it) }
            return Carried(blocks, dropped)
        }

        val oldTokens = tokens(old.text)
        val newTokens = tokens(new.text)
        val deltas = DiffUtils.diff(oldTokens.map { it.text }, newTokens.map { it.text }).deltas.sortedBy { it.source.position }

        // A highlight the model wrote a tag for is where it put it, and is not stretched back over the words it left out.
        val rewritten = highlights.filter { new.holds(it) }.map { it.key }.toSet()
        fun equal(oldFrom: Int, newFrom: Int, count: Int) {
            for (k in 0 until count) {
                val from = oldTokens[oldFrom + k]
                val to = newTokens[newFrom + k]
                for (c in from.text.indices) {
                    val o = from.start + c
                    val n = to.start + c
                    if (old.colour[o] != null && new.colour[n] == null) addColour[n] = old.colour[o]
                    val note = old.note[o]
                    if (note != null && new.note[n] == null && Flat.keyOf(note) !in rewritten) addNote[n] = note
                }
            }
        }
        var oi = 0
        var ni = 0
        for (delta in deltas) {
            equal(oi, ni, delta.source.position - oi)
            oi = delta.source.position + delta.source.size()
            ni = delta.target.position + delta.target.size()
        }
        equal(oi, ni, oldTokens.size - oi)
        // A space or a full stop that happens to be in the same place is not a word that survived:
        // what would be left of a mark on nothing but those is taken off again.
        unworded(new.text, addColour)
        unworded(new.text, addNote)
        val carried = addNote.filterNotNull().map { Flat.keyOf(it) }.toSet()

        val stretches = stretches(deltas, oldTokens, newTokens, old.text, new.text)
        for (highlight in highlights) {
            if (highlight.key in carried || highlight.key in rewritten) continue
            val moved = movedOnto(highlight, highlights, stretches, old, new)
            if (moved == null) {
                dropped += removed(old, highlight)
            } else {
                for (n in moved) addNote[n] = old.note[highlight.positions.first()]
            }
        }

        blocks.forEach { rebuild(it, new.starts, addColour, addNote) }
        return Carried(blocks, dropped)
    }

    /** What was rewritten, in characters either side: the old words, and the new ones put in their place. */
    private class Stretch(val source: IntRange, val target: IntRange)

    /**
     * The diff's changes as stretches of characters, two changes kept as one where only spaces lie between
     * them - "gone words" rewritten as "lost text" is one rewrite, though the space between came through.
     */
    private fun stretches(
        deltas: List<AbstractDelta<String>>,
        oldTokens: List<Token>,
        newTokens: List<Token>,
        oldText: String,
        newText: String,
    ): List<Stretch> {
        fun start(tokens: List<Token>, at: Int, length: Int) = if (at < tokens.size) tokens[at].start else length
        val out = mutableListOf<Stretch>()
        var lastSourceEnd = -1
        for (delta in deltas) {
            val sourceFrom = start(oldTokens, delta.source.position, oldText.length)
            val sourceTo = start(oldTokens, delta.source.position + delta.source.size(), oldText.length)
            val targetFrom = start(newTokens, delta.target.position, newText.length)
            val targetTo = start(newTokens, delta.target.position + delta.target.size(), newText.length)
            val previous = out.lastOrNull()
            if (previous != null && lastSourceEnd >= 0 && oldText.substring(lastSourceEnd, sourceFrom).isBlank()) {
                out[out.size - 1] = Stretch(previous.source.first until sourceTo, minOf(previous.target.first, targetFrom) until targetTo)
            } else {
                out += Stretch(sourceFrom until sourceTo, targetFrom until targetTo)
            }
            lastSourceEnd = sourceTo
        }
        return out
    }

    /** The new characters a highlight moves onto when one rewritten stretch replaced its words and only its. */
    private fun movedOnto(highlight: Highlight, all: List<Highlight>, stretches: List<Stretch>, old: Flat, new: Flat): List<Int>? {
        val from = highlight.positions.first()
        val to = highlight.positions.last()
        val stretch = stretches.filter { !it.source.isEmpty() && it.source.first <= to && it.source.last >= from }.singleOrNull()
            ?: return null
        if (all.any { other -> other !== highlight && other.positions.any { it in stretch.source } }) return null
        // Exactly its words were rewritten: a sentence rewritten round a highlighted word gives no word to move it to.
        val own = highlight.positions.toSet()
        if (stretch.source.any { !old.text[it].isWhitespace() && it !in own }) return null
        val target = stretch.target.filter { !new.text[it].isWhitespace() }
        if (target.isEmpty()) return null
        val onto = (target.first()..target.last()).toList()
        if (onto.any { new.text[it] == SEPARATOR || new.note[it] != null }) return null
        return onto
    }

    /** Takes a carried mark off every run of it that holds no letter or digit. */
    private fun unworded(text: String, added: Array<ObjectNode?>) {
        var at = 0
        while (at < added.size) {
            val mark = added[at]
            if (mark == null) {
                at++
                continue
            }
            var end = at
            while (end < added.size && added[end] === mark) end++
            if ((at until end).none { text[it].isLetterOrDigit() }) (at until end).forEach { added[it] = null }
            at = end
        }
    }

    private fun removed(old: Flat, highlight: Highlight): String {
        val words = highlight.positions.map { old.text[it] }.joinToString("").replace(SEPARATOR, ' ')
            .replace(Regex("\\s+"), " ").trim()
        val said = if (words.length > QUOTED) words.take(QUOTED).trimEnd() + "…" else words
        return "highlight '$said' was removed with its words"
    }

    /** Splits each text node where what is carried onto it changes, and puts the carried marks on. */
    private fun rebuild(node: ObjectNode, starts: IdentityHashMap<JsonNode, Int>, colour: Array<ObjectNode?>, note: Array<ObjectNode?>) {
        val content = node.path("content") as? ArrayNode ?: return
        val out = mapper.createArrayNode()
        for (at in 0 until content.size()) {
            val child = content.get(at) as? ObjectNode ?: continue
            val start = starts[child]
            if (child.path("type").asText("") != "text" || start == null) {
                rebuild(child, starts, colour, note)
                out.add(child)
                continue
            }
            val text = child.path("text").asText("")
            var runStart = 0
            for (end in 1..text.length) {
                val boundary = end == text.length ||
                    colour[start + end] !== colour[start + runStart] || note[start + end] !== note[start + runStart]
                if (!boundary) continue
                val run = child.deepCopy()
                run.put("text", text.substring(runStart, end))
                val adding = listOfNotNull(colour[start + runStart], note[start + runStart])
                if (adding.isNotEmpty()) {
                    val marks = (run.path("marks") as? ArrayNode) ?: run.putArray("marks")
                    adding.forEach { marks.add(it.deepCopy()) }
                }
                out.add(run)
                runStart = end
            }
        }
        node.set("content", out)
    }

    /** One highlight of the old text: the characters it covers, in order, and what tells it apart. */
    private class Highlight(val key: String, val id: String?, val note: String, val positions: List<Int>)

    private class Token(val text: String, val start: Int)

    /**
     * Blocks as one string, a [SEPARATOR] after each node that is not inline, with the colour
     * and the highlight on each character - and where each text node begins in it.
     */
    private class Flat(val text: String, val colour: Array<ObjectNode?>, val note: Array<ObjectNode?>, val starts: IdentityHashMap<JsonNode, Int>) {

        fun highlights(): List<Highlight> {
            val found = linkedMapOf<String, MutableList<Int>>()
            val first = mutableMapOf<String, ObjectNode>()
            for (at in note.indices) {
                val mark = note[at] ?: continue
                val key = keyOf(mark)
                found.getOrPut(key) { mutableListOf() } += at
                first.putIfAbsent(key, mark)
            }
            return found.map { (key, positions) ->
                val attrs = first.getValue(key).path("attrs")
                Highlight(key, attrs.path("id").asText("").ifEmpty { null }, attrs.path("note").asText(""), positions)
            }
        }

        /** Whether the model kept this highlight itself, by writing a tag with its id - or its note, where it had no id. */
        fun holds(highlight: Highlight): Boolean = note.any { mark ->
            mark != null && if (highlight.id != null) {
                mark.path("attrs").path("id").asText("") == highlight.id
            } else {
                mark.path("attrs").path("note").asText("") == highlight.note
            }
        }

        companion object {
            fun keyOf(mark: JsonNode): String {
                val attrs = mark.path("attrs")
                return attrs.path("id").asText("").ifEmpty { null }?.let { "id:$it" } ?: ("note:" + attrs.path("note").asText(""))
            }

            fun of(blocks: List<JsonNode>): Flat {
                val text = StringBuilder()
                val colour = mutableListOf<ObjectNode?>()
                val note = mutableListOf<ObjectNode?>()
                val starts = IdentityHashMap<JsonNode, Int>()
                fun add(chars: String, c: ObjectNode?, n: ObjectNode?) {
                    text.append(chars)
                    repeat(chars.length) {
                        colour += c
                        note += n
                    }
                }
                fun walk(node: JsonNode) {
                    when (node.path("type").asText("")) {
                        "text" -> {
                            val marks = node.path("marks")
                            val held = (0 until marks.size()).map { marks.get(it) }
                            starts[node] = text.length
                            add(
                                node.path("text").asText(""),
                                held.firstOrNull { it.path("type").asText("") == "textStyle" } as? ObjectNode,
                                held.firstOrNull { it.path("type").asText("") == "note" } as? ObjectNode,
                            )
                        }
                        "hardBreak" -> add("\n", null, null)
                        else -> {
                            val inner = node.path("content")
                            for (at in 0 until inner.size()) walk(inner.get(at))
                            add(SEPARATOR.toString(), null, null)
                        }
                    }
                }
                blocks.forEach(::walk)
                return Flat(text.toString(), colour.toTypedArray(), note.toTypedArray(), starts)
            }
        }
    }

    private fun tokens(text: String): List<Token> = TOKEN.findAll(text).map { Token(it.value, it.range.first) }.toList()

    private companion object {
        /** Where a block ends in the flattened text: a character no document's words hold. */
        const val SEPARATOR = '\u0000'

        /** A word, a run of spaces, or one other character: what the diff compares. */
        val TOKEN = Regex("[\\p{L}\\p{N}_]+|\\s+|[^\\p{L}\\p{N}_\\s]")

        /** How many characters of a dropped highlight's words are quoted. A format: enough to recognise it by. */
        const val QUOTED = 60
    }
}
