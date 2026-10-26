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
 * of the words it was given, and the person's marks were on those words. So the
 * words before and after are compared with Myers' diff (java-diff-utils), in
 * order across the replaced blocks, and a colour or highlight on words that came
 * back unchanged is put back on them. What the model wrote a tag for itself is
 * left as it wrote it.
 *
 * Colours and highlights go by one rule, in one place, told apart only by [Kind].
 * A mark none of whose words survived is moved onto the words that replaced them
 * only when that is unambiguous: one rewritten stretch replaced exactly its words
 * and no other mark's of the same kind. Otherwise it is dropped, and
 * [Carried.dropped] says so in words the model and the person can both read,
 * because a highlight is a person's note and a colour a person's emphasis, and
 * losing either silently is losing what they did.
 *
 * @param mostCharacters how long the replaced text may be, either side, and still
 *   be compared; past it nothing is carried, and the marks the model did not
 *   keep are said to be dropped.
 */
class DocumentMarkCarry(private val mapper: ObjectMapper, private val mostCharacters: Int) {

    /** The new blocks with what was carried put on them, and the marks that could not be, in document order. */
    data class Carried(val blocks: List<ObjectNode>, val dropped: List<String>)

    fun carry(before: List<JsonNode>, after: List<ObjectNode>): Carried {
        val old = Flat.of(before)
        if (Kind.entries.all { kind -> old.marks(kind).all { it == null } }) return Carried(after, emptyList())
        val blocks = after.map { it.deepCopy() }
        val new = Flat.of(blocks)
        val added = Kind.entries.associateWith { arrayOfNulls<ObjectNode>(new.text.length) }
        val dropped = mutableListOf<Pair<Int, String>>()
        fun drop(kind: Kind, piece: Piece) {
            dropped += piece.positions.first() to removed(kind, old, piece)
        }
        fun said() = dropped.sortedBy { it.first }.map { it.second }

        if (old.text.length > mostCharacters || new.text.length > mostCharacters) {
            for (kind in Kind.entries) old.pieces(kind).filterNot { kind.kept(it, new, null) }.forEach { drop(kind, it) }
            return Carried(blocks, said())
        }

        val oldTokens = tokens(old.text)
        val newTokens = tokens(new.text)
        val deltas = DiffUtils.diff(oldTokens.map { it.text }, newTokens.map { it.text }).deltas.sortedBy { it.source.position }

        // Where each old character came through unchanged, or -1.
        val image = IntArray(old.text.length) { -1 }
        fun equal(oldFrom: Int, newFrom: Int, count: Int) {
            for (k in 0 until count) {
                val from = oldTokens[oldFrom + k]
                val to = newTokens[newFrom + k]
                for (c in from.text.indices) image[from.start + c] = to.start + c
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
        val stretches = stretches(deltas, oldTokens, newTokens, old.text, new.text)

        for (kind in Kind.entries) {
            val marks = old.marks(kind)
            val written = new.marks(kind)
            val add = added.getValue(kind)
            val pieces = old.pieces(kind)
            // A mark the model wrote a tag for is where it put it, and is not stretched back over the words it left out.
            val kept = pieces.filter { kind.kept(it, new, became(it, image, stretches)) }.toSet()
            for (piece in pieces) {
                if (piece in kept) continue
                for (o in piece.positions) {
                    val n = image[o]
                    if (n >= 0 && written[n] == null) add[n] = marks[o]
                }
            }
            // A space or a full stop that happens to be in the same place is not a word that survived:
            // what would be left of a mark on nothing but those is taken off again.
            unworded(new.text, add)
            for (piece in pieces) {
                if (piece in kept || piece.positions.any { image[it] >= 0 && add[image[it]] != null }) continue
                val moved = movedOnto(kind, piece, pieces, stretches, old, new)
                if (moved == null) drop(kind, piece) else for (n in moved) add[n] = marks[piece.positions.first()]
            }
        }

        blocks.forEach { rebuild(it, new.starts, added) }
        return Carried(blocks, said())
    }

    /** The new characters a piece's words became: those that came through unchanged, and what replaced the rest. */
    private fun became(piece: Piece, image: IntArray, stretches: List<Stretch>): List<Int> {
        val from = piece.positions.first()
        val to = piece.positions.last()
        return piece.positions.map { image[it] }.filter { it >= 0 } +
            stretches.filter { !it.source.isEmpty() && it.source.first <= to && it.source.last >= from }.flatMap { it.target }
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

    /**
     * The new characters a piece moves onto when one rewritten stretch replaced its words and only its:
     * no other piece of the same kind lies in it, and nothing but spaces outside the piece's own words.
     */
    private fun movedOnto(kind: Kind, piece: Piece, all: List<Piece>, stretches: List<Stretch>, old: Flat, new: Flat): List<Int>? {
        val from = piece.positions.first()
        val to = piece.positions.last()
        val stretch = stretches.filter { !it.source.isEmpty() && it.source.first <= to && it.source.last >= from }.singleOrNull()
            ?: return null
        if (all.any { other -> other !== piece && other.positions.any { it in stretch.source } }) return null
        // Exactly its words were rewritten: a sentence rewritten round a marked word gives no word to move it to.
        val own = piece.positions.toSet()
        if (stretch.source.any { !old.text[it].isWhitespace() && it !in own }) return null
        val target = stretch.target.filter { !new.text[it].isWhitespace() }
        if (target.isEmpty()) return null
        val onto = (target.first()..target.last()).toList()
        val written = new.marks(kind)
        if (onto.any { new.text[it] == SEPARATOR || written[it] != null }) return null
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

    private fun removed(kind: Kind, old: Flat, piece: Piece): String {
        val words = piece.positions.map { old.text[it] }.joinToString("").replace(SEPARATOR, ' ')
            .replace(Regex("\\s+"), " ").trim()
        val said = if (words.length > QUOTED) words.take(QUOTED).trimEnd() + "…" else words
        return kind.named(piece.mark, said) + " was removed with its words"
    }

    /** Splits each text node where what is carried onto it changes, and puts the carried marks on. */
    private fun rebuild(node: ObjectNode, starts: IdentityHashMap<JsonNode, Int>, added: Map<Kind, Array<ObjectNode?>>) {
        val content = node.path("content") as? ArrayNode ?: return
        val out = mapper.createArrayNode()
        for (at in 0 until content.size()) {
            val child = content.get(at) as? ObjectNode ?: continue
            val start = starts[child]
            if (child.path("type").asText("") != "text" || start == null) {
                rebuild(child, starts, added)
                out.add(child)
                continue
            }
            val text = child.path("text").asText("")
            var runStart = 0
            for (end in 1..text.length) {
                val boundary = end == text.length || added.values.any { it[start + end] !== it[start + runStart] }
                if (!boundary) continue
                val run = child.deepCopy()
                run.put("text", text.substring(runStart, end))
                val adding = Kind.entries.mapNotNull { added.getValue(it)[start + runStart] }
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

    /**
     * The two marks carried, and the only ways they differ: how one is told apart from another, whether
     * one is a single stretch of words ([contiguous]), how a lost one is named, and what says the model
     * kept it itself.
     */
    private enum class Kind(val type: String, val contiguous: Boolean) {
        /**
         * A colour is its value, and one is a single run of words: two red phrases in a paragraph are two
         * colours, either of which may go. The model kept one when it wrote any colour on the words it
         * became - it chose that colour - and, past the cap where no words are compared, when it wrote
         * that colour anywhere.
         */
        COLOUR("textStyle", contiguous = true) {
            override fun keyOf(mark: JsonNode) = "colour:" + mark.path("attrs").path("color").asText("").lowercase()
            override fun named(mark: ObjectNode, words: String) = "colour ${mark.path("attrs").path("color").asText("")} on '$words'"
            override fun kept(piece: Piece, new: Flat, became: List<Int>?): Boolean {
                val written = new.marks(this)
                return became?.any { written[it] != null } ?: written.any { it != null && keyOf(it) == piece.key }
            }
        },

        /**
         * A highlight is its id - or its note, where it has none - wherever its words are. The model kept
         * one when it wrote a tag with that id, or that note where there was no id, anywhere: another note
         * on the same words does not say this one was meant to go, so that is not a keeping.
         */
        NOTE("note", contiguous = false) {
            override fun keyOf(mark: JsonNode): String {
                val attrs = mark.path("attrs")
                return attrs.path("id").asText("").ifEmpty { null }?.let { "id:$it" } ?: ("note:" + attrs.path("note").asText(""))
            }
            override fun named(mark: ObjectNode, words: String) = "highlight '$words'"
            override fun kept(piece: Piece, new: Flat, became: List<Int>?): Boolean {
                val id = piece.mark.path("attrs").path("id").asText("").ifEmpty { null }
                val note = piece.mark.path("attrs").path("note").asText("")
                return new.marks(this).any { mark ->
                    mark != null && if (id != null) mark.path("attrs").path("id").asText("") == id else mark.path("attrs").path("note").asText("") == note
                }
            }
        };

        abstract fun keyOf(mark: JsonNode): String
        abstract fun named(mark: ObjectNode, words: String): String

        /** Whether the model kept this piece itself; [became] is null where nothing was compared. */
        abstract fun kept(piece: Piece, new: Flat, became: List<Int>?): Boolean
    }

    /** One colour or highlight of the old text: the characters it covers, in order, and the mark it is. */
    private class Piece(val key: String, val mark: ObjectNode, val positions: List<Int>)

    private class Token(val text: String, val start: Int)

    /**
     * Blocks as one string, a [SEPARATOR] after each node that is not inline, with each kind's mark
     * on each character - and where each text node begins in it.
     */
    private class Flat(val text: String, private val held: Map<Kind, Array<ObjectNode?>>, val starts: IdentityHashMap<JsonNode, Int>) {

        fun marks(kind: Kind): Array<ObjectNode?> = held.getValue(kind)

        /** The kind's marks as pieces: one per key, or per unbroken run of a key where the kind is [Kind.contiguous]. */
        fun pieces(kind: Kind): List<Piece> {
            val marks = marks(kind)
            val found = linkedMapOf<String, MutableList<Int>>()
            val first = mutableMapOf<String, ObjectNode>()
            var runStart = 0
            for (at in marks.indices) {
                val mark = marks[at] ?: continue
                val key = kind.keyOf(mark)
                if (at == 0 || marks[at - 1]?.let(kind::keyOf) != key) runStart = at
                val group = if (kind.contiguous) "$key@$runStart" else key
                found.getOrPut(group) { mutableListOf() } += at
                first.putIfAbsent(group, mark)
            }
            return found.map { (group, positions) -> Piece(kind.keyOf(first.getValue(group)), first.getValue(group), positions) }
        }

        companion object {
            fun of(blocks: List<JsonNode>): Flat {
                val text = StringBuilder()
                val held = Kind.entries.associateWith { mutableListOf<ObjectNode?>() }
                val starts = IdentityHashMap<JsonNode, Int>()
                fun add(chars: String, marks: List<JsonNode>) {
                    text.append(chars)
                    for ((kind, list) in held) {
                        val mark = marks.firstOrNull { it.path("type").asText("") == kind.type } as? ObjectNode
                        repeat(chars.length) { list += mark }
                    }
                }
                fun walk(node: JsonNode) {
                    when (node.path("type").asText("")) {
                        "text" -> {
                            val marks = node.path("marks")
                            starts[node] = text.length
                            add(node.path("text").asText(""), (0 until marks.size()).map { marks.get(it) })
                        }
                        "hardBreak" -> add("\n", emptyList())
                        // Inline, and never marked: its name is part of the line's words, not a block of its own.
                        "documentLink" -> add(node.path("attrs").path("name").asText(""), emptyList())
                        else -> {
                            val inner = node.path("content")
                            for (at in 0 until inner.size()) walk(inner.get(at))
                            add(SEPARATOR.toString(), emptyList())
                        }
                    }
                }
                blocks.forEach(::walk)
                return Flat(text.toString(), held.mapValues { it.value.toTypedArray() }, starts)
            }
        }
    }

    private fun tokens(text: String): List<Token> = TOKEN.findAll(text).map { Token(it.value, it.range.first) }.toList()

    private companion object {
        /** Where a block ends in the flattened text: a character no document's words hold. */
        const val SEPARATOR = '\u0000'

        /** A word, a run of spaces, or one other character: what the diff compares. */
        val TOKEN = Regex("[\\p{L}\\p{N}_]+|\\s+|[^\\p{L}\\p{N}_\\s]")

        /** How many characters of a dropped mark's words are quoted. A format: enough to recognise it by. */
        const val QUOTED = 60
    }
}
