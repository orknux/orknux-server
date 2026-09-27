package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.server.agent.Agent
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
    private val connections: WorkspaceConnectionService,
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
) {

    /**
     * The system turn for this agent, or null when it has nothing to say — an
     * agent with no prompt and no skills is a model with a name on it, and an
     * empty system turn is worth fewer tokens than it costs.
     *
     * The agent's own prose first, then [grants] - what it was given and the
     * rules for using it.
     */
    fun of(agent: Agent): String? =
        listOfNotNull(agent.systemPrompt?.takeIf { it.isNotBlank() }, grants(agent))
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
    fun grants(agent: Agent): String? {
        val parts = mutableListOf<String>()

        val instructions = skills.list(agent)

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
                    append("You have ").append(instructions.size)
                    append(if (instructions.size == 1) " skill" else " skills")
                    append(" - pages describing how this workspace goes about things. You do not need ")
                    append("them to answer; call skill_list only when a request needs one or somebody asks ")
                    appendLine("what you can do.")
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
                if (named.isNotEmpty()) appendLine()
                /*
                 * The rest are not counted or pointed at. "There are 24 other
                 * skills - call skill_list for them" is the same invitation as
                 * above, and the model accepted it. They are reachable through
                 * skill_list when a request needs one, which the paragraph on
                 * commands below already says.
                 */
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
            val held = remembered.sumOf { it.memoryCount }
            parts += buildString {
                append("This workspace has written ").append(held)
                append(if (held == 1) " thing down" else " things down")
                append(" that you can read, in ")
                append(remembered.joinToString(", ") { "${it.name} (${it.memoryCount})" })
                appendLine(".")
                append("Search it with memory_search before answering anything about how this workspace ")
                append("works, what it has decided, or who its customers are - what is written down there ")
                append("is what somebody wanted you to know, and it beats what you would otherwise assume. ")
                appendLine("Write down with memory_save anything you are told that the next conversation would need.")
            }
        }

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
        val held = tools.getObject().specsFor(agent)
        if (held.isNotEmpty()) {
            val block = installation.toolSummariesFullUpTo()
            val step = installation.toolSummaryTrimPercent()
            val blocks = ((held.size - 1) / block).coerceAtLeast(0)
            val kept = (MOST_TOOL_SUMMARY_CHARS * (100 - blocks * step).coerceAtLeast(0) / 100)
                .coerceAtLeast(SHORTEST_SUMMARY)
            parts += buildString {
                /*
                 * Which tool searches, by name. Issue #526: this said "search
                 * for it by the name below" and never said with what. A skill
                 * told the agent to post with a tool that is findable and not
                 * offered; the agent held no such tool, had not been told
                 * find_tools was the way to it, and looped on the tools it did
                 * hold - no runaway session ever called find_tools.
                 */
                append("These are the tools you have, all of them, whether or not they are in front of you ")
                append("this turn. Where one you need is marked (load it first), call ")
                append(ToolSearchTools.FIND).append(" with its name from the list below - ")
                // An example from its own list: core does not name a plugin's tool.
                val example = held.minByOrNull { it.name }?.name ?: "its_name"
                append(ToolSearchTools.FIND).append(" with names [\"").append(example)
                append("\"], say - and it is yours ")
                append("from your next message. The marked ones are not in front of you until you do. ")
                append("If you do not know which tool a job needs, ").append(ToolSearchTools.SEARCH)
                append(" finds them by words about it. A tool that is not in this list is not one you have: ")
                appendLine("say so rather than claiming you can do what it would do.")
                /*
                 * Which of them are behind the search, marked. Issue #534:
                 * the list named every tool alike, so a model reading
                 * slack_whoIs in it believed it held it and tried to call it -
                 * and a server that holds the reply to the tools actually
                 * offered turned each attempt into a call to one that was.
                 * Session 510: "I'll call slack_whoIs", then todo_complete,
                 * seven times, the model watching itself do it.
                 */
                val behind = tools.getObject().offeringFor(agent).searchable.map { it.name }.toSet()
                held.sortedBy { it.name }.forEach { spec ->
                    val said = spec.summary?.trim()?.ifEmpty { null } ?: spec.description.trim()
                    appendLine()
                    append("- ").append(spec.name)
                    if (spec.name in behind) append(" (load it first)")
                    said.take(kept).trim().takeIf { it.isNotEmpty() }?.let { append(": ").append(it) }
                }
            }
        }

        val asks = runTools.getObject()
        if (asks.offered(agent)) {
            parts += buildString {
                append("You may put a question to another agent with ").append(AgentRunTools.ASK)
                append(", up to ").append(asks.limitFor(agent))
                appendLine(" times in this conversation. Spend them on work you have no tool for.")
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

        if (connectionTools.offered(agent)) {
            parts += buildString {
                append("You have been granted ").append(agent.connections.size)
                append(" connections. Where a tool takes a connection id, find the one you were told ")
                append("to use with `").append(ConnectionTools.FIND).append("` - and only pass an id ")
                appendLine("when you have been explicitly told to use that connection; otherwise leave ")
                appendLine("the tool to its configured default.")
            }
        }

        val reachable = if (connectionTools.recited(agent)) {
            agent.connections
                .mapNotNull { connections.workspaceConnection(it) }
                .filter { it.workspaceId == agent.workspaceId }
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
                }
                appendLine()
            }
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
}
