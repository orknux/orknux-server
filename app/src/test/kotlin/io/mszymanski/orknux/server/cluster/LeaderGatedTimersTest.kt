package io.mszymanski.orknux.server.cluster

import io.mszymanski.orknux.connector.cluster.ClusterLeader
import io.mszymanski.orknux.connector.connection.ConnectionCheckProperties
import io.mszymanski.orknux.connector.connection.ConnectionMonitor
import io.mszymanski.orknux.connector.connection.McpServerCheckProperties
import io.mszymanski.orknux.connector.connection.McpServerMonitor
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.McpServerService
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.model.ModelProviderCheckProperties
import io.mszymanski.orknux.connector.model.ModelProviderMonitor
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.shell.ShellProperties
import io.mszymanski.orknux.connector.shell.ShellRepository
import io.mszymanski.orknux.connector.shell.ShellService
import io.mszymanski.orknux.connector.shell.ShellSessionRepository
import io.mszymanski.orknux.connector.shell.ShellSessionService
import io.mszymanski.orknux.connector.shell.ShellSessionSweeper
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.ScratchpadSweepProperties
import io.mszymanski.orknux.server.llm.ScratchpadSweeper
import io.mszymanski.orknux.server.llm.SessionDueSweeper
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.llm.SessionProperties
import io.mszymanski.orknux.server.llm.SessionScratchpadRepository
import io.mszymanski.orknux.server.revision.ComponentRevisionRepository
import io.mszymanski.orknux.server.revision.RevisionProperties
import io.mszymanski.orknux.server.revision.RevisionSweeper
import io.mszymanski.orknux.server.task.TaskEngine
import io.mszymanski.orknux.server.task.TaskRepository
import io.mszymanski.orknux.server.task.TaskSweepProperties
import io.mszymanski.orknux.server.task.TaskSweeper
import io.mszymanski.orknux.server.workflow.ExecutionRetentionProperties
import io.mszymanski.orknux.server.workflow.ExecutionSweeper
import io.mszymanski.orknux.server.workflow.WorkflowPublicationRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import net.javacrumbs.shedlock.core.LockProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Optional

/**
 * Every timer that must run once asks the lease first. Issue #597.
 *
 * Each one is built twice, around a replica that leads and one that does not,
 * and its timer's pass is called by hand: the follower must not touch the
 * table the pass reads, and the leader must. Stand-ins for the repositories,
 * because what is being asserted is whether the pass happened at all - what a
 * pass does is each sweeper's own test's business. The lease itself, on both
 * databases, is [ClusterLeaderTest].
 */
class LeaderGatedTimersTest {

    private val leading = ClusterLeader.alone()

    /** A replica whose every attempt at the lease is refused, as it is while another holds it. */
    private val following = ClusterLeader(
        locks = LockProvider { Optional.empty() },
        timings = { 30 },
        instance = "follower",
    ).also { it.renew() }

    @Test
    fun `the follower is a follower`() {
        assertThat(following.leads()).isFalse()
        assertThat(leading.leads()).isTrue()
    }

    @Test
    fun `the connection checks run on the leader only`() {
        gated(WorkspaceConnectionRepository::class.java, { it.findAll() }) { repo, leader ->
            ConnectionMonitor(repo, mock(WorkspaceConnectionService::class.java), ConnectionCheckProperties(), leader)::timedPass
        }
    }

    @Test
    fun `the MCP server checks run on the leader only`() {
        gated(McpServerRepository::class.java, { it.findAll() }) { repo, leader ->
            McpServerMonitor(repo, mock(McpServerService::class.java), McpServerCheckProperties(), leader)::timedPass
        }
    }

    @Test
    fun `the model provider checks run on the leader only`() {
        gated(ModelProviderRepository::class.java, { it.findAll() }) { repo, leader ->
            ModelProviderMonitor(repo, mock(ModelService::class.java), ModelProviderCheckProperties(), leader)::timedPass
        }
    }

