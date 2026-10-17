package io.mszymanski.orknux.workflow.execution

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A stop reaches a step only while the step is being carried.
 *
 * The stop ends a call by its hook first, and the call's thread is then free
 * to leave the step - record it, clear its interrupt, and go on to write what
 * happened. An interrupt that was still on its way at that moment landed on
 * the write instead: on SQLite the transaction manager's lock wait is
 * interruptible, so the step was never recorded as stopped and the activity
 * failed. That is how ExecutionWorkflowTest's stop test timed out on the SQLite
 * leg of CI, with an InterruptedException out of
 * `OneWriterTransactionManager.doBegin` under `StepRunner.stopStep`.
 */
class StepInterruptsTest {

    @Test
    fun `a stop whose hook outlasts the step leaves the thread that carried it uninterrupted`() {
        val interrupts = StepInterrupts()
        val entered = CountDownLatch(1)
        val hungUp = CountDownLatch(1)
        val left = CountDownLatch(1)
        val stopReturned = AtomicBoolean(false)
        val interruptedAfter = AtomicReference<Boolean>()

        val carrier = Thread.ofPlatform().start {
            runCatching {
                interrupts.stoppable(EXECUTION, "call") {
                    StepInterrupts.current()!!.holding({
                        hungUp.countDown()
                        // The hook is slow to return: the step is over before the stop goes on.
                        left.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    }) {
                        entered.countDown()
                        hungUp.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        throw IOException("the stream was closed")
                    }
                }
            }
            left.countDown()
            // Not a blocking wait, which an interrupt would end: the thread carries
            // on as the step runner does, and looks at its flag once stop() is over.
            while (!stopReturned.get()) Thread.onSpinWait()
            interruptedAfter.set(Thread.interrupted())
        }

        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        interrupts.stop(EXECUTION)
        stopReturned.set(true)
        carrier.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        assertThat(interruptedAfter.get()).isFalse()
    }

    @Test
    fun `a stop while the step is in flight interrupts it, and the step says it was stopped`() {
        val interrupts = StepInterrupts()
        val entered = CountDownLatch(1)
        val thrown = AtomicReference<Throwable>()

        val carrier = Thread.ofPlatform().start {
            try {
                interrupts.stoppable(EXECUTION, "sleep") {
                    entered.countDown()
                    Thread.sleep(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
                }
            } catch (failure: Throwable) {
                thrown.set(failure)
            }
        }

        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        interrupts.stop(EXECUTION)
        carrier.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        assertThat(thrown.get()).isInstanceOf(StepInterruptedException::class.java)
        assertThat(interrupts.isCarrying(EXECUTION)).isFalse()
    }

    private companion object {
        const val EXECUTION = 1L
        const val WAIT_SECONDS = 5L
    }
}
