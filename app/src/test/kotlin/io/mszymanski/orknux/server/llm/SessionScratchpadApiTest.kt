package io.mszymanski.orknux.server.llm

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

/**
 * The session page's half of scratchpads: list them, open one, edit it, make a
 * new one and delete it, all through the GraphQL a person's browser calls.
 * Issue #429.
 *
 * The service's own rules are pinned in [ScratchpadTest]; what is pinned here is
 * that the resolver reaches that service, answers the view shapes the page reads,
 * and turns a [ScratchpadResult.No] into an error a screen can show rather than a
 * correlation id. Makes its own workspace and session each time.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionScratchpadApiTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val pads: SessionScratchpadService,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var session: Long = 0

    @BeforeEach
    fun make() {
        val workspaceId = requireNotNull(
            (workspaces.findByName("pad-api") ?: workspaces.save(Workspace(name = "pad-api"))).id,
        )
        session = recorder.open(workspaceId, "test", "pad-api-${System.nanoTime()}")
    }

    @Test
    fun `the session's scratchpads are listed by name, sized, without content`() {
        // A fresh session with no ancestors: this is the only pad it can see.
        pads.create(session, "plan.md", "The rollout plan", "one\ntwo\n")

        graphQlTester
            .document("query { sessionScratchpads(sessionId: $session) { name description bytes shared ownedHere } }")
            .execute()
            .path("sessionScratchpads[0].name").entity(String::class.java).isEqualTo("plan.md")
            .path("sessionScratchpads[0].description").entity(String::class.java).isEqualTo("The rollout plan")
            .path("sessionScratchpads[0].bytes").entity(Int::class.java).isEqualTo("one\ntwo\n".toByteArray().size)
            .path("sessionScratchpads[0].ownedHere").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `one scratchpad is opened with its content, and a missing one is null`() {
        pads.create(session, "notes", null, "the body")

        graphQlTester
            .document("query { sessionScratchpad(sessionId: $session, name: \"notes\") { name content } }")
            .execute()
            .path("sessionScratchpad.content").entity(String::class.java).isEqualTo("the body")

        graphQlTester
            .document("query { sessionScratchpad(sessionId: $session, name: \"absent\") { name } }")
            .execute()
            .path("sessionScratchpad").valueIsNull()
    }

    @Test
    fun `a new scratchpad is created from the page`() {
        graphQlTester
            .document(
                "mutation { createSessionScratchpad(sessionId: $session, name: \"draft\", " +
                    "description: \"A draft\", content: \"hello\") { name content bytes } }",
            )
            .execute()
            .path("createSessionScratchpad.content").entity(String::class.java).isEqualTo("hello")

        assertThat(requireNotNull(pads.find(session, "draft")).content).isEqualTo("hello")
    }

    @Test
    fun `a name already taken is refused in words`() {
        pads.create(session, "taken", null, "one")

        graphQlTester
            .document(
                "mutation { createSessionScratchpad(sessionId: $session, name: \"taken\", content: \"two\") { name } }",
            )
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("already has a scratchpad")
                })
            }
    }

    @Test
    fun `a scratchpad's content is edited and read back`() {
        pads.create(session, "edit-me", null, "before")

        graphQlTester
            .document(
                "mutation { writeSessionScratchpad(sessionId: $session, name: \"edit-me\", content: \"after\") " +
                    "{ name content bytes } }",
            )
            .execute()
            .path("writeSessionScratchpad.content").entity(String::class.java).isEqualTo("after")

        assertThat(requireNotNull(pads.find(session, "edit-me")).content).isEqualTo("after")
    }

    @Test
    fun `a scratchpad is deleted from the page`() {
        pads.create(session, "temp", null, "throwaway")

        graphQlTester
            .document("mutation { deleteSessionScratchpad(sessionId: $session, name: \"temp\") }")
            .execute()
            .path("deleteSessionScratchpad").entity(Boolean::class.java).isEqualTo(true)

        assertThat(pads.find(session, "temp")).isNull()
    }

    @Test
    fun `deleting one this session does not own is refused in words`() {
        pads.create(session, "shared.md", null, "body")
        pads.share(session, "shared.md", true)
        val child = recorder.openUnder(session, "A task")

        graphQlTester
            .document("mutation { deleteSessionScratchpad(sessionId: $child, name: \"shared.md\") }")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("of its own")
                })
            }

        assertThat(pads.find(session, "shared.md")).isNotNull()
    }
}
