package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.dependency.Dependant
import io.mszymanski.orknux.server.dependency.DependencyKind
import org.springframework.stereotype.Component

/**
 * Which of a workspace's agents were granted a thing, by the name it is granted by.
 *
 * [WorkflowReferences][io.mszymanski.orknux.server.workflow.WorkflowReferences]
 * asks the same question of workflows, and the two are not the same question.
 * A workflow node holds an *id*: delete what it points at and the id is left
 * dangling, which is at least a thing a run can notice and report. A grant holds
 * a **name**, and a name that matches nothing is not dangling — it is simply not
 * there. [WorkspaceToolCaller] drops it, [SkillTool] drops it, and both are right
 * to: a rename should cost an agent one grant rather than every call it makes.
 *
 * So there is no dangling reference to detect and nothing anywhere reports one.
 * Deleting a tool takes a capability away from every agent that had it, the
 * agent's own screen goes on listing the grant, and the first anybody knows is a
 * conversation in which the agent did not do the thing it can do. That is worse
 * than a refused delete, which is the whole argument for asking here.
 *
 * A name is also re-bindable, which the id case is not. Create a tool called
 * `weather` again tomorrow and every agent still holding the grant is silently
 * handed the new one — whatever it now does. Nobody chose that; they chose the
 * tool that used to have the name. Refusing the delete is what keeps a grant
 * pointing at the thing somebody meant, and it is why this guard belongs at the
 * delete rather than at the next thing that reads the grant.
 *
 * Only the workspace's own agents are asked. A grant is looked up within the
 * agent's workspace, so an agent elsewhere holding the same word holds a grant
 * to a different thing entirely.
 *
 * All four name-grants are asked here, and the fourth is answered differently.
 * [Agent.mcpServers] names an address somebody registered, run by somebody
 * else, where a workspace's own tools, skills and memories are things this
 * application holds. "The server is gone, so I removed the entry" is ordinary
 * housekeeping, and a refusal would stand in the way of a tidy-up that is
 * already the right thing to do — so `removeMcpServer` does not refuse.
 *
 * It does not leave the grant behind either. A name pointing at nothing is
 * exactly the re-binding above: register a server under that name again and
 * every agent still holding it is armed with whatever now answers there, and
 * nobody chose that. So the removal takes the grant off each agent that held
 * it and the response names them. Refusing and revoking are two ways to keep a
 * grant pointing at the thing somebody meant; which one fits depends on what is
 * named, and only for MCP is the thing named already somebody else's to remove.
 */
@Component
class AgentGrants(private val agents: AgentRepository) {

    /** Which of the workspace's agents may ask this one - see `ask_agent`. */
    fun toAgent(workspaceId: Long, id: Long): List<Dependant> =
        agents.findAsking(workspaceId, id).map { asker ->
            Dependant(
                kind = DependencyKind.AGENT,
                id = asker.id,
                name = asker.name,
                workspaceId = workspaceId,
                workspaceName = null,
                published = false,
                phrase = "the agent ${asker.name}",
            )
        }

    /** Which of the workspace's agents may call this tool. */
    fun toTool(workspaceId: Long, name: String): List<Dependant> =
        agents.findGrantedTool(workspaceId, name).map(::held)

    /** Which of the workspace's agents draw on this skill catalog. */
    fun toSkillCatalog(workspaceId: Long, name: String): List<Dependant> =
        agents.findGrantedSkillCatalog(workspaceId, name).map(::held)

    /** Which of the workspace's agents may read this memory catalog. */
    fun toMemoryCatalog(workspaceId: Long, name: String): List<Dependant> =
        agents.findGrantedMemoryCatalog(workspaceId, name).map(::held)

    /**
     * The agent as a row, and as the clause a refusal says it in.
     *
     * "the agent Reviewer" is the wording all three refusals already used, and it
     * stays exactly that — [Dependant.phrase] is what they join. What the row adds
     * is the id, so the screen that lists where a tool is used can open the agent
     * instead of leaving the reader to go and find it.
     */
    private fun held(agent: Agent) = Dependant(
        kind = DependencyKind.AGENT,
        id = requireNotNull(agent.id),
        name = agent.name,
        workspaceId = agent.workspaceId,
        workspaceName = null,
        published = false,
        phrase = "the agent ${agent.name}",
    )

    /**
     * Which of the workspace's agents may connect to this MCP server.
     *
     * The agents themselves rather than a sentence about them, because this one
     * is not read to write a refusal: the caller takes the grant off each of
     * them. See the note above for why that is the answer here and a refusal is
     * the answer for the other three.
     */
    fun toMcpServer(workspaceId: Long, name: String): List<Agent> =
        agents.findGrantedMcpServer(workspaceId, name)
}
