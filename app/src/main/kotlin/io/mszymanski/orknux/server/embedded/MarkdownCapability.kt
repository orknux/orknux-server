package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Markdown as plain readable text. Issue #508.
 *
 * The markdown plugin declared two functions and only one of them belongs here.
 * `toSlack` writes Slack's *mrkdwn*, which is somebody else's dialect and has
 * moved into the Slack plugin - where `post` now converts on the way out, so
 * nobody has to remember to call it. `toText` is not a dialect: it is markdown
 * with its punctuation taken off, for an email subject, a commit message, a log
 * line or a notification.
 *
 * **Parsed rather than stripped with regular expressions.** The plugin did this
 * by hand and said so - "there is no markdown-to-mrkdwn library worth the
 * dependency" - which was true of mrkdwn and is not true of plain text.
 * commonmark-java is already here for `orknux.validate` (#416), and walking the
 * tree it builds gets the awkward parts right for free: an asterisk inside a
 * code span is an asterisk, a link's URL is not its text, and a fenced block is
 * literal to its last character.
 */
@Component
class MarkdownCapability(private val mapper: ObjectMapper) : EmbeddedCapability {

    override val key = "markdown"
    override val name = "Markdown"

    private val parser = Parser.builder().build()

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = TO_TEXT,
            summary = "Markdown stripped to plain readable text.",
            description = "Turns markdown into plain readable text, for somewhere that shows no formatting: " +
                "an email subject, a commit message, a log line, a notification. Headings keep their words, " +
                "lists keep their bullets as dashes, links become their text rather than their URL, and code " +
                "fences keep every character as written. Nothing is silently deleted - what cannot be styled " +
                "is left readable.",
            params = listOf(EmbeddedParam(MARKDOWN, ValueType.STRING, "The markdown.", required = true)),
        ),
    )

    override fun functions(): List<EmbeddedFunction> = listOf(
        EmbeddedFunction(
            name = TO_TEXT,
            description = "Markdown stripped to plain readable text.",
            returnType = ValueType.STRING,
            params = listOf(EmbeddedParam(MARKDOWN, ValueType.STRING, "The markdown.", required = true)),
        ),
    )

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        if (name != TO_TEXT) return mapper.writeValueAsString(mapOf("error" to "There is no tool called markdown_$name."))
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return mapper.writeValueAsString(mapOf("error" to "That is not valid JSON."))
        val written = asked.path(MARKDOWN).takeIf { it.isTextual }?.stringValue()
            ?: return mapper.writeValueAsString(mapOf("error" to "Give the $MARKDOWN to strip."))
        return mapper.writeValueAsString(mapOf("text" to toText(written)))
    }

    override fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? {
        if (name != TO_TEXT) return null
        val given = arguments.getOrNull(0)?.trim().orEmpty()
        val written = runCatching { mapper.readTree(given) }.getOrNull()
            ?.takeIf { it.isTextual }?.stringValue()
            ?: given
        return ScriptResult.Returned(mapper.writeValueAsString(toText(written)), 0)
    }

    /** The text of a document, walked block by block. */
    fun toText(markdown: String): String {
        val said = StringBuilder()
        write(parser.parse(markdown), said, depth = 0)
        return said.toString().trim().replace(THREE_BREAKS, BREAK + BREAK)
    }

    private fun write(node: Node, said: StringBuilder, depth: Int) {
        var child = node.firstChild
        var counted = 1
        while (child != null) {
            when (child) {
                is Heading -> {
                    said.append(inlineOf(child)).append(BREAK).append(BREAK)
                }

                is Paragraph -> {
                    said.append(inlineOf(child)).append(BREAK).append(BREAK)
                }

                is BulletList -> {
                    writeItems(child, said, depth) { "- " }
                    said.append(BREAK)
                }

                is OrderedList -> {
                    var at = child.markerStartNumber ?: 1
                    writeItems(child, said, depth) { (at++).toString() + ". " }
                    said.append(BREAK)
                }

                /*
                 * A fence keeps every character it was given. Stripping the
                 * punctuation out of code is how a command in a message stops
                 * being a command that runs.
                 */
                is FencedCodeBlock -> said.append(child.literal.trimEnd()).append(BREAK).append(BREAK)
                is IndentedCodeBlock -> said.append(child.literal.trimEnd()).append(BREAK).append(BREAK)

                is BlockQuote -> write(child, said, depth + 1)
                is ThematicBreak -> said.append(BREAK)
                is Document -> write(child, said, depth)
                else -> said.append(inlineOf(child))
            }
            counted++
            child = child.next
        }
    }

    private fun writeItems(list: Node, said: StringBuilder, depth: Int, marker: () -> String) {
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                said.append("  ".repeat(depth)).append(marker())
                val inner = StringBuilder()
                write(item, inner, depth + 1)
                said.append(inner.toString().trim()).append(BREAK)
            }
            item = item.next
        }
    }

    /**
     * The words of an inline run.
     *
     * A link becomes its text, not its URL: "see the [runbook](https://…)"
     * reads as "see the runbook", which is what somebody wants in a subject
     * line. An image becomes its alt text for the same reason, and where it has
     * none it leaves nothing rather than a bare address.
     */
    private fun inlineOf(node: Node): String {
        val said = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Text -> said.append(child.literal)
                is Code -> said.append(child.literal)
                is SoftLineBreak -> said.append(' ')
                is HardLineBreak -> said.append(BREAK)
                is Link -> said.append(inlineOf(child))
                is Image -> said.append(inlineOf(child))
                else -> said.append(inlineOf(child))
            }
            child = child.next
        }
        return said.toString()
    }

    private companion object {
        const val TO_TEXT = "toText"
        const val MARKDOWN = "markdown"

        /** Built rather than typed, per the house rule about escapes in heredocs. */
        val BREAK = 10.toChar().toString()
        val THREE_BREAKS = Regex(BREAK + "{3,}")
    }
}
