package io.mszymanski.orknux.server.embedded

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import javax.sound.midi.Track

/**
 * A Standard MIDI File made from a description a model writes: a tempo, a
 * metre, and tracks of notes with a General MIDI instrument each.
 *
 * Assembled through the JDK's `javax.sound.midi` - a [Sequence] of [Track]s of
 * [MidiEvent]s, written by [MidiSystem.write] - so no byte of the file format
 * is written here. What is here is the reading of the description, which is
 * strict: a pitch that is not a note, a beat before the start or a velocity
 * outside 1 to 127 is refused with where it was and what would do, because a
 * file quietly made of what could be guessed is a piece nobody asked for.
 *
 * **Written in pieces.** A song is longer than one call can carry, so a call
 * may name a file made before as [APPEND] and add to it: a track named as one
 * already there gets the notes, a new name is a new track, and [AT] moves
 * every start along so a section is written from its own beat 0.
 */
internal class MidiWriter(private val mapper: ObjectMapper) {

    /** A file made, or why not. */
    sealed interface Made {
        data class Written(val bytes: ByteArray, val sequence: Sequence, val tracks: List<Summary>, val added: Int) : Made
        data class Refused(val reason: String) : Made
    }

    /** One track as the answer describes it. */
    data class Summary(val name: String, val channel: Int, val instrument: String, val notes: Int)

    private data class Note(val pitch: Int, val start: Long, val end: Long, val velocity: Int)

    private data class Part(val name: String, val drums: Boolean, val program: Int?, val notes: List<Note>)

    /**
     * The file [asked] describes, laid over [onto] where it adds to one.
     * Everything is checked before anything is built, and every problem found is
     * said at once, so a model fixes its description in one go rather than one
     * refusal at a time.
     */
    fun write(asked: JsonNode, onto: Sequence?): Made {
        val problems = mutableListOf<String>()
        if (onto != null && onto.divisionType != Sequence.PPQ) {
            return Made.Refused("That file is timed in SMPTE frames rather than beats, so nothing can be added to it in beats.")
        }
        val resolution = onto?.resolution ?: RESOLUTION
        fun ticks(beats: Double): Long = Math.round(beats * resolution)

        val at = number(asked, AT, problems)?.also { if (it < 0) problems += "$AT is a beat to start from, 0 or later." } ?: 0.0
        val tempo = number(asked, TEMPO, problems)?.also {
            if (it < LEAST_BPM || it > MOST_BPM) problems += "$TEMPO is beats a minute, from $LEAST_BPM to $MOST_BPM; $it is not."
        }
        val metre = text(asked, TIME_SIGNATURE)?.let { said ->
            metreOf(said) ?: null.also {
                problems += "$TIME_SIGNATURE is written like 4/4, 3/4 or 6/8: a count, a slash and 1, 2, 4, 8, 16 or 32; \"$said\" is not."
            }
        }
        val key = text(asked, KEY_SIGNATURE)?.let { said ->
            keyOf(said) ?: null.also { problems += "$KEY_SIGNATURE is written like \"D major\" or \"F# minor\"; \"$said\" is not." }
        }

        val tracks = listIn(asked.path(TRACKS))
        if (tracks == null || tracks.isEmpty) {
            problems += "Give $TRACKS: a list of tracks, each with its $NOTES."
            return refused(problems)
        }
        if (tracks.size() > MOST_TRACKS) problems += "A file holds at most $MOST_TRACKS tracks, one per MIDI channel; that is ${tracks.size()}."

        val parts = tracks.mapIndexedNotNull { index, track -> part(track, "$TRACKS[$index]", at, ::ticks, problems) }
        parts.groupBy { it.name.lowercase() }.filter { it.value.size > 1 }.keys.forEach {
            problems += "Two tracks are called \"$it\"; a name is how a track is found again, so give each its own."
        }
        if (problems.isEmpty() && parts.sumOf { it.notes.size } == 0) problems += "There are no notes in any track."
        if (problems.isNotEmpty()) return refused(problems)

        return build(onto, resolution, parts, tempo, metre, key, ticks(at), asked)
    }

