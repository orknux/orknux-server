package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.connector.model.ModelImageClient
import io.mszymanski.orknux.connector.model.Picture
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.PictureFilenames
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * A chat drawing a picture, and where that picture goes.
 *
 * Issue #294. This is the filing half of what `drawChatPicture` used to be, and
 * it is deliberately the same filing: attachment storage, as a [ChatAttachment]
 * on this chat, under this workspace, behind the same installation switch the
 * upload button obeys — and then the exchange written into the thread as a
 * markdown image pointing at `/api/attachments/{id}`. That last part is the
 * whole reason a drawn picture survives being drawn: the thread is what a chat
 * is read back out of, so there is no second record of which message carried
 * which file to keep in step, and nothing had to be added to `ChatMessageView`
 * for the screen to draw it.
 *
 * **What changed is only who asks.** The mutation was a person pressing a button
 * beside the composer and retyping their description into it; this is the agent
 * answering the conversation, which is where the request was made in the first
 * place. So the description is the model's rather than a person's, and that is
 * the one thing this may not file the way the button did — see [draw].
 *
 * A picture whose bytes have since been removed is a link that 404s, and the
 * interface's markdown renders that as one line saying so. Left to itself the
 * browser would draw a broken-image icon, which says the page is broken rather
 * than that the file is gone. Nothing here changes that, and nothing about it
 * depends on how the picture came to be drawn: every picture any chat has ever
 * drawn is one of these rows with one of these lines pointing at it.
 */
