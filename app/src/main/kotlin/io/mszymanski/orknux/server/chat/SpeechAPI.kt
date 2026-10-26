package io.mszymanski.orknux.server.chat

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelSpeechClient
import io.mszymanski.orknux.connector.model.Speech
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Reads text aloud, so an answer can be listened to rather than read.
 *
 * The mirror of [TranscriptionAPI], scoped and refused the same way. REST
 * rather than GraphQL for the reason that one is: what comes back is audio, and
 * a JSON field would mean base64 for bytes the browser is about to play.
 *
 * The text is sent rather than named by message id, which is deliberate. An
 * answer that has just finished streaming is on screen before it has an id the
 * interface knows, and a speaker that worked on yesterday's messages but not on
 * the one somebody just read would be the wrong way round.
 */
@RestController
class SpeechAPI(
    private val workspaces: WorkspaceRepository,
    private val models: LlmModelRepository,
    private val speaker: ModelSpeechClient,
    private val access: WorkspaceAccess,
    private val tuning: Tuning,
) {

    @PostMapping("/api/workspaces/{workspaceId}/speech")
    fun speak(
        @PathVariable workspaceId: Long,
        @RequestBody said: SpeechRequest,
    ): ResponseEntity<Any> {
        val workspace = access.requireVisible(workspaceId)

        val modelId = workspace.speechModelId
            ?: return refuse(HttpStatus.CONFLICT, "This workspace has no speech model.")

        val text = said.text.trim()
        if (text.isEmpty()) return refuse(HttpStatus.BAD_REQUEST, "There is nothing to read.")
        if (text.length > MAX_CHARS) {
            return refuse(HttpStatus.PAYLOAD_TOO_LARGE, "That answer is too long to read in one piece.")
        }

        val model = models.findByIdOrNull(modelId)

        /*
         * The lines this reader is not to be given, where it has been said so.
         *
         * Done here rather than in the browser because it is a fact about the
         * reader, not about the answer: the same text sent to two speech models
         * wants this for one and not the other, and the piece has already been
         * decided by then. It cannot move a cut either - under paragraph
         * chunking the cuts are made *on* these lines, before any of this.
         */
        /*
         * Never empty, whatever comes out: `text` is trimmed and was refused
         * above if it was blank, so something on it is not whitespace and the
         * line holding it survives.
         */
        val reading = if (model?.skipEmptyLines == true) withoutEmptyLines(text) else text

        return when (val spoken = speaker.speak(modelId, reading, model?.voice, speedOf(said.speed))) {
            is Speech.Spoke -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(spoken.contentType))
                .header("Content-Length", spoken.audio.size.toString())
                // Listened to once, and never worth a second copy on disk.
                .header("Cache-Control", "no-store")
                .body(spoken.audio)

            // Said plainly: whoever pressed the speaker is the person who can
            // fix the model behind it.
            is Speech.Failed -> refuse(HttpStatus.BAD_GATEWAY, spoken.reason)
        }
    }

    /**
     * The same text with its empty lines gone.
     *
     * Lines that are blank or nothing but spaces come out; the rest keep their
     * own order and their own line breaks, because a line break is where a
     * reader draws breath and taking those out too would run a list together
     * into one sentence.
     *
     * Trailing spaces go with them, so a line of two spaces is empty here in
     * the way it looks empty on the screen rather than in the way a byte count
     * would have it.
     */
    private fun withoutEmptyLines(text: String): String = text
        .lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()

    /**
     * How fast to read: the person's choice, or the installation's where they
     * made none, held inside what the speech API accepts. Bounded rather than
     * refused, because a speed out of range is a slider somebody pushed too
     * far and the answer is still worth hearing.
     */
    private fun speedOf(asked: Double?): Double {
        val speed = asked?.takeIf { it.isFinite() } ?: (tuning[Tunable.SPEECH_SPEED_PERCENT] / 100.0)
        return speed.coerceIn(SLOWEST, FASTEST)
    }

    private fun refuse(status: HttpStatus, says: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(mapOf("error" to says))

    private companion object {
        /**
         * As much as can actually be read before the request gives up.
         *
         * Measured against a local reader: 1,500 characters took about 17
         * seconds to synthesise, so the 120-second request timeout is reached
         * somewhere near 10,000. A cap above that is not a cap — it is a
         * promise the timeout breaks first, and a timeout says nothing about
         * why. Under it, an answer too long to read says so.
         *
         * Long answers are exactly the ones somebody wants read to them, so
         * this is as generous as it can be and still be true.
         */
        const val MAX_CHARS = 8_000

        /** The range OpenAI's `/audio/speech` accepts for `speed`; a format, not a choice. */
        const val SLOWEST = 0.25
        const val FASTEST = 4.0
    }
}

/**
 * What to read.
 *
 * Annotated the way every other request body here is, and with no default on
 * the property. A default makes Kotlin emit a second, no-argument constructor,
 * and Jackson then finds two creators annotated the same way and refuses the
 * type outright — every request answering 500 rather than the text arriving.
 * Before that it was quieter and worse: it built through the no-argument path
 * and left `text` empty, so a perfectly good request came back as "there is
 * nothing to read".
 */
data class SpeechRequest @JsonCreator constructor(
    @JsonProperty("text") val text: String,
    /**
     * How fast to read it, 1.0 being the reader's own pace; null where the
     * person has not chosen. A default on this one alone emits no no-argument
     * constructor - only a synthetic one Jackson does not look at.
     */
    @JsonProperty("speed") val speed: Double? = null,
)
