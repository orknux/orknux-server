package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.chat.AgentConversation
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The walls between one agent turn and the rest of the server. Issue #616: a
 * few fat turns at once - reruns pressed on a run still going - filled a 2 GB
 * heap and the server died with every other run in it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BulkheadsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val bulkheads: Bulkheads,
    @Autowired val settings: InstallationSettingRepository,
    @Autowired val conversation: AgentConversation,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    @BeforeEach
    @AfterEach
    fun reset() {
        settings.deleteAllById(Bulkheads.NAMES)
        bulkheads.heapAfterGc = { 10 }
    }

    @Test
    fun `nothing set is the defaults, and a saved set reads back`() {
        assertThat(bulkheads.values()).isEqualTo(BulkheadValues.DEFAULT)

        graphQlTester.document(
            """mutation { setBulkheads(input: { turnsEnabled: true, turnsAtOnce: 7, turnWaitSeconds: 30,
               heapEnabled: false, heapPercent: 90, memoryEnabled: true, turnMemoryMb: 16, toolResultKb: 64 })
               { turnsAtOnce turnWaitSeconds heapEnabled heapPercent turnMemoryMb toolResultKb runningTurns } }""",
        ).execute()
            .path("setBulkheads.turnsAtOnce").entity(Int::class.java).isEqualTo(7)
            .path("setBulkheads.heapEnabled").entity(Boolean::class.java).isEqualTo(false)
            .path("setBulkheads.toolResultKb").entity(Int::class.java).isEqualTo(64)
            .path("setBulkheads.runningTurns").entity(Int::class.java).isEqualTo(0)

        assertThat(bulkheads.values().turnMemoryMb).isEqualTo(16)
        assertThat(audit.findAll().map { it.message }).contains("Agent turns at once: 7", "Heap guard for agent turns switched off")
    }

    @Test
    fun `a value out of range is refused with a code, and nothing is stored`() {
        graphQlTester.document(
            """mutation { setBulkheads(input: { turnsEnabled: true, turnsAtOnce: 0, turnWaitSeconds: 30,
               heapEnabled: true, heapPercent: 85, memoryEnabled: true, turnMemoryMb: 48, toolResultKb: 512 })
               { turnsAtOnce } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).singleElement().satisfies({
                assertThat(it.extensions["code"]).isEqualTo("BulkheadValueOutOfRange")
                assertThat(it.message).contains("Choose between 1 and 1000")
            })
        }
        assertThat(bulkheads.values()).isEqualTo(BulkheadValues.DEFAULT)
    }

    @Test
    fun `one turn more than the limit waits, and is refused when no place comes free`() {
        bulkheads.save(BulkheadValues.DEFAULT.copy(turnsAtOnce = 1, turnWaitSeconds = 1), "alice")
        val inside = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val first = thread { bulkheads.turn({ "refused" }) { inside.countDown(); leave.await(10, TimeUnit.SECONDS); "ran" } }
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue()

        val second = bulkheads.turn({ why -> why }) { "ran" }
        leave.countDown()
        first.join()

        assertThat(second).contains("already running 1 agent turns at once")
        // And with the wall switched off, the same second turn simply runs.
        bulkheads.save(BulkheadValues.DEFAULT.copy(turnsEnabled = false, turnsAtOnce = 1), "alice")
        val blocked = CountDownLatch(1)
        val holder = thread { bulkheads.turn({ "refused" }) { blocked.await(10, TimeUnit.SECONDS); "ran" } }
        assertThat(bulkheads.turn({ it }) { "ran" }).isEqualTo("ran")
        blocked.countDown()
        holder.join()
        assertThat(bulkheads.runningTurns()).isZero()
    }

    @Test
    fun `a full heap after a collection stops a turn before the model is asked`() {
        val workspace = workspaces.findByName("bulkheads") ?: workspaces.save(Workspace(name = "bulkheads"))
        val agent = agents.save(Agent(workspaceId = requireNotNull(workspace.id), name = "Heavy", type = AgentType.LLM))
        bulkheads.heapAfterGc = { 93 }

        val answered = conversation.answer(Long.MAX_VALUE, agent, listOf(ChatTurn("user", "hello")))

        assertThat(answered).isInstanceOf(ChatCompletion.Failed::class.java)
        answered as ChatCompletion.Failed
        assertThat(answered.reason).contains("93% full after its last clean-up")
        assertThat(answered.permanent).isFalse()

        bulkheads.save(BulkheadValues.DEFAULT.copy(heapEnabled = false), "alice")
        assertThat(bulkheads.heapRefusal()).isNull()
    }

    @Test
    fun `a turn holds its tool results to its budget, oldest first, and one result to its size`() {
        bulkheads.save(BulkheadValues.DEFAULT.copy(turnMemoryMb = 1, toolResultKb = 600), "alice")
        val big = "x".repeat(500 * 1024)
        val turns = mutableListOf(
            ChatTurn("system", "brief"),
            ChatTurn("user", big, respondingTo = "a"),
            ChatTurn("user", big, respondingTo = "b"),
            ChatTurn("user", big, respondingTo = "c"),
            ChatTurn("user", "y".repeat(700 * 1024), respondingTo = "d"),
        )

        val changed = bulkheads.bound(turns)

        assertThat(changed).isGreaterThan(0)
        assertThat(turns[0].content).isEqualTo("brief")
        // The oldest go first, and say so; the newest is cut, never dropped.
        assertThat(turns[1].content).startsWith(Bulkheads.DROPPED)
        assertThat(turns[2].content).startsWith(Bulkheads.DROPPED)
        assertThat(turns[4].content).startsWith("y".repeat(100)).contains("more characters of this result were not kept")
        assertThat(turns.filter { it.respondingTo != null }.sumOf { it.content.length }).isLessThanOrEqualTo(1024 * 1024)

        // Off, nothing is touched.
        bulkheads.save(BulkheadValues.DEFAULT.copy(memoryEnabled = false), "alice")
        val untouched = mutableListOf(ChatTurn("user", "z".repeat(2 * 1024 * 1024), respondingTo = "e"))
        assertThat(bulkheads.bound(untouched)).isZero()
        assertThat(untouched[0].content).hasSize(2 * 1024 * 1024)
    }
}