    private fun build(
        onto: Sequence?,
        resolution: Int,
        parts: List<Part>,
        tempo: Double?,
        metre: Pair<Int, Int>?,
        key: Pair<Int, Int>?,
        from: Long,
        asked: JsonNode,
    ): Made {
        // Every track's events so far, without the end-of-track each will be given again.
        val events = onto?.tracks?.map { track -> (0 until track.size()).map { track.get(it) }.filterNot { isEnd(it.message) }.toMutableList() }
            ?.toMutableList()
            ?: mutableListOf(mutableListOf<MidiEvent>().apply {
                text(asked, NAME)?.let { add(MidiEvent(meta(TRACK_NAME, it.toByteArray(Charsets.ISO_8859_1)), 0)) }
            })

        // The conductor is the first track, which is where this writes tempo and metre and where most files keep them.
        val conductor = events[0]
        (tempo ?: if (onto == null) DEFAULT_BPM else null)?.let { bpm ->
            val micros = Math.round(60_000_000.0 / bpm).toInt()
            conductor += MidiEvent(meta(SET_TEMPO, byteArrayOf((micros shr 16).toByte(), (micros shr 8).toByte(), micros.toByte())), from)
        }
        (metre ?: if (onto == null) 4 to 4 else null)?.let { (count, unit) ->
            val power = Integer.numberOfTrailingZeros(unit)
            conductor += MidiEvent(meta(TIME_SIGNATURE_META, byteArrayOf(count.toByte(), power.toByte(), 24, 8)), from)
        }
        key?.let { (sharps, minor) -> conductor += MidiEvent(meta(KEY_SIGNATURE_META, byteArrayOf(sharps.toByte(), minor.toByte())), from) }

        val names = events.map { list -> list.firstNotNullOfOrNull { e -> (e.message as? MetaMessage)?.takeIf { it.type == TRACK_NAME }?.data }?.let { String(it, Charsets.ISO_8859_1).trim() } }
        val channels = events.map { list -> list.firstNotNullOfOrNull { (it.message as? ShortMessage)?.channel } }
        val used = channels.filterNotNull().toMutableSet()

        val summaries = mutableListOf<Summary>()
        for (part in parts) {
            val existing = names.indexOfFirst { it != null && it.equals(part.name, ignoreCase = true) }.takeIf { it >= 0 }
            val channel = when {
                part.drums -> DRUM_CHANNEL
                existing != null && channels[existing] != null -> channels[existing]!!
                else -> (0 until 16).firstOrNull { it != DRUM_CHANNEL && it !in used }
                    ?: return Made.Refused("Every melodic MIDI channel is taken, so there is no room for the track \"${part.name}\".")
            }
            if (existing != null && channels[existing] != null && channels[existing] != channel) {
                return Made.Refused("The track \"${part.name}\" is on channel ${channels[existing]!! + 1}; drums are on channel 10 only, so it cannot become a drum track.")
            }
            used += channel
            val into = existing?.let { events[it] } ?: mutableListOf<MidiEvent>().also { list ->
                list += MidiEvent(meta(TRACK_NAME, part.name.toByteArray(Charsets.ISO_8859_1)), 0)
                events += list
            }
            part.program?.let { into += MidiEvent(ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, it, 0), if (existing == null) 0 else from) }
            part.notes.forEach { note ->
                into += MidiEvent(ShortMessage(ShortMessage.NOTE_ON, channel, note.pitch, note.velocity), note.start)
                into += MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, channel, note.pitch, 0), note.end)
            }
            summaries += Summary(
                name = part.name,
                channel = channel + 1,
                instrument = if (part.drums) "drums" else part.program?.let { "$it ${INSTRUMENTS[it]}" } ?: "as before",
                notes = part.notes.size,
            )
        }

        val sequence = Sequence(Sequence.PPQ, resolution)
        events.forEach { list ->
            val track = sequence.createTrack()
            // Stable, and releases before strikes at one tick, so a note repeated back to back is struck again rather than cut.
            list.sortedWith(compareBy<MidiEvent> { it.tick }.thenBy { rank(it.message) }).forEach { track.add(it) }
        }
        val out = ByteArrayOutputStream()
        val type = if (1 in MidiSystem.getMidiFileTypes(sequence)) 1 else MidiSystem.getMidiFileTypes(sequence).firstOrNull()
            ?: return Made.Refused("The JDK cannot write this sequence as a MIDI file.")
        MidiSystem.write(sequence, type, out)
        return Made.Written(out.toByteArray(), sequence, summaries, parts.sumOf { it.notes.size })
    }

    private fun part(track: JsonNode, where: String, at: Double, ticks: (Double) -> Long, problems: MutableList<String>): Part? {
        if (!track.isObject) {
            problems += "$where is a track: an object with $NOTES, and a $NAME and an $INSTRUMENT or $DRUMS."
            return null
        }
        val drums = track.path(DRUMS).let { it.isBoolean && it.booleanValue() || it.isString && it.stringValue().equals("true", true) }
        val program = if (drums) null else track.path(INSTRUMENT).takeUnless { it.isMissingNode || it.isNull }?.let { instrumentOf(it, "$where.$INSTRUMENT", problems) }
        val name = text(track, NAME)?.trim()?.ifEmpty { null }
            ?: if (drums) "Drums" else program?.let { INSTRUMENTS[it] }
            ?: null.also { problems += "$where needs a $NAME or an $INSTRUMENT." }
            ?: return null
        val notes = listIn(track.path(NOTES))
        if (notes == null) {
            problems += "$where.$NOTES is a list of notes, each with a $PITCH, $START and $DURATION."
            return null
        }
        val read = notes.flatMapIndexed { index, note -> notesOf(note, "$where.$NOTES[$index]", drums, at, ticks, problems) }
        return Part(name, drums, program, read)
    }

    private fun notesOf(note: JsonNode, where: String, drums: Boolean, at: Double, ticks: (Double) -> Long, problems: MutableList<String>): List<Note> {
        if (!note.isObject) {
            problems += "$where is a note: {\"$PITCH\": \"C4\", \"$START\": 0, \"$DURATION\": 1}."
            return emptyList()
        }
        val given = problems.size
        val pitchNode = note.path(PITCH)
        val pitches = when {
            pitchNode.isArray -> pitchNode.mapIndexedNotNull { i, one -> pitchOf(one, "$where.$PITCH[$i]", drums, problems) }
            pitchNode.isMissingNode || pitchNode.isNull -> emptyList<Int>().also { problems += "$where needs a $PITCH." }
            else -> listOfNotNull(pitchOf(pitchNode, "$where.$PITCH", drums, problems))
        }
        if (pitchNode.isArray && pitchNode.isEmpty) problems += "$where.$PITCH is an empty chord."
        val start = number(note, START, problems, where) ?: null.also { if (note.path(START).isMissingNode) problems += "$where needs a $START, in beats from 0." }
        val duration = number(note, DURATION, problems, where) ?: null.also { if (note.path(DURATION).isMissingNode) problems += "$where needs a $DURATION, in beats." }
        val velocity = if (note.path(VELOCITY).isMissingNode || note.path(VELOCITY).isNull) DEFAULT_VELOCITY.toDouble() else number(note, VELOCITY, problems, where)
        if (start != null && start < 0) problems += "$where.$START is $start; a note starts at beat 0 or later."
        if (duration != null && duration <= 0) problems += "$where.$DURATION is $duration; a note lasts longer than 0 beats."
        if (velocity != null && (velocity < 1 || velocity > 127 || velocity != Math.floor(velocity))) {
            problems += "$where.$VELOCITY is a whole number from 1 (softest) to 127 (loudest); $velocity is not."
        }
        if (problems.size > given || start == null || duration == null || velocity == null) return emptyList()
        val from = ticks(at + start)
        val to = maxOf(from + 1, ticks(at + start + duration))
        return pitches.map { Note(it, from, to, velocity.toInt()) }
    }

    private fun pitchOf(node: JsonNode, where: String, drums: Boolean, problems: MutableList<String>): Int? {
        if (node.isNumber) {
            val n = node.doubleValue()
            if (n == Math.floor(n) && n in 0.0..127.0) return n.toInt()
            problems += "$where is $n; a pitch number is a whole number from 0 to 127 (60 is middle C)."
            return null
        }
        val said = node.takeIf { it.isString }?.stringValue()?.trim().orEmpty()
        noteNumber(said)?.let { return it }
        if (drums) DRUMS_BY_NAME[squashed(said)]?.let { return it }
        problems += "$where is \"$said\", which is not a pitch: write a number from 0 to 127 or a name like C4, F#3 or Bb2" +
            (if (drums) ", or a drum like kick, snare or closed hihat." else ".")
        return null
    }

    private fun instrumentOf(node: JsonNode, where: String, problems: MutableList<String>): Int? {
        if (node.isNumber || node.isString && node.stringValue().trim().toIntOrNull() != null) {
            val n = if (node.isNumber) node.doubleValue() else node.stringValue().trim().toDouble()
            if (n == Math.floor(n) && n in 0.0..127.0) return n.toInt()
            problems += "$where is $n; a General MIDI program is 0 to 127, counted from 0 (0 is Acoustic Grand Piano)."
            return null
        }
        val said = node.takeIf { it.isString }?.stringValue()?.trim().orEmpty()
        return when (val found = instrumentNamed(said)) {
            is Found.One -> found.program
            is Found.Several -> null.also {
                problems += "$where \"$said\" could be ${found.programs.take(6).joinToString(", ") { "$it ${INSTRUMENTS[it]}" }}; give the program number or the whole name."
            }
            Found.None -> null.also { problems += "$where \"$said\" is not a General MIDI instrument; give a program 0 to 127 or a name like \"Acoustic Grand Piano\", \"String Ensemble 1\" or \"Flute\"." }
        }
    }

    private fun number(node: JsonNode, field: String, problems: MutableList<String>, where: String? = null): Double? {
        val held = node.path(field)
        val at = where?.let { "$it.$field" } ?: field
        return when {
            held.isMissingNode || held.isNull -> null
            held.isNumber -> held.doubleValue()
            held.isString -> held.stringValue().trim().toDoubleOrNull() ?: null.also { problems += "$at is a number; \"${held.stringValue()}\" is not." }
            else -> null.also { problems += "$at is a number." }
        }?.takeIf { it.isFinite() }
    }

    private fun text(node: JsonNode, field: String): String? = node.path(field).takeIf { it.isString }?.stringValue()?.trim()?.ifEmpty { null }

    /** A list, or the text of one: every parameter reaches a model typed as a string, so a list often arrives as its JSON. */
    private fun listIn(node: JsonNode): JsonNode? = when {
        node.isArray -> node
        node.isString -> runCatching { mapper.readTree(node.stringValue()) }.getOrNull()?.takeIf { it.isArray }
        else -> null
    }

    private fun refused(problems: List<String>): Made.Refused = Made.Refused(
        if (problems.size == 1) problems.single()
        else "Nothing was written: " + problems.take(MOST_PROBLEMS_SAID).joinToString(" ") +
            (if (problems.size > MOST_PROBLEMS_SAID) " And ${problems.size - MOST_PROBLEMS_SAID} more like these." else ""),
    )

    sealed interface Found {
        data class One(val program: Int) : Found
        data class Several(val programs: List<Int>) : Found
        data object None : Found
    }

    internal companion object {
        const val TEMPO = "tempo"
        const val TIME_SIGNATURE = "timeSignature"
        const val KEY_SIGNATURE = "keySignature"
        const val TRACKS = "tracks"
        const val NAME = "name"
        const val INSTRUMENT = "instrument"
        const val DRUMS = "drums"
        const val NOTES = "notes"
        const val PITCH = "pitch"
        const val START = "start"
        const val DURATION = "duration"
        const val VELOCITY = "velocity"
        const val APPEND = "append"
        const val AT = "at"

        /** Ticks per beat in a file made here: divisible by 3 and by 16, so triplets and sixty-fourths land on a tick. */
        const val RESOLUTION = 480

        /** Channel 10, counted from 1 as musicians do; General MIDI's drums. */
        const val DRUM_CHANNEL = 9
        const val MOST_TRACKS = 16
        const val DEFAULT_VELOCITY = 90
        const val DEFAULT_BPM = 120.0

        /** What a Set Tempo's three bytes can say, near enough, and what any piece is. */
        const val LEAST_BPM = 4.0
        const val MOST_BPM = 1000.0

        private const val MOST_PROBLEMS_SAID = 8
        private const val SET_TEMPO = 0x51
        private const val TIME_SIGNATURE_META = 0x58
        private const val KEY_SIGNATURE_META = 0x59
        private const val TRACK_NAME = 0x03
        private const val END_OF_TRACK = 0x2F

        private fun meta(type: Int, data: ByteArray) = MetaMessage(type, data, data.size)

        private fun isEnd(message: MidiMessage) = message is MetaMessage && message.type == END_OF_TRACK

        private fun rank(message: MidiMessage): Int = when {
            message is ShortMessage && (message.command == ShortMessage.NOTE_OFF ||
                message.command == ShortMessage.NOTE_ON && message.data2 == 0) -> 0
            message is MetaMessage -> 1
            message is ShortMessage && message.command == ShortMessage.NOTE_ON -> 3
            else -> 2
        }

        private val LETTERS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
        private val NOTE_NAME = Regex("^([A-Ga-g])([#b♯♭]{0,2})(-?\\d{1,2})$")

        /** Scientific pitch to a MIDI number: C4 is 60, middle C. Null for anything else, or outside 0 to 127. */
        fun noteNumber(said: String): Int? {
            val match = NOTE_NAME.matchEntire(said.trim()) ?: return null
            val (letter, accidentals, octave) = match.destructured
            val shift = accidentals.sumOf { if (it == '#' || it == '♯') 1 else -1 }
            val number = (octave.toInt() + 1) * 12 + LETTERS.getValue(letter.uppercase()[0]) + shift
            return number.takeIf { it in 0..127 }
        }

        private val MAJOR_KEYS = listOf("Cb", "Gb", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#")
        private val MINOR_KEYS = listOf("Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#", "G#", "D#", "A#")

        /** "D major" to two sharps and major; null for a key no signature writes. */
        fun keyOf(said: String): Pair<Int, Int>? {
            val words = said.trim().split(Regex("\\s+"))
            if (words.size != 2) return null
            val tonic = words[0].replaceFirstChar { it.uppercaseChar() }
            return when (words[1].lowercase()) {
                "major" -> MAJOR_KEYS.indexOf(tonic).takeIf { it >= 0 }?.let { it - 7 to 0 }
                "minor" -> MINOR_KEYS.indexOf(tonic).takeIf { it >= 0 }?.let { it - 7 to 1 }
                else -> null
            }
        }

        /** "6/8" to six and eight; null where the lower figure is not a power of two a file can say. */
        fun metreOf(said: String): Pair<Int, Int>? {
            val (count, unit) = said.trim().split('/').takeIf { it.size == 2 }?.map { it.trim().toIntOrNull() } ?: return null
            if (count == null || unit == null || count !in 1..32 || unit !in setOf(1, 2, 4, 8, 16, 32)) return null
            return count to unit
        }

        private fun squashed(said: String) = said.lowercase().filter { it.isLetterOrDigit() }

        /** A General MIDI instrument by its name, whole or as a part only one name holds. */
        fun instrumentNamed(said: String): Found {
            val asked = squashed(said)
            if (asked.isEmpty()) return Found.None
            INSTRUMENTS.indexOfFirst { squashed(it) == asked }.takeIf { it >= 0 }?.let { return Found.One(it) }
            ALIASES[asked]?.let { return Found.One(it) }
            val holding = INSTRUMENTS.indices.filter { asked in squashed(INSTRUMENTS[it]) }
            return when (holding.size) {
                0 -> Found.None
                1 -> Found.One(holding.single())
                else -> Found.Several(holding)
            }
        }

        /** The words people use for the instrument they mean, where the General MIDI name is longer. */
        private val ALIASES = mapOf(
            "piano" to 0, "grandpiano" to 0, "electricpiano" to 4, "rhodes" to 4, "organ" to 16, "churchorgan" to 19,
            "guitar" to 24, "acousticguitar" to 25, "electricguitar" to 27, "bass" to 33, "electricbass" to 33,
            "strings" to 48, "choir" to 52, "brass" to 61, "synth" to 80, "synthlead" to 80, "pad" to 88, "synthpad" to 88,
        )

        /** General MIDI Level 1's instruments, by program number counted from 0. */
        val INSTRUMENTS = listOf(
            "Acoustic Grand Piano", "Bright Acoustic Piano", "Electric Grand Piano", "Honky-tonk Piano",
            "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavinet",
            "Celesta", "Glockenspiel", "Music Box", "Vibraphone", "Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
            "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ", "Reed Organ", "Accordion", "Harmonica", "Tango Accordion",
            "Acoustic Guitar (nylon)", "Acoustic Guitar (steel)", "Electric Guitar (jazz)", "Electric Guitar (clean)",
            "Electric Guitar (muted)", "Overdriven Guitar", "Distortion Guitar", "Guitar Harmonics",
            "Acoustic Bass", "Electric Bass (finger)", "Electric Bass (pick)", "Fretless Bass",
            "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
            "Violin", "Viola", "Cello", "Contrabass", "Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
            "String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2",
            "Choir Aahs", "Voice Oohs", "Synth Choir", "Orchestra Hit",
            "Trumpet", "Trombone", "Tuba", "Muted Trumpet", "French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2",
            "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax", "Oboe", "English Horn", "Bassoon", "Clarinet",
            "Piccolo", "Flute", "Recorder", "Pan Flute", "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
            "Lead 1 (square)", "Lead 2 (sawtooth)", "Lead 3 (calliope)", "Lead 4 (chiff)",
            "Lead 5 (charang)", "Lead 6 (voice)", "Lead 7 (fifths)", "Lead 8 (bass + lead)",
            "Pad 1 (new age)", "Pad 2 (warm)", "Pad 3 (polysynth)", "Pad 4 (choir)",
            "Pad 5 (bowed)", "Pad 6 (metallic)", "Pad 7 (halo)", "Pad 8 (sweep)",
            "FX 1 (rain)", "FX 2 (soundtrack)", "FX 3 (crystal)", "FX 4 (atmosphere)",
            "FX 5 (brightness)", "FX 6 (goblins)", "FX 7 (echoes)", "FX 8 (sci-fi)",
            "Sitar", "Banjo", "Shamisen", "Koto", "Kalimba", "Bagpipe", "Fiddle", "Shanai",
            "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock", "Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cymbal",
            "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet", "Telephone Ring", "Helicopter", "Applause", "Gunshot",
        )

        /** General MIDI's percussion on channel 10, by note number, and the short words for the common ones. */
        val DRUMS_BY_NAME: Map<String, Int> = listOf(
            35 to "Acoustic Bass Drum", 36 to "Bass Drum 1", 37 to "Side Stick", 38 to "Acoustic Snare", 39 to "Hand Clap",
            40 to "Electric Snare", 41 to "Low Floor Tom", 42 to "Closed Hi-Hat", 43 to "High Floor Tom", 44 to "Pedal Hi-Hat",
            45 to "Low Tom", 46 to "Open Hi-Hat", 47 to "Low-Mid Tom", 48 to "Hi-Mid Tom", 49 to "Crash Cymbal 1",
            50 to "High Tom", 51 to "Ride Cymbal 1", 52 to "Chinese Cymbal", 53 to "Ride Bell", 54 to "Tambourine",
            55 to "Splash Cymbal", 56 to "Cowbell", 57 to "Crash Cymbal 2", 58 to "Vibraslap", 59 to "Ride Cymbal 2",
            60 to "Hi Bongo", 61 to "Low Bongo", 62 to "Mute Hi Conga", 63 to "Open Hi Conga", 64 to "Low Conga",
            65 to "High Timbale", 66 to "Low Timbale", 67 to "High Agogo", 68 to "Low Agogo", 69 to "Cabasa",
            70 to "Maracas", 71 to "Short Whistle", 72 to "Long Whistle", 73 to "Short Guiro", 74 to "Long Guiro",
            75 to "Claves", 76 to "Hi Wood Block", 77 to "Low Wood Block", 78 to "Mute Cuica", 79 to "Open Cuica",
            80 to "Mute Triangle", 81 to "Open Triangle",
        ).associate { (number, name) -> squashed(name) to number } + mapOf(
            "kick" to 36, "bassdrum" to 36, "snare" to 38, "rimshot" to 37, "clap" to 39, "hihat" to 42,
            "closedhihat" to 42, "pedalhihat" to 44, "openhihat" to 46, "crash" to 49, "ride" to 51,
            "tom" to 47, "lowtom" to 45, "midtom" to 47, "hightom" to 50, "floortom" to 41, "triangle" to 81,
        )
    }
}
