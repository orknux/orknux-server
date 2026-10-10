package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.util.Locale

/**
 * How much of a session may go back in front of a model, in one object.
 *
 * The five numbers that used to be `private const` in [LlmSessionRecorder].
 * They were sized against one installation's models and one installation's
 * tools - the eight thousand characters any single lookup was allowed came from
 * a workspace's open issues measuring under five thousand and every issue in it
 * measuring over forty, which is a fact about `orknux_issues` rather than about
 * anybody else's tools - and nobody could change them without a rebuild.
 *
 * **Where the setting lives, and why.** A budget is a share of a context
 * window, so it is two things owned by two different rows and it is stored as
 * two things:
 *
 * - The **window** belongs to the model, and is already on it: `contextWindow`
 *   on `llm_model`. An installation runs models whose windows differ by an
 *   order of magnitude, so a single number set once for the installation is
 *   generous on one of them and a provider error on the next. Nothing new was
 *   stored for this half; it was already there and merely unused.
 * - The **share** belongs to the agent, and is `agent.memory_share`. What a
 *   budget should be depends on what that agent's tools give back: an agent
 *   reading whole files needs a different allowance from one reading issue
 *   lists, and both may be pointed at the same model.
 * - And where the agent says nothing, the share belongs to the workspace:
 *   `workspace.default_memory_share`. The per-agent setting is the right place
 *   to make an exception and the wrong place to state a policy - an
 *   installation that wants its agents to remember more than the built-in
 *   allowance was saying so once per agent and again on every agent made
 *   afterwards. So the order is **agent, then workspace, then built-in**, and a
 *   workspace that sets nothing leaves every agent exactly where it was.
 *
 * There is deliberately no `ORKNUX_` variable for any of it. An installation-
 * wide number is the one answer that is wrong for every model in the
 * installation, which is the thing this replaces rather than a cheaper spelling
 * of it. The workspace default is not that number wearing a different hat: it
 * is a share rather than a count, so it still means something different against
 * each of the workspace's models, and it is one workspace's decision rather
 * than the whole installation's.
 *
 * **One number, not five.** Somebody setting this is answering "how much
 * conversation should it carry", and four of the five fall out of that answer:
 * see [of]. Only [results] does not, because it is a read-ahead rather than a
 * budget - the reason is on it.
 *
 * **Characters, converted at the surface.** Every count here is characters,
 * which is what the recorder counts and what every model agrees on. Nothing
 * user-facing says "characters": the API speaks tokens and divides by
 * [CHARS_PER_TOKEN] on the way out, because whoever sets this reads a number
 * beside a context window as tokens whatever the label says, and would be out
 * by a factor of four. The conversion is an approximation and is described as
 * one wherever it appears.
 */
data class SessionMemoryBudget(
    /** How many said turns come back at most. */
    val turns: Int,

    /** And how much of them, in characters. */
    val memoryChars: Int,

    /** How many tool lookups are considered. */
    val results: Int,

    /**
     * And how much of them altogether, in characters.
     *
     * On top of [memoryChars] rather than inside it. A result is not a turn and
     * must not compete with one: sharing the allowance, a single large listing
     * either crowds out everything that was said or is itself the first thing
     * cut, and both of those are the bug #222 fixed wearing a different hat.
     */
    val recallChars: Int,

    /** And how much of any single one; longer is cut, and the cut says so. */
    val longestResult: Int,
) {

    /** What a session may add to one prompt, both allowances together. */
    val totalChars: Int get() = memoryChars + recallChars

    companion object {

        /**
         * What an agent that has been given no share gets.
         *
         * Exactly the five numbers this feature found in the source, so an
         * installation that sets nothing behaves today as it did yesterday.
         * Written as a derivation rather than as five literals because it has
         * to stay the same thing the arithmetic produces - if the split ever
         * moves, the default moves with it instead of quietly becoming a shape
         * no share can reproduce.
         */
        val DEFAULT: SessionMemoryBudget = of(DEFAULT_CHARS)

        /**
         * The whole shape, from the one number a person actually sets.
         *
         * The split is what was already there, kept: three fifths of the
         * allowance to what was said and two fifths to what tools returned,
         * which at [DEFAULT_CHARS] is the 24,000 and 16,000 that were in the
         * source. The turn count follows the conversation's half at
         * [CHARS_PER_TURN] apiece, and the longest single result is half of the
         * tools' half - so two ordinary lookups still fit, which is the case
         * worth sizing for.
         */
        fun of(totalChars: Int): SessionMemoryBudget {
            val said = (totalChars.toLong() * SAID_PERCENT / 100).toInt()
            val recall = totalChars - said
            return SessionMemoryBudget(
                turns = (said / CHARS_PER_TURN).coerceAtLeast(1),
                memoryChars = said,
                results = RESULTS_READ,
                recallChars = recall,
                longestResult = (recall / LOOKUPS_KEPT_WHOLE).coerceAtLeast(1),
            )
        }
    }
}

