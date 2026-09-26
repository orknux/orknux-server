package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.Hangup
import io.mszymanski.orknux.server.chat.AgentBriefing
import io.mszymanski.orknux.server.chat.AgentConversation
import io.mszymanski.orknux.server.llm.LlmSessionKeyTooLongException
import io.mszymanski.orknux.connector.connection.SlackFile
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionThinking
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import io.mszymanski.orknux.server.workflow.NodeExpressions
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.KIND_RUNNER_ORDER
import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.LogLevel
import io.mszymanski.orknux.workflow.execution.RunLogger
import io.mszymanski.orknux.workflow.execution.StepFailedException
import io.mszymanski.orknux.workflow.execution.StepInterrupts
import io.mszymanski.orknux.workflow.execution.StepResult
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.server.obj.ObjectShapes
import org.springframework.data.repository.findByIdOrNull
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Runs an agent node: asks the agent what it makes of what reached it.
 *
 * The same runtime a chat with an agent uses, and deliberately so. An agent is
 * one configuration — a model, instructions, granted catalogs — and it should
 * behave the same whether somebody is talking to it or a workflow is. Anything
 * else means two agents with one name, and a difference nobody can see until it
 * matters.
 *
 * What the node is handed becomes the question, and what the agent says becomes
 * the step's output, so the node after it is handed an answer the way it would
 * be handed a function's return value.
 *
 * A model is the one thing a node here talks to that can fail for reasons that
 * are nothing to do with the graph, so every failure leaving this runner says
 * whether it is settled. That word is what the node's retry policy spends
 * attempts on and what Temporal reads before retrying an activity: a request the
 * provider refused for what it said is asked once, and a provider that timed out
 * or said not now is asked again.
 */
