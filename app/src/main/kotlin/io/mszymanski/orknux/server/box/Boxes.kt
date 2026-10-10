package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.server.agent.Agent
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Duration

/** One box as everything above the clients sees it, whichever kind it is. */
data class KnownBox(
    /** `local`, or the remote box's row id as text. */
    val id: String,
    val name: String,
    val kind: BoxKind,
    val label: String?,
    /** The row behind a remote box; null for the local one. */
    val row: Box? = null,
)

/**
 * Every box this installation has, and a client for each.
 *
 * The one place that knows the local box and the remote ones are both boxes:
 * the API lists them through here, and the tools find the ones an agent may use
 * through here, so the two cannot disagree about what exists or who may reach
 * it. Also the one place a remote box's token is read.
 */
@Service
class Boxes(
    private val boxes: BoxRepository,
    private val settings: BoxSettings,
    private val local: LocalBox,
    private val router: ProxyRouter,
    /** The address guard every outbound call is put past; see [RemoteBoxClient]. */
    private val probe: io.mszymanski.orknux.connector.connection.ConnectionProbe,
) {

    /** The local box while it is switched on, then the remote ones by name. */
    fun all(): List<KnownBox> = buildList {
        localBox()?.let(::add)
        boxes.findAllByOrderByNameAsc().forEach { add(known(it)) }
    }

    fun byId(id: String): KnownBox? =
        if (id == LOCAL_BOX_ID) localBox() else id.toLongOrNull()?.let { boxes.findByIdOrNull(it) }?.let(::known)

    fun byName(name: String): KnownBox? =
        if (name.trim().equals(LOCAL_BOX_NAME, ignoreCase = true)) localBox() else boxes.findByName(name.trim())?.let(::known)

    /**
     * The boxes [agent] may open a session on: those whose label is one it was
     * granted. A box with no label is nobody's, and an agent granted no label
     * reaches none - the local box included.
     */
    fun usableBy(agent: Agent): List<KnownBox> {
        val granted = agent.boxLabels.toSet()
        if (granted.isEmpty()) return emptyList()
        return all().filter { it.label != null && it.label in granted }
    }

    /** Whether [agent] may use [box] now. Asked on every call, so a grant taken back is taken back at once. */
    fun mayUse(agent: Agent, box: KnownBox): Boolean = box.label != null && box.label in agent.boxLabels

    /** A client for [box]; the local one is started if it was not. */
    fun client(box: KnownBox): BoxClient = when (box.kind) {
        BoxKind.LOCAL -> local.client()
        BoxKind.REMOTE -> remote(requireNotNull(box.row))
    }

    /** A client for [box] only if one can be had without starting anything. */
    fun clientIfRunning(box: KnownBox): BoxClient? = when (box.kind) {
        BoxKind.LOCAL -> local.runningClient()
        BoxKind.REMOTE -> remote(requireNotNull(box.row))
    }

    fun remote(row: Box): RemoteBoxClient = remote(row.address, row.token)

    /** A client for an address that may not be saved yet, for testing a connection. */
    fun remote(address: String, token: String?, waiting: Duration = Duration.ZERO): RemoteBoxClient =
        RemoteBoxClient(address, token, router.client(), requestTimeout().plus(waiting), probe::vet)

    fun requestTimeout(): Duration = Duration.ofSeconds(settings.requestTimeoutSeconds().toLong())

    private fun localBox(): KnownBox? =
        if (local.enabled()) KnownBox(LOCAL_BOX_ID, LOCAL_BOX_NAME, BoxKind.LOCAL, LOCAL_BOX_LABEL) else null

    private fun known(row: Box) = KnownBox(requireNotNull(row.id).toString(), row.name, BoxKind.REMOTE, row.label, row)

    companion object {
        /**
         * An address a box can be reached at, as it is stored: an http or https
         * URL with a host, without a trailing slash. Null where it is not one.
         */
        fun normalisedAddress(address: String): String? {
            val given = address.trim().trimEnd('/')
            val uri = runCatching { URI(given) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
            if (uri.query != null || uri.fragment != null) return null
            return given
        }
    }
}
