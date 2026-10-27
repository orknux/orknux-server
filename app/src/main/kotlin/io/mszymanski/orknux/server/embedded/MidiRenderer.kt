package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.file.Files
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import javax.sound.midi.Soundbank
import javax.sound.midi.Synthesizer
import javax.sound.midi.SysexMessage
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * A MIDI file played into sound, offline, and encoded as FLAC, MP3 or WAV.
 *
 * **Synthesised by the JDK's own Gervill**, the software synthesizer behind
 * `MidiSystem.getSynthesizer()`, through the one door it has for rendering
 * faster than real time: `AudioSynthesizer.openStream`, which hands back the
 * sound as a stream to read rather than playing it on a sound card a server
 * does not have. Every event of the file is sent first with its time in
 * microseconds, and the stream is read for as long as the piece lasts - so a
 * minute of music takes as long as the CPU needs, not a minute. That class is
 * in a package `java.desktop` does not export, which is why the jar carries
 * `Add-Exports` (see app/pom.xml) and why [unavailable] says so plainly where a
 * JVM was started without it.
 *
 * **What it sounds like is the SoundFont's.** Gervill brings a bank of its own
 * - small, and on Linux generated rather than sampled - so an administrator may
 * point `ORKNUX_MUSIC_SOUNDFONT` at a General MIDI `.sf2`, read on every render.
 * Which bank played is in every answer as a fact.
 *
 * **Encoded by FFmpeg** as JavaCV carries it (see [io.mszymanski.orknux.server.design.VideoFrames]):
 * its native FLAC encoder, and LAME for MP3, which the bytedeco build links in.
 * WAV is written by the JDK and needs neither.
 */
