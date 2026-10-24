package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.BuiltInSkills
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.embedded.EmbeddedCapabilities
import io.mszymanski.orknux.server.embedded.MidiCapability
import io.mszymanski.orknux.server.embedded.MidiWriter
import io.mszymanski.orknux.server.llm.LlmSession
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.llm.LlmSessionStoreRepository
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.StoredKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import javax.sound.sampled.AudioSystem
import javax.sql.DataSource

/**
 * Making music: midi_write writes a MIDI file from notes a model describes,
 * midi_render plays one into FLAC, MP3 or WAV, and both keep what they make in
 * the session's store under a key with its kind - the way every tool that makes
 * bytes answers. The audio is checked as a file, by what its own header says
 * and by decoding it, never by trusting the answer's numbers.
 *
 * Synthesised by the JDK's Gervill, which needs the export surefire passes
 * (see app/pom.xml); encoded by the FFmpeg JavaCV carries.
 */
@SpringBootTest
class MidiWriteRenderTest(
    @Autowired val midi: MidiCapability,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val scratch: LlmSessionStore,
    @Autowired val entries: LlmSessionStoreRepository,
    @Autowired val tunables: Tunables,
    @Autowired val mapper: ObjectMapper,
    @Autowired val embedded: EmbeddedCapabilities,
    @Autowired val searching: ToolSearchTools,
    @Autowired val builtIns: BuiltInSkills,
    @Autowired val skills: SkillTool,
    @Autowired val agents: AgentRepository,
    @Autowired val dataSource: DataSource,
) {

    private var workspaceId = 0L
    private var session = 0L

    @BeforeEach
    fun session() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "midi-write-${System.nanoTime()}")).id)
        session = requireNotNull(sessions.save(LlmSession(workspaceId = workspaceId, sessionKey = "midi-${System.nanoTime()}")).id)
    }

    @AfterEach
    fun settings() {
        tunables.set(Tunable.MIDI_RENDER_LONGEST_SECONDS, null, "alice")
        tunables.set(Tunable.MIDI_RENDER_SAMPLE_RATE, null, "alice")
    }

    private fun call(tool: String, arguments: String): JsonNode = mapper.readTree(midi.run(tool, arguments, workspaceId, session))

    /** Two bars of 4/4 at 120: a melody, a chord held for each bar, and a drum beat - eight beats, four seconds. */
    private val song = """
        {"name": "Little Tune", "tempo": 120, "timeSignature": "4/4", "keySignature": "C major",
         "tracks": [
           {"name": "Melody", "instrument": "Flute", "notes": [
             {"pitch": "C5", "start": 0, "duration": 1, "velocity": 100},
             {"pitch": "E5", "start": 1, "duration": 1},
             {"pitch": 79, "start": 2, "duration": 2, "velocity": 80}]},
           {"name": "Chords", "instrument": 0, "notes": [
             {"pitch": ["C4", "E4", "G4"], "start": 0, "duration": 4, "velocity": 70},
             {"pitch": ["F3", "A3", "C4"], "start": 4, "duration": 4, "velocity": 70}]},
           {"name": "Drums", "drums": true, "notes": [
             {"pitch": "kick", "start": 0, "duration": 0.5},
             {"pitch": "snare", "start": 1, "duration": 0.5},
             {"pitch": "closed hihat", "start": 7.5, "duration": 0.5}]}
         ]}
    """.trimIndent()

    @Test
    fun `a written file reads back as the notes it was written with, and is kept as MIDI`() {
        val written = call(MidiCapability.WRITE, song)
        assertThat(written.path("error").isMissingNode).describedAs(written.toString()).isTrue()
        val key = written.path(MidiCapability.KEY).stringValue()
        assertThat(key).startsWith("little-tune.").endsWith(".mid")
        assertThat(scratch.kindOf(session, key)).isEqualTo(StoredKind("audio/midi", true))
        assertThat(written.path("notesAdded").intValue()).isEqualTo(3 + 6 + 3)
        assertThat(written.path("seconds").doubleValue()).isEqualTo(4.0)
        assertThat(written.path("tracks").let { t -> (0 until t.size()).map { t[it].path("channel").intValue() } }).containsExactly(1, 2, 10)
        assertThat(written.path("tracks")[0].path("instrument").stringValue()).isEqualTo("73 Flute")

        val text = call(MidiCapability.READ, """{"key":"$key"}""").path("midi").stringValue()
        assertThat(text).contains("Tempo: 120 BPM at beat 0.")
        assertThat(text).contains("Time signature: 4/4 at beat 0.")
        assertThat(text).contains("Key signature: C major at beat 0.")
        assertThat(text).contains("\"Melody\" - channel 1, program 73, 3 notes:")
        assertThat(text).contains("C5, 0, 1, 0, 500, 100", "E5, 1, 1, 500, 500, ${MidiWriter.DEFAULT_VELOCITY}", "G5, 2, 2, 1000, 1000, 80")
        assertThat(text).contains("\"Chords\" - channel 2, program 0, 6 notes:")
        assertThat(text).contains("C4, 0, 4, 0, 2000, 70", "E4, 0, 4", "G4, 0, 4", "F3, 4, 4, 2000, 2000, 70", "A3, 4, 4")
        assertThat(text).contains("\"Drums\" - channel 10, 3 notes:")
        assertThat(text).contains("C2, 0, 0.5", "D2, 1, 0.5", "F#2, 7.5, 0.5")
    }

    @Test
    fun `a piece is written in sections, each added to the last at its own beat`() {
        val first = call(MidiCapability.WRITE, """{"name":"Verse","tracks":[{"name":"Lead","instrument":"Violin","notes":[{"pitch":"A4","start":0,"duration":4}]}]}""")
        val firstKey = first.path(MidiCapability.KEY).stringValue()
        val second = call(
            MidiCapability.WRITE,
            """{"append":"$firstKey","at":4,"tempo":60,"tracks":[
                 {"name":"Lead","notes":[{"pitch":"B4","start":0,"duration":2}]},
                 {"name":"Bass","instrument":"Acoustic Bass","notes":[{"pitch":"A2","start":0,"duration":2}]}]}""",
        )
        assertThat(second.path("error").isMissingNode).describedAs(second.toString()).isTrue()
        val key = second.path(MidiCapability.KEY).stringValue()
        assertThat(key).isNotEqualTo(firstKey)
        // Four beats at 120 and two at 60: two seconds and two.
        assertThat(second.path("seconds").doubleValue()).isEqualTo(4.0)

        val text = call(MidiCapability.READ, """{"key":"$key"}""").path("midi").stringValue()
        assertThat(text).contains("Tempo: 120 BPM at beat 0; 60 BPM at beat 4.")
        assertThat(text).contains("\"Lead\" - channel 1, program 40, 2 notes:")
        assertThat(text).contains("A4, 0, 4, 0, 2000", "B4, 4, 2, 2000, 2000")
        assertThat(text).contains("\"Bass\" - channel 2, program 32, 1 note:")
    }

    @Test
    fun `a description that is not music is refused, every problem named, and nothing is kept`() {
        val before = entries.countBySessionId(session)
        val refused = call(
            MidiCapability.WRITE,
            """{"tempo": 0, "timeSignature": "4/5", "tracks": [
                 {"name": "A", "instrument": "Kazooo", "notes": [
                   {"pitch": "H4", "start": -1, "duration": 0, "velocity": 200}]},
                 {"name": "B", "notes": [{"pitch": "kick", "start": 0, "duration": 1}]}]}""",
        ).path("error").stringValue()

        assertThat(refused).startsWith("Nothing was written:")
        assertThat(refused).contains("tempo is beats a minute")
        assertThat(refused).contains("timeSignature is written like 4/4")
        assertThat(refused).contains("tracks[0].instrument \"Kazooo\" is not a General MIDI instrument")
        assertThat(refused).contains("tracks[0].notes[0].pitch is \"H4\", which is not a pitch")
        // A drum by name is a drum only on a drum track.
        assertThat(refused).contains("tracks[1].notes[0].pitch is \"kick\"")
        assertThat(entries.countBySessionId(session)).isEqualTo(before)

        val more = call(
            MidiCapability.WRITE,
            """{"tracks":[{"name":"A","instrument":"Piano","notes":[{"pitch":"C4","start":0,"duration":0,"velocity":200}]}]}""",
        ).path("error").stringValue()
        assertThat(more).contains("duration is 0.0; a note lasts longer than 0 beats")
        assertThat(more).contains("velocity is a whole number from 1 (softest) to 127 (loudest)")

        assertThat(call(MidiCapability.WRITE, """{"tracks":[]}""").path("error").stringValue()).contains("Give tracks")
        assertThat(call(MidiCapability.WRITE, """{"tracks":[{"name":"A","notes":[]}]}""").path("error").stringValue())
            .contains("no notes")
        // "Sax" could be four instruments; a name is taken only where it means one.
        assertThat(call(MidiCapability.WRITE, """{"tracks":[{"instrument":"Sax","notes":[{"pitch":60,"start":0,"duration":1}]}]}""").path("error").stringValue())
            .contains("could be").contains("Alto Sax")
        assertThat(call(MidiCapability.WRITE, """{"append":"nothing.here","tracks":[{"name":"A","instrument":0,"notes":[{"pitch":60,"start":0,"duration":1}]}]}""").path("error").stringValue())
            .contains("nothing.here")
        assertThat(entries.countBySessionId(session)).isEqualTo(before)
    }

    @Test
    fun `tracks sent as the text of a list are read as the list`() {
        val tracks = mapper.writeValueAsString("""[{"name":"A","instrument":0,"notes":[{"pitch":"C4","start":0,"duration":1}]}]""")
        val written = call(MidiCapability.WRITE, """{"tracks":$tracks}""")
        assertThat(written.path("error").isMissingNode).describedAs(written.toString()).isTrue()
    }

    @Test
    fun `a rendered FLAC is a FLAC of the piece's length at the configured rate, kept as audio`() {
        tunables.set(Tunable.MIDI_RENDER_SAMPLE_RATE, 32000, "alice")
        val key = call(MidiCapability.WRITE, song).path(MidiCapability.KEY).stringValue()

        val rendered = call(MidiCapability.RENDER, """{"key":"$key","format":"flac"}""")
        assertThat(rendered.path("error").isMissingNode).describedAs(rendered.toString()).isTrue()
        val audio = rendered.path(MidiCapability.KEY).stringValue()
        assertThat(audio).endsWith(".flac")
        assertThat(scratch.kindOf(session, audio)).isEqualTo(StoredKind("audio/flac", true))
        // With no SoundFont set, the answer says which bank played, as a fact.
        assertThat(rendered.path("soundbankNote").stringValue()).contains("ORKNUX_MUSIC_SOUNDFONT")

        val flac = bytes(audio)
        assertThat(String(flac, 0, 4, Charsets.US_ASCII)).isEqualTo("fLaC")
        // STREAMINFO: after the block header, two block sizes and two frame sizes, then 64 bits of rate, channels, depth and length.
        assertThat(flac[4].toInt() and 0x7f).isEqualTo(0)
        val packed = (18 until 26).fold(0L) { held, i -> (held shl 8) or (flac[i].toLong() and 0xff) }
        val rate = (packed ushr 44).toInt()
        val channels = ((packed ushr 41) and 7).toInt() + 1
        val samples = packed and 0xFFFFFFFFFL
        assertThat(rate).isEqualTo(32000)
        assertThat(channels).isEqualTo(2)
        // Four seconds of notes and the two the sound is let ring.
        assertThat(samples.toDouble() / rate).isCloseTo(6.0, within(0.05))
        assertThat(rendered.path("seconds").doubleValue()).isCloseTo(6.0, within(0.05))
        assertThat(rendered.path("sampleRate").intValue()).isEqualTo(32000)
        // And it is sound, not six seconds of silence.
        assertThat(loudness(audio)).isGreaterThan(0.001)
    }

    @Test
    fun `a rendered MP3 is MPEG audio that decodes to the piece's length`() {
        val key = call(MidiCapability.WRITE, song).path(MidiCapability.KEY).stringValue()

        val rendered = call(MidiCapability.RENDER, """{"key":"$key"}""")
        assertThat(rendered.path("error").isMissingNode).describedAs(rendered.toString()).isTrue()
        assertThat(rendered.path("format").stringValue()).isEqualTo(MidiCapability.DEFAULT_FORMAT)
        val audio = rendered.path(MidiCapability.KEY).stringValue()
        assertThat(audio).endsWith(".mp3")
        assertThat(scratch.kindOf(session, audio)).isEqualTo(StoredKind("audio/mpeg", true))
        assertThat(rendered.path("bitrateKbits").intValue()).isEqualTo(tunables[Tunable.MIDI_RENDER_MP3_KBITS])

        val mp3 = bytes(audio)
        // An ID3v2 tag may come first; its size is four seven-bit bytes.
        var at = 0
        if (String(mp3, 0, 3, Charsets.US_ASCII) == "ID3") {
            at = 10 + ((mp3[6].toInt() and 0x7f) shl 21 or ((mp3[7].toInt() and 0x7f) shl 14) or ((mp3[8].toInt() and 0x7f) shl 7) or (mp3[9].toInt() and 0x7f))
        }
        assertThat(mp3[at].toInt() and 0xff).isEqualTo(0xff)
        assertThat(mp3[at + 1].toInt() and 0xe0).isEqualTo(0xe0)
        // MPEG-1 Layer III.
        assertThat((mp3[at + 1].toInt() shr 1) and 0x0f).isEqualTo(0b1101)

        assertThat(decodedSeconds(audio, ".mp3")).isCloseTo(6.0, within(0.15))
        assertThat(loudness(audio)).isGreaterThan(0.001)
    }

    @Test
    fun `a WAV is written by the JDK and reads back as stereo PCM of the same length`() {
        val key = call(MidiCapability.WRITE, song).path(MidiCapability.KEY).stringValue()
        val audio = call(MidiCapability.RENDER, """{"key":"$key","format":"wav"}""").path(MidiCapability.KEY).stringValue()
        assertThat(scratch.kindOf(session, audio)).isEqualTo(StoredKind("audio/wav", true))
        AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes(audio))).use { wav ->
            assertThat(wav.format.channels).isEqualTo(2)
            assertThat(wav.format.sampleRate.toInt()).isEqualTo(tunables[Tunable.MIDI_RENDER_SAMPLE_RATE])
            assertThat(wav.frameLength.toDouble() / wav.format.sampleRate).isCloseTo(6.0, within(0.01))
        }
    }

    @Test
    fun `a render that cannot be done says why`() {
        val key = call(MidiCapability.WRITE, song).path(MidiCapability.KEY).stringValue()
        assertThat(call(MidiCapability.RENDER, """{"key":"$key","format":"ogg"}""").path("error").stringValue())
            .contains("ogg is not a format").contains("flac, mp3, wav")
        assertThat(call(MidiCapability.RENDER, """{"key":"nothing.here"}""").path("error").stringValue()).contains("nothing.here")

        scratch.put(session, "notes.txt", mapper.writeValueAsString("just words"), StoredKind("text/plain", false))
        assertThat(call(MidiCapability.RENDER, """{"key":"notes.txt"}""").path("error").stringValue())
            .contains("holds text/plain, not a MIDI file")

        tunables.set(Tunable.MIDI_RENDER_LONGEST_SECONDS, 2, "alice")
        assertThat(call(MidiCapability.RENDER, """{"key":"$key"}""").path("error").stringValue())
            .contains("plays for 4 seconds").contains("the longest this server renders is 2")
    }

    /* ------------------------------------------------------- being found */

    @Test
    fun `a model looking for music by its own words finds the two tools`() {
        val specs = embedded.toolSpecs()
        assertThat(specs.map { it.name }).contains("midi_write", "midi_render")
        assertThat(BuiltInTools.GRANTED).contains("midi_write", "midi_render")

        val shed = searching.shed(specs, mutableSetOf(), room = { 10 })
        for (query in listOf("midi", "music", "melody", "mp3", "song", "compose a melody", "make a song as mp3")) {
            val said = shed.run(ToolCall("1", ToolSearchTools.SEARCH, mapper.writeValueAsString(mapOf("queries" to listOf(query)))))
            val listed = said.lines().filter { it.contains(" - ") }.map { it.substringBefore(" - ").trim() }
            assertThat(listed.take(3)).describedAs("$query: $said").containsAnyOf("midi_write", "midi_render")
            assertThat(listed.first()).describedAs("$query: $said").startsWith("midi_")
        }
    }

    /* ------------------------------------------------------- the skill */

    @Test
    fun `the music skill is listed, found by the words people use, and loads with every tool it names declared`() {
        val skill = builtIns.catalogs().single().skills.single { it.name == "Composing music (MIDI)" }
        assertThat(skill.content).doesNotContain("{{tool:", "{{param:")

        val declared = embedded.toolSpecs().map { it.name }.toSet() + BuiltInTools.GRANTED
        val named = Regex("`([a-z]+_[a-zA-Z]+)`").findAll(skill.content).map { it.groupValues[1] }.toSet()
        assertThat(named).contains("midi_write", "midi_read", "midi_render", AgentTools.SAVE_ARTIFACT)
        assertThat(declared).containsAll(named)
        // The parameters it tells a model to send are the ones the tools declare.
        val params = midi.tools().flatMap { tool -> tool.params.map { it.name } }
        assertThat(params).contains(MidiWriter.APPEND, MidiWriter.AT, MidiCapability.FORMAT)
        assertThat(skill.content).contains("`${MidiWriter.APPEND}`", "`${MidiWriter.AT}`", "`${MidiCapability.FORMAT}`")
        assertThat(skill.content).contains("`${MidiWriter.PITCH}`", "`${MidiWriter.START}`", "`${MidiWriter.DURATION}`", "\"${MidiWriter.DRUMS}\": true")

        val agent = requireNotNull(
            agents.save(
                Agent(workspaceId = workspaceId, name = "Composer ${System.nanoTime()}", type = AgentType.LLM)
                    .apply { skillCatalogs = mutableListOf(BuiltInSkills.CATALOG) },
            ).id,
        ).let { requireNotNull(agents.findById(it).orElse(null)) }
        assertThat(skills.list(agent).map { it.name }).contains(skill.name)
        for (word in listOf("midi", "music", "melody", "song", "compose", "mp3")) {
            assertThat(skills.search(agent, word).map { it.name }).describedAs(word).contains(skill.name)
        }
        assertThat(skills.load(agent, skill.key)?.content).isEqualTo(skill.content)
    }

    /* ------------------------------------------------------- the migration */

    /**
     * The migration that marks the two tools Always for the agents there were,
     * run again here against this engine's own database: an agent made without
     * them has both, once, after it - and running it twice changes nothing.
     */
    @Test
    fun `the migration gives an existing agent both tools, once`() {
        val agentId = requireNotNull(agents.save(Agent(workspaceId = workspaceId, name = "Old ${System.nanoTime()}", type = AgentType.LLM)).id)
        val sqlite = dataSource.connection.use { it.metaData.databaseProductName.contains("SQLite", ignoreCase = true) }
        val script = if (sqlite) "db/migration/sqlite/V117__midi_write_and_render.sql" else "db/migration/postgresql/V439__midi_write_and_render.sql"
        repeat(2) { ResourceDatabasePopulator(ClassPathResource(script)).execute(dataSource) }

        val required = requireNotNull(agents.findById(agentId).orElse(null)).requiredTools
        assertThat(required.filter { it.startsWith("midi_") }).containsExactlyInAnyOrder("midi_write", "midi_render")
    }

    private fun bytes(key: String): ByteArray {
        val stored = requireNotNull(scratch.get(session, key))
        return Base64.getDecoder().decode(mapper.readTree(stored).stringValue())
    }

    /** How long the audio under [key] is, decoded by FFmpeg sample by sample. */
    private fun decodedSeconds(key: String, extension: String): Double = withFile(key, extension) { file ->
        FFmpegFrameGrabber(file.toFile()).use { grabber ->
            grabber.start()
            var samples = 0L
            while (true) {
                val frame = grabber.grabSamples() ?: break
                val held = frame.samples ?: continue
                samples += if (held.size == 1) held[0].limit() / grabber.audioChannels else held[0].limit()
            }
            samples.toDouble() / grabber.sampleRate
        }
    }

    /** The mean absolute level of the audio under [key], as a fraction of full scale. */
    private fun loudness(key: String): Double = withFile(key, "." + key.substringAfterLast('.')) { file ->
        FFmpegFrameGrabber(file.toFile()).use { grabber ->
            grabber.sampleMode = org.bytedeco.javacv.FrameGrabber.SampleMode.FLOAT
            grabber.start()
            var sum = 0.0
            var count = 0L
            while (true) {
                val frame = grabber.grabSamples() ?: break
                frame.samples?.forEach { buffer ->
                    val floats = buffer as java.nio.FloatBuffer
                    for (i in 0 until floats.limit()) {
                        sum += Math.abs(floats.get(i))
                        count++
                    }
                }
            }
            if (count == 0L) 0.0 else sum / count
        }
    }

    private fun <T> withFile(key: String, extension: String, use: (java.nio.file.Path) -> T): T {
        val file = Files.createTempFile("midi-test-", extension)
        try {
            Files.write(file, bytes(key))
            return use(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
