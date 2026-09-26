package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.KIND_RUNNER_ORDER
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.StepFailedException
import io.mszymanski.orknux.workflow.execution.StepResult
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.springframework.core.annotation.Order
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Runs an image node: draws a picture from a prompt, and files it with the run.
 *
 * The workflow end of the drawing that a chat's button and a task's tool already
 * reach - the same [ModelImageClient], the same attachment storage under the
 * same installation switch. What is new is where the picture goes: an
 * [ExecutionPicture] beside the run that drew it, keyed by the step, so the run
 * graph can show a preview under the node and hand back the file. Issue #333.
 *
 * The model is the node's own, picked on it like an agent node picks an agent,
 * not a workspace default - so two image nodes in one workflow can draw with two
 * models. The prompt is a mapping, `prompt`, so it can be wording of your own or
 * a field the run is carrying.
 *
 * A model that is gone, off, or unchosen, a blank prompt, and attachments turned
 * off are all reported as a skipped step rather than a failed run: a graph is
 * drawn before it is finished, and a run should say what it found. A draw the
 * provider refused is a real failure and leaves as one, so the node's retry
 * policy and Temporal can decide what to do with it.
 */
@Component
@Order(KIND_RUNNER_ORDER)
class ImageNodeRunner(
    private val steps: StepPictures,
    private val executions: WorkflowExecutionRepository,
    private val settings: InstallationSettings,
    private val expressions: NodeExpressions,
    private val mapper: ObjectMapper,
) : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.IMAGE

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult {
        val modelId = step.imageModelId
            ?: return StepResult(StepStatus.SKIPPED, "${step.name} names no image model, so there was nothing to draw with.")

        if (!settings.attachmentsEnabled()) {
            return StepResult(
                StepStatus.SKIPPED,
                "${step.name} draws a picture, which is kept as an attachment, and attachments are turned off here.",
            )
        }

        // The workspace decides who may open the picture and where the bytes are
        // filed; it is the run's, read from the run rather than a call.
        val workspaceId = executions.findByIdOrNull(step.executionId)?.workspaceId
            ?: return StepResult(StepStatus.SKIPPED, "The run ${step.name} belongs to has gone.")

        val given = expressions.parse(input)
        val started = expressions.parse(trigger)
        val byName = expressions.mappingsOf(step)
        val prompt = byName[PROMPT]?.let { expressions.textOf(it, given, started) }?.trim().orEmpty()
        if (prompt.isEmpty()) {
            return StepResult(StepStatus.SKIPPED, "${step.name} has nothing to draw: give it a prompt.")
        }

        /*
         * Drawn and filed by the same service an agent's `draw_picture` uses,
         * so a picture is a picture however it was asked for: one row shape,
         * one storage call, one download endpoint. What stays here is what is
         * the *node's* to decide - which model, and that a draw it could not do
         * is a failed step rather than a sentence handed back to somebody.
         *
         * Everything this can be refused for has been ruled out above but the
         * two the provider decides, so a refusal at this point means somebody
         * meant this to draw and it did not. The provider's own words, which
         * say whether it was the prompt or the endpoint.
         */
        // The run's own copy of what the node asked for beyond the prompt; each
        // null is left out of the request, so the model's default stands.
        val options = ImageOptions(size = step.imageSize, quality = step.imageQuality, style = step.imageStyle)
        val saved = when (val drew = steps.draw(step.executionId, step.nodeKey, workspaceId, prompt, modelId, options)) {
            is StepDrawing.Drawn -> drew.picture
            is StepDrawing.Refused -> throw StepFailedException(step.nodeKey, "${step.name} could not draw: ${drew.reason}")
        }

        // The output the next node is handed: where the picture is and what it
        // is, so a later node can reference `{{input.<name>.url}}`. The run graph
        // shows the picture itself from the ExecutionPicture rows, not from this.
        //
        // Beside what reached this step, not instead of it. A picture is
        // something a run gains on its way past, and a reply after it usually
        // wants both - the agent's words to say and the picture to attach.
        // Replacing the payload left the picture as the only field there was,
        // so every reference the reply held read as one nothing produces.
        val id = requireNotNull(saved.id)
        val answer = mapper.createObjectNode()
            .put("id", id)
            .put("url", "${StepPictures.DOWNLOAD_PATH}/$id")
            .put("prompt", prompt)
            .put("contentType", saved.contentType)
        return StepResult(StepStatus.COMPLETED, expressions.alongsideJson(step.outputName, mapper.writeValueAsString(answer), input))
    }

    private companion object {
        /** The mapping that carries what to draw. */
        const val PROMPT = "prompt"
    }
}
