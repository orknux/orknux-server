package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * What a workspace's runs have produced, listed and deleted.
 *
 * The pictures were reachable from the run that drew them and nowhere else,
 * which is no way to find one afterwards and no way at all to see what is
 * filling the disk. These are the two halves of the page that fixes that.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceArtifactAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val pictures: ExecutionPictureRepository,
    @Autowired val saved: SavedArtifacts,
    @Autowired val savedRows: SavedArtifactRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val store: AttachmentStore,
) {

    private var workspaceId: Long = 0
    private var otherWorkspaceId: Long = 0

    /** A run for the pictures to hang off; the row has a foreign key to one. */
    private var executionId: Long = 0
    private var otherExecutionId: Long = 0

    @BeforeEach
    fun seed() {
        pictures.deleteAll()
        savedRows.deleteAll()
        executions.deleteAll()
        workflows.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        otherWorkspaceId = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        executionId = ran(workspaceId)
        otherExecutionId = ran(otherWorkspaceId)
    }

    /** A finished run, which is all a picture needs of one. */
    private fun ran(workspace: Long): Long {
        val workflow = workflows.save(Workflow(name = "Nightly $workspace"))
        return requireNotNull(
            executions.save(
                WorkflowExecution(
                    workspaceId = workspace,
                    workflowId = requireNotNull(workflow.id),
                    workflowName = workflow.name,
                    status = ExecutionStatus.COMPLETED,
                    trigger = ExecutionTrigger.MANUAL,
                    startedAt = OffsetDateTime.now(),
                ),
            ).id,
        )
    }

    /** A drawn picture, filed the way an image node files one. */
    private fun drawn(
        prompt: String,
        workspace: Long = workspaceId,
        at: OffsetDateTime = OffsetDateTime.now(),
    ): ExecutionPicture {
        val location = store.put(workspace, "$prompt.png", byteArrayOf(1, 2, 3))
        return pictures.save(
            ExecutionPicture(
                executionId = if (workspace == workspaceId) executionId else otherExecutionId,
                nodeKey = "draw",
                workspaceId = workspace,
                prompt = prompt,
                filename = "$prompt.png",
                contentType = "image/png",
                sizeBytes = 3,
                location = location,
                drawnAt = at,
            ),
        )
    }

    @Test
    fun `it lists what this workspace drew, newest first`() {
        val now = OffsetDateTime.now()
        drawn("older", at = now.minusHours(2))
        drawn("newer", at = now)
        // Another workspace's, which this one may not see.
        drawn("theirs", workspace = otherWorkspaceId, at = now)

        graphQlTester.document(
            """query { workspaceArtifacts(workspaceId: $workspaceId) {
                 totalElements content { id prompt kind filename sizeBytes url } } }""",
        ).execute()
            .path("workspaceArtifacts.totalElements").entity(Int::class.java).isEqualTo(2)
            .path("workspaceArtifacts.content[*].prompt").entityList(String::class.java)
            .containsExactly("newer", "older")
            .path("workspaceArtifacts.content[0].kind").entity(String::class.java).isEqualTo("IMAGE")
            .path("workspaceArtifacts.content[0].url").entity(String::class.java)
            .satisfies { assertThat(it).startsWith("/api/execution-pictures/") }
            // The id says which table it came from: both number their rows
            // separately, so a bare 7 could not say which was meant.
            .path("workspaceArtifacts.content[0].id").entity(String::class.java)
            .satisfies { assertThat(it).startsWith("IMAGE-") }
    }

    /**
     * The row goes and the file goes with it.
     *
     * A row deleted on its own leaves bytes nobody can reach and nobody is
     * counting - which is the whole reason somebody came to this page.
     */
    @Test
    fun `deleting an artifact takes its file as well`() {
        val picture = drawn("a hen in a hat")
        assertThat(store.exists(picture.location)).isTrue()

        graphQlTester.document(
            """mutation { deleteArtifact(id: "IMAGE-${picture.id}") }""",
        ).execute().path("deleteArtifact").entity(Boolean::class.java).isEqualTo(true)

        assertThat(pictures.findById(requireNotNull(picture.id))).isEmpty
        assertThat(store.exists(picture.location)).isFalse()
    }

    /**
     * A file an agent saved is an artifact too, and deletes the same way.
     *
     * The third source on this page and the first that nobody drew: there is no
     * run and no task behind it, which is why it names its agent and links
     * nowhere.
     */
    @Test
    fun `a file an agent saved is listed beside the drawn ones, and can be deleted`() {
        drawn("drawn")
        val filed = saved.save(
            workspaceId = workspaceId,
            savedBy = "Support responder",
            name = "flow.svg",
            description = "The login flow, as a diagram.",
            content = "<svg/>",
            base64 = false,
        )
        val artifact = (filed as SavedArtifacts.Saving.Saved).artifact

        graphQlTester.document(
            """query { workspaceArtifacts(workspaceId: $workspaceId) {
                 totalElements content { id kind source sourcePath filename prompt } } }""",
        ).execute()
            .path("workspaceArtifacts.totalElements").entity(Int::class.java).isEqualTo(2)
            // Newest first, and it was saved after the picture was drawn.
            .path("workspaceArtifacts.content[0].kind").entity(String::class.java).isEqualTo("SAVED")
            .path("workspaceArtifacts.content[0].filename").entity(String::class.java).isEqualTo("flow.svg")
            // Its agent, named and not linked: there is nothing to open.
            .path("workspaceArtifacts.content[0].source").entity(String::class.java).isEqualTo("Support responder")
            .path("workspaceArtifacts.content[0].sourcePath").entity(String::class.java).isEqualTo("")
            // The description is the caption, the way a picture's prompt is.
            .path("workspaceArtifacts.content[0].prompt").entity(String::class.java)
            .isEqualTo("The login flow, as a diagram.")

        // What a model drew, and only that: Design's Images page.
        graphQlTester.document(
            """query { workspaceArtifacts(workspaceId: $workspaceId, drawn: true) {
                 totalElements content { kind } } }""",
        ).execute()
            .path("workspaceArtifacts.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("workspaceArtifacts.content[0].kind").entity(String::class.java).isEqualTo("IMAGE")

        graphQlTester.document(
            """mutation { deleteArtifact(id: "SAVED-${artifact.id}") }""",
        ).execute().path("deleteArtifact").entity(Boolean::class.java).isEqualTo(true)

        assertThat(savedRows.count()).isZero()
        assertThat(store.exists(artifact.location)).isFalse()
    }

    /** Nothing to delete is false, not an error: the caller's wish is already true. */
    @Test
    fun `deleting an artifact that is not there answers false`() {
        graphQlTester.document(
            """mutation { deleteArtifact(id: "IMAGE-999999") }""",
        ).execute().path("deleteArtifact").entity(Boolean::class.java).isEqualTo(false)
    }
}
