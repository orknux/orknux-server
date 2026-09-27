package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * An agent that looks for the tool it needs instead of carrying all of them.
 *
 * ### What it is for
 *
 * Everything an agent held was declared on every call, and an agent granted
 * more tools than the provider accepts could not answer at all: OpenAI and
 * Azure refuse the whole request - "Invalid 'tools': array too long. Expected an
 * array with maximum length 128" - and what reached the person who asked was
 * that sentence, in a Slack thread. Nothing counted them until the provider
 * did. Raising the cap is not available, because the number is the provider's.
 * Not sending all of them is.
 *
 * So above the ceiling an agent is handed the tools it uses constantly, and
 * `find_tools`. It searches what the agent was granted and answers with what
 * matches; the server then puts those in the array for the next round. That is
 * the whole mechanism, and the reason it has to work that way: a model can only
 * call what was declared in the request it is answering, so discovery has to
 * change what the *next* request declares. The conversation loop rebuilds the
 * request every round, so the array can grow inside one answer.
 *
 * ### What it is not
 *
 * Not a grant, and not a way past one. What can be found is exactly what the
 * agent was granted; search decides what is *declared*, never what is allowed.
 * An agent granted nothing finds nothing.
 *
 * And not on until it is needed. Below the provider's ceiling every tool is
 * declared exactly as it always was: the cost of finding is a round, and
 * spending one to discover a tool that would have fitted in the request is a
 * round spent on nothing. Which means an installation whose agents hold twelve
 * tools never meets any of this.
 *
 * ### Why what is found stays found
 *
 * A tool searched for in one turn is declared for the rest of the session
 * rather than searched for again. An agent that looks something up, is asked a
 * follow-up and has to rediscover the same tool spends a round per turn on a
 * question it has already answered - and the second search is not guaranteed to
 * return what the first did, so the agent's own account of what it can do would
 * change under it between turns.
 */
