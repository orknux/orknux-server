package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Sort
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A workspace shaped like the one that would not copy. Issue #581.
 *
 * On a Postgres installation a duplicate of a workspace with connections, MCP
 * servers, an Azure OpenAI provider reading its key from a workspace variable,
 * several models, agents granted servers and workflows sat for ever, with
 * nothing in the log. This builds that shape, copies it while somebody else
 * keeps the database busy - the page asking how far the copy has got, and the
 * checks the monitors run, which write to the source's own rows - and holds the
 * copy to a minute. Pre-emptively, so a copy that waits for ever fails this
 * test rather than hanging the suite.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceCopyUnderLoadTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val connections: WorkspaceConnectionRepository,
    @Autowired val connectionService: WorkspaceConnectionService,
    @Autowired val models: ModelService,
    @Autowired val progress: WorkspaceCopyProgress,
    @Autowired val duplicator: WorkspaceDuplicator,
    @Autowired val transactions: PlatformTransactionManager,
    @Autowired val dataSource: javax.sql.DataSource,
    @org.springframework.beans.factory.annotation.Value("\${spring.datasource.url}") val jdbcUrl: String,
) {

    private fun id(document: String, path: String): Long =
        graphQlTester.document(document).execute().path(path).entity(Long::class.java).get()

    @Test
    fun `a workspace shaped like production copies within a minute while the database is busy`() {
        val stamp = System.nanoTime()

        // An installation connection, which every new workspace inherits.
        graphQlTester.document(
            """mutation { createConnection(input: { name: "Outbound $stamp", type: HTTP, url: "https://outbound.invalid" }) { id } }""",
        ).execute().errors().verify()
        val source = id("""mutation { createWorkspace(input: { name: "dup-load-$stamp" }) { id } }""", "createWorkspace.id")

        val catalog = id(
            """mutation { createVariableCatalog(workspaceId: $source, name: "keys") { id } }""",
            "createVariableCatalog.id",
        )
        val key = id(
            """mutation { createVariable(input: { workspaceId: $source, catalogId: $catalog, name: "azure_key",
                 type: STRING, kind: SECRET, value: "sk-azure" }) { id } }""",
            "createVariable.id",
        )
        val token = id(
            """mutation { createVariable(input: { workspaceId: $source, catalogId: $catalog, name: "hook_token",
                 type: STRING, kind: SECRET, value: "t-hook" }) { id } }""",
            "createVariable.id",
        )

        (1..3).forEach { at ->
            graphQlTester.document(
                """mutation { createWorkspaceConnection(input: { workspaceId: $source, name: "Hook $at", type: HTTP,
                     url: "https://hook$at.invalid", authType: BEARER_TOKEN, secretVariableId: $token,
                     headers: [{ name: "X-Team", value: "desk" }] }) { id } }""",
            ).execute().errors().verify()
        }
        val servers = (1..3).map { at ->
            graphQlTester.document(
                """mutation { createMcpServer(input: { workspaceId: $source, name: "mcp-$at", address: "https://mcp$at.invalid/mcp",
                     authType: API_KEY, secret: "k-$at", headers: [{ name: "X-Tenant", value: "t$at" }] }) { id } }""",
            ).execute().errors().verify()
            "mcp-$at"
        }
        val azure = id(
            """mutation { createModelProvider(input: { workspaceId: $source, name: "Azure", type: AZURE_OPENAI,
                 endpoint: "https://azure.invalid", secretVariableId: $key, apiVersion: "2025-04-01-preview",
                 deploymentName: "gpt" }) { id } }""",
            "createModelProvider.id",
        )
        val other = id(
            """mutation { createModelProvider(input: { workspaceId: $source, name: "Local", endpoint: "http://local.invalid/v1",
                 secret: "sk-local" }) { id } }""",
            "createModelProvider.id",
        )
        val modelIds = listOf(azure, azure, azure, other).mapIndexed { at, provider ->
            id(
                """mutation { createModel(input: { providerId: $provider, name: "Model $at", modelId: "m$at", kind: CHAT }) { id } }""",
                "createModel.id",
            )
        }
        workspaces.findById(source).get().let { it.quickChatModelId = modelIds.first(); workspaces.save(it) }

        (1..6).forEach { at ->
            val agent = id(
                """mutation { createAgent(input: { workspaceId: $source, name: "Agent $at", type: LLM }) { id } }""",
                "createAgent.id",
            )
            graphQlTester.document(
                """mutation { updateAgent(id: $agent, input: { name: "Agent $at", modelId: ${modelIds[at % modelIds.size]},
                     mcpServers: [${servers.joinToString { "\"$it\"" }}] }) { id } }""",
            ).execute().errors().verify()
        }
        (1..4).forEach { at ->
            graphQlTester.document(
                """mutation { createFunction(input: { workspaceId: $source, name: "step$at" }) { id } }""",
            ).execute().errors().verify()
            graphQlTester.document(
                """mutation { createWorkflow(input: { workspaceId: $source, name: "Flow $at $stamp" }) { id } }""",
            ).execute().errors().verify()
        }

        val sourceConnections = connections.findByWorkspaceId(source, Sort.by("name"))
        assertThat(sourceConnections.filter { it.connectionId != null }).describedAs("an inherited connection").isNotEmpty()

        /*
         * Somebody else, all the while: the page's poll - a transaction, as its
         * session is, and a read of the progress - and the monitors' checks,
         * which write to the source's provider and connections.
         */
        val stop = AtomicBoolean(false)
        val polls = AtomicInteger()
        val checks = AtomicInteger()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val busy = Executors.newFixedThreadPool(3)
        busy.execute {
            while (!stop.get()) {
                runCatching { TransactionTemplate(transactions).execute { workspaces.count() }; progress.read("load-$stamp") }
                    .onSuccess { polls.incrementAndGet() }.onFailure { failures += it }
                Thread.sleep(20)
            }
        }
        busy.execute {
            while (!stop.get()) {
                runCatching { models.testProvider(azure); models.testProvider(other) }
                    .onSuccess { checks.incrementAndGet() }.onFailure { failures += it }
            }
        }
        busy.execute {
            while (!stop.get()) {
                sourceConnections.forEach { held ->
                    runCatching { connectionService.testWorkspaceConnection(requireNotNull(held.id)) }
                        .onFailure { failures += it }
                }
            }
        }

        val security = SecurityContextHolder.getContext()
        val seen = java.util.Collections.synchronizedList(mutableListOf<WorkspaceCopyProgress.Step>())
        val copied: WorkspaceDuplicator.Copied = try {
            assertTimeoutPreemptively<WorkspaceDuplicator.Copied>(Duration.ofSeconds(60)) {
                SecurityContextHolder.setContext(security)
                duplicator.duplicate(source, "dup-load-copy-$stamp", "alice") { step ->
                    seen += step
                    progress.report("load-$stamp", step)
                }
            }
        } finally {
            stop.set(true)
            busy.shutdown()
            busy.awaitTermination(30, TimeUnit.SECONDS)
        }

        assertThat(copied.problems).isEmpty()
        assertThat(copied.counts).containsEntry("connection", sourceConnections.size)
            .containsEntry("mcp server", 3).containsEntry("model provider", 2).containsEntry("agent", 6)
            .containsEntry("workflow", 4)
        assertThat(polls.get()).isPositive()
        assertThat(failures.map { it.toString() }).describedAs("what the busy threads met").isEmpty()
        // Counted from the first step: the externals as well as the components.
        assertThat(seen.first().overallDone).isZero()
        assertThat(seen.first().kind).isEqualTo("connection")
        assertThat(seen.last().overallDone).isEqualTo(seen.last().overallTotal)
    }

    /**
     * A lock the copy cannot get ends the copy, rather than the copy waiting for
     * it for ever. Issue #581: on Postgres a lock wait has no limit unless one
     * is set, and a copy waiting on one sat with nothing in the log.
     *
     * The installation's connection is held FOR UPDATE by somebody else, which
     * is what any transaction deleting it would hold. A workspace connection
     * inheriting it needs a share of that row to be inserted, so the copy waits
     * on it - for the two seconds the setting says, and then stops and says so.
     * SQLite has one writer and no row locks; its wait is bounded already.
     */
    @Test
    fun `a copy waiting on a lock stops after the wait the settings allow, and says where`() {
        assumeTrue(!jdbcUrl.startsWith("jdbc:sqlite:"), "row locks are Postgres's")
        val stamp = System.nanoTime()
        val held = id(
            """mutation { createConnection(input: { name: "Held $stamp", type: HTTP, url: "https://held.invalid" }) { id } }""",
            "createConnection.id",
        )
        val source = id("""mutation { createWorkspace(input: { name: "dup-lock-$stamp" }) { id } }""", "createWorkspace.id")
        graphQlTester.document("""mutation { setWorkspaceCopyLockWaitSeconds(seconds: 2) { workspaceCopyLockWaitSeconds } }""")
            .execute().path("setWorkspaceCopyLockWaitSeconds.workspaceCopyLockWaitSeconds").entity(Int::class.java).isEqualTo(2)

        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        try {
            holder.execute {
                TransactionTemplate(transactions).executeWithoutResult {
                    org.springframework.jdbc.core.JdbcTemplate(dataSource)
                        .queryForList("SELECT id FROM connection WHERE id = ? FOR UPDATE", held)
                    locked.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()

            val security = SecurityContextHolder.getContext()
            val stopped = assertTimeoutPreemptively<Throwable>(Duration.ofSeconds(20)) {
                SecurityContextHolder.setContext(security)
                org.junit.jupiter.api.Assertions.assertThrows(WorkspaceCopyStoppedException::class.java) {
                    duplicator.duplicate(source, "dup-lock-copy-$stamp", "alice")
                }
            }
            assertThat(stopped.message)
                .contains("stopped at connection \"Held $stamp\"")
                .contains("waited 2 seconds for a row another transaction was holding")
                .contains("dup-lock-copy-$stamp")
        } finally {
            release.countDown()
            holder.shutdown()
            holder.awaitTermination(30, TimeUnit.SECONDS)
            graphQlTester.document("""mutation { setWorkspaceCopyLockWaitSeconds(seconds: 60) { workspaceCopyLockWaitSeconds } }""")
                .execute().errors().verify()
        }
    }

    @Test
    fun `the lock wait is refused outside a second and an hour`() {
        graphQlTester.document("""mutation { setWorkspaceCopyLockWaitSeconds(seconds: 0) { workspaceCopyLockWaitSeconds } }""")
            .execute().errors().expect { it.extensions["code"] == "CopyLockWaitOutOfRange" }.verify()
    }
}