@Component
class MidiRenderer(
    private val tuning: Tuning,
    @param:Value("\${orknux.music.soundfont:}") private val soundfont: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** A sound made, or why not. */
    sealed interface Rendered {
        data class Made(
            val bytes: ByteArray,
            val contentType: String,
            val extension: String,
            val seconds: Double,
            val sampleRate: Int,
            val channels: Int,
            /** The bank that played it, by the name it gives itself. */
            val soundbank: String,
            /** Whether that was an administrator's SoundFont rather than the JDK's own bank. */
            val soundfont: Boolean,
            /** A fact about the bank worth saying, where there is one. */
            val soundbankNote: String?,
            val bitrateKbits: Int?,
        ) : Rendered

        data class Refused(val reason: String) : Rendered
    }

    /**
     * Why this JVM cannot synthesise offline, or null where it can: started
     * without the export the jar's manifest asks for, or with no Gervill.
     */
    fun unavailable(): String? = runCatching {
        val synthesizer = Class.forName(AUDIO_SYNTHESIZER)
        if (!synthesizer.module.isExported(synthesizer.packageName, MidiRenderer::class.java.module)) {
            "This server's Java was started without --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED, " +
                "which rendering MIDI offline needs. The server's jar asks for it when started with java -jar."
        } else {
            null
        }
    }.getOrElse { "This server's Java has no software synthesizer to render MIDI with: ${it.message ?: it.javaClass.simpleName}" }

    /** Why FFmpeg cannot encode here, or null where it can - its libraries are carried for Linux and Windows on x86-64. */
    fun encoderUnavailable(): String? = try {
        quiet
        null
    } catch (missing: Throwable) {
        "This server cannot encode audio on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}: " +
            (missing.message ?: missing.javaClass.simpleName) + ". WAV needs no encoder."
    }

    /** FFmpeg talks on stderr at every open; only what went wrong is worth a line in the server's output. */
    private val quiet by lazy { avutil.av_log_set_level(avutil.AV_LOG_ERROR) }

    fun render(midi: ByteArray, format: String): Rendered {
        val wanted = FORMATS[format.trim().lowercase()]
            ?: return Rendered.Refused("$format is not a format this renders: it makes ${FORMATS.keys.joinToString(", ")}.")
        unavailable()?.let { return Rendered.Refused(it) }
        if (wanted.codec != null) encoderUnavailable()?.let { return Rendered.Refused(it) }

        val sequence = runCatching { MidiSystem.getSequence(ByteArrayInputStream(midi)) }.getOrElse {
            return Rendered.Refused("That is not a MIDI file this can read: ${it.message ?: it.javaClass.simpleName}.")
        }
        val playing = sequence.microsecondLength / 1_000_000.0
        val longest = tuning[Tunable.MIDI_RENDER_LONGEST_SECONDS]
        if (playing > longest) {
            return Rendered.Refused(
                "That piece plays for ${MidiCapability.number(playing)} seconds, and the longest this server renders is $longest " +
                    "(an administrator sets it as ${Tunable.MIDI_RENDER_LONGEST_SECONDS.label}).",
            )
        }
        val rate = tuning[Tunable.MIDI_RENDER_SAMPLE_RATE]
        val kbits = tuning[Tunable.MIDI_RENDER_MP3_KBITS]
        val bank = bank()

        val frames = Math.ceil((sequence.microsecondLength / 1_000_000.0 + TAIL_SECONDS) * rate).toLong()
        /*
         * A WAV is written whole into one array, so with the longest piece set
         * past what one holds - it has no ceiling - a piece that long is refused
         * here rather than failing as it is written. FLAC and MP3 are compressed
         * and read back from a file.
         */
        if (wanted.codec == null && frames * FRAME_BYTES > io.mszymanski.orknux.server.tuning.TunableArithmetic.LARGEST_ARRAY - WAV_HEADER_ROOM) {
            return Rendered.Refused(
                "That piece plays for ${MidiCapability.number(playing)} seconds, which as a WAV at $rate Hz is more than " +
                    "one file this server holds; ask for FLAC or MP3 instead.",
            )
        }
        val bytes = synthesised(sequence, rate, frames, bank.soundbank) { sound ->
            when (wanted.codec) {
                null -> wav(sound)
                else -> encode(sound, rate, wanted, kbits)
            }
        }
        return Rendered.Made(
            bytes = bytes,
            contentType = wanted.contentType,
            extension = wanted.extension,
            seconds = frames.toDouble() / rate,
            sampleRate = rate,
            channels = CHANNELS,
            soundbank = bank.name,
            soundfont = bank.soundbank != null,
            soundbankNote = bank.note,
            bitrateKbits = if (wanted.extension == "mp3") kbits else null,
        )
    }

    private data class Bank(val soundbank: Soundbank?, val name: String, val note: String?)

    /** The administrator's SoundFont where one is set and reads, the JDK's own bank otherwise, and which. */
    private fun bank(): Bank {
        val path = soundfont.trim()
        val builtIn = "the JDK's built-in General MIDI bank"
        if (path.isEmpty()) {
            return Bank(null, builtIn, "No SoundFont is configured (ORKNUX_MUSIC_SOUNDFONT), so the JDK's small built-in bank played it.")
        }
        val file = File(path)
        return runCatching { MidiSystem.getSoundbank(file) }.fold(
            onSuccess = { Bank(it, it.name?.trim()?.ifEmpty { null } ?: file.name, null) },
            onFailure = {
                log.warn("The SoundFont {} could not be read, so the built-in bank played: {}", path, it.message)
                Bank(null, builtIn, "The configured SoundFont $path could not be read (${it.message ?: it.javaClass.simpleName}), so the JDK's built-in bank played it.")
            },
        )
    }

    /**
     * The piece as a stream of 16-bit stereo PCM, little-endian, handed to
     * [into] to encode as it is read: every event sent first with its time,
     * then [frames] read - to the end of the last note and [TAIL_SECONDS] past
     * it, where a held note's release and the reverb ring out. Read as it is
     * made rather than held, so five minutes of sound is never fifty megabytes
     * of PCM in the heap at once.
     */
    private fun <T> synthesised(sequence: Sequence, rate: Int, frames: Long, soundbank: Soundbank?, into: (AudioInputStream) -> T): T {
        val format = AudioFormat(rate.toFloat(), 16, CHANNELS, true, false)
        val synthesizer = Class.forName(SOFT_SYNTHESIZER).getConstructor().newInstance() as Synthesizer
        val stream = Class.forName(AUDIO_SYNTHESIZER)
            .getMethod("openStream", AudioFormat::class.java, Map::class.java)
            .invoke(synthesizer, format, null) as AudioInputStream
        try {
            soundbank?.let { synthesizer.loadAllInstruments(it) }
            val receiver = synthesizer.receiver
            timed(sequence).forEach { (micros, message) -> receiver.send(message, micros) }
            return into(AudioInputStream(stream, format, frames))
        } finally {
            runCatching { stream.close() }
            synthesizer.close()
        }
    }

    /**
     * Every event that makes sound, in the order it is heard and with its time
     * in microseconds - through the file's tempo changes, from whichever track
     * holds them. Meta events say nothing to a synthesizer and are left out.
     */
    private fun timed(sequence: Sequence): List<Pair<Long, javax.sound.midi.MidiMessage>> {
        val events: List<MidiEvent> = sequence.tracks
            .flatMap { track -> (0 until track.size()).map { track.get(it) } }
            .sortedBy { it.tick }
        if (sequence.divisionType != Sequence.PPQ) {
            val ticksPerSecond = sequence.divisionType.toDouble() * sequence.resolution
            return events.filter { it.message is ShortMessage || it.message is SysexMessage }
                .map { Math.round(it.tick / ticksPerSecond * 1_000_000.0) to it.message }
        }
        val perBeat = sequence.resolution.toDouble()
        var tempo = DEFAULT_TEMPO
        var lastTick = 0L
        var micros = 0.0
        val timed = mutableListOf<Pair<Long, javax.sound.midi.MidiMessage>>()
        for (event in events) {
            micros += (event.tick - lastTick) / perBeat * tempo
            lastTick = event.tick
            val message = event.message
            when {
                message is MetaMessage && message.type == SET_TEMPO && message.data.size >= 3 -> {
                    val data = message.data
                    val said = ((data[0].toInt() and 0xff) shl 16) or ((data[1].toInt() and 0xff) shl 8) or (data[2].toInt() and 0xff)
                    if (said > 0) tempo = said
                }
                message is ShortMessage || message is SysexMessage -> timed += Math.round(micros) to message
            }
        }
        return timed
    }

    /** A WAV is the PCM with a header, which the JDK writes; the stream's frame length is what goes in it. */
    private fun wav(sound: AudioInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        AudioSystem.write(sound, AudioFileFormat.Type.WAVE, out)
        return out.toByteArray()
    }

    /** [sound] through FFmpeg into a file of [wanted]'s format, read back; the file is the recorder's to write. */
    private fun encode(sound: AudioInputStream, rate: Int, wanted: Format, kbits: Int): ByteArray {
        quiet
        val file = Files.createTempFile("orknux-midi-", "." + wanted.extension)
        try {
            FFmpegFrameRecorder(file.toFile(), CHANNELS).use { recorder ->
                recorder.format = wanted.muxer
                recorder.audioCodec = requireNotNull(wanted.codec)
                recorder.sampleRate = rate
                recorder.audioChannels = CHANNELS
                if (wanted.extension == "mp3") recorder.audioBitrate = kbits * 1000
                recorder.start()
                val bytes = ByteArray(BLOCK_FRAMES * FRAME_BYTES)
                while (true) {
                    val read = sound.readNBytes(bytes, 0, bytes.size)
                    if (read <= 0) break
                    val samples = ShortArray(read / 2)
                    ByteBuffer.wrap(bytes, 0, read - read % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                    recorder.recordSamples(rate, CHANNELS, ShortBuffer.wrap(samples))
                }
                recorder.stop()
            }
            return Files.readAllBytes(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private data class Format(val extension: String, val contentType: String, val muxer: String, val codec: Int?)

    internal companion object {
        private const val SOFT_SYNTHESIZER = "com.sun.media.sound.SoftSynthesizer"
        private const val AUDIO_SYNTHESIZER = "com.sun.media.sound.AudioSynthesizer"
        private const val CHANNELS = 2
        private const val FRAME_BYTES = 2 * CHANNELS
        private const val BLOCK_FRAMES = 4096

        /** Room for a WAV's header beside its samples. */
        private const val WAV_HEADER_ROOM = 1024
        private const val SET_TEMPO = 0x51

        /** 120 beats a minute, which is what a file without a Set Tempo plays at. */
        private const val DEFAULT_TEMPO = 500_000

        /**
         * How long the sound goes on after the last note ends: its release and
         * the reverb, which a piece cut at the last note-off loses audibly. Part
         * of what a rendering is rather than a choice about one.
         */
        private const val TAIL_SECONDS = 2.0

        private val FORMATS = linkedMapOf(
            "flac" to Format("flac", "audio/flac", "flac", avcodecId { avcodec.AV_CODEC_ID_FLAC }),
            "mp3" to Format("mp3", "audio/mpeg", "mp3", avcodecId { avcodec.AV_CODEC_ID_MP3 }),
            "wav" to Format("wav", "audio/wav", "wav", null),
        )

        /** A codec's id, read where FFmpeg's classes load; -1 where they do not, which [encoderUnavailable] then says. */
        private fun avcodecId(id: () -> Int): Int = runCatching(id).getOrDefault(-1)

        val FORMAT_NAMES: Set<String> get() = FORMATS.keys
    }
}
