package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.attachment.InstallationSetting
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.graphql.Refusal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The walls between one agent turn and the rest of the server. Issue #616.
 *
 * A turn holds its whole conversation in memory - the briefing, every page a
 * skill_load brought in, every tool result, the pictures on the message - and
 * sends all of it again on every round. One fat turn is a few hundred
 * megabytes at its peak; a handful of them at once, which is what pressing
 * Re-run on a run that is still going does, is the heap. Nothing said no: the
 * server carried on into an OutOfMemoryError and took every other run, chat
 * and page down with it.
 *
 * So three walls, each one an administrator's switch with its number beside
 * it, and each read when it is needed rather than at start-up:
 *
 * - **Turns at once.** How many agent turns - workflow agent steps, chats and
 *   tasks together - may be running in this process. One more waits for a
 *   place, for as long as [BulkheadValues.turnWaitSeconds] says, and is then
 *   refused with a sentence that says why. A subagent runs inside the turn that
 *   asked for it and takes no place of its own, or a parent holding the last
 *   place would wait on a child that is waiting on it.
 * - **Heap after GC.** Before a turn starts and before each of its rounds, the
 *   old generation as the last collection left it. Usage *after* a collection
 *   and not raw usage, because raw usage is mostly garbage nobody has swept yet
 *   and would stop turns for churn; what is left after a collection is what is
 *   really held. Above the line, the turn ends with a refusal rather than
 *   carrying on into an OutOfMemoryError.
 * - **What a turn holds.** No single tool result kept longer than
 *   [BulkheadValues.toolResultKb], and the results together held to
 *   [BulkheadValues.turnMemoryMb] - the oldest ones first replaced by a line
 *   saying what was dropped and how to get it back, the way the session's own
 *   memory budget already treats what it carries between turns.
 *
 * Off restores exactly what happened before each wall existed.
 */
