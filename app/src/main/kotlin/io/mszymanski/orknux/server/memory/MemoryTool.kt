package io.mszymanski.orknux.server.memory

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The workspace's memory, as an agent reads and writes it.
 *
 * A built-in rather than one of the workspace's tools, because a workspace tool
 * is JavaScript in a sandbox with no IO — it cannot read a table, and widening
 * the sandbox so it could would be a hole opened for one feature. So the lookup
 * is implemented here and offered to agents as something they may call.
 *
 * What an agent may touch is what it was granted: [Agent.memoryCatalogs] names
 * the catalogs, and an agent granted none reaches nothing. That is the point of
 * the grant — everything the workspace knows is rarely what one agent should be
 * given, and an agent that can read every catalog by default makes the grant a
 * decoration. The same grant covers saving, because "remember this" is the
 * other half of "what do we know".
 */
@Service
class MemoryTool(
    private val catalogs: MemoryCatalogRepository,
    private val memories: MemoryRepository,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    /** What this looks like to an agent choosing whether to call it. */
    fun descriptor(): ToolDescriptor = DESCRIPTOR

    /** The writing half, offered beside the reading one. */
    fun saveDescriptor(): ToolDescriptor = SAVE_DESCRIPTOR

    /**
     * The catalogs this agent may read, as the screen would name them.
     *
     * A granted name that no longer matches a catalog is dropped rather than
     * failing: catalogs are granted by name, and a rename should cost an agent
     * one grant, not every call it makes.
     */
    fun catalogsFor(agent: Agent): List<MemoryCatalogView> {
        if (agent.memoryCatalogs.isEmpty()) return emptyList()
        val granted = agent.memoryCatalogs.toSet()
        return catalogs.findByWorkspaceIdOrderByNameAsc(agent.workspaceId)
            .filter { it.name in granted }
            .map { MemoryCatalogView(
                id = requireNotNull(it.id),
                workspaceId = it.workspaceId,
                name = it.name,
                memoryCount = memories.countByCatalogId(requireNotNull(it.id)).toInt(),
                createdAt = it.createdAt.toString(),
                createdBy = it.createdBy,
                isDefault = it.isDefault,
            ) }
    }

    /**
     * Searches the catalogs this agent holds, best match first.
     *
     * ### Why it is words rather than the string
     *
     * It was one `contains` over the whole query, which is a search that only
     * answers a question somebody already knows the answer to. A memory reading
     * "order-processing-service is abbreviated as OPS" was not found by "what
     * does OPS stand for", by "OPS abbreviation", or by anything except a
     * fragment of its own text - so an agent that had written something down
     * could not find it again, said it knew nothing, and the row sat there. The
     * strictness was invisible: an empty list looks the same whether nothing
     * matched or nothing is there.
     *
     * So every word of the query is looked for on its own, a memory is scored by
     * how many of them it carries and where, and what comes back is in that
     * order.
     *
     * Any word rather than all of them, which is the opposite of the connection
     * search and deliberately: a query there names one thing and is narrowing,
     * while a question put to memory is a handful of words about a subject and
     * most of them will not appear anywhere. Ranking is what keeps that useful -
     * the memory carrying three of the words comes above the one carrying one.
     *
     * ### What it is not
     *
     * Not an index. What is searched is the granted catalogs read into memory,
     * which is what this already did: a workspace's memory is tens or hundreds
     * of short rows, and a tsvector would be a Postgres answer in a product that
     * also runs on SQLite. If a catalog grows past what is comfortable to read
     * whole, that is the point to put an index behind this, and the shape of the
     * answer would not change.
     *
     * A blank query returns what is there, newest first, rather than nothing: an
     * agent asking "what do you know" is a reasonable first move. The limit is
     * capped, because what comes back is going into a prompt.
     */
    fun search(agent: Agent, query: String?, catalog: String?, limit: Int = DEFAULT_LIMIT): List<MemoryResult> {
        val allowed = catalogsFor(agent)
            .filter { catalog == null || it.name.equals(catalog, ignoreCase = true) }
        if (allowed.isEmpty()) return emptyList()

        val byName = allowed.associateBy { it.id }
        val held = memories.findByCatalogIdInOrderByLastModifiedAtDesc(byName.keys)
        val wanted = wordsOf(query)

        /*
         * Asked nothing, as against asked something that came to nothing.
         *
         * A blank query is "show me what is there" and answers with the lot. A
         * query whose every word was dropped - "what is the" - is a question
         * that matched nothing, and handing back the whole catalogue there would
         * be answering something nobody asked. The two look identical after the
         * words are taken out, which is why this is asked of the query itself.
         */
        val found = when {
            query.isNullOrBlank() ->
                // What is there, newest change first, which is the order this
                // list has always come back in.
                held

            wanted.isEmpty() -> emptyList()

            else ->
                held.map { it to score(it, wanted) }
                    .filter { (_, score) -> score > 0 }
                    // Best first, and the newest of an equal pair first: `held`
                    // is already in that order and this sort is stable.
                    .sortedByDescending { (_, score) -> score }
                    .map { (memory, _) -> memory }
        }

        return found
            .take(limit.coerceIn(1, MAX_LIMIT))
            .map { memory ->
                MemoryResult(
                    catalog = byName.getValue(memory.catalogId).name,
                    title = memory.title,
                    content = memory.content,
                    addedBy = memory.createdBy,
                )
            }
    }

    /**
     * The words worth looking for, out of what was asked.
     *
     * One-letter words are dropped because they match everything, and so are the
     * few that carry no subject of their own: a question is mostly "what", "the"
     * and "is", and a memory scoring a point for each of those would be ranked
     * by how wordy it is rather than by what it is about.
     */
    private fun wordsOf(query: String?): List<String> =
        query?.lowercase()?.split(NOT_A_WORD).orEmpty()
            .filter { it.length > 1 && it !in EMPTY_WORDS }
            .distinct()

    /**
     * How well one memory answers those words.
     *
     * A word in the title is worth more than the same word in the body: a title
     * is what somebody filed it under, so a memory called "OPS abbreviation" is
     * more about OPS than one that mentions it in passing.
     */
    private fun score(memory: Memory, words: List<String>): Int {
        val title = memory.title.lowercase()
        val content = memory.content.lowercase()
        return words.sumOf { word ->
            when {
                carries(title, word) -> TITLE_WORTH
                carries(content, word) -> CONTENT_WORTH
                else -> 0
            }
        }
    }

    /**
     * Whether a text carries a word, by the word rather than by the letters.
     *
     * `contains` alone makes "ops" match "operations" and "cops", which is how a
     * three-letter query comes back with everything in the catalogue. So the
     * match is against the text's own words.
     *
     * One of the two may be a prefix of the other, which is as much stemming as
     * is worth having without a language to do it in - "abbreviations" finds
     * "abbreviation" and the other way round. Both directions, because which of
     * the two happens to be the plural is not something either side controls:
     * the question is typed by a person and the memory was written by whoever
     * wrote it.
     *
     * The shorter of the two has to be [STEM] long before a prefix counts. A
     * question asking about "ops" must not match a memory about "operations",
     * and without a floor the prefix rule brings back exactly what taking
     * `contains` out was for.
     */
    private fun carries(text: String, word: String): Boolean =
        text.split(NOT_A_WORD).any { held ->
            held == word ||
                (minOf(held.length, word.length) >= STEM && (held.startsWith(word) || word.startsWith(held)))
        }

    /**
     * Writes one memory into a catalog this agent holds.
     *
     * The same grant the search reads: an agent given a catalog can add to what
     * it holds, because "remember this" is the other half of "what do we know" —
     * and an agent that can only read is an agent whose lessons die with the
     * conversation. The catalog may be left unsaid only while the agent holds
     * exactly one, so nothing is ever filed somewhere by tie-break.
     *
     * A title already present in the catalog is updated rather than doubled:
     * the title is how a memory is addressed, and two under one name are one an
     * agent finds and one it never sees again. The author is the agent's name,
     * so the card and the audit both say who wrote it.
     *
     * Refusals are thrown in words the model can act on; the caller turns them
     * into an error result rather than a failed conversation.
     *
     * The audit line is [WorkspaceAuditRecorder.recordAutomated] and names the
     * agent, which is not a detail. `record` reads the signed-in user and
     * *fails* where there is none - and an agent answering a Slack message, a
     * workflow node or a task has none. So every save from those three came back
     * "No authenticated user to attribute this change to", and because the
     * audit throws inside this transaction the memory was rolled back with it:
     * the agent was told it could not remember, or told the person it had, and
     * nothing was ever written. It only worked from a chat, which is the one
     * place a person is signed in.
     *
     * The actor is the agent's name, which is what [Memory.createdBy] already
     * records - so the row and the log agree on who wrote it.
     */
    @Transactional
    fun save(agent: Agent, catalog: String?, title: String?, content: String?): MemorySaved {
        val allowed = catalogsFor(agent)
        if (allowed.isEmpty()) throw MemorySaveRefusedException("This agent has no memory catalog to write to")

        val into = when {
            catalog != null -> allowed.firstOrNull { it.name.equals(catalog, ignoreCase = true) }
                ?: throw MemorySaveRefusedException(
                    "This agent has no catalog called $catalog. It holds: " + allowed.joinToString { it.name },
                )

            allowed.size == 1 -> allowed.single()
            else -> throw MemorySaveRefusedException(
                "Say which catalog to save into. This agent holds: " + allowed.joinToString { it.name },
            )
        }

        val said = title?.trim().orEmpty()
        val kept = content?.trim().orEmpty()
        if (said.isEmpty()) throw MemorySaveRefusedException("A memory needs a title")
        if (said.length > MAX_TITLE) throw MemorySaveRefusedException("A title fits in $MAX_TITLE characters")
        if (kept.isEmpty()) throw MemorySaveRefusedException("A memory needs content")

        val now = java.time.OffsetDateTime.now()
        val existing = memories.findByCatalogIdAndTitle(into.id, said)
        if (existing != null) {
            existing.content = kept
            existing.lastModifiedAt = now
            existing.lastModifiedBy = agent.name
            auditRecorder.recordAutomated(
                into.workspaceId,
                WorkspaceAuditCategory.MEMORY,
                "Memory $said updated in ${into.name} by the agent ${agent.name}",
                actor = agent.name,
            )
            return MemorySaved(catalog = into.name, title = said, updated = true)
        }

        memories.save(
            Memory(
                catalogId = into.id,
                title = said,
                content = kept,
                createdAt = now,
                createdBy = agent.name,
                lastModifiedAt = now,
                lastModifiedBy = agent.name,
            ),
        )
        auditRecorder.recordAutomated(
            into.workspaceId,
            WorkspaceAuditCategory.MEMORY,
            "Memory $said added to ${into.name} by the agent ${agent.name}",
            actor = agent.name,
        )
        return MemorySaved(catalog = into.name, title = said, updated = false)
    }

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50

        /** A word in the title is worth three in the body; see `score`. */
        const val TITLE_WORTH = 3
        const val CONTENT_WORTH = 1

        /**
         * How much of a word has to be shared before a prefix counts as one.
         *
         * Four, which keeps "abbreviation" and "abbreviations" together and
         * keeps "ops" away from "operations" - the match the whole change was
         * about getting rid of.
         */
        const val STEM = 4

        /** Letters and digits are a word; everything else separates two. */
        val NOT_A_WORD = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Words that say nothing about a subject.
         *
         * Not a stop-word list in the linguistic sense - it is the handful that
         * turn up in every question anybody puts to an agent. Left in, they rank
         * a memory by how long it is rather than by what it is about.
         */
        val EMPTY_WORDS = setOf(
            "the", "a", "an", "and", "or", "of", "in", "on", "at", "to", "for", "from", "by", "with",
            "is", "are", "was", "were", "be", "do", "does", "did", "what", "which", "who", "how",
            "when", "where", "why", "it", "this", "that", "there", "about", "we", "you",
        )

        /** What the column takes; over it is refused in words rather than by the database. */
        const val MAX_TITLE = 200

        val DESCRIPTOR = ToolDescriptor(
            name = "memory_search",
            description = "Search what this workspace has written down. " +
                "Only the memory catalogs this agent has been given are searched. " +
                "Call with no query to see what a catalog holds.",
            parameters = listOf(
                ToolParameter("query", "What to look for, in the title or the body. Optional.", required = false),
                ToolParameter("catalog", "Restrict to one catalog by name. Optional.", required = false),
            ),
        )

        val SAVE_DESCRIPTOR = ToolDescriptor(
            name = "memory_save",
            description = "Write something down for this workspace to keep. " +
                "Saves into one of the memory catalogs this agent has been given; " +
                "a memory with the same title in that catalog is updated rather than doubled.",
            parameters = listOf(
                ToolParameter("title", "What to file it under, in a short line.", required = true),
                ToolParameter("content", "What to remember.", required = true),
                ToolParameter(
                    "catalog",
                    "Which catalog to save into, by name. Optional while the agent holds exactly one.",
                    required = false,
                ),
            ),
        )
    }
}

/** What one save came to, in the shape an agent is handed back. */
data class MemorySaved(
    val catalog: String,
    val title: String,
    /** True when a memory with that title already existed and was rewritten. */
    val updated: Boolean,
)

/** Said in words the model can act on; the tool loop turns it into an error result. */
class MemorySaveRefusedException(message: String) : RuntimeException(message)

/** One memory, in the shape an agent is handed it. */
data class MemoryResult(
    val catalog: String,
    val title: String,
    val content: String,
    val addedBy: String,
)

/**
 * What a built-in tool is, from the outside.
 *
 * Deliberately small: a name, what it is for, and what it takes. Enough for an
 * agent to be told it exists, and enough for a screen to list it beside the
 * workspace's own tools.
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
)

data class ToolParameter(
    val name: String,
    val description: String,
    val required: Boolean,
)