    @Test
    fun `the shell sweep runs on the leader only`() {
        gated(ShellRepository::class.java, { it.findAll() }) { repo, leader ->
            ShellSessionSweeper(
                repo,
                mock(ShellSessionRepository::class.java),
                mock(ShellService::class.java),
                mock(ShellSessionService::class.java),
                ShellProperties(),
                leader,
            )::timedPass
        }
    }

    @Test
    fun `the task sweep runs on the leader only`() {
        gated(TaskRepository::class.java, { it.idsInStateSince(anyOf(), anyOf()) }) { repo, leader ->
            TaskSweeper(repo, mock(TaskEngine::class.java), settings(), TaskSweepProperties(), leader)::timedPass
        }
    }

    @Test
    fun `the run history sweep runs on the leader only`() {
        gated(WorkflowExecutionRepository::class.java, { it.idsFinishedBefore(anyOf()) }) { repo, leader ->
            ExecutionSweeper(
                repo,
                mock(ExecutionStepRepository::class.java),
                mock(ExecutionLogRepository::class.java),
                settings(),
                ExecutionRetentionProperties(),
                leader,
            )::timedPass
        }
    }

    @Test
    fun `the revision sweep runs on the leader only`() {
        gated(ComponentRevisionRepository::class.java, { it.idsRecordedBefore(anyOf()) }) { repo, leader ->
            RevisionSweeper(repo, mock(WorkflowPublicationRepository::class.java), settings(), RevisionProperties(), leader)::timedPass
        }
    }

    @Test
    fun `the scratchpad sweep runs on the leader only`() {
        gated(SessionScratchpadRepository::class.java, { it.deleteUpdatedBefore(anyOf()) }) { repo, leader ->
            ScratchpadSweeper(repo, settings(), ScratchpadSweepProperties(), leader)::timedPass
        }
    }

    @Test
    fun `reminders are announced by the leader only`() {
        val onFollower = mock(SessionEventRepository::class.java)
        SessionDueSweeper(onFollower, mock(ApplicationEventPublisher::class.java), SessionProperties(), following).timedPass()
        verifyNoInteractions(onFollower)

        val onLeader = mock(SessionEventRepository::class.java)
        SessionDueSweeper(onLeader, mock(ApplicationEventPublisher::class.java), SessionProperties(), leading).timedPass()
        verify(onLeader).sessionsDueBetween(anyOf(), anyOf())
    }

    @Test
    fun `a follower that takes over reads back over the gap the leader left`() {
        val events = mock(SessionEventRepository::class.java)
        val sweeper = SessionDueSweeper(events, mock(ApplicationEventPublisher::class.java), SessionProperties(), following)
        val asked = OffsetDateTime.now()
        sweeper.timedPass()

        // The first look it would take on becoming the leader: from a lease
        // back, not from the moment it took over.
        sweeper.sweep()

        val after = ArgumentCaptor.forClass(OffsetDateTime::class.java)
        verify(events).sessionsDueBetween(after.capture() ?: asked, anyOf())
        assertThat(after.value).isBefore(asked.minus(Duration.ofSeconds(29)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(): T = org.mockito.Mockito.any<T>() ?: (null as T)

    private fun settings(): InstallationSettings = mock(InstallationSettings::class.java).also {
        `when`(it.taskSweepMinutes()).thenReturn(5)
        `when`(it.executionRetentionDays()).thenReturn(90)
        `when`(it.revisionRetentionDays()).thenReturn(14)
        `when`(it.scratchpadKeepDays()).thenReturn(30)
    }

    /**
     * Built around the follower, the pass reads nothing; built around the
     * leader, it reads [read] off the same kind of repository.
     */
    private fun <R : Any> gated(type: Class<R>, read: (R) -> Any?, build: (R, ClusterLeader) -> () -> Boolean) {
        val onFollower = mock(type)
        assertThat(build(onFollower, following)()).isFalse()
        verifyNoInteractions(onFollower)

        val onLeader = mock(type)
        assertThat(build(onLeader, leading)()).isTrue()
        read(verify(onLeader))
    }
}