@Service
class ChatPictures(
    private val chats: ChatService,
    private val workspaces: WorkspaceRepository,
    private val drawing: ModelImageClient,
    private val attachments: ChatAttachmentRepository,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    /**
     * Whether this chat can draw at all, which decides whether the tool is
     * offered.
     *
     * Asked before the model is told about the tool rather than answered when it
     * calls one, which is the rule `AgentTools` states for every tool in this
     * application: a model is only ever offered tools that will run, and one
     * declared but not implemented is a model told it can do something it
     * cannot — it will believe you, and it will spend a turn finding out.
     *
     * Two things have to be true, and they are the two `TaskPictures.offered`
     * asks. The workspace has chosen a model that draws, which is the setting
     * the picture button used to obey and is now the only thing that turns this
     * on. And the installation allows attachments, because a drawn picture is
     * kept as one and there would otherwise be nowhere to put it.
     */
    fun offered(chat: ChatSession): Boolean =
        settings.attachmentsEnabled() && modelFor(chat) != null

    /**
     * Draws one, files it, and puts it in the chat.
     *
     * The bytes go down before the row, for the reason `ChatPictureAPI` gave: a
     * row pointing at a file that was never written is an attachment nobody can
     * open and nothing can tell apart from one whose file has been deleted,
     * while a file with no row is a wasted block and nothing worse.
     *
     * **Only the picture is written into the thread, not the description.** The
     * button wrote both, and it was right to: somebody had typed the
     * description, so it was a turn they had taken. Here the description is the
     * model's own, and writing it as a user turn would put words in a person's
     * mouth — and then feed them back to the model on the next send as though
     * they had said them. What goes in is the one line that is true: an
     * assistant turn holding the picture.
     *
     * Written the moment it is drawn rather than left for the model to place in
     * its answer, which is the argument `TaskPictures` makes and it holds here:
     * a model asked to repeat a link is a model that will one day repeat it
     * wrongly, or not at all, and leave a picture that was paid for where nobody
     * will find it. A round that then fails leaves the picture in the chat,
     * which is the right way round — it was drawn, and it was charged for.
     *
     * No transaction is held across the provider call. That is [ChatService.ask]'s
     * rule and this runs from inside a round it opened nothing for: a call that
     * takes half a minute must not hold a connection, or on SQLite the one write
     * lock there is. The two writes either side are each their own.
     *
     * @param options size and quality where the agent asked for them, already
     *   held to what the model takes by `DrawToolParameters`. Left out, the
     *   model draws at its own defaults, which is what every call did before
     *   the tool took a size.
     */
    fun draw(chat: ChatSession, prompt: String, options: ImageOptions = ImageOptions.NONE): ChatDrawing {
        if (!settings.attachmentsEnabled()) {
            return ChatDrawing.Refused(
                "A drawn picture is kept as an attachment, and attachments are turned off for this installation.",
            )
        }

        val modelId = modelFor(chat) ?: return ChatDrawing.Refused(
            "This workspace has no image model. Somebody has to choose one on the workspace's Chat settings " +
                "before anything here can draw.",
        )

        val asked = prompt.trim()
        if (asked.isEmpty()) return ChatDrawing.Refused("There is nothing to draw: say what the picture should be of.")
        if (asked.length > MOST_PROMPT) {
            return ChatDrawing.Refused("That description is too long to draw from; say it in under $MOST_PROMPT characters.")
        }

        val drawn = when (val picture = drawing.draw(modelId, asked, options)) {
            is Picture.Failed -> return ChatDrawing.Refused(picture.reason)
            is Picture.Drawn -> picture
        }

        val filename = PictureFilenames.of(asked, drawn.contentType)
        val location = store.put(chat.workspaceId, filename, drawn.image)
        val saved = attachments.save(
            ChatAttachment(
                workspaceId = chat.workspaceId,
                chatSessionId = requireNotNull(chat.id),
                filename = filename,
                contentType = drawn.contentType,
                sizeBytes = drawn.image.size.toLong(),
                location = location,
                // Whose chat it is. The download checks the chat rather than the
                // uploader for a file that has one, but this keeps the row
                // honest about who caused it - and an agent drawing inside
                // somebody's conversation caused it on their behalf.
                uploadedBy = chat.userId,
            ),
        )

        val said = "![${alt(asked)}](/api/attachments/${requireNotNull(saved.id)})"
        chats.recordPicture(requireNotNull(chat.id), said)

        /*
         * Audited, and audited exactly as the button was.
         *
         * The entry answers "what happened in this workspace", and what happened
         * is unchanged: this installation's image model was called and somebody's
         * workspace was charged for a picture. Dropping the entry because a model
         * rather than a person decided would make the log go quiet about
         * drawings that still happen and still cost money - and the one thing
         * worth saying differently is who decided, which is what the sentence
         * says. The recorder redacts on the way in, so nothing here has to.
         */
        auditRecorder.record(chat.workspaceId, WorkspaceAuditCategory.CHAT, "An agent drew a picture in a chat")

        return ChatDrawing.Drawn(requireNotNull(saved.id), said, drawn.millis)
    }

    /**
     * What this chat draws with: whatever its workspace chose, or nothing.
     *
     * Public because the tool's descriptor is the model's - which sizes it
     * draws, whether it takes a quality - and the shed has to know which model
     * that is before the round starts.
     */
    fun modelFor(chat: ChatSession): Long? =
        workspaces.findByIdOrNull(chat.workspaceId)?.imageModelId

    /**
     * As much of the description as belongs in one image's alt text.
     *
     * Brackets and line breaks are taken out rather than escaped, which is
     * `TaskPictures`' answer and the same problem: what is being built is a
     * markdown link and the description is a model's prose, so a `]` in it would
     * close the alt text early and leave the rest of the sentence standing in
     * the chat as text — the sort of thing that reads as a bug in the page
     * rather than as a bracket in a prompt.
     */
    private fun alt(prompt: String): String = prompt
        .replace(Regex("[\\[\\]\\r\\n]"), " ")
        .replace(Regex(" +"), " ")
        .trim()
        .take(ALT_LENGTH)

    private companion object {
        /**
         * As long a description as is worth sending, the same figure the task's
         * door uses: these providers cap the prompt themselves — around a
         * thousand characters for the older models — and refusing here says so
         * in words, where a provider's own refusal arrives as a 400 about a
         * field name.
         */
        const val MOST_PROMPT = 2_000

        /** As much of the description as belongs in the alt text of one image. */
        const val ALT_LENGTH = 120
    }
}

/** What came of asking for a picture in a chat: one that was drawn and filed, or why not. */
sealed interface ChatDrawing {

    /**
     * @param attachmentId the [ChatAttachment] the bytes were filed as.
     * @param said the line written into the thread: a markdown image.
     */
    data class Drawn(val attachmentId: Long, val said: String, val millis: Long) : ChatDrawing

    /**
     * Why nothing was drawn, in words the model is handed.
     *
     * A sentence rather than a category, because the reasons are not alike: a
     * workspace that has chosen no model, a provider that refused the
     * description, an installation with attachments off and a request that ran
     * out of time all arrive here, and only the sentence tells the agent which
     * of them it can do something about.
     */
    data class Refused(val reason: String) : ChatDrawing
}
