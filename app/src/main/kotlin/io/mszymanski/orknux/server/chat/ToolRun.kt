package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import tools.jackson.databind.ObjectMapper

/**
 * The one tool through which an agent calls a tool its request does not carry.
 *
 * A session's tool array is fixed at its first request and sent byte for byte
 * the same until the conversation is compacted - see
 * [io.mszymanski.orknux.server.llm.SessionFrames] for why: one tool added to the
 * array and a llama.cpp server running a hybrid model reads the whole prompt
 * again. So a tool that arrives later - granted in the conversation, found with
 * tool_load past the provider's cap, brought by an MCP server or a plugin - is
 * not added to the array. Its schema reaches the model as a message, and the
 * model calls it through this, by name. One found with tool_load is the
 * exception from the next turn on: it joins the array then, where the
 * provider's cap leaves room (see [ConversationFrame.joined]), and this reaches
 * it only in the turn that loaded it.
 *
 * Not a tool of its own: [unwrap] turns the call into the one it stands for
 * before anything looks at it, so the grant, the approval, the loop guard, the
 * session's record and the watcher all see the real tool, under its own name,
 * exactly as a direct call. A direct call by the real name is answered the same
 * way - a provider that lets a model name a tool it was not offered passes it
 * through, and it runs.
 */
object ToolRun {

    const val NAME = "tool_run"

    /** What it takes: the tool's exact name, and its arguments. */
    const val TOOL = "name"
    const val ARGUMENTS = "arguments"

    /** How many places it takes in the array. */
    const val SLOTS = 1

    val SPEC = ToolSpec(
        name = NAME,
        // Asking changes nothing by itself; what it runs is judged as itself, plan mode included.
        readOnly = true,
        description = "Calls one of your tools that is not in this list of tools - one you were granted, loaded " +
            "or given since this conversation began - by its exact name, with its arguments. It runs exactly as " +
            "calling that tool would. You are told when such a tool is yours and how it is called.",
        parameters = listOf(
            ToolParameterSpec(name = TOOL, description = "The tool's exact name.", required = true),
            ToolParameterSpec(
                name = ARGUMENTS,
                description = "The tool's arguments, as the JSON object it takes. {} where it takes none.",
                schema = mapOf("type" to "object"),
            ),
        ),
        summary = "call a tool you hold that is not in your list of tools, by name",
    )

    private val json = ObjectMapper()

    /**
     * The call [call] stands for: itself where it is not a [NAME] call, and the
     * named tool with its arguments where it is - under the same id, so its
     * answer still goes back to the call the model made. A [NAME] call that
     * names nothing, or names itself, stays as it is and is refused by [refusal].
     */
    fun unwrap(call: ToolCall): ToolCall {
        if (call.name != NAME) return call
        val sent = runCatching { json.readTree(call.arguments.ifBlank { "{}" }) }.getOrNull() ?: return call
        val named = sent.path(TOOL).takeIf { it.isString }?.stringValue()?.trim().orEmpty()
        if (named.isEmpty() || named == NAME) return call
        val given = sent.path(ARGUMENTS)
        val arguments = when {
            given.isMissingNode || given.isNull -> "{}"
            // Every parameter reaches some models typed as a string, so an object may arrive as its text.
            given.isString -> given.stringValue().trim().ifEmpty { "{}" }
            else -> json.writeValueAsString(given)
        }
        return ToolCall(call.id, named, arguments)
    }

    /**
     * [spec] as a model needs it to call the tool through [NAME]: its name, its
     * whole description, and its arguments as the JSON Schema the array would
     * have carried - the same schema, so a tool called this way is called with
     * what it would have been called with directly.
     */
    fun described(spec: ToolSpec): String {
        val properties = LinkedHashMap<String, Any?>()
        spec.parameters.forEach { properties[it.name] = it.declared() }
        val schema = linkedMapOf<String, Any?>("type" to "object", "properties" to properties)
        val required = spec.parameters.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) schema["required"] = required
        return spec.name + "\n" + spec.description.trim() + "\nArguments: " + json.writeValueAsString(schema)
    }

    /** What a [NAME] call [unwrap] could not turn into a tool's answers with. */
    fun refusal(call: ToolCall): String = json.writeValueAsString(
        mapOf("error" to "Say which tool to run: $TOOL is its exact name and $ARGUMENTS the JSON object it takes."),
    )
}
