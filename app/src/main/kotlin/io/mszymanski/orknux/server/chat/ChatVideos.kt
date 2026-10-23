package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatParameters
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.VideoInput
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachments
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Whether the videos on a chat message may go to the model that will answer it.
 *
 * Asked before the message is written down and before the stream opens, so a
 * refusal is a status and a sentence rather than a turn that half happened: a
 * message the model cannot watch is not a message somebody wants recorded as
 * sent and then answered with an error. [ModelChatClient] refuses the same
 * call again at the wire for any other way a video could reach a model; this
 * is the one that says it in a way the browser can translate.
 */
@Service
class ChatVideos(
    private val chats: ChatService,
    private val models: LlmModelRepository,
    private val providers: ModelProviderRepository,
    private val settings: InstallationSettings,
) {

    /**
     * Throws where [attached] holds a video [session]'s model may not be sent,
     * or one over the limit. Says nothing where there is no video, and nothing
     * where there is no model - the send refuses that itself, in its own words.
     */
    fun refuseUnwatchable(session: ChatSession, attached: List<ChatAttachment>) {
        val videos = attached.filter { ChatAttachments.isVideo(it.contentType) }
        if (videos.isEmpty()) return
        val model = chats.modelAnswering(session)?.let { models.findByIdOrNull(it) } ?: return
        val provider = providers.findByIdOrNull(model.providerId) ?: return

        if (!ChatParameters.takesVideo(provider, model)) {
            if (VideoInput.carried(provider, model)) throw ChatVideoNotTakenException(model.name)
            throw ChatVideoNotCarriedException(model.name, provider.name)
        }

        val limit = settings.chatVideoMaxMb()
        videos.firstOrNull { it.sizeBytes > limit * MEGABYTE }?.let { video ->
            throw ChatVideoTooLargeException(video.filename, (video.sizeBytes + MEGABYTE - 1) / MEGABYTE, limit)
        }
    }

    private companion object {
        const val MEGABYTE = 1024L * 1024L
    }
}
