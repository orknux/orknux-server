package io.mszymanski.orknux.connector.connection

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConnectionProperties::class, SlackProperties::class)
class ConnectionConfig

/** What the Slack listener does, when there is a token for it to do it with. */
@ConfigurationProperties(prefix = "orknux.slack")
data class SlackProperties(
    /**
     * False opens no sockets at all, whatever the connections hold. The tests
     * run that way; a deployment that wants to receive mentions should not.
     */
    val enabled: Boolean = true,

    /** How often the open sockets are compared with the stored connections. */
    val reconcileSeconds: Long = 30,

    /**
     * How long a connection that would not open is left alone. Slack answers
     * `invalid_auth` immediately, and asking again twice a minute for as long as
     * the process lives helps nobody; pasting a new token clears the wait.
     */
    val retryFailedSeconds: Long = 300,

    /**
     * How long a socket may hear nothing before it is pinged, and reopened if
     * the ping goes unanswered. Silence alone reopens nothing - a quiet
     * workspace is quiet - so this bounds how long a dead socket goes
     * unnoticed, not how often a live one is disturbed. Zero turns it off.
     */
    val quietPeriod: Duration = Duration.ofMinutes(10),
)
