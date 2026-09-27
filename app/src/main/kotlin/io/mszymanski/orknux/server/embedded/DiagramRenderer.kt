package io.mszymanski.orknux.server.embedded

import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import net.sourceforge.plantuml.core.DiagramDescription
import net.sourceforge.plantuml.security.SecurityProfile
import net.sourceforge.plantuml.security.SecurityUtils
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * A diagram, drawn here. Issue #487.
 *
 * ## What this replaces
 *
 * A hand-written mermaid parser in a JavaScript sandbox that knew five kinds -
 * flowchart, sequence, state, class, ER - and refused everything else by name.
 * A model asking for a pie chart, a gantt, a mindmap or a timeline got told the
 * headers that work, which reads as "try another spelling", so it tried another
 * spelling. Each further kind was a day of parser and a day of layout.
 *
 * There is also a PlantUML *plugin*, which is PlantUML compiled to JavaScript
 * by TeaVM, and its README is a list of what a sandbox costs: an event loop
 * turned by hand after the call returns because the engine defers onto a timer
 * that will never fire, and font metrics answered out of a table of Helvetica's
 * advance widths because there is no canvas to measure with. Every diagram was
 * laid out against the wrong measurements and looked it.
 *
 * Here PlantUML is PlantUML. It measures text with the JVM's font stack,
 * because there is one.
 *
 * ## Mermaid is still what a model writes
 *
 * Models write mermaid, and telling them not to is a losing argument, so
 * mermaid source is accepted and translated - see [Mermaid]. What cannot be
 * translated is drawn by PlantUML directly, which is the other half of why this
 * is worth doing: `@startuml` opens the whole language, and `pie`, `gantt`,
 * `mindmap`, `journey` and the rest stop being refusals.
 *
 * ## What is switched off
 *
 * PlantUML reads includes, fetches sprites over the network and can be asked to
 * run things. A diagram arriving here was written by a model a moment ago,
 * which needs none of it, so the security profile is pinned rather than left at
 * the library's default: those defaults have moved between versions, and the
 * one thing this must not do is change what it reaches when the dependency is
 * bumped.
 */
@Component
class DiagramRenderer {

    private val log = LoggerFactory.getLogger(javaClass)

    /** What came of asking for a diagram. */
    sealed interface Drawing {
        data class Drawn(val svg: String, val kind: String) : Drawing

        data class Refused(val reason: String) : Drawing
    }

    init {
        /*
         * Written before PlantUML is ever touched, because the profile is read
         * once in a static initialiser and there is no setter - the library
         * takes a system property, falling back to the environment, and
         * whatever it reads the first time a diagram class loads is what it
         * keeps for the life of the process.
         *
         * SANDBOX is the profile that reaches nothing at all: no include off
         * the disk, no sprite over the network, no local file of any kind. A
         * diagram arriving here was written by a model a moment ago and needs
         * none of it. Pinned rather than left at the default because those have
         * moved between versions, and the one thing this must not do is change
         * what it reaches when the dependency is bumped.
         *
         * Only where nobody has said otherwise: an installation that sets the
         * variable meant it, and quietly overriding somebody's deployment is
         * worse than the setting they chose.
         */
        if (System.getProperty(PROFILE).isNullOrEmpty() && System.getenv(PROFILE).isNullOrEmpty()) {
            System.setProperty(PROFILE, SecurityProfile.SANDBOX.name)
        }
    }

    /**
     * And then checked, rather than assumed.
     *
     * Reading it back is what turns "we set a property" into "the library is in
     * this mode": the property is read at a class initialisation this code does
     * not control, so the only honest way to know it took is to ask. Loud if it
     * did not, because the difference is whether a diagram can reach the
     * network.
     */
    @PostConstruct
    fun checkProfile() {
        val held = runCatching { SecurityUtils.getSecurityProfile() }.getOrNull()
        if (held == SecurityProfile.SANDBOX) {
            log.info("Diagrams are drawn here, reaching nothing")
        } else {
            log.warn(
                "Diagrams are drawn under the {} profile rather than SANDBOX; a diagram may reach files or the network",
                held ?: "unknown",
            )
        }
    }

