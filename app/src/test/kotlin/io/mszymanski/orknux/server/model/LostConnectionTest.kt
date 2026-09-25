package io.mszymanski.orknux.server.model

import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.ModelClients
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderProbe
import io.mszymanski.orknux.connector.model.OpenAiChat
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretReferences
import io.mszymanski.orknux.connector.security.SecretVariables
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A connection the other end closed while it was idle.
 *
 * This is the failure a self-hosted model server produces every day and no stub
 * in this suite had ever produced. llama.cpp answers `Keep-Alive: timeout=5`
 * and drops an idle connection after five seconds; a pool holding one for
 * minutes writes its next request into a socket that is already gone and reads
 * back `unexpected end of stream` with no response at all. It looked
 * intermittent, because whichever call happened to follow a quiet spell was the
 * one that failed - in practice, naming a chat after the answer had streamed.
 *
 * **Why the socket is written by hand here.** A stub built on `HttpServer`
 * cannot stage it: closing a connection from the handler is a response, and
 * what has to be staged is a connection closed with nothing sent, on a socket
 * the client believes it may still use. So this listens itself, answers the
 * first request properly, and closes the second connection unread - which is
 * exactly what a client holding a dead pooled connection sees.
 *
 * Shortening how long a connection is kept narrows this and cannot close it:
 * the socket looks open right up until it is written to. Asking once more is
 * what closes it.
 */
class LostConnectionTest {

    private lateinit var listener: ServerSocket
    private lateinit var accepting: Thread

    /** How many connections were opened, which is the point of the test. */
    private val opened = CopyOnWriteArrayList<String>()

    /** Closed unread rather than answered, the way a stale pooled socket is. */
    @Volatile
    private var dropNext = false

    private val ready = CountDownLatch(1)

    @BeforeEach
    fun start() {
        opened.clear()
        dropNext = false
        listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        accepting = thread(isDaemon = true) {
            ready.countDown()
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) { serve(socket) }
            }
        }
        ready.await(5, TimeUnit.SECONDS)
    }

    @AfterEach
    fun stop() {
        runCatching { listener.close() }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val reader = BufferedReader(it.getInputStream().reader(StandardCharsets.ISO_8859_1))
            while (true) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: return
                if (line.isEmpty()) continue
                if (!line.startsWith("POST") && !line.startsWith("GET")) continue

                if (dropNext) {
                    /*
                     * Once, and gone before a byte of answer. One socket is
                     * dead, not the server - which is what a keep-alive timeout
                     * leaves behind, and why asking again on a fresh connection
                     * is the whole fix.
                     */
                    dropNext = false
                    opened += "dropped"
                    return
                }
                opened += "answered"

                // Read past the headers and whatever body came with them.
                var length = 0
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        length = header.substringAfter(':').trim().toInt()
                    }
                }
                if (length > 0) reader.read(CharArray(length), 0, length)

                val body = """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":""" +
                    """[{"index":0,"message":{"role":"assistant","content":"Hello."},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}"""
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                it.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
                )
                it.getOutputStream().write(bytes)
                it.getOutputStream().flush()
            }
        }
    }

    @Test
    fun `a connection closed before it answers is asked again, not reported as a failure`() {
        val chat = chat()

        // The first call is ordinary and leaves a connection in the pool.
        val first = chat.complete(provider(), model(), listOf(ChatTurn("user", "Hi")), emptyList())
        assertThat(first).isInstanceOf(OpenAiChat.Outcome.Answered::class.java)

        // Now the other end goes away without saying so, which is what a
        // keep-alive timeout looks like from here.
        dropNext = true

        val second = chat.complete(provider(), model(), listOf(ChatTurn("user", "Again")), emptyList())

        assertThat(second)
            .describedAs("a lost connection is asked again rather than reported to the caller")
            .isInstanceOf(OpenAiChat.Outcome.Answered::class.java)
        assertThat(opened).contains("dropped")
    }

    private fun chat(): OpenAiChat {
        val router = ProxyRouter(ProxyRuleSource { emptyList() })
        val properties = ConnectionProperties()
        val clients = ModelClients(router)
        val probe = ModelProviderProbe(
            ConnectionProbe(properties, router, SecretCipher(TEST_KEY)),
            properties,
            ObjectMapper(),
            SecretReferences(SecretVariables { _, _ -> null }, SecretCipher(TEST_KEY)),
            router,
            clients,
        )
        return OpenAiChat(clients, probe, ObjectMapper())
    }

    private fun provider() = ModelProvider(
        workspaceId = 1,
        name = "Local",
        type = ProviderType.OPENAI,
        endpoint = "http://${listener.inetAddress.hostAddress}:${listener.localPort}",
        secret = "sk-test",
    )

    private fun model() = LlmModel(providerId = 1, name = "Local", modelId = "local", kind = ModelKind.CHAT)

    private companion object {
        const val TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
