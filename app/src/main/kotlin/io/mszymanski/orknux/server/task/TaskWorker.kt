package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Who a task's turn is put to, and what that worker may reach.
 *
 * A task reaches exactly what its agent was granted, plus whatever a person has
 * approved for this task and nothing else. That is the whole of the security
 * answer and it is deliberately the narrow one: the issue's wording ("with all
 * necessary tools available") invites a task that can do more than a chat can,
 * and a loop that runs for an hour unattended is the last place to widen what an
 * agent may do. What a task *can* do that a chat cannot is keep going without
 * anybody there - not reach further.
 *
 * The widening is done by building a detached copy of the agent rather than by
 * threading an overlay through every class that reads a grant. That keeps the
 * rule in one function that can be read in one sitting, and it means
 * [io.mszymanski.orknux.server.chat.AgentTools] and everything under it goes on
 * asking the question it already asks - "what is this agent allowed" - with no
 * second answer to keep in step.
 *
 * **The copy is never saved.** It carries the real agent's id because the shell
 * tools record sessions against it, and it must not reach a repository: it holds
 * grants nobody gave the agent. Nothing here hands it to one, and nothing
 * should.
 */
@Component
class TaskWorker(
    private val agents: AgentRepository,
    private val models: LlmModelRepository,
    private val grants: TaskGrantRepository,
) {

    /**
     * The agent that will take this task's next turn, widened by what has been
     * approved for it.
     *
     * @throws TaskNotRunnableException when the agent or the model it needs is
     *   no longer there. Said rather than substituted: which model a task runs
     *   on changes what it costs and what it answers.
     */
    fun of(task: Task): Working {
        val taskId = requireNotNull(task.id)
        val approved = grants.findByTaskIdOrderByGrantedAtAscIdAsc(taskId)

        val agent = task.agentId?.let {
            agents.findByIdOrNull(it) ?: throw TaskNotRunnableException("The agent that had this task is gone")
        }
        if (agent != null && !agent.enabled) {
            throw TaskNotRunnableException("${agent.name} is switched off")
        }

        val modelId = task.modelId
            ?: agent?.modelId
            ?: throw TaskNotRunnableException("This task has no model to think with")
        val model = models.findByIdOrNull(modelId)
            ?: throw TaskNotRunnableException("The model this task ran on is gone")

        return Working(widened(task, agent, model.name, modelId, approved), modelId)
    }

    /**
     * The worker as the model sees it: an agent's grants plus the approvals.
     *
     * A task given a bare model gets an agent that exists only for this call,
     * named after the model and granted nothing. It is a coherent starting
     * point rather than a special case downstream: it has the three tools every
     * task has, it can ask for anything else, and whoever approves is approving
     * for a worker that began with nothing.
     */
    private fun widened(
        task: Task,
        agent: Agent?,
        modelName: String,
        modelId: Long,
        approved: List<TaskGrant>,
    ): Agent {
        val held = approved.map { it.capability }.toSet()
        fun named(capability: TaskCapability) =
            approved.filter { it.capability == capability }.mapNotNull { it.subject }

        return Agent(
            id = agent?.id,
            workspaceId = task.workspaceId,
            name = agent?.name ?: modelName,
            type = AgentType.LLM,
            description = agent?.description,
            systemPrompt = agent?.systemPrompt,
            enabled = true,
            modelId = modelId,
            memoryShare = agent?.memoryShare,
            orknuxAccess = agent?.orknuxAccess == true || TaskCapability.ORKNUX in held,
            shellAccess = agent?.shellAccess == true || TaskCapability.SHELLS in held,
            mcpServers = merged(agent?.mcpServers, named(TaskCapability.MCP_SERVER)),
            memoryCatalogs = merged(agent?.memoryCatalogs, named(TaskCapability.MEMORY_CATALOG)),
            skillCatalogs = merged(agent?.skillCatalogs, named(TaskCapability.SKILL_CATALOG)),
            tools = merged(agent?.tools, named(TaskCapability.TOOL)),
            // What the agent switched off stays off in a task - built-ins and orknux_* tools alike.
            hiddenTools = agent?.hiddenTools?.toMutableList() ?: mutableListOf(),
        )
    }

    /**
     * The agent's own list with the approvals added, each name once.
     *
     * A grant is a name, so approving something the agent already had is not an
     * error and not a second entry - it is somebody approving what was already
     * true, which happens when a tool was granted between the ask and the
     * answer.
     */
    private fun merged(held: List<String>?, approved: List<String>): MutableList<String> =
        (held.orEmpty() + approved).distinct().toMutableList()

    /** The worker and the model it thinks with, which the loop needs both of. */
    data class Working(val agent: Agent, val modelId: Long)
}
