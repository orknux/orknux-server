package io.mszymanski.orknux.server.workspace

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Whether a workspace's chat timers sound when they end: on until the
 * workspace turns it off, and back on again. What one timer does when the
 * agent chose for it is the widget's, in WidgetToolsTest.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceWidgetTimerSoundTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "timer-sound-${System.nanoTime()}")).id)
    }

    @Test
    fun `a workspace's timers sound until it turns that off`() {
        graphQlTester.document("""query { workspace(id: $workspaceId) { widgetTimerSound } }""").execute()
            .path("workspace.widgetTimerSound").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document(
            """mutation { setWorkspaceWidgetTimerSound(workspaceId: $workspaceId, sound: false) { widgetTimerSound } }""",
        ).execute()
            .path("setWorkspaceWidgetTimerSound.widgetTimerSound").entity(Boolean::class.java).isEqualTo(false)
        assertThat(workspaces.findById(workspaceId).get().widgetTimerSound).isFalse()

        graphQlTester.document(
            """mutation { setWorkspaceWidgetTimerSound(workspaceId: $workspaceId, sound: true) { widgetTimerSound } }""",
        ).execute()
            .path("setWorkspaceWidgetTimerSound.widgetTimerSound").entity(Boolean::class.java).isEqualTo(true)
    }
}
