package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.http.HttpClient
import java.util.Base64
import java.util.Optional

/**
 * Fetching a file somebody attached in Slack, and - mostly - refusing to.
 *
 * The fetch itself is three lines of `HttpClient`; everything around it is the
 * part worth pinning, because every one of those refusals is a way this could
 * be made to fetch something it should not. A `url_private` arrives inside an
 * event somebody else composed, and it is handed to a client that carries a
 * *bot token*: a URL pointing anywhere but Slack would be this server sending
 * that credential to whoever asked.
 *
 * Said rather than thrown, all of it. A picture that could not be read is a
 * turn that goes on without the picture, not a run that fails - see
 * [SlackFile.NotRead].
 */
class SlackFilesTest {

    private val connections = mock(WorkspaceConnectionRepository::class.java)
    private val credentials = mock(ConnectionCredentials::class.java)
    private val proxies = mock(ProxyRouter::class.java)

    private val files: SlackFiles

    init {
        // A real builder, because the class holds the client it builds; nothing
        // in these tests gets as far as sending anything through it.
        `when`(proxies.builder()).thenReturn(HttpClient.newBuilder())
        files = SlackFiles(connections, credentials, proxies)
    }

    private fun slackConnection(id: Long = 1, workspaceId: Long = 9) =
        WorkspaceConnection(
            id = id,
            workspaceId = workspaceId,
            name = "Slack",
            type = ConnectionType.SLACK,
            url = "https://slack.com",
        )

    private fun held(connection: WorkspaceConnection?, token: String? = "xoxb-token") {
        `when`(connections.findById(1L)).thenReturn(Optional.ofNullable(connection))
        if (connection != null) {
            `when`(credentials.secretOf(connection)).thenReturn(
                if (token == null) {
                    io.mszymanski.orknux.connector.security.HeldCredential.Absent
                } else {
                    io.mszymanski.orknux.connector.security.HeldCredential.Held(token)
                },
            )
        }
    }

    private fun refusalFor(url: String, on: Long? = null): String {
        val read = files.read(1, url, on)
        assertThat(read).isInstanceOf(SlackFile.NotRead::class.java)
        return (read as SlackFile.NotRead).reason
    }

    @Test
    fun `a url that is not Slack's is refused, whatever it points at`() {
        held(slackConnection())

        assertThat(refusalFor("https://example.invalid/a.png")).contains("Slack's own host")
        // The trick the guard is actually for: a host that ends in the right
        // letters without being the right host.
        assertThat(refusalFor("https://slack.com.example.invalid/a.png")).contains("Slack's own host")
        assertThat(refusalFor("https://notslack.com/a.png")).contains("Slack's own host")
    }

    @Test
    fun `and neither is a url that is not https`() {
        held(slackConnection())

        assertThat(refusalFor("http://files.slack.com/a.png")).contains("Slack's own host")
    }

    @Test
    fun `nor something that is not a url at all`() {
        held(slackConnection())

        assertThat(refusalFor("not a url")).isNotEmpty()
    }

    @Test
    fun `a connection that has gone reads nothing`() {
        held(null)

        assertThat(refusalFor("https://files.slack.com/a.png")).contains("has been deleted")
    }

    /**
     * The same sentence as a connection that is gone, deliberately: a caller
     * asking about another workspace's connection learns nothing about whether
     * it exists.
     */
    @Test
    fun `a connection in another workspace is not this workspace's to read`() {
        held(slackConnection(workspaceId = 9))

        assertThat(refusalFor("https://files.slack.com/a.png", on = 11)).contains("has been deleted")
    }

    /**
     * But a workspace with its own Slack connection reads through that one.
     * Issue #562: two workspaces on one Slack app share its events, so a
     * message arrived on the other workspace's connection and its screenshot
     * was refused. What is used instead is this workspace's own, with its own
     * token - never the other workspace's.
     */
    @Test
    fun `a connection in another workspace falls back to this workspace's own`() {
        held(slackConnection(workspaceId = 9))
        val own = slackConnection(id = 5, workspaceId = 11)
        `when`(connections.findByWorkspaceId(11L, org.springframework.data.domain.Sort.by("id")))
            .thenReturn(listOf(own))
        `when`(credentials.secretOf(own)).thenReturn(io.mszymanski.orknux.connector.security.HeldCredential.Held("xoxb-own"))

        // Past the connection and on to the url check, which only a resolved connection reaches.
        assertThat(refusalFor("http://files.slack.com/a.png", on = 11)).contains("Slack's own host")
        org.mockito.Mockito.verify(credentials, org.mockito.Mockito.atLeastOnce()).secretOf(own)
        org.mockito.Mockito.verify(credentials, org.mockito.Mockito.never()).secretOf(slackConnection(workspaceId = 9))
    }

    @Test
    fun `a connection of another kind holds no files`() {
        held(
            WorkspaceConnection(
                id = 1,
                workspaceId = 9,
                name = "Mail",
                type = ConnectionType.SMTP,
                url = "smtp://mail.invalid",
            ),
        )

        assertThat(refusalFor("https://files.slack.com/a.png")).contains("hold no files")
    }

    @Test
    fun `a connection with no token cannot ask for one`() {
        held(slackConnection(), token = null)

        assertThat(refusalFor("https://files.slack.com/a.png")).contains("no bot token")
    }

    /**
     * What a fetched file becomes: the shape a model's request takes a picture
     * in. The `data:` URL is the whole reason this class exists - the bytes go
     * from Slack into a turn without being stored anywhere in between.
     */
    @Test
    fun `a fetched file is handed on as a data url`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val fetched = SlackFile.Fetched(bytes = png, mimetype = "image/png", name = "shot.png")

        val url = fetched.asDataUrl()

        assertThat(url).startsWith("data:image/png;base64,")
        assertThat(Base64.getDecoder().decode(url.substringAfter("base64,"))).isEqualTo(png)
    }
}