@Component
// Ordered like its siblings. Without this it sat where an unannotated bean sits
// — the same place as UnimplementedNodeRunner — so which of the two claimed an
// AGENT node was down to the order the beans happened to be registered in, and
// losing that race means the node is skipped and the run reports success.
@Order(KIND_RUNNER_ORDER)
class AgentNodeRunner(
    private val agents: AgentRepository,
    private val briefing: AgentBriefing,
    private val conversation: AgentConversation,
    private val expressions: NodeExpressions,
    private val runLog: RunLogger,
    private val sessions: LlmSessionRecorder,
    /** For the pictures on a message; see [picturesFor]. */
    private val slackFiles: io.mszymanski.orknux.connector.connection.SlackFiles,
    /** What lets an agent inside a run draw; see [io.mszymanski.orknux.server.workflow.StepPictureTools]. */
    private val drawings: io.mszymanski.orknux.server.workflow.StepPictureTools,
    /** What lets it stop when the work is already delivered; see [FinishAnswerTools]. */
    private val finishing: FinishAnswerTools,
    /** What lets an agent write something down for itself; see [NoteTools]. Issue #371. */
    private val notes: io.mszymanski.orknux.server.chat.NoteTools,
    /** What lets an agent keep working files across the job; see [ScratchpadTools]. Issue #411. */
    private val scratchpads: io.mszymanski.orknux.server.chat.ScratchpadTools,
    /** What lets an agent plan its work as a to-do list; see [TodoTools]. Issue #405. */
    private val todos: io.mszymanski.orknux.server.chat.TodoTools,
    /** What lets an agent ask what the current time is; see [DateTools]. Issue #407. */
    private val dates: io.mszymanski.orknux.server.chat.DateTools,
    /** The agent's setup, written into the log where it changes; see [AgentDetails]. Issues #391, #441. */
    private val agentDetails: AgentDetails,
    /**
     * Where the step is written down the moment it has a session, rather than
     * when it ends. Issue #464: the engine saves a step when it completes or
     * parks, so a run part-way through a two-minute answer had recorded no
     * session yet - and the session's page, which reads the runs back off
     * exactly that column, showed every run that had finished and not the one
     * talking to it as somebody watched.
     */
    private val steps: io.mszymanski.orknux.workflow.execution.ExecutionStepRepository,
    private val budgets: SessionMemoryBudgets,
    private val shapes: ObjectShapes,
    private val mapper: ObjectMapper,
    /** What bounds an agent's waiting; see [FinishAnswerTools.Sleeping]. */
    private val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
    /** The skills a node names by id, loaded before the model starts. Issue #381. */
    private val skills: SkillTool,
) : NodeRunner {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.AGENT

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult {
        // A node pointing at nothing is not configured, which is not a failure:
        // a graph is drawn before it is finished, and a run should say what it
        // found rather than stopping the workflow over it.
        val agentId = step.agentId
            ?: return StepResult(StepStatus.SKIPPED, "${step.name} names no agent, so there was nothing to ask.")

        val agent = agents.findByIdOrNull(agentId)
            ?: return StepResult(StepStatus.SKIPPED, "The agent ${step.name} runs has been deleted.")
        if (!agent.enabled) {
            return StepResult(StepStatus.SKIPPED, "${agent.name} is not active, so it was not asked.")
        }

        // A model is the one thing it cannot do without, and being told so is
        // more useful than an empty answer.
        val modelId = agent.modelId
            ?: throw StepFailedException(
                step.nodeKey,
                "${agent.name} has no model chosen, so it cannot answer",
                // An agent nobody has finished configuring is the same agent on
                // the second attempt. This is a form to fill in, not a wait.
                permanent = true,
            )

        /*
         * The node's own wording, if it was given any.
         *
         * Without this the agent is asked whatever arrived along the edge,
         * verbatim — which for a Slack trigger means being handed the raw event
         * JSON and left to work out what the question was. A prompt mapping is
         * where the question gets asked — either wording of your own, or the
         * field carrying what came in.
         *
         * Both fall back to what they did before, so a node drawn before this
         * existed runs exactly as it did.
         */
        val payload = expressions.parse(input)
        val started = expressions.parse(trigger)
        val mappings = expressions.mappingsOf(step)

        val prompt = mappings[PROMPT]
            ?.let { expressions.textOf(it, payload, started) }
            ?.takeIf { it.isNotBlank() }

        // A node may replace the agent's own prose with its own. When it does,
        // the skills and commands the agent was granted are still appended: a
        // command is a fact about the agent, not part of a persona, and a Slack
        // bot with a bespoke voice that dropped its commands would deny having
        // any when asked. Issue #381.
        val override = mappings[SYSTEM_PROMPT]
            ?.let { expressions.textOf(it, payload, started) }
            ?.takeIf { it.isNotBlank() }
        val briefed = if (override != null) {
            listOfNotNull(override, briefing.grants(agent)).joinToString("\n\n")
        } else {
            briefing.of(agent)
        }

        /*
         * The skills this node names, loaded whether or not the model would
         * have asked. Named on the graph - written out, or read from what
         * started the run, which is how a Slack message saying `!review` lands
         * the agent with the review skill already in front of it. An id that
         * names nothing is said in the run log and costs nothing else: a word
         * out of a message is not a reason to stop. Issue #381.
         */
        val forced = mappings[SKILL_IDS]?.let { skillIdsOf(it, payload, started) }.orEmpty()
        val loaded = if (forced.isEmpty()) null else skills.byKeys(agent.workspaceId, forced)
        if (loaded != null && loaded.missing.isNotEmpty()) {
            runLog.write(
                step.executionId,
                step.nodeKey,
                LogLevel.INFO,
                "No skill in this workspace has the id ${loaded.missing.joinToString(", ")}; " +
                    "the rest were loaded",
            )
        }
        val system = if (loaded == null || loaded.found.isEmpty()) {
            briefed
        } else {
            (briefed?.plus("\n\n") ?: "") + buildString {
                appendLine("These skills are loaded for this task. Follow them:")
                loaded.found.forEach { skill ->
                    append("\n### ").append(skill.name).append(" (").append(skill.key).appendLine(")")
                    appendLine(skill.content.trim())
                }
            }.trimEnd()
        }

        /*
         * The shape the answer is held to, said in the system prompt.
         *
         * Told rather than hoped for, and checked below rather than trusted:
         * the shape travels on the step, so a published workflow keeps meaning
         * what it meant, and a shape that has been deleted since is a failure
         * the run reports rather than a node that quietly answers prose again.
         */
        val held = step.outputObjectId?.let { objectId ->
            shapes.described(objectId)
                ?: throw StepFailedException(
                    step.nodeKey,
                    "the shape ${step.name}'s answer is held to has been deleted",
                    permanent = true,
                )
        }
        val instructed = if (held == null) {
            system
        } else {
            (system?.plus("\n\n") ?: "") +
                "Answer with a single JSON object matching this shape, and nothing else - " +
                "no prose around it and no code fence:\n$held"
        }

        val asked = prompt ?: input ?: "There is no input for this step. Say what you would do."

        /*
         * And what it left itself, where this is a step waking up.
         *
         * A woken node is handed the same input it had the first time and
         * nothing else - the run does not keep a turn's working - so without
         * this the agent wakes, reads the question it has already worked on, and
         * starts again from the top. A session would carry more, but most nodes
         * keep none and the ones that do are not the only ones that may wait.
         *
         * Left where it is rather than cleared as it is read. A wait that is
         * taken again writes over it, so what is there is always the last thing
         * the agent said to itself - and a step that failed after waking is
         * handed it again on the attempt after that, which is the behaviour the
         * note exists for. Nothing reads it once the step has finished.
         */
        val question = step.agentSleepNote?.let { note ->
            """
            |$asked
            |
            |You stopped here earlier to wait, and left yourself this note:
            |$note
            """.trimMargin()
        } ?: asked

        /*
         * The session this node writes into, if it names one.
         *
         * Opened before the turns are built, because what is already in it goes
         * into them: a session that is only written to is a transcript, and what
         * this feature is for is an agent that remembers. The second workflow to
         * compute this key asks its question of a model that has heard the first
         * one's exchange.
         */
        val session = sessionFor(step, agent, mappings, payload, started)
        // On the step, so the run's page can link this step to the conversation
        // it produced. The engine saves the step whether this round completes or
        // parks, so a still-talking agent is reachable too. Issue #387.
        step.sessionId = session
        // Written now rather than at the end of the step: while this agent is
        // talking, the session's page should name the run that is talking.
        step.id?.let { steps.save(step) }

        /*
         * A picture is something an agent decides on while it is working, so
         * the tool that draws one is lent for this step rather than granted on
         * the agent.
         *
         * Whether it is offered at all is a grant like any other tool's - it
         * is ticked in the agent's Tools list, which is where somebody looks
         * to see what an agent may do. Where the picture goes is not on the
         * agent and cannot be: filing needs the run and the step to file
         * against, and only this knows which those are. So the grant says
         * *may it*, and the shed says *where*.
         *
         * Null where the agent was not granted it, or the installation keeps
         * no attachments, or the workspace has chosen no image model - and
         * then the round is exactly the round it was before this existed.
         *
         * Built here, before the turns, because the account of the agent's
         * setup written into the log below names what was lent as well as what
         * was granted (#446), and that account goes in before the question so
         * the log opens with the setup its words were said under.
         */
        val drawing = drawings.shed(
            step.executionId,
            step.nodeKey,
            agent.workspaceId,
            granted = io.mszymanski.orknux.server.chat.BuiltInTools
                .granted(agent, io.mszymanski.orknux.server.workflow.StepPictureTools.DRAW),
            // The session this round is written into, which is also the only
            // place a drawn picture's bytes can be left for something else to
            // send. A node that keeps no session gets no key; see the shed.
            sessionId = session,
            // Drawing and linking are two decisions: an agent may be trusted to
            // draw and still have no business writing an address into an answer
            // that is read somewhere this installation is not.
            mayLink = agent.pictureLinkAccess,
        )

        /*
         * And the ending, lent beside the drawing.
         *
         * A round ends when the model writes prose, which assumes the answer
         * is the prose. An agent that posted its reply to Slack itself has
         * nothing left to write, and being asked for an answer anyway is what
         * made it either repeat the message or answer with nothing - an empty
         * message, which reads as a failure, which is retried, which posts the
         * whole thing twice. See [FinishAnswerTools].
         */
        val shed = io.mszymanski.orknux.server.chat.sheds(
            drawing,
            /*
             * Somewhere to write a note to itself, where this node keeps a
             * session to write it into. Issue #371: a wake-up already carries
             * one, and this is the same thing without the parking - what the
             * first six steps of a long job found, which the transcript trims
             * away exactly when it starts to matter.
             */
            notes.shed(session, agent.name),
            /*
             * And somewhere to keep a working file across the job - a document
             * it is drafting, code it is writing - which the note is too short
             * to be and the transcript too trimmed. Only where there is a
             * session to keep it in. Issue #411.
             */
            scratchpads.shed(session),
            /*
             * And a to-do list to plan the job on, where this node keeps a
             * session to hold it. The plan an agent works down across steps it
             * cannot finish in one turn. Issue #405.
             */
            todos.shed(session),
            // And the clock, so it can reason about time it has no way to know
            // otherwise. Needs no session; see the shed. Issue #407.
            dates.shed(),
            finishing.shed(
                granted = agent.finishAccess,
                shaped = step.outputObjectId != null,
                /*
                 * And what is left of its waiting, which is a fact about this
                 * step rather than about the agent: the count is on the row
                 * because the wake-up may be carried by another worker
                 * entirely. Both numbers are the installation's, read now
                 * rather than when the run started - an operator who shortens
                 * the ceiling means it for the wait being asked for next, not
                 * for runs that start tomorrow.
                 */
                sleeping = FinishAnswerTools.Sleeping(
                    longest = java.time.Duration.ofSeconds(settings.agentSleepSeconds().toLong()),
                    left = (settings.agentSleepTimes() - step.agentSleeps).coerceAtLeast(0),
                    spent = step.agentSleeps,
                ),
            ),
        )

        /*
         * The agent's setup, written into the log where this agent starts
         * responding - if it differs from the last setup logged there. A
         * session is not this node's alone: a Slack thread's session is
         * answered by whichever agent node a run points at it, and an agent is
         * edited between turns, so the log says which setup each stretch of it
         * was answered under rather than the one that opened it. On every pass,
         * a wake included: the recorder compares and writes nothing for the
         * same setup, and an agent edited while its step slept is a change
         * worth a line. Issues #391, #441. With the shed, so the account names
         * every tool the model is about to be handed, lent ones included. #446.
         *
         * And with the instructions this turn is answered under - `instructed`,
         * the same string the system turn below is built from, rather than
         * `agent.systemPrompt`. Issue #454: a node that replaced the prompt, the
         * grants briefing appended after it, the skills this node forced and the
         * shape the answer is held to were all absent from the record, which
         * read as complete; a lent shed's paragraph goes on the end of it by the
         * rule the round uses.
         */
        sessions.describeAgent(session, agentDetails.snapshot(agent, shed, instructed))

        /*
         * How much of it this agent is allowed to bring back.
         *
         * Its own share of its own model's window - or, where it has none of
         * its own, its workspace's default - resolved fresh on every run rather
         * than carried in the published graph: everything it is made of is a
         * live row, and a run that used yesterday's window would be spending
         * against a number that no longer exists.
         */
        val budget = budgets.budget(agent.memoryShare, agent.workspaceId, modelId)

        /*
         * Read before this turn's question is recorded, not after - otherwise
         * the question would come back as part of its own history and the model
         * would be shown it twice.
         */
        val remembered = session?.let { sessions.remembered(it, budget) }.orEmpty()

        /*
         * And what its tools returned, which is not the same thing.
         *
         * What was said is the agent's account of the data; this is the data.
         * An agent that looked something up in the last run and is asked about
         * it in this one would otherwise be working from its own summary, and a
         * summary cannot be checked against anything.
         *
         * Last, so the freshest thing in the prompt is the thing most likely to
         * be what the question is about.
         */
        val recalled = session?.let { sessions.recalled(it, budget) }.orEmpty()

        /*
         * Pictures somebody attached to what is being answered.
         *
         * A model that can see reads an image part; everything else here is
         * text, so a screenshot in Slack used to reach an agent as the word
         * "screenshot.png" and nothing else. It would then answer questions
         * about a picture it had never been shown, which is the worst of the
         * three possible behaviours.
         *
         * Read off the payload this step was handed rather than fetched by the
         * listener: an upload is cheap to announce and expensive to carry, and
         * most messages are answered by a workflow that never looks at one.
         * See [picturesFor] for what is fetched and what is left alone.
         */
        val pictures = picturesFor(payload, agent.workspaceId)

        /*
         * And what it wrote down for itself, put back whole. Issue #371.
         *
         * In the system turn beside the briefing rather than among the
         * remembered turns, because those are what the budget trims and a note
         * is the thing that must survive that. Empty where nothing was written,
         * which is most turns.
         */
        val written = notes.recalled(session)
        // The plan it is working down, put back beside its notes. Issue #405.
        val planned = todos.recalled(session)

        val turns = buildList {
            listOfNotNull(instructed, written.takeIf { it.isNotBlank() }, planned.takeIf { it.isNotBlank() })
                .takeIf { it.isNotEmpty() }
                ?.let { add(ChatTurn("system", it.joinToString(separator = "\n\n"))) }
            addAll(remembered)
            addAll(recalled)
            add(ChatTurn("user", question, images = pictures))
        }

        /*
         * Written before the model is asked, not after: a run that dies waiting
         * on a model should still leave the question in the transcript, since a
         * session with an answer and no question is worse than one with a
         * question and no answer.
         */
        /*
         * Only the first time through, not when the step wakes. A woken node
         * re-runs run() from the top (see the note above), so recording here
         * unguarded wrote the user's message into the session again on every
         * wake - the thread read as the person repeating themselves. agentSleeps
         * is 0 until the first wait and nonzero after, so it is the signal that
         * this pass is a resume. Issue #396.
         */
        if (step.agentSleeps == 0) session?.let { sessions.userSaid(it, step.name, question) }

        /*
         * Said before the model is asked, not after.
         *
         * A model can take a minute, and until it answers the run's log showed
         * nothing at all for this node — indistinguishable from a step that had
         * stalled. This is the line that says the wait is the model thinking.
         */
        runLog.write(
            step.executionId,
            step.nodeKey,
            LogLevel.INFO,
            "${agent.name} is thinking — waiting on the model",
        )

        /*
         * What the model is thinking, written down as it thinks it.
         *
         * Handing over a watcher is also what makes the round stream - see
         * [AgentConversation.answer] - and that is the point rather than a side
         * effect: a reasoning model can spend a minute before it says a word,
         * and until this the session had a question, then nothing, then an
         * answer. The page showing the run had nothing to draw for the part of
         * the turn there was most to see.
         *
         * A watcher that writes rather than relays, for the reason a task's
         * does: a node runs where nobody is necessarily looking, and whoever
         * opens the session tomorrow must be given the same account as whoever
         * is watching now. `SessionTail` follows the table, so the live view
         * costs nothing extra.
         *
         * Only where there is a session to write into. A node that keeps no
         * session keeps nothing, and a watcher would have nowhere to put this -
         * it would also quietly switch that node to a streamed call, which is a
         * change nobody asked for in return for nothing kept.
         *
         * A provider that sends no reasoning opens no line: the watcher is fed
         * only what the model actually thought, and blank thinking is not
         * written. So a model without thinking, or a provider that keeps it to
         * itself, leaves the transcript exactly as it was.
         */
        val watching = session?.let { SessionThinking(it, agent.name, sessions) }

        /*
         * And the way to end the call early, handed to the step's interrupt.
         *
         * Stop on the run used to be read between steps only, so an agent in
         * the middle of a two-minute model call carried on for the two minutes
         * and the run noticed afterwards. The hangup is what a chat's Stop
         * button pulls, and it does the same here: the stream is closed, or
         * the blocking request cancelled, and the round comes back a failure
         * nobody is left to read - which the engine records as a stopped step
         * rather than a failed one, because it asked. Issue #440.
         */
        val hangup = Hangup()
        val answer = try {
            stoppable(hangup) {
                conversation.answer(modelId, agent, turns, session, shed = shed, watch = watching, hangup = hangup)
            }
        } catch (finished: AnswerFinished) {
            val wake = finished.wake
            if (wake != null) {
                /*
                 * Not finished: waiting.
                 *
                 * The step parks and the run comes back to this node when the
                 * time is up, which is the same mechanism a waiting action uses
                 * - and the point of it is that nothing is held while it runs
                 * down. The deadline goes on the row because the wait outlives
                 * the worker that started it, and the count with it, because the
                 * installation's ceiling is counted against runs rather than
                 * against processes.
                 */
                step.agentSleeps += 1
                step.agentSleepNote = finished.answer.takeIf { it.isNotEmpty() }
                step.waitUntil = java.time.OffsetDateTime.now().plus(wake)

                val note = "${agent.name} is waiting ${wake.toSeconds()}s" +
                    (if (finished.clipped) ", shortened to what this installation allows" else "") +
                    "; asked again then (${step.agentSleeps} of ${settings.agentSleepTimes()})"
                runLog.write(step.executionId, step.nodeKey, LogLevel.INFO, note)
                return StepResult.waiting(wake, note)
            }

            /*
             * The agent said that was the work.
             *
             * Completed rather than failed, and with whatever it passed -
             * usually nothing, because the thing it made went somewhere the
             * graph is not. Named the same way an ordinary answer is, so a
             * node after this one reads it in the place it would have read
             * prose. No shape to satisfy: the tool is not offered where the
             * node is held to one.
             */
            runLog.write(
                step.executionId,
                step.nodeKey,
                LogLevel.INFO,
                "${agent.name} finished: the work was delivered, so there was nothing left to answer",
            )
            return StepResult(StepStatus.COMPLETED, expressions.named(step.outputName, finished.answer))
        } finally {
            // Whatever the turn did, and before anything else reads the
            // session: a line left open is one a page reads as still being
            // thought, and on a turn that threw there is nothing left to close
            // it later.
            watching?.settle()
        }

        return when (answer) {
            // Named, the answer is handed on as an object holding it, so the next
            // node can refer to it by that name. Prose has no fields, and a
            // node cannot refer to something that has no name.
            is ChatCompletion.Answered ->
                if (step.outputObjectId == null) {
                    StepResult(StepStatus.COMPLETED, expressions.named(step.outputName, answer.content))
                } else {
                    shaped(step, agent.name, answer.content)
                }

            /*
             * Whether this is worth asking again is not the node's to guess.
             *
             * The layer that made the call is the only one that saw the status
             * and the exception, so it is the one that decides, and the node
             * carries that decision through unchanged. Deciding here would mean
             * reading it back out of the sentence — and a node that matched on
             * the words would start retrying a 400 the day a provider reworded
             * its errors.
             */
            is ChatCompletion.Failed -> throw StepFailedException(
                step.nodeKey,
                "${agent.name} could not answer: ${answer.reason}",
                permanent = answer.permanent,
            )

            // The loop runs tools to a conclusion, so nothing here is still
            // asking for one. A round that ended this way ends it the same way
            // next time: it is the agent's tools, not the moment.
            is ChatCompletion.CalledTools -> throw StepFailedException(
                step.nodeKey,
                "${agent.name} asked for a tool that could not be run",
                permanent = true,
            )
        }
    }

    /**
     * Runs [body] with [hangup] pulled if the run is asked to stop meanwhile.
     *
     * Only where this thread is carrying a workflow step, which is where the
     * interrupt is registered; a chat or a task reaching this runner some other
     * way has nothing to register with and simply runs. Issue #440.
     */
    private fun <T> stoppable(hangup: Hangup, body: () -> T): T {
        val interrupt = StepInterrupts.current() ?: return body()
        return interrupt.holding({ hangup.hangUp() }, body)
    }

    /**
     * The pictures on the message this step is answering, as data URLs.
     *
     * Empty for everything that is not a picture, which is most of what people
     * attach: a PDF is not an image, and a model handed one as an image part
     * is a request a provider refuses in its own words. Those stay where they
     * were - named on the payload, fetched by `slack_readAttachment` when an
     * agent decides it wants one.
     *
     * Bounded at [MOST_PICTURES], because a thread where somebody pasted
     * fifteen screenshots is a context window spent on fifteen screenshots. The
     * first few are what the question is about; the rest are still in the
     * thread, and still fetchable by name.
     *
     * A picture that cannot be read is left out rather than failing the step.
     * The question was asked and can be answered without it, and a run that
     * died because Slack was slow would be the worse outcome by a distance.
     */
    private fun picturesFor(payload: JsonNode?, workspaceId: Long): List<String> {
        val files = payload?.path("files")?.takeIf { it.isTextual }?.asString()?.ifBlank { null }
            ?: return emptyList()
        val connectionId = payload.path("connection").let {
            when {
                it.isNumber -> it.asLong()
                it.isTextual -> it.asString().trim().toLongOrNull()
                else -> null
            }
        } ?: return emptyList()

        /*
         * The payload carries the files as the text of a JSON array, because a
         * trigger's context is a map of strings - see `SlackListener.describe`.
         * A shape that is not that is not an error: something else put a
         * `files` on this payload and it is not ours to read.
         */
        val described = runCatching { mapper.readTree(files) }.getOrNull()?.takeIf { it.isArray }
            ?: return emptyList()

        return described.values()
            .filter { it.path("mimetype").asString("").startsWith("image/") }
            .take(MOST_PICTURES)
            .mapNotNull { file ->
                val url = file.path("url").asString("").ifEmpty { null } ?: return@mapNotNull null
                when (val got = slackFiles.read(connectionId, url, workspaceId)) {
                    is SlackFile.Fetched -> got.asDataUrl()
                    is SlackFile.NotRead -> {
                        // The name and the reason, never the bytes: a picture
                        // nobody could fetch is a line in the log rather than a
                        // failed turn.
                        log.info("A picture on a Slack message was not shown to the agent: {}", got.reason)
                        null
                    }
                }
            }
    }

    /**
     * The answer, held to the node's shape.
     *
     * The model was told the shape in its system turn; this is where being told
     * becomes being held. A code fence is stripped rather than counted against
     * it - a model that fenced valid JSON did what was asked, in the one way
     * models keep doing it - but an answer that does not parse, or parses into
     * the wrong shape, fails the step *unsettled*: the node's own retry policy
     * decides how many times the model is worth re-asking, exactly as it does
     * for a provider that timed out. The problems are in the failure, so the
     * transcript says what was wrong rather than only that something was.
     */
    private fun shaped(step: ExecutionStep, agent: String, content: String): StepResult {
        val bare = unfenced(content)
        val parsed = runCatching { mapper.readTree(bare) }.getOrNull()
            ?: throw StepFailedException(
                step.nodeKey,
                "$agent was asked for a JSON object and answered prose",
                permanent = false,
            )

        val problems = shapes.problems(requireNotNull(step.outputObjectId), parsed)
        if (problems.isNotEmpty()) {
            throw StepFailedException(
                step.nodeKey,
                "$agent's answer does not match the shape it is held to: " + problems.joinToString("; "),
                permanent = false,
            )
        }

        return StepResult(StepStatus.COMPLETED, expressions.namedJson(step.outputName, mapper.writeValueAsString(parsed)))
    }

    /** The JSON inside a ```fence```, where the model wrapped it in one. */
    private fun unfenced(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    /**
     * The LLM session this node was told to talk into, or null for a node that
     * names none.
     *
     * The two halves are not the agent node's own. They belong to the session
     * node an edge leads from, and `AppWorkflowGraphSource` folds them onto this
     * step when the graph is read for the run — so what arrives here is the
     * session that was wired to this agent at the moment the run started, and
     * redrawing the canvas afterwards cannot change it. Two agents wired to one
     * session node are handed the same two halves, which is how they end up in
     * one conversation without either of them naming it.
     *
     * Two parameters rather than one, because the halves come from different
     * places: the prefix is nearly always something the author typed — the name
     * of the conversation this workflow has — while the key is nearly always
     * read off what arrived, a thread, a ticket, a customer. They are resolved
     * here, against what *this* step was handed, which is what keeps a
     * referenced key reading the run rather than the session node's own view of
     * it. Joining them here is what makes two workflows land in one session on
     * purpose: the same halves, however each of them arrived at them, are the
     * same session.
     *
     * A node with no key records nothing and asks nothing of the database. That
     * is every agent node with no session wired to it, and — because a node that
     * still carries these names from before session nodes existed is left alone
     * unless a session overrides it — every agent node drawn before either
     * existed still behaves exactly as it did.
     */
    private fun sessionFor(
        step: ExecutionStep,
        agent: Agent,
        mappings: Map<String, NodeBinding>,
        payload: JsonNode?,
        started: JsonNode?,
    ): Long? {
        fun resolved(name: String) = mappings[name]
            ?.let { expressions.textOf(it, payload, started) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val key = resolved(SESSION_KEY) ?: return null

        return try {
            sessions.open(agent.workspaceId, resolved(SESSION_KEY_PREFIX), key)
        } catch (refused: LlmSessionKeyTooLongException) {
            /*
             * Refused rather than trimmed to fit. Two long keys cut to the same
             * length would be one session, and quietly pouring two
             * conversations into one is worse than stopping to say the key
             * cannot be stored — which is a mapping to fix, not a run to retry.
             */
            throw StepFailedException(
                step.nodeKey,
                "${step.name} cannot record its session: ${refused.message}",
                permanent = true,
            )
        }
    }

    /**
     * The ids a `skillIds` mapping names: a list where the value is one - a
     * reference to the trigger's `commands`, say - and otherwise the words of
     * the text it holds, split on commas and spaces.
     */
    private fun skillIdsOf(binding: NodeBinding, payload: JsonNode?, started: JsonNode?): List<String> {
        val raw = expressions.jsonOf(binding, payload, started)
        val node = runCatching { mapper.readTree(raw) }.getOrNull()
        val words = when {
            node != null && node.isArray -> node.values().map { it.asString() }
            node != null && node.isTextual -> node.stringValue().split(SEPARATORS)
            else -> raw.split(SEPARATORS)
        }
        return words.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        /** What a written list of skill ids is split on. */
        val SEPARATORS = Regex("[,\\s]+")

        /**
         * Which skills are loaded for this node before the model starts, by
         * id. Written out, or read from another node - the trigger's
         * `commands`, say. Issue #381.
         */
        const val SKILL_IDS = "skillIds"

        /**
         * How many pictures one message is worth showing.
         *
         * A thread where somebody pasted fifteen screenshots is a context
         * window spent on fifteen screenshots - and the first few are what the
         * question is about. The rest stay in the thread, named, and fetchable
         * by name.
         */
        const val MOST_PICTURES = 3

        /** What the node asks. Blank or absent leaves the edge's value as the question. */
        const val PROMPT = "prompt"

        /** Replaces the agent's own briefing for this node only. */
        const val SYSTEM_PROMPT = "systemPrompt"

        /**
         * Which conversation this node's turn belongs to, put here by the
         * session node wired to this one. Blank or absent means the turn is not
         * kept anywhere, which is what an agent node with no session does.
         */
        const val SESSION_KEY = "sessionKey"

        /** What the key is namespaced under. Optional; see LlmSessionKey. */
        const val SESSION_KEY_PREFIX = "sessionKeyPrefix"
    }
}