/**
 * Four characters to a token, and it is an approximation on purpose.
 *
 * There is no tokeniser here and there should not be one: it would have to be
 * the provider's, it would differ per model, and it would be run over the whole
 * of a session on every turn to answer a question that only decides where to
 * cut. Four is the usual figure for English prose and is generous for the JSON
 * a tool returns, so a budget converted with it errs towards being smaller than
 * asked for rather than larger - which is the direction a ceiling should err in.
 */
const val CHARS_PER_TOKEN = 4

/** What the built-in default is a budget of; see [SessionMemoryBudget.DEFAULT]. */
const val DEFAULT_CHARS = 40_000

/** How much of the allowance goes to what was said, as a percentage. */
private const val SAID_PERCENT = 60

/**
 * What one said turn is reckoned to cost.
 *
 * Only ever decides how many turns are asked for; the characters are counted
 * for real afterwards, so a session of short turns gets more of them than this
 * suggests and one of long turns is cut by [SessionMemoryBudget.memoryChars]
 * before the count bites.
 */
private const val CHARS_PER_TURN = 600

/** How many full-size lookups the tools' allowance is meant to hold. */
private const val LOOKUPS_KEPT_WHOLE = 2

/**
 * How many lookups are read before any of them are measured.
 *
 * The one number that does not scale with the budget, because it is not a
 * budget: it is a ceiling on a query. Duplicates are dropped after they are
 * read - an agent that asked one thing five times would otherwise recall
 * nothing else - so this is deliberately more than can ever fit, and a smaller
 * budget wants it no smaller.
 */
private const val RESULTS_READ = 24

/** The narrowest and widest share of a window a session may be given. */
const val MIN_MEMORY_SHARE = 1
const val MAX_MEMORY_SHARE = 50

/** Below this a budget cannot carry a question and its answer, in characters. */
private const val FLOOR_CHARS = 2_000

/**
 * What is left for the system prompt, the tool declarations and the question,
 * as a fraction of the window, once the session and the answer have had theirs.
 */
private const val RESERVED_PERCENT = 10

/**
 * A budget worked out, and whether it can be saved.
 *
 * Both readings in one object because they come from one calculation. The
 * screen setting a share wants to show what it works out to while it is being
 * dragged, and the mutation wants to refuse one that cannot work; two
 * implementations of that would eventually disagree about which shares are
 * allowed, and the one that drifted would be the screen.
 *
 * [budget] is always usable. Where [refusal] is set it is
 * [SessionMemoryBudget.DEFAULT], so a share that stopped working - a model
 * whose window was cleared after the fact - degrades to what an unconfigured
 * agent gets rather than to nothing.
 */
