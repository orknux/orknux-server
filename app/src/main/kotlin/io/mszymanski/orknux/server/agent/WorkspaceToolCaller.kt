package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.action.ScriptImports
import io.mszymanski.orknux.server.action.ScriptImportsResult
import io.mszymanski.orknux.server.action.ScriptTimeouts
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.variable.VariableArguments
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Calling the workspace's own JavaScript, as an agent does it.
 *
 * The skills and memory tools are built in because they read tables a sandbox
 * cannot reach. This is the opposite case: a tool *is* the workspace's code, and
 * the sandbox is exactly where it belongs. Nothing here widens it — the same
 * [ScriptRunner] runs it, with the same limits, and everything crossing the
 * boundary is JSON text.
 *
 * What an agent may call is what it was granted. That matters more here than for
 * the reading tools: a skill is a page an agent reads, and a tool does something.
 */
@Service
class WorkspaceToolCaller(
    private val tools: AgentToolRepository,
    private val scripts: ScriptRunner,
    private val scriptImports: ScriptImports,
    private val externals: VariableArguments,
    private val timeouts: ScriptTimeouts,
    private val mapper: ObjectMapper,
) {

    /**
     * The tools this agent may call, in the order it was given them.
     *
     * A granted name that matches no tool is dropped rather than failing, the
     * way a granted catalog is: a rename should cost an agent one grant, not
     * every call it makes. A tool switched off is out of reach here as anywhere.
     */
    fun granted(agent: Agent): List<AgentTool> {
        if (agent.tools.isEmpty()) return emptyList()
        return agent.tools
            .mapNotNull { tools.findByWorkspaceIdAndName(agent.workspaceId, it) }
            .filter { it.enabled }
    }

    /**
     * Runs one, handing it the arguments the model composed.
     *
     * Positionally, in the order the tool declares them — the same way the
     * sandbox hands a function its arguments, and for the same reason: the
     * declaration in the code and the list in the editor are two spellings of
     * one signature, so they have to be filled in the same order.
     *
     * The model addresses them by name, because that is all a tool schema can
     * say; the mapping from those names to positions happens here.
     *
     * A script that failed comes back as a result rather than an exception. The
     * model can apologise, try another way, or answer without it — all of which
     * beat the conversation dying because a tool threw.
     */
    fun call(agent: Agent, tool: AgentTool, arguments: String, sessionId: Long? = null): String {
        /*
         * What it imports, assembled before it runs. An import that no longer
         * resolves comes back to the model as an error like any other failure: the
         * agent can say so, try another way, or answer without it, all of which beat
         * the conversation ending because a tool could not be put together.
         */
        val resolved = when (val found = scriptImports.resolve(tool.imports, tool.libraries)) {
            is ScriptImportsResult.Resolved -> found
            is ScriptImportsResult.Broken -> {
                log.warn("Tool {} could not be assembled for agent {}: {}", tool.name, agent.name, found.reason)
                return mapper.writeValueAsString(mapOf("error" to "${tool.name} ${found.reason}"))
            }
        }

        val result = scripts.call(
            source = tool.source,
            functionName = tool.name,
            // The declared parameters as the model filled them, then the
            // workspace's variables — after the declaration, where the code
            // expects them, exactly as a function is handed its own.
            arguments = argumentsFor(tool, arguments) + externals.of(tool.externals, "Tool ${tool.name}"),
            context = context(agent, tool),
            modules = resolved.modules,
            imports = resolved.imports,
            on = agent.workspaceId,
            timeoutMillis = timeouts.forTool(tool.timeoutSeconds, agent.workspaceId),
            sessionId = sessionId,
            language = tool.language.script,
        )
        return when (result) {
            is ScriptResult.Returned -> result.json ?: mapper.writeValueAsString(mapOf("result" to null))
            is ScriptResult.Failed -> {
                log.warn("Tool {} failed for agent {}: {}", tool.name, agent.name, result.reason)
                mapper.writeValueAsString(mapOf("error" to result.reason))
            }
        }
    }

    /**
     * What the model sent, laid out in the order the tool takes it.
     *
     * Every argument is JSON text, and `null` is the JSON for "not given" — so a
     * parameter the model left out arrives as null rather than shifting the ones
     * after it along, which is the failure a positional call has to avoid.
     *
     * Two kindnesses to models that do not follow the schema exactly. One: a
     * tool taking a single parameter accepts arguments sent flat, which is how
     * the one parameter every tool used to take was already being read. Two: a
     * value stringified by a model that was asked for an object is unwrapped if
     * the string turns out to be the JSON, because the schema this crosses can
     * only say "string" and some models take that literally.
     */
    private fun argumentsFor(tool: AgentTool, arguments: String): List<String> {
        if (tool.params.isEmpty()) return emptyList()
        val sent = runCatching { mapper.readTree(arguments) }.getOrNull()

        return tool.params.map { param ->
            val given = sent?.path(param.name)
            when {
                given == null || given.isMissingNode || given.isNull ->
                    if (tool.params.size == 1) arguments.ifBlank { "{}" } else "null"

                given.isString && param.type != ValueType.STRING -> unwrapped(given.stringValue().orEmpty())
                    ?: mapper.writeValueAsString(given)

                else -> mapper.writeValueAsString(given)
            }
        }
    }

    /** The JSON inside a string a model stringified, or null if it was only a string. */
    private fun unwrapped(text: String): String? {
        val parsed = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
        return if (parsed.isMissingNode || parsed.isNull || parsed.isString) null else text
    }

    /** What the script is told about where it is running. */
    private fun context(agent: Agent, tool: AgentTool): String = mapper.writeValueAsString(
        mapOf("workspaceId" to agent.workspaceId, "agent" to agent.name, "tool" to tool.name),
    )

    private companion object {
        val log = LoggerFactory.getLogger(WorkspaceToolCaller::class.java)
    }
}
