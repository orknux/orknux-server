package io.mszymanski.orknux.server.workflow

/**
 * `{{field}}` inside a written value: a field the run carries, put into the text
 * where it stands. `Hello {{trigger.user}}, order {{order.sku}} is ready`.
 *
 * Placeholders were once taken out altogether, because nothing checked them: a
 * value holding `{{llmResult}}` that nothing produced was sent to Slack as those
 * eleven characters, and nothing said a word. They are back on one condition -
 * every placeholder in a value has to name something that will be there, and a
 * value with one that does not is refused where it is typed and again where it
 * is saved ([GraphValidator]). So a placeholder that reaches a run is one the
 * run can fill.
 *
 * A placeholder names a field the way a reference does: `trigger` or
 * `trigger.x` for the event that started the run, anything else for what a
 * node produced, with dots into it - and `variables.<id>` for one of the
 * workspace's variables, by id as a reference names one, so a sentence can hold
 * a variable as well as be one. That is answered by [StepVariables] by the rules
 * a reference is: a variable the workspace may read, its own or a lent
 * catalog's, and never a secret, which here would be written into the text.
 */
object Placeholders {

    /** A well-formed placeholder: a dotted name between double braces, spaces allowed inside. */
    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*)\s*\}\}""")

    /** Anything opened with `{{`, well formed or not, up to the next `}}` or the end. */
    private val OPENED = Regex("""\{\{[^}]*(\}\})?""")

    /** The fields a value's placeholders name, in order, each once. */
    fun fieldsIn(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** Whether a value holds anything placeholder-shaped at all, which is what decides whether it is checked. */
    fun mentions(text: String): Boolean = text.contains("{{")

    /** The `{{…}}` in a value that are not placeholders: an unclosed brace, a name that cannot be one. */
    fun malformedIn(text: String): List<String> =
        OPENED.findAll(text).map { it.value }.filter { !PLACEHOLDER.matches(it) }.toList()

    /** The text with every placeholder replaced by what [valueOf] says the field holds. */
    fun filled(text: String, valueOf: (String) -> String): String =
        if (!mentions(text)) text else PLACEHOLDER.replace(text) { valueOf(it.groupValues[1]) }
}
