package io.mszymanski.orknux.server.llm

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * Takes the long values out of a tool call before the line is stored.
 *
 * A tool call is recorded as the model sent it, and most of the time that is a
 * few short arguments and a page of text back. Then a tool carries a payload -
 * a base64 image on its way to Slack, a rendered SVG, the bytes of a file - and
 * the same row is suddenly kilobytes of one string. One real
 * `llm_session_event` held 5,867 characters of base64 in a single field. The
 * table grows out of proportion to the conversation it is recording, and the
 * transcript page is a wall of nothing anybody can read, with the two arguments
 * that actually say what happened somewhere inside it.
 *
 * **Field by field rather than blob by blob**, and that is the whole of the
 * design. Cutting the recorded text at some length would take the payload out
 * and everything after it with it, leaving a row that is not JSON and a reader
 * who cannot tell which argument was the large one. So the JSON is walked and
 * only the values over [LONGEST_VALUE] are shortened:
 * `{"base64":"…6000 chars…","channel":"C123"}` keeps `channel` exactly as it
 * was, keeps its braces and its quotes, and loses only the one value that was
 * never worth storing. Objects and arrays are walked all the way down, because
 * a payload is as often two levels in - `{"blocks":[{"image":"…"}]}` - as it is
 * at the top.
 *
 * **The cut says how long the value was.** A value that simply stops reads as a
 * value that was that short, and somebody looking at a row for the reason
 * anybody looks at one - working out what the tool was actually handed - would
 * be guessing. `… (trimmed from 5867 characters)` says a payload was here, says
 * roughly how big, and is short enough not to be the thing that bloats the row.
 *
 * **Text that is not JSON is shortened whole**, on the same rule and with the
 * same marker. A tool that answers in plain text - a shell command's output, a
 * `--help`, a stack trace - can carry exactly the same kilobyte of nothing, and
 * a rule that only applied to well-formed JSON would be a rule the noisiest
 * tools escape. This is the part with a real cost attached, and it should be
 * read with eyes open: a build log kept in full is a build log
 * [LlmSessionRecorder.recalled] can put back in front of a model that has to
 * fix the build, and from here on the model gets the first [LONGEST_VALUE]
 * characters of it and a note saying how much there was. A tool that answers in
 * JSON keeps all of its short fields and is unaffected, which is what most of
 * them do; one that answers in prose is cut. The marker is what makes that
 * survivable - a model told it is holding part of an answer can call the tool
 * again, which is the same bargain the recall budget already makes.
 *
 * **Nothing under the line is touched at all.** Where no value needed cutting,
 * the text that came in is the text that goes out, character for character -
 * not a re-serialised copy of it. [LlmSessionRecorder.toolCalled] records the
 * arguments unparsed and unprettied on purpose, because reformatting them is
 * this deciding what the model meant, and that promise is kept for every call
 * that carries no payload. Only a row that had something cut out of it is
 * written back from the parsed tree, and that one is compact JSON rather than
 * whatever spacing arrived.
 *
 * **This is the record and not the call.** Nothing here touches the value a
 * tool returned to the agent, the arguments the tool was run with, or what the
 * model is given inside the round it asked in. It runs between the answer and
 * the `INSERT`, which is the only place it can run: what is already in the
 * table stays as long as it was.
 *
 * It runs after [io.mszymanski.orknux.server.workspace.AuditRedaction], and
 * that order matters. Redacting first means a credential is found in the whole
 * text; redacting a value that has already been cut would leave the first
 * characters of a secret behind at the cut, which is the failure
 * `AuditRedaction` names in its own list of what it cannot catch.
 */
object SessionValueTrim {

    /**
     * How long a single value may be before it is shortened, in characters.
     *
     * Long enough to hold the things a field is legitimately long for - a
     * commit message, a paragraph of a description, a URL with a query on it -
     * and far short of anything that is a payload rather than a value. A value
     * just over the line comes out a few characters longer than it went in,
     * once the marker is on it. That is accepted rather than fixed with a
     * second threshold, because a rule with two numbers in it is one nobody
     * reading a row can predict.
     */
    const val LONGEST_VALUE = 1_000

    /**
     * The text as it should be stored: the same thing, with the payloads cut.
     *
     * @param text what was about to be written into `llm_session_event` -
     *   arguments or a result, JSON or not.
     */
    fun trim(text: String, longest: Int = LONGEST_VALUE): String {
        // Nothing inside a short text can be over the line, and this is the
        // ordinary case: it costs a length check rather than a JSON parse.
        if (text.length <= longest) return text

        val tree = runCatching { reader.readTree(text) }.getOrNull() ?: return shorten(text, longest)
        val cut = walk(tree, longest) ?: return text
        // A tree that could be read can be written, so the fallback here is for
        // the case nobody has seen rather than one anybody expects - and losing
        // a payload is still better than losing the line.
        return runCatching { reader.writeValueAsString(cut) }.getOrElse { shorten(text, longest) }
    }

    /**
     * The node to put in this one's place, or null where it stays as it is.
     *
     * Null rather than the node itself is what tells [trim] whether anything
     * was cut at all, and that answer is what decides between writing the tree
     * back out and keeping the text exactly as it arrived.
     */
    private fun walk(node: JsonNode, longest: Int): JsonNode? = when {
        node.isString -> node.stringValue()
            .takeIf { it.length > longest }
            ?.let { NODES.stringNode(shorten(it, longest)) }

        node.isObject -> walkObject(node as ObjectNode, longest)
        node.isArray -> walkArray(node as ArrayNode, longest)

        // A number, a boolean or a null has no length worth bounding, and the
        // names of an object's fields are not values: a payload arrives as a
        // field's contents, never as what the field is called.
        else -> null
    }

    private fun walkObject(node: ObjectNode, longest: Int): JsonNode? {
        var cut = false
        // The names are taken first because replacing a value goes through the
        // same map `properties()` is a view of, and a walk that rewrote as it
        // read would be modifying what it is iterating.
        node.properties().map { it.key }.forEach { name ->
            val value = node.get(name) ?: return@forEach
            walk(value, longest)?.let {
                node.set(name, it)
                cut = true
            }
        }
        return node.takeIf { cut }
    }

    private fun walkArray(node: ArrayNode, longest: Int): JsonNode? {
        var cut = false
        for (index in 0 until node.size()) {
            walk(node.get(index), longest)?.let {
                node.set(index, it)
                cut = true
            }
        }
        return node.takeIf { cut }
    }

    /**
     * The cut, and what it says about itself.
     *
     * It says calling again will not recover the rest, because the marker used
     * to imply the opposite and a model believed it. What is missing is missing
     * from the *record*: the tool would answer in full again, and this would
     * keep the same first characters of it and drop the same tail. So a model
     * reading this on a later turn and calling the tool to get the rest back
     * arrives exactly where it started, which is a loop with a polite invitation
     * at the top of it. Issue #519.
     */
    private fun shorten(value: String, longest: Int): String =
        value.take(longest) + "… (trimmed from ${value.length} characters; " +
            "calling the tool again returns the same answer and it is kept the same way)"

    /**
     * A reader of its own, built once.
     *
     * Not injected, because this is called from a recorder that is itself a
     * side effect of somebody's conversation and has to work wherever it is
     * called from - including from a unit test that builds no context at all.
     * Nothing here configures the mapper, so there is nothing an injected one
     * would carry that this does not.
     */
    private val reader = ObjectMapper()

    private val NODES: JsonNodeFactory = JsonNodeFactory.instance
}