@Service
class ToolSearchTools(
    private val mapper: ObjectMapper,
    /** For how many findable tools are named outright; Admin -> Settings decides. Issue #442. */
    private val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
) {

    /**
     * A shed over [searchable], writing what it finds into [found].
     *
     * [found] is the round loop's own set: this adds names to it, and the loop
     * reads it when it builds the next request. Handed in rather than kept here
     * because what is found belongs to one answer in progress, and this is a
     * service every answer shares.
     *
     * @param searchable everything the agent was granted and is not carrying.
     * @param found the names discovered so far, added to in place.
     * @param room how many more tools the next request has space for. A search
     *   that would overflow the array answers with the best of what it matched
     *   and says so, rather than being refused by the provider.
     * @param forget what to give up where there is not enough room; see the
     *   field. Defaulting to giving up nothing keeps the #368 behaviour for a
     *   caller that has no budget to manage - it refuses instead.
     *
     * Both are named rather than trailing on purpose. `room` was the trailing
     * lambda and `forget` landing after it rebound every existing call silently,
     * which the compiler caught here and would not have in a language with
     * looser types.
     */
    fun shed(
        searchable: List<ToolSpec>,
        found: MutableSet<String>,
        room: () -> Int,
        /** The tools the agent already carries, so loading one is answered "already loaded". Issue #542. */
        carried: Set<String> = emptySet(),
        forget: (Int) -> List<String> = { emptyList() },
    ): ToolShed = Shed(searchable, found, room, forget, carried)

    private inner class Shed(
        private val searchable: List<ToolSpec>,
        private val found: MutableSet<String>,
        private val room: () -> Int,
        /**
         * Gives up that many of the tools already found, oldest first, and
         * answers with what was given up.
         *
         * Issue #372. An agent that has filled its budget and needs something
         * else should lose what it looked up longest ago rather than be told it
         * is full: "you cannot have any more" is a dead end for a model, where
         * forgetting is what a person does without noticing. What it is using
         * now was found most recently and is the last thing to go.
         */
        private val forget: (Int) -> List<String>,
        private val carried: Set<String> = emptySet(),
    ) : ToolShed {

        /**
         * The names of everything findable, where there are few enough to say.
         *
         * A model told it holds seventeen tools "too many to be listed" was
         * being told something untrue, and paid for it: it had to guess words
         * for a search when the names would have fitted in the sentence. Names
         * are short and a model reads a list of them at a glance, so up to as
         * many as Admin -> Settings allows go into the tool's own description -
         * the model then asks for one by name and the search is a lookup. Above
         * that the list really would be the cost the whole mechanism exists to
         * avoid, and the description says how many there are, as before. The
         * number is the installation's to set, not this file's: zero turns the
         * listing off. Issue #442.
         */
        private fun listed(): String =
            if (searchable.size <= settings.toolsNamedInSearch()) searchable.map { it.name }.sorted().joinToString(", ") else ""

        override fun specs(): List<ToolSpec> = listOf(
            ToolSpec(
                name = SEARCH,
                description = "Finds which tools you could load for a job, by words about it, and says what " +
                    "each does. It loads nothing: call " + FIND + " with the names you want. You can load " +
                    "${searchable.size} tools" + (listed().takeIf { it.isNotEmpty() }?.let { ": $it." } ?: ".") +
                    " If what you need is not among them, you cannot do it - say so rather than claiming you can.",
                parameters = listOf(
                    ToolParameterSpec(
                        name = QUERIES,
                        description = "What the job needs, as a list: [\"send a message in Slack\", \"jira issue\"].",
                        required = true,
                    ),
                ),
            ),
            ToolSpec(
                name = FIND,
                description = "Loads tools by their exact names so you can call them from your next " +
                    "message onwards. A name you cannot load is said to be one, with the nearest real names - " +
                    "it is never swapped for another tool. Load everything the job needs in one call.",
                parameters = listOf(
                    ToolParameterSpec(
                        name = NAMES,
                        description = "The tools' exact names, as a list: [\"slack_post\", \"slack_whoIs\"]. " +
                            "Call " + SEARCH + " first if you do not know them.",
                        required = true,
                    ),
                ),
            ),
        )

        /*
         * And the name it had, for a conversation that remembers it. Issue
         * #535: a session older than the rename carries find_tools in its
         * recorded history, and a model reading that asks for find_tools
         * again - it is answered, not refused, though only tool_load is offered.
         */
        override fun handles(name: String): Boolean = name == SEARCH || name == FIND || name == FORMERLY

        /*
         * Two tools and the name the pair used to be. Issue #538.
         *
         * One tool searched by words and loaded whatever ranked highest, and
         * that is two jobs with two different failures. Asked for an exact name
         * the agent did not hold - github_openPull, in session 514 - it ranked
         * everything else and loaded validate_format, and the model concluded
         * the tool was broken and then that it could use GitHub after all. So
         * finding says what there is and loads nothing, loading takes names and
         * says plainly when one does not exist, and the old name keeps doing
         * what it did for a conversation that remembers it.
         */
        override fun run(call: ToolCall): String = when (call.name) {
            SEARCH -> find(call)
            FIND -> load(call)
            else -> searchAndLoad(call)
        }

        /** What there is for the job, by words: names and what each does. Loads nothing. */
        private fun find(call: ToolCall): String {
            val searches = argument(call).take(MOST_SEARCHES)
            if (searches.isEmpty()) {
                return "Say what you are looking for: $QUERIES is a list of words about the job."
            }
            val byQuery = searches.associateWith { matching(it) }
            val matches = interleaved(searches.map { byQuery.getValue(it) })
            if (matches.isEmpty()) {
                return "Nothing among the ${searchable.size} tools you can load matches " +
                    "\"${searches.joinToString(", ")}\". " +
                    (listed().takeIf { it.isNotEmpty() }?.let { "They are: $it." }
                        ?: "Try the name of the system rather than the action, or fewer words.")
            }
            return buildString {
                append("These match. None is loaded yet: call ").append(FIND)
                appendLine(" with the names you want.")
                matches.take(LOADED_AT_ONCE).forEach { spec ->
                    append("\n").append(spec.name)
                    if (spec.name in found) append(" (already loaded)")
                    append(" - ").append(spec.description.take(DESCRIPTION))
                }
                if (matches.size > LOADED_AT_ONCE) {
                    append("\n\n").append(matches.size - LOADED_AT_ONCE).append(" more matched; narrower words find them.")
                }
            }
        }

        /**
         * The tools named, loaded. A name that is not a tool the agent can load
         * is said to be one, with the nearest real names - never swapped for
         * whatever ranked highest.
         */
        private fun load(call: ToolCall): String {
            val asked = argument(call, NAMES).take(MOST_SEARCHES)
            if (asked.isEmpty()) {
                return "Say which tools to load: $NAMES is a list of their exact names. " +
                    "Call $SEARCH first if you do not know them."
            }
            val byName = searchable.associateBy { it.name.lowercase() }
            /*
             * Already in hand: carried from the start, or loaded earlier. Issue
             * #542: save_artifact is carried, so it is not among the tools to
             * load, and asking for it was answered "you have no tool called
             * save_artifact" - which is false, and sent the model looking for a
             * way round a tool it had all along.
             */
            val inHand = (carried + found).map { it.lowercase() }.toSet()
            val already = asked.filter { it.lowercase() in inHand }
            val present = asked.filter { it.lowercase() !in inHand }.mapNotNull { byName[it.lowercase()] }
                .distinctBy { it.name }
            val missing = asked.filter { it.lowercase() !in inHand && it.lowercase() !in byName }
            val none = buildString {
                missing.forEach { name ->
                    val near = matching(name).take(NEAREST).map { it.name }
                    append("You have no tool called ").append(name).append(".")
                    if (near.isNotEmpty()) append(" Nearest: ").append(near.joinToString(", ")).append(".")
                    append("\n")
                }
            }.trim()
            val held = if (already.isEmpty()) {
                ""
            } else {
                "Already loaded, so call " + (if (already.size == 1) "it" else "them") + " directly: " +
                    already.joinToString(", ") + "."
            }
            if (present.isEmpty()) {
                return listOf(held, none.takeIf { it.isNotEmpty() }?.let {
                    it + "\n\nNothing " + (if (held.isEmpty()) "" else "else ") + "was loaded. Do not say you can do what these would have done."
                }.orEmpty()).filter { it.isNotEmpty() }.joinToString("\n\n")
            }
            val loaded = loadInto(present, present.map { it.name }, present.associate { it.name to listOf(it) })
            return listOf(loaded, held, none).filter { it.isNotEmpty() }.joinToString("\n\n")
        }

        private fun searchAndLoad(call: ToolCall): String {
            val searches = argument(call).take(MOST_SEARCHES)
            if (searches.isEmpty()) {
                return "Say what you are looking for: $QUERIES is a list of words about the job."
            }
            val asked = searches.joinToString(", ")

            /*
             * One call, as many searches as the job needs. Issue #464.
             *
             * A job is rarely about one system - read the thread, file the
             * issue, post the answer - and one search per system meant one
             * round per system, each of them a paid call to the model before
             * the work had begun. Separated by a semicolon or a new line
             * because those are what a model reaches for unprompted; each is
             * scored on its own, and what comes back is drawn from all of them
             * in turn, so a broad search does not take the whole of the room
             * from a narrow one beside it.
             */
            val byQuery = searches.associateWith { matching(it) }
            val matches = interleaved(searches.map { byQuery.getValue(it) })
            if (matches.isEmpty()) {
                // The names, where they fit: a miss that lists what there is
                // ends the guessing, where "try fewer words" invites another go.
                return "Nothing among the ${searchable.size} tools you hold matches \"$asked\". " +
                    (listed().takeIf { it.isNotEmpty() }?.let { "They are: $it. Ask for one by name." }
                        ?: "Try the name of the system rather than the action, or fewer words.")
            }

            return loadInto(matches, searches, byQuery)
        }

        private fun loadInto(
            matches: List<ToolSpec>,
            searches: List<String>,
            byQuery: Map<String, List<ToolSpec>>,
        ): String {
            /*
             * Only as many as the next request has space for. The point of all
             * this is that the array fits, and a search that filled it past the
             * ceiling would fail the very call it was meant to make possible -
             * with the provider's sentence, which is where this started.
             */
            val free = room().coerceAtLeast(0)

            /*
             * Free room is filled in bulk; room that has to be made is made one
             * tool at a time. Issue #372.
             *
             * The two halves are deliberately different. Filling space nobody is
             * using costs nothing, and a job usually needs two or three tools
             * from the same system - so a search that finds room takes what it
             * found. Making space costs something the agent already has, and a
             * broad query would otherwise trade eight tools it was holding for
             * eight it merely asked about. So when the budget is full, one
             * search buys one tool.
             *
             * Which is also the honest reading of what an agent is doing when it
             * searches a full toolset: it has hit a wall on one thing, not
             * decided to re-equip.
             */
            val space = if (free > 0) minOf(free, matches.size, LOADED_AT_ONCE) else 0
            val dropped = if (space == 0) forget(1) else emptyList()
            val taking = if (space > 0) space else dropped.size

            val taken = matches.take(taking)
            found += taken.map { it.name }

            return buildString {
                if (taken.isEmpty()) {
                    append(
                        "There is no room for another tool and none could be given up. " +
                            "Finish with the ones you have.",
                    )
                    return@buildString
                }
                // "Loaded", matching the name: the tools are now the agent's to call. Issue #535.
                append("Loaded ${taken.size} of the ${matches.size} that matched. ")
                append("You can call these from your next message onwards:\n")
                taken.forEach { append("\n${it.name} - ${it.description.take(DESCRIPTION)}") }
                /*
                 * Said rather than done quietly. A tool the agent called two
                 * turns ago and is about to call again has just gone, and an
                 * agent told so searches for it again instead of concluding it
                 * has lost the ability.
                 */
                if (dropped.isNotEmpty()) {
                    append("\n\nTo make room, these are no longer in your hands: ${dropped.joinToString(", ")}. ")
                    append("Load one again with ").append(FIND).append(" if you need it back.")
                }
                /*
                 * And which of several searches found nothing, by name. A model
                 * that asked for three things and was handed tools for two has
                 * no way to tell which of the three to think again about, and
                 * the one it needed may be the one that missed.
                 */
                val empty = searches.filter { byQuery.getValue(it).isEmpty() }
                if (searches.size > 1 && empty.isNotEmpty()) {
                    append("\n\nNothing matched: ")
                    append(empty.joinToString("; ") { "\"" + it + "\"" })
                    append(".")
                }
                if (taken.size < matches.size) {
                    append(
                        "\n\n${matches.size - taken.size} more matched and were left out. " +
                            "Call " + SEARCH + " with narrower words if none of these is the one.",
                    )
                }
            }
        }

        /**
         * Several searches' results as one list: the best of each, then the
         * next best of each. Issue #464.
         *
         * Round-robin rather than one list after another, because the room is
         * shared and the first search would otherwise spend it all - an agent
         * that asked for Slack and for Jira would be handed eight Slack tools
         * and told there was no room for the rest, which is the opposite of
         * what asking for both meant. A tool matched by two searches is taken
         * once, at the first place it comes up.
         */
        private fun interleaved(lists: List<List<ToolSpec>>): List<ToolSpec> {
            if (lists.size <= 1) return lists.firstOrNull().orEmpty()
            val out = mutableListOf<ToolSpec>()
            val seen = mutableSetOf<String>()
            val deepest = lists.maxOf { it.size }
            for (at in 0 until deepest) {
                lists.forEach { list ->
                    list.getOrNull(at)?.let { spec -> if (seen.add(spec.name)) out += spec }
                }
            }
            return out
        }

        /**
         * What matches, best first.
         *
         * Every word of the query is looked for among the words of the name and
         * of the description, and a tool is scored by how many it carries and
         * where. A name match counts for more than a description match, because
         * a model asking for "jira" means the tools called `jira_*` rather than
         * every tool whose description mentions one.
         *
         * By the word rather than by the letters, the rule the memory search
         * settled on: `contains` made "up" match half a list and "uploads" miss
         * `slack_upload`. A name is split on `_` and on the case change inside
         * it - `slack_uploadBinary` is slack, upload, binary - and a query word
         * and a held word match when equal or when one is a prefix of the other
         * from [STEM] letters on, which is as much stemming as is worth having
         * without a language to do it in. Issue #451.
         *
         * Deliberately not an index. What is being searched is at most a few
         * hundred short strings held in memory, and the thing asking is a model
         * that will read the answer and search again if it was wrong - so what
         * matters is that a reasonable query finds the right tool, not that the
         * ranking is defensible to two decimal places.
         */
        private fun matching(asked: String): List<ToolSpec> {
            val words = asked.lowercase().split(NOT_A_WORD).filter { it.length > 1 }.distinct()
            if (words.isEmpty()) return emptyList()

            return searchable
                .map { it to score(it, words) }
                .filter { (_, score) -> score > 0 }
                .sortedWith(compareByDescending<Pair<ToolSpec, Int>> { it.second }.thenBy { it.first.name })
                .map { it.first }
        }

        private fun score(tool: ToolSpec, words: List<String>): Int {
            val named = wordsOfName(tool.name)
            val described = tool.description.lowercase().split(NOT_A_WORD).filter { it.isNotEmpty() }
            return words.sumOf { word ->
                when {
                    named.any { carries(it, word) } -> NAME_WORTH
                    described.any { carries(it, word) } -> DESCRIPTION_WORTH
                    else -> 0
                }
            }
        }

        /** The words a tool's name is made of: `slack_uploadBinary` is slack, upload, binary. */
        private fun wordsOfName(name: String): List<String> =
            name.replace(CASE_CHANGE, "$1 $2").lowercase().split(NOT_A_WORD).filter { it.isNotEmpty() }

        /** Equal, or one a prefix of the other from [STEM] letters on - so "uploads" and "upload" meet, "up" meets nothing. */
        private fun carries(held: String, word: String): Boolean =
            held == word || (minOf(held.length, word.length) >= STEM && (held.startsWith(word) || word.startsWith(held)))

        /**
         * The searches asked for. Issue #517.
         *
         * A list, because it used to be one string with the searches separated
         * by a semicolon and a model writing structure inside a string is a
         * model that can break the string. It did: `{"query":"diagram_render;`
         * arrived truncated, llama.cpp refused the whole call with a 500, and
         * the round was spent on nothing. An array cannot come apart that way -
         * the separator is the format's own.
         *
         * A bare string is still read, because that is what a model trained on
         * the old shape will send and refusing it would spend a round teaching
         * it. Split on the old separators for the same reason.
         */
        private fun argument(call: ToolCall, key: String = QUERIES): List<String> = runCatching {
            val sent = mapper.readTree(call.arguments)
            // The key asked for, then the others, so a name sent as queries still loads. Issue #538.
            val given = listOf(key, NAMES, QUERIES, QUERY).map { sent.path(it) }.firstOrNull { !it.isMissingNode }
                ?: sent.path(key)
            // A list sent as the text of one - every parameter reaches the model typed as a string. Issue #538.
            val listed = if (given.isTextual) {
                runCatching { mapper.readTree(given.stringValue()) }.getOrNull()?.takeIf { it.isArray } ?: given
            } else {
                given
            }
            val written = when {
                listed.isArray -> listed.mapNotNull { one -> one.takeIf { it.isTextual }?.stringValue() }
                listed.isTextual -> listed.stringValue().orEmpty().split(SEARCH_SEPARATOR)
                else -> emptyList()
            }
            written.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }.getOrDefault(emptyList())
    }

    companion object {

        /**
         * `tool_load`, noun first like `skill_load` beside it. Issue #535: it
         * was `find_tools`, which reads as a lookup, and a model that believed
         * it already held a tool and only needed to find out about it tried to
         * call the tool instead - session 510.
         */
        const val FIND = "tool_load"

        /** What it used to be called, still answered - searching and loading at once, as it did. Issue #535. */
        const val FORMERLY = "find_tools"

        /** Finding by words, loading nothing. Issue #538. */
        const val SEARCH = "tool_find"

        /** What tool_load takes: exact names. Issue #538. */
        const val NAMES = "names"

        /** How many near names a missing one is answered with. */
        private const val NEAREST = 3
        /** What it takes now: a list. Issue #517. */
        const val QUERIES = "queries"

        /** And what it used to take, still read so an older habit is not punished. */
        const val QUERY = "query"

        /** A word in the name is worth three in the description; see `score`. */
        private const val NAME_WORTH = 3
        private const val DESCRIPTION_WORTH = 1

        /**
         * How much of a tool's description comes back per match.
         *
         * Enough to choose by, and no more: the whole point is an array that
         * fits, and an answer quoting forty full descriptions would spend on
         * the reply what it saved on the request.
         */
        private const val DESCRIPTION = 200

        /**
         * How many one search puts in the agent's hands at once.
         *
         * A bulk load rather than one at a time - a job usually needs two or
         * three tools from the same system, and making the agent search once
         * per tool spends a round on each. Bounded, because a query matching
         * forty would otherwise evict everything the agent was holding to make
         * room for a list it did not mean to ask for.
         */
        private const val LOADED_AT_ONCE = 8

        private val NOT_A_WORD = Regex("[^a-z0-9]+")

        /**
         * What separates one search from the next in a single call: a semicolon
         * or a new line. Issue #464. Not a comma - "jira, slack" is one phrase
         * as often as it is two, and a model writing a list of words usually
         * means them as one search.
         */
        private val SEARCH_SEPARATOR = Regex("[;\n\r]+")

        /**
         * How many searches one call may carry. Enough for a job that touches
         * three or four systems, and a bound so a paragraph pasted into the box
         * is not read as forty searches of one word each.
         */
        private const val MOST_SEARCHES = 8

        /** Where a name changes case, which is where its words meet: `uploadBinary`. */
        private val CASE_CHANGE = Regex("([a-z0-9])([A-Z])")

        /**
         * From how many letters a prefix counts as the same word. Four, as the
         * memory search has it: shorter and "post" would meet "postpone" but so
         * would "up" meet "upload", which is the substring rule back again.
         */
        private const val STEM = 4
    }
}
