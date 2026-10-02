package io.mszymanski.orknux.server

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The one thing `ddl-auto: validate` does not look at.
 *
 * Hibernate compares tables and columns and says nothing at all about a CHECK
 * constraint, so a migration that widens one on Postgres - a new audit category,
 * a new status, a new kind of trigger - leaves SqliteSchemaTest green and breaks
 * the SQLite installation on the first row that uses the new value. It is the
 * quiet half of the two-schemas bargain, and this is what watches it.
 *
 * Textual on purpose. It reads the Postgres history and the SQLite one as
 * files rather than asking a database, so it needs neither container nor
 * connection and runs in whichever suite is going. What it asserts is that every
 * literal a named CHECK allows on Postgres is also allowed under SQLite. How the
 * constraint is spelled is not its business, and a value SQLite accepts and
 * Postgres does not is not the mistake anybody makes.
 *
 * It compares the constraints the two schemas share by name and nothing else,
 * because the Postgres history also contains constraints on tables it went on to
 * drop and a name that no longer exists is not a drift. A CHECK added to a table
 * that already existed and folded into only one of the two would therefore slip
 * past this; a CHECK arriving with a new table would not, since the table itself
 * is what validate is looking for.
 */
class SqliteCheckConstraintTest {

    @Test
    fun `every value a CHECK allows on Postgres is allowed on SQLite`() {
        val postgres = checksIn(postgresSchema())
        val sqlite = checksIn(sqliteSchema())

        assertThat(postgres).isNotEmpty()

        val missing = postgres.mapNotNull { (name, allowed) ->
            val here = sqlite[name] ?: return@mapNotNull null
            val absent = allowed - here
            if (absent.isEmpty()) null else "$name allows $absent on Postgres and not on SQLite"
        }
        assertThat(missing).describedAs(
            "A CHECK constraint was changed in a Postgres migration and not in a " +
                "db/migration/sqlite migration",
        ).isEmpty()
    }

    /**
     * The Postgres history flattened into the schema it ends at: a constraint
     * dropped and added again by a later migration is the later one, which is
     * how every widening in this history has been written.
     */
    private fun postgresSchema(): String {
        val resolver = PathMatchingResourcePatternResolver()
        val migrations = resolver.getResources("classpath:db/migration/postgresql/V*.sql")
            .sortedBy { it.filename!!.substringAfter('V').substringBefore("__").toInt() }
        return migrations.joinToString("\n") { it.inputStream.reader().readText() }
    }

    /**
     * The SQLite baseline and every migration after it, in order. Since SQLite
     * installations exist the baseline cannot be edited - its checksum would
     * refuse their next start - so a widened CHECK arrives in a numbered file
     * that rebuilds the table, V12 the first, and reading only V1 would report
     * it missing.
     */
    private fun sqliteSchema(): String =
        PathMatchingResourcePatternResolver().getResources("classpath:db/migration/sqlite/V*.sql")
            .sortedBy { it.filename!!.substringAfter('V').substringBefore("__").toInt() }
            .joinToString("\n") { it.inputStream.reader().readText() }

    /**
     * Every named CHECK in the text, and the quoted literals it names. Later
     * definitions of one name replace earlier ones, which is what makes reading
     * the whole history in order equivalent to reading the schema it produced.
     */
    private fun checksIn(sql: String): Map<String, Set<String>> {
        val checks = linkedMapOf<String, Set<String>>()
        val declaration = Regex("""CONSTRAINT\s+(\w+)\s+CHECK\s*\(""", RegexOption.IGNORE_CASE)
        for (match in declaration.findAll(sql)) {
            val body = balanced(sql, match.range.last) ?: continue
            checks[match.groupValues[1].lowercase()] = Regex("'([^']*)'").findAll(body)
                .map { it.groupValues[1] }
                .toSet()
        }
        return checks
    }

    /** The text between the parenthesis at [open] and the one that closes it. */
    private fun balanced(sql: String, open: Int): String? {
        var depth = 0
        for (i in open until sql.length) {
            when (sql[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return sql.substring(open + 1, i)
            }
        }
        return null
    }
}
