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
)
