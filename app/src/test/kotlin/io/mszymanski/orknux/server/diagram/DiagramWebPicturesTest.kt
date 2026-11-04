package io.mszymanski.orknux.server.diagram

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.InetSocketAddress

/**
 * A picture a diagram shows from an address on this machine's network, and
 * the switch under Admin -> Settings that says whether the server fetches it.
 *
 * Served here from a loopback address, which is what an internal address is.
 * The switch decides at once in both directions: a picture fetched while it
 * was on is not drawn from what is held once it is off, and one refused while
 * it was off is fetched as soon as it is on.
 */
@SpringBootTest
class DiagramWebPicturesTest(
    @Autowired val pictures: DiagramWebPictures,
    @Autowired val settings: InstallationSettings,
) {

    private lateinit var server: HttpServer
    private var asked = 0

    /** The smallest PNG there is: one transparent pixel. */
    private val png = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    )

    @BeforeEach
    fun serve() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            asked += 1
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        settings.setDiagramPicturesInternal(true, "test")
    }

    private fun address(name: String) = "http://127.0.0.1:${server.address.port}/$name.png?${System.nanoTime()}"

    @Test
    fun `an internal address is fetched while allowed and refused while not`() {
        settings.setDiagramPicturesInternal(true, "test")
        assertThat(pictures.data(address("allowed"))).startsWith("data:image/png;base64,")

        settings.setDiagramPicturesInternal(false, "test")
        assertThat(pictures.data(address("refused"))).isNull()
    }

    @Test
    fun `turning internal addresses off stops a picture already fetched`() {
        val one = address("held")
        settings.setDiagramPicturesInternal(true, "test")
        assertThat(pictures.data(one)).isNotNull()

        settings.setDiagramPicturesInternal(false, "test")

        assertThat(pictures.data(one)).isNull()
    }

    @Test
    fun `turning internal addresses on fetches a picture refused before`() {
        val one = address("later")
        settings.setDiagramPicturesInternal(false, "test")
        assertThat(pictures.data(one)).isNull()
        assertThat(asked).isZero()

        settings.setDiagramPicturesInternal(true, "test")

        assertThat(pictures.data(one)).startsWith("data:image/png;base64,")
    }

    @Test
    fun `a picture asked for twice is fetched once`() {
        val one = address("twice")
        settings.setDiagramPicturesInternal(true, "test")
        pictures.data(one)
        pictures.data(one)
        assertThat(asked).isEqualTo(1)
    }
}
