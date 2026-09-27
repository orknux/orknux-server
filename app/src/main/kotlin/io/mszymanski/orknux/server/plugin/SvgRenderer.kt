package io.mszymanski.orknux.server.plugin

import org.apache.batik.transcoder.SVGAbstractTranscoder
import org.apache.batik.transcoder.TranscoderException
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.slf4j.LoggerFactory
import java.awt.Color
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.imageio.ImageIO

/**
 * An SVG, drawn as a PNG.
 *
 * The server does this because the sandbox cannot: GraalJS here has no
 * WebAssembly at all — `typeof WebAssembly` is `undefined` and only the `js`
 * language is installed, which was checked rather than assumed — so the usual
 * answer of bundling a WASM rasteriser is not available to a plugin. Nor is
 * there a pure-JavaScript one worth having: drawing SVG means a full 2D engine,
 * path filling and font rendering.
 *
 * ## What is switched off, and why it is switched off here
 *
 * SVG is not a picture format. It is a document with a scripting model, an
 * external-reference model and an entity model, and a renderer that honours all
 * three is a program that fetches what the document tells it to fetch and runs
 * what the document tells it to run. Everything below is set explicitly rather
 * than left to the library's defaults, because those have moved between
 * versions and the one thing this must not do is change behaviour when the
 * dependency is bumped.
 *
 * What a plugin hands over is a diagram it drew a moment ago, which needs none
 * of it. The guards are for the day something hands over a document from
 * somewhere else.
 */
@Component
class SvgRenderer {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param svg the document, as text.
     * @param width how wide the picture should be in pixels, or null for the
     *   size the document itself declares.
     * @param scale how many times the declared size to draw it at, where no
     *   width was asked for. Issue #529: a diagram drawn at its natural 290
     *   pixels has one-pixel lines, and on a phone or a dark theme they are
     *   barely there.
     */
    fun png(svg: String, width: Int?, scale: Double = 1.0): Drawing {
        if (svg.isBlank()) return Drawing.Refused("there is nothing to draw: the svg is empty")
        if (svg.length > MOST_SOURCE) {
            return Drawing.Refused("that svg is ${svg.length} characters, and $MOST_SOURCE is the most this draws")
        }

        /*
         * A shape is not proof, and it is not meant to be. What refuses a
         * document that is not an SVG is the parser; this is here so the
         * common mistake - handing over base64, or a whole HTML page - is
         * answered in a sentence rather than as a parse error forty lines long.
         */
        if (!svg.trimStart().let { it.startsWith("<svg") || it.startsWith("<?xml") || it.startsWith("<!") }) {
            return Drawing.Refused("that does not look like svg: it has to be the markup itself, not base64 or a url")
        }

        if (width != null && (width < 1 || width > MOST_WIDTH)) {
            return Drawing.Refused("a width has to be between 1 and $MOST_WIDTH pixels")
        }

        // The declared width, scaled, where none was asked for - capped, like everything here.
        val drawnWidth = width ?: declaredWidth(svg)
            ?.takeIf { scale > 1.0 }
            ?.let { (it * scale).toInt().coerceAtMost(MOST_WIDTH) }

        val out = ByteArrayOutputStream()
        val transcoder = PNGTranscoder().apply {
            /*
             * White behind it, always. Issue #529: left alone the picture is
             * transparent, and a diagram's black lines and text on a
             * transparent ground are drawn onto whatever the reader's theme is
             * - in Slack's dark one, onto near-black, where they vanish.
             */
            addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, Color.WHITE)
            /*
             * No scripts, and nothing fetched.
             *
             * Between them these are the whole of what makes rendering somebody
             * else's SVG dangerous: a document that runs code, and a document
             * that makes this server issue requests of its choosing - to an
             * intranet address, or to a file:// url that reads the disk. Off,
             * by name, both of them.
             */
            addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false)
            addTranscodingHint(SVGAbstractTranscoder.KEY_CONSTRAIN_SCRIPT_ORIGIN, true)
            addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, false)

            // A ceiling on what comes out, not only on what goes in: a small
            // document can declare an enormous canvas, and the bytes for it are
            // this server's memory.
            if (drawnWidth != null) {
                addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, drawnWidth.toFloat())
            }
            addTranscodingHint(SVGAbstractTranscoder.KEY_MAX_WIDTH, MOST_WIDTH.toFloat())
            addTranscodingHint(SVGAbstractTranscoder.KEY_MAX_HEIGHT, MOST_HEIGHT.toFloat())
        }

        return try {
            transcoder.transcode(TranscoderInput(StringReader(svg)), TranscoderOutput(out))
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) {
                Drawing.Refused("the renderer produced nothing; the svg may declare no size")
            } else {
                /*
                 * Measured from the file, not from the hints.
                 *
                 * Reading the header back is a few bytes of work and it is the
                 * only answer that is true: a hint is a request, the aspect
                 * ratio decides the other side, and the maximums above can
                 * quietly bring both down.
                 */
                val drawn = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
                Drawing.Drawn(bytes, drawn?.width ?: 0, drawn?.height ?: 0)
            }
        } catch (failure: TranscoderException) {
            /*
             * The library's own sentence, and its cause where it has one.
             *
             * A TranscoderException often carries no message of its own - what
             * went wrong is on the exception it wraps - so reporting only the
             * top of the chain says "could not draw that svg: null", which
             * tells nobody anything. The cause is where the element and the
             * attribute are named.
             */
            val said = failure.message
                ?: failure.exception?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: failure.cause?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: "the document could not be read"
            log.warn("an svg could not be drawn: {}", said, failure)
            Drawing.Refused("could not draw that svg: $said")
        } catch (failure: OutOfMemoryError) {
            // A canvas past what the ceilings caught. Reported rather than
            // allowed to take a request thread down with it.
            log.warn("an svg asked for more memory than this server would give it", failure)
            Drawing.Refused("that svg asks for a picture too large to draw")
        } catch (failure: Exception) {
            log.warn("an svg could not be drawn", failure)
            Drawing.Refused("could not draw that svg: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    sealed interface Drawing {
        /**
         * The picture, and how big it came out.
         *
         * The size is read back off the bytes rather than assumed from what
         * was asked for: a width is a request - the document's own aspect
         * ratio decides the height, and the ceilings here can refuse both - so
         * "what did I actually get" is a different question from "what did I
         * ask for". Without it a plugin holding a blank or a giant has no way
         * to tell which, which is the difference between diagnosing and
         * guessing. `pngFromPdf` has always said; this now says the same.
         */
        data class Drawn(val png: ByteArray, val width: Int, val height: Int) : Drawing

        /** Said back to the caller as it stands, so it knows what to do differently. */
        data class Refused(val reason: String) : Drawing
    }

    /** The width the document declares for itself, in pixels, if it says. */
    private fun declaredWidth(svg: String): Int? =
        DECLARED_WIDTH.find(svg.take(HEADER))?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }

    private companion object {
        /** `<svg ... width="290px"`: the root's width, read off its opening tag. */
        val DECLARED_WIDTH = Regex("<svg[^>]*?\\swidth=\"([0-9.]+)(?:px)?\"")

        /** Far enough in to be past the prolog and the root's attributes. */
        const val HEADER = 4096

        /** As long a document as this draws. A diagram, not a map. */
        const val MOST_SOURCE = 2 * 1024 * 1024

        const val MOST_WIDTH = 4096
        const val MOST_HEIGHT = 4096
    }
}
