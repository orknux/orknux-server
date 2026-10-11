package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.orkbox.model.PublishedPort
import java.net.URI

/**
 * A port a box forwarded, as somebody opens it: the port inside the session
 * and the address it answers at.
 *
 * @param note set when the address is one only the box's own machine can
 *   reach - a port forwarded on loopback of a box that is somewhere else.
 */
data class BoxPort(val container: Int, val url: String, val note: String? = null)

/** Where a forwarded port can be opened from here. */
internal object BoxPorts {

    private val LOOPBACK = setOf("127.0.0.1", "::1", "localhost")
    private val EVERY_INTERFACE = setOf("0.0.0.0", "::", "")

    /**
     * The address [published] answers at, for a person or a model to open.
     *
     * A forward listening on every interface, or on loopback, is named by the
     * box's own host - `localhost` for the local box, the host of the address a
     * remote box is registered under - since that is the name this side
     * already reaches it by. Loopback of a box on another machine answers only
     * there, which the note says rather than handing out a link that cannot work.
     */
    fun of(box: KnownBox, published: PublishedPort): BoxPort {
        val boxHost = when (box.kind) {
            BoxKind.LOCAL -> "localhost"
            BoxKind.REMOTE -> box.row?.address?.let { runCatching { URI(it).host }.getOrNull() } ?: "localhost"
        }
        val loopback = published.host in LOOPBACK
        val host = if (loopback || published.host in EVERY_INTERFACE) boxHost else published.host
        val shown = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return BoxPort(
            container = published.container,
            url = "http://$shown:${published.hostPort}/",
            note = if (loopback && boxHost !in LOOPBACK) {
                "Forwarded on the box machine's loopback only, so it opens there and nowhere else; the box's operator " +
                    "can forward on the network with ORKBOX_PUBLISH_HOST."
            } else {
                null
            },
        )
    }
}
