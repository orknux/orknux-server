package io.mszymanski.orknux.server.chat

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Whether this installation has a chat.
 *
 * A property of the deployment rather than of anybody using it: an installation
 * that exists to run workflows has no use for a chat window, and one whose
 * models are not cleared for open conversation should not be offering one.
 * Administrators can turn it off from the screen — that choice is stored and
 * wins — but false here is final, the same floor attachments sit on.
 */
@ConfigurationProperties(prefix = "orknux.chat")
data class ChatProperties(
    val enabled: Boolean = true,

    /**
     * How many times an agent may call tools before it has to answer.
     *
     * Eight was written into the code, and eight is right for an agent with two
     * tools and wrong for one holding twenty: a list, a load, a lookup and an
     * answer is four rounds before the work starts, and the agent was stopped
     * mid-chain with nothing to show for what had been paid for. An
     * administrator changes it from the screen; this is where a fresh
     * installation starts.
     */
    val maxRounds: Int = 8,

    /**
     * The longest an agent may put itself to sleep for, in seconds.
     *
     * An agent ending its turn with a wake-up parks the step, and the run comes
     * back to it when the time is up. An hour is the ceiling a fresh
     * installation puts on one of those waits: long enough for the thing being
     * waited on - a build, a colleague, a batch job - and short enough that a
     * model asking for a fortnight is corrected rather than obeyed.
     */
    val sleepSeconds: Int = 3600,

    /**
     * How many times in a row an agent may do that on one step.
     *
     * Because a wait is a decision the model makes again every time it wakes,
     * and one that keeps deciding to wait a little longer never finishes. Ten
     * is a day of hourly checks, which is more than any of this is for; zero
     * turns waiting off for the installation entirely.
     */
    val sleepTimes: Int = 10,
)
