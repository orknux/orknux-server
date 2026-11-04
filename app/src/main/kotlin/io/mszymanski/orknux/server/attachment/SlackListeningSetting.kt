package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.connector.connection.SlackListeningSwitch
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/** The Slack listener's switch, answered from the installation's settings. */
@Component
class SlackListeningSetting(@param:Lazy private val settings: InstallationSettings) : SlackListeningSwitch {
    override fun on(): Boolean = settings.slackListening()
}
