package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.agent.Chattiness
import io.mszymanski.orknux.server.chat.AgentBriefing

/**
 * The commands that set how much an agent says while it works, for one turn:
 * `!progress-none`, `!progress-low`, `!progress-medium`, `!progress-high`, with
 * whatever marker the workspace uses.
 *
 * Built in rather than a skill. A skill is instructions the model reads; this
 * is a setting the server holds to - the level None keeps what the model writes
 * beside its calls from the person whatever it was told - so a word for it is
 * read here and never handed on as a skill id. One per [Chattiness] level, made
 * from the enum, so a level added there is a command the same day.
 *
 * For the turn the message starts, and only that one: the agent's own level is
 * the one its session's system turn was written with, and that turn is frozen
 * for the session (see `SessionFrames`), so the level asked for is said with the
 * message instead, the way anything that changes inside a session is said.
 */
object ProgressCommands {

    /** What every one of them starts with, after the marker. */
    const val PREFIX = "progress-"

    /** Each command word, without the marker, and the level it asks for. */
    val ALL: Map<String, Chattiness> = Chattiness.entries.associateBy { PREFIX + it.name.lowercase() }

    /** Whether a command word, without its marker, is one of these rather than a skill's id. */
    fun isOne(word: String): Boolean = word.lowercase() in ALL

    /** The level the last of these among some command words asks for, or null where none does. */
    fun levelIn(words: List<String>): Chattiness? = words.lastOrNull { isOne(it) }?.let { ALL[it.lowercase()] }

    /** The level a message asks for with one of these, or null where it names none. */
    fun levelIn(text: String?, marker: String): Chattiness? = levelIn(Commands.parse(text, marker))

    /**
     * What the model is told with the message: the level's own instruction,
     * the one its system turn would have carried, said for this turn.
     */
    fun note(level: Chattiness): String =
        "For this turn only, the person set how much you say while you work. " + when (level) {
            Chattiness.NONE -> AgentBriefing.REPORT_NOTHING
            Chattiness.LOW -> AgentBriefing.REPORT_ONCE
            Chattiness.MEDIUM -> AgentBriefing.REPORT_PROGRESS
            Chattiness.HIGH -> AgentBriefing.REPORT_EVERYTHING
        }
}
