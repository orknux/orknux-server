package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.connector.model.ModelImageClient
import io.mszymanski.orknux.connector.model.Picture
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.PictureFilenames
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.util.Base64

/**
 * Drawing a picture inside a run, and filing it against the step that drew it.
 *
 * The fourth door onto the same feature and deliberately not a fourth
 * implementation of it: the same [ModelImageClient], the same attachment
 * storage, the same installation switch, and the same [ExecutionPicture] row an
 * image node writes. [ImageNodeRunner] draws from a mapping somebody wrote on
 * the node; this exists because an agent inside a run sometimes decides what
 * the picture should be of while it is working, and until now the only way to
 * let it was to put an image node after it with a fixed prompt - which is a
 * graph that draws whether or not there was anything to draw.
 *
 * **Filed against the step, so nothing depends on the model repeating a link.**
 * That is the lesson `TaskPictures` writes down at length: a model asked to
 * paste a URL into its answer will one day paste it wrongly or not at all, and
 * a picture nobody can find was still paid for. The row is what the run graph
 * reads, so a drawing survives an agent that forgets to mention it.
 */
@Service
class StepPictures(
    private val workspaces: WorkspaceRepository,
    /** Where this installation is; see [base]. */
    private val web: io.mszymanski.orknux.server.security.WebProperties,
    private val drawing: ModelImageClient,
    private val pictures: ExecutionPictureRepository,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
) {

    /**
     * Whether this workspace can draw at all, which decides whether the tool is
     * offered.
     *
     * Asked before the model is told about the tool rather than answered when
     * it calls one, which is the rule `AgentTools` states for every tool here:
     * a model is only ever offered tools that will run, and one declared but
     * not implemented is a model told it can do something it cannot - it will
     * believe you, and it will spend a turn finding out.
     */
    fun offered(workspaceId: Long): Boolean =
        settings.attachmentsEnabled() && modelFor(workspaceId) != null

    /**
     * What one run has drawn, oldest first.
     *
     * The order the graph shows them in, and the list a key is resolved
     * against: a key is a row id, and a model that guessed a number should be
     * told there is nothing under it rather than handed another run's picture.
     */
    fun of(executionId: Long): List<ExecutionPicture> =
        pictures.findByExecutionIdOrderByDrawnAtAscIdAsc(executionId)

    /** What a run draws with: whatever its workspace chose, or nothing. */
    fun modelFor(workspaceId: Long): Long? =
        workspaces.findByIdOrNull(workspaceId)?.imageModelId

    /**
     * Draws one and files it against this step of this run.
     *
     * The bytes go down before the row, the order every picture in this
     * application is filed in: a row pointing at a file that was never written
     * is an attachment nothing can open and nothing can tell apart from one
     * whose file has been deleted, while a file with no row is a wasted block
     * and nothing worse.
     *
     * @param modelId what to draw with, where the caller has a say - an image
     *   node names its own model. Left out, the workspace's choice is used,
     *   which is what an agent's tool has.
     * @param options size, quality and style, where the caller has a say - again
     *   the image node's. Left out, the model draws at its own defaults, which
     *   is what an agent's tool asks for.
     */
    fun draw(
        executionId: Long,
        nodeKey: String,
        workspaceId: Long,
        prompt: String,
        modelId: Long? = null,
        options: ImageOptions = ImageOptions.NONE,
    ): StepDrawing {
        if (!settings.attachmentsEnabled()) {
            return StepDrawing.Refused(
                "A drawn picture is kept as an attachment, and attachments are turned off on this installation.",
            )
        }

        val drawsWith = modelId ?: modelFor(workspaceId) ?: return StepDrawing.Refused(
            "This workspace has no image model. Somebody has to choose one on the workspace's Chat settings " +
                "before anything here can draw.",
        )

        val asked = prompt.trim()
        if (asked.isEmpty()) return StepDrawing.Refused("There is nothing to draw: say what the picture should be of.")
        if (asked.length > MOST_PROMPT) {
            return StepDrawing.Refused(
                "That description is too long to draw from; say it in under $MOST_PROMPT characters.",
            )
        }

        /*
         * A ceiling, and it is the one bound this adds.
         *
         * The same argument `TaskPictures` makes: a turn's tool loop runs
         * several rounds and a run has many steps, so an agent that has decided
         * drawing is the answer can ask until somebody notices the bill. Twenty
         * is more pictures than any run is, and the refusal is a sentence the
         * model reads and works around rather than a failure that loses the run.
         */
        if (pictures.countByExecutionId(executionId) >= MOST_PICTURES) {
            return StepDrawing.Refused(
                "This run has already drawn $MOST_PICTURES pictures, which is as many as one run may draw. " +
                    "Finish with the ones you have.",
            )
        }

        val drawn = when (val picture = drawing.draw(drawsWith, asked, options)) {
            is Picture.Failed -> return StepDrawing.Refused(picture.reason)
            is Picture.Drawn -> picture
        }

        val filename = PictureFilenames.of(asked, drawn.contentType)
        val location = store.put(workspaceId, filename, drawn.image)
        val saved = pictures.save(
            ExecutionPicture(
                executionId = executionId,
                nodeKey = nodeKey,
                workspaceId = workspaceId,
                prompt = asked,
                filename = filename,
                contentType = drawn.contentType,
                sizeBytes = drawn.image.size.toLong(),
                location = location,
            ),
        )
        return StepDrawing.Drawn(saved, drawn.millis, Base64.getEncoder().encodeToString(drawn.image))
    }

    /**
     * A markdown image pointing at the picture.
     *
     * The same line a chat writes into its thread and a task hands its agent,
     * at this feature's own endpoint, and markdown for the same reason:
     * whatever renders what an agent writes already renders this, so nothing
     * downstream has to be taught a new field.
     */
    fun linkTo(picture: ExecutionPicture): String =
        "![" + alt(picture.prompt) + "](" + urlOf(requireNotNull(picture.id)) + ")"

    /** Where a picture is, absolutely; see [base] for why that matters. */
    fun urlOf(id: Long): String = base() + DOWNLOAD_PATH + "/" + id

    /**
     * Where this installation is, for a link somebody else's client has to
     * resolve.
     *
     * A model is handed this markdown and pastes what it was given. Slack has
     * no document to resolve a path against, so `![alt](/api/…)` became
     * `<…|alt>` on the way through the mrkdwn conversion and arrived as that
     * construction, printed: angle brackets, a pipe, and an image description
     * standing in for link text. Nothing downstream could mend it - the host
     * was missing and cannot be invented.
     *
     * The same base the issue mail and the password reset write their links
     * from. Those write no link at all where it is blank, because a mail with
     * a broken link is worse than a mail without one; a picture is the other
     * way round - the run's own page is where somebody would look, so this
     * falls back to the development address rather than handing back a link to
     * nowhere.
     */
    private fun base(): String =
        web.baseUrl.trim().trimEnd('/').ifEmpty { "http://localhost:5173" }

    /**
     * As much of the description as belongs in one image's alt text.
     *
     * Brackets and line breaks are taken out rather than escaped: what is being
     * built is a markdown link out of a model's prose, and a `]` in it would
     * close the alt text early and leave the rest of the sentence standing in
     * the answer as text.
     */
    private fun alt(prompt: String): String = prompt
        .replace(Regex("[\\[\\]\\r\\n]"), " ")
        .replace(Regex(" +"), " ")
        .trim()
        .take(ALT_LENGTH)

    companion object {
        /** Where the bytes are served; see ExecutionPictureAPI. */
        const val DOWNLOAD_PATH = "/api/execution-pictures"

        /** As many pictures as one run may draw. See [draw] for why there is a number at all. */
        const val MOST_PICTURES = 20

        /**
         * As long a description as is worth sending, the same figure the chat's
         * door and a task's tool use: these providers cap the prompt
         * themselves, and refusing here says so in words, where a provider's own
         * refusal arrives as a 400 about a field name.
         */
        const val MOST_PROMPT = 2_000

        /** As much of the description as belongs in the alt text of one image. */
        const val ALT_LENGTH = 120
    }
}

/** What came of asking a run for a picture: one that was drawn and filed, or why not. */
sealed interface StepDrawing {

    /**
     * @param base64 the picture itself, which the caller needs for something
     *   the row cannot give it: putting the bytes where a plugin can read
     *   them. It is held rather than read back from storage because it is
     *   already in hand at the moment it is filed, and reading a megabyte off
     *   the disk to hand back what was just written is work for nothing.
     */
    data class Drawn(
        val picture: ExecutionPicture,
        val millis: Long,
        val base64: String,
    ) : StepDrawing

    /**
     * Why nothing was drawn, in words the model is handed.
     *
     * A sentence rather than a category, because the reasons are not alike: a
     * workspace that has chosen no model, a provider that refused the
     * description, and an installation with attachments off all arrive here, and
     * only the sentence tells the agent which of them it can do something about.
     */
    data class Refused(val reason: String) : StepDrawing
}
