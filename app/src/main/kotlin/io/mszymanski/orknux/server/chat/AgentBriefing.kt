package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.Chattiness
import io.mszymanski.orknux.server.agent.MOST_TOOL_SUMMARY_CHARS
import io.mszymanski.orknux.server.agent.SkillTool
import org.springframework.stereotype.Service

/**
 * The shortest a trimmed line is allowed to get. Issue #481.
 *
 * A phrase cut to nothing is a name with a colon after it, which is worse than
 * a name on its own: it reads as a description that failed to load. Eight
 * characters is about two words, which is the least that can still say
 * something.
 */
private const val SHORTEST_SUMMARY = 8

/** The built-in skill on building an HTML page, named in the briefing. Issue #557. */
private const val HTML_SKILL = "Complex HTML"

/**
 * What an agent is told before anything is said to it.
 *
 * An agent is a configuration, not a model: it names the model that answers, the
 * instructions it works under, and the skills it has been granted. This turns
 * that into the one system turn both request shapes already understand, so
 * chatting with an agent needs no new path through [ModelChatClient].
 *
 * Skills are listed here, not spelled out. Each one is a page of markdown, and
 * an agent granted five catalogs would spend most of its context on instructions
 * for work it is not doing — so the briefing gives the names and what each is
 * for, and the agent loads the one that applies with [SkillTool].
 *
 * Memory is named here but not spelled out, which is the same trade and was not
 * always the arrangement. It used to say nothing at all, on the reasoning that
 * memory "is looked up when it turns out to be needed" - and nothing ever told
 * the agent it would turn out to be needed. The result was a feature that
 * worked and was used once: agents searched when somebody said "check your
 * memory" and never otherwise. What is here now is how much is written down and
 * where, which is a line, plus when to go and look.
 */
