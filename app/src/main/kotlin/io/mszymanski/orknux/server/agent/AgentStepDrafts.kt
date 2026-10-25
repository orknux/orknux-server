package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.chat.RoundWatch
import io.mszymanski.orknux.workflow.execution.ExecutionStep

/**
 * Something that shows an agent step's answer while it is being written.
 *
 * A step's output is saved whole, when the step ends, and that is right for
 * everything after it - a node handed half a report would act on half a
 * report. What a person watching wants is the other thing: the words as they
 * come. A research's report took minutes to write and the page showed nothing
 * of it until Save had laid it out. So the answer is handed here a piece at a
 * time as well, beside the step and not instead of its output.
 *
 * Asked once per step; a step nobody follows answers null and is run exactly
 * as it was.
 */
fun interface AgentStepDrafts {

    /** Where [step]'s answer goes, piece by piece, as it is written; null for a step this does not follow. */
    fun follow(step: ExecutionStep): ((String) -> Unit)?
}

/**
 * A round's watcher with the answer's pieces handed to [draft] as well: the
 * session's own watcher where the step keeps a session, and nothing else
 * where it does not.
 */
internal class DraftWatch(private val inner: RoundWatch?, private val draft: (String) -> Unit) :
    RoundWatch by (inner ?: object : RoundWatch {}) {

    override fun answered(piece: String) {
        inner?.answered(piece)
        draft(piece)
    }
}
