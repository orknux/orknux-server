package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper

/**
 * `save_artifact` naming an artifact to replace: the file goes in at the same
 * address, so the link an agent handed somebody yesterday opens today's file.
 */
@SpringBootTest
class SaveArtifactReplaceTest(
    @Autowired val tools: AgentTools,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val saved: SavedArtifactRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private fun agentIn(workspace: Long) = Agent(
        workspaceId = workspace,
        name = "Reporter",
        type = AgentType.LLM,
        tools = BuiltInTools.GRANTED.filterNot { BuiltInTools.switchable(it) }.toMutableList(),
        hiddenTools = BuiltInTools.hiddenBy(BuiltInTools.GRANTED),
    )

    private fun call(agent: Agent, session: Long, arguments: Map<String, String>) = mapper.readTree(
        tools.saveArtifact(agent, ToolCall("1", AgentTools.SAVE_ARTIFACT, mapper.writeValueAsString(arguments)), session),
    )

    @Test
    fun `naming an artifact to replace keeps its address and puts the new file behind it`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "replace-tool-${System.nanoTime()}")).id)
        val session = recorder.open(workspace, "task", "replace")
        val agent = agentIn(workspace)

        val first = call(agent, session, mapOf("name" to "summary.md", "description" to "The summary", "content" to "# One"))
        val link = first.path("url").asString()
        val row = saved.findByWorkspaceId(workspace).single()
        val oldLocation = row.location

        val second = call(
            agent, session,
            mapOf("name" to "summary.md", "description" to "", "content" to "# Two", AgentTools.REPLACES to link),
        )
        assertThat(second.path("url").asString()).describedAs(second.toString()).isEqualTo(link)
        assertThat(second.path("replaced").isMissingNode).isFalse()
        val now = saved.findByWorkspaceId(workspace).single()
        assertThat(now.id).isEqualTo(row.id)
        assertThat(now.description).describedAs("a blank one keeps the caption it had").isEqualTo("The summary")
        assertThat(store.open(now.location).use { it.readBytes() }).isEqualTo("# Two".toByteArray())
        assertThat(store.exists(oldLocation)).isFalse()
        assertThat(audit.findAll().filter { it.workspaceId == workspace }.map { it.message })
            .contains("Artifact summary.md replaced by Reporter")

        // The key the page shows names it as well.
        call(agent, session, mapOf("name" to "summary.md", "description" to "Three", "content" to "# Three", AgentTools.REPLACES to "SAVED-${row.id}"))
        assertThat(store.open(saved.findByWorkspaceId(workspace).single().location).use { it.readBytes() }).isEqualTo("# Three".toByteArray())
    }

    @Test
    fun `another workspace's artifact, another kind's key and an unknown one are refused in words`() {
        val mine = requireNotNull(workspaces.save(Workspace(name = "replace-tool-mine-${System.nanoTime()}")).id)
        val theirs = requireNotNull(workspaces.save(Workspace(name = "replace-tool-theirs-${System.nanoTime()}")).id)
        val theirSession = recorder.open(theirs, "task", "theirs")
        call(agentIn(theirs), theirSession, mapOf("name" to "plan.txt", "description" to "Their plan", "content" to "theirs"))
        val theirRow = saved.findByWorkspaceId(theirs).single()

        val session = recorder.open(mine, "task", "mine")
        val across = call(agentIn(mine), session, mapOf("name" to "plan.txt", "description" to "x", "content" to "mine", AgentTools.REPLACES to "SAVED-${theirRow.id}"))
        assertThat(across.path("error").asString()).isEqualTo("This workspace has no saved artifact SAVED-${theirRow.id}.")
        assertThat(store.open(saved.findById(requireNotNull(theirRow.id)).get().location).use { it.readBytes() }).isEqualTo("theirs".toByteArray())

        val picture = call(agentIn(mine), session, mapOf("name" to "a.png", "description" to "x", "content" to "x", AgentTools.REPLACES to "IMAGE-3"))
        assertThat(picture.path("error").asString()).contains("IMAGE-3").contains(AgentTools.REPLACES)
        assertThat(saved.findByWorkspaceId(mine)).describedAs("a refused replace saves nothing new").isEmpty()
    }
}
