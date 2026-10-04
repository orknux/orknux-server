package io.mszymanski.orknux.server.cluster

import io.mszymanski.orknux.connector.cluster.ClusterLeader
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.database.isSqlite
import io.mszymanski.orknux.server.database.jdbcUrlOf
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.provider.sql.DatabaseProduct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionTranslator
import org.springframework.transaction.PlatformTransactionManager
import java.sql.SQLException
import javax.sql.DataSource

/**
 * The cluster lease, wired. Issue #597.
 *
 * [ClusterLeader] lives in the connection module because the Slack listener and
 * the checks there ask it; what it is built from - the database, the settings,
 * which engine is running - is the app's to know.
 */
@Configuration(proxyBeanMethods = false)
class ClusterConfig {

    /**
     * ShedLock's JDBC provider on the application's own pool and transactions.
     *
     * The application's transaction manager rather than one ShedLock would make
     * for itself, because on SQLite that one is the queue in front of the single
     * writer: a lease written around it would wait on the busy timeout instead
     * of its turn.
     *
     * The database's clock on Postgres, so replicas whose clocks disagree still
     * agree on when a lease ran out. SQLite has no such statements in ShedLock
     * and needs none - it is one process on one machine.
     */
    @Bean
    fun clusterLockProvider(dataSource: DataSource, transactions: PlatformTransactionManager): LockProvider =
        lockProvider(dataSource, transactions, ClusterLeader.defaultInstance())

    @Bean
    fun clusterLeader(
        locks: LockProvider,
        dataSource: DataSource,
        settings: InstallationSettings,
        @Value("\${orknux.cluster.lease-enabled:true}") enabled: Boolean,
        @Value("\${orknux.temporal.enabled:true}") temporal: Boolean,
    ): ClusterLeader = ClusterLeader(
        locks = locks,
        timings = { settings.clusterLeaseSeconds() },
        enabled = enabled,
        requireSole = !temporal,
        instance = (locks as? NamedLockProvider)?.instance ?: ClusterLeader.defaultInstance(),
        holderOf = holderOf(JdbcTemplate(dataSource)),
    )

    companion object {

        /**
         * A provider that writes [instance] as the holder. Its own function so a
         * test can build two of them on one pool and be two replicas.
         */
        fun lockProvider(dataSource: DataSource, transactions: PlatformTransactionManager, instance: String): LockProvider {
            val sqlite = isSqlite(jdbcUrlOf(dataSource))
            val jdbc = JdbcTemplate(dataSource)
            if (sqlite) jdbc.exceptionTranslator = SqliteDuplicateKeys(jdbc.exceptionTranslator)
            val builder = JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc)
                .withTransactionManager(transactions)
                .withTableName(ClusterLeader.TABLE)
                .withLockedByValue(instance)
            if (!sqlite) {
                builder.withDatabaseProduct(DatabaseProduct.POSTGRES_SQL).usingDbTime()
            }
            return NamedLockProvider(JdbcTemplateLockProvider(builder.build()), instance)
        }

        /** Who the row says holds a lease - not whether it is still in date, which is the database's clock. */
        fun holderOf(jdbc: JdbcTemplate): (String) -> String? = { name ->
            jdbc.query("select locked_by from ${ClusterLeader.TABLE} where name = ?", { rs, _ -> rs.getString(1) }, name)
                .firstOrNull()
        }
    }
}

/**
 * A taken name said the way ShedLock listens for it.
 *
 * A replica asking for a lease another already wrote tries an insert first and
 * expects a [DuplicateKeyException] to send it on to the update. Spring has no
 * error codes for SQLite, so the primary key refusing the insert arrived as an
 * uncategorised exception and the lease could never change hands there - a
 * graceful stop let go and nobody could pick it up. Found by running
 * ClusterLeaderTest on SQLite; Postgres never shows it.
 */
class SqliteDuplicateKeys(private val fallback: SQLExceptionTranslator) : SQLExceptionTranslator {
    override fun translate(task: String, sql: String?, ex: SQLException): DataAccessException? =
        if (ex.errorCode == SQLITE_CONSTRAINT && ex.message.orEmpty().contains("UNIQUE constraint failed")) {
            DuplicateKeyException(ex.message.orEmpty(), ex)
        } else {
            fallback.translate(task, sql, ex)
        }

    private companion object {
        /** SQLite's result code for any constraint; the message says which. */
        const val SQLITE_CONSTRAINT = 19
    }
}

/** A provider that remembers who it writes as, so the leader built on it says the same name. */
class NamedLockProvider(private val delegate: LockProvider, val instance: String) : LockProvider by delegate
