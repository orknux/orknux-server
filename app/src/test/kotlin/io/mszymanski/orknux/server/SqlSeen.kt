package io.mszymanski.orknux.server

import org.hibernate.resource.jdbc.spi.StatementInspector
import java.util.Collections

/**
 * The SQL Hibernate prepared while something ran. Issue #616.
 *
 * [EntityLoads] counts rows; this is for the question a count cannot answer -
 * whether a column was read at all. A plugin is still loaded by a lookup, and
 * what matters is that the lookup no longer selects its megabytes of source.
 *
 * Registered for the whole suite as Hibernate's statement inspector, in the
 * surefire properties of `app/pom.xml`, and records nothing until a test arms
 * it - so the rest of the suite pays one boolean read a statement.
 */
class SqlSeen : StatementInspector {

    override fun inspect(sql: String): String {
        if (watched === Thread.currentThread()) seen.add(sql)
        return sql
    }

    companion object {
        @Volatile
        private var watched: Thread? = null
        private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /**
         * Every statement prepared on the calling thread while [block] ran.
         *
         * That thread and no other: this is one static list for the whole JVM,
         * so it would otherwise take what every cached context's timers prepare
         * meanwhile - temporary files, session tails, the scratchpad sweep - and
         * a count asserted exact came out one off on CI's SQLite leg in 1.3.6
         * and again in 1.4.0, in a different column each time. What the block
         * itself asks for runs where it is called: the GraphQL tester executes a
         * document on the calling thread, and the suite's workflows run inline.
         */
        fun <T> during(block: () -> T): Pair<T, List<String>> {
            seen.clear()
            watched = Thread.currentThread()
            try {
                val answer = block()
                return answer to seen.toList()
            } finally {
                watched = null
            }
        }
    }
}