@Component
class Bulkheads(
    private val settings: InstallationSettingRepository,
    /**
     * Whether the real heap is read at all. Off only in the test build, whose
     * one JVM holds a cache of Spring contexts and sits above any sensible line
     * for reasons that have nothing to do with a turn - 0.9.9.18's build had the
     * guard refuse the turns of a test that was not about it. The tests of the
     * guard hand it their own readings.
     */
    @org.springframework.beans.factory.annotation.Value("\${orknux.bulkheads.read-heap:true}") readHeap: Boolean = true,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Turns running now, guarded by [lock]; a counter rather than a Semaphore so the cap can change while held. */
    private var running = 0
    private val lock = ReentrantLock()
    private val freed = lock.newCondition()

    /** Where the heap reading comes from; replaced in tests, which cannot fill a real heap on purpose. */
    internal var heapAfterGc: () -> Int? = if (readHeap) ::oldGenerationAfterGcPercent else { -> null }

    /** What is set now, each value at its default where nobody has set it. */
    fun values(): BulkheadValues {
        val held = settings.findAllById(NAMES).associate { it.name to it.value }
        fun flag(name: String, default: Boolean) = held[name]?.toBooleanStrictOrNull() ?: default
        fun number(name: String, default: Int) = held[name]?.toIntOrNull() ?: default
        return BulkheadValues(
            turnsEnabled = flag(TURNS_ENABLED, BulkheadValues.DEFAULT.turnsEnabled),
            turnsAtOnce = number(TURNS_AT_ONCE, BulkheadValues.DEFAULT.turnsAtOnce),
            turnWaitSeconds = number(TURN_WAIT_SECONDS, BulkheadValues.DEFAULT.turnWaitSeconds),
            heapEnabled = flag(HEAP_ENABLED, BulkheadValues.DEFAULT.heapEnabled),
            heapPercent = number(HEAP_PERCENT, BulkheadValues.DEFAULT.heapPercent),
            memoryEnabled = flag(MEMORY_ENABLED, BulkheadValues.DEFAULT.memoryEnabled),
            turnMemoryMb = number(TURN_MEMORY_MB, BulkheadValues.DEFAULT.turnMemoryMb),
            toolResultKb = number(TOOL_RESULT_KB, BulkheadValues.DEFAULT.toolResultKb),
        )
    }

    /** Stores all of them at once, refusing the lot if one is out of range. */
    @Transactional
    fun save(values: BulkheadValues, by: String) {
        check(TURNS_AT_ONCE, values.turnsAtOnce, 1, 1000)
        check(TURN_WAIT_SECONDS, values.turnWaitSeconds, 0, 3600)
        check(HEAP_PERCENT, values.heapPercent, 50, 99)
        check(TURN_MEMORY_MB, values.turnMemoryMb, 1, 4096)
        check(TOOL_RESULT_KB, values.toolResultKb, 4, 65536)
        write(TURNS_ENABLED, values.turnsEnabled.toString(), by)
        write(TURNS_AT_ONCE, values.turnsAtOnce.toString(), by)
        write(TURN_WAIT_SECONDS, values.turnWaitSeconds.toString(), by)
        write(HEAP_ENABLED, values.heapEnabled.toString(), by)
        write(HEAP_PERCENT, values.heapPercent.toString(), by)
        write(MEMORY_ENABLED, values.memoryEnabled.toString(), by)
        write(TURN_MEMORY_MB, values.turnMemoryMb.toString(), by)
        write(TOOL_RESULT_KB, values.toolResultKb.toString(), by)
    }

    /** How many turns hold a place now, for the screen and for tests. */
    fun runningTurns(): Int = lock.withLock { running }

    /**
     * Runs [body] in a place, or answers [refused] with the reason where none
     * came free in time. With the wall off, [body] simply runs.
     */
    fun <T> turn(refused: (String) -> T, body: () -> T): T {
        val asked = values()
        if (!asked.turnsEnabled) return body()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(asked.turnWaitSeconds.toLong())
        lock.withLock {
            while (running >= values().turnsAtOnce.coerceAtLeast(1)) {
                val left = deadline - System.nanoTime()
                if (left <= 0L) {
                    log.warn("An agent turn was refused: {} were already running", running)
                    return refused(
                        "the server was already running ${asked.turnsAtOnce} agent turns at once and this one " +
                            "waited ${asked.turnWaitSeconds} seconds for a place - it can be asked again, and " +
                            "an administrator can raise Agent turns at once under Admin, Settings, Bulkheads",
                    )
                }
                // Woken by a turn ending, and at least every second, so a cap raised
                // on the screen lets a waiting turn in without one having to end.
                freed.await(minOf(left, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS)
            }
            running += 1
        }
        try {
            return body()
        } finally {
            lock.withLock {
                running -= 1
                freed.signalAll()
            }
        }
    }

    /**
     * Why a turn must not go on, or null where it may: the old generation fuller
     * after its last collection than the line allows.
     */
    fun heapRefusal(): String? {
        val asked = values()
        if (!asked.heapEnabled) return null
        val now = heapAfterGc() ?: return null
        if (now < asked.heapPercent) return null
        log.warn("An agent turn was stopped: the heap was {}% full after its last collection", now)
        return "the server's memory was $now% full after its last clean-up, above the ${asked.heapPercent}% an " +
            "agent turn may start or carry on at, so this turn was stopped rather than risk the whole server - " +
            "it can be asked again once the server is less busy"
    }

    /**
     * Holds the tool results in [conversation] to what one turn may keep, in
     * place. Answers how many were cut or dropped.
     *
     * The newest result is never dropped whole, only cut: it is the one the
     * model is about to read. Everything else is dropped oldest first, its
     * place kept by a line so the call it answered still has an answer.
     */
    fun bound(conversation: MutableList<ChatTurn>): Int {
        val asked = values()
        if (!asked.memoryEnabled) return 0
        val longest = asked.toolResultKb * 1024
        var changed = 0
        val results = conversation.indices.filter { conversation[it].respondingTo != null }
        for (at in results) {
            val one = conversation[at]
            if (one.content.length > longest) {
                conversation[at] = one.copy(content = cut(one.content, longest))
                changed += 1
            }
        }
        val budget = asked.turnMemoryMb.toLong() * 1024 * 1024
        var held = results.sumOf { conversation[it].content.length.toLong() }
        for (at in results.dropLast(1)) {
            if (held <= budget) break
            val one = conversation[at]
            if (one.content.startsWith(DROPPED)) continue
            val note = "$DROPPED ${one.content.length} characters, to keep this turn within the memory it may " +
                "hold. Call the tool again if you still need what it said.]"
            held -= one.content.length - note.length
            conversation[at] = one.copy(content = note)
            changed += 1
        }
        return changed
    }

    private fun cut(said: String, longest: Int): String =
        said.take(longest) + "\n[${said.length - longest} more characters of this result were not kept: one " +
            "tool result may hold ${longest / 1024} KB in a turn. Ask for less - a narrower query, fewer pages - " +
            "rather than calling it the same way again.]"

    private fun check(name: String, value: Int, min: Int, max: Int) {
        if (value !in min..max) throw BulkheadValueOutOfRangeException(name, value, min, max)
    }

    private fun write(name: String, value: String, by: String) {
        val held = settings.findById(name).orElse(null) ?: InstallationSetting(name = name)
        held.value = value
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    companion object {
        const val TURNS_ENABLED = "bulkhead.turns.enabled"
        const val TURNS_AT_ONCE = "bulkhead.turns.at.once"
        const val TURN_WAIT_SECONDS = "bulkhead.turn.wait.seconds"
        const val HEAP_ENABLED = "bulkhead.heap.enabled"
        const val HEAP_PERCENT = "bulkhead.heap.percent"
        const val MEMORY_ENABLED = "bulkhead.memory.enabled"
        const val TURN_MEMORY_MB = "bulkhead.turn.memory.mb"
        const val TOOL_RESULT_KB = "bulkhead.tool.result.kb"
        val NAMES = listOf(
            TURNS_ENABLED, TURNS_AT_ONCE, TURN_WAIT_SECONDS, HEAP_ENABLED, HEAP_PERCENT,
            MEMORY_ENABLED, TURN_MEMORY_MB, TOOL_RESULT_KB,
        )

        /** How a dropped result begins, so a second pass leaves it alone. */
        const val DROPPED = "[This tool result was dropped:"

        /**
         * The largest heap pool - the old generation under Serial, Parallel and
         * G1 alike - as full as its last collection left it, in percent.
         */
        private val tenured: MemoryPoolMXBean? = runCatching {
            ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP && it.isValid && it.isCollectionUsageThresholdSupported }
                .filter { (it.usage?.max ?: -1) > 0 }
                .maxByOrNull { it.usage.max }
        }.getOrNull()

        fun oldGenerationAfterGcPercent(): Int? {
            val after = tenured?.collectionUsage ?: return null
            val max = after.max.takeIf { it > 0 } ?: return null
            return (after.used * 100 / max).toInt()
        }
    }
}

/** The three walls as set; see [Bulkheads]. */
data class BulkheadValues(
    val turnsEnabled: Boolean,
    val turnsAtOnce: Int,
    val turnWaitSeconds: Int,
    val heapEnabled: Boolean,
    val heapPercent: Int,
    val memoryEnabled: Boolean,
    val turnMemoryMb: Int,
    val toolResultKb: Int,
) {
    companion object {
        /**
         * What protects a server of two or three gigabytes: four fat turns at
         * once is about a gigabyte at their peak, 85% after a collection is
         * still room for the turn in flight to finish its round, and 48 MB of
         * tool results is far past what any model's window takes - so a turn
         * that hits it was going to be refused by its provider anyway.
         */
        val DEFAULT = BulkheadValues(
            turnsEnabled = true,
            turnsAtOnce = 4,
            turnWaitSeconds = 120,
            heapEnabled = true,
            heapPercent = 85,
            memoryEnabled = true,
            turnMemoryMb = 48,
            toolResultKb = 512,
        )
    }
}

class BulkheadValueOutOfRangeException(val setting: String, val value: Int, val min: Int, val max: Int) :
    RuntimeException("$value is not a value $setting can take. Choose between $min and $max."), Refusal {
    override val arguments get() = mapOf("setting" to setting, "value" to value, "min" to min, "max" to max)
}
