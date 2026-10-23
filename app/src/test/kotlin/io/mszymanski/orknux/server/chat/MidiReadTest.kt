package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.AttachmentTypes
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.attachment.ChatAttachmentShelf
import io.mszymanski.orknux.server.embedded.MidiCapability
import io.mszymanski.orknux.server.llm.LlmSession
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage

/**
 * A MIDI file attached to a chat: no model hears one, so it is read. The shelf
 * keeps it as `audio/midi` - whatever the browser called it - and names
 * midi_read in the note, and midi_read answers the tracks, tempo, time
 * signature and every note as text, as many notes as Admin Settings allows.
 *
 * The file is made here with `javax.sound.midi` and written as a standard MIDI
 * file, so what is read is what a sequencer would have saved.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-midi-read"])
class MidiReadTest(
    @Autowired val rows: ChatAttachmentRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val shelf: ChatAttachmentShelf,
    @Autowired val midi: MidiCapability,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val tunables: Tunables,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId = 0L

    @BeforeEach
    fun workspace() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "midi-read-${System.nanoTime()}")).id)
    }

    @AfterEach
    fun limit() = tunables.set(Tunable.MIDI_READ_NOTES, null, "alice")

    @Test
    fun `an attached mid is kept as MIDI and read as its notes`() {
        val session = requireNotNull(sessions.save(LlmSession(workspaceId = workspaceId, sessionKey = "midi-${System.nanoTime()}")).id)
        val bytes = song()
        val file = rows.save(
            ChatAttachment(
                workspaceId = workspaceId,
                filename = "tune.mid",
                // What a browser that does not know .mid sends; the upload's rule makes it MIDI.
                contentType = AttachmentTypes.of("tune.mid", "application/octet-stream"),
                sizeBytes = bytes.size.toLong(),
                location = store.put(workspaceId, "tune.mid", bytes),
                uploadedBy = "alice",
            ),
        )
        assertThat(file.contentType).isEqualTo("audio/midi")

        val note = requireNotNull(shelf.shelve(session, listOf(file)))
        val key = "${ChatAttachmentShelf.KEY_PREFIX}${file.id}"
        assertThat(note).contains(key).contains("midi_read {\"key\": \"$key\"}")

        val read = mapper.readTree(midi.run(MidiCapability.READ, """{"key":"$key"}""", workspaceId, session))
        val text = read.path("midi").stringValue()
        assertThat(text).startsWith("MIDI format 1, 2 tracks, 480 ticks per beat")
        assertThat(text).contains("Tempo: 120 BPM at beat 0; 60 BPM at beat 2.")
        assertThat(text).contains("Time signature: 3/4 at beat 0.")
        assertThat(text).contains("Track 2 \"Melody\" - channel 1, program 0, 3 notes:")
        // Middle C for a beat at 120, E for a beat at 120, then G for two beats at 60: a second, then two.
        assertThat(text).contains("C4, 0, 1, 0, 500, 100")
        assertThat(text).contains("E4, 1, 1, 500, 500, 90")
        assertThat(text).contains("G4, 2, 2, 1000, 2000, 80")
        assertThat(read.path("notes").intValue()).isEqualTo(3)
        assertThat(read.path("notesListed").intValue()).isEqualTo(3)
    }

    @Test
    fun `past the limit the notes stop being listed and the rest are counted`() {
        tunables.set(Tunable.MIDI_READ_NOTES, 10, "alice")
        val sequence = Sequence(Sequence.PPQ, 480)
        val track = sequence.createTrack()
        repeat(15) { i -> note(track, 0, 60 + i, 90, i * 480L, 480L) }

        val read = midi.read(sequence, 1, tunables[Tunable.MIDI_READ_NOTES])

        assertThat(read.notes).isEqualTo(15)
        assertThat(read.listed).isEqualTo(10)
        assertThat(read.text).contains("... and 5 more in this track, not listed: the limit is 10 notes.")
        assertThat(read.text).contains("A4, 9, 1").doesNotContain("A#4, 10, 1")
    }

    @Test
    fun `a key that holds no MIDI is refused by name`() {
        val session = requireNotNull(sessions.save(LlmSession(workspaceId = workspaceId, sessionKey = "midi-${System.nanoTime()}")).id)
        val error = mapper.readTree(midi.run(MidiCapability.READ, """{"key":"nothing.here"}""", workspaceId, session)).path("error").stringValue()
        assertThat(error).contains("nothing.here")
    }

    /** Two tracks: the tempo map and metre, and a melody of three notes with a slowdown before the last. */
    private fun song(): ByteArray {
        val sequence = Sequence(Sequence.PPQ, 480)
        val conductor = sequence.createTrack()
        conductor.add(MidiEvent(MetaMessage(0x51, byteArrayOf(0x07, 0xA1.toByte(), 0x20), 3), 0))
        conductor.add(MidiEvent(MetaMessage(0x58, byteArrayOf(3, 2, 24, 8), 4), 0))
        conductor.add(MidiEvent(MetaMessage(0x51, byteArrayOf(0x0F, 0x42, 0x40), 3), 960))

        val melody = sequence.createTrack()
        val name = "Melody".toByteArray(Charsets.ISO_8859_1)
        melody.add(MidiEvent(MetaMessage(0x03, name, name.size), 0))
        melody.add(MidiEvent(ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 0, 0), 0))
        note(melody, 0, 60, 100, 0, 480)
        note(melody, 0, 64, 90, 480, 480)
        note(melody, 0, 67, 80, 960, 960)

        return ByteArrayOutputStream().also { MidiSystem.write(sequence, 1, it) }.toByteArray()
    }

    private fun note(track: javax.sound.midi.Track, channel: Int, pitch: Int, velocity: Int, start: Long, length: Long) {
        track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, channel, pitch, velocity), start))
        track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), start + length))
    }
}
