package io.mszymanski.orknux.server.chat

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
import tools.jackson.databind.ObjectMapper

/**
 * What can be typed in a chat instead of said.
 *
 * Issue #343. A chat is for asking an agent, and some of what people want from
 * this product is not a question: start that workflow, file that as an issue.
 * Doing either meant leaving the conversation, finding the page and coming back
 * - and the conversation is usually where the reason lives, so what came back
 * was somebody retyping what they had just written.
 *
 * The catalogue is the server's rather than the interface's, because the chat is
 * not the only place people type: Slack's own slash commands arrive here with
 * nothing of the browser about them. That is what is pinned here - the commands
 * are answered over the API, they are carried out by the same code the agents
 * reach, and what they refuse they refuse in words somebody can act on.
 *
 * Makes its own workspace and leaves it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatCommandsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val commands: ChatCommands,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(
            (workspaces.findByName("commands") ?: workspaces.save(Workspace(name = "commands"))).id,
        )
    }

    private fun error(said: String): String? =
        mapper.readTree(said).path("error").takeIf { it.isTextual }?.stringValue()

    /* ------------------------------------------------- what is on offer ---- */

    @Test
    fun `the commands are answered over the API, not written in the interface`() {
        graphQlTester
            .document("""query { chatCommands(workspaceId: $workspaceId) { name summary argument warning } }""")
            .execute()
            .path("chatCommands[*].name").entityList(String::class.java)
            .contains("workflow", "issue")
    }

    /**
     * Said on the row rather than only in the result. `/workflow` really runs
     * it: if the workflow messages somebody, it messages them, and that is worth
     * knowing before pressing rather than after.
     */
    @Test
    fun `the one with consequences says so before it is pressed`() {
        val running = commands.commands().single { it.name == "workflow" }

        assertThat(running.warning).isNotNull()
        assertThat(running.warning).contains("really runs")
        assertThat(running.argument).isNotNull()
    }

    @Test
    fun `one that takes nothing says it takes nothing`() {
        assertThat(commands.commands().single { it.name == "runs" }.argument).isNull()
    }

    /* ------------------------------------------------ what it refuses ------ */

    @Test
    fun `a command nobody has is refused by name, and the others are named`() {
        val said = commands.run(workspaceId, "alice", "teleport", "anywhere")

        assertThat(error(said)).contains("There is no /teleport")
        // Told what there is rather than only what there is not, because the
        // next thing somebody does is guess again.
        assertThat(error(said)).contains("/workflow").contains("/issue")
    }

    @Test
    fun `one that needs an argument says which, rather than running on nothing`() {
        assertThat(error(commands.run(workspaceId, "alice", "workflow", "  ")))
            .contains("needs the workflow's name")
        assertThat(error(commands.run(workspaceId, "alice", "issue", null)))
            .contains("needs one line")
    }

    /** Typed with the slash or without it: both are what somebody means. */
    @Test
    fun `the slash is optional when it is run`() {
        assertThat(error(commands.run(workspaceId, "alice", "/teleport", "x"))).contains("There is no /teleport")
    }

    /* ------------------------------------------- and it actually does it --- */

    /**
     * Carried out by the same code the agents reach, which is the point of
     * routing it that way: a second implementation would be a second set of
     * rules about what a run may do, and the two would drift.
     */
    @Test
    fun `a workflow nobody has is reported by the tool rather than by this`() {
        val said = commands.run(workspaceId, "alice", "workflow", "No Such Workflow")

        // Whatever orknux_run_workflow says about a name it cannot find - the
        // point is that it reached it, rather than being turned back here.
        assertThat(said).isNotEmpty()
        assertThat(error(said) ?: said).doesNotContain("There is no /workflow")
    }

    @Test
    fun `filing an issue from a command files it under the person who typed it`() {
        val said = commands.run(workspaceId, "alice", "issue", "Typed from a chat command")
        println(said)

        // The tool answers with the issue it filed; what matters here is that
        // the command reached it and the name on it is the person's.
        assertThat(said).contains("Typed from a chat command")
    }

    @Test
    fun `the mutation runs it as the caller`() {
        graphQlTester
            .document(
                """mutation { runChatCommand(workspaceId: $workspaceId, name: "issue",
                     argument: "Typed through the API") }""",
            )
            .execute()
            .path("runChatCommand").entity(String::class.java)
            .satisfies({ assertThat(it).contains("Typed through the API") })
    }
}
