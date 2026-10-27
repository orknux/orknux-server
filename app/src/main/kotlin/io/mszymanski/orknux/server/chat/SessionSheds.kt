package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.agent.Agent
import org.springframework.stereotype.Service

/**
 * The tools every kind of turn lends an agent because it has a session, as one
 * set.
 *
 * A note to itself, working files, a to-do list, the clock, a reminder, a
 * watcher and a schedule are not things a workflow step, a task, a watcher's follow-up, a chat
 * or an asked agent has - they are things a session has, and every one of those
 * turns is a turn in one. Each door used to lend them by hand, and the lists
 * drifted: a chat had the scratchpads, the timer and the watchers and nothing
 * else, so a chat's agent could not write a note, keep a to-do list or ask the
 * time, while the same agent in a workflow step could.
 *
 * So every door takes [of] whole and adds only what is its own: `finish_answer`
 * for a step and a watcher's follow-up, the `task_*` tools for a task, the
 * drawing tool each door files differently. A capability added here reaches
 * every door the same day; one added to a door is that door's by decision.
 *
 * The order is fixed, because the tool list is part of the prompt a provider
 * caches by its prefix. Whether each tool is offered is still the agent's grant:
 * the round applies [BuiltInTools.lentTo], so a built-in hidden on the agent's
 * page is neither declared nor answered whichever door lent it.
 */
@Service
class SessionSheds(
    private val notes: NoteTools,
    private val scratchpads: ScratchpadTools,
    private val todos: TodoTools,
    private val dates: DateTools,
    private val timers: TimerTools,
    private val watchers: io.mszymanski.orknux.server.watcher.WatcherTools,
    private val schedules: io.mszymanski.orknux.server.schedule.ScheduleTools,
    private val links: LinkTools,
    private val ratings: io.mszymanski.orknux.server.rating.RatingTools,
    private val flags: io.mszymanski.orknux.server.flag.SessionFlagTools,
) {

    /**
     * The session's tools for one turn of [agent] in [session].
     *
     * With no session only the clock is left, since everything else needs
     * somewhere to keep what it writes or someone to wake.
     */
    fun of(agent: Agent, session: Long?): ToolShed? = sheds(
        // What it found, kept past the transcript being trimmed. #371.
        notes.shed(session, agent.name),
        // Working files across the job, longer than a note. #411.
        scratchpads.shed(session),
        // The plan it works down across turns. #405.
        todos.shed(session),
        // The time now, which it has no other way to know. Needs no session. #407.
        dates.shed(),
        // Where this installation is, and a path made a link that opens anywhere. Needs no session.
        links.shed(),
        // A reminder it sets and carries on; delivered to the session.
        timers.shed(session),
        // One of its own tools called on an interval until it matches; wakes the session. #606.
        watchers.shed(agent, session),
        // A reminder that comes back on its own, every so often or on a cron.
        schedules.shed(agent, session),
        // The rating the person gave the conversation, while ratings are on.
        ratings.shed(agent, session),
        // Flagging this conversation for the installation's administrators, kept from the person.
        flags.shed(agent, session),
    )

    /**
     * What the agent wrote down for itself and the plan it is working down, as
     * one block, or null where there is neither. Issues #371, #405.
     *
     * Put at the head of a conversation rebuilt from the log, as a user turn
     * after the system turn - never into the system turn, where writing a note
     * would change every message after it. Where what was sent is kept (see
     * [io.mszymanski.orknux.server.llm.SessionPrompt]) the calls that wrote them
     * are in it already, and a door does not add this.
     */
    fun standing(session: Long?): String? =
        listOfNotNull(
            notes.recalled(session).takeIf { it.isNotBlank() },
            todos.recalled(session).takeIf { it.isNotBlank() },
        ).takeIf { it.isNotEmpty() }?.joinToString(separator = "\n\n")
}
