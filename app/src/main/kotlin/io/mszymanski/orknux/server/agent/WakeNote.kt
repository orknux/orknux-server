package io.mszymanski.orknux.server.agent

/**
 * What an agent node reads when a wait it took comes due.
 *
 * Reported from production: asked to ping somebody every five minutes until the
 * fix was confirmed, an agent pinged, waited five minutes with the note "I have
 * started pinging him every five minutes and will stop when somebody confirms",
 * woke, read that note as work already done, and finished. Nothing had told it
 * that being started again *is* the next five minutes - that nothing runs while
 * it is stopped - so a note about a job in progress read as a report of one
 * finished. This says so, every time it wakes.
 */
object WakeNote {

    /**
     * @param asked what the step was asked the first time.
     * @param note what the agent left itself when it stopped.
     * @param spent how many waits this step has taken, this one included.
     * @param allowed how many it may take in all.
     */
    fun wokenQuestion(asked: String, note: String, spent: Int, allowed: Int): String {
        val left = (allowed - spent).coerceAtLeast(0)
        return buildString {
            appendLine(asked)
            appendLine()
            appendLine(
                "You stopped to wait, and you are being run again now because that wait is over " +
                    "(wait $spent of $allowed; $left left). Nothing happened on your behalf while you were " +
                    "stopped: whatever you said you would keep doing, you do now or not at all.",
            )
            appendLine("The note you left yourself:")
            appendLine(note)
            appendLine()
            append(
                "If that is a job that repeats - a message every few minutes, a check until something " +
                    "happens - this is its next round: do it now, check whether it should stop, and unless it " +
                    "should, end your turn with finish_answer and wake_after_ms again. Finish without a wake " +
                    "only when the job is over.",
            )
        }
    }
}
