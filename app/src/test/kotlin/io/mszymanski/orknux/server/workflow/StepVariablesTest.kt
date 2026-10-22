package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.obj.ObjectNodeRunner
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * A node parameter pointed at one of the workspace's variables reads it when
 * the step runs: `variables.<id>`, typed as the variable is - and never a
 * secret, which would be handed on as text into whatever the node writes.
 */
@SpringBootTest
class StepVariablesTest(
    @Autowired val runner: ObjectNodeRunner,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var runId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "vars-${System.nanoTime()}")).id)
        runId = requireNotNull(
            executions.save(
                WorkflowExecution(
                    workspaceId = workspaceId, workflowId = 1, workflowName = "Report", status = ExecutionStatus.RUNNING,
                    trigger = ExecutionTrigger.API, startedAt = OffsetDateTime.now(), input = "{}",
                ),
            ).id,
        )
    }

    private fun variable(workspace: Long, name: String, held: String, type: VariableType, kind: VariableKind): Long {
        val catalog = requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspace, name = "c-$name-${System.nanoTime()}")).id)
        return requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = workspace, catalogId = catalog, name = name, type = type, kind = kind, value = held,
                ),
            ).id,
        )
    }

    private fun made(vararg fields: Pair<String, NodeBinding>) = mapper.readTree(
        runner.run(
            ExecutionStep(
                executionId = runId, nodeKey = "held", kind = NodeKind.OBJECT, name = "Report", outputName = "report",
                mappings = mapper.writeValueAsString(fields.toMap()), order = 0, x = 0.0, y = 0.0,
            ),
            input = "{}",
            trigger = null,
        ).output.orEmpty(),
    ).path("report")

    @Test
    fun `a reference to a variable reads what it holds, typed as it is`() {
        val project = variable(workspaceId, "project", "SUPPORT", VariableType.STRING, VariableKind.VALUE)
        val limit = variable(workspaceId, "limit", "25", VariableType.NUMBER, VariableKind.VALUE)

        val held = made(
            "project" to NodeBinding(expression = "variables.$project", reference = true),
            "limit" to NodeBinding(expression = "variables.$limit", reference = true),
        )

        assertThat(held.path("project").stringValue()).isEqualTo("SUPPORT")
        assertThat(held.path("limit").isNumber).isTrue()
        assertThat(held.path("limit").asInt()).isEqualTo(25)
    }

    @Test
    fun `a secret and another workspace's variable read as nothing`() {
        val secret = variable(workspaceId, "token", "sk-live-123", VariableType.STRING, VariableKind.SECRET)
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "other-${System.nanoTime()}")).id)
        val theirs = variable(elsewhere, "project", "THEIRS", VariableType.STRING, VariableKind.VALUE)

        val held = made(
            "token" to NodeBinding(expression = "variables.$secret", reference = true),
            "project" to NodeBinding(expression = "variables.$theirs", reference = true),
        )

        assertThat(held.toString()).doesNotContain("sk-live-123").doesNotContain("THEIRS")
    }

    /**
     * The same rules inside a written value: what a variable holds is written
     * where it stands, and a secret, another workspace's variable or one that is
     * gone writes nothing - never the secret, and never its braces.
     */
    @Test
    fun `a variable written into a value as a placeholder is filled, and a secret never is`() {
        val project = variable(workspaceId, "project", "SUPPORT", VariableType.STRING, VariableKind.VALUE)
        val template = variable(workspaceId, "template", "Hi {{trigger.user}}", VariableType.STRING, VariableKind.VALUE)
        val secret = variable(workspaceId, "token", "sk-live-123", VariableType.STRING, VariableKind.SECRET)
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "other-${System.nanoTime()}")).id)
        val theirs = variable(elsewhere, "project", "THEIRS", VariableType.STRING, VariableKind.VALUE)

        val held = made(
            "said" to NodeBinding(expression = "Project {{variables.$project}}: {{ variables.$template }}"),
            "leak" to NodeBinding(expression = "[{{variables.$secret}}|{{variables.$theirs}}|{{variables.987654321}}]"),
        )

        // What the variable holds is sent as it holds it, not filled a second time.
        assertThat(held.path("said").stringValue()).isEqualTo("Project SUPPORT: Hi {{trigger.user}}")
        assertThat(held.path("leak").stringValue()).isEqualTo("[||]")
    }
}
