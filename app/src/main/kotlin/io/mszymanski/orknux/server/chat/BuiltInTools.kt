package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.FinishAnswerTools
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.memory.MemoryTool
import io.mszymanski.orknux.server.workflow.StepPictureTools
import org.springframework.stereotype.Service

/**
 * What decides whether an agent is offered one of the server's own tools.
 *
 * Issue #444. Every tool the server brings itself is switched somewhere, and
 * until this the somewheres were five: a name in the agent's grant list, three
 * booleans on its row, a catalog grant, an access grant, and "always" for the
 * handful the round loop lent without asking. The agent's Tools list - the one
 * place somebody reads to see what an agent may do - showed three of them.
 *
 * There are two kinds now, and the kind is declared beside the name.
 *
 * [GRANT] is a tool switched on the Tools list like any other: by its name in
 * [Agent.tools], carried on every turn where it is in [Agent.requiredTools] and
 * findable otherwise, exactly as a workspace's or a plugin's tool is. Everything
 * an agent could once be handed without asking is this kind. The others are a
 * tool that comes with a wider grant - the skill tools with the skill catalogs,
 * the memories with the memory catalogs, `orknux_*` with orknux access - and is
 * listed so the reader sees it, but switched where the grant is switched: two
 * switches for one thing is how they disagree.
 */
enum class BuiltInGovernance {
    /** By its name in the agent's Tools list; carried or found per the Always mark. */
    GRANT,

    /**
     * The same, but off until somebody asks for it, and with no unsafe-
     * visibility gate in front of the switch. Issues #509 and #510.
     *
     * For the built-ins that reach outside this installation. They are not
     * what the product is built on - nothing breaks without them - and they
     * cost money, make requests to addresses that a page may have suggested,
     * and belong to an administrator's decision rather than to a default.
     */
    GRANT_REACHING,

    /**
     * On by default like [GRANT], but with no unsafe-visibility gate in front
     * of the switch: a built-in nothing else depends on, whose absence the
     * agent does not notice. `rate_session`, so far.
     */
    GRANT_OPTIONAL,

    /** Offered while the agent holds any skill catalog. */
    SKILL_CATALOGS,

    /** Offered while the agent holds any memory catalog. */
    MEMORY_CATALOGS,

    /**
     * Offered while the agent has orknux access, and then each switched by name
     * - hidden, offered, or carried where it is marked Always - like [GRANT].
     */
    ORKNUX_ACCESS,

    /**
     * Offered while the agent holds one of the HTTP tools and they are switched
     * on in Admin Settings. Issue #602: `http_allowList`, which answers where
     * those tools may go - a question nobody without one of them can have.
     */
    HTTP_TOOLS,

    /**
     * Offered while the agent holds any box label. The box tools are one
     * family - opening a session is pointless without the tools that use it -
     * so the label grant switches them, and none can be hidden on its own.
     * Each is Offer or Always while a label is held, which is the agent's mark.
     */
    BOX_ACCESS,

    /**
     * Offered while the agent holds any secret or variable catalog: the
     * variable tools, and the secrets and values `orkbox_exec` takes, come with
     * those grants.
     */
    VARIABLE_CATALOGS,

    /**
     * Offered while the agent may ask for more tools: listing what it could be
     * granted and asking for it. See [GrantRequestTools].
     */
    MAY_ASK_FOR_TOOLS,
}

/** One of the server's own tools, and what switches it. */
data class BuiltInTool(val name: String, val governance: BuiltInGovernance)

/**
 * The one list of the tools this server brings itself. Issue #444.
 *
 * Both readers read it: the agent form draws a row per entry, and the round
 * offers or withholds by the same names. A tool added to one and not the other
 * was how `note_to_self` came to be something every agent had and nobody could
 * see; a tool the form lists and the server never offers would be the same
 * fault the other way round, which is why the names here are the constants the
 * tools themselves are called by rather than a second spelling of them.
 *
 * The grant-bound kinds are read off the services that own them, so a
 * tool added to the orknux surface is a row here without anybody remembering
 * to add it.
 */
