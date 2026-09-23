package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.ModelSpeechClient
import io.mszymanski.orknux.connector.model.Speech
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.PictureFilenames
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Saying something inside a run, and filing the audio against the step.
 *
 * Issue #264. The product spoke in a chat and nowhere else: a run that wanted to
 * hand somebody audio - a summary to listen to on the way in, a warning read out
 * over a phone bridge, a message for a channel where nobody reads - had no way
 * to make any. Every other kind of output a run produces is a file it leaves
 * behind, and this was the missing one.
 *
 * Written against the same pieces a drawn picture uses, deliberately: the same
 * [ModelSpeechClient] a chat reads an answer aloud with, the same attachment
 * storage, the same installation switch, and a row filed against the step the
 * way [StepPictures] files one. An installation that has already chosen a
 * speech model has nothing further to set up.
 *
 * **It produces the file and stops there.** Sending it is what the connection
 * actions already do, and a node that spoke *and* posted would be two decisions
 * in one - with no way to keep the audio without also sending it.
 */
@Service
class StepSpeech(
    private val workspaces: WorkspaceRepository,
    private val speaking: ModelSpeechClient,
    private val speeches: ExecutionSpeechRepository,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
) {

    /**
     * Says it, files it, and answers with the row.
     *
     * Every refusal is a sentence rather than an exception, the rule
     * [StepPictures] keeps: the step reports it and the run carries on down its
     * failure edge if it has one, instead of a stack trace reaching a page.
     *
     * @param modelId which model speaks it; null follows the workspace's own.
     * @param voice which voice, where the provider offers more than one.
     */
    fun speak(
        executionId: Long,
        nodeKey: String,
        workspaceId: Long,
        text: String,
        modelId: Long? = null,
        voice: String? = null,
    ): StepSpoken {
        if (!settings.attachmentsEnabled()) {
            return StepSpoken.Refused(
                "Speech is kept as an attachment, and attachments are turned off on this installation.",
            )
        }

        /*
         * The words before the model, which is the order these are asked in on
         * purpose. What is wrong with the text is local, cheap and the thing the
         * node's author is looking at; the model is a workspace setting the same
         * person may not own. A node with nothing to say never reaches the
         * lookup either, which is the cheapest question answered first.
         */
        val said = text.trim()
        if (said.isEmpty()) return StepSpoken.Refused("There is nothing to say: the text was empty.")
        if (said.length > MOST_TEXT) {
            return StepSpoken.Refused(
                "That is too long to read out in one go; say it in under $MOST_TEXT characters.",
            )
        }

        val speaksWith = modelId ?: modelFor(workspaceId) ?: return StepSpoken.Refused(
            "This workspace has no text-to-speech model. Somebody has to choose one on the workspace's " +
                "Chat settings before anything here can speak.",
        )

        /*
         * A ceiling, the one bound this adds, and the same argument the pictures
         * make: a run has many steps and a loop has many turns, so a graph that
         * has decided speaking is the answer can ask until somebody notices the
         * bill. Twenty is more than any run is, and the refusal is a sentence
         * the step reports rather than a failure that loses the run.
         */
        if (speeches.countByExecutionId(executionId) >= MOST_SPEECHES) {
            return StepSpoken.Refused(
                "This run has already spoken $MOST_SPEECHES times, which is as many as one run may. " +
                    "Finish with the audio you have.",
            )
        }

        val spoken = when (val answer = speaking.speak(speaksWith, said, voice)) {
            is Speech.Failed -> return StepSpoken.Refused(answer.reason)
            is Speech.Spoke -> answer
        }

        /*
         * Named after what it says, the way a picture is named after what it is
         * of - so a file saved to a desktop still says what it holds rather than
         * being a number. The same helper, because the rule is the same one.
         */
        val filename = PictureFilenames.of(said, spoken.contentType)
        val location = store.put(workspaceId, filename, spoken.audio)
        val saved = speeches.save(
            ExecutionSpeech(
                executionId = executionId,
                nodeKey = nodeKey,
                workspaceId = workspaceId,
                said = said,
                filename = filename,
                contentType = spoken.contentType,
                sizeBytes = spoken.audio.size.toLong(),
                location = location,
            ),
        )
        return StepSpoken.Spoke(saved, spoken.millis)
    }

    /** The workspace's own choice, which is the one a chat reads answers with. */
    private fun modelFor(workspaceId: Long): Long? =
        workspaces.findByIdOrNull(workspaceId)?.speechModelId

    companion object {

        /** As many as one run may say; see the note where it is spent. */
        const val MOST_SPEECHES = 20

        /**
         * As much as is worth reading out in one go.
         *
         * The providers take more than this, but a run that has generated four
         * thousand characters of speech has produced something nobody is going
         * to listen to - and it is billed by the character. A refusal that says
         * so is more use than a file nobody plays.
         */
        const val MOST_TEXT = 4000
    }
}

/** What one attempt to speak came to. */
sealed interface StepSpoken {

    data class Spoke(val speech: ExecutionSpeech, val millis: Long) : StepSpoken

    /** Said in words the step reports, rather than thrown; see `speak`. */
    data class Refused(val reason: String) : StepSpoken
}
