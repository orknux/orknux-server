package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import javax.sound.midi.Track

/**
 * Reading a MIDI file, for a model that cannot hear one.
 *
 * No model takes MIDI as input: it is not sound but a list of instructions for
 * making some, and the audio part every provider has is for sound. So an
 * attached `.mid` is read rather than sent - the format, the tracks, the tempo
 * and time signature, and every note with its pitch, when it starts and how
 * long it lasts, in beats and in milliseconds - as text a model reads the way
 * a musician reads a score.
 *
 * Parsed by the JDK's own `javax.sound.midi` ([MidiSystem.getSequence]), which
 * reads standard MIDI files of all three formats; nothing here decodes a byte
 * of the file itself. How many notes are listed is Admin Settings' to say
 * ([Tunable.MIDI_READ_NOTES]), because every one is a line a model pays for.
 */
@Component
class MidiCapability(
    private val scratch: LlmSessionStore,
    private val mapper: ObjectMapper,
    private val tuning: Tuning,
) : EmbeddedCapability {

    override val key = "midi"
    override val name = "MIDI"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = READ,
            summary = "Reads a MIDI file kept under a key: its tracks, tempo and notes.",
            description = "Reads a MIDI file (.mid) kept under a $KEY in this session - an attached one - and answers it " +
                "as text: the file's format, its tracks, tempo and time signature changes, and every note per track " +
                "with its pitch (C4 is middle C), start and length in beats and milliseconds, and velocity.",
            params = listOf(EmbeddedParam(KEY, ValueType.STRING, "The contentKey of the MIDI file.", required = true)),
            reads = StoredReader(MIDI_TYPES, KEY),
        ),
    )

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        if (name != READ) return refusal("There is no tool called ${key}_$name.")
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull() ?: return refusal("That is not valid JSON.")
        val named = asked.path(KEY).takeIf { it.isTextual }?.stringValue()?.trim()?.ifEmpty { null }
            ?: return refusal("Give the $KEY of a MIDI file.")
        val bytes = held(named, sessionId) ?: return refusal("Nothing in this session is kept under \"$named\".")
        val sequence = runCatching { MidiSystem.getSequence(ByteArrayInputStream(bytes)) }.getOrElse {
            val kind = sessionId?.let { scratch.kindOf(it, named)?.contentType }
            return refusal("\"$named\" is not a MIDI file${kind?.let { " (it holds $it)" }.orEmpty()}.")
        }
        val format = runCatching { MidiSystem.getMidiFileFormat(ByteArrayInputStream(bytes)).type }.getOrNull()
        val read = read(sequence, format, tuning[Tunable.MIDI_READ_NOTES])
        return mapper.writeValueAsString(
            linkedMapOf(
                "midi" to read.text,
                "tracks" to sequence.tracks.size,
                "notes" to read.notes,
                "notesListed" to read.listed,
            ),
        )
    }

    /** What [read] made of a file: the text, and how many notes there were and were listed. */
    data class Read(val text: String, val notes: Int, val listed: Int)

    /** One note, as heard: from its note-on to its note-off. */
    private data class Note(val channel: Int, val pitch: Int, val velocity: Int, val start: Long, val end: Long)

    /** A file as text, at most [most] notes of it listed. */
    internal fun read(sequence: Sequence, format: Int?, most: Int): Read {
        val clock = Clock(sequence)
        val lines = mutableListOf<String>()
        val beats = clock.beats(sequence.tickLength)
        lines += buildString {
            append("MIDI")
            format?.let { append(" format $it") }
            append(", ${sequence.tracks.size} track${if (sequence.tracks.size == 1) "" else "s"}")
            if (clock.ppq) append(", ${sequence.resolution} ticks per beat")
            beats?.let { append(", ${number(it)} beats") }
            append(" (${number(sequence.microsecondLength / 1000.0)} ms).")
        }

        val tempos = clock.changes.map { (tick, micros) -> "${number(60_000_000.0 / micros)} BPM at ${at(clock, tick)}" }
        lines += "Tempo: " + (tempos.ifEmpty { listOf("120 BPM (none given)") }.joinToString("; ")) + "."
        val metres = metas(sequence, TIME_SIGNATURE).mapNotNull { (tick, data) ->
            if (data.size < 2) null else "${data[0].toInt() and 0xff}/${1 shl (data[1].toInt() and 0xff)} at ${at(clock, tick)}"
        }
        if (metres.isNotEmpty()) lines += "Time signature: ${metres.joinToString("; ")}."
        val keys = metas(sequence, KEY_SIGNATURE).mapNotNull { (tick, data) -> keyOf(data)?.let { "$it at ${at(clock, tick)}" } }
        if (keys.isNotEmpty()) lines += "Key signature: ${keys.joinToString("; ")}."

        var total = 0
        var listed = 0
        sequence.tracks.forEachIndexed { index, track ->
            val notes = notesOf(track)
            total += notes.size
            val trackName = metaOf(track, TRACK_NAME)?.let { " \"${String(it, Charsets.ISO_8859_1).trim()}\"" }.orEmpty()
            val channels = notes.map { it.channel + 1 }.distinct().sorted()
            val programs = programsOf(track)
            lines += ""
            lines += buildString {
                append("Track ${index + 1}$trackName")
                if (channels.isNotEmpty()) append(" - channel${if (channels.size == 1) "" else "s"} ${channels.joinToString(", ")}")
                if (programs.isNotEmpty()) append(", program ${programs.joinToString(", ")}")
                append(", ${notes.size} note${if (notes.size == 1) "" else "s"}${if (notes.isEmpty()) "." else ":"}")
            }
            if (notes.isEmpty()) return@forEachIndexed
            val shown = notes.take(most - listed)
            if (shown.isNotEmpty()) {
                lines += if (clock.ppq) "pitch, start beat, length beats, start ms, length ms, velocity" else "pitch, start ms, length ms, velocity"
            }
            shown.forEach { note ->
                val startMs = number(clock.millis(note.start))
                val lengthMs = number(clock.millis(note.end) - clock.millis(note.start))
                lines += if (clock.ppq) {
                    "${pitchName(note.pitch)}, ${number(note.start / clock.perBeat)}, ${number((note.end - note.start) / clock.perBeat)}, " +
                        "$startMs, $lengthMs, ${note.velocity}"
                } else {
                    "${pitchName(note.pitch)}, $startMs, $lengthMs, ${note.velocity}"
                }
                listed++
            }
            if (notes.size > shown.size) {
                lines += "... and ${notes.size - shown.size} more in this track, not listed: the limit is $most notes."
            }
        }
        return Read(lines.joinToString("\n"), total, listed)
    }

    /**
     * Ticks into beats and milliseconds, through the file's tempo changes - a
     * tick is a fraction of a beat, and how long a beat is changes with every
     * Set Tempo in any track.
     */
    private class Clock(sequence: Sequence) {
        val ppq = sequence.divisionType == Sequence.PPQ
        val perBeat = sequence.resolution.toDouble()
        private val ticksPerSecond = if (ppq) 0.0 else sequence.divisionType.toDouble() * sequence.resolution

        /** Tempo changes in tick order, as microseconds per beat. */
        val changes: List<Pair<Long, Int>> = if (!ppq) emptyList() else metas(sequence, SET_TEMPO)
            .mapNotNull { (tick, data) ->
                if (data.size < 3) null else tick to (((data[0].toInt() and 0xff) shl 16) or ((data[1].toInt() and 0xff) shl 8) or (data[2].toInt() and 0xff))
            }
            .filter { it.second > 0 }

        fun beats(tick: Long): Double? = if (ppq) tick / perBeat else null

        fun millis(tick: Long): Double {
            if (!ppq) return tick / ticksPerSecond * 1000.0
            var micros = 0.0
            var from = 0L
            var tempo = DEFAULT_TEMPO
            for ((at, next) in changes) {
                if (at >= tick) break
                micros += (at - from) / perBeat * tempo
                from = at
                tempo = next
            }
            micros += (tick - from) / perBeat * tempo
            return micros / 1000.0
        }
    }

    private fun at(clock: Clock, tick: Long): String =
        clock.beats(tick)?.let { "beat ${number(it)}" } ?: "${number(clock.millis(tick))} ms"

    /** The notes of one track, paired note-on to note-off per channel and pitch, in the order they start. */
    private fun notesOf(track: Track): List<Note> {
        val open = HashMap<Int, ArrayDeque<Pair<Long, Int>>>()
        val notes = mutableListOf<Note>()
        var last = 0L
        for (i in 0 until track.size()) {
            val event = track.get(i)
            last = maxOf(last, event.tick)
            val message = event.message as? ShortMessage ?: continue
            val slot = message.channel * 128 + message.data1
            val on = message.command == ShortMessage.NOTE_ON && message.data2 > 0
            val off = message.command == ShortMessage.NOTE_OFF || (message.command == ShortMessage.NOTE_ON && message.data2 == 0)
            when {
                on -> open.getOrPut(slot) { ArrayDeque() }.addLast(event.tick to message.data2)
                off -> open[slot]?.removeFirstOrNull()?.let { (start, velocity) ->
                    notes += Note(message.channel, message.data1, velocity, start, event.tick)
                }
            }
        }
        // A note never released lasts to the end of its track.
        open.forEach { (slot, waiting) ->
            waiting.forEach { (start, velocity) -> notes += Note(slot / 128, slot % 128, velocity, start, last) }
        }
        return notes.sortedWith(compareBy({ it.start }, { it.pitch }))
    }

    private fun programsOf(track: Track): List<Int> = (0 until track.size())
        .mapNotNull { (track.get(it).message as? ShortMessage)?.takeIf { m -> m.command == ShortMessage.PROGRAM_CHANGE }?.data1 }
        .distinct()

    private fun metaOf(track: Track, type: Int): ByteArray? = (0 until track.size())
        .firstNotNullOfOrNull { (track.get(it).message as? MetaMessage)?.takeIf { m -> m.type == type }?.data }

    /** The bytes the stored value under [named] holds, decoded from the base64 the shelf kept them as. */
    private fun held(named: String, sessionId: Long?): ByteArray? {
        if (sessionId == null) return null
        val stored = scratch.get(sessionId, named) ?: return null
        val value = runCatching { mapper.readTree(stored) }.getOrNull()?.takeIf(JsonNode::isTextual)?.stringValue() ?: stored
        return runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull() ?: value.toByteArray(Charsets.ISO_8859_1)
    }

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    internal companion object {
        const val READ = "read"
        const val KEY = "key"

        /** What an uploaded `.mid` is kept as - see AttachmentTypes - and the older words for it. */
        val MIDI_TYPES = setOf("audio/midi", "audio/mid", "audio/x-midi", "audio/x-mid")

        private const val SET_TEMPO = 0x51
        private const val TIME_SIGNATURE = 0x58
        private const val KEY_SIGNATURE = 0x59
        private const val TRACK_NAME = 0x03

        /** 120 beats a minute, which is what a file without a Set Tempo plays at. */
        private const val DEFAULT_TEMPO = 500_000

        private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val MAJOR = listOf("Cb", "Gb", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#")
        private val MINOR = listOf("Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#", "G#", "D#", "A#")

        /** Scientific pitch: 60 is C4, middle C. */
        fun pitchName(pitch: Int): String = NAMES[pitch % 12] + (pitch / 12 - 1)

        /** Every meta event of [type] in the file, in tick order, from whichever track holds it. */
        fun metas(sequence: Sequence, type: Int): List<Pair<Long, ByteArray>> = sequence.tracks
            .flatMap { track -> (0 until track.size()).map { track.get(it) } }
            .mapNotNull { event -> (event.message as? MetaMessage)?.takeIf { it.type == type }?.let { event.tick to it.data } }
            .sortedBy { it.first }

        private fun keyOf(data: ByteArray): String? {
            if (data.size < 2) return null
            val sharps = data[0].toInt()
            if (sharps !in -7..7) return null
            return if (data[1].toInt() == 1) "${MINOR[sharps + 7]} minor" else "${MAJOR[sharps + 7]} major"
        }

        /** A number as a person writes it: at most three decimals, none where it is whole. */
        fun number(value: Double): String {
            val rounded = Math.round(value * 1000.0) / 1000.0
            return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
        }
    }
}
