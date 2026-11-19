package io.mszymanski.orknux.server.diagram

/**
 * What a Jira shape shows: one issue, by its key. Read off the key itself
 * (`PROJ-123`) or off an address a person copies - `…/browse/PROJ-123`, or a
 * board's `…?selectedIssue=PROJ-123` - on any host, because a Jira site is the
 * company's own and nothing here could recognise its name. Only the key is
 * kept: the site is the workspace's Jira plugin's to know.
 */
data class DiagramJiraSource(val key: String) {
    companion object {
        /** A project key is a letter then letters, digits or underscores; the number follows a dash. */
        private val KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}-[1-9][0-9]{0,11}$")
        private val IN_ADDRESS = Regex("(?:/browse/|[?&]selectedIssue=)([A-Za-z][A-Za-z0-9_]{0,63}-[1-9][0-9]{0,11})(?:[/?#&]|$)")

        /** The issue [said] names, its key in upper case, or null when it names none. */
        fun of(said: String): DiagramJiraSource? {
            val text = said.trim()
            if (text.isEmpty() || text.length > Diagrams.LINK_LENGTH) return null
            if (KEY.matches(text)) return DiagramJiraSource(text.uppercase())
            if (!text.startsWith("https://", ignoreCase = true) && !text.startsWith("http://", ignoreCase = true)) return null
            return IN_ADDRESS.find(text)?.groupValues?.get(1)?.let { DiagramJiraSource(it.uppercase()) }
        }
    }
}
