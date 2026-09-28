package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.chat.AgentBriefing
import io.mszymanski.orknux.server.chat.AgentConversation
import io.mszymanski.orknux.server.chat.Interjections
import io.mszymanski.orknux.server.chat.RoundWatch
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime

/**
 * How far a task got in one go, as whatever is carrying it needs to know.
 *
 * The same three answers a workflow step gives — it did some work, it is parked
 * and here is when to come back, or there is nothing further to do — because
 * they are the same three answers, and the engines above this were written
 * against them.
 */
sealed interface TaskTurn {

    /** A turn was taken and the task is still going. Ask again straight away. */
    data object Working : TaskTurn

    /** Stopped for a person. Ask again after this, and not before. */
    data class Parked(val after: Duration) : TaskTurn

    /** Finished, failed, given up on or stopped. Nothing further to ask. */
    data object Over : TaskTurn
}

/**
 * One turn of a task, and everything that decides whether there is another.
 *
 * A turn is one round of the ordinary agent conversation — the same
 * [AgentConversation] a chat and a workflow's agent node go through, with the
 * same tool loop inside it, writing into the same LLM session. What this adds is
 * the outer loop: it decides what to put in front of the model, what the answer
 * meant, whether the task may have another go, and what to do when the agent
 * stops to ask a person something.
 *
 * **The task's memory is the session, not this class.** Nothing is carried in a
 * field between two turns; each one rebuilds what the model sees out of
 * `llm_session_event`. That is what lets a task be picked up by another process
 * after a restart with nothing lost, and it is why the loop can be re-entered by
 * a Temporal activity that may be delivered twice.
 *
 * It holds no transaction. A turn calls a model and then whatever tools that
 * model asks for, which can be minutes; a database connection held for that long
 * is one nobody else has. Every write here is its own.
 */
