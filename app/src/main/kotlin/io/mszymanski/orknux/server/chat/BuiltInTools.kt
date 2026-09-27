package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.FinishAnswerTools
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.memory.MemoryTool
import io.mszymanski.orknux.server.shell.ShellTools
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
 * an agent could once be handed without asking is this kind. The other four are
 * a tool that comes with a wider grant - the skill tools with the skill catalogs,
 * the memories with the memory catalogs, `orknux_*` with orknux access, the
 * shells with shell access - and is listed so the reader sees it, but switched
 * where the grant is switched: two switches for one thing is how they disagree.
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

    /** Offered while the agent holds any skill catalog. */
    SKILL_CATALOGS,

    /** Offered while the agent holds any memory catalog. */
    MEMORY_CATALOGS,

    /** Offered while the agent has orknux access. */
    ORKNUX_ACCESS,

    /** Offered while the agent has shell access. */
    SHELL_ACCESS,
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
 * The four grant-bound kinds are read off the services that own them, so a
 * tool added to the orknux surface is a row here without anybody remembering
 * to add it.
 */
@Service
class BuiltInTools(
    private val skills: SkillTool,
    private val memories: MemoryTool,
    private val orknux: OrknuxTools,
    private val shells: ShellTools,
) {

    /** Every built-in, the grant-governed first and in the order the form lists them. */
    fun all(): List<BuiltInTool> = buildList {
        GRANTED.forEach { add(BuiltInTool(it, BuiltInGovernance.GRANT)) }
        REACHING.forEach { add(BuiltInTool(it, BuiltInGovernance.GRANT_REACHING)) }
        skills.descriptors().forEach { add(BuiltInTool(it.name, BuiltInGovernance.SKILL_CATALOGS)) }
        add(BuiltInTool(memories.descriptor().name, BuiltInGovernance.MEMORY_CATALOGS))
        add(BuiltInTool(memories.saveDescriptor().name, BuiltInGovernance.MEMORY_CATALOGS))
        /*
         * As an agent is offered them: an agent's scope may write, since starting
         * a workflow is most of what the grant is for, and has nobody at a screen
         * to show a proposal to. The list is what the names are; the workspace
         * in the scope is read by nothing here.
         */
        orknux.specs(OrknuxScope(workspaceId = 0, mayWrite = true)).forEach {
            add(BuiltInTool(it.name, BuiltInGovernance.ORKNUX_ACCESS))
        }
        shells.specs().forEach { add(BuiltInTool(it.name, BuiltInGovernance.SHELL_ACCESS)) }
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
            AgentTools.VALIDATE,

            /*
             * And the tools the release embeds. Named here rather than read off
             * the bundles, because this list is what the Tools screen draws and
             * a screen that changed shape with a resource file would be a
             * screen nobody could reason about. The set is fixed per release,
             * which is what makes writing it down honest. Issue #501.
             */
            "pdf_fromHtml",
            "pdf_read",
            "pdf_preview",
            "pdf_toPng",
            "charts_render",
            "diagram_render",
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
            "http_download",
            "web_search",
            "web_searchImages",
        )

        private val REACHING_SET = REACHING.toSet()

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
         * is looked for. A name that is not a grant-governed built-in is always
         * carried here, because whether *it* is found is somebody else's decision.
         */
        fun carried(agent: Agent, name: String): Boolean =
            (name !in GRANTED_SET && name !in REACHING_SET) ||
                agent.maxTools == null ||
                name in agent.requiredTools

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
        fun listed(system: String?, agent: Agent, lent: List<ToolSpec>): String? {
            if (system == null || lent.isEmpty()) return system
            val lines = system.split(NL)
            val listed = lines.indices.filter { ENTRY.containsMatchIn(lines[it]) }
            if (listed.isEmpty()) return system
            val first = listed.first()
            val last = listed.last()
            val present = listed.mapNotNull { ENTRY.find(lines[it])?.groupValues?.get(1) }.toSet()
            val added = lent.filter { it.name !in present }.map { spec ->
                val said = spec.summary?.trim()?.ifEmpty { null }
                    ?: spec.description.trim().substringBefore(". ").take(LENT_SUMMARY_CHARS)
                "- " + spec.name + (if (carried(agent, spec.name)) " (loaded)" else " (load it first)") +
                    (if (said.isEmpty()) "" else ": " + said)
            }
            val merged = (lines.subList(first, last + 1) + added)
                .sortedBy { ENTRY.find(it)?.groupValues?.get(1) ?: it }
            return (lines.subList(0, first) + merged + lines.subList(last + 1, lines.size)).joinToString(NL)
        }

        /** A line of the briefing's list of tools: "- name (loaded): what it does". */
        private val ENTRY = Regex("^- ([A-Za-z0-9_.:-]+) [(](loaded|load it first)[)]")

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
