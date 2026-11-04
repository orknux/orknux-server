package io.mszymanski.orknux.connector.connection

/**
 * Whether this server listens to Slack, asked every time the sockets are
 * reconciled. The app answers it from the installation's settings, which an
 * administrator changes on the screen; the module holds no notion of settings,
 * so it declares what it needs and is handed it.
 */
fun interface SlackListeningSwitch {
    fun on(): Boolean
}
