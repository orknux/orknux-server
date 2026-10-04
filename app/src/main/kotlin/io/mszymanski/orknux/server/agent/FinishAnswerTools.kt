package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.watcher.WatcherTools

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.chat.AgentRoundHalted
import io.mszymanski.orknux.server.chat.ToolShed
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * A way for an agent in a run to say "that was the work" and stop.
 *
 * ### What it is for
 *
 * A round ends when the model writes prose instead of asking for another tool,
 * and that rule assumes the answer *is* the prose. It often is not. An agent
 * answering a Slack mention posts its reply itself, with the connection's own
 * tool, because that is how a file or a picture reaches somebody; by the time
 * the work is done, everything worth saying has been said, in the place it was
 * supposed to be said. The model is then asked for an answer it has no reason
 * to write, and what it does next is one of two bad things: it repeats the
 * message it has already sent, or it answers with nothing at all - which the
 * provider reports as an empty message, which the run reads as a failure, which
 * is retried, which posts the whole thing a second time.
 *
 * So this is the same ending said deliberately. The model calls it, the round
 * ends there, and the step is finished rather than failed.
 *
 * ### Why it is a shed, and why the switch is the other way round
 *
 * It is a shed because it is not a capability. [ToolShed] exists for exactly
 * this - the thing running the loop being addressed by the model inside it, in
 * the one channel a model has for saying something that is not prose - and "I
 * have finished" is the example its own documentation gives. `task_done` is the
 * same idea.
 *
 * And it is on until somebody turns it off, rather than granted by name like a
 * tool. The name-grants are a list of what an agent was *given*: something it
 * could not otherwise reach, ticked by whoever decided it should. An ending
 * reaches nothing and takes nothing; an agent that has to be granted the right
 * to stop is an agent whose turn ends by accident. So it is a switch on the
 * agent - [Agent.finishAccess], the same shape as `artifactAccess` - drawn in
 * the same list as the grants because that list is where somebody looks to see
 * what an agent may do, and ticked there until it is unticked. What it is for
 * is the workflow whose next node needs an answer to work with, where an agent
 * finishing early hands it an empty one.
 *
 * ### What the answer becomes
 *
 * Whatever was passed, and nothing where nothing was passed. A node after this
 * one reads an empty answer, which is the truth: the agent's work went
 * somewhere else, and inventing prose to fill the field would be the run
 * telling the next node something the agent never said.
 *
 * Not offered where the node's answer is held to a shape. A step that has to
 * produce an object cannot be finished without one, and a tool that could only
 * ever be refused is a turn spent teaching the model what the node already
 * knew - the rule `AgentTools` states for every tool.
 *
 * ### Ending a turn without ending the work
 *
 * The same tool is how an agent waits. Some work is not finished and not
 * failing either: a build is running, a colleague has been asked, a batch job
 * lands at six. Until now the only two ways to spend that time were to hold the
 * round open - a model billed for every minute of it, and a step that dies with
 * the worker - or to answer as though the work were done and lose it.
 *
 * So the ending takes a wake-up. The agent says how long it wants, the step
 * parks, and the run comes back to that node when the time is up. That is the
 * mechanism a waiting action already uses, and the reason it is worth reusing
 * is that nothing is held while it runs down: a Temporal timer costs nothing
 * and survives every process involved being restarted.
 *
 * Bounded at both ends by the installation, because the model decides again
 * every time it wakes and one that keeps deciding to wait a little longer never
 * finishes. [Sleeping] carries what is left of both bounds, and where nothing
 * is left the parameter is not offered at all - a model is not taught about a
 * thing it will only be refused.
 */
@Service
class FinishAnswerTools(private val mapper: ObjectMapper) {

    /**
     * The shed for one step, or null where finishing early makes no sense.
     *
     * @param granted whether the agent may finish early. On for every agent
     *   until somebody turns it off, which is the opposite way round from the
     *   name-grants: those are a list of what an agent was given, and an
     *   ending is not something to be given. See [Agent.finishAccess].
     * @param shaped whether the node's answer is held to an object shape. A
     *   step that has to produce an object cannot be finished without one, and
     *   a tool that could only ever be refused is a turn spent teaching the
     *   model what the node already knew.
     */
    fun shed(granted: Boolean = true, shaped: Boolean = false, sleeping: Sleeping? = null): ToolShed? =
        if (granted && !shaped) Shed(sleeping) else null

