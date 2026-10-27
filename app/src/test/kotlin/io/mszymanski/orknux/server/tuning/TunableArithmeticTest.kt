package io.mszymanski.orknux.server.tuning

import io.mszymanski.orknux.connector.connection.SlackWebApi
import io.mszymanski.orknux.server.design.ExportLimits
import io.mszymanski.orknux.server.design.MaskedComposite
import io.mszymanski.orknux.server.design.documentSnippet
import io.mszymanski.orknux.server.embedded.MidiRenderer
import io.mszymanski.orknux.server.embedded.PdfText
import io.mszymanski.orknux.server.security.GuardedFetch
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage

/**
 * Every place a setting with no ceiling was found doing arithmetic that wraps,
 * or sizing something from the setting, asked at [Int.MAX_VALUE] - the largest
 * number the Limits box takes. Each answer has to be non-negative and unwrapped,
 * and nothing may throw for the size alone.
 */
class TunableArithmeticTest {

    private val most = Int.MAX_VALUE

    @Test
    fun `megabytes are counted in Long`() {
        assertThat(TunableArithmetic.bytesOf(most)).isEqualTo(most.toLong() * 1024 * 1024).isPositive()
        // What NetworkPluginHost.mostDownloadedBytes did before: Int arithmetic, negative at 2,048 MB.
        assertThat(2048 * 1024 * 1024).isNotPositive()
        assertThat(TunableArithmetic.bytesOf(2048)).isEqualTo(2_147_483_648L)
    }

    @Test
    fun `a sum or product of a setting is held rather than wrapped`() {
        assertThat(TunableArithmetic.plus(most, 1)).isEqualTo(most)
        assertThat(TunableArithmetic.times(2, most)).isEqualTo(most)
        assertThat(TunableArithmetic.times(most, most)).isEqualTo(most)
        assertThat(TunableArithmetic.plus(5, 1)).isEqualTo(6)
        assertThat(TunableArithmetic.times(2, 5)).isEqualTo(10)
    }

    /** DesignImageAPI: a feather of any width blurs no further than the picture, and loops no longer for it. */
    @Test
    fun `a feather wider than the picture reaches its edge and no further`() {
        assertThat(MaskedComposite.reachOf(most, 640)).isEqualTo(640)
        assertThat(MaskedComposite.reachOf(8, 640)).isEqualTo(4)
        assertThat(MaskedComposite.reachOf(0, 640)).isEqualTo(1)
    }

    /** PdfText: a snippet as long as there is is the whole page, not a negative index. */
    @Test
    fun `a pdf snippet of any length is the page around the match`() {
        val text = "the quick brown fox"
        val said = PdfText(Tuning.DEFAULTS).snippet(text, 10..14, most)
        assertThat(said).isEqualTo("the quick «brown» fox")
    }

    /** DocumentAPI.snippet: the same for a document search. */
    @Test
    fun `a document snippet of any length is the whole text`() {
        assertThat(documentSnippet("a needle in a haystack", "needle", most)).isEqualTo("a needle in a haystack")
        assertThat(documentSnippet("a needle in a haystack", "absent", most)).isEqualTo("a needle in a haystack")
    }

    /** SlackWebApi.download: a limit past an array's length reads what an array holds. */
    @Test
    fun `a download limit past what an array holds is held to it`() {
        val readable = SlackWebApi.readableOf(TunableArithmetic.bytesOf(most))
        assertThat(readable).isPositive().isLessThan(most)
        assertThat(readable + 1).isPositive()
        assertThat(SlackWebApi.readableOf(5L * 1024 * 1024)).isEqualTo(5 * 1024 * 1024)
    }

    /** GuardedFetch.read: the HTTP tools' limit is characters × 4 in Long, which can be far past an array. */
    @Test
    fun `a fetch limit past what an array holds reads the body`() {
        val (bytes, over) = GuardedFetch.read(ByteArrayInputStream("hello".toByteArray()), most * 4L)
        assertThat(String(bytes)).isEqualTo("hello")
        assertThat(over).isFalse()
    }

    /** DesignVideoExports: a deadline of Int.MAX_VALUE seconds that wraps past Long's end is still in the future. */
    @Test
    fun `an export deadline that wraps is not taken as passed`() {
        val out = Files.createTempFile("export-", ".bin")
        try {
            val limits = ExportLimits(seconds = most, megabytes = most, out = out, clock = { Long.MAX_VALUE - 1 })
            assertThatCode { limits.check(final = true) }.doesNotThrowAnyException()
        } finally {
            Files.deleteIfExists(out)
        }
    }

    /** MidiRenderer: with no longest piece, a WAV past one array is refused in words rather than failing as it is written. */
    @Test
    fun `a wav longer than one array holds is refused in words`() {
        val renderer = MidiRenderer(
            Tuning { if (it == Tunable.MIDI_RENDER_LONGEST_SECONDS) most else it.fallback },
            "",
        )
        // Four hours at 120 beats a minute: 28,800 beats, which at 44.1 kHz stereo is 2.5 GB of samples.
        val sequence = Sequence(Sequence.PPQ, 480)
        val track = sequence.createTrack()
        track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 0, 60, 90), 0))
        track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 28_800L * 480))
        val midi = ByteArrayOutputStream().also { MidiSystem.write(sequence, 1, it) }.toByteArray()

        val rendered = renderer.render(midi, "wav")
        assertThat(rendered).isInstanceOf(MidiRenderer.Rendered.Refused::class.java)
        assertThat((rendered as MidiRenderer.Rendered.Refused).reason).contains("FLAC or MP3")
    }
}
