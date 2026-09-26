package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.workflow.script.FormatValidation
import org.commonmark.parser.Parser
import org.jsoup.Jsoup
import org.jsoup.parser.ParseSettings
import org.springframework.stereotype.Service
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.MarkedYAMLException
import tools.jackson.databind.ObjectMapper
import tools.jackson.core.JacksonException

/**
 * Whether a document is the thing it claims to be. Issue #416.
 *
 * Four formats, each read by the parser its world actually uses rather than by a
 * check written here: a validator that disagrees with the parser everybody else
 * runs is worse than no validator, because it sends somebody to look for a fault
 * that is not there.
 *
 * **What a problem is, and is not.** This answers whether the document parses,
 * and where it does not. It has no opinion about indentation, naming, tag order
 * or style - those are a linter's, they differ per house, and a tool that
 * refuses a valid document over them is a tool people learn to ignore.
 *
 * Markdown is the exception worth stating: almost any text is valid markdown, so
 * a parser answers "yes" to everything and tells nobody anything. What is
 * checked instead is the handful of things that are mistakes rather than style -
 * a code fence opened and never closed, a link written `[text](` and left
 * unfinished, a table row with a different number of cells from its header -
 * which is what actually goes wrong when a model writes a page.
 *
 * Nothing here reaches anything. It is a parse over a string, which is why it
 * needs no permission and is offered to every agent.
 */
@Service
class FormatValidator(private val mapper: ObjectMapper) : FormatValidation {

    override fun check(request: String): String {
        val node = runCatching { mapper.readTree(request) }.getOrNull()
            ?: return error("the request was not readable JSON")
        val format = node.path(FORMAT).takeIf { it.isTextual }?.stringValue()?.trim()?.lowercase()
            ?: return error("say which format to read it as in \"$FORMAT\": ${FORMATS.joinToString(", ")}")
        val text = node.path(TEXT).takeIf { it.isTextual }?.stringValue()
            ?: return error("say the document to read in \"$TEXT\"")

        return when (format) {
            "json" -> answer(json(text))
            "yaml", "yml" -> answer(yaml(text))
            "html", "xhtml" -> answer(html(text))
            "markdown", "md" -> answer(markdown(text))
            else -> error("there is no format called \"$format\". Try ${FORMATS.joinToString(", ")}.")
        }
    }

    /** One problem: where it is, and what a parser said about it. */
    data class Problem(val line: Int?, val column: Int?, val message: String)

    fun json(text: String): List<Problem> = try {
        mapper.readTree(text)
        emptyList()
    } catch (failure: JacksonException) {
        val at = failure.location
        listOf(Problem(at?.lineNr, at?.columnNr, failure.originalMessage ?: "This is not valid JSON."))
    }

    fun yaml(text: String): List<Problem> = try {
        // Every document in the stream, because a file of three is one file and
        // the second being broken is what somebody needs to hear.
        Yaml(LoaderOptions().apply { isAllowDuplicateKeys = false }).loadAll(text).forEach { _ -> }
        emptyList()
    } catch (failure: MarkedYAMLException) {
        val at = failure.problemMark
        listOf(Problem(at?.line?.plus(1), at?.column?.plus(1), failure.problem ?: "This is not valid YAML."))
    } catch (failure: RuntimeException) {
        listOf(Problem(null, null, failure.message ?: "This is not valid YAML."))
    }

    /**
     * HTML as a browser would read it, which is deliberately forgiving.
     *
     * jsoup in its tracking mode reports what it had to correct - an unclosed
     * tag, an attribute with no value, a stray `<` - and correcting is exactly
     * what a browser does, so these are things worth knowing rather than things
     * that stop the page working. Said as problems anyway: a page with three of
     * them is a page somebody wrote by hand and stopped halfway.
     */
    fun html(text: String): List<Problem> {
        val parser = org.jsoup.parser.Parser.htmlParser()
            .setTrackErrors(MOST_PROBLEMS)
            .settings(ParseSettings.preserveCase)
        Jsoup.parse(text, "", parser)
        return parser.errors.take(MOST_PROBLEMS).map { fault ->
            Problem(null, fault.position, fault.errorMessage)
        }
    }

    /**
     * Markdown, where the question is not "does it parse" - everything does.
     *
     * The parser runs, so a document that somehow breaks it is reported; what
     * is looked for beyond that is the small set of things that are mistakes
     * rather than taste.
     */
    fun markdown(text: String): List<Problem> {
        runCatching { Parser.builder().build().parse(text) }.onFailure { failure ->
            return listOf(Problem(null, null, failure.message ?: "This markdown could not be read."))
        }

        val problems = mutableListOf<Problem>()
        val lines = text.lines()

        /*
         * A fence opened and never closed, which is the one markdown mistake
         * that eats the rest of the document: everything after it is code.
         */
        var fenceAt: Int? = null
        var fence = ""
        lines.forEachIndexed { index, line ->
            val opening = line.trimStart()
            val mark = opening.takeWhile { it == '`' || it == '~' }
            if (mark.length < 3) return@forEachIndexed
            if (fenceAt == null) {
                fenceAt = index + 1
                fence = mark.take(1)
            } else if (mark.startsWith(fence)) {
                fenceAt = null
            }
        }
        fenceAt?.let { problems += Problem(it, null, "A code fence is opened here and never closed.") }

        // A link or an image written and left unfinished, which renders as the
        // markup itself and is always a mistake rather than a choice.
        lines.forEachIndexed { index, line ->
            if (UNFINISHED_LINK.containsMatchIn(line)) {
                problems += Problem(index + 1, null, "A link is opened here and its address is never closed.")
            }
        }

        /*
         * And a table whose rows do not match its header, which renders as a
         * table with a column missing rather than as an error.
         */
        lines.forEachIndexed { index, line ->
            if (index == 0 || !line.contains('|')) return@forEachIndexed
            val divider = lines.getOrNull(index)?.trim().orEmpty()
            if (!TABLE_DIVIDER.matches(divider)) return@forEachIndexed
            val header = cells(lines[index - 1])
            var at = index + 1
            while (at < lines.size && lines[at].contains('|')) {
                if (cells(lines[at]) != header) {
                    problems += Problem(
                        at + 1,
                        null,
                        "This table row has ${cells(lines[at])} cells where the header has $header.",
                    )
                    break
                }
                at++
            }
        }

        return problems.take(MOST_PROBLEMS)
    }

    private fun cells(row: String): Int = row.trim().trim('|').split('|').size

    private fun answer(problems: List<Problem>): String = mapper.writeValueAsString(
        if (problems.isEmpty()) {
            mapOf("valid" to true)
        } else {
            mapOf(
                "valid" to false,
                "problems" to problems.map { one ->
                    linkedMapOf("line" to one.line, "column" to one.column, "message" to one.message)
                },
            )
        },
    )

    private fun error(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    companion object {
        const val FORMAT = "format"
        const val TEXT = "text"

        /** What it reads, in the words somebody would write. */
        val FORMATS = listOf("json", "yaml", "html", "markdown")

        /**
         * How many faults are reported before the list stops being useful.
         *
         * A document with fifty is a document that is the wrong format
         * altogether, and the first few say so as well as fifty would.
         */
        const val MOST_PROBLEMS = 20

        private val UNFINISHED_LINK = Regex("""!?\[[^\]]*]\([^)\n]*$""")
        private val TABLE_DIVIDER = Regex("""\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?""")
    }
}
