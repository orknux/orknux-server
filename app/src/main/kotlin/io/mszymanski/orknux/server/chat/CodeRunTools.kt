package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.workflow.script.ScriptLanguage
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import io.mszymanski.orknux.workflow.script.ScriptToolDoor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * A short program an agent writes and runs at once, over the session's files.
 *
 * An agent asked to mark every cancelled order in a 1,900-row CSV had pads and
 * keys and no way to compute over them: it could read the file a slice at a
 * time and edit one line per call, which is two thousand calls, so it looked
 * for "CSV transform" and "scratchpad execute script", found neither, and left
 * the file half done. A join, a filter, a count - work a dozen lines of Python
 * does in a millisecond - is work a model does worst of all by hand.
 *
 * Nothing new is opened for it. The code runs through [ScriptRunner], the
 * sandbox every workspace function runs in, with the same denials and the same
 * clock; it reaches the session's scratchpads and keys through the `orknux`
 * helpers a function already has, and nothing that a function could not.
 * Lent with the scratchpads, because the files are what it is for and they
 * exist only where there is a session.
 *
 * ### And the agent's own tools
 *
 * The same agent's statuses came from an MCP tool that takes a page of order
 * ids at a time - thirty-odd calls for that file, each answer to be copied into
 * a pad by hand. A program that can only compute is half of it; one that can
 * call `orknux.tools.call("order_mcp__query", {...})` in its loop is the whole.
 * The call goes through [AgentTools.run], the dispatch the round itself uses,
 * so the agent's grants and hidden tools hold exactly as they do for a call it
 * makes itself. Bounded by [Tunable.CODE_RUN_TOOL_CALLS] and by the clock,
 * [Tunable.CODE_RUN_SECONDS]; asking another agent and waiting for one are not
 * offered, as they are not to a watcher. Not read-only, for the same reason:
 * what a program calls may change something, so a turn that is only looking
 * refuses the program rather than what it calls.
 */
