package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * A node parameter pointed at one of the workspace's variables, answered.
 *
 * A reference reads what the run carries or what started it, and a workspace
 * variable is neither - so a parameter that wanted one went through a function
 * written only to hand it on. A reference written `variables.<id>` is read here,
 * when the step's parameters are, and becomes the value the variable holds now:
 * what a later node sees is the variable, not a copy taken when the run began.
 *
 * By id rather than by name, because a name is unique only within its catalog,
 * and an id survives the variable being renamed.
 *
 * **Never a secret.** A parameter is handed on as text - into a Slack message,
 * a Jira comment, a model's prompt - and a secret referenced into one is a
 * secret published in clear. The editor does not offer them; one referenced
 * anyway resolves to nothing, and the log says why. Secrets reach code through
 * a function's externals and a plugin's parameters, which hold them in.
 */
@Component
class StepVariables(
    private val variables: WorkspaceVariableRepository,
    private val executions: WorkflowExecutionRepository,
) {

    fun resolved(step: ExecutionStep, bindings: Map<String, NodeBinding>): Map<String, NodeBinding> {
        if (bindings.values.none { it.reference && it.expression.trim().startsWith(PREFIX) }) return bindings
        val workspaceId = executions.findByIdOrNull(step.executionId)?.workspaceId ?: return bindings
        return bindings.mapValues { (name, binding) ->
            val asked = binding.expression.trim()
            if (!binding.reference || !asked.startsWith(PREFIX)) return@mapValues binding
            val variable = asked.removePrefix(PREFIX).toLongOrNull()?.let { variables.findByIdOrNull(it) }
                ?.takeIf { it.workspaceId == workspaceId }
            when {
                variable == null -> {
                    log.warn("{} reads {}, which is no variable of this workspace", step.name, asked)
                    NodeBinding(expression = "")
                }
                variable.kind == VariableKind.SECRET -> {
                    log.warn("{} reads the secret {} into {}, which is never handed on", step.name, variable.name, name)
                    NodeBinding(expression = "")
                }
                else -> NodeBinding(expression = variable.value.orEmpty(), type = typeOf(variable.type))
            }
        }
    }

    /** What a written value's field holds, so jsonOf hands on a number as a number. */
    private fun typeOf(type: VariableType): String? = when (type) {
        VariableType.NUMBER -> "NUMBER"
        VariableType.BOOLEAN -> "BOOLEAN"
        VariableType.LIST -> "ARRAY"
        VariableType.STRING -> null
    }

    private companion object {
        const val PREFIX = NodeExpressions.VARIABLES + "."
        val log = LoggerFactory.getLogger(StepVariables::class.java)
    }
}
