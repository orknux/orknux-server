package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.SessionScratch
import io.mszymanski.orknux.workflow.script.StoredKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * A file a task makes reaches the workspace's Artifacts.
 *
 * Reported on task 81: the agent made a PDF, said so, and the file was only in
 * its session's store - `save_artifact` could not take a key, so the bytes had
 * to be typed back, and that agent had not been given the tool anyway.
 */
@SpringBootTest
class TaskArtifactsTest(
    @Autowired val taskArtifacts: TaskArtifacts,
    @Autowired val tools: AgentTools,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val scratch: SessionScratch,
    @Autowired val saved: SavedArtifactRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private val pdf = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".toByteArray()

    private fun agentIn(workspace: Long, granted: List<String>) = Agent(
        workspaceId = workspace,
        name = "Reporter",
        type = AgentType.LLM,
        tools = granted.filterNot { BuiltInTools.switchable(it) }.toMutableList(),
        hiddenTools = BuiltInTools.hiddenBy(granted),
    )

    @Test
    fun `save_artifact takes the bytes another tool left under a key`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "art-${System.nanoTime()}")).id)
        val session = recorder.open(workspace, "task", "pdf")
        scratch.put(session, "file.report", mapper.writeValueAsString(Base64.getEncoder().encodeToString(pdf)), StoredKind("application/pdf", true))

        val answer = mapper.readTree(
            tools.saveArtifact(
                agentIn(workspace, BuiltInTools.GRANTED),
                ToolCall("1", AgentTools.SAVE_ARTIFACT, """{"name":"report.pdf","description":"The weekly report","contentKey":"file.report"}"""),
                session,
            ),
        )
        assertThat(answer.path("url").asString()).describedAs(answer.toString()).contains("/api/artifacts/")
        val held = saved.findByWorkspaceId(workspace).single()
        assertThat(held.sizeBytes).describedAs("the PDF's own bytes, not its base64").isEqualTo(pdf.size.toLong())
        assertThat(held.contentType).isEqualTo("application/pdf")
    }

    @Test
    fun `a key nothing is under is said so`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "art-${System.nanoTime()}")).id)
        val session = recorder.open(workspace, "task", "none")
        val answer = tools.saveArtifact(
            agentIn(workspace, BuiltInTools.GRANTED),
            ToolCall("1", AgentTools.SAVE_ARTIFACT, """{"name":"x.pdf","contentKey":"file.missing"}"""),
            session,
        )
        assertThat(answer).contains("Nothing is kept under file.missing")
    }

    @Test
    fun `a task agent without the tool is offered it, and one with it is not offered it twice`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "art-${System.nanoTime()}")).id)
        val session = recorder.open(workspace, "task", "grant")

        val without = taskArtifacts.shed(agentIn(workspace, BuiltInTools.GRANTED - AgentTools.SAVE_ARTIFACT), session)
        assertThat(without).isNotNull
        assertThat(without!!.specs().map { it.name }).containsExactly(AgentTools.SAVE_ARTIFACT)
        assertThat(without.handles(AgentTools.SAVE_ARTIFACT)).isTrue()

        assertThat(taskArtifacts.shed(agentIn(workspace, BuiltInTools.GRANTED), session)).isNull()
    }
}
