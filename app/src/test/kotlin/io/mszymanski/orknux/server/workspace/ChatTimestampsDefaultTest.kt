package io.mszymanski.orknux.server.workspace

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/** A chat shows when each message was sent unless its workspace turns that off. Issue #323. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatTimestampsDefaultTest(@Autowired val graphQlTester: ExecutionGraphQlServiceTester) {

    @Test
    fun `a new workspace shows message times, and can stop`() {
        val id = graphQlTester.document(
            """mutation { createWorkspace(input: { name: "times ${System.nanoTime()}" }) { id chatShowTimestamps } }""",
        ).execute()
            .path("createWorkspace.chatShowTimestamps").entity(Boolean::class.java).isEqualTo(true)
            .path("createWorkspace.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { setWorkspaceChatTimestamps(workspaceId: $id, shown: false) { chatShowTimestamps } }""")
            .execute().path("setWorkspaceChatTimestamps.chatShowTimestamps").entity(Boolean::class.java).isEqualTo(false)
    }
}
