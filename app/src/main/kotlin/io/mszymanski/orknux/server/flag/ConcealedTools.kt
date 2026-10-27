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
 *   installation administrator; and a chat leaves them out for an
 *   administrator too, unless the installation says otherwise - [shownInChat].
 *
 * The model's own conversation keeps them: it has to remember what it flagged.
 */
object ConcealedTools {

    /** Whether a call to a tool of this name is kept from the person. */
    fun conceals(name: String?): Boolean = name != null && name in SessionFlagTools.NAMES

    /**
     * Whether this call is kept from the person: one to a concealed tool, or
     * one to a tool that reaches another by name - finding, describing,
     * loading or running it - with a concealed name among its arguments.
     * Session 936 loaded flag_session with tool_load before calling it, and
     * the load was drawn in the chat with the sentence announcing it: a call
     * that names the flag tells the person as surely as the flag does.
     */
    fun conceals(name: String?, arguments: String?): Boolean =
        conceals(name) || (name in REACHING && arguments != null && MENTIONED.containsMatchIn(arguments))

    /**
     * The recorded calls among [events] that [conceals] keeps from the person
     * through a reaching tool - rows a query by tool name cannot tell from an
     * ordinary load - by id, for a query that leaves them out.
     */
    fun reachingConcealed(events: Collection<io.mszymanski.orknux.server.llm.LlmSessionEvent>): Set<Long> =
        events.filter { it.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.TOOL && conceals(it.actor, it.content) }
            .mapNotNull { it.id }.toSet()

    /** The tools that act on another tool named in their arguments. */
    val REACHING: Set<String> = setOf(
        io.mszymanski.orknux.server.chat.ToolSearchTools.SEARCH,
        io.mszymanski.orknux.server.chat.ToolSearchTools.DESCRIBE,
        io.mszymanski.orknux.server.chat.ToolSearchTools.FIND,
        io.mszymanski.orknux.server.chat.ToolSearchTools.FORMERLY,
        io.mszymanski.orknux.server.chat.ToolRun.NAME,
    )

    /** A concealed name as a whole word, so `flag_session` is not found inside `unflag_session` or anything longer. */
    private val MENTIONED = Regex(
        SessionFlagTools.NAMES.joinToString("|", "(?<![A-Za-z0-9_])(?:", ")(?![A-Za-z0-9_])") { Regex.escape(it) },
    )

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
     * The rows a transcript query leaves out for this reader besides the
     * concealed tools' own: the calls that reach one by name - see
     * [reachingConcealed]. None for an installation administrator, given as an
     * id no row has, since an empty `in` list is not one every database accepts.
     */
    fun hiddenRows(
        administrator: Boolean,
        session: Long,
        events: io.mszymanski.orknux.server.llm.LlmSessionEventRepository,
    ): Collection<Long> {
        if (administrator) return NO_ROWS
        val found = reachingConcealed(
            events.findBySessionIdAndKindAndActorIn(session, io.mszymanski.orknux.server.llm.LlmSessionEventKind.TOOL, REACHING),
        )
        return found.ifEmpty { NO_ROWS }
    }

    private val NO_ROWS = setOf(-1L)

    /**
     * Whether a chat shows this reader the calls: never anybody who does not
     * administer the installation, and an administrator only while the
     * installation has not hidden them in chats - which by default it has
     * ([io.mszymanski.orknux.server.attachment.InstallationSettings.concealedCallsHiddenInChats]).
     * Asked each time a chat is drawn, so switching it applies on the next
     * reload. The session page and its CSV ask [hiddenFrom] instead: they are
     * where an administrator goes to look at a flag, and stay as they were.
     * The live round needs no asking - [ConcealingWatch] tells nobody.
     */
    fun shownInChat(administrator: Boolean, hiddenInChats: () -> Boolean): Boolean = administrator && !hiddenInChats()

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
