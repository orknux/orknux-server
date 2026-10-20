package io.mszymanski.orknux.server.agent

/**
 * How much an agent says while it works - the progress messages it writes
 * beside its tool calls, which reach a person before the answer does.
 *
 * Set on the agent; a workflow node may override it with its `chattiness`
 * parameter. What each level tells the model is in
 * [io.mszymanski.orknux.server.chat.AgentBriefing].
 */
enum class Chattiness {
    /** Works in silence and says it all in its answer. */
    NONE,

    /** Says once what it is about to do, then nothing until its answer. */
    LOW,

    /** Says what it is about to do, and every few calls what it found: what every agent was told until this existed. */
    MEDIUM,

    /** Keeps the person posted at every step. */
    HIGH,
    ;

    companion object {
        /** A level named on a node, whatever the capitals; null for blank or anything else. */
        fun of(said: String?): Chattiness? =
            said?.trim()?.takeIf { it.isNotEmpty() }?.let { wanted -> entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) } }
    }
}
