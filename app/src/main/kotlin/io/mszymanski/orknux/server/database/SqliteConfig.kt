package io.mszymanski.orknux.server.database

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * How long anything waits for SQLite's one writer before giving up: the
 * connection's busy timeout, and the queue in [OneWriterTransactionManager].
 */
const val SQLITE_BUSY_TIMEOUT_MS: Long = 30_000

/** What tells the rest of the application which database is underneath. */
fun isSqlite(url: String?): Boolean = url != null && url.startsWith("jdbc:sqlite:")

/**
 * The URL a pool was built from, where it is one that remembers - which every
 * pool this application builds is. Null means the question cannot be answered,
 * and everywhere that asks treats that as Postgres, the default.
 */
fun jdbcUrlOf(dataSource: DataSource): String? = (dataSource as? HikariDataSource)?.jdbcUrl


/**
 * Refuses a database file in a directory that is not there, while it can still
 * be said plainly.
 *
 * SQLite makes the file but not the directory holding it, and everything above
 * reports the failure as not being able to open a connection - which is what an
 * operator sees when the volume they meant to mount is not mounted, and it does
 * not mention the path once. Naming it here turns a morning into a minute. The
 * file itself is allowed to be missing; a new installation is exactly that.
 */
private fun requireDirectoryExists(jdbcUrl: String) {
    val target = jdbcUrl.removePrefix("jdbc:sqlite:").substringBefore('?')
    // Everything SQLite treats as something other than a path on this disk: a
    // database held in memory, a shared cache name, or a file: URI with its own
    // rules. None of them has a directory to check.
    if (target.isEmpty() || target.startsWith(":") || target.startsWith("file:")) return

    val directory = Path.of(target).toAbsolutePath().parent ?: return
    if (!Files.isDirectory(directory)) {
        throw IllegalStateException(
            "The database file $target cannot be created: $directory is not a directory that exists. " +
                "SQLite makes the file but not the directory holding it, so create it, or point " +
                "ORKNUX_DB_URL somewhere that is already there.",
        )
    }
}

/**
 * The handful of things SQLite needs said to it before it behaves like a
 * database this application can be run on.
 *
 * None of it is configuration - there is no version of these an operator would
 * want to choose differently - so it is applied wherever the connection URL says
 * SQLite and is absent otherwise. On Postgres every bean here is a no-op.
 */
@Configuration
class SqliteConfig {

    /**
     * Four pragmas, set as each connection is opened, because SQLite's defaults
     * are the defaults of a library embedded in one program rather than of a
     * server several threads talk to at once.
     *
     * Foreign keys are off unless asked for. That is the one that matters most:
     * the schema is full of ON DELETE CASCADE and without this every one of them
     * is a comment. Deleting a workspace would leave its agents, its connections
     * and its chats behind, unreachable and still counted.
     *
     * WAL lets a reader carry on while a writer commits, which is the difference
     * between a page loading during a workflow run and a page waiting for it. It
     * writes two files beside the database and does not work on a network share;
     * both are true of SQLite generally rather than of this choice.
     *
     * The busy timeout is how long a caller waits for the one ahead of it rather
     * than failing at once. SQLite takes one writer at a time and the honest
     * answer to a second is to queue, not to raise.
     *
     * The transaction mode is what makes that timeout mean anything. Left to
     * itself SQLite starts a transaction without deciding whether it will write,
     * and takes the write lock later - so a transaction that read first and
     * writes second can find the database changed underneath it, which it
     * reports as busy immediately and does not retry, timeout or no timeout.
     * Taking the lock up front turns that case into the ordinary wait it should
     * have been. It costs concurrency, since a reading transaction now queues
     * behind a writing one, and that is the trade SQLite offers: correct and
     * serial, or parallel and occasionally refused.
     */
    @Bean
    fun sqlitePragmas(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is HikariDataSource && isSqlite(bean.jdbcUrl)) {
                requireDirectoryExists(bean.jdbcUrl)
                bean.addDataSourceProperty("foreign_keys", "true")
                bean.addDataSourceProperty("journal_mode", "WAL")
                bean.addDataSourceProperty("busy_timeout", SQLITE_BUSY_TIMEOUT_MS.toString())
                bean.addDataSourceProperty("transaction_mode", "IMMEDIATE")
            }
            return bean
        }
    }

    /**
     * The transaction manager Boot would have made, except that on SQLite it
     * queues for the write lock fairly - see [OneWriterTransactionManager] for
     * why SQLite's own waiting starves a page behind a long run. Declared here,
     * so Boot's backs off; its customizers are applied as Boot would have.
     */
    @Bean
    fun transactionManager(
        dataSource: DataSource,
        customizers: ObjectProvider<TransactionManagerCustomizers>,
    ): PlatformTransactionManager = OneWriterTransactionManager(isSqlite(jdbcUrlOf(dataSource))).also { manager ->
        customizers.ifAvailable { it.customize(manager) }
    }

    /**
     * Which dialect, and how Hibernate reads the schema back when it validates
     * it at startup.
     *
     * It asks for every table's columns in one call by default, and the SQLite
     * driver answers that by building a UNION of one SELECT per column. SQLite
     * refuses a compound SELECT of more than five hundred terms and this schema
     * has more columns than that, so the grouped read fails outright - not on
     * anything wrong with the schema, on its size. Asking table by table keeps
     * each query small. It is slower, once, at startup.
     */
    @Bean
    fun sqliteMetadataExtraction(dataSource: DataSource): HibernatePropertiesCustomizer =
        HibernatePropertiesCustomizer { properties ->
            if (isSqlite(jdbcUrlOf(dataSource))) {
                properties["hibernate.dialect"] = OrknuxSqliteDialect::class.java.name
                properties["hibernate.hbm2ddl.jdbc_metadata_extraction_strategy"] = "individually"
            }
        }
}
