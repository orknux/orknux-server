package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.database.isSqlite
import io.mszymanski.orknux.server.database.jdbcUrlOf
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.config.SessionRepositoryCustomizer
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * Sessions in the database, so signing in outlives the process.
 *
 * Enabled here rather than left to auto-configuration, which is not a preference:
 * Spring Boot 4 splits auto-configuration into a module per technology —
 * `spring-boot-jdbc`, `spring-boot-security`, `spring-boot-ldap` and so on — and
 * there is no session module on this classpath. Adding `spring-session-jdbc` alone
 * put the repository *class* within reach and wired nothing: no repository bean, no
 * filter, and every session still a Tomcat object in this JVM's heap. It looked
 * exactly like it was working — the tables existed, nothing errored, and the table
 * stayed empty while somebody was signed in.
 *
 * The annotation creates the repository and the filter itself, so this does not
 * depend on which auto-configuration modules happen to be present.
 *
 * The schema is not created here. Flyway owns it, in V71, copied from Spring
 * Session's own DDL — two things creating tables is two things that can disagree.
 */
@Configuration(proxyBeanMethods = false)
/*
 * The cleanup schedule is written here rather than taken from a property: Spring
 * Session validates this attribute as a cron expression before any placeholder in it
 * is resolved, so `${...}` fails startup with "cleanupCron must be valid" — which
 * says nothing about the actual cause. Half past every hour, and the hour, is not
 * something an installation needs to tune.
 */
@EnableJdbcHttpSession(cleanupCron = "0 */30 * * * *")
class SessionStoreConfig {

    /**
     * How long a session lasts, from the one place that already says so.
     *
     * `server.servlet.session.timeout` is where anybody would look for it, and it is
     * what the servlet container uses. Without this the repository would quietly keep
     * its own default of thirty minutes, and the setting in the configuration file
     * would be a documented lie.
     */
    /**
     * The session cookie, as the configuration file describes it. Issue #588.
     *
     * Spring Session writes its own cookie and reads none of
     * `server.servlet.session.cookie`, so ORKNUX_SESSION_COOKIE_SAME_SITE and
     * ORKNUX_SESSION_COOKIE_HTTP_ONLY were documented and did nothing: the
     * cookie was Lax and HttpOnly whatever they said. And Secure followed
     * whether the request reached this process over TLS, which behind a proxy
     * that ends TLS it never does - so the cookie went out without it.
     * ORKNUX_SESSION_COOKIE_SECURE says so where the request cannot.
     */
    @Bean
    fun sessionCookie(server: ServerProperties, web: WebProperties): org.springframework.session.web.http.CookieSerializer =
        org.springframework.session.web.http.DefaultCookieSerializer().apply {
            val cookie = server.servlet.session.cookie
            cookie.sameSite?.let { setSameSite(it.attributeValue()) }
            cookie.httpOnly?.let { setUseHttpOnlyCookie(it) }
            when (web.sessionCookieSecure.trim().lowercase()) {
                "true" -> setUseSecureCookie(true)
                "false" -> setUseSecureCookie(false)
                // "auto": Secure when the request arrived over TLS, as before.
            }
        }

    @Bean
    fun sessionTimeout(server: ServerProperties): SessionRepositoryCustomizer<JdbcIndexedSessionRepository> =
        SessionRepositoryCustomizer { repository ->
            server.servlet.session.timeout?.let { repository.setDefaultMaxInactiveInterval(it) }
        }

    /**
     * How a session read or write is wrapped in a transaction.
     *
     * Spring Session would make this itself, and would make it REQUIRES_NEW: a
     * session is stored whatever becomes of the work that stored it, which is
     * what you want from a session store and costs a second connection while the
     * caller's transaction waits.
     *
     * That is right on Postgres and is a deadlock on SQLite, where there is one
     * write lock for the whole database. The caller holds it, the session's own
     * transaction waits for it on another connection, and the caller cannot
     * reach its commit until the wait ends. Nothing resolves that; it fails
     * after the busy timeout, on whichever request happened to end a session or
     * reset a password.
     *
     * So on SQLite the session joins the caller instead - one connection, no
     * wait. The independence is what is given up: a request that rolls back now
     * takes what it wrote about its own session with it. On a database that
     * takes one writer that is the lesser of the two, and on Postgres nothing
     * changes.
     *
     * The bean name is not decoration. Spring Session looks this up by the
     * qualifier `springSessionTransactionOperations` and builds its own when
     * nothing answers to it.
     */
    @Bean
    fun springSessionTransactionOperations(
        dataSource: DataSource,
        transactions: PlatformTransactionManager,
    ): TransactionOperations = TransactionTemplate(transactions).apply {
        propagationBehavior = if (isSqlite(jdbcUrlOf(dataSource))) {
            TransactionDefinition.PROPAGATION_REQUIRED
        } else {
            TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
    }
}
