package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.FinishAnswerTools
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.memory.MemoryTool
import io.mszymanski.orknux.server.shell.ShellTools
import io.mszymanski.orknux.server.workflow.StepPictureTools
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

/**
 * One line about each built-in, for the agent form's hover card.
 *
 * Read off the specs the model is handed, so the card says what the model is
 * told and cannot drift from it. The built-ins are spread over a dozen owners -
 * the lenders a round borrows from, and the grant-bound services - so each is
 * asked in turn; a lender's specs do not depend on the session it is lent to,
 * which is why a placeholder session is enough to read them. Each is asked
 * inside its own `runCatching`: a card with a line missing is better than a
 * Tools list that does not load.
 *
 * Taken lazily, because several of these depend on things that depend on
 * [AgentTools], and a controller is no reason to make a cycle.
 */
@Service
class BuiltInToolSummaries(
    private val tools: ObjectProvider<AgentTools>,
    private val notes: ObjectProvider<NoteTools>,
    private val todos: ObjectProvider<TodoTools>,
    private val dates: ObjectProvider<DateTools>,
    private val timers: ObjectProvider<TimerTools>,
    private val scratchpads: ObjectProvider<ScratchpadTools>,
    private val finishing: ObjectProvider<FinishAnswerTools>,
    private val pictures: ObjectProvider<StepPictureTools>,
    private val memories: ObjectProvider<MemoryTool>,
    private val orknux: ObjectProvider<OrknuxTools>,
    private val shells: ObjectProvider<ShellTools>,
    /**
     * What the release brings, read directly as well: the agent's own offer
     * leaves the HTTP tools out while Admin Settings has them off, and these
     * lines are read once for the life of the server. Issue #602.
     */
    private val embedded: ObjectProvider<io.mszymanski.orknux.server.embedded.EmbeddedCapabilities>,
) {

    /** Each built-in's name to its first sentence; a name with no spec found is absent. */
    fun summaries(): Map<String, String> {
        val specs = mutableListOf<Pair<String, String>>()
        fun take(read: () -> List<ToolSpec>?) {
            runCatching { read() }.getOrNull()?.let { found -> specs += found.map { it.name to it.description } }
        }
        val everything = Agent(
            workspaceId = 0,
            name = "",
            type = AgentType.LLM,
            tools = (BuiltInTools.GRANTED + BuiltInTools.REACHING).toMutableList(),
        )
        take { tools.getObject().specsFor(everything) }
        take { embedded.getObject().toolSpecs() }
        take { notes.getObject().shed(PLACEHOLDER, "")?.specs() }
        take { todos.getObject().shed(PLACEHOLDER)?.specs() }
        take { dates.getObject().shed().specs() }
        take { timers.getObject().shed(PLACEHOLDER)?.specs() }
        take { scratchpads.getObject().shed(PLACEHOLDER)?.specs() }
        take { finishing.getObject().shed()?.specs() }
        take { pictures.getObject().shed(PLACEHOLDER, "", PLACEHOLDER, sessionId = PLACEHOLDER)?.specs() }
        runCatching {
            listOf(SkillTool.LIST, SkillTool.LOAD, SkillTool.SEARCH).forEach { specs += it.name to it.description }
        }
        runCatching {
            memories.getObject().let { listOf(it.descriptor(), it.saveDescriptor(), it.updateDescriptor(), it.deleteDescriptor()) }
                .forEach { specs += it.name to it.description }
        }
        take { orknux.getObject().specs(OrknuxScope(workspaceId = 0, mayWrite = true)) }
        take { shells.getObject().specs() }
        return specs
            .filter { (_, description) -> description.isNotBlank() }
            .associate { (name, description) -> name to firstSentence(description) }
    }

    companion object {
        private const val PLACEHOLDER = 0L

        /** The opening sentence, which is what a model's description leads with; the rest is for the model. */
        fun firstSentence(description: String): String {
            val flat = description.replace(Regex("""\s+"""), " ").trim()
            val end = Regex("""(?<=[.!?])\s""").find(flat)?.range?.first ?: flat.length
            return flat.substring(0, end).trim()
        }
    }
}
