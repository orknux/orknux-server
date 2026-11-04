package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * An argument an orknux_* tool does not take is said in its answer.
 *
 * `orknux_update_agent` handed `agents` where it takes `mayAsk` answered
 * "updated" and changed nothing, and a model reported the agent changed. The
 * call still runs - a stray field beside good ones is common - but the answer
 * names what was not used and what the tool does take.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class OrknuxIgnoredArgumentsTest(
    @Autowired val tools: OrknuxTools,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    @Test
    fun `an argument a tool does not take is named in its answer, with the ones it does`() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "ignored-${System.nanoTime()}")).id)
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Tester")

        val answer = mapper.readTree(tools.run(scope, DiagramTools.LIST, """{"catalogue": "Main", "colour": "blue"}"""))

        assertThat(answer.path("ignored").asString()).contains("catalogue").contains("colour").contains("catalog")
        // A call with only what it takes says nothing of the kind.
        assertThat(mapper.readTree(tools.run(scope, DiagramTools.LIST, "{}")).has("ignored")).isFalse()
    }
}
