package io.mszymanski.orknux.connector.connection

/**
 * Asks the plugin a connection's kind belongs to whether the connection really
 * is one of that kind.
 *
 * Declared here and implemented by the app, because plugins are the app's and
 * this module may not depend on it. A kind's address answering says nothing
 * about what answered - a login page answers 200 - and only the plugin that
 * declared the kind knows what its service says when asked.
 */
fun interface PluginConnectionChecks {
    /** What the plugin said, or null where the kind brings no check and the address is all there is to ask. */
    fun check(connection: WorkspaceConnection): CheckResult?
}
