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
 * What a session's model is writing this second - a call being spelled out, an
 * answer half written - is in no line of the transcript until it lands. A model
 * putting a file into one call wrote for minutes with the session page showing
 * nothing, so the page asks for it, and it is there while the round writes and
 * gone the moment the round has said what it wrote.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionWritingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var session = 0L

    @BeforeEach
    fun open() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "writing-" + System.nanoTime())).id)
        session = recorder.open(workspaceId, "test", System.nanoTime().toString())
    }

    private fun writing() = graphQlTester
        .document("{ llmSessionWriting(id: $session) { agent tool chars since } }")
        .execute()

    @Test
    fun `a call being written is there while the round writes it, and gone once the call is made`() {
        val watch = SessionThinking(session, "Builder", recorder)
        writing().path("llmSessionWriting").valueIsNull()

        watch.writing("orkbox_write", 1200)
        watch.writing("orkbox_write", 5400)
        writing().also {
            it.path("llmSessionWriting.agent").entity(String::class.java).isEqualTo("Builder")
            it.path("llmSessionWriting.tool").entity(String::class.java).isEqualTo("orkbox_write")
            it.path("llmSessionWriting.chars").entity(Int::class.java).isEqualTo(5400)
            it.path("llmSessionWriting.since").hasValue()
        }

        watch.called(0, "orkbox_write", "{}")
        writing().path("llmSessionWriting").valueIsNull()
    }

    @Test
    fun `an answer being written says so with no tool, and the end of the turn clears it`() {
        val watch = SessionThinking(session, "Builder", recorder)
        watch.writing(null, 300)
        writing().also {
            it.path("llmSessionWriting.tool").valueIsNull()
            it.path("llmSessionWriting.chars").entity(Int::class.java).isEqualTo(300)
        }

        watch.settle()
        writing().path("llmSessionWriting").valueIsNull()
    }
}
