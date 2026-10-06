package io.mszymanski.orknux.connector.connection

import com.slack.api.Slack
import com.slack.api.SlackConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.util.http.SlackHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/**
 * Closing a Socket Mode session closes the Slack it was built on. Issue #616.
 *
 * Every session gets a Slack of its own, and with it an OkHttp dispatcher and
 * connection pool. Closing the session closed only the socket, so a listener
 * that reconnected every few minutes added a pool and a dispatcher's threads
 * each time and never gave one back.
 *
 * The socket is a stand-in - opening a real one needs Slack - and the Slack is
 * real, because its dispatcher being shut down is the thing being asked.
 */
class OpenSocketTest {

    @Test
    fun `closing a session closes its socket and the Slack built for it`() {
        val http = SlackHttpClient()
        val slack = Slack.getInstance(SlackConfig(), http)
        val dispatcher = http.okHttpClient.dispatcher.executorService
        val socket = mock(SocketModeApp::class.java)

        OpenSocket(socket, slack).close()

        verify(socket).close()
        assertThat(dispatcher.isShutdown).describedAs("the session's OkHttp dispatcher").isTrue()
    }
}
