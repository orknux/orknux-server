package io.mszymanski.orknux.server.recyclebin

import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.design.DesignDocumentRepository
import io.mszymanski.orknux.server.diagram.DiagramRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.util.concurrent.atomic.AtomicLong

/**
 * Deleting an object puts it in the workspace's recycle bin, and a restore
 * re-creates it.
 *
 * The six kinds that keep their whole state in one snapshot. A delete writes the
 * object down and removes it as before, so the bin is the only new thing; a
 * restore re-creates it with a new id - safe because a delete is refused while
 * anything still points at it - and the three code kinds come back whole enough
 * to assert a tool's parameters and an agent's prompt survived the trip.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class RecycleBinTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val entries: RecycleBinRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val documents: DesignDocumentRepository,
    @Autowired val diagrams: DiagramRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    /** A workspace of its own per test, so nothing it does reaches another. */
    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "recycle-${counter.incrementAndGet()}")).id)
    }

    /** This workspace's bin, and nobody else's. */
    private fun binned() = entries.findAll().filter { it.workspaceId == workspaceId }

    // ------------------------------------------------------------- the six kinds

    /** An agent comes back whole: the delete put it in the bin, the restore re-made it. */
    @Test
    fun `a deleted agent goes to the bin and restores with its prompt`() {
        val id = agent("Researcher", prompt = "Read the tests first.")
        deleteAgent(id)

        assertThat(agents.findById(id)).isEmpty
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.AGENT)
        assertThat(entry.name).isEqualTo("Researcher")
        assertThat(entry.deletedBy).isEqualTo("alice")

        restore(entry.id!!)

        assertThat(binned()).isEmpty()
        val back = agents.findByWorkspaceIdAndName(workspaceId, "Researcher")
        assertThat(back).isNotNull
        assertThat(back!!.systemPrompt).isEqualTo("Read the tests first.")
        // A new id, not the old one: nothing referenced it, so nothing is left dangling.
        assertThat(back.id).isNotEqualTo(id)
    }

    /** A tool's parameters are in the snapshot, so its signature survives the trip. */
    @Test
    fun `a deleted tool restores with its parameters`() {
        val id = tool("forecast")
        graphQlTester.document(
            """mutation { updateTool(id: $id, input: {
                 params: [{ name: "city", type: STRING }, { name: "days", type: NUMBER }],
                 source: "export default function forecast(city, days) { return {}; }",
                 typescript: "export default function forecast(city: string, days: number) { return {}; }"
               }) { signature } }""",
        ).execute().path("updateTool.signature").entity(String::class.java).isEqualTo("(city: string, days: number)")

        deleteTool(id)
        restore(binned().single().id!!)

        val back = requireNotNull(tools.findByWorkspaceIdAndName(workspaceId, "forecast"))
        assertThat(back.params.map { it.name }).containsExactly("city", "days")
    }

    /** A function restores, and a workspace function is editable again. */
    @Test
    fun `a deleted function goes to the bin and restores`() {
        val id = function("greet")
        deleteFunction(id)
        assertThat(binned().single().kind).isEqualTo(RecycleBinKind.FUNCTION)

        restore(binned().single().id!!)
        val back = requireNotNull(functions.findByWorkspaceIdAndName(workspaceId, "greet"))
        assertThat(back.editable).isTrue()
    }

    /** A skill restores into its folder with a fresh, usable id key. */
    @Test
    fun `a deleted skill restores into its catalog`() {
        val catalogId = graphQlTester.document(
            """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "reviews") { id } }""",
        ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()
        val id = graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "reviewCode", catalogId: $catalogId }) { id } }""",
        ).execute().path("createSkill.id").entity(Long::class.java).get()

        deleteSkill(id)
        restore(binned().single().id!!)

        val back = requireNotNull(skills.findByWorkspaceIdAndName(workspaceId, "reviewCode"))
        assertThat(back.catalogId).isEqualTo(catalogId)
        assertThat(back.key).isNotBlank()
    }

    /** A document goes to the bin and restores, re-filed in its catalog. */
    @Test
    fun `a deleted document goes to the bin and restores`() {
        val id = graphQlTester.document(
            """mutation { createDocument(workspaceId: $workspaceId, name: "Spec") { id } }""",
        ).execute().path("createDocument.id").entity(Long::class.java).get()
        val catalog = requireNotNull(documents.findById(id).orElse(null)).catalogId

        deleteDocument(id)
        assertThat(binned().single().kind).isEqualTo(RecycleBinKind.DOCUMENT)

        restore(binned().single().id!!)
        val back = documents.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId).single()
        assertThat(back.name).isEqualTo("Spec")
        assertThat(back.catalogId).isEqualTo(catalog)
    }

    /** A diagram goes to the bin and restores. */
    @Test
    fun `a deleted diagram goes to the bin and restores`() {
        val id = graphQlTester.document(
            """mutation { createDiagram(workspaceId: $workspaceId, name: "Flow") { id } }""",
        ).execute().path("createDiagram.id").entity(Long::class.java).get()

        deleteDiagram(id)
        assertThat(binned().single().kind).isEqualTo(RecycleBinKind.DIAGRAM)

        restore(binned().single().id!!)
        assertThat(diagrams.findAll().filter { it.workspaceId == workspaceId }.map { it.name }).contains("Flow")
    }

    // -------------------------------------------------------- refusing, purging

    /** A name taken since the delete is refused, not silently worked around. */
    @Test
    fun `restoring onto a taken name is refused`() {
        val id = agent("Lead")
        deleteAgent(id)
        agent("Lead") // the name is taken again

        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: ${binned().single().id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({ assertThat(it.message).contains("already exists") })
            }
        // Still in the bin: a refused restore changes nothing.
        assertThat(binned()).hasSize(1)
    }

    /** Deleting an object forever takes it out of the bin without re-creating it. */
    @Test
    fun `purging removes an entry without restoring it`() {
        val id = tool("forecast")
        deleteTool(id)
        val entryId = binned().single().id!!

        graphQlTester.document("""mutation { purgeRecycleBinEntry(id: $entryId) }""")
            .execute().path("purgeRecycleBinEntry").entity(Boolean::class.java).isEqualTo(true)

        assertThat(binned()).isEmpty()
        assertThat(tools.findByWorkspaceIdAndName(workspaceId, "forecast")).isNull()
    }

    /** Emptying the bin removes everything in it and says how much. */
    @Test
    fun `emptying the bin clears it`() {
        deleteTool(tool("one"))
        deleteTool(tool("two"))
        assertThat(binned()).hasSize(2)

        graphQlTester.document("""mutation { emptyRecycleBin(workspaceId: $workspaceId) }""")
            .execute().path("emptyRecycleBin").entity(Int::class.java).isEqualTo(2)
        assertThat(binned()).isEmpty()
    }

    /** The bin lists only its own workspace's entries, newest first. */
    @Test
    fun `the bin lists this workspace's deletions`() {
        deleteTool(tool("forecast"))
        graphQlTester.document(
            """query { recycleBin(workspaceId: $workspaceId) { totalElements content { kind name } } }""",
        ).execute()
            .path("recycleBin.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("recycleBin.content[0].name").entity(String::class.java).isEqualTo("forecast")
            .path("recycleBin.content[0].kind").entity(String::class.java).isEqualTo("TOOL")
    }

    /** The audit log says a restore happened, in the words the screen shows. */
    @Test
    fun `a restore is written into the audit log`() {
        val id = agent("Researcher")
        deleteAgent(id)
        restore(binned().single().id!!)

        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message })
            .anySatisfy { assertThat(it).isEqualTo("Agent Researcher restored from the recycle bin") }
    }

    // ---------------------------------------------------------------- helpers

    private fun agent(name: String, prompt: String? = null): Long {
        val input = buildString {
            append("""workspaceId: $workspaceId, name: "$name", type: LLM""")
            if (prompt != null) append(""", systemPrompt: "$prompt"""")
        }
        return graphQlTester.document("""mutation { createAgent(input: { $input }) { id } }""")
            .execute().path("createAgent.id").entity(Long::class.java).get()
    }

    private fun tool(name: String): Long = graphQlTester.document(
        """mutation { createTool(input: { workspaceId: $workspaceId, name: "$name" }) { id } }""",
    ).execute().path("createTool.id").entity(Long::class.java).get()

    private fun function(name: String): Long = graphQlTester.document(
        """mutation { createFunction(input: { workspaceId: $workspaceId, name: "$name" }) { id } }""",
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    private fun deleteAgent(id: Long) = del("deleteAgent", id)
    private fun deleteTool(id: Long) = del("deleteTool", id)
    private fun deleteFunction(id: Long) = del("deleteFunction", id)
    private fun deleteSkill(id: Long) = del("deleteSkill", id)
    private fun deleteDocument(id: Long) = del("deleteDocument", id)
    private fun deleteDiagram(id: Long) = del("deleteDiagram", id)

    private fun del(mutation: String, id: Long) {
        graphQlTester.document("""mutation { $mutation(id: $id) }""")
            .execute().path(mutation).entity(Boolean::class.java).isEqualTo(true)
    }

    private fun restore(entryId: Long) {
        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: $entryId) }""")
            .execute().path("restoreRecycleBinEntry").entity(Boolean::class.java).isEqualTo(true)
    }

    private companion object {
        val counter = AtomicLong(System.nanoTime())
    }
}
