package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.attachment.AttachmentTypes
import io.mszymanski.orknux.server.chat.AgentTools
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
 * Reading a MIDI file, for a model that cannot hear one - and writing one, and
 * turning one into sound ([MidiWriter], [MidiRenderer]).
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
    /** What turns a file into sound; see there for the synthesizer, the SoundFont and the encoders. */
    private val renderer: MidiRenderer,
) : EmbeddedCapability {

    override val key = "midi"
    override val name = "MIDI"

    private val writer = MidiWriter(mapper)

    /*
     * Making music, written here beside reading it: midi_write is the score
     * and midi_render the performance. A model cannot hear either, which is
     * why the score is checked with midi_read and the sound is a file handed
     * on by key rather than anything the model is shown.
     */
    override fun tools(): List<EmbeddedTool> = reading + listOf(
        EmbeddedTool(
            name = WRITE,
            readOnly = true,
            summary = "Composes music as a MIDI file from notes: a melody, chords, a bass line, drums.",
            description = "Composes music - a melody, a song, a chord progression, a drum beat - as a MIDI file (.mid) " +
                "from notes you write: a tempo, a time signature, and tracks, each a General MIDI instrument (a program " +
                "0-127, or a name like \"Acoustic Grand Piano\", \"Violin\", \"Flute\") or drums on channel 10, with " +
                "notes that have a pitch (60 or C4 for middle C; a list of pitches is a chord), a start and a duration " +
                "in beats, and a velocity from 1 to 127. Answers a $KEY for the file: ${key}_$RENDER turns it into an " +
                "MP3 or FLAC to listen to, ${key}_$READ reads the notes back, and ${AgentTools.SAVE_ARTIFACT} or an " +
                "upload tool takes it as it is. A long piece is written in parts: pass the last $KEY as " +
                "${MidiWriter.APPEND} with the next section's notes, and ${MidiWriter.AT} for the beat it starts on.",
            params = listOf(
                EmbeddedParam(
                    MidiWriter.TRACKS, ValueType.ARRAY,
                    "The tracks, as a list: [{\"${MidiWriter.NAME}\": \"Melody\", \"${MidiWriter.INSTRUMENT}\": \"Flute\", " +
                        "\"${MidiWriter.NOTES}\": [{\"${MidiWriter.PITCH}\": \"E5\", \"${MidiWriter.START}\": 0, " +
                        "\"${MidiWriter.DURATION}\": 1, \"${MidiWriter.VELOCITY}\": 96}]}, {\"${MidiWriter.NAME}\": \"Drums\", " +
                        "\"${MidiWriter.DRUMS}\": true, \"${MidiWriter.NOTES}\": [{\"${MidiWriter.PITCH}\": \"kick\", " +
                        "\"${MidiWriter.START}\": 0, \"${MidiWriter.DURATION}\": 0.5}]}]. A chord is a list of pitches: " +
                        "[\"C4\", \"E4\", \"G4\"]. A drum is a note number or a name: kick, snare, closed hihat, open hihat, " +
                        "crash, ride, clap, low tom. Velocity is ${MidiWriter.DEFAULT_VELOCITY} where not given.",
                    required = true,
                ),
                EmbeddedParam(MidiWriter.TEMPO, ValueType.NUMBER, "Beats a minute; ${MidiWriter.DEFAULT_BPM.toInt()} where not given."),
                EmbeddedParam(MidiWriter.TIME_SIGNATURE, ValueType.STRING, "Like 4/4, 3/4 or 6/8; 4/4 where not given."),
                EmbeddedParam(
                    MidiWriter.KEY_SIGNATURE, ValueType.STRING,
                    "Like \"D major\" or \"F# minor\". Written into the file for whoever reads it; it changes no note.",
                ),
                EmbeddedParam(MidiWriter.NAME, ValueType.STRING, "What the piece is called; the file is named after it."),
                EmbeddedParam(
                    MidiWriter.APPEND, ValueType.STRING,
                    "The $KEY of a MIDI file to add to rather than starting a new one. A track named as one already " +
                        "there gets more notes; a new name is a new track. A tempo or time signature given changes " +
                        "from ${MidiWriter.AT} on.",
                ),
                EmbeddedParam(
                    MidiWriter.AT, ValueType.NUMBER,
                    "The beat this call's notes count from, so a section is written from its own 0: with " +
                        "${MidiWriter.AT} 32 a note at ${MidiWriter.START} 0 sounds on beat 32. 0 where not given.",
                ),
            ),
        ),
        EmbeddedTool(
            name = RENDER,
            readOnly = true,
            summary = "Turns a MIDI file into sound: an MP3, FLAC or WAV to listen to.",
            description = "Turns a MIDI file kept under a $KEY - one ${key}_$WRITE made, or an attached .mid - into " +
                "audio somebody can listen to: an MP3 (the default), a FLAC or a WAV, for when a song, a melody or " +
                "music is wanted as a sound file rather than a score. Answers a $KEY for the audio, which " +
                "${AgentTools.SAVE_ARTIFACT} or an upload tool takes as it is, and says how long it is and which " +
                "instruments' sounds played it.",
            params = listOf(
                EmbeddedParam(KEY, ValueType.STRING, "The $KEY of the MIDI file.", required = true),
                EmbeddedParam(
                    FORMAT, ValueType.STRING,
                    MidiRenderer.FORMAT_NAMES.joinToString(", ") { "\"$it\"" } + "; \"$DEFAULT_FORMAT\" where not given.",
                ),
            ),
        ),
    )

    private val reading: List<EmbeddedTool> get() = listOf(
        EmbeddedTool(
            name = READ,
            readOnly = true,
            summary = "Reads a MIDI file kept under a key: its tracks, tempo and notes.",
            description = "Reads a MIDI file (.mid) kept under a $KEY in this session - an attached one - and answers it " +
                "as text: the file's format, its tracks, tempo and time signature changes, and every note per track " +
                "with its pitch (C4 is middle C), start and length in beats and milliseconds, and velocity.",
            params = listOf(EmbeddedParam(KEY, ValueType.STRING, "The contentKey of the MIDI file.", required = true)),
            reads = StoredReader(MIDI_TYPES, KEY),
        ),
    )

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull() ?: return refusal("That is not valid JSON.")
        return when (name) {
            READ -> read(asked, sessionId)
            WRITE -> write(asked, sessionId)
            RENDER -> render(asked, sessionId)
            else -> refusal("There is no tool called ${key}_$name.")
        }
    }

    private fun write(asked: JsonNode, sessionId: Long?): String {
        val onto = asked.path(MidiWriter.APPEND).takeIf { it.isString }?.stringValue()?.trim()?.ifEmpty { null }?.let { named ->
            val bytes = held(named, sessionId) ?: return refusal(PictureAddresses.nothingUnder(named, "Nothing in this session is kept under \"$named\" to add to."))
            runCatching { MidiSystem.getSequence(ByteArrayInputStream(bytes)) }.getOrElse {
                return refusal("\"$named\" is not a MIDI file, so nothing can be added to it.")
            }
        }
        val made = when (val written = writer.write(asked, onto)) {
            is MidiWriter.Made.Refused -> return refusal(written.reason)
            is MidiWriter.Made.Written -> written
        }
        val answer = linkedMapOf<String, Any?>(
            "contentType" to AttachmentTypes.MIDI,
            "tracks" to made.tracks.map { linkedMapOf("name" to it.name, "channel" to it.channel, "instrument" to it.instrument, "notes" to it.notes) },
            "notesAdded" to made.added,
            "beats" to rounded(made.sequence.tickLength / made.sequence.resolution.toDouble()),
            "seconds" to rounded(made.sequence.microsecondLength / 1_000_000.0),
            "bytes" to made.bytes.size,
        )
        if (sessionId == null) {
            answer["base64"] = Base64.getEncoder().encodeToString(made.bytes)
            return mapper.writeValueAsString(answer)
        }
        val named = slug(asked.path(MidiWriter.NAME).takeIf { it.isString }?.stringValue(), "music") + "." +
            java.lang.Long.toString(System.nanoTime(), 36) + ".mid"
        kept(sessionId, named, made.bytes, AttachmentTypes.MIDI)?.let { return refusal(it) }
        return mapper.writeValueAsString(
            linkedMapOf<String, Any?>(KEY to named) + answer + mapOf(
                "note" to "Pass $KEY to ${key}_$RENDER for an MP3 or FLAC to listen to, to ${key}_$READ to check the " +
                    "notes, or to whatever saves or uploads a file. To add the next section, call ${key}_$WRITE again " +
                    "with ${MidiWriter.APPEND} set to this $KEY.",
            ),
        )
    }

    private fun render(asked: JsonNode, sessionId: Long?): String {
        val named = asked.path(KEY).takeIf { it.isString }?.stringValue()?.trim()?.ifEmpty { null }
            ?: return refusal("Give the $KEY of a MIDI file.")
        val bytes = held(named, sessionId) ?: return refusal(PictureAddresses.nothingUnder(named))
        val format = asked.path(FORMAT).takeIf { it.isString }?.stringValue()?.trim()?.removePrefix(".")?.ifEmpty { null } ?: DEFAULT_FORMAT
        val made = when (val rendered = renderer.render(bytes, format)) {
            is MidiRenderer.Rendered.Refused -> return refusal(
                sessionId?.let { scratch.kindOf(it, named)?.contentType }?.takeIf { it !in MIDI_TYPES }
                    ?.let { "\"$named\" holds $it, not a MIDI file. ${rendered.reason}" } ?: rendered.reason,
            )
            is MidiRenderer.Rendered.Made -> rendered
        }
        val answer = linkedMapOf<String, Any?>(
            "contentType" to made.contentType,
            "format" to made.extension,
            "seconds" to rounded(made.seconds),
            "sampleRate" to made.sampleRate,
            "channels" to made.channels,
            "bytes" to made.bytes.size,
        )
        made.bitrateKbits?.let { answer["bitrateKbits"] = it }
        answer["soundbank"] = made.soundbank
        made.soundbankNote?.let { answer["soundbankNote"] = it }
        if (sessionId == null) {
            answer["base64"] = Base64.getEncoder().encodeToString(made.bytes)
            return mapper.writeValueAsString(answer)
        }
        val base = named.substringBefore('.').ifEmpty { "music" }
        val stored = base + "." + java.lang.Long.toString(System.nanoTime(), 36) + "." + made.extension
        kept(sessionId, stored, made.bytes, made.contentType)?.let { return refusal("The sound was made and could not be kept: $it.") }
        return mapper.writeValueAsString(
            linkedMapOf<String, Any?>(KEY to stored) + answer + mapOf(
                "note" to "Pass $KEY to ${AgentTools.SAVE_ARTIFACT} (named like $base.${made.extension}) for a link " +
                    "somebody can open, or to whatever uploads or sends a file. The bytes are not text.",
            ),
        )
    }

    /** Bytes into the session's store as base64, with what they are; the store's refusal where it would not. */
    private fun kept(sessionId: Long, key: String, bytes: ByteArray, contentType: String): String? = scratch.put(
        sessionId, key, mapper.writeValueAsString(Base64.getEncoder().encodeToString(bytes)),
        io.mszymanski.orknux.workflow.script.StoredKind(contentType, true),
    )

    private fun read(asked: JsonNode, sessionId: Long?): String {
        val named = asked.path(KEY).takeIf { it.isTextual }?.stringValue()?.trim()?.ifEmpty { null }
            ?: return refusal("Give the $KEY of a MIDI file.")
        val bytes = held(named, sessionId) ?: return refusal(PictureAddresses.nothingUnder(named))
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

    /** A number for an answer, as a number: at most three decimals. */
    private fun rounded(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    internal companion object {
        const val READ = "read"
        const val WRITE = "write"
        const val RENDER = "render"
        const val KEY = "key"
        const val FORMAT = "format"
        const val DEFAULT_FORMAT = "mp3"

        /** A name made fit for a key: letters, digits and dashes, short, and [otherwise] where nothing is left. */
        fun slug(said: String?, otherwise: String): String = said.orEmpty().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { otherwise }

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
