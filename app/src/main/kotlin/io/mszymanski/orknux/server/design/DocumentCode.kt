package io.mszymanski.orknux.server.design

import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory
import org.fife.ui.rsyntaxtextarea.TokenTypes
import javax.swing.text.Segment

/**
 * A code block's words, coloured, for the files a document is written out as.
 *
 * The editor highlights with highlight.js in the browser; a PDF or an ODT is
 * made here, where there is no browser, so the same languages are tokenised by
 * RSyntaxTextArea's token makers - which run without a display - and each kind
 * of token is given the colour the printed page uses for it. A language the
 * editor names that has no token maker here is written plain, in monospace.
 */
object DocumentCode {

    /** One run of code and its colour, null for the ink of the page. */
    data class Run(val text: String, val colour: String?)

    /** The languages the editor's picker offers, as highlight.js names them, and the token maker for each. */
    private val STYLES = mapOf(
        "kotlin" to SyntaxConstants.SYNTAX_STYLE_KOTLIN,
        "java" to SyntaxConstants.SYNTAX_STYLE_JAVA,
        "typescript" to SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT,
        "javascript" to SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT,
        "python" to SyntaxConstants.SYNTAX_STYLE_PYTHON,
        "sql" to SyntaxConstants.SYNTAX_STYLE_SQL,
        "json" to SyntaxConstants.SYNTAX_STYLE_JSON,
        "yaml" to SyntaxConstants.SYNTAX_STYLE_YAML,
        "bash" to SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL,
        "html" to SyntaxConstants.SYNTAX_STYLE_HTML,
        "css" to SyntaxConstants.SYNTAX_STYLE_CSS,
        "go" to SyntaxConstants.SYNTAX_STYLE_GO,
        "rust" to SyntaxConstants.SYNTAX_STYLE_RUST,
        "c" to SyntaxConstants.SYNTAX_STYLE_C,
        "cpp" to SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS,
        "csharp" to SyntaxConstants.SYNTAX_STYLE_CSHARP,
        "xml" to SyntaxConstants.SYNTAX_STYLE_XML,
        "markdown" to SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
    )

    /** What a model or a fence may call a language, as the picker's names. */
    private val ALIASES = mapOf(
        "kt" to "kotlin", "ts" to "typescript", "js" to "javascript", "py" to "python", "sh" to "bash",
        "shell" to "bash", "zsh" to "bash", "yml" to "yaml", "c++" to "cpp", "cs" to "csharp", "md" to "markdown",
        "golang" to "go", "rs" to "rust",
    )

    /** The picker's name for a language as written, or null for one it does not offer. */
    fun named(language: String?): String? {
        val lower = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return (ALIASES[lower] ?: lower).takeIf { it in STYLES }
    }

    /** [code] as runs of colour, line ends kept in the runs; one plain run where the language is not known. */
    fun runs(code: String, language: String?): List<Run> {
        val style = named(language)?.let { STYLES[it] } ?: return listOf(Run(code, null))
        val maker = TokenMakerFactory.getDefaultInstance().getTokenMaker(style)
        val out = mutableListOf<Run>()
        var state = TokenTypes.NULL
        val lines = code.split('\n')
        lines.forEachIndexed { at, line ->
            val chars = line.toCharArray()
            val segment = Segment(chars, 0, chars.size)
            var token = maker.getTokenList(segment, state, 0)
            var covered = 0
            while (token != null && token.isPaintable) {
                val lexeme = token.lexeme
                out += Run(lexeme, COLOURS[token.type])
                covered += lexeme.length
                token = token.nextToken
            }
            // Whatever the maker left untokenised is still code.
            if (covered < line.length) out += Run(line.substring(covered), null)
            state = maker.getLastTokenTypeOnLine(segment, state)
            if (at < lines.size - 1) out += Run("\n", null)
        }
        return merged(out)
    }

    /** Neighbouring runs of one colour as one, so a file is not a span per space. */
    private fun merged(runs: List<Run>): List<Run> {
        val out = mutableListOf<Run>()
        runs.filter { it.text.isNotEmpty() }.forEach { run ->
            val last = out.lastOrNull()
            if (last != null && last.colour == run.colour) {
                out[out.size - 1] = Run(last.text + run.text, last.colour)
            } else {
                out += run
            }
        }
        return out
    }

    /** The printed page's colours, dark enough to read on white: the editor's light theme's. */
    private val COLOURS: Map<Int, String> = buildMap {
        listOf(TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE, TokenTypes.COMMENT_DOCUMENTATION, TokenTypes.COMMENT_KEYWORD,
            TokenTypes.COMMENT_MARKUP, TokenTypes.MARKUP_COMMENT).forEach { put(it, "#6a737d") }
        listOf(TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.PREPROCESSOR).forEach { put(it, "#cf222e") }
        listOf(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE, TokenTypes.REGEX,
            TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE).forEach { put(it, "#0a3069") }
        listOf(TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT, TokenTypes.LITERAL_NUMBER_HEXADECIMAL,
            TokenTypes.LITERAL_BOOLEAN).forEach { put(it, "#0550ae") }
        listOf(TokenTypes.FUNCTION, TokenTypes.ANNOTATION, TokenTypes.MARKUP_TAG_ATTRIBUTE).forEach { put(it, "#8250df") }
        listOf(TokenTypes.DATA_TYPE, TokenTypes.MARKUP_TAG_NAME).forEach { put(it, "#116329") }
        put(TokenTypes.VARIABLE, "#953800")
    }
}
