package io.mszymanski.orknux.server.database

import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionDefinition
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The application's transaction manager, which on SQLite hands out the one
 * write lock in the order it was asked for.
 *
 * Every transaction on SQLite starts `BEGIN IMMEDIATE` (see [SqliteConfig]), so
 * only one runs at a time whatever this class does. What SQLite does not do is
 * queue: a transaction that finds the lock taken sleeps and looks again, for
 * longer each time, and whoever asks at the moment it is free gets it. A thread
 * that commits and begins again straight away - a workspace copy, one component
 * per transaction - is always the one asking at that moment, so everything else
 * waits for the whole run rather than for one transaction of it. Issue #572: the
 * page reading how far a copy had got could not load its own session until the
 * copy was over, so the bar never moved on the engine `orknux-one` ships with.
 *
 * So the lock is taken here first, fairly, and SQLite's is then free by the time
 * it is asked for. Held from the start of a transaction to its cleanup, and
 * re-entrant, because a thread that suspends one transaction for another already
 * holds the database and must not queue behind itself. A wait is bounded by the
 * same busy timeout SQLite would have given up after, so nothing that failed
 * after half a minute before now waits for ever instead.
 *
 * On Postgres [serialise] is false and this is exactly a JpaTransactionManager.
 */
class OneWriterTransactionManager(private val serialise: Boolean) : JpaTransactionManager() {

    private val writer = ReentrantLock(true)

    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        if (!serialise) return super.doBegin(transaction, definition)
        // The timed tryLock is the one that honours fairness; the untimed one barges.
        if (!writer.tryLock(SQLITE_BUSY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw CannotCreateTransactionException(
                "Waited ${SQLITE_BUSY_TIMEOUT_MS / 1000}s for the database's one writer and it was not free.",
            )
        }
        try {
            super.doBegin(transaction, definition)
        } catch (failed: Throwable) {
            writer.unlock()
            throw failed
        }
    }

    override fun doCleanupAfterCompletion(transaction: Any) {
        try {
            super.doCleanupAfterCompletion(transaction)
        } finally {
            if (serialise && writer.isHeldByCurrentThread) writer.unlock()
        }
    }
}