    private inner class Shed(private val sleeping: Sleeping?) : ToolShed {

        /** Whether waiting is on the table at all, which is what decides the spec. */
        private val mayWait = sleeping != null && sleeping.left > 0

        override fun specs(): List<ToolSpec> = listOf(if (mayWait) waking(sleeping!!) else FINISHING)

        override fun handles(name: String): Boolean = name == FINISH

        /**
         * Returns only where the model asked for something it cannot have.
         *
         * Ending the round is what this tool does, and [AgentRoundHalted] is how
         * a shed says so - what it carries is what the step answers with, and
         * how long before the run comes back to it. A wake-up this installation
         * will not allow is answered in words instead: the round goes on, and
         * the model can finish properly rather than having its turn ended by a
         * refusal it never saw.
         */
        override fun run(call: ToolCall): String {
            val answer = text(call, "answer").orEmpty().trim()
            val asked = number(call, WAKE)

            /*
             * Where waiting is offered, the model says whether it waits - every
             * time. Reported from production: an agent posted "after the PR is
             * up I will check the build every 10 minutes" and ended with
             * finish_answer {}, the wake-up simply left out. Leaving a field out
             * is the easiest thing a model does; a required number is a question
             * it has to answer, and -1 is how it says never.
             */
            if (mayWait && asked == null) {
                return "$WAKE is required: pass $NEVER to finish for good, or how many milliseconds until you " +
                    "are started again. If you said you would check, follow up or do something later, that later " +
                    "only happens with a number here."
            }
            if (asked == null || asked == NEVER) throw AnswerFinished(answer)

            if (!mayWait) {
                return "This run has no waiting left in it" +
                    (sleeping?.let { " (${it.spent} of ${it.spent} already spent)" } ?: "") +
                    ". Finish now, or carry on and answer."
            }
            if (asked <= 0) {
                return "$WAKE must be a number of milliseconds greater than zero, or $NEVER to finish here " +
                    "instead of waiting."
            }

            val longest = sleeping!!.longest
            val wake = if (asked > longest.toMillis()) longest else Duration.ofMillis(asked)
            throw AnswerFinished(answer, wake, clipped = asked > longest.toMillis())
        }

        private fun text(call: ToolCall, name: String): String? = runCatching {
            mapper.readTree(call.arguments).path(name).takeIf { it.isTextual }?.stringValue()
        }.getOrNull()

        /**
         * A number the model wrote, however it wrote it.
         *
         * A parameter has no declared type - every one of them reaches a
         * provider as a string - so "60000" and 60000 both arrive, and which of
         * the two a given model sends is not something to build on.
         */
        private fun number(call: ToolCall, name: String): Long? = runCatching {
            val node = mapper.readTree(call.arguments).path(name)
            when {
                node.isNumber -> node.asLong()
                node.isTextual -> node.stringValue().trim().toLongOrNull()
                else -> null
            }
        }.getOrNull()
    }

    /**
     * The same tool with the wake-up on it, and the bounds written into the
     * words rather than only enforced behind them.
     *
     * Told what is left because a model that knows it has two waits and an hour
     * apiece spends them on the two things worth waiting for. One that is only
     * refused after the fact spends a round finding out, every time.
     */
    private fun waking(sleeping: Sleeping): ToolSpec = FINISHING.copy(
        description = FINISHING.description +
            " This is also how you come back later, and the only way: saying you will check back does " +
            "nothing unless you pass `$WAKE`. " +
            /*
             * A watcher before a wake, where a tool can see the thing. #606: a
             * wake to call the same tool again is a model call per look; a
             * watcher looks without one and wakes the agent on the match.
             */
            "To wait for something one of your tools can see - a build finishing, a status changing, a " +
            "reply arriving - set a watcher with ${WatcherTools.SET} first: it calls the tool for you on an " +
            "interval and starts you again when the result matches, so you finish with `$WAKE` $NEVER. " +
            "Use a wake to wait only as the last resort, where no tool can observe the thing or the " +
            "condition cannot be written as a JSONPath or a regular expression: pass `$WAKE` and this step " +
            "stops here and is started again when that time is up, with the note you left yourself, " +
            "rather than holding this turn open or answering as though it had happened. " +
            // And to repeat, which it is just as much. Issue #568.
            "It is also how you do something on a timer: act, wait, and act again when you are started - " +
            "a message every few seconds. Keep the count in a note to yourself. " +
            // The note is read by the agent that wakes, not by anybody else. A note saying what was
            // done read, on waking, as a job finished; say what to do next instead.
            "When you wait, `answer` is that note: you are the one who reads it when you are started " +
            "again, so write what to do then - the next round, and when to stop - not what you have done. " +
            "Asked for something open-ended, like every five seconds, do it for as many waits as are left " +
            "and say where it will stop, rather than saying you cannot. " +
            "This run has ${sleeping.left} of those left, and one may be up to " +
            "${sleeping.longest.toMillis()} ms. " +
            "Every call says which: `$WAKE` is $NEVER when the work is over, and a number whenever anything " +
            "you said you would do - a check, a follow-up, the next round - is still to come.",
        parameters = FINISHING.parameters + ToolParameterSpec(
            name = WAKE,
            description = "Required. $NEVER to finish for good - nothing more will happen after this turn. " +
                "Otherwise how long to wait, in milliseconds, before you are started again to carry on. " +
                "Anything longer than ${sleeping.longest.toMillis()} ms is shortened to that.",
            required = true,
        ),
    )

