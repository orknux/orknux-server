package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.Workspace
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

/**
 * An installation that will not let a conversation be thrown away.
 *
 * A session is the record of what an agent was asked and what it answered, and
 * on some installations that is the only account of a decision anybody has.
 * Removing one is a person tidying up after a mistyped key or a run they were
 * trying out - which is what it is for - but where the record has to stand it is
 * a hole somebody can put in it with one press and no way back.
 *
 * So an operator can close the door. What is pinned here is that closing it
 * actually closes it - the mutation is the boundary, not the screen - and that
 * it says so rather than answering false, which already means something else.
 *
 * Leaves the installation on the setting it found.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionRemovalTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationSettings,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var was: Boolean = true

    @BeforeEach
    fun make() {
        was = settings.sessionsRemovable()
        workspaceId = requireNotNull(
            (workspaces.findByName("removing") ?: workspaces.save(Workspace(name = "removing"))).id,
        )
    }

    @AfterEach
    fun restore() {
        settings.setSessionsRemovable(was, "alice")
    }

    private fun session(): Long = recorder.open(workspaceId, "test", "removal-${System.nanoTime()}")

    @Test
    fun `an installation allows it until somebody says otherwise`() {
        // Which is how this has always worked: a switch that silently took an
        // ability away on upgrade would be worse than the hole it closes.
        settings.setSessionsRemovable(true, "alice")
        assertThat(settings.sessionsRemovable()).isTrue()
    }

    @Test
    fun `a conversation can be thrown away while the door is open`() {
        settings.setSessionsRemovable(true, "alice")
        val id = session()

        graphQlTester.document("mutation { removeLlmSession(id: $id) }")
            .execute().path("removeLlmSession").entity(Boolean::class.java).isEqualTo(true)

        assertThat(sessions.findById(id)).isEmpty()
    }

    /**
     * The mutation is the boundary. The screen leaves the control out, but the
     * same call is reachable from the API and a form is not a boundary.
     */
    @Test
    fun `closing the door refuses the removal and keeps the conversation`() {
        settings.setSessionsRemovable(false, "alice")
        val id = session()

        graphQlTester.document("mutation { removeLlmSession(id: $id) }")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("does not allow conversations to be removed")
                    // And where to change it, because the next thing somebody
                    // does is look for the setting.
                    assertThat(it.message).contains("Admin")
                })
            }

        assertThat(sessions.findById(id)).isPresent()
    }

    /**
     * In words rather than a false, which the mutation already uses to mean
     * "there was no such session": somebody who may not do this needs to be told
     * that rather than left believing the conversation had already gone.
     */
    @Test
    fun `a session that is not there is still answered false while the door is open`() {
        settings.setSessionsRemovable(true, "alice")

        graphQlTester.document("mutation { removeLlmSession(id: 987654321) }")
            .execute().path("removeLlmSession").entity(Boolean::class.java).isEqualTo(false)
    }

    /**
     * Asked before the session is looked up, so a closed installation cannot be
     * used to find out which ids exist.
     */
    @Test
    fun `a closed installation says the same about an id that does not exist`() {
        settings.setSessionsRemovable(false, "alice")

        graphQlTester.document("mutation { removeLlmSession(id: 987654321) }")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("does not allow conversations to be removed")
                })
            }
    }

    @Test
    fun `the screen sets it and reads it back`() {
        graphQlTester.document("mutation { setSessionsRemovable(removable: false) { sessionsRemovable } }")
            .execute().path("setSessionsRemovable.sessionsRemovable").entity(Boolean::class.java).isEqualTo(false)

        assertThat(settings.sessionsRemovable()).isFalse()

        graphQlTester.document("mutation { setSessionsRemovable(removable: true) { sessionsRemovable } }")
            .execute().path("setSessionsRemovable.sessionsRemovable").entity(Boolean::class.java).isEqualTo(true)
    }
}
