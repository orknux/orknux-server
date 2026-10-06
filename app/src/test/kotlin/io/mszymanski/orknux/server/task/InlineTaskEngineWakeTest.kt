package io.mszymanski.orknux.server.task

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.Duration
import java.util.Optional
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A parked task has one wake on the clock, not one per park. Issue #616.
 *
 * Every park scheduled a callback a week out and kept no handle on it, so a
 * task nudged while it waited left one more callback behind each time, and a
 * finished task left its last one behind as well. In production that was a
 * queue growing for a week before any of it ran.
 *
 * The engine is built by hand over a stand-in loop: what is measured is the
 * engine's clock, and nothing past [TaskLoop] has a bearing on it.
 */
class InlineTaskEngineWakeTest {

    private val loop = mock(TaskLoop::class.java)
    private val tasks = mock(TaskRepository::class.java)

    /** What each turn answers, in order; a turn is taken off this as it runs. */
    private val turns = LinkedBlockingQueue<TaskTurn>()

    /**
     * Counted off as a worker lets a task go. The engine reads the row once
     * more after letting go, which is past the turn and past the clock, so it
     * is the moment a nudge is certain to be heard.
     */
    private val letGo = LinkedBlockingQueue<Long>()

    private val engine = InlineTaskEngine(loop, tasks, TaskProperties())

    init {
        `when`(tasks.inState(anyList())).thenReturn(emptyList())
        `when`(tasks.findById(anyLong())).thenAnswer { call ->
            letGo.put(call.getArgument(0))
            Optional.empty<Task>()
        }
        `when`(loop.advance(anyLong())).thenAnswer { turns.poll() }
        engine.start()
    }

    @AfterEach
    fun stop() = engine.stop()

    @Test
    fun `a task parked again and again keeps one wake, and none once it is over`() {
        repeat(3) {
            turns.put(TaskTurn.Parked(Duration.ofDays(7)))
            engine.nudge(TASK)
            awaitTurn()
        }
        assertThat(engine.queued()).describedAs("wakes after three parks").isEqualTo(1)

        turns.put(TaskTurn.Over)
        engine.nudge(TASK)
        awaitTurn()
        assertThat(engine.queued()).describedAs("wakes once the task is over").isZero()
    }

    private fun awaitTurn() {
        assertThat(letGo.poll(10, TimeUnit.SECONDS)).isEqualTo(TASK)
    }

    private companion object {
        const val TASK = 7L
    }
}