@Service
class AgentBriefing(
    /**
     * Asked rather than queried, so the briefing lists exactly what
     * `skill_list` will list and `skill_load` will load — including the skills
     * a plugin brought. This used to read the catalogs itself, which meant two
     * places deciding what an agent had been granted and only one of them
     * knowing about plugins.
     */
    private val skills: SkillTool,
    /** What this agent has been written down for it; see the note in `of`. */
    private val memories: io.mszymanski.orknux.server.memory.MemoryTool,
    /** Which of the two ways the granted connections are told; see [ConnectionTools]. */
    private val connectionTools: ConnectionTools,
    /**
     * How many other agents this one may ask, and whether it may ask at all.
     *
     * Through a provider rather than wired in: [AgentRunTools] reaches the
     * conversation, and the conversation reaches this, so a direct edge is a
     * cycle Spring refuses at startup. Asked for at the moment a briefing is
     * written, by which time everything exists. Issue #476.
     */
    private val runTools: org.springframework.beans.factory.ObjectProvider<AgentRunTools>,
    /**
     * Every tool this agent holds, for the inventory below. Issue #481.
     *
     * Through a provider for the reason the asks are: [AgentTools] reaches the
     * conversation and the conversation reaches this, so a direct edge is a
     * cycle refused at startup.
     */
    private val tools: org.springframework.beans.factory.ObjectProvider<AgentTools>,
    /** For the command marker the agent advertises its skills under. Issue #381. */
    private val workspaces: io.mszymanski.orknux.server.workspace.WorkspaceRepository,
    /** The installation's default marker, where the workspace has none. Issue #402. */
    private val installation: io.mszymanski.orknux.server.attachment.InstallationSettings,
    /** What a turn starts out carrying, for the marks on the list; see [ToolCeilings]. */
    private val ceilings: org.springframework.beans.factory.ObjectProvider<ToolCeilings>,
    /** Whether the workspace has any diagrams, for the paragraph that sends an agent to them. */
    private val diagrams: io.mszymanski.orknux.server.diagram.DiagramRepository,
) {

    /**
     * The system turn for this agent, or null when it has nothing to say — an
     * agent with no prompt and no skills is a model with a name on it, and an
     * empty system turn is worth fewer tokens than it costs.
     *
     * The agent's own prose first, then [grants] - what it was given and the
     * rules for using it.
     */
    fun of(agent: Agent, chattiness: Chattiness = agent.chattiness, session: Long? = null): String? =
        listOfNotNull(agent.systemPrompt?.takeIf { it.isNotBlank() }, grants(agent, chattiness, session))
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")

    /**
     * What the agent was given, and the standing rules for using it: its skills
     * and their commands, its memory, its connections. Null where it was given
     * nothing worth a sentence.
     *
     * Apart from [of] because a workflow node may replace the agent's own prose
     * with its own, and when it does this must not go with it: the skills an
     * agent can load and the commands people reach it by are facts about the
     * agent, not part of a persona. A node that overrides the prompt still
     * appends this, so a Slack bot with a bespoke voice still knows its own
     * commands and still says so. Issue #381.
     */
    fun grants(agent: Agent, chattiness: Chattiness = agent.chattiness, session: Long? = null): String? {
        val parts = mutableListOf<String>()

        /*
         * How a turn works, said first and to every agent.
         *
         * Nothing told a model how it exists here, so it assumed what a person
         * assumes: that it goes on after it stops talking. Seen in production -
         * an agent delegated, said it would "check the status later", and ended
         * its turn with nothing to bring it back, so the answer it asked for
         * landed with nobody to read it. The same model waited correctly when
         * told to act every five seconds; what it lacked was not the tool but
         * the fact that there is no later unless it makes one. That is true of
         * every agent in every context, which is why it is here and not in a
         * tool's description.
         */
        /*
         * And when to plan, beside it, where the agent holds the list: every
         * turn in a session lends it now (see [SessionSheds]), and a model that
         * was never told when to reach for it reached for it never, or for a
         * one-line answer.
         */
        parts += if (BuiltInTools.granted(agent, TodoTools.ADD) && BuiltInTools.granted(agent, TodoTools.COMPLETE)) {
            HOW_YOU_RUN + " " + PLAN_WITH_TODOS
        } else {
            HOW_YOU_RUN
        }
        /*
         * And to say what it is doing while it does it. A model on a long piece
         * of work thought for minutes and called tool after tool in silence:
         * what it writes beside a call reaches the person as it happens, its
         * thinking may not, and one that writes nothing leaves them guessing
         * whether it is working at all.
         */
        parts += when (chattiness) {
            Chattiness.NONE -> REPORT_NOTHING
            Chattiness.LOW -> REPORT_ONCE
            Chattiness.MEDIUM -> REPORT_PROGRESS
            Chattiness.HIGH -> REPORT_EVERYTHING
        }

        /*
         * Only where the agent can read one. The server's own skills reach every
         * agent, so this paragraph was written for one with the skill tools
         * hidden too - the research writer, told to call skill_list, wrote the
         * call out as its report. A model told of a tool it does not have
         * believes it, and acts on it however it can.
         */
        val readsSkills = BuiltInTools.granted(agent, io.mszymanski.orknux.server.agent.SkillTool.LOAD.name)
        val instructions = if (readsSkills) skills.list(agent) else emptyList()

        if (instructions.isNotEmpty()) {
            /*
             * Each skill with the command that asks for it, and the agent told
             * to say so. A person in Slack cannot see the skill list; the only
             * way they learn that `!review` exists is the agent telling them,
             * and the only way `!review` in a message means anything where
             * the graph did not map it is the agent loading the skill itself.
             * Issue #381.
             */
            val marker = workspaces.findById(agent.workspaceId).map { it.commandMarker }.orElse(null)
                ?: installation.commandMarker()
            /*
             * The ones marked Always, and only those. Issue #521.
             *
             * Every granted skill used to be named here with its description,
             * which is the same mistake the tool list made before `find_tools`:
             * a menu of everything, in front of the model, every turn. With
             * twenty-five of them it stopped being a list to choose from and
             * became a list to work through - sessions 474 and 477 loaded the
             * lot rather than answering "are you there?".
             *
             * So the three states mean for a skill what they mean for a tool.
             * Always is in the briefing, because somebody said this one matters
             * whatever the agent is doing. Offer is reachable and not named,
             * through `skill_list` and `skill_load`, which is one deliberate
             * call rather than a permanent cost. Hidden is neither, as before.
             */
            val named = skills.always(agent)
            parts += buildString {
                if (named.isEmpty()) {
                    /*
                     * Said without an invitation. It read "call skill_list to see
                     * them", and a model told there are pages it has not seen
                     * goes and looks: session 493 answered "hi" with eighty-nine
                     * skill_list calls. Listing is for when a request needs a
                     * skill or somebody asks what the agent can do.
                     */
                    /*
                     * And then the other way. Issue #620: "call skill_list only
                     * when a request needs one" left the judgement to the model,
                     * and a model asked to do something judged that it did not -
                     * agents answered from what they already believed and
                     * read a skill only when it was marked Always. So it is told
                     * when, the way memory_search is below: before the work,
                     * not before every message. What made #493 list eighty-nine
                     * times was an invitation with no bound; this one excludes
                     * a greeting and is said to happen once.
                     */
                    append("You have ").append(instructions.size)
                    append(if (instructions.size == 1) " skill" else " skills")
                    appendLine(" - pages describing how this workspace goes about things.")
                    appendLine(LOOK_FOR_SKILLS)
                } else {
                    /*
                     * Every one of them, and said as an instruction rather than
                     * an invitation. Issue #521.
                     *
                     * This first read "load the one that applies", which is a
                     * judgement call, and a model asked "who are you?" decided
                     * none applied and answered without its own workspace's
                     * rules - then reasoned in as many words that loading was
                     * something commands asked for. Always means always: the
                     * person who marked a skill that way has already made the
                     * judgement, and leaving it to be made again each turn is
                     * what Offer is for.
                     */
                    /*
                     * Once per conversation, not once per turn. It said "every
                     * turn", which reloaded the same pages on every message and
                     * put back into the context exactly the seventeen kilobytes
                     * #521 took out of the system prompt.
                     */
                    append("These skills are in force here. Load each of them with skill_load before your ")
                    append("first answer in this conversation, whether or not the request looks like it ")
                    append("needs them. Once one is loaded, do not load it again - what it said still ")
                    append("holds. They are listed rather than written out to keep this short: the lines ")
                    appendLine("below say what each one is, not what it says.")
                }
                named.forEach { skill ->
                    append("\n- ").append(skill.name).append(" (").append(marker).append(skill.key).append(")")
                    skill.description?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                }
                if (named.isNotEmpty()) {
                    appendLine()
                    /*
                     * The rest are still not counted - "there are 24 other
                     * skills" was an invitation with no bound, and the model
                     * accepted it. What it is given instead is when to look,
                     * the same rule as an agent with none marked. Issue #620.
                     */
                    appendLine(LOOK_FOR_SKILLS)
                }
                append("\nEvery skill has a command: the marker and its id, like ")
                append(marker).append(instructions.first().id).append(". ")
                append("Anybody can write one anywhere in a message to have you load and follow that skill. ")
                append("When a message carries a command, call skill_load for the id after the marker as ")
                append("well, unless you have already loaded it in this conversation - as well as the ones ")
                append("above, not instead of them - and follow what it says, ")
                append("however simple the request looks. A command is the person saying how they want ")
                append("this answered, so answering without reading it answers the wrong question. Load ")
                append("the ones the message names and the ones above, and no others. ")
                append("This is the one special ")
                append("syntax people have with you - so when they ask what you can do, or how to use ")
                append("commands, tell them these commands and this ").append(marker)
                appendLine("id syntax rather than saying there is none.")
                /*
                 * And where to get the list again. This paragraph is written once,
                 * at the top of a conversation that may run for hours, and what it
                 * listed is a dozen turns back by the time somebody asks what the
                 * commands are - so an agent asked for its commands was answering
                 * from memory of a list it had half forgotten, or from the skills
                 * loaded into that one turn, which is one skill and reads as a bug.
                 * `skill_list` is the same list, live, and asking for it costs a
                 * call. Issue #471.
                 */
                append("\nWhen somebody asks what commands you take, call skill_list - it returns every ")
                appendLine("skill with its id, and it is what to answer from.")
                /*
                 * And where two of them answer to one command. Issue #473: an
                 * id is unique inside a plugin and inside the workspace, and
                 * nothing makes it unique across two plugins - so the command
                 * means whichever the order picked, and the other was out of
                 * reach with nobody told. Said here because this paragraph is
                 * where the commands are, and said as the way out of it: the
                 * catalog written in front of the id reaches either one.
                 */
                instructions.filter { it.alsoIn.isNotEmpty() }.distinctBy { it.id.lowercase() }.forEach { one ->
                    appendLine()
                    append("More than one of your skills answers to ").append(marker).append(one.id)
                    append(": the command loads the one in ").append(one.catalog).append(", and ")
                    append(one.alsoIn.joinToString(" and ")).append(" hold another of that id. ")
                    append("Ask skill_load for ").append(one.alsoIn.first()).append(":").append(one.id)
                    appendLine(" to read that one instead.")
                }
            }

        /*
         * And the ones that are not a choice. Issue #480: a skill marked Always
         * is in force for every turn, so its page goes in here rather than
         * waiting for the agent to decide to load it. Some instructions are not
         * "read this when it applies" but "this is how you work here", and an
         * agent deciding whether to read those has already half missed them.
         *
         * After the list and not instead of it: the skill keeps its line and
         * its command, because a person can still write the command and because
         * an agent that sees the page but not the name cannot tell anybody what
         * it is following. It is marked as already loaded so nothing spends a
         * call re-reading it.
         */
        /*
         * And never the page itself. Issue #521.
         *
         * A skill marked Always used to have its whole text written in here, on
         * the reasoning that some instructions are "this is how you work" rather
         * than "read this when it applies". The reasoning is sound and the
         * implementation was not: one Slack skill came to 17,206 characters and
         * 348 of the briefing's 467 lines - sixty-one per cent of the system
         * prompt, on every turn, in every conversation.
         *
         * What that does to a model is measurable. With the page inlined, five
         * of six runs answering "are you there?" went off loading skills until
         * the context died; with it loaded on demand instead, six of six
         * answered in one round. A prompt that is mostly one document produces
         * a model that thinks its job is documents.
         *
         * So Always now means what it means for a tool: named in the list
         * above, where a line costs a line. Its page arrives through skill_load
         * like any other, which is one call and is bounded by what the turn
         * needs.
         */
        }

        /*
         * That there is anything written down at all.
         *
         * This said nothing, on the reasoning that memory "is looked up when it
         * turns out to be needed" - but nothing ever told the agent it would
         * turn out to be needed. It had a tool description among twenty others
         * and no reason to believe the catalogue held anything, so it searched
         * when somebody said "check your memory" and never otherwise: the
         * feature worked and was used once.
         *
         * Named and counted rather than listed. What makes an agent look is
         * knowing there is something to find, and that is a line - where the
         * memories themselves are the thing this deliberately does not inline,
         * for the reason the skills above are not spelled out either.
         *
         * The instruction is the other half. "You may search" is a capability
         * and changes nothing; what changes behaviour is being told when, and
         * the when is before answering from what it already believes.
         */
        val remembered = memories.catalogsFor(agent).filter { it.memoryCount > 0 }
        if (remembered.isNotEmpty()) {
            /*
             * Named but not counted. The counts were here, and they moved every
             * time anybody in the workspace saved a memory - the agent itself
             * included - which changed the system turn, and a provider caching
             * the prompt by its prefix then read the whole conversation again.
             */
            parts += buildString {
                append("This workspace has written things down that you can read, in ")
                append(remembered.joinToString(", ") { it.name })
                appendLine(".")
                append("Search it with memory_search before answering anything about how this workspace ")
                append("works, what it has decided, or who its customers are - what is written down there ")
                append("is what somebody wanted you to know, and it beats what you would otherwise assume. ")
                append("Write down with memory_save anything you are told that the next conversation would need")
                val writable = memories.catalogsFor(agent).map { it.name }
                appendLine(if (writable.size > 1) ", naming the catalog: one of ${writable.joinToString(", ")}." else ".")
            }
        }

        diagramsParagraph(agent)?.let { parts += it }

        /*
         * The connections this agent may name, and the rule for naming one.
         *
         * A grant is permission, not encouragement: a tool that takes a
         * connection has a configured default, and an agent that started
         * second-guessing that default because a list of alternatives was in
         * its briefing would be doing exactly what granting them did not mean.
         * So the instruction is explicit and restrictive, and stands right
         * next to the list it governs.
         *
         * Read by id and dropped silently where the id no longer answers -
         * a deleted connection is not this turn's problem, and the settings
         * page is where a stale grant is reported.
         */
        /*
         * Recited while they are few, and pointed at once they are many.
         *
         * A handful of lines in a system turn is nothing and a round trip is
         * not free, so a short list stays exactly where it was. A long one is
         * paid for on every round of every turn for something most turns never
         * read, and past that point the agent is better off asking. See
         * [ConnectionTools].
         */
        /*
         * Whether it may hand any of this to another agent, said outright.
         *
         * Issue #476: a workspace that allows none takes `ask_agent` off the
         * list, which is correct and silent - and a model that has been told
         * nothing assumes the ordinary thing, so agents went on planning to
         * delegate, telling people work had been handed over, and waiting for
         * an answer nobody was writing. A tool that is absent is not a sentence
         * anybody reads; this is.
         *
         * Both halves are said. The number is what the issue asked for - an
         * agent that knows it has two asks spends them on the two things worth
         * asking about - and the refusal is what stops the pretending.
         */
        /*
         * What it holds, by name, always. Issue #481.
         *
         * The request carries the tools it may call, and that used to be the
         * whole of what an agent knew: with a ceiling set, only some of them
         * travel at a time and the rest are behind a search - so an agent
         * looking for something reached for words it hoped existed, and gave up
         * where nothing matched. The inventory is the fix: every tool it holds,
         * named, with a phrase saying what it is for, in the one place that is
         * always in front of the model.
         *
         * Trimmed rather than dropped where an agent holds a great many. The
         * two numbers are the installation's, because what a system prompt can
         * afford is a fact about its models and its plugins: lines are whole up
         * to the first, and each further block of that many costs the second in
         * percent. The front of the phrase is what survives, which is what the
         * editor tells whoever writes one.
         */
        /*
         * What this conversation is offered, not what the agent holds in
         * general: a task is not offered tools_request, and a list naming it
         * there had a model believe it held one, look for it and loop.
         */
        /*
         * And marked by what a turn starts out carrying, by the rule the round
         * uses - see [ToolCeilings.split]. It once read a different ceiling
         * from the round's, so the list said "(load it first)" of tools the
         * request carried and the model loaded them, a round each.
         */
        val all = tools.getObject()
        val ceilingNow = ceilings.getObject().of(agent)
        val holding = all.offeringFor(agent, ceilingNow, session)
        val start = ceilings.getObject().split(agent, agent.modelId, holding, emptyList()) { all.offeringFor(agent, it, session) }
        val held = holding.core + holding.searchable
        if (held.isNotEmpty()) {
            val block = installation.toolSummariesFullUpTo()
            val step = installation.toolSummaryTrimPercent()
            val blocks = ((held.size - 1) / block).coerceAtLeast(0)
            val kept = (MOST_TOOL_SUMMARY_CHARS * (100 - blocks * step).coerceAtLeast(0) / 100)
                .coerceAtLeast(SHORTEST_SUMMARY)
            /*
             * Every tool in hand from the first round: they all fit in the
             * request, so there is nothing to find or load - and telling a
             * model to load what it holds is how it spends a round loading it.
             * See [ToolCeilings].
             */
            val allInHand = !start.hunting
            parts += buildString {
                if (allInHand) {
                    appendLine(ALL_IN_HAND)
                } else {
                    // An example from its own list: core does not name a plugin's tool.
                    appendLine(behindSearch(held.minByOrNull { it.name }?.name ?: "its_name"))
                }
                /*
                 * Which of them are behind the search, marked. Issue #534:
                 * the list named every tool alike, so a model reading
                 * slack_whoIs in it believed it held it and tried to call it -
                 * and a server that holds the reply to the tools actually
                 * offered turned each attempt into a call to one that was.
                 * Session 510: "I'll call slack_whoIs", then todo_complete,
                 * seven times, the model watching itself do it.
                 */
                val behind = if (allInHand) emptySet() else start.findable.map { it.name }.toSet()
                held.sortedBy { it.name }.forEach { spec ->
                    val said = spec.summary?.trim()?.ifEmpty { null } ?: spec.description.trim()
                    appendLine()
                    append("- ").append(spec.name)
                    // And the ones in hand, said too, so a model does not try to load them. Issue #542.
                    append(if (spec.name in behind) " (load it first)" else " (loaded)")
                    said.take(kept).trim().takeIf { it.isNotEmpty() }?.let { append(": ").append(it) }
                }
            }
        }

        /*
         * Where building a page is written down, named the way delegating is.
         * Issue #557: asked for an HTML report with pictures and charts, a model
         * that never read the skill shipped a page with none of them in it.
         */
        // Every agent holds the built-in catalog, so this is said to every agent.
        run {
            val marker = workspaces.findById(agent.workspaceId).map { it.commandMarker }.orElse(null)
                ?: installation.commandMarker()
            parts += "Before you build an HTML page or report, load the " + HTML_SKILL + " skill (" + marker +
                io.mszymanski.orknux.server.agent.SkillKeys.derive(HTML_SKILL) +
                "): it says how its pictures, charts and diagrams go in and how it is handed over."
            /*
             * And changing a repository in a box, named the same way: session
             * 629 cloned, edited, never built and pushed to main, the page
             * that says not to on offer and never read.
             */
            if (agent.boxLabels.isNotEmpty()) {
                parts += "Before you change, commit or push anything in a repository in a box, load the " +
                    "box-project-changes skill (" + marker + io.mszymanski.orknux.server.agent.BuiltInSkills.PROJECT_CHANGES +
                    "): build and test before committing, work on a new branch, never push to main or master, " +
                    "and open a pull request."
            }
        }

        /*
         * Where its secrets go, said once: a model that holds a credential's
         * name and not its value reaches for the value - asks the person, or
         * puts it in a command line - unless it is told the name is the way.
         */
        if (agent.secretCatalogs.isNotEmpty()) parts += SECRETS_BY_NAME

        val asks = runTools.getObject()
        if (asks.offered(agent)) {
            parts += buildString {
                append("You may put a question to another agent with ").append(AgentRunTools.ASK)
                append(", up to ").append(asks.limitFor(agent))
                append(" times in this conversation. Spend them on work you have no tool for. ")
                // Where the how is written down, loaded when it is needed rather than carried. Issue #541.
                appendLine("Before you ask, load the delegating skill: it says how to write the question and use the answer.")
            }
        } else if (agent.agents.isNotEmpty()) {
            /*
             * Said only where somebody granted agents and something else - a
             * limit of zero, or every one of them switched off - takes the tool
             * away. An agent nobody gave anybody to needs no sentence about it,
             * and every prompt in the product is not the place to pay for one.
             */
            parts += "You cannot hand any part of this to another agent in this conversation: this " +
                "workspace allows none. Do the work yourself rather than saying you will pass it on."
        }

        if (connectionTools.pointedAt(agent)) {
            parts += buildString {
                append("You have been granted ").append(agent.connections.size)
                append(" connections. Where a tool takes a connection id, find the one you were told ")
                append("to use with `").append(ConnectionTools.FIND).append("` - and only pass an id ")
                appendLine("when you have been explicitly told to use that connection; otherwise leave ")
                appendLine("the tool to its configured default.")
            }
        }

        val reachable = if (connectionTools.recited(agent)) {
            connectionTools.granted(agent)
        } else {
            emptyList()
        }
        if (reachable.isNotEmpty()) {
            parts += buildString {
                append("These connections have been granted to you. Where a tool takes a connection id, ")
                append("only pass one of these when you have been explicitly told to use that connection - ")
                appendLine("otherwise leave the tool to its configured default.")
                reachable.forEach { connection ->
                    append("\n- ").append(connection.name)
                    append(" (").append(connection.type.name).append(", id ").append(connection.id).append(")")
                    // What it is for, which is how two connections to one system are told apart.
                    connection.description?.let { append(": ").append(it) }
                }
                appendLine()
            }
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }


    /**
     * That the workspace keeps diagrams, and when to look in them - said beside
     * memory, for the reason memory is said: agents searched memory because they
     * were told when to, and a diagram of how the systems fit together that
     * nothing sends them to is a diagram nobody's agent reads.
     *
     * Only where there is something to find and the agent holds the search -
     * in hand or behind the tool search, which is a question of its marks and
     * not of this turn - so it is the same on every round of a conversation and
     * the prompt's cached prefix holds. The invitation to change a diagram goes
     * with the change tool, and says which diagrams take it rather than naming
     * them: which ones are read only is a setting that moves, and the list tool
     * says it at the moment it matters. Every tool is named off its declaration.
     */
    internal fun diagramsParagraph(agent: Agent): String? {
        val held = { name: String -> agent.orknuxAccess && name !in agent.hiddenTools }
        val search = io.mszymanski.orknux.server.mcp.DiagramTools.SEARCH
        if (!held(search) || !diagrams.existsByWorkspaceId(agent.workspaceId)) return null
        val node = io.mszymanski.orknux.server.mcp.DiagramTools.NODE
        val change = io.mszymanski.orknux.server.mcp.DiagramTools.CHANGE
        val list = io.mszymanski.orknux.server.mcp.DiagramTools.LIST
        return buildString {
            append("This workspace keeps diagrams of how its systems, processes and architecture fit together")
            if (held(list)) {
                append(", each with a ").append(io.mszymanski.orknux.server.mcp.DiagramTools.DESCRIPTION)
                append(" saying what it is for - ").append(list).append(" lists them, so pick the one that fits")
            }
            append(". ")
            append("Search them with ").append(search).append(" before answering anything about how its parts connect, ")
            append("what calls what, or where something lives")
            if (held(node)) {
                append(", and read a node with ").append(node).append(" when a hit looks relevant - its notes say what the box cannot")
            }
            append(".")
            if (held(change)) {
                append(" When you learn something a diagram lacks or has wrong, update it with ").append(change)
                append(" - add a node or a connection, fix a label, write in a node's notes - where that would help the ")
                append("next reader, and say in your answer what you changed. Only a diagram whose ")
                append(io.mszymanski.orknux.server.mcp.DiagramTools.AGENTS).append(" is ")
                append(io.mszymanski.orknux.server.mcp.DiagramTools.CHANGES).append(" takes changes")
                if (held(list)) append("; ").append(list).append(" says which")
                append(".")
            }
        }
    }

    companion object {
        /** The paragraph over the list of tools where every one goes out from the first round. */
        const val ALL_IN_HAND =
            "These are the tools you have, all of them, and every one is in front of you. " +
                "A tool that is not in this list is not one you have: say so rather than claiming you can do what it would do."

        /** How [behindSearch] starts, so the list can be re-marked without knowing its example. */
        const val BEHIND_SEARCH_START = "These are the tools you have, all of them, whether or not they are in front of you"

        /**
         * The paragraph over the list of tools where some are behind the search.
         *
         * Which tool searches, by name. Issue #526: this said "search for it by
         * the name below" and never said with what, and no runaway session ever
         * called find_tools.
         *
         * And that a load holds while the marks do not move. The marks are what
         * the conversation started out carrying, so the system turn is the same
         * after a load as before it; a model that read "(load it first)" beside
         * a tool it had just loaded would otherwise load it again.
         */
        fun behindSearch(example: String): String =
            BEHIND_SEARCH_START + " this turn. Where one you need is marked (load it first), call " +
                ToolSearchTools.FIND + " with its name from the list below - " + ToolSearchTools.FIND +
                " with names [\"" + example + "\"], say - and it answers with how the tool is called. Then call it " +
                "with " + ToolRun.NAME + ", giving its name and its arguments. Once " + ToolSearchTools.FIND +
                " says one is loaded it stays yours for the rest of this conversation, still marked here as it " +
                "was: call it with " + ToolRun.NAME + " rather than loading it again. If you do not know which " +
                "tool a job needs, " + ToolSearchTools.SEARCH + " finds them by words about it, and " +
                ToolSearchTools.DESCRIBE + " shows one in full before you load it. A tool that is not in this " +
                "list is not one you have: say so rather than claiming you can do what it would do."

        /**
         * Said after [ALL_IN_HAND] where some of the list arrived after the
         * conversation's tool array was made: that array is fixed for the
         * session, so they are reached through tool_run. See
         * [io.mszymanski.orknux.server.llm.SessionFrames].
         */
        val ARRIVED_LATE: String =
            "Those marked (" + BuiltInTools.THROUGH_RUN + ") became yours after this conversation began, so they " +
                "are not in front of you: call each with " + ToolRun.NAME + ", giving its name and its arguments."

        /**
         * When to look at the skills it was not told to load. Issue #620; see
         * where [grants] says it.
         */
        const val LOOK_FOR_SKILLS =
            "Before you start on a piece of work - anything more than a greeting, a thanks or a question " +
                "about what was just said - call skill_list, and load with skill_load every skill whose " +
                "description covers what you were asked, then follow it. A skill is how this workspace wants " +
                "that kind of work done, and it beats what you would otherwise assume. Do this once per " +
                "conversation: the list does not change, so for later requests choose from the one you read."


        /** How an agent granted secrets uses them; see [grants]. */
        const val SECRETS_BY_NAME =
            "You may use some of the workspace's secrets without ever seeing them: variable_list names them, and " +
                "orkbox_exec or orkbox_open takes them by name in secrets, setting each as an environment variable " +
                "of the same name for the command, such as \$GITHUB_TOKEN. Never ask for a secret's value or write " +
                "one into a command, a file or an answer; anything that prints one shows ***."

        /** How a turn works; see [grants]. */
        const val HOW_YOU_RUN =
            "How you run: you act only during a turn. A turn starts when somebody writes to you, or when " +
                "a wake-up you set comes due. When it ends you stop completely - nothing carries on in the " +
                "background and nothing reminds you. So never say you will do something later, check back " +
                "or follow up unless you make it happen: do it now, in this turn, or, where you have to wait " +
                "for something one of your tools can see, set a watcher with watcher_set, where you have " +
                "it - it calls the tool for you and starts a turn when the result matches. Otherwise wait " +
                "before you finish, or, as a last resort, end your turn with a wake-up, where one is " +
                "offered, and say when you will be back. Where you can do none of these, say so plainly " +
                "rather than promising."

        /** Saying what it is doing while it works; see [grants]. */
        /*
         * The four levels, each saying plainly what is shown and when. "Every
         * few calls" and "say once" read as suggestions: a model given them
         * wrote three lines where it was asked for one, because nothing said
         * what a line beside a call is. Each now says that every such line is
         * sent at once as a message of its own, which is the fact the model
         * was missing, and gives the count as a rule.
         */

        /** What an agent whose progress messages are None is told instead of [REPORT_PROGRESS]. */
        const val REPORT_NOTHING =
            "Whoever asked sees what you write, never what you think, and wants only your answer. Write " +
                "no text at all beside your tool calls - not a plan, not a status, not a single word: call " +
                "your tools in silence, and put everything you have to say in your one answer at the end."

        /** What an agent whose progress messages are Low is told instead of [REPORT_PROGRESS]. */
        const val REPORT_ONCE =
            "Whoever asked sees what you write, never what you think, and every line you write beside a " +
                "tool call is sent to them at once as a message of its own. For work that takes more than a " +
                "couple of tool calls, write exactly one short line, beside your first call, saying what you " +
                "are about to do. After that, write no text beside any call - not one more line, whatever you " +
                "find - until your answer at the end."

        /** What an agent whose progress messages are High is told instead of [REPORT_PROGRESS]. */
        const val REPORT_EVERYTHING =
            "Whoever asked sees what you write, never what you think, and every line you write beside a " +
                "tool call is sent to them at once as a message of its own - they want to follow along. " +
                "Beside every tool call, without exception, write one short line on what you are doing and " +
                "why, and once a result is back, say in that next line what it showed. The full answer comes " +
                "at the end."

        /** What an agent whose progress messages are Medium is told: what every agent was told before there were levels. */
        const val REPORT_PROGRESS =
            "Whoever asked sees what you write, never what you think, and every line you write beside a " +
                "tool call is sent to them at once as a message of its own. For work that takes more than a " +
                "couple of tool calls, write one short line beside your first call saying what you are about " +
                "to do, then one short line beside every third call or so on what you found and what is next " +
                "- the way a colleague keeps you posted, no more often. The full answer comes at the end."

        /** When the to-do list is worth keeping; said after [HOW_YOU_RUN] where the agent holds it. */
        const val PLAN_WITH_TODOS =
            "For work of more than a few steps, write the steps down with " + TodoTools.ADD + " first and " +
                "tick each off with " + TodoTools.COMPLETE + " as it lands; for a quick answer, skip the list."
    }
}
