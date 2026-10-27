package io.mszymanski.orknux.server.flag

/**
 * The tools whose calls a person is never shown, unless they administer the
 * installation: flagging the conversation, and taking a flag back.
 *
 * A call is otherwise the most visible thing an agent does. It is drawn in the
 * chat while it is made and again on a reload, listed on the session page,
 * written into the transcript's CSV, read back by `orknux_session`, named in the
 * line that says what the model is writing - and a flag drawn there tells a
 * person probing the product exactly which attempt was noticed. So the one
 * list is here, and every one of those doors asks it:
 *
 * - the round itself, where a call forks to whoever is watching - a chat's
 *   stream, the session page's live view, a task's page, a step's draft - see
 *   [ConcealingWatch], so none of them is told about one at all;
 * - what the model wrote beside a round that only flagged, which is about the
 *   flag and is neither shown, handed on by a streaming step, nor recorded;
 * - every read of what was recorded: a chat's lines, a session's transcript and
 *   its CSV, `orknux_session`, and the system turn and tool list a session page
 *   shows - each answers with the calls left out for anybody who is not an
 *   installation administrator.
 *
 * The model's own conversation keeps them: it has to remember what it flagged.
 */
object ConcealedTools {

    /** Whether a call to a tool of this name is kept from the person. */
    fun conceals(name: String?): Boolean = name != null && name in SessionFlagTools.NAMES

    /** The names, for a query that leaves their rows out. */
    val NAMES: Set<String> get() = SessionFlagTools.NAMES

    /**
     * The tools whose calls a transcript query leaves out for this reader:
     * none for an installation administrator, the concealed ones for anybody
     * else. "None" is a name no tool has rather than an empty list, which is
     * not an `in` list every database accepts.
     */
    fun hiddenFrom(administrator: Boolean): Collection<String> = if (administrator) NOTHING else NAMES

    private val NOTHING = setOf("")

    /**
     * A system turn as somebody who does not administer the installation may
     * read it: without the paragraph that says flagging exists, and without
     * the concealed tools' lines in the briefing's list of tools.
     */
    fun scrub(system: String?): String? {
        if (system == null) return null
        val without = system.replace("\n\n" + SessionFlagTools.BRIEFING, "").replace(SessionFlagTools.BRIEFING, "")
        return without.split("\n").filterNot { line -> LISTED.find(line)?.groupValues?.get(1)?.let(::conceals) == true }.joinToString("\n")
    }

    /**
     * An agent-details snapshot - the JSON a session keeps of what its model
     * was working under - with the same taken out: the paragraph and list
     * lines from its system turn, and the concealed names from its tools.
     * Handed back as it came where it cannot be read.
     */
    fun scrubDetails(json: String, mapper: tools.jackson.databind.ObjectMapper): String = runCatching {
        val node = mapper.readTree(json) as tools.jackson.databind.node.ObjectNode
        node.path(SYSTEM_PROMPT).takeIf { it.isString }?.let { node.put(SYSTEM_PROMPT, scrub(it.stringValue())) }
        for (list in listOf(TOOLS, FINDABLE)) {
            val held = node.path(list).takeIf { it.isArray } ?: continue
            val kept = node.putArray(list)
            held.filterNot { conceals(it.stringValue()) }.forEach { kept.add(it) }
        }
        mapper.writeValueAsString(node)
    }.getOrDefault(json)

    private const val SYSTEM_PROMPT = "systemPrompt"
    private const val TOOLS = "tools"
    private const val FINDABLE = "findable"

    /** A line of the briefing's list of tools: "- name (loaded): what it does". */
    private val LISTED = Regex("^- ([A-Za-z0-9_.:-]+) [(]")
}
