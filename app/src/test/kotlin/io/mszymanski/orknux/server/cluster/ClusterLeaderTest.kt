package io.mszymanski.orknux.server.cluster

import io.mszymanski.orknux.connector.cluster.ClusterLeader
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretColumns
import io.mszymanski.orknux.server.admin.DoctorAPI
import io.mszymanski.orknux.server.admin.DoctorVerdict
import io.mszymanski.orknux.server.attachment.AttachmentProperties
import io.mszymanski.orknux.server.security.SecurityProperties
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * The cluster lease, on the real table, with two replicas in one JVM. Issue #597.
 *
 * Each "replica" is a [ClusterLeader] on a provider of its own that writes its
 * own name, which is all that tells two servers apart to the row. The lease
 * name is fresh per test, so nothing here contends with the context's own
 * leader or with another test. Runs on whichever engine the suite is on, so
 * both are covered: Postgres on the database's clock, SQLite on this one's.
 *
 * Three seconds of lease, the floor [ClusterLeader] allows, so failover costs a
 * few seconds of waiting rather than half a minute.
 */
@SpringBootTest
class ClusterLeaderTest(
    @Autowired val dataSource: DataSource,
    @Autowired val transactions: PlatformTransactionManager,
    @Autowired val cipher: SecretCipher,
    @Autowired val security: SecurityProperties,
    @Autowired val web: WebProperties,
    @Autowired val access: WorkspaceAccess,
    @Autowired val attachments: AttachmentProperties,
    @Autowired val secrets: SecretColumns,
) {

    private val name = "test-" + UUID.randomUUID().toString().take(8)
    private val built = CopyOnWriteArrayList<ClusterLeader>()

    @AfterEach
    fun stopAll() {
        built.forEach { it.stop() }
        JdbcTemplate(dataSource).update("delete from shedlock where name = ?", name)
    }

    @Test
    fun `the lease taken by one replica is refused to a second`() {
        val first = replica("first")
        val second = replica("second")

        first.renew()
        second.renew()

        assertThat(first.leads()).isTrue()
        assertThat(second.leads()).isFalse()
        assertThat(second.holder()).isEqualTo(first.instance)
    }

    @Test
    fun `a holder that keeps renewing keeps the lease past its first length`() {
        val first = replica("first")
        val second = replica("second")
        first.renew()

        repeat(3) {
            Thread.sleep(LEASE_MILLIS / 2)
            first.renew()
            second.renew()
            assertThat(first.leads()).isTrue()
            assertThat(second.leads()).isFalse()
        }
    }

    @Test
    fun `when the holder stops renewing the lease moves to the other replica`() {
        val first = replica("first")
        val second = replica("second")
        val heard = CopyOnWriteArrayList<Boolean>()
        second.onChange { heard += it }
        first.renew()
        second.renew()
        assertThat(second.leads()).isFalse()

        first.abandon()

        // Not before the row has run out: a holder that merely missed one
        // renewal must not lose the lease to the first replica that asks.
        second.renew()
        assertThat(second.leads()).isFalse()

        await().atMost(Duration.ofMillis(LEASE_MILLIS * 3)).pollInterval(Duration.ofMillis(250)).until {
            second.renew()
            second.leads()
        }
        assertThat(second.holder()).isEqualTo(second.instance)
        assertThat(heard).containsExactly(true)
        assertThat(first.leads()).isFalse()
    }

    @Test
    fun `a holder that stops gracefully lets go at once`() {
        val first = replica("first")
        val second = replica("second")
        first.start()
        assertThat(first.leads()).isTrue()

        first.stop()
        second.renew()

        assertThat(second.leads()).isTrue()
    }

    @Test
    fun `a holder stops believing it leads before the row runs out`() {
        val first = replica("first")
        first.renew()
        assertThat(first.leads()).isTrue()

        // No renewals: the belief ends a third of a lease before the row does,
        // so there is no moment at which another replica holds the row and
        // this one still says yes.
        Thread.sleep(LEASE_MILLIS * 3 / 4)

        assertThat(first.leads()).isFalse()
    }

    @Test
    fun `where a second server would be wrong it refuses to start`() {
        val first = replica("first")
        first.start()
        val second = replica("second", requireSole = true)

        assertThatThrownBy { second.start() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(first.instance)
            .hasMessageContaining("ORKNUX_TEMPORAL_ENABLED")
        assertThat(first.leads()).isTrue()
    }

    @Test
    fun `where a second server would be wrong it waits out a predecessor that died`() {
        val dead = replica("dead")
        dead.renew()
        dead.abandon()

        val next = replica("next", requireSole = true)
        next.start()

        assertThat(next.leads()).isTrue()
    }

    @Test
    fun `turned off, every process leads and nothing is written`() {
        val alone = ClusterLeader.alone()
        alone.start()

        assertThat(alone.leads()).isTrue()
        assertThat(JdbcTemplate(dataSource).queryForObject("select count(*) from shedlock where name = ?", Int::class.java, ClusterLeader.LEADER))
            .isZero()
    }

    @Test
    fun `the Doctor page says which server leads, on the leader and on the follower`() {
        val first = replica("first")
        val second = replica("second")
        first.renew()
        second.renew()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("doctor", "n/a", listOf(SimpleGrantedAuthority("ROLE_ADMINS")))
        try {
            val onFirst = doctorOn(first).doctor().first { it.name == "Replicas" }
            val onSecond = doctorOn(second).doctor().first { it.name == "Replicas" }

            assertThat(onFirst.verdict).isEqualTo(DoctorVerdict.OK)
            assertThat(onFirst.detail).startsWith("This server (${first.instance}) holds the cluster lease")
            assertThat(onSecond.verdict).isEqualTo(DoctorVerdict.OK)
            assertThat(onSecond.detail).startsWith("${first.instance} holds the cluster lease")
                .contains("this server (${second.instance}) takes over")
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun doctorOn(leader: ClusterLeader) =
        DoctorAPI(cipher, security, web, access, JdbcTemplate(dataSource), attachments, secrets, leader)

    private fun replica(who: String, requireSole: Boolean = false): ClusterLeader {
        val instance = "$who-${UUID.randomUUID().toString().take(4)}"
        return ClusterLeader(
            locks = ClusterConfig.lockProvider(dataSource, transactions, instance),
            timings = { LEASE_MILLIS / 1000 },
            requireSole = requireSole,
            instance = instance,
            name = name,
            holderOf = ClusterConfig.holderOf(JdbcTemplate(dataSource)),
        ).also { built += it }
    }

    private companion object {
        const val LEASE_MILLIS = 3000L
    }
}
