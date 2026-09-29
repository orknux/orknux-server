package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.Decision
import io.mszymanski.orknux.connector.model.DecisionModelClient
import io.mszymanski.orknux.connector.model.DecisionQuestionKind
import io.mszymanski.orknux.workflow.execution.EdgeBranch
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.KIND_RUNNER_ORDER
import io.mszymanski.orknux.workflow.execution.LogLevel
import io.mszymanski.orknux.workflow.execution.RunLogger
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.StepFailedException
import io.mszymanski.orknux.workflow.execution.StepResult
import io.mszymanski.orknux.workflow.execution.StepStatus
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import kotlin.math.max

/**
 * Runs a decision node: asks a decision model - or a chat model, in the same
 * shape - its questions about the run, and says which way the answer goes.
 *
 * The state is the node's `state` parameter - wording of its own, or a field
 * the run carries, which may be a whole object - and where it is left empty,
 * whatever reached the node. The model is the node's own, picked on it like an
 * image node's.
 *
 * Every answer goes on as the provider gave it, with one field added: `sure`,
 * whether it cleared the node's threshold. A noul also gets `holds`, the yes or
 * no its probability comes to, so a later condition has a flag to ask rather
 * than a number to compare. Where the node branches on a choice, the option it
 * picked is the edge the run leaves by - or the unsure edge, where the answer
 * did not clear the threshold. Issue #577.
 *
 * Unfinished is skipped, not failed, for the reason an image node's is: a
 * graph is drawn before it is finished. A model that could not be asked is a
 * real failure, permanent where asking again cannot change the answer - a
 * refused key, a request the provider found invalid - so the retry machinery
 * spends nothing on it.
 */
@Component
@Order(KIND_RUNNER_ORDER)
class DecisionNodeRunner(
    private val client: DecisionModelClient,
    private val expressions: NodeExpressions,
    private val mapper: ObjectMapper,
    /** For what a chat model's answer could not be read for, so the run says why a question is unanswered. */
    private val runLog: RunLogger,
) : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.DECISION

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult {
        val modelId = step.decisionModelId
            ?: return StepResult(StepStatus.SKIPPED, "${step.name} names no decision model, so nothing was asked.")
        val spec = DecisionSpec.read(step.decisionSpec, mapper)
        if (spec.questions.isEmpty()) {
            return StepResult(StepStatus.SKIPPED, "${step.name} has no questions to ask.")
        }

        val state = stateOf(step, input, trigger)
        val answers = when (val decided = client.decide(modelId, state, spec.questions)) {
            is Decision.Failed -> throw StepFailedException(
                step.nodeKey,
                "${step.name} could not decide: ${decided.reason}",
                permanent = decided.permanent,
            )
            is Decision.Answered -> {
                // Each question the answer could not be read for is left
                // unanswered - which a branching question reads as unsure -
                // and said here, so the run shows why.
                decided.notes.forEach { note ->
                    runLog.write(step.executionId, step.nodeKey, LogLevel.INFO, "${step.name}: $note")
                }
                decided.answers
            }
        }

        spec.questions.forEach { question ->
            val answer = answers.path(question.key) as? ObjectNode ?: return@forEach
            if (question.kind == DecisionQuestionKind.NOUL) {
                answer.put("holds", answer.path("noul").asDouble(0.0) >= HALF)
            }
            answer.put("sure", sure(question.kind, answer, spec.threshold))
        }

        val output = expressions.alongsideJson(step.outputName, mapper.writeValueAsString(answers), input)
        val branching = spec.branching() ?: return StepResult(StepStatus.COMPLETED, output)

        /*
         * The option, where the model was sure enough; the unsure edge
         * otherwise. A choice that came back naming nothing - no answer under
         * its key at all - is as unsure as an answer can be.
         */
        val answer = answers.path(branching.key)
        val picked = answer.path("choice").stringValueOpt().orElse(null)
        return if (picked != null && answer.path("sure").asBoolean(false)) {
            StepResult(StepStatus.COMPLETED, output, branch = EdgeBranch.OPTION, option = picked)
        } else {
            StepResult(StepStatus.COMPLETED, output, branch = EdgeBranch.UNSURE)
        }
    }

    /**
     * What the questions are about: the `state` parameter, read the way an
     * action's argument is - a reference to an object stays an object - or,
     * left empty, whatever reached the node.
     */
    private fun stateOf(step: ExecutionStep, input: String?, trigger: String?): JsonNode {
        val given = expressions.parse(input)
        val binding = expressions.mappingsOf(step)[DECISION_STATE]?.takeIf { it.expression.isNotBlank() }
        if (binding == null) return given ?: mapper.stringNode(input.orEmpty())

        if (!binding.reference) return mapper.stringNode(binding.expression)
        val read = runCatching { mapper.readTree(expressions.jsonOf(binding, given, expressions.parse(trigger))) }.getOrNull()
        return if (read == null || read.isNull) mapper.stringNode("") else read
    }

    /**
     * Whether an answer cleared the threshold: its confidence for a choice or
     * a score, and for a noul the probability of whichever side it came down
     * on - 0.1 is as sure a "no" as 0.9 is a "yes".
     */
    private fun sure(kind: DecisionQuestionKind, answer: JsonNode, threshold: Double?): Boolean {
        if (threshold == null) return true
        val certainty = when (kind) {
            DecisionQuestionKind.NOUL -> answer.path("noul").asDouble(HALF).let { max(it, 1 - it) }
            else -> answer.path("confidence").asDouble(0.0)
        }
        return certainty >= threshold
    }

    private companion object {
        const val HALF = 0.5
    }
}
