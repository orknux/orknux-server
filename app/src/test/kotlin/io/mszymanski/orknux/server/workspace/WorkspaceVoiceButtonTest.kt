package io.mszymanski.orknux.server.workspace

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/** A workspace may hide the voice mode button in its chats; it is shown until somebody chooses otherwise. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceVoiceButtonTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
) {

    @Test
    fun `the voice button is shown by default and hidden when the workspace says so`() {
        val id = requireNotNull(workspaces.save(Workspace(name = "voice-button-${System.nanoTime()}")).id)

        graphQlTester.document("{ workspace(id: $id) { voiceButtonHidden } }").execute()
            .path("workspace.voiceButtonHidden").entity(Boolean::class.java).isEqualTo(false)

        graphQlTester.document("mutation { setWorkspaceVoiceButtonHidden(workspaceId: $id, hidden: true) { voiceButtonHidden } }").execute()
            .path("setWorkspaceVoiceButtonHidden.voiceButtonHidden").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("{ workspace(id: $id) { voiceButtonHidden } }").execute()
            .path("workspace.voiceButtonHidden").entity(Boolean::class.java).isEqualTo(true)
    }
}