@Service
class TaskLoop(
    private val tasks: TaskRepository,
    private val requests: TaskRequestRepository,
    private val messages: TaskMessageRepository,
    private val conversation: AgentConversation,
    private val briefings: AgentBriefing,
    private val sessions: LlmSessionRecorder,
    private val budgets: SessionMemoryBudgets,
    private val worker: TaskWorker,
    private val tools: TaskTools,
    /** What lets an agent write something down for itself; see [NoteTools]. #371. */
    private val notes: io.mszymanski.orknux.server.chat.NoteTools,
    /** What lets an agent keep working files across the task; see [ScratchpadTools]. #411. */
    private val scratchpads: io.mszymanski.orknux.server.chat.ScratchpadTools,
    /** What lets an agent plan its work as a to-do list; see [TodoTools]. #405. */
    private val todos: io.mszymanski.orknux.server.chat.TodoTools,
    /** What lets an agent ask what the current time is; see [DateTools]. #407. */
    private val dates: io.mszymanski.orknux.server.chat.DateTools,
    /** A reminder the agent sets for itself; see [io.mszymanski.orknux.server.chat.TimerTools]. */
    private val timers: io.mszymanski.orknux.server.chat.TimerTools,
    /** save_artifact whatever the agent's list says; see [TaskArtifacts]. */
    private val artifacts: TaskArtifacts,
    /** The agent's setup, written into the log where it changes; see [AgentDetails]. #391, #441. */
    private val agentDetails: io.mszymanski.orknux.server.agent.AgentDetails,
    private val news: TaskNewsDesk,
    private val properties: TaskProperties,
) {

    /**
     * Takes the task one turn further, or says why it did not.
     *
     * Safe to call on a task in any state, including one that is already over:
     * a Temporal activity is delivered at least once, and a second delivery must
     * not start a finished task again.
     */
    fun advance(taskId: Long): TaskTurn {
        val task = tasks.findByIdOrNull(taskId) ?: return TaskTurn.Over

        when (task.status) {
            TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.STOPPED -> return TaskTurn.Over

            TaskStatus.WAITING -> return waiting(task)

            TaskStatus.QUEUED -> {
                task.status = TaskStatus.RUNNING
                task.startedAt = OffsetDateTime.now()
                tasks.save(task)
            }

            TaskStatus.RUNNING -> Unit
        }

        spent(task)?.let { return end(task, TaskStatus.FAILED, it, said = null) }

        return try {
            take(task)
        } catch (parked: TaskParked) {
            park(task, parked)
        } catch (finished: TaskFinished) {
            /*
             * Not finished if somebody said something first.
             *
             * A message is read at the top of a turn, and for a task that does
             * its work inside one turn there is never a second top to read it
             * at: the agent calls its tools to a conclusion, calls task_done,
             * and a correction sent thirteen seconds in is still sitting
             * unread when the row goes to DONE. That is the shape most tasks
             * have, so the box was taking words nothing would ever look at.
             *
             * So finishing is where the last check happens. What was said is
             * put in front of the agent and it is given the turn it would
             * otherwise not have had, which is the promise the screen makes -
             * a message sent before the task ends is read before it ends. The
             * summary it just wrote is dropped, deliberately: it describes work
             * done without knowing what was wanted, and the agent writes
             * another when it calls task_done again. Nothing loops, because
             * what was delivered is marked delivered.
             */
            reconsider(task) ?: end(task, TaskStatus.DONE, "finished", finished.summary)
        } catch (unrunnable: TaskNotRunnableException) {
            // The agent was deleted, switched off, or its model is gone. Not
            // something another turn will fix, and not a failure of the model.
            end(task, TaskStatus.FAILED, unrunnable.message ?: "it could not be run", said = null)
        }
    }

    /**
     * Another turn, where the agent tried to finish with something unread.
     *
     * Null when there was nothing waiting, which is the ordinary case and means
     * the caller should let the task end.
     */
    private fun reconsider(task: Task): TaskTurn? {
        val taskId = requireNotNull(task.id)
        val session = task.sessionId ?: return null
        if (messages.findByTaskIdAndDeliveredAtIsNullOrderBySentAtAscIdAsc(taskId).isEmpty()) return null

        deliver(taskId, session)
        sessions.userSaid(session, TASK, RECONSIDER)
        log.debug("Task {} was told something before it could finish, so it takes another turn", taskId)
        return TaskTurn.Working
    }

    /** One round of the agent's own conversation, and what its answer meant. */
    private fun take(task: Task): TaskTurn {
        val taskId = requireNotNull(task.id)
        val session = task.sessionId ?: return end(task, TaskStatus.FAILED, "its log is gone", said = null)
        val working = worker.of(task)
        val agent = working.agent
        val budget = budgets.budget(agent.memoryShare, task.workspaceId, working.modelId)

        /*
         * What this turn lends the agent beside its own tools. Built before the
         * setup is written down, because the account of it names what was lent
         * as well as what was granted (#446), and before the turns so the log
         * opens with the setup its words were said under.
         */
        val shed = io.mszymanski.orknux.server.chat.sheds(
            // Drawing and linking are two decisions; see the shed.
            tools.shed(task, mayLink = agent.pictureLinkAccess),
            /*
             * And somewhere to write a note to itself. Issue #371: a
             * task is the longest thing an agent does here - many turns
             * over hours - so it is where the transcript being trimmed
             * costs the most, and where what was found on turn three is
             * most likely to be gone by turn thirty.
             */
            notes.shed(session, agent.name),
            /*
             * And working files across the task - a document it is
             * building, code it is writing - which the note is too
             * short to hold. A task is the longest job here, so it is
             * where a scratchpad earns its keep. Issue #411.
             */
            scratchpads.shed(session),
            /*
             * And a to-do list to plan the task on. A task is the
             * longest job here - many turns over hours - so it is where
             * a plan worked down step by step earns its keep most.
             * Issue #405.
             */
            todos.shed(session),
            // And the clock: a task runs for hours, so the time it began
            // is not the time now. Needs no session. #407.
            dates.shed(),
            // A reminder it sets and carries on; delivered to the session.
            timers.shed(session),
            // Somewhere a finished file goes, whatever the agent's list says.
            artifacts.shed(agent, session),
        )

        /*
         * The standing instructions this turn is answered under, composed once:
         * the same string goes into the system turn below and into the record of
         * the setup. Issue #454 - the record used to be `agent.systemPrompt`, so
         * a task agent whose instructions are its grants briefing and the task
         * rules read as having no system prompt at all.
         */
        val instructions = instructions(briefings.of(agent))

        // The agent's setup, written into the log where this turn starts if it
        // differs from the last one logged - so the first turn opens the log
        // with it, and an agent edited between turns is a line saying so rather
        // than a silent change. Issues #391, #441. With the shed, so the account
        // names every tool the model is handed, lent ones included. #446.
        sessions.describeAgent(session, agentDetails.snapshot(agent, shed, instructions))

        deliver(taskId, session)

        val turns = buildList {
            /*
             * The briefing, and what this agent wrote down for itself. Issue
             * #371: the conversation below is trimmed to a share of the window,
             * so what turn three found is gone by turn thirty - and a task is
             * the longest thing an agent does here. A note is what survives
             * that, so it rides with the briefing rather than in the tail.
             */
            add(
                ChatTurn(
                    "system",
                    listOfNotNull(
                        briefing(instructions, task),
                        notes.recalled(session).takeIf { it.isNotBlank() },
                        // The plan it is working down, put back each turn. #405.
                        todos.recalled(session).takeIf { it.isNotBlank() },
                    ).joinToString(separator = "\n\n"),
                ),
            )
            addAll(sessions.remembered(session, budget))
            addAll(sessions.recalled(session, budget))
        }

        /*
         * Somebody is watching, and that is the whole of what makes this turn
         * visible while it happens.
         *
         * A round with a watcher is streamed and a round without is one
         * blocking call - see [AgentConversation], where the two paths differ
         * by nothing else. A task used to pass none, so a turn was up to eight
         * HTTP calls that each said nothing until they were over, and a page
         * showing the task had minutes with nothing to draw. It is not the
         * chat's watcher: a chat has a reader on the other end of an open
         * connection and relays to them, while a task has nobody in particular
         * and writes to the session instead. See [SessionThinking].
         */
        val watching = io.mszymanski.orknux.server.llm.SessionThinking(session, agent.name, sessions)

        val begun = System.nanoTime()
        val answer = try {
            conversation.answer(
                working.modelId,
                agent,
                turns,
                session,
                shed,
                watching,
                interjections = { pickUp(taskId, session) },
            )
        } finally {
            // Before the turn is counted, and whatever the turn did. A line
            // left open is one a page reads as still being thought, and on a
            // turn that threw there is nothing left to close it later.
            watching.settle()
            record(task, Duration.ofNanos(System.nanoTime() - begun))
        }

        return when (answer) {
            /*
             * Text and no `task_done`, which is the agent reporting progress.
             * AgentConversation has already written what it said into the
             * session, so all that is left is to ask it to carry on - written
             * down as well, because a transcript in which the agent answers
             * twice in a row with nothing in between reads as a model talking
             * to itself, and because two turns of one role running together is
             * a shape some providers refuse.
             */
            is ChatCompletion.Answered -> {
                sessions.userSaid(session, TASK, CARRY_ON)
                log.debug("Task {} took turn {}", taskId, task.turnsSpent)
                TaskTurn.Working
            }

            /*
             * The model could not answer. The task ends here rather than trying
             * again: what the loop would put in front of it next time is what it
             * has just refused, and a task that quietly retries an outage for
             * forty turns is the bill this feature exists to bound. The reason
             * is the model's own words, in the log, for whoever reads it.
             */
            /*
             * Out of rounds is a turn used up, not a failure. Reported: a task
             * with forty turns stopped after one turn's eight rounds, halfway
             * through making a PDF. What it did is in the session, so the next
             * turn carries on from there; the task's own turn limit is what
             * bounds a model that never finishes.
             */
            is ChatCompletion.Failed if answer.outOfRounds -> {
                sessions.userSaid(session, TASK, OUT_OF_ROUNDS)
                log.debug("Task {} used a turn's rounds on turn {}", taskId, task.turnsSpent)
                TaskTurn.Working
            }

            is ChatCompletion.Failed ->
                end(task, TaskStatus.FAILED, "the model could not answer: ${answer.reason}", said = null)

            // The loop inside runs tools to a conclusion, so nothing here is
            // still asking for one.
            is ChatCompletion.CalledTools ->
                end(task, TaskStatus.FAILED, "the model asked for a tool that could not be run", said = null)
        }
    }

    /**
     * Puts anything a person said while the last turn ran in front of the agent.
     *
     * This is the whole of how a message reaches a task, and it is a read rather
     * than a delivery. Nothing pushes: the row was written by [TaskService.say]
     * whenever somebody pressed send, and the run comes past here at the top of
     * every turn and picks up what is there. Which is why it works the same on
     * the inline engine and on Temporal, why a message sent to a task whose
     * process then died is still read by whichever process picks it up, and why
     * there is no signal to be delivered to a workflow that has moved on.
     *
     * Here rather than at the moment somebody typed, because a turn is minutes
     * long and the agent's answer is written when it ends. A line dropped into
     * the session mid-turn would sit *before* an answer composed without it, and
     * the next turn would read a conversation in which the agent had apparently
     * already replied to something it never saw.
     *
     * Written down first and marked delivered second. A process that dies
     * between the two says the same thing twice, which the agent can read; the
     * other order loses it silently, which nobody can.
     */
    private fun deliver(taskId: Long, session: Long) {
        pickUp(taskId, session)
    }

    /**
     * Everything waiting, written down, marked delivered, and handed back.
     *
     * Handed back because this is also what [Interjections] is answered with:
     * the same rows, the same writes, read from two places in one turn. The top
     * of a turn is one of them, and between the rounds of that turn is the
     * other - and the second is what makes this feature what it says it is.
     *
     * A turn is not one call. An agent drawing three pictures spends minutes
     * inside a single turn, and a correction sent thirteen seconds in used to
     * wait for a turn boundary that, on a task finishing inside its first turn,
     * never arrived at all. Asked between rounds it reaches the model before
     * the second picture.
     *
     * Written down first and marked delivered second, on both roads. A process
     * that dies between the two says the same thing twice, which the agent can
     * read; the other order loses it silently, which nobody can.
     */
    private fun pickUp(taskId: Long, session: Long): List<String> {
        val waiting = messages.findByTaskIdAndDeliveredAtIsNullOrderBySentAtAscIdAsc(taskId)
        if (waiting.isEmpty()) return emptyList()

        val now = OffsetDateTime.now()
        waiting.forEach { message ->
            // Under the name of whoever typed it, so the transcript says who
            // changed the work rather than attributing it to the machinery.
            sessions.userSaid(session, message.saidBy, message.body)
            message.deliveredAt = now
            messages.save(message)
        }
        log.debug("Task {} was told {} thing(s) while it worked", taskId, waiting.size)
        return waiting.map { it.body }
    }

    /**
     * What the model is told about being a task, on top of its agent's briefing.
     *
     * Said every turn rather than once at the start, because it is a system turn
     * and system turns are not remembered - only what was *said* is. It is also
     * where the bounds are named: a model that knows it has forty turns spends
     * them differently from one that thinks it has for ever.
     *
     * Two halves since #454, and the split is what changes between turns. The
     * [instructions] are the standing ones - the agent's briefing and what
     * working on a task means - and are what the log records as the setup this
     * turn was answered under. The count below is a fact about the turn rather
     * than about the setup, and recording it would put a fresh page of prompt in
     * the transcript before every single turn, which is the one thing the
     * setup-changed comparison exists to avoid.
     */
    private fun briefing(instructions: String, task: Task): String = buildString {
        append(instructions)
        appendLine(
            "You have taken ${task.turnsSpent} of ${task.turnsAllowed} turns. When they run out the task stops " +
                "unfinished, so if you are running short, finish what you can and say so.",
        )
    }

    /** The standing half of the briefing: everything that is the same on every turn. */
    private fun instructions(agentBriefing: String?): String = buildString {
        agentBriefing?.let { appendLine(it).appendLine() }
        appendLine(
            "You are working on a task on your own. Nobody is answering between your turns, so do the work " +
                "rather than describing what you would do, and use your tools to actually carry it out.",
        )
        appendLine(
            "Somebody may leave you a message while you work, and it appears as a turn from them. It is the " +
                "newest word on what is wanted, so follow it even where it contradicts the original task, say " +
                "what you are doing differently, and carry on.",
        )
        appendLine(
            "When the work is finished, call task_done with a summary. Until you do, whatever you write is " +
                "recorded as progress and you will be asked to carry on.",
        )
        appendLine(
            "A file you make for somebody - a PDF, a page, a zip - is not delivered until it is saved: save it " +
                "with save_artifact, passing the contentKey the tool that made it gave you, and put the link it " +
                "answers in your task_done summary. A file left only in this session is one nobody can open.",
        )
        appendLine(
            "If you need something you have not been given, call task_request_permission. If you cannot sensibly " +
                "go on without knowing something - most often how what you are producing should be delivered - " +
                "call task_ask. Both stop the task until a person answers, so use them when you mean them.",
        )
    }

    /** What a task that is standing still should do next. */
    private fun waiting(task: Task): TaskTurn {
        val patience = task.waitingUntil
        if (patience != null && OffsetDateTime.now().isAfter(patience)) {
            val asked = requests.findFirstByTaskIdAndDecisionIsNullOrderByAskedAtAscIdAsc(requireNotNull(task.id))
            asked?.let {
                it.decision = TaskDecision.REFUSED
                it.decidedBy = TASK
                it.decidedAt = OffsetDateTime.now()
                requests.save(it)
            }
            return end(task, TaskStatus.FAILED, "nobody answered", said = null)
        }
        return TaskTurn.Parked(pollFor(task))
    }

    /**
     * How long to leave a parked task before looking again.
     *
     * Short at first and long afterwards, because the two are answering
     * different questions. Somebody who approves a request while looking at the
     * page expects the task to move, so the first few minutes are polled
     * closely; a task nobody has come back to in an hour is one that will be
     * answered tomorrow, and asking every thirty seconds until then writes a
     * week of history for a fact that has not changed.
     */
    private fun pollFor(task: Task): Duration {
        val since = task.waitingUntil?.minus(properties.patience) ?: return TaskProperties.POLL_WHILE_WAITING
        val waited = Duration.between(since, OffsetDateTime.now())
        return if (waited < TaskProperties.POLL_WHILE_WAITING.multipliedBy(CLOSE_POLLS)) {
            TaskProperties.POLL_WHILE_WAITING
        } else {
            TaskProperties.POLL_WHILE_WAITING.multipliedBy(CLOSE_POLLS)
        }
    }

    /** Writes down what the agent stopped for, and tells whoever should hear. */
    private fun park(task: Task, parked: TaskParked): TaskTurn {
        val taskId = requireNotNull(task.id)
        val written = requests.save(
            TaskRequest(
                taskId = taskId,
                kind = parked.kind,
                capability = parked.capability,
                subject = parked.subject,
                asks = parked.asks,
            ),
        )

        task.status = TaskStatus.WAITING
        task.waitingUntil = OffsetDateTime.now().plus(properties.patience)
        tasks.save(task)

        task.sessionId?.let { sessions.note(it, parked.message.orEmpty()) }
        news.waiting(task, written)
        return TaskTurn.Parked(TaskProperties.POLL_WHILE_WAITING)
    }

    /** Adds what this turn cost, whether or not it produced anything. */
    private fun record(task: Task, took: Duration) {
        task.turnsSpent += 1
        task.workedSeconds += took.toSeconds()
        tasks.save(task)
    }

    /** Whether the task has run out of something, in the words the page shows. */
    private fun spent(task: Task): String? = when {
        task.turnsSpent >= task.turnsAllowed -> "out of turns after ${task.turnsSpent}"
        task.workedSeconds >= task.secondsAllowed -> "out of time after ${task.workedSeconds}s of work"
        else -> null
    }

    /**
     * Ends the task, one way or another.
     *
     * The one place a task stops, so what is written down when it does cannot
     * differ between the six ways of getting here.
     */
    private fun end(task: Task, status: TaskStatus, because: String, said: String?): TaskTurn {
        task.status = status
        task.endedBecause = because.take(Task.TITLE_LENGTH)
        task.outcome = said
        task.finishedAt = OffsetDateTime.now()
        tasks.save(task)

        task.sessionId?.let {
            sessions.note(it, said?.let { summary -> "The task is finished. $summary" } ?: "The task stopped: $because")
        }
        news.finished(task)
        return TaskTurn.Over
    }

    private companion object {
        /** Who the machinery speaks as in a transcript, beside the agent and the person. */
        const val TASK = "task"

        const val CARRY_ON = "Carry on with the task. Call task_done when it is finished."

        /** Said when a turn ran out of tool rounds: the work so far stands, and this is a new turn. */
        const val OUT_OF_ROUNDS = "That turn used all its tool rounds. What you did is above and still stands; " +
            "this is a new turn, so carry on from where you stopped. Call task_done when it is finished."

        /**
         * Said after a task_done that arrived with something unread above it.
         *
         * It names what happened, because the alternative is an agent that
         * announced it had finished and is then asked to carry on with no
         * explanation - which reads, to the model, as its own summary having
         * been ignored.
         */
        const val RECONSIDER = "You called task_done, but the message above arrived while you were working " +
            "and is the newest word on what is wanted. Take it into account, say what you are doing " +
            "differently, and call task_done again when that is finished."

        /** How many close polls a freshly parked task gets before they lengthen. */
        const val CLOSE_POLLS = 10L

        val log = LoggerFactory.getLogger(TaskLoop::class.java)
    }
}
