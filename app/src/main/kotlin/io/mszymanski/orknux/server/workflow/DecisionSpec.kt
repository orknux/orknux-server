package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.DecisionOption
import io.mszymanski.orknux.connector.model.DecisionQuestion
import io.mszymanski.orknux.connector.model.DecisionQuestionKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * What a decision node asks, and how it decides which way the run goes.
 *
 * One document rather than a column per field and a child table per question,
 * because a question holds a list of options, the node reads and writes the
 * whole of it at once, and nothing ever asks the database about one option.
 * It is written and read by hand, like [WorkflowSnapshot], for the same reason:
 * the app's mapper cannot construct a Kotlin class, and a stored shape outlives
 * the class it came from. Issue #577.
 */
data class DecisionSpec(
    val questions: List<DecisionQuestion> = emptyList(),
    /**
     * The key of the question whose answer picks the edge the run leaves by,
     * or null for a node that only answers. A choice leaves by the option it
     * picked; a yes-or-no by `yes` or `no`. A score cannot: a place on a
     * scale is not a name an edge can carry.
     */
    val branchQuestion: String? = null,
    /**
     * How sure an answer has to be to be taken, from 0 to 1; null takes every
     * answer. A choice or a score is sure when its confidence clears it, a
     * noul when its probability does on either side. Under it, a branching
     * node leaves by its unsure edge rather than by the option.
     */
    val threshold: Double? = null,
) {

    /** The question that branches, where there is one and it is a choice or a yes-or-no. */
    fun branching(): DecisionQuestion? =
        branchQuestion?.let { key -> questions.firstOrNull { it.key == key && branches(it.kind) } }

    /**
     * The options the node leaves by, one OPTION edge each: a choice's option
     * names, or a yes-or-no's `yes` and `no`. The unsure edge is beside these,
     * never one of them. Empty for a node that does not branch.
     */
    fun ways(): List<String> {
        val question = branching() ?: return emptyList()
        return if (question.kind == DecisionQuestionKind.NOUL) listOf(YES, NO) else question.options.map { it.name }
    }

    companion object {

        /** A node that asks nothing yet. */
        val EMPTY = DecisionSpec()

        /** The two options a yes-or-no question leaves by, as its OPTION edges carry them. */
        const val YES = "yes"
        const val NO = "no"

        /** Whether a question of this kind can pick the edge a run leaves by. */
        fun branches(kind: DecisionQuestionKind): Boolean =
            kind == DecisionQuestionKind.CHOICE || kind == DecisionQuestionKind.NOUL

        fun write(spec: DecisionSpec, mapper: ObjectMapper): String = mapper.writeValueAsString(
            mapOf(
                "questions" to spec.questions.map { question ->
                    mapOf(
                        "key" to question.key,
                        "kind" to question.kind.name,
                        "instructions" to question.instructions,
                        "options" to question.options.map { mapOf("name" to it.name, "description" to it.description) },
                    )
                },
                "branchQuestion" to spec.branchQuestion,
                "threshold" to spec.threshold,
            ),
        )

        /** Null or unreadable is a node that asks nothing, rather than a failed read. */
        fun read(json: String?, mapper: ObjectMapper): DecisionSpec {
            if (json.isNullOrBlank()) return EMPTY
            val held = runCatching { mapper.readTree(json) }.getOrNull() ?: return EMPTY
            return DecisionSpec(
                questions = held.path("questions").values().mapNotNull { question ->
                    val kind = text(question, "kind")
                        ?.let { name -> DecisionQuestionKind.entries.firstOrNull { it.name == name } }
                        ?: return@mapNotNull null
                    DecisionQuestion(
                        key = text(question, "key").orEmpty(),
                        kind = kind,
                        instructions = text(question, "instructions").orEmpty(),
                        options = question.path("options").values().map { option ->
                            DecisionOption(text(option, "name").orEmpty(), text(option, "description").orEmpty())
                        },
                    )
                },
                branchQuestion = text(held, "branchQuestion"),
                threshold = held.path("threshold").let { if (it.isNumber) it.asDouble() else null },
            )
        }

        /**
         * The same, for a caller with no mapper to hand - a view built from an
         * entity. Reading a tree needs nothing the application's mapper is
         * configured with.
         */
        fun read(json: String?): DecisionSpec = read(json, PLAIN)

        private val PLAIN: ObjectMapper = tools.jackson.databind.json.JsonMapper.builder().build()

        private fun text(node: JsonNode, name: String): String? =
            node.path(name).let { if (it.isString) it.stringValue() else null }
    }
}