data class ResolvedMemoryBudget(
    val budget: SessionMemoryBudget,
    /**
     * The share that applies, after the workspace default has been consulted;
     * null when neither the agent nor its workspace set one.
     */
    val share: Int?,
    /** The window it is a share of, in tokens; null when the model has none. */
    val contextWindow: Int?,
    /** True when [share] and [contextWindow] produced [budget]. */
    val derived: Boolean,
    /**
     * True when [share] came from the workspace default rather than from the
     * agent.
     *
     * The one thing a form cannot work out for itself: an agent showing 10%
     * needs to say whether that is its own answer or the one it inherits,
     * because clearing the first lands on the second rather than on the
     * built-in allowance.
     */
    val inherited: Boolean,
    /** Why this share cannot be saved, or null when it can. */
    val refusal: String?,
)

/**
 * Works a share of a model's window out into a budget, or says why it cannot.
 *
 * The one place the two halves of the setting are put together, and the one
 * place a share is judged. Everything that needs a budget asks here: the two
 * runtimes that build a prompt, the mutation that saves a share, and the query
 * that previews one.
 */
@Service
class SessionMemoryBudgets(
    private val models: ModelService,
    private val workspaces: WorkspaceRepository,
) {

    /** The budget to build a prompt with; a refused share falls back to the default. */
    fun budget(share: Int?, workspaceId: Long, modelId: Long?): SessionMemoryBudget =
        resolve(share, workspaceId, modelId).budget

    /**
     * How large what a session's model was sent may grow before it is started
     * again from the log, in characters. See [SessionPrompt].
     *
     * Half the model's window where it has one recorded - [MAX_MEMORY_SHARE],
     * the line above which this file already says there is no room for the
     * instructions, the tools and the answer - and never less than twice the
     * budget. The second half matters as much as the first: what a session is
     * started again from is that budget, so a ceiling of twice it means a
     * restart leaves at most half the room used, and the next restart is many
     * turns away rather than on the next one. A ceiling equal to the budget
     * would restart every turn, which is the instability this is here to stop.
     */
    fun promptCeiling(share: Int?, workspaceId: Long, modelId: Long?): Int {
        val resolved = resolve(share, workspaceId, modelId)
        val floor = resolved.budget.totalChars.toLong() * 2
        val window = resolved.contextWindow?.takeIf { it > 0 }
            ?.let { it.toLong() * CHARS_PER_TOKEN * MAX_MEMORY_SHARE / 100 }
            ?: 0L
        return maxOf(floor, window).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * The whole answer: the numbers, and whether the share is one that can work.
     *
     * The one place the resolution order lives - the agent's own share, then
     * its workspace's default, then the built-in allowance - so that nothing
     * else has to know there are three steps. Every caller passes what the
     * agent itself was given, which is null far more often than not, and the
     * fallback happens here rather than four times over.
     *
     * Refused rather than clamped. A share silently reduced to what fits is a
     * setting that does not say what it does, and the person who set it finds
     * out by reading the prompts - which is the discovery-at-the-provider this
     * exists to avoid, moved one step earlier and made quieter.
     */
    fun resolve(share: Int?, workspaceId: Long, modelId: Long?): ResolvedMemoryBudget {
        val inherited = share == null
        val applied = share ?: defaultShareOf(workspaceId)
        val model = modelId?.let { models.model(it) }
        val window = model?.contextWindow

        if (applied == null) {
            return ResolvedMemoryBudget(
                SessionMemoryBudget.DEFAULT,
                null,
                window,
                derived = false,
                inherited = false,
                refusal = null,
            )
        }

        refusalFor(applied, model?.name, window, model?.maxOutput)?.let {
            return ResolvedMemoryBudget(
                SessionMemoryBudget.DEFAULT,
                applied,
                window,
                derived = false,
                inherited = inherited,
                refusal = it,
            )
        }

        val chars = (requireNotNull(window).toLong() * applied / 100 * CHARS_PER_TOKEN).toInt()
        return ResolvedMemoryBudget(
            SessionMemoryBudget.of(chars),
            applied,
            window,
            derived = true,
            inherited = inherited,
            refusal = null,
        )
    }

    /**
     * A workspace default judged on its own, which is the bounds and nothing else.
     *
     * The narrower refusals all name a model - its window, and the tokens it
     * reserves for its answer - and a workspace default is deliberately not
     * tied to one. A workspace runs several models at once whose windows differ
     * by an order of magnitude, so a default refused because the smallest of
     * them could not give it would be refusing a setting that is right for
     * every other model in the workspace, and one that only ever applies to
     * agents that may not use that model at all.
     *
     * Nothing is lost by not checking here: [resolve] still checks, against the
     * model an agent actually uses, at the point the budget is worked out. What
     * changes is only where the refusal appears - beside the agent whose model
     * cannot give it, rather than in front of everyone.
     *
     * The bounds are the part that is true of every model: above half a window
     * there is no room for the instructions, the tools and the answer whatever
     * the window is. That check is [boundsRefusal], and it is the same call
     * [refusalFor] makes first, so the two surfaces cannot drift into refusing
     * different numbers or explaining them in different words.
     */
    fun resolveDefault(share: Int?): ResolvedMemoryBudget = ResolvedMemoryBudget(
        SessionMemoryBudget.DEFAULT,
        share,
        contextWindow = null,
        derived = false,
        inherited = false,
        refusal = share?.let(::boundsRefusal),
    )

    /** The workspace's answer for agents that give none of their own. */
    private fun defaultShareOf(workspaceId: Long): Int? =
        workspaces.findByIdOrNull(workspaceId)?.defaultMemoryShare

    /**
     * Why a share cannot work, in the words the person setting it needs.
     *
     * Every one of these is a value that would otherwise be found out at the
     * provider, on somebody's turn, as a request that was refused or a bill
     * that was larger than expected.
     */
    private fun refusalFor(share: Int, model: String?, window: Int?, maxOutput: Int?): String? {
        boundsRefusal(share)?.let { return it }
        if (model == null) {
            return "Choose a model first. How much a session may carry is a share of that model's context " +
                "window, so there is nothing to take a share of until one is chosen."
        }
        if (window == null || window <= 0) {
            return "$model has no context window recorded, so a share of it cannot be worked out. " +
                "Set the model's context window on the Models screen first."
        }

        val tokens = (window.toLong() * share / 100).toInt()
        if (tokens * CHARS_PER_TOKEN < FLOOR_CHARS) {
            return "$share% of $model's ${window.thousands()}-token window is ${tokens.thousands()} tokens, " +
                "which is not enough to carry a single exchange. Give it more, or use a model with a larger " +
                "window."
        }

        val answer = maxOutput ?: 0
        val room = window - answer - window * RESERVED_PERCENT / 100
        if (tokens > room) {
            val most = (room.toLong() * 100 / window).toInt().coerceAtMost(MAX_MEMORY_SHARE)
            return if (most < MIN_MEMORY_SHARE) {
                "$model reserves ${answer.thousands()} tokens of its ${window.thousands()}-token window for " +
                    "its answer, which leaves nothing for a session to carry."
            } else {
                "$model can give a session at most $most% of its ${window.thousands()}-token window once the " +
                    "${answer.thousands()} tokens it reserves for its answer are allowed for."
            }
        }
        return null
    }

    /**
     * The half of the rule that needs no model, and so applies wherever a share
     * is typed.
     *
     * Its own function because two surfaces set a share now - an agent's, and
     * the workspace default it falls back to - and only one of them has a model
     * to judge against. Written once so they cannot come to disagree about
     * which numbers are allowed, or explain the same refusal in two sets of
     * words.
     */
    private fun boundsRefusal(share: Int): String? {
        if (share < MIN_MEMORY_SHARE || share > MAX_MEMORY_SHARE) {
            return "A session may be given between $MIN_MEMORY_SHARE% and $MAX_MEMORY_SHARE% of a model's " +
                "context window. Above half of it there is no room left for the instructions, the tools and " +
                "the answer."
        }
        return null
    }

    /** Grouped, and in one locale: a refusal is read by whoever typed the share. */
    private fun Int.thousands(): String = String.format(Locale.ROOT, "%,d", this)
}