    /**
     * One diagram, as SVG.
     *
     * SVG rather than a raster, because a diagram in a document should be
     * vectors: it stays sharp at any size, it is a tenth of the bytes, and the
     * PDF writer draws it into the page rather than pasting a picture onto it.
     * [SvgRenderer][io.mszymanski.orknux.server.plugin.SvgRenderer] turns it
     * into a PNG where somebody actually wants a picture.
     */
    fun svg(source: String): Drawing {
        val written = source.trim()
        if (written.isEmpty()) return Drawing.Refused("there is nothing to draw: the diagram is empty")
        if (written.length > MOST_CHARS) {
            return Drawing.Refused("that diagram is longer than $MOST_CHARS characters, which is the most this draws")
        }

        /*
         * A chart is refused here rather than attempted. PlantUML draws no pie
         * and no xy plot, and handing it one does not fail - it answers a
         * picture of a syntax error, which would go into somebody's report
         * looking like a drawing. Naming the tool that does draw it is the
         * difference between a retry and a dead end, which is the whole lesson
         * of #503.
         */
        if (Mermaid.isChart(written)) {
            return Drawing.Refused(
                "that is a chart rather than a diagram, and this draws diagrams. Call charts_render for a " +
                    "pie, a donut, bars, columns, a line or an area.",
            )
        }

        val uml = Mermaid.asPlantUml(written)
        val drawn = ByteArrayOutputStream()
        val described: DiagramDescription = try {
            SourceStringReader(uml).outputImage(drawn, FileFormatOption(FileFormat.SVG))
        } catch (failure: Exception) {
            log.warn("A diagram could not be drawn: {}", failure.message)
            return Drawing.Refused(failure.message ?: "the diagram could not be drawn")
        }

        val svg = drawn.toString(Charsets.UTF_8)
        /*
         * PlantUML answers a picture either way: where it cannot read the
         * source it draws the error onto the canvas and returns it, which is
         * right for somebody looking at a screen and wrong here - a document
         * would carry a red box saying "syntax error" and the call would look
         * like it worked. So the description is read, and a diagram it could
         * not parse is a refusal with the line in it.
         */
        /*
         * And not only when it says "syntax error". Issue #525: PlantUML also
         * describes a failed drawing as `(Error)`, which this let through - so a
         * mermaid flowchart it could not read came back as a successful picture,
         * with a key to send, of a red box. The model uploaded it believing it
         * had a diagram. The canvas is read as well as the description, because
         * the drawn error is the one place the failure is certain to be.
         */
        val said = described.description.orEmpty()
        if (said.isEmpty() || said.contains("error", ignoreCase = true) ||
            svg.contains("Syntax Error", ignoreCase = true)
        ) {
            return Drawing.Refused(reasonIn(svg) ?: "that is not a diagram this can read")
        }
        return Drawing.Drawn(svg, said)
    }

    /**
     * The line PlantUML wrote into the picture, pulled back out as text.
     *
     * The error is drawn as `<text>` elements, which is the only place it
     * exists - there is no exception and no code, and a refusal saying "it did
     * not work" sends somebody back to guessing.
     */
    private fun reasonIn(svg: String): String? {
        val said = TEXT.findAll(svg)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .take(MOST_REASON_LINES)
            .toList()
        return said.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private companion object {
        /** Past this it is a program, not a diagram, and PlantUML will take minutes over it. */
        const val MOST_CHARS = 100_000

        /** Enough to carry "syntax error" and the line it is on. */
        const val MOST_REASON_LINES = 4

        /** What PlantUML reads its profile from, as a property first and then the environment. */
        const val PROFILE = "PLANTUML_SECURITY_PROFILE"

        val TEXT = Regex("<text[^>]*>([^<]*)</text>")
    }
}