@Service
class BuiltInTools(
    private val skills: SkillTool,
    private val memories: MemoryTool,
    private val orknux: OrknuxTools,
    private val boxes: io.mszymanski.orknux.server.box.BoxTools,
    private val variables: io.mszymanski.orknux.server.variable.VariableTools,
) {

    /**
     * The orknux_* tools, each of which an agent granted Orknux access may hide.
     *
     * Asked for: the grant was all or nothing, so an agent that should read
     * runs but not start one could not be given that. Hiding one is the careful
     * direction, like a tool that reaches the network, so the workspace's unsafe
     * switch does not gate it.
     */
    fun orknuxNames(): List<String> = orknux.specs(OrknuxScope(workspaceId = 0, mayWrite = true)).map { it.name }

    /** The orknux_* tools a new agent starts with hidden, for its owner to turn on; see [OrknuxTools.startsHidden]. */
    fun orknuxStartsHidden(): Set<String> = orknux.startsHidden()

    /** The box tools' names, for the marks an agent holding a label may carry. */
    fun boxNames(): List<String> = boxes.specs().map { it.name }

    /** Every built-in, the grant-governed first and in the order the form lists them. */
    fun all(): List<BuiltInTool> = buildList {
        GRANTED.forEach { add(BuiltInTool(it, if (it in OPTIONAL) BuiltInGovernance.GRANT_OPTIONAL else BuiltInGovernance.GRANT)) }
        REACHING.forEach { add(BuiltInTool(it, BuiltInGovernance.GRANT_REACHING)) }
        add(BuiltInTool(HTTP_ALLOW_LIST, BuiltInGovernance.HTTP_TOOLS))
        add(BuiltInTool(memories.descriptor().name, BuiltInGovernance.MEMORY_CATALOGS))
        add(BuiltInTool(memories.saveDescriptor().name, BuiltInGovernance.MEMORY_CATALOGS))
        add(BuiltInTool(memories.updateDescriptor().name, BuiltInGovernance.MEMORY_CATALOGS))
        add(BuiltInTool(memories.deleteDescriptor().name, BuiltInGovernance.MEMORY_CATALOGS))
        /*
         * As an agent is offered them: an agent's scope may write, since starting
         * a workflow is most of what the grant is for, and has nobody at a screen
         * to show a proposal to. The list is what the names are; the workspace
         * in the scope is read by nothing here.
         */
        orknux.specs(OrknuxScope(workspaceId = 0, mayWrite = true)).forEach {
            add(BuiltInTool(it.name, BuiltInGovernance.ORKNUX_ACCESS))
        }
        boxes.specs().forEach { add(BuiltInTool(it.name, BuiltInGovernance.BOX_ACCESS)) }
        variables.specs().forEach { add(BuiltInTool(it.name, BuiltInGovernance.VARIABLE_CATALOGS)) }
        GrantRequestTools.SPECS.forEach { add(BuiltInTool(it.name, BuiltInGovernance.MAY_ASK_FOR_TOOLS)) }
    }

    companion object {

        /**
         * The built-ins switched by name in the agent's Tools list, in the order
         * the form lists them and a new agent is given them.
         *
         * Every name here is one the round used to hand out without asking:
         * lent by whatever was running the agent, or offered by [AgentTools]
         * behind a boolean on the row. A new agent starts with all of them on
         * and Always, so what it is offered is what it was offered before this
         * list existed; V302 gives every agent that predates it the same.
         *
         * `draw_picture` was already a grant here and keeps its place. The two
         * base64 tools travel with `save_artifact` in [AgentTools] but are rows
         * of their own: a row per name is the rule, and a name a model can call
         * that the form does not show is the fault this list is for.
         */
        val GRANTED: List<String> = listOf(
            NoteTools.NOTE,
            TodoTools.ADD,
            TodoTools.LIST,
            TodoTools.REORDER,
            TodoTools.NOTE,
            TodoTools.COMPLETE,
            DateTools.NOW,
            // The installation's address and absolute links; V390 marks it Always.
            LinkTools.LINK,
            TimerTools.SET,
            // Listing and calling off a reminder; V362 marks them Always.
            TimerTools.LIST,
            TimerTools.CANCEL,
            /*
             * Watchers, #606: on by default like the timer beside them, since
             * a watcher calls only the agent's own tools with its own grants,
             * bounded by Admin Settings -> Watchers. See WatcherTools.
             */
            io.mszymanski.orknux.server.watcher.WatcherTools.SET,
            io.mszymanski.orknux.server.watcher.WatcherTools.LIST,
            io.mszymanski.orknux.server.watcher.WatcherTools.FINISH,
            // Changing one after a look at its result, #618; V344 marks it Always.
            io.mszymanski.orknux.server.watcher.WatcherTools.UPDATE,
            /*
             * Schedules: a reminder that repeats, on by default like the timer
             * it repeats, since it reaches nothing but the agent's own note and
             * is bounded by Admin Settings -> Schedules. V389 marks them Always.
             */
            io.mszymanski.orknux.server.schedule.ScheduleTools.SET,
            io.mszymanski.orknux.server.schedule.ScheduleTools.LIST,
            io.mszymanski.orknux.server.schedule.ScheduleTools.CANCEL,
            // The rating a person gave, while Reports > Ratings is on; V403 marks it Always.
            io.mszymanski.orknux.server.rating.RatingTools.RATE,
            // And taking one back when they ask; V413 marks it Always beside it.
            io.mszymanski.orknux.server.rating.RatingTools.REMOVE,
            /*
             * The skill tools are built-ins like the rest rather than a view of
             * the catalog grants: the skills the server brings reach every agent
             * whatever it was granted, and without these an agent with no catalog
             * ticked could not reach even `!caveman`.
             */
            io.mszymanski.orknux.server.agent.SkillTool.LIST.name,
            io.mszymanski.orknux.server.agent.SkillTool.LOAD.name,
            io.mszymanski.orknux.server.agent.SkillTool.SEARCH.name,
            ScratchpadTools.LIST,
            ScratchpadTools.READ,
            ScratchpadTools.WRITE,
            ScratchpadTools.APPEND,
            ScratchpadTools.REPLACE,
            ScratchpadTools.SEARCH,
            ScratchpadTools.DELETE,
            AgentTools.SAVE_ARTIFACT,
            AgentTools.VIEW,
            AgentTools.BASE64_ENCODE,
            AgentTools.BASE64_DECODE,
            ZipTools.ZIP_FILES,
            ZipTools.ZIP_EXTRACT,
            AgentTools.VALIDATE,

            /*
             * And the tools the release embeds. Named here rather than read off
             * the bundles, because this list is what the Tools screen draws and
             * a screen that changed shape with a resource file would be a
             * screen nobody could reason about. The set is fixed per release,
             * which is what makes writing it down honest. Issue #501.
             */
            "pdf_fromHtml",
            "pdf_fromHtmlZip",
            "pdf_read",
            // Finding a passage without reading the document whole; V490 marks it Always.
            io.mszymanski.orknux.server.embedded.PdfCapability.SEARCH_TOOL,
            "pdf_preview",
            "pdf_toPng",
            "charts_render",
            "diagram_render",
            "svg_draw",
            "svg_toPng",
            "svg_read",
            /*
             * Questions about a large SVG or XML file, answered without the
             * file entering the turn - chat 981, a KiCad copper layer read
             * whole. V490 marks them Always.
             */
            io.mszymanski.orknux.server.embedded.SvgCapability.MEASURE_TOOL,
            io.mszymanski.orknux.server.embedded.XmlCapability.QUERY_TOOL,
            "sheet_read",
            "midi_read",
            "midi_write",
            "midi_render",
            "markdown_toText",
            "date_today",
            "date_now",
            "date_describe",
            "date_shift",
            "date_shiftBusinessDays",
            "date_between",
            "date_businessDaysBetween",
            "date_isBusinessHours",
            ConnectionTools.FIND,
            AgentRunTools.ASK,
            QuestionTools.ASK,
            AgentRunTools.ASKS,
            AgentRunTools.LIST,
            AgentRunTools.WAIT,
            FinishAnswerTools.FINISH,
            StepPictureTools.DRAW,
            StepPictureTools.LINK,
        )

        private val GRANTED_SET = GRANTED.toSet()

        /**
         * The built-ins that reach outside this installation, off until somebody
         * says otherwise. Issues #509 and #510.
         *
         * Everything in [GRANTED] is on by default and stays on, because the
         * product cannot stand behind an agent missing its own tools - that is
         * what the unsafe-visibility switch is about. These are the opposite
         * case and want the opposite rule.
         *
         * **Off by default**, because they make requests to somewhere else.
         * Fetching a URL and searching the web are not like drawing a chart:
         * they cost somebody money, they can be pointed at an address a page
         * suggested, and an agent that reads pages for a living is exactly the
         * thing you would not hand an unattended fetch to. An administrator who
         * wants one turns it on, having decided.
         *
         * **And freely switchable**, with no unsafe-visibility gate in front.
         * That gate exists to stop somebody quietly crippling an agent by
         * hiding what it needs; switching *off* a tool that reaches the network
         * is the safe direction, and making somebody accept a scary workspace
         * setting before they can do the cautious thing teaches the wrong
         * lesson about the setting.
         */
        val REACHING: List<String> = listOf(
            "http_get",
            "http_request",
            HTTP_DOWNLOAD,
            "web_search",
            "web_searchImages",
        )

        private val REACHING_SET = REACHING.toSet()

        /**
         * The built-ins in [GRANTED] a new agent starts with at Offer rather
         * than Always: held, and found through the tool search once a tool
         * limit is set, rather than carried on every turn. Asked for: an agent
         * under a limit carried sixty tools it rarely used in front of the model.
         *
         * Rating a conversation, the picture tools, the MIDI tools and the
         * format readers past the two basic ones. `pdf_read` and `sheet_read`
         * stay Always, because they are what the note on an attached PDF or
         * spreadsheet tells the model to call, and those are the files people
         * attach most; an SVG, an XML file or a MIDI file is rarer, and its
         * reader is found when one arrives.
         *
         * Only where an agent starts: one that exists keeps the marks it has.
         * The orknux_* and orkbox_* tools start at Offer too; see [startsOffered].
         */
        val STARTS_OFFERED: Set<String> = setOf(
            io.mszymanski.orknux.server.rating.RatingTools.RATE,
            io.mszymanski.orknux.server.rating.RatingTools.REMOVE,
            // Pictures: looking at one, drawing one, linking one, and turning a format into one.
            AgentTools.VIEW,
            StepPictureTools.DRAW,
            StepPictureTools.LINK,
            "svg_draw",
            "svg_toPng",
            "pdf_toPng",
            "pdf_preview",
            "charts_render",
            "diagram_render",
            "midi_read",
            "midi_write",
            "midi_render",
            // The readers that answer about a file rather than read it whole, and the rarer formats'.
            "svg_read",
            io.mszymanski.orknux.server.embedded.SvgCapability.MEASURE_TOOL,
            io.mszymanski.orknux.server.embedded.XmlCapability.QUERY_TOOL,
            io.mszymanski.orknux.server.embedded.PdfCapability.SEARCH_TOOL,
            AgentTools.VALIDATE,
        )

        /** The box tools, which follow Offer and Always like the orknux_* tools while the agent holds a box label. */
        private val BOX_NAMES: Set<String> = io.mszymanski.orknux.server.box.BoxTools.SPECS.map { it.name }.toSet()

        /** Whether this is one of the box tools, carried or found by its mark while the agent holds a label. */
        fun box(name: String): Boolean = name in BOX_NAMES

        /**
         * Whether a new agent, or one given the grant afresh - Orknux access, a
         * box label - starts with this tool at Offer rather than Always. The one
         * place that says so: [STARTS_OFFERED], every orknux_* tool, and every
         * orkbox_* tool. A tool that starts hidden is hidden whatever this says.
         */
        fun startsOffered(name: String): Boolean = name in STARTS_OFFERED || orknux(name) || box(name)

        /** The built-ins in [GRANTED] a new agent carries Always: the rest start at Offer. */
        fun startingAlways(): List<String> = GRANTED.filterNot(::startsOffered)

        /**
         * The built-ins held at Always whatever was sent, while the workspace
         * keeps its built-ins fixed: the form draws their rows fixed at Always,
         * and [io.mszymanski.orknux.server.agent.AgentAPI] puts the mark back
         * from every door.
         *
         * `finish_answer` first (V330): an agent under a ceiling holding it at
         * Offer promised to check back later and could not. And the skill tools
         * since session 896: the briefing tells every agent to call skill_list
         * and skill_load before it starts work, and a Qwen agent under a limit
         * found them marked "(load it first)", asked to be granted skill_list,
         * was told it could not be, and never read a skill. A tool the briefing
         * makes the first step is not one to be found. V600 marks the agents
         * that predate it.
         */
        val FIXED_ALWAYS: Set<String> = setOf(
            FinishAnswerTools.FINISH,
            SkillTool.LIST.name,
            SkillTool.LOAD.name,
            SkillTool.SEARCH.name,
        )

        /** Whether this built-in is held at Always while the workspace keeps its built-ins fixed; see [FIXED_ALWAYS]. */
        fun fixedAlways(name: String): Boolean = name in FIXED_ALWAYS

        /**
         * The built-ins in [GRANTED] that may be hidden without the workspace's
         * unsafe switch. The switch guards what an agent is built on - a
         * scratchpad, a clock, a way to say it has finished - and sending a
         * review on is none of those: an agent without it behaves as one does
         * while ratings are off in Admin > Reports.
         */
        val OPTIONAL: Set<String> = setOf(
            io.mszymanski.orknux.server.rating.RatingTools.RATE,
            io.mszymanski.orknux.server.rating.RatingTools.REMOVE,
        )

        /**
         * The three that make requests, which Admin Settings -> HTTP tools
         * switches and fences. Issue #602. Not `web_search`: a search engine's
         * address is not one a model picks.
         */
        val HTTP: List<String> = listOf("http_get", "http_request", HTTP_DOWNLOAD)

        /** Fetching an address's bytes into a key: named where a model is told how a file at an address comes in. */
        const val HTTP_DOWNLOAD = "http_download"

        /**
         * What answers where those may go. Issue #602.
         *
         * Not a grant of its own, in [GRANTED] or [REACHING]: it is offered
         * exactly while the agent holds one of [HTTP] and they are switched
         * on, because the policy is a question only an agent that can make a
         * request has, and a model told about a fence it has no way to reach
         * is a model handed a tool that answers about nothing. Derived, so no
         * migration gives it to anybody - it arrives with the HTTP tools on
         * the agents that already hold them.
         */
        const val HTTP_ALLOW_LIST = "http_allowList"

        /** Whether this name is one of the built-ins switched on the Tools list. */
        fun switchable(name: String): Boolean = name in GRANTED_SET || name in REACHING_SET

        /** Whether this one reaches outside, and so is off until it is asked for. */
        fun reaches(name: String): Boolean = name in REACHING_SET

        /**
         * Whether this agent may be offered a tool of this name.
         *
         * True for every name that is not a grant-governed built-in: a
         * workspace's tool, a plugin's, a shed's own like `task_done`, and the
         * catalog- and access-bound built-ins are all decided elsewhere, and
         * this says nothing about them.
         *
         * A grant-governed one is offered unless the agent has it hidden, which
         * is the way round issue #455 settled on: a list of what is allowed
         * answers "no" for every built-in written after it, so one added in a
         * later release would arrive switched off everywhere and an agent made
         * without the names would hold none. A list of what is refused answers
         * "yes" to a name nobody has ever had an opinion about, which is what a
         * built-in ought to be.
         */
        fun granted(agent: Agent, name: String): Boolean = when {
            /*
             * Named to be had, rather than named to be refused. The inverted
             * list below is right for a tool the product wants every agent to
             * hold; it is wrong for one that reaches the network, where a name
             * nobody has had an opinion about must answer "no".
             */
            name in REACHING_SET -> name in agent.tools
            name == HTTP_ALLOW_LIST -> HTTP.any { it in agent.tools }
            else -> name !in GRANTED_SET || name !in agent.hiddenTools
        }

        /** The built-ins this agent holds, in the order the list declares them. */
        fun grantedTo(agent: Agent): List<String> =
            GRANTED.filter { it !in agent.hiddenTools } + REACHING.filter { it in agent.tools }

        /** The ones it does not, which is what the agent stores; anything else given is ignored. */
        fun hiddenBy(given: Collection<String>): MutableList<String> =
            GRANTED.filterNot { it in given }.toMutableList()

        /**
         * Whether a tool of this name travels on every turn, or is found.
         *
         * The rule [AgentTools.offeringFor] keeps for every other tool, applied
         * to the built-ins: only an agent carrying a ceiling of its own drops
         * anything, and under one what is marked Always is carried and the rest
         * is looked for, and the orknux_* and orkbox_* tools likewise. A name
         * that is none of these is always carried here, because whether *it* is
         * found is somebody else's decision.
         */
        fun carried(agent: Agent, name: String, ceiling: Int? = agent.maxTools): Boolean =
            /*
             * The policy travels with any HTTP tool, even where those are only
             * found: it is one small tool, and it is the answer to "where can
             * you send requests". Carried only where they were, a model with the
             * HTTP tools behind a search answered that from the links it had
             * seen instead, and never looked.
             */
            if (name == HTTP_ALLOW_LIST) HTTP.any { it in agent.tools } else
            (name !in GRANTED_SET && name !in REACHING_SET && !orknux(name) && !box(name)) ||
                ceiling == null ||
                name in agent.requiredTools

        /**
         * Whether this is one of the orknux_* tools, which follow the same
         * Hide, Offer, Always as the grant-governed built-ins while the agent
         * has Orknux access. They used to travel on every turn whatever was
         * marked, so an agent under a ceiling could not leave one to be found;
         * V424 marked them Always on the agents that predate that.
         */
        fun orknux(name: String): Boolean = name.startsWith(ORKNUX_PREFIX)

        /** OrknuxTools.PREFIX, which its private companion keeps to itself; the inventory test holds the two together. */
        private const val ORKNUX_PREFIX = "orknux_"

        /**
         * The shed as this agent may use it: every tool it lends that the agent
         * has hidden is neither declared nor answered.
         *
         * The lenders keep lending what they always did - a note, a to-do list,
         * the clock, a scratchpad - and the grant is applied once, here, where
         * the round takes the shed. That is what makes hiding `note_to_self` on
         * an agent's page mean the server stops declaring it, with no lender
         * having to learn about grants. A hidden name falls through to the
         * agent's own tools and is refused there as a tool that does not exist,
         * which for this agent is true.
         *
         * Null in, null out; a shed with nothing left is still a shed, because
         * what it holds is asked again every round and the caller's is the one
         * that knows when.
         */
        /**
         * The briefing with the lent tools put into its list of tools. Issue #546.
         *
         * That list is built from the agent's own tools before there is a
         * session, and closes with "a tool that is not in this list is not one
         * you have" - and a shed's tools are lent per session, so none of them
         * was in it. Session 520: asked three times to zip a report, the model
         * found no zip in its list and said it could not, while zip_files was
         * one tool_load away. Merged into the one list, in its order, rather
         * than a second list after it: two lists of your tools is a question
         * about which one counts.
         */
        /*
         * And every line marked by what the turn starts out carrying, with the
         * paragraph above the list saying whether there is anything to load.
         * The briefing marks its own tools by the same rule, but it is written
         * without the lent ones, and they count towards the provider's cap - so
         * the marks are settled here, where the whole of what is sent is known.
         * Never by what has been loaded within the turn: the system turn stays
         * as it was after a tool_load, because a provider caching the prompt by
         * its prefix reads all of it again for one changed byte. At the next
         * turn the loaded tool is carried, and this marks it so - see
         * [ConversationFrame.joined], which calls this on a list already marked.
         */
        /*
         * The paragraph over a list with things to load is written again with
         * it, by [loading] - what a load holds is the provider's to say, and a
         * setting changed since the briefing was written is said from the turn
         * it changed.
         */
        fun listed(system: String?, lent: List<ToolSpec>, carried: Set<String>, hunting: Boolean, loading: LoadedTools = LoadedTools.JOIN): String? {
            if (system == null) return null
            val lines = system.split(NL)
            val listed = lines.indices.filter { ENTRY.containsMatchIn(lines[it]) }
            if (listed.isEmpty()) return system
            val first = listed.first()
            val last = listed.last()
            val present = listed.mapNotNull { ENTRY.find(lines[it])?.groupValues?.get(1) }.toSet()
            val added = lent.filter { it.name !in present }.map { spec ->
                val said = spec.summary?.trim()?.ifEmpty { null }
                    ?: spec.description.trim().substringBefore(". ").take(LENT_SUMMARY_CHARS)
                "- " + spec.name + " (loaded)" + (if (said.isEmpty()) "" else ": " + said)
            }
            /*
             * And a third mark, for a tool the session's array does not carry
             * though nothing is left to be found: one that arrived after the
             * array was made, which is fixed for the session and reached
             * through tool_run. See [io.mszymanski.orknux.server.llm.SessionFrames].
             */
            fun marked(line: String): String {
                val entry = ENTRY.find(line) ?: return line
                val name = entry.groupValues[1]
                val mark = when {
                    name in carried -> LOADED
                    hunting -> TO_LOAD
                    else -> THROUGH_RUN
                }
                return "- " + name + " (" + mark + ")" + line.substring(entry.range.last + 1)
            }
            val merged = (lines.subList(first, last + 1) + added).map(::marked)
                .sortedBy { ENTRY.find(it)?.groupValues?.get(1) ?: it }
            val late = merged.any { ENTRY.find(it)?.groupValues?.get(2) == THROUGH_RUN }
            val head = lines.subList(0, first).toMutableList()
            val said = head.indexOfLast { it.startsWith(AgentBriefing.ALL_IN_HAND) || it.startsWith(AgentBriefing.BEHIND_SEARCH_START) }
            if (said >= 0) {
                // The first of the list as it is sent, so the paragraph's example is a line the model can see.
                val example = merged.firstNotNullOfOrNull { ENTRY.find(it)?.groupValues?.get(1) } ?: "its_name"
                head[said] = when {
                    !hunting && late -> AgentBriefing.ALL_IN_HAND + " " + AgentBriefing.ARRIVED_LATE
                    !hunting -> AgentBriefing.ALL_IN_HAND
                    else -> AgentBriefing.behindSearch(example, loading)
                }
            }
            return (head + merged + lines.subList(last + 1, lines.size)).joinToString(NL)
        }

        /** The marks a line of the briefing's list of tools carries. */
        const val LOADED = "loaded"
        const val TO_LOAD = "load it first"
        val THROUGH_RUN = "call it with " + ToolRun.NAME

        /** Whether [line] is one tool's line in the briefing's list of tools. */
        fun isListEntry(line: String): Boolean = ENTRY.containsMatchIn(line)

        /** A line of the briefing's list of tools: "- name (loaded): what it does". */
        private val ENTRY = Regex("^- ([A-Za-z0-9_.:-]+) [(](" + LOADED + "|" + TO_LOAD + "|" + Regex.escape(THROUGH_RUN) + ")[)]")

        private val NL = 10.toChar().toString()

        /** How much of a lent tool's description stands in for a summary it does not have. */
        private const val LENT_SUMMARY_CHARS = 100

        fun lentTo(agent: Agent, shed: ToolShed?): ToolShed? {
            if (shed == null) return null
            return object : ToolShed {
                override fun specs(): List<ToolSpec> = shed.specs().filter { granted(agent, it.name) }

                /*
                 * And what the shed wanted said about its tools, which this has
                 * to carry rather than answer null to: a shed briefs the model
                 * about what it is lending (#445, the scratchpads), and a
                 * wrapper that forgot to pass it on would take the paragraph
                 * away from every agent while leaving the tools in place.
                 */
                override fun briefing(): String? = shed.briefing()

                override fun handles(name: String): Boolean = granted(agent, name) && shed.handles(name)

                override fun run(call: ToolCall): String = shed.run(call)
            }
        }
    }
}
