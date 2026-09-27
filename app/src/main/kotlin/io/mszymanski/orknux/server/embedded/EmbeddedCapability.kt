package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.workflow.script.ScriptResult

/**
 * One thing the release can do, written here rather than run in a sandbox.
 * Issue #505.
 *
 * #501 took the pdf and the charts off the Plugins page and left the JavaScript
 * exactly where it was: the same jsPDF, the same hand-written mermaid parser,
 * the same sandbox. That was half the job. Embedding means the code is ours and
 * runs on the JVM, and what the sandbox cost is written down in the bundle's own
 * preamble - a page of shims defining `TextEncoder`, `console`, `setTimeout` and
 * a global object before a single line of our own runs, because a module body is
 * evaluated before it has been granted anything.
 *
 * What it bought was worse than what it cost. Five mermaid diagram kinds,
 * because the parser is hand-written and each kind is a day's work. Fonts as
 * base64 subsets, because there is no font stack. A chart that cannot get into
 * a page, because the two renderers are separate bundles with a session store
 * between them. None of that is true here.
 *
 * **A capability declares what it brings and answers when asked**, which is the
 * whole of the contract, because that is all [EmbeddedCapabilities] ever needed
 * from a bundle either. The two dispatch points - an agent's tool call and a
 * workflow's function call - go on reaching the same names, so nothing outside
 * this package can tell which kind answered.
 *
 * **Nothing is granted and nothing is asked.** A plugin declares permissions
 * because a plugin is somebody else's code; this is ours, and a capability the
 * product itself needs is not a thing to put to an administrator - refusing it
 * would leave a feature that cannot work and no way to say why.
 */
interface EmbeddedCapability {

    /**
     * What its names begin with: `pdf` gives `pdf_fromHtml`, the way the bundle
     * spelled it, so an agent that knew the name knows it still.
     */
    val key: String

    /** What it is called where a person reads a list of them. */
    val name: String

    /** The tools an agent is offered, named without the key; it is prefixed here. */
    fun tools(): List<EmbeddedTool>

    /**
     * The workflow functions, which are rows a graph points at.
     *
     * Usually the same list as [tools] and not always: a tool takes a key
     * because bytes must not travel through a model, and a function behind it
     * takes the bytes because a workflow node has no session to keep them in.
     */
    fun functions(): List<EmbeddedFunction> = emptyList()

    /**
     * One tool call. [arguments] is the JSON object the model sent; the answer
     * is JSON, and a refusal is `{"error": "..."}` like every other tool's.
     */
    fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String

    /**
     * One function call, arguments positional and in declared order, which is
     * how a graph passes them. Null where this capability has no such function.
     */
    fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? = null
}

/** One tool, as a capability declares it. */
data class EmbeddedTool(
    /** Without the key: `fromHtml`, not `pdf_fromHtml`. */
    val name: String,
    val description: String,
    val params: List<EmbeddedParam> = emptyList(),
    /**
     * The line it gets where every tool is listed at once, rather than the
     * description read at the moment of calling. Issue #481.
     */
    val summary: String? = null,
)

/** One workflow function, which is a row rather than a call. */
data class EmbeddedFunction(
    val name: String,
    val description: String,
    val returnType: ValueType = ValueType.MAP,
    val params: List<EmbeddedParam> = emptyList(),
)

/**
 * One argument.
 *
 * [description] rather than a type name, which is what a bundle's declaration
 * could offer and why a model reading one learned so little: "string" says what
 * it is and nothing about what to put in it.
 */
data class EmbeddedParam(
    val name: String,
    val type: ValueType,
    val description: String,
    val required: Boolean = false,
    /** JSON, as it would be written in a call. */
    val defaultJson: String? = null,
)

/** What a capability hands back where the answer is a tool's. */
fun EmbeddedTool.spec(key: String): ToolSpec = ToolSpec(
    name = key + "_" + name,
    description = description,
    parameters = params.map {
        io.mszymanski.orknux.connector.model.ToolParameterSpec(
            name = it.name,
            description = it.description,
            required = it.required,
        )
    },
    summary = summary,
)
