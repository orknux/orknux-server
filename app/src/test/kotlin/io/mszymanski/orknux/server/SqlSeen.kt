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
        if (armed) seen.add(sql)
        return sql
    }

    companion object {
        @Volatile
        private var armed = false
        private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** Every statement prepared while [block] ran, on any thread. */
        fun <T> during(block: () -> T): Pair<T, List<String>> {
            seen.clear()
            armed = true
            try {
                val answer = block()
                return answer to seen.toList()
            } finally {
                armed = false
            }
        }
    }
}
