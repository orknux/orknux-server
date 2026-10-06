package io.mszymanski.orknux.server

import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import kotlin.reflect.KClass

/**
 * How many rows of one entity Hibernate materialised while something ran.
 * Issue #616.
 *
 * A fix for a path that read a whole growing table to answer something small
 * gives the same answer as the path it replaced, so an assertion on the answer
 * passes both ways. What differs is what was read to get there, and this is
 * the count of that - the one thing a regression back to `findAll()` changes.
 *
 * Statistics are switched on for the measurement and left as they were, so
 * the rest of the suite pays nothing for them.
 */
class EntityLoads(factory: EntityManagerFactory) {

    private val statistics = factory.unwrap(SessionFactory::class.java).statistics

    fun <T> of(entity: KClass<*>, block: () -> T): Measured<T> {
        val wasOn = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
        statistics.clear()
        try {
            val answer = block()
            return Measured(answer, statistics.getEntityStatistics(entity.java.name).loadCount)
        } finally {
            statistics.isStatisticsEnabled = wasOn
        }
    }

    data class Measured<T>(val answer: T, val loaded: Long)
}
