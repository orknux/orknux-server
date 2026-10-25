package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser

/**
 * Admin Settings' Largest file: one upload limit, held in the database over
 * ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB, ranged, audited, and what a chat's
 * upload is refused by - in a sentence naming the file's size, the limit and
 * where it is raised. The HTTP half, through a real Tomcat, is
 * [UploadLimitHttpTest].
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-upload-limit"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class UploadLimitTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationSettings,
    @Autowired val held: InstallationSettingRepository,
    @Autowired val uploads: AttachmentAPI,
    @Autowired val attachments: ChatAttachmentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        forget()
        attachments.deleteAll()
        audit.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "upload-limit-${System.nanoTime()}")).id)
    }

    /** The row shared by every test in the run, gone again so the next class sees the configured default. */
    @AfterEach
    fun forget() {
        held.findAll().filter { it.name == SettingNames.UPLOAD_MAX_MB }.forEach(held::delete)
    }

    @Test
    fun `the limit starts at the configured 25 MB, and what an administrator sets wins over it`() {
        graphQlTester.document("{ installationSettings { attachmentMaxFileSizeMb attachmentMaxFileSizeMbConfigured } }")
            .execute()
            .path("installationSettings.attachmentMaxFileSizeMb").entity(Int::class.java).isEqualTo(25)
            .path("installationSettings.attachmentMaxFileSizeMbConfigured").entity(Int::class.java).isEqualTo(25)

        graphQlTester.document("mutation { setAttachmentMaxFileSizeMb(megabytes: 500) { attachmentMaxFileSizeMb attachmentMaxFileSizeMbConfigured } }")
            .execute()
            .path("setAttachmentMaxFileSizeMb.attachmentMaxFileSizeMb").entity(Int::class.java).isEqualTo(500)
            .path("setAttachmentMaxFileSizeMb.attachmentMaxFileSizeMbConfigured").entity(Int::class.java).isEqualTo(25)
        assertThat(settings.maxFileSizeMb()).isEqualTo(500)
        assertThat(audit.findAll().map { it.message }).contains("Upload limit set to 500 MB")
    }

    @Test
    fun `the limit is 1 MB or more, with no ceiling, and below that is refused with a code`() {
        listOf(0, -5).forEach { megabytes ->
            graphQlTester.document("mutation { setAttachmentMaxFileSizeMb(megabytes: $megabytes) { attachmentMaxFileSizeMb } }")
                .execute().errors().satisfy { errors ->
                    assertThat(errors).hasSize(1)
                    assertThat(errors.single().message).isEqualTo("Uploads cannot be limited to $megabytes MB. Choose 1 MB or more.")
                    assertThat(errors.single().extensions["code"]).isEqualTo("UploadMaxOutOfRange")
                    assertThat(errors.single().extensions["arguments"]).isEqualTo(mapOf("megabytes" to megabytes))
                }
        }
        assertThat(settings.maxFileSizeMb()).isEqualTo(25)

        listOf(1, 2048, 100_000).forEach { megabytes ->
            graphQlTester.document("mutation { setAttachmentMaxFileSizeMb(megabytes: $megabytes) { attachmentMaxFileSizeMb } }")
                .execute().path("setAttachmentMaxFileSizeMb.attachmentMaxFileSizeMb").entity(Int::class.java).isEqualTo(megabytes)
        }
    }

    @Test
    fun `a chat file over the limit is refused by its size and the limit, and one under it is kept`() {
        settings.setMaxFileSizeMb(1, "alice")
        val over = MockMultipartFile("files", "long.mov", "video/quicktime", ByteArray(1024 * 1024 + 1))

        val refused = assertThrows<AttachmentTooLargeException> { uploads.upload(workspaceId, listOf(over)) }

        assertThat(refused.message).isEqualTo(
            "long.mov is 2 MB; uploads are limited to 1 MB - an administrator can raise it in Admin Settings → Attachments",
        )
        assertThat(refused.arguments).isEqualTo(mapOf("name" to "long.mov", "sizeMb" to 2L, "limitMb" to 1L))
        assertThat(attachments.findAll()).isEmpty()

        // Exactly the limit is not over it.
        val under = MockMultipartFile("files", "short.mov", "video/quicktime", ByteArray(1024 * 1024))
        assertThat(uploads.upload(workspaceId, listOf(under)).statusCode.value()).isEqualTo(200)
        assertThat(attachments.findAll().map { it.filename to it.sizeBytes }).containsExactly("short.mov" to 1024L * 1024)
    }

    @Test
    fun `one file over the limit among several keeps none of them`() {
        settings.setMaxFileSizeMb(1, "alice")
        val small = MockMultipartFile("files", "notes.txt", "text/plain", "x".toByteArray())
        val big = MockMultipartFile("files", "dump.bin", "application/octet-stream", ByteArray(2 * 1024 * 1024))

        assertThrows<AttachmentTooLargeException> { uploads.upload(workspaceId, listOf(small, big)) }

        assertThat(attachments.findAll()).isEmpty()
    }
}
