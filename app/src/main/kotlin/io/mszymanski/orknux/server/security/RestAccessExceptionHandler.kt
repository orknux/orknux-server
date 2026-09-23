package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.attachment.AttachmentNotFoundException
import io.mszymanski.orknux.server.attachment.AttachmentTooLargeException
import io.mszymanski.orknux.server.attachment.AttachmentsDisabledException
import io.mszymanski.orknux.server.task.TaskPictureNotFoundException
import io.mszymanski.orknux.server.workflow.ExecutionPictureNotFoundException
import io.mszymanski.orknux.server.workflow.ExecutionSpeechNotFoundException
import io.mszymanski.orknux.server.workflow.SavedArtifactNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * What the REST endpoints answer when access says no.
 *
 * The GraphQL side has resolvers for these; the REST side had nothing, so every
 * one of them arrived as a 500 with a stack trace in the log and
 * "Internal Server Error" on the wire. Asking about a workspace that does not
 * exist is not the server breaking — it is the answer.
 *
 * It read as a 401 until recently, which was worse: the error page is a second
 * dispatch, and the security chain was refusing it. That is fixed in
 * [SecurityConfig]; this is the other half, and without it the honest status is
 * an honest 500.
 *
 * Attachments, transcription, speech, the quick chat and the MCP endpoint all
 * throw these. One place to answer them, rather than a try/catch in each.
 */
@RestControllerAdvice
class RestAccessExceptionHandler {

    /**
     * Absent and forbidden are the same answer.
     *
     * A workspace somebody may not see is reported exactly as one that is not
     * there, the way it is everywhere else here: "you may not see this" confirms
     * that this is somebody's, which is a fact worth not handing out.
     *
     * An attachment is answered the same way and for the same reason - a file
     * on a chat that is not the caller's is refused as one that is not there -
     * and it was arriving as a 500, which says the server broke when what
     * happened is that somebody asked for a document of somebody else's.
     *
     * A picture a task drew is here too, and there the status is what the page
     * reads: the outcome's markdown asks for it in an `<img>`, and a 404 is
     * what the interface turns into one line saying the picture is gone rather
     * than into the browser's broken-image icon.
     */
    @ExceptionHandler(
        WorkspaceNotFoundException::class,
        WorkspaceForbiddenException::class,
        AttachmentNotFoundException::class,
        TaskPictureNotFoundException::class,
        ExecutionPictureNotFoundException::class,
        ExecutionSpeechNotFoundException::class,
        SavedArtifactNotFoundException::class,
    )
    fun notFound(failure: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(mapOf("error" to (failure.message ?: "That is not here")))

    @ExceptionHandler(AdminRequiredException::class, WorkspaceAdminRequiredException::class)
    fun forbidden(failure: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(mapOf("error" to (failure.message ?: "That requires the administrator role")))

    /**
     * Attachments turned off, and files too big for this installation.
     *
     * Both are answers rather than faults, and both used to arrive as a 500 with
     * "Internal Server Error" on the wire - which reads as the server having
     * broken when what happened is that somebody was told no. The GraphQL side
     * has said these in a sentence since they existed; this is the upload's
     * half, and the upload is where they are actually raised.
     *
     * Bad request rather than forbidden for the switch, to match what the
     * GraphQL resolver answers for the same exception: one refusal should not
     * have two statuses depending on which door it came through.
     */
    @ExceptionHandler(AttachmentsDisabledException::class)
    fun attachmentsOff(failure: AttachmentsDisabledException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to (failure.message ?: "Attachments are turned off for this installation")))

    @ExceptionHandler(AttachmentTooLargeException::class)
    fun tooLarge(failure: AttachmentTooLargeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(mapOf("error" to (failure.message ?: "That file is larger than this installation allows")))
}
