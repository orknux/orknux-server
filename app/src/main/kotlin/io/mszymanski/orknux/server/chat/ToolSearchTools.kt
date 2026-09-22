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
class ToolSearchTools(private val mapper: ObjectMapper) {

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
     *   that would overflow the provider's array answers with the best of what
     *   it matched and says so, rather than being refused by the provider.
     */
    fun shed(searchable: List<ToolSpec>, found: MutableSet<String>, room: () -> Int): ToolShed =
        Shed(searchable, found, room)

    private inner class Shed(
        private val searchable: List<ToolSpec>,
        private val found: MutableSet<String>,
        private val room: () -> Int,
    ) : ToolShed {

        override fun specs(): List<ToolSpec> = listOf(
            ToolSpec(
                name = FIND,
                description = "Finds the tools you have been given but are not carrying, and puts them " +
                    "in your hands for the rest of this conversation. You hold ${searchable.size} of " +
                    "them - too many to be listed at once - so search for what the work needs before " +
                    "saying you cannot do it. `query` is words about the job: what you want to do, or " +
                    "the system you want to do it in. What comes back is usable from your next message " +
                    "onwards, not in this one.",
                parameters = listOf(
                    ToolParameterSpec(
                        name = QUERY,
                        description = "What you are looking for, in words: \"send a message in Slack\", " +
                            "\"jira issue\", \"read a page\". Matched against every tool's name and " +
                            "what it says it does.",
                        required = true,
                    ),
                ),
            ),
        )

        override fun handles(name: String): Boolean = name == FIND

        override fun run(call: ToolCall): String {
            val asked = argument(call).orEmpty().trim()
            if (asked.isEmpty()) return "Say what you are looking for: $QUERY is words about the job."

            val matches = matching(asked)
            if (matches.isEmpty()) {
                return "Nothing among the ${searchable.size} tools you hold matches \"$asked\". " +
                    "Try the name of the system rather than the action, or fewer words."
            }

            /*
             * Only as many as the next request has space for. The point of all
             * this is that the array fits, and a search that filled it past the
             * provider's ceiling would fail the very call it was meant to make
             * possible - with the provider's sentence, which is where this
             * started.
             */
            val space = room().coerceAtLeast(0)
            val taken = matches.take(space)
            found += taken.map { it.name }

            return buildString {
                append("Found ${taken.size} of ${matches.size}. ")
                append("You can call these from your next message onwards:\n")
                taken.forEach { append("\n${it.name} - ${it.description.take(DESCRIPTION)}") }
                if (taken.size < matches.size) {
                    append(
                        "\n\n${matches.size - taken.size} more matched and were left out: " +
                            "there is room for ${space} more tools. Search again with narrower words " +
                            "if none of these is the one.",
                    )
                }
            }
        }

        /**
         * What matches, best first.
         *
         * Every word of the query is looked for in the name and in the
         * description, and a tool is scored by how many it carries and where.
         * A name match counts for more than a description match, because a
         * model asking for "jira" means the tools called `jira_*` rather than
         * every tool whose description mentions one.
         *
         * Deliberately not a stemmer or an index. What is being searched is at
         * most a few hundred short strings held in memory, and the thing asking
         * is a model that will read the answer and search again if it was
         * wrong - so what matters is that a reasonable query finds the right
         * tool, not that the ranking is defensible to two decimal places.
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
            val name = tool.name.lowercase()
            val described = tool.description.lowercase()
            return words.sumOf { word ->
                when {
                    name.contains(word) -> NAME_WORTH
                    described.contains(word) -> DESCRIPTION_WORTH
                    else -> 0
                }
            }
        }

        private fun argument(call: ToolCall): String? = runCatching {
            mapper.readTree(call.arguments).path(QUERY).takeIf { it.isTextual }?.stringValue()
        }.getOrNull()
    }

    companion object {

        const val FIND = "find_tools"
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

        private val NOT_A_WORD = Regex("[^a-z0-9]+")
    }
}
