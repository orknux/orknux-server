package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.workflow.execution.ExecutionStep
import org.springframework.stereotype.Component
import io.mszymanski.orknux.workflow.execution.NodeBinding
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * What a node was told to pass on, and what that comes to once the run is
 * carrying something.
 *
 * A parameter is either a value, used as written, or a reference to a field the
 * run is carrying. A value may hold `{{field}}` placeholders, filled from what
 * the run carries - see [Placeholders], and [GraphValidator] for why one that
 * nothing will answer never reaches a run.
 *
 * There are two ways to want the answer, and the difference matters. An action
 * argument is JSON — a string argument has to arrive quoted, or the function
 * receives something that is not a string. A prompt is read by a model, and
 * quoting it would put the quote marks in the sentence. So the caller says which
 * it needs.
 */
@Component
class NodeExpressions(
    private val mapper: ObjectMapper,
    /** Where `variables.<id>` is answered from; see [StepVariables]. */
    @param:org.springframework.context.annotation.Lazy private val variables: StepVariables? = null,
) {

    /** The run's payload, or null when there is nothing to read from. */
    fun parse(input: String?): JsonNode? = input
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { mapper.readTree(it) }.getOrNull() }

    /**
     * The same again, with what reached the step kept alongside it.
     *
     * For a node that adds to a run rather than answering it. An image node is
     * the case: it reads a prompt out of what the agent before it said and
     * draws a picture, and a reply after it wants both - the words to say and
     * the picture to attach. Replacing the payload, which is what every other
     * node does, made the picture the only thing left and every reference the
     * reply held read as a field nothing produces.
     *
     * The step's own field wins a collision: it is what this step did, and
     * silently keeping the older value under the same name would be a step
     * reporting a result it did not produce. Without a name there is nothing
     * to merge under, so the value goes on as it stands, as it always has.
     */
    fun alongsideJson(outputName: String?, json: String, reaching: String?): String {
        val name = outputName?.trim().orEmpty()
        if (name.isEmpty()) return json

        val before = parse(reaching)
        if (before == null || !before.isObject) return namedJson(name, json)

        val merged = (before.deepCopy() as tools.jackson.databind.node.ObjectNode)
        val parsed = runCatching { mapper.readTree(json) }.getOrNull()
        if (parsed == null) merged.put(name, json) else merged.set(name, parsed)
        return mapper.writeValueAsString(merged)
    }

    /**
     * What a step hands on, under the name its node gave it.
     *
     * Without a name the value goes on as it is, which is what every node did
     * before names existed and what a node still does when it has none. With
     * one, it becomes `{"reply": "…"}` — the smallest thing a later node can
     * address a field of.
     */
    fun named(outputName: String?, value: String): String {
        val name = outputName?.trim().orEmpty()
        if (name.isEmpty()) return value

        return mapper.writeValueAsString(mapOf(name to value))
    }

    /**
     * The same, for a value that is already JSON.
     *
     * A function returns JSON, so wrapping it as text would hand the next node
     * the *source* of an object rather than the object — `{"result":"{\"id\":1}"}`
     * where `{"result":{"id":1}}` was meant, and a reference to `result.id` would
     * have nothing to read.
     */
    fun namedJson(outputName: String?, json: String): String {
        val name = outputName?.trim().orEmpty()
        if (name.isEmpty()) return json

        val parsed = runCatching { mapper.readTree(json) }.getOrNull()
            // Not JSON after all: treat it as the text it is rather than failing
            // a step over the shape of its own output.
            ?: return named(name, json)

        return mapper.writeValueAsString(mapOf(name to parsed))
    }

    /**
     * What this step was told to pass, as the planner wrote it down.
     *
     * Steps planned before parameters knew whether they were a value or a
     * reference wrote a plain name-to-text map. Those are read as written
     * values, which is what they were.
     */
    fun mappingsOf(step: ExecutionStep): Map<String, NodeBinding> = step.mappings
        ?.let { runCatching { mapper.readValue(it, Map::class.java) }.getOrNull() }
        ?.entries
        ?.mapNotNull { (name, held) ->
            val binding = when (held) {
                is Map<*, *> -> NodeBinding(
                    expression = held["expression"]?.toString().orEmpty(),
                    reference = held["reference"] == true,
                    from = held["from"]?.toString(),
                    type = held["type"]?.toString(),
                )

                else -> NodeBinding(expression = held?.toString() ?: return@mapNotNull null)
            }
            name.toString() to binding
        }
        ?.toMap()
        .orEmpty()
        .let { bindings -> variables?.resolved(step, bindings) ?: bindings }

    /**
     * What a parameter comes to, as text.
     *
     * A value is the text itself — all of it, whatever it looks like. A
     * reference reads the field it names, out of what the run is carrying or out
     * of the event that started it.
     *
     * A value's `{{field}}` placeholders are filled in as it is read. One that
     * nothing answers was refused on save, which is what keeps this from being
     * the failure that sent `{{llmResult}}` to Slack as those eleven characters.
     */
    fun textOf(binding: NodeBinding, input: JsonNode?, trigger: JsonNode? = null): String {
        if (!binding.reference) return filled(binding.expression, input, trigger)

        val (source, path) = read(binding.expression, input, trigger)
        val value = path.fold(source) { node, step -> node?.get(step) } ?: return ""
        return if (value.isTextual) value.stringValue() else value.toString()
    }

    /**
     * The same, as JSON, for something being handed to a function.
     *
     * A written value is text unless the field says what it holds. Where it
     * does - an Object node's own field, which is the one place somebody names
     * a field *and* fills it in - the text is read as the thing it spells: `3`
     * typed into a number is the number 3 downstream rather than the string
     * "3", and the flag a later condition asks about is a flag.
     *
     * What cannot be read that way is kept as the text it is. A field declared
     * a number and left holding `soon` is somebody mid-edit or somebody who
     * meant it; dropping the field, or failing the step over it, would lose
     * work that the run can perfectly well carry and whoever reads it can
     * perfectly well see.
     */
    fun jsonOf(binding: NodeBinding, input: JsonNode?, trigger: JsonNode? = null): String {
        if (!binding.reference) return written(binding.copy(expression = filled(binding.expression, input, trigger)))

        val (source, path) = read(binding.expression, input, trigger)
        return path.fold(source) { node, step -> node?.get(step) }?.toString() ?: "null"
    }

    /** A written value with its placeholders filled: a field's text as it is, anything else as its JSON. */
    private fun filled(text: String, input: JsonNode?, trigger: JsonNode?): String = Placeholders.filled(text) { field ->
        val (source, path) = read(field, input, trigger)
        val value = path.fold(source) { node, step -> node?.get(step) }
        when {
            value == null || value.isNull || value.isMissingNode -> ""
            value.isTextual -> value.stringValue()
            else -> value.toString()
        }
    }

    /** A written value, as what its field says it is - or as the text it is. */
    private fun written(binding: NodeBinding): String {
        val said = binding.expression
        val parsed = when (binding.type) {
            NUMBER -> said.trim().toBigDecimalOrNull()?.toString()
            BOOLEAN -> said.trim().lowercase().takeIf { it == "true" || it == "false" }
            OBJECT, ARRAY -> runCatching { mapper.readTree(said) }
                .getOrNull()
                ?.takeIf { it.isObject || it.isArray }
                ?.toString()

            // STRING, and every field nobody typed, are the text they hold.
            else -> null
        }
        return parsed ?: mapper.writeValueAsString(said)
    }

    /**
     * Which payload a reference reads from, and the path into it.
     *
     * `trigger.channel` reads the event that started the run; anything else
     * reads what the run is carrying now, which is where every named output
     * ends up.
     */
    private fun read(field: String, input: JsonNode?, trigger: JsonNode?): Pair<JsonNode?, List<String>> {
        val steps = field.trim().split('.').filter { it.isNotEmpty() }
        return if (steps.firstOrNull() == TRIGGER) trigger to steps.drop(1) else input to steps
    }

    companion object {
        const val TRIGGER = "trigger"

        /** What a reference to one of the workspace's variables starts with: `variables.<id>`. */
        const val VARIABLES = "variables"

        /*
         * The kinds a field can be declared, by name.
         *
         * Names rather than the enum, because what a shape is made of is
         * `PropertyKind` in the half of the product that edits shapes, and a
         * binding carries only what a run has to know. Spelled here so a kind
         * that is renamed is one failing constant rather than five silent
         * string comparisons.
         */
        const val NUMBER = "NUMBER"
        const val BOOLEAN = "BOOLEAN"
        const val OBJECT = "OBJECT"
        const val ARRAY = "ARRAY"
    }
}
