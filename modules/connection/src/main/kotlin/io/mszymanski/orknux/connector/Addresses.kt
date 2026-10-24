package io.mszymanski.orknux.connector

/**
 * An http(s) address as somebody types it, made into the one that is stored.
 *
 * Nobody types `http://` in front of `192.168.0.10:8081`, and refusing the
 * address for the want of it - or, worse, storing it bare for a client to read
 * as a relative path - asked a person for what this side could say for them. So
 * an address typed with no scheme is taken as plain `http://`, the scheme of
 * every address on a network nobody put a certificate on, and an address with
 * one is left exactly as written. One rule, in one place: every save of a field
 * that holds an http(s) address passes through here before its own validation,
 * so what is stored always carries a scheme and nothing that reads it changes.
 *
 * Only for a field that *is* an http(s) address. A mail server's host, a proxy's
 * host and a pattern are not, and a field that may take another scheme keeps
 * whatever was typed - the default applies to nothing having been typed, never
 * to something else having been.
 */
object Addresses {

    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

    /** [text] trimmed, with `http://` in front where it names no scheme; empty stays empty. */
    fun withScheme(text: String): String {
        val given = text.trim()
        if (given.isEmpty() || SCHEME.containsMatchIn(given)) return given
        return "http://$given"
    }
}
