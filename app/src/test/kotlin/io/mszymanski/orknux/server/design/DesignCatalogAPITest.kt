package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.workflow.ExecutionPicture
import io.mszymanski.orknux.server.workflow.ExecutionPictureRepository
import io.mszymanski.orknux.server.workflow.Workflow
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Catalogs for Design's images, wireframes and videos.
 *
 * Pinned: a fresh workspace has a default catalog of each kind without anybody
 * making one, which cannot be deleted; a catalog is made, renamed and deleted
 * through one door for the three kinds, each audited, and refused while it holds
 * anything; things are filed in the catalog asked for or the default, listed by
 * catalog, and moved between them - a wireframe, a video with its versions, and
 * pictures whatever drew them; a catalog is this workspace's and this kind's or
 * it is not found; and somebody who cannot see the workspace sees none of it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DesignCatalogAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val videos: DesignVideoRepository,
    @Autowired val pictures: ExecutionPictureRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val tools: OrknuxTools,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "catalogs ${System.nanoTime()}")).id)
    }

    @Test
    fun `a fresh workspace has a default catalog of each kind, and it cannot be deleted`() {
        listOf("IMAGE", "WIREFRAME", "VIDEO").forEach { kind ->
            val listed = catalogs(kind)
            assertThat(listed.map { it.name }).describedAs(kind).containsExactly("General")
            assertThat(listed.single().isDefault).isTrue()
            // Asked again, the same one: it is made once.
            assertThat(catalogs(kind).single().id).isEqualTo(listed.single().id)
            graphQlTester.document("""mutation { deleteDesignCatalog(workspaceId: $workspaceId, kind: $kind, id: ${listed.single().id}) }""")
                .execute().errors().satisfy { errors ->
                    assertThat(errors.single().extensions["code"]).isEqualTo("DesignCatalogDefault")
                }
        }
    }

    @Test
    fun `a catalog is made, renamed and deleted, each audited, and refused while it holds anything`() {
        val holiday = create("WIREFRAME", "Holiday")
        graphQlTester.document("""mutation { createDesignCatalog(workspaceId: $workspaceId, kind: WIREFRAME, name: "Holiday") { id } }""")
            .execute().errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("DesignCatalogNameTaken") }
        // The same name in another kind is another catalog.
        create("IMAGE", "Holiday")
        graphQlTester.document("""mutation { createDesignCatalog(workspaceId: $workspaceId, kind: VIDEO, name: "   ") { id } }""")
            .execute().errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("DesignCatalogNameInvalid") }

        graphQlTester.document("""mutation { renameDesignCatalog(workspaceId: $workspaceId, kind: WIREFRAME, id: $holiday, name: "Trips") { name } }""")
            .execute().path("renameDesignCatalog.name").entity(String::class.java).isEqualTo("Trips")

        val wireframe = wireframe("Beach", holiday)
        graphQlTester.document("""mutation { deleteDesignCatalog(workspaceId: $workspaceId, kind: WIREFRAME, id: $holiday) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().extensions["code"]).isEqualTo("DesignCatalogNotEmpty")
                assertThat(errors.single().message).isEqualTo("Trips holds 1 wireframes; move or delete them before deleting it")
            }

        graphQlTester.document("""mutation { deleteWireframe(id: $wireframe) }""").execute()
        graphQlTester.document("""mutation { deleteDesignCatalog(workspaceId: $workspaceId, kind: WIREFRAME, id: $holiday) }""")
            .execute().path("deleteDesignCatalog").entity(Boolean::class.java).isEqualTo(true)
        assertThat(catalogs("WIREFRAME").map { it.name }).containsExactly("General")

        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message }).contains(
            "Wireframe catalog Holiday added",
            "Image catalog Holiday added",
            "Wireframe catalog Holiday renamed to Trips",
            "Wireframe catalog Trips removed",
        )
    }

    @Test
    fun `wireframes are filed where asked or in the default, listed by catalog, and moved`() {
        val general = catalogs("WIREFRAME").single().id
        val drafts = create("WIREFRAME", "Drafts")
        val loose = wireframe("Loose", null)
        val filed = wireframe("Filed", drafts)

        graphQlTester.document("""{ wireframes(workspaceId: $workspaceId, catalogId: $drafts) { name catalogName } }""")
            .execute().path("wireframes[*].name").entityList(String::class.java).containsExactly("Filed")
        graphQlTester.document("""{ wireframes(workspaceId: $workspaceId, catalogId: $general) { name catalogId } }""")
            .execute().path("wireframes[*].name").entityList(String::class.java).containsExactly("Loose")
        graphQlTester.document("""{ wireframes(workspaceId: $workspaceId) { name } }""")
            .execute().path("wireframes[*].name").entityList(String::class.java).containsExactly("Filed", "Loose")

        graphQlTester.document("""mutation { moveWireframe(id: $loose, catalogId: $drafts) { catalogId catalogName } }""")
            .execute().path("moveWireframe.catalogName").entity(String::class.java).isEqualTo("Drafts")
        assertThat(catalogs("WIREFRAME").associate { it.name to it.count }).isEqualTo(mapOf("General" to 0, "Drafts" to 2))
        assertThat(audit.findAll().map { it.message }).contains("Wireframe Loose moved to Drafts")
        assertThat(filed).isNotEqualTo(loose)

        // Another workspace's catalog is not one a wireframe here can be filed in.
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "elsewhere ${System.nanoTime()}")).id)
        val theirs = graphQlTester.document("""{ designCatalogs(workspaceId: $elsewhere, kind: WIREFRAME) { id } }""")
            .execute().path("designCatalogs[0].id").entity(String::class.java).get()
        graphQlTester.document("""mutation { moveWireframe(id: $loose, catalogId: $theirs) { name } }""")
            .execute().errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("DesignCatalogNotFound") }
    }

    /** An agent lists wireframes by catalog and files a new one, making the catalog it names. */
    @Test
    fun `the wireframe tools name each one's catalog, narrow to one, and file a new one`() {
        SecurityContextHolder.clearContext()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Sketcher")
        fun call(name: String, arguments: String) = mapper.readTree(tools.run(scope, name, arguments))

        val made = call("orknux_create_wireframe", """{"name":"Login","catalog":"Mobile"}""")
        assertThat(made.has("error")).describedAs(made.toString()).isFalse()
        call("orknux_create_wireframe", """{"name":"Home"}""")

        val mobile = call("orknux_wireframes", """{"catalog":"mobile"}""").path("wireframes")
        assertThat(mobile.size()).isEqualTo(1)
        assertThat(mobile.get(0).path("name").stringValue()).isEqualTo("Login")
        assertThat(mobile.get(0).path("catalog").stringValue()).isEqualTo("Mobile")
        val all = call("orknux_wireframes", "{}").path("wireframes")
        assertThat((0 until all.size()).map { all.get(it).path("catalog").stringValue() }).containsExactlyInAnyOrder("Mobile", "General")
        assertThat(call("orknux_wireframes", """{"catalog":"Nowhere"}""").path("error").stringValue()).contains("no wireframe catalog called Nowhere")
    }

    @Test
    fun `a video is filed where it is moved, with the versions made of it`() {
        val general = catalogs("VIDEO").single().id
        val clips = create("VIDEO", "Clips")
        val original = videos.save(DesignVideo(workspaceId = workspaceId, modelId = null, modelName = "m", prompt = "waves", name = "Waves", requestedBy = "alice"))
        val larger = videos.save(
            DesignVideo(
                workspaceId = workspaceId, modelId = null, modelName = "m", prompt = "waves", name = "Waves", requestedBy = "alice",
                versionOf = original.id, upscaledFrom = original.id,
            ),
        )
        graphQlTester.document("""{ designVideos(workspaceId: $workspaceId, catalogId: $general) { id catalogName } }""")
            .execute().path("designVideos[*].id").entityList(String::class.java).hasSize(2)

        graphQlTester.document("""mutation { moveDesignVideo(workspaceId: $workspaceId, id: ${larger.id}, catalogId: $clips) { catalogName } }""")
            .execute().path("moveDesignVideo.catalogName").entity(String::class.java).isEqualTo("Clips")
        graphQlTester.document("""{ designVideos(workspaceId: $workspaceId, catalogId: $clips) { id } }""")
            .execute().path("designVideos[*].id").entityList(String::class.java).get()
            .let { assertThat(it).containsExactlyInAnyOrder(original.id.toString(), larger.id.toString()) }
        // One video, counted once: its larger version is not a video of its own.
        assertThat(catalogs("VIDEO").associate { it.name to it.count }).isEqualTo(mapOf("General" to 0, "Clips" to 1))
    }

    @Test
    fun `pictures are filed where imported, listed by catalog, and moved whatever drew them`() {
        val general = catalogs("IMAGE").single().id
        val trips = create("IMAGE", "Trips")
        val imported = graphQlTester.document(
            """mutation { importDesignImage(input: { workspaceId: $workspaceId, image: "$PNG", name: "Red", catalogId: $trips }) { id } }""",
        ).execute().path("importDesignImage.id").entity(String::class.java).get()
        val run = ranPicture("sunset")

        fun listed(catalogId: String) = graphQlTester.document(
            """{ workspaceArtifacts(workspaceId: $workspaceId, drawn: true, catalogId: $catalogId) { content { id catalogName } } }""",
        ).execute().path("workspaceArtifacts.content[*].id").entityList(String::class.java)

        listed(trips).containsExactly(imported)
        listed(general).containsExactly("IMAGE-${run.id}")
        graphQlTester.document("""{ workspaceArtifacts(workspaceId: $workspaceId, drawn: true) { content { id catalogName } } }""")
            .execute().path("workspaceArtifacts.content[*].catalogName").entityList(String::class.java).get()
            .let { assertThat(it).containsExactlyInAnyOrder("Trips", "General") }

        // A run's picture, filed like one drawn here.
        graphQlTester.document("""mutation { moveDesignImage(workspaceId: $workspaceId, id: "IMAGE-${run.id}", catalogId: $trips) { catalogName } }""")
            .execute().path("moveDesignImage.catalogName").entity(String::class.java).isEqualTo("Trips")
        listed(trips).get().let { assertThat(it).containsExactlyInAnyOrder(imported, "IMAGE-${run.id}") }
        assertThat(catalogs("IMAGE").associate { it.name to it.count }).isEqualTo(mapOf("General" to 0, "Trips" to 2))
        assertThat(audit.findAll().map { it.message }).contains("Image sunset.png moved to Trips")

        // The Artifacts page asks without drawn, and is told nothing of catalogs.
        graphQlTester.document("""{ workspaceArtifacts(workspaceId: $workspaceId) { content { catalogId } } }""")
            .execute().path("workspaceArtifacts.content[*]").entityList(Map::class.java).get()
            .let { content -> assertThat(content.map { it["catalogId"] }).isNotEmpty().containsOnlyNulls() }
    }

    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `somebody who cannot see the workspace sees none of its catalogs and makes none`() {
        graphQlTester.document("""{ designCatalogs(workspaceId: $workspaceId, kind: IMAGE) { name } }""")
            .execute().errors().expect { true }.verify()
        graphQlTester.document("""mutation { createDesignCatalog(workspaceId: $workspaceId, kind: VIDEO, name: "Mine") { id } }""")
            .execute().errors().expect { true }.verify()
    }

    private data class Listed(val id: String, val name: String, val isDefault: Boolean, val count: Int)

    private fun catalogs(kind: String): List<Listed> {
        val read = graphQlTester.document("""{ designCatalogs(workspaceId: $workspaceId, kind: $kind) { id name isDefault count } }""")
            .execute().path("designCatalogs").entity(Any::class.java).get()
        val list = mapper.convertValue(read, JsonNode::class.java)
        return (0 until list.size()).map { at ->
            val it = list.get(at)
            Listed(it.path("id").asText(), it.path("name").asText(), it.path("isDefault").asBoolean(), it.path("count").asInt())
        }
    }

    private fun create(kind: String, name: String): String =
        graphQlTester.document("""mutation { createDesignCatalog(workspaceId: $workspaceId, kind: $kind, name: "$name") { id } }""")
            .execute().path("createDesignCatalog.id").entity(String::class.java).get()

    private fun wireframe(name: String, catalogId: String?): String =
        graphQlTester.document(
            """mutation { createWireframe(workspaceId: $workspaceId, name: "$name"${catalogId?.let { ", catalogId: $it" } ?: ""}) { id } }""",
        ).execute().path("createWireframe.id").entity(String::class.java).get()

    /** A picture a workflow's image node drew, which needs a run to hang off. */
    private fun ranPicture(prompt: String): ExecutionPicture {
        val workflow = workflows.save(Workflow(name = "Draws ${System.nanoTime()}"))
        val run = executions.save(
            WorkflowExecution(
                workspaceId = workspaceId,
                workflowId = requireNotNull(workflow.id),
                workflowName = workflow.name,
                status = ExecutionStatus.COMPLETED,
                trigger = ExecutionTrigger.MANUAL,
                startedAt = OffsetDateTime.now(),
            ),
        )
        return pictures.save(
            ExecutionPicture(
                executionId = requireNotNull(run.id),
                nodeKey = "draw",
                workspaceId = workspaceId,
                prompt = prompt,
                filename = "$prompt.png",
                contentType = "image/png",
                sizeBytes = 3,
                location = store.put(workspaceId, "$prompt.png", byteArrayOf(1, 2, 3)),
            ),
        )
    }

    private companion object {
        /** A 1x1 PNG, which is all an import needs to keep a picture. */
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
