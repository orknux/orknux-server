package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.server.plugin.NetworkPluginHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/**
 * A plugin talking to a WebSocket through `orknux.http.websocket`, against a
 * real socket - the shape MuseScore's plugin API serves: a command in, its
 * answer out.
 *
 * The server below is written out by hand, the handshake and the frames, so
 * the test needs no library and pins what is actually on the wire: the
 * client's frames are masked and this unmasks them, a reply is one text frame,
 * and a close is answered.
 *
 *   in order    each message is answered before the next is sent, and the
 *               replies come back in the order they were asked for
 *   bounded     a reply that never comes is a refusal in words that keeps the
 *               replies which did, not a call that hangs the plugin
 *   vetted      the address is asked about as a request's is: link-local is
 *               refused before anything is opened
 */
@SpringBootTest
class PluginWebSocketTest(
    @Autowired val host: NetworkPluginHost,
    @Autowired val mapper: ObjectMapper,
) {

    private lateinit var server: ServerSocket

    @BeforeEach
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    fun `each message gets its own reply, in order`() {
        val answer = exchange(listOf("""{"action":"ping"}""", "second"))

        assertThat(answer.path("error").isMissingNode).isTrue()
        assertThat(replies(answer))
            .containsExactly("""{"echo":{"action":"ping"}}""", """{"echo":"second"}""")
    }

    @Test
    fun `an http address is spoken to as a websocket, since that is how a connection stores one`() {
        val answer = exchange(listOf("one"), url = "http://127.0.0.1:${server.localPort}/")

        assertThat(replies(answer)).containsExactly("""{"echo":"one"}""")
    }

    @Test
    fun `a reply that never comes is a refusal that keeps what did`() {
        val answer = exchange(listOf("first", "silent"), timeoutMs = 400)

        assertThat(answer.path("error").asString()).isEqualTo("no reply to message 2 came within 400 ms")
        assertThat(replies(answer)).containsExactly("""{"echo":"first"}""")
    }

    @Test
    fun `a closed port is said in words`() {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        val answer = exchange(listOf("one"), url = "ws://127.0.0.1:$closed/")

        assertThat(answer.path("error").asString()).startsWith("could not open ws://127.0.0.1:$closed/")
    }

    @Test
    fun `a link-local address and a scheme it does not speak are refused before anything is opened`() {
        assertThat(exchange(listOf("one"), url = "ws://169.254.169.254/").path("error").asString())
            .startsWith("that address may not be reached")
        assertThat(exchange(listOf("one"), url = "ftp://127.0.0.1/").path("error").asString())
            .isEqualTo("only ws, wss, http and https can be opened as a websocket, and that is ftp")
    }

    private fun replies(answer: tools.jackson.databind.JsonNode): List<String> =
        (0 until answer.path("replies").size()).map { answer.path("replies").get(it).asString() }

    private fun exchange(messages: List<String>, url: String? = null, timeoutMs: Long? = null) =
        mapper.readTree(
            host.request(
                mapper.writeValueAsString(
                    mapOf(
                        "op" to "websocket",
                        "url" to (url ?: "ws://127.0.0.1:${server.localPort}/"),
                        "messages" to messages,
                        "headers" to emptyMap<String, String>(),
                        "timeoutMs" to timeoutMs,
                    ),
                ),
            ),
        )

    /** One connection: the handshake, then a reply to every text frame but `silent`, until a close. */
    private fun serve(client: Socket) = client.use {
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()

        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
        val key = head.lines().first { it.lowercase().startsWith("sec-websocket-key:") }.substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
        )
        output.flush()

        while (true) {
            val first = input.readUnsignedByte()
            val second = input.readUnsignedByte()
            val opcode = first and 0x0F
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong()
            if (length == 127L) length = input.readLong()
            val mask = ByteArray(4).also { input.readFully(it) }
            val payload = ByteArray(length.toInt()).also { input.readFully(it) }
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

            when (opcode) {
                0x8 -> {
                    frame(output, 0x8, ByteArray(0))
                    return
                }
                0x1 -> {
                    val text = String(payload, StandardCharsets.UTF_8)
                    if (text == "silent") continue
                    val echoed = if (text.startsWith("{")) text else "\"$text\""
                    frame(output, 0x1, """{"echo":$echoed}""".toByteArray(StandardCharsets.UTF_8))
                }
            }
        }
    }

    /** A server's frame: final, unmasked, short. */
    private fun frame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        output.write(payload.size)
        output.write(payload)
        output.flush()
    }
}
