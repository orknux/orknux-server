package io.mszymanski.orknux.connector.model

import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The same typed questions, asked of an ordinary chat model. Issue #577.
 *
 * A decision node runs on a decision model or on any chat model the workspace
 * has, and whatever answered, what comes back is the decision API's own shape -
 * a choice's `choice`, `confidence` and `probabilities`, a score's `score` and
 * `legend`, a noul's `noul` - so the node's threshold, its branches and every
 * reference a later node holds mean the same thing either way. What differs is
 * where the numbers come from: a decision model is calibrated, and a chat model
 * is asked for its own estimate of each probability, which is normalised here
 * and read the same way. Confidence is the probability of the answer taken, so
 * a threshold of 0.8 asks the same question of both.
 *
 * Asked through [ModelChatClient], the one client every chat call goes
 * through - its throttle, its usage count, its providers. It has no structured
 * output mode, so the shape is asked for in words and held here, the way an
 * agent node's shaped answer is: a reply that is not a JSON object fails
 * unsettled and the node's retry policy decides whether to ask again, and an
 * answer to one question that cannot be read leaves that question unanswered,
 * with a note saying why, rather than failing the others.
 */
@Service
class ChatDecisions(
    private val chat: ModelChatClient,
    private val mapper: ObjectMapper,
) {

    fun decide(model: LlmModel, state: JsonNode, questions: List<DecisionQuestion>): Decision {
        val turns = listOf(
            ChatTurn("system", INSTRUCTIONS),
            ChatTurn("user", asked(state, questions)),
        )
        val reply = when (val said = chat.complete(requireNotNull(model.id), turns)) {
            is ChatCompletion.Answered -> said
            is ChatCompletion.Failed -> return Decision.Failed("${model.name} could not answer: ${said.reason}", said.permanent)
            else -> return Decision.Failed("${model.name} asked for tools instead of answering", permanent = false)
        }

        val tree = runCatching { mapper.readTree(unfenced(reply.content)) }.getOrNull()
        if (tree == null || !tree.isObject) {
            return Decision.Failed("${model.name} was asked for a JSON object and answered something else", permanent = false)
        }

        val notes = mutableListOf<String>()
        val answers = mapper.createObjectNode()
        /*
         * One question answered without its key around it. Seen from DeepSeek:
         * asked the same thing twice, it answered {"q": {"probabilities": ...}}
         * once and {"probabilities": ...} the next time, and the second was read
         * as no answer at all. With one question there is only one thing the
         * bare answer can be about.
         */
        val bare = questions.size == 1 && !tree.has(questions.single().key) &&
            (tree.has("probabilities") || tree.has("noul") || questions.single().options.any { tree.has(it.name) })
        questions.forEach { question ->
            val given = if (bare) tree else tree.path(question.key)
            val answer = when (question.kind) {
                DecisionQuestionKind.CHOICE -> choice(question, given, notes)
                DecisionQuestionKind.SCORE -> score(question, given, notes)
                DecisionQuestionKind.NOUL -> noul(question, given, notes)
            }
            if (answer != null) answers.set(question.key, answer)
        }
        return Decision.Answered(
            answers = answers,
            model = model.modelId,
            inputTokens = reply.inputTokens,
            outputTokens = reply.outputTokens,
            millis = reply.millis,
            notes = notes,
        )
    }

    /** The state, then every question in the words and names the reply has to use. */
    private fun asked(state: JsonNode, questions: List<DecisionQuestion>): String = buildString {
        append("State:\n")
        append(if (state.isString) state.stringValue() else state.toString())
        append("\n\nQuestions:\n")
        questions.forEach { question ->
            append("- \"").append(question.key).append("\" (").append(question.kind.wire).append("): ")
            append(question.instructions.ifBlank { "(no wording given)" }).append('\n')
            when (question.kind) {
                DecisionQuestionKind.CHOICE -> question.options.forEach {
                    append("    option \"").append(it.name).append("\": ").append(it.description.ifBlank { it.name }).append('\n')
                }
                DecisionQuestionKind.SCORE -> question.options.forEachIndexed { at, level ->
                    append("    level ").append(at).append(": ").append(level.description.ifBlank { level.name }).append('\n')
                }
                DecisionQuestionKind.NOUL -> question.options.filter { it.description.isNotBlank() }.forEach {
                    append("    ").append(it.name).append(" means: ").append(it.description).append('\n')
                }
            }
        }
        /*
         * And the reply written out, with these keys and these options, the
         * numbers left to the model. Told only a template, DeepSeek answered the
         * same question in three shapes across three calls - with the key and
         * "probabilities", without the key, without "probabilities" - and two
         * were read as no answer. An example to copy is what it keeps to.
         */
        append("\nReply in exactly this shape, with your own numbers:\n")
        append(example(questions))
    }

    /** The reply these questions want, with placeholder numbers, as one line of JSON. */
    private fun example(questions: List<DecisionQuestion>): String {
        val root = mapper.createObjectNode()
        questions.forEach { question ->
            val entry = root.putObject(question.key)
            when (question.kind) {
                DecisionQuestionKind.CHOICE -> entry.putObject("probabilities").also { held ->
                    question.options.forEach { held.put(it.name, 0.0) }
                }
                DecisionQuestionKind.SCORE -> entry.putObject("probabilities").also { held ->
                    question.options.indices.forEach { held.put(it.toString(), 0.0) }
                }
                DecisionQuestionKind.NOUL -> entry.put("noul", 0.0)
            }
        }
        return root.toString()
    }

    private fun choice(question: DecisionQuestion, given: JsonNode, notes: MutableList<String>): ObjectNode? {
        val names = question.options.map { it.name }
        val offered = offeredIn(given)
        val stray = offered.keys - names.toSet()
        if (stray.isNotEmpty()) notes += "\"${question.key}\": the model named ${stray.joinToString { "\"$it\"" }}, which it was not offered"
        val kept = names.associateWith { offered[it] ?: 0.0 }
        val normal = normalised(kept) ?: run {
            notes += "\"${question.key}\": the model gave no probability for any option it was offered, so it is left unanswered"
            return null
        }
        val picked = normal.maxBy { it.value }
        return mapper.createObjectNode().apply {
            put("type", "choice")
            put("choice", picked.key)
            put("confidence", picked.value)
            putObject("probabilities").also { held -> normal.forEach { (name, p) -> held.put(name, p) } }
        }
    }

    private fun score(question: DecisionQuestion, given: JsonNode, notes: MutableList<String>): ObjectNode? {
        val levels = question.options.indices.map { it.toString() }
        val offered = offeredIn(given)
        val stray = offered.keys - levels.toSet()
        if (stray.isNotEmpty()) notes += "\"${question.key}\": the model named level ${stray.joinToString()}, which the scale does not have"
        val normal = normalised(levels.associateWith { offered[it] ?: 0.0 }) ?: run {
            notes += "\"${question.key}\": the model gave no probability for any level, so it is left unanswered"
            return null
        }
        val top = normal.maxBy { it.value }
        return mapper.createObjectNode().apply {
            put("type", "score")
            put("score", normal.entries.sumOf { it.key.toInt() * it.value })
            put("confidence", top.value)
            putObject("legend").also { held ->
                question.options.forEachIndexed { at, level -> held.put(at.toString(), level.description.ifBlank { level.name }) }
            }
            putObject("probabilities").also { held -> normal.forEach { (level, p) -> held.put(level, p) } }
        }
    }

    private fun noul(question: DecisionQuestion, given: JsonNode, notes: MutableList<String>): ObjectNode? {
        val held = given.path("noul")
        val p = when {
            held.isNumber -> held.asDouble()
            held.isBoolean -> if (held.asBoolean()) 1.0 else 0.0
            else -> null
        }
        if (p == null || p.isNaN()) {
            notes += "\"${question.key}\": the model gave no probability that it holds, so it is left unanswered"
            return null
        }
        // A percentage is the same estimate on another scale.
        val probability = if (p > 1.0 && p <= PERCENT) p / PERCENT else p
        return mapper.createObjectNode().put("type", "noul").put("noul", probability.coerceIn(0.0, 1.0))
    }

    /**
     * The numbers under a name, never below nought; anything that is not a
     * number is not a probability. Not capped at one, because they are
     * normalised next - a model that answers in percentages, or in weights,
     * has still said how the options compare.
     */
    private fun probabilities(node: JsonNode): Map<String, Double> =
        if (!node.isObject) emptyMap() else node.properties()
            .filter { (_, value) -> value.isNumber }
            .associate { (name, value) -> name to value.asDouble().coerceAtLeast(0.0) }

    /**
     * A choice's or score's numbers wherever the model put them: under
     * "probabilities", as asked, or straight under the question's key - which
     * DeepSeek did on one call in three. A backup to the example in the prompt.
     */
    private fun offeredIn(given: JsonNode): Map<String, Double> =
        if (given.path("probabilities").isObject) probabilities(given.path("probabilities")) else probabilities(given)

    /** Scaled to sum to one; null where they sum to nothing, which is no answer at all. */
    private fun normalised(probabilities: Map<String, Double>): Map<String, Double>? {
        val total = probabilities.values.sum()
        if (total <= 0.0 || total.isNaN()) return null
        return probabilities.mapValues { it.value / total }
    }

    /** The JSON inside a ```fence```, where the model wrapped it in one. */
    private fun unfenced(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private companion object {
        const val PERCENT = 100.0

        val INSTRUCTIONS = """
            You answer typed questions about a state. Reply with one JSON object and nothing else, with one entry
            per question key:
            - a choice question: {"probabilities": {"<option>": p, ...}} over exactly the options listed
            - a score question: {"probabilities": {"0": p, "1": p, ...}} over the level numbers listed
            - a yes-or-no question: {"noul": p}, the probability that the statement holds
            Each p is your honest estimate from 0 to 1.
        """.trimIndent()
    }
}