@Service
class CodeRunTools(
    private val runner: ScriptRunner,
    private val sessions: LlmSessionRepository,
    /** Fetched: the agent's tools hold the sheds that hold this. */
    private val agentTools: ObjectProvider<AgentTools>,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
) {

    fun spec(): ToolSpec {
        val seconds = tuning[Tunable.CODE_RUN_SECONDS]
        val calls = tuning[Tunable.CODE_RUN_TOOL_CALLS]
        /*
         * The bounds in numbers, read when the conversation's tools are made:
         * a model told only "a time limit" plans a loop of two thousand calls
         * and learns the bound from its refusal, half way through.
         */
        val bounds = " Limits for one run: $seconds seconds, " + if (calls > 0) {
            "and at most $calls calls of your tools - more ids per call rather than more calls. "
        } else {
            "and no calls of your tools at all on this server: orknux.tools.call is refused. "
        } + "It cannot ask another agent or a person, and it makes no web requests and sends no Slack " +
            "messages of its own: call a tool of yours for that."
        return ToolSpec(
            name = RUN,
            readOnly = false,
            description = "Runs a short program you write - Python unless you say JavaScript - and answers with what it " +
                "returned and printed. Use it to transform, filter, join, count or check data: a CSV, a JSON list, " +
                "lines of text - anything that would otherwise take a call per row. It reads and writes your " +
                "scratchpads and session keys directly, so the data never passes through you: " +
                "orknux.scratchpad.text(name) is a pad's whole text, orknux.scratchpad.write(name, text) creates or " +
                "replaces one, orknux.scratchpad.list() lists them, and orknux.session.store.get(key) is what a key " +
                "holds (an attached CSV's text; base64 for a binary file). orknux.tools.call(name, arguments) calls " +
                "any of your own tools - an MCP query, a lookup - and answers what it answered, so a program can " +
                "query a hundred ids at a time in a loop and join the answers itself, in one call of this. " +
                "Python either defines main() and returns the answer, or is a plain script that sets result to it; " +
                "it has its standard " +
                "library - csv, json, re, io, datetime, collections, statistics. JavaScript exports a default " +
                "function. Return something small - a count, a summary, a few rows - and write anything long into a " +
                "pad. It runs in a sandbox: no files, no processes, no packages to install." + bounds,
            parameters = listOf(
                ToolParameterSpec(
                    CODE,
                    "The program. Python: def main(): ... that returns the answer, or a script that sets result. JavaScript: " +
                        "export default function main() { ... }. Example: import csv, io\\n" +
                        "def main():\\n    rows = list(csv.DictReader(io.StringIO(orknux.scratchpad.text('orders.csv'))))\\n" +
                        "    return len(rows)",
                    required = true,
                ),
                ToolParameterSpec(LANGUAGE, "\"python\" (the default) or \"javascript\".", required = false),
            ),
        )
    }

    fun handles(name: String): Boolean = name == RUN

    fun run(arguments: String, session: Long, agent: Agent? = null): String {
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return refusal("That is not valid JSON.")
        val code = asked.path(CODE).takeIf { it.isTextual }?.stringValue()?.takeIf { it.isNotBlank() }
            ?: return refusal("Give the program as $CODE.")
        val language = when (asked.path(LANGUAGE).takeIf { it.isTextual }?.stringValue()?.trim()?.lowercase()) {
            null, "", "python", "py" -> ScriptLanguage.PYTHON
            "javascript", "js", "typescript", "ts" -> ScriptLanguage.JAVASCRIPT
            else -> return refusal("$LANGUAGE is \"python\" or \"javascript\".")
        }
        /*
         * A script with no main runs as a script: its top level when it is
         * loaded, and a main added after it that answers with `result`. Two
         * agents in two chats each wrote their first program that way and lost
         * a round to the refusal this used to be, so the form a model reaches
         * for first is a form that works. Appended rather than indented in, so
         * a multi-line string in the script keeps its text.
         */
        val program = if (language == ScriptLanguage.PYTHON && !PYTHON_MAIN.containsMatchIn(code)) {
            code + SCRIPT_MAIN
        } else {
            code
        }
        val called = linkedMapOf<String, Int>()
        val result = runner.call(
            source = program,
            functionName = "main",
            arguments = emptyList(),
            // The session's workspace, so a helper that reaches a connection reaches this workspace's only.
            on = sessions.findByIdOrNull(session)?.workspaceId,
            sessionId = session,
            language = language,
            timeoutMillis = tuning[Tunable.CODE_RUN_SECONDS] * 1000L,
            tools = agent?.let { door(it, session, called) },
            // Nothing a function gets without asking: what it reaches is the agent's grants, through the door.
            confined = true,
        )
        return mapper.writeValueAsString(
            when (result) {
                is ScriptResult.Returned -> buildMap {
                    put("returned", result.json?.let { runCatching { mapper.readTree(it) }.getOrNull() ?: it })
                    if (result.logs.isNotEmpty()) put("printed", result.logs)
                    if (called.isNotEmpty()) put("toolCalls", called)
                    put("ms", result.durationMillis)
                }
                is ScriptResult.Failed -> buildMap {
                    put("error", result.reason)
                    if (result.logs.isNotEmpty()) put("printed", result.logs)
                    if (called.isNotEmpty()) put("toolCalls", called)
                }
            },
        )
    }

    /**
     * The agent's tools, as the program reaches them: by the round's own
     * dispatch, counted, and refused past the bound or where the tool is one
     * a program has no business calling. [called] is what the answer reports,
     * since the transcript shows one code_run where a hundred calls ran.
     */
    private fun door(agent: Agent, session: Long, called: MutableMap<String, Int>): ScriptToolDoor {
        val most = tuning[Tunable.CODE_RUN_TOOL_CALLS]
        var made = 0
        return ScriptToolDoor { name, arguments ->
            when {
                name in NOT_FROM_A_PROGRAM -> refusal("$name cannot be called from a program; call it yourself.")
                made >= most -> refusal("This program has called $most tools, which is as many as one run may.")
                else -> {
                    made += 1
                    called.merge(name, 1, Int::plus)
                    agentTools.getObject().run(agent, ToolCall("code-run-$made", name, arguments), session)
                }
            }
        }
    }

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    companion object {
        const val RUN = "code_run"
        const val CODE = "code"
        const val LANGUAGE = "language"

        /**
         * Asking another agent starts a conversation and waiting for one holds
         * the sandbox's thread for as long as it waits - the two a watcher is
         * refused for the same reason - and a person cannot be asked from a loop.
         */
        private val NOT_FROM_A_PROGRAM = setOf(
            AgentRunTools.ASK, AgentRunTools.WAIT, AgentRunTools.ASKS, AgentRunTools.LIST, AgentRunTools.STOP,
            QuestionTools.ASK, RUN,
        )

        private val PYTHON_MAIN = Regex("""(?m)^def\s+main\s*\(""")

        /** What a Python script with no main is run through: its own `result`, or nothing. */
        private const val SCRIPT_MAIN = "\n\ndef main():\n    return globals().get('result')\n"
    }
}