    /**
     * What is left of an agent's waiting on the step it is running.
     *
     * Handed in rather than read here for the reason the drawing shed's key is:
     * only the thing running the step knows which step it is, and both numbers
     * are facts about this run rather than about the agent.
     *
     * @param longest the most one wait may be, the installation's number.
     * @param left how many waits this step has in it, spent ones taken off.
     * @param spent how many it has already taken, for saying so when there are
     *   none left.
     */
    data class Sleeping(val longest: Duration, val left: Int, val spent: Int = 0)

    companion object {

        const val FINISH = "finish_answer"

        /** What the wake-up is called in the tool call, in the model's own units. */
        const val WAKE = "wake_after_ms"

        /** What the wake-up is when the agent is finishing for good. */
        const val NEVER = -1L

        val FINISHING = ToolSpec(
            name = FINISH,
            description = "Ends your turn now, without writing an answer. Call it when the work is done " +
                "and the result has already been delivered - a message you posted, a file you uploaded, " +
                "a picture you sent - so there is nothing left to say here. Do not repeat what you have " +
                "already sent; that is what this is for. `answer` is optional and is only for something " +
                "a later step in this workflow needs to read. Ending your turn is final: you are not run " +
                "again unless somebody writes to you. There is no \"later\" you can check back in - if " +
                "something you need has not happened yet, wait for it before you finish, or, where one of " +
                "your tools can see it, set a watcher with ${WatcherTools.SET}, which starts you again when " +
                "it happens.",
            parameters = listOf(
                ToolParameterSpec(
                    name = "answer",
                    /*
                     * Public, and said so. Issue #539: an agent filed its
                     * answer as a note to itself - "tool_load seemed to be
                     * malfunctioning... the presence of the plugin indicates
                     * this is a supported capability" - which is what the run
                     * log and the transcript then showed people as its answer.
                     */
                    description = "What this step should answer with, for the steps after it. Depending " +
                        "on the workflow it may also be read by a person, so word it for them. Leave it out " +
                        "where nothing follows or nothing needs it.",
                    required = false,
                ),
            ),
        )
    }
}

/**
 * The agent ended its turn - for good, or until [wake] is up.
 *
 * @param answer what the step answers with, which is usually empty. On a wait it
 *   is the note the agent left itself, put to it again when it is started again.
 * @param wake how long before the step is started again; null is the ending this
 *   began as. Already held to the installation's ceiling by the time it is here.
 * @param clipped whether the wait asked for was longer than the installation
 *   allows and was shortened. Said in the run's log, because a wait that is not
 *   the one the agent asked for is exactly the sort of thing somebody reads that
 *   log to find out.
 */
class AnswerFinished(
    val answer: String,
    val wake: Duration? = null,
    val clipped: Boolean = false,
) : AgentRoundHalted(
    when {
        wake != null -> "Waiting ${wake.toMillis()} ms." + if (answer.isEmpty()) "" else " $answer"
        answer.isEmpty() -> "Finished. The work was delivered, so there is nothing to add."
        else -> answer
    },
)
