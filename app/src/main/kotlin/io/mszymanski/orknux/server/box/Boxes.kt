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
    /** The row behind a remote box; null for the local one. */
    val row: Box? = null,
)

/**
 * A box's sessions: how many are open, the most it takes, and the room left.
 *
 * [busy] are being made or running a command, and are what the limit counts;
 * [idle] are open with nothing running - waiting on an agent or a person until
 * their idle timeout stops them - and take no place until they start one.
 */
data class BoxCapacity(val busy: Int, val idle: Int, val max: Int) {
    val active: Int get() = busy + idle
    val free: Int get() = (max - busy).coerceAtLeast(0)
}

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
    private val agents: io.mszymanski.orknux.server.agent.AgentRepository,
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
     * The boxes [agent] may open a session on: those it was granted by name. An
     * agent granted none reaches none - the local box included.
     *
     * By name and nothing else. A box used to carry a label as well, and a grant
     * named the label; two names for one box was one more thing to set, and the
     * label was the one nobody could see from anywhere an agent used it.
     * [BoxGrantsByName] moved the grants over.
     */
    fun usableBy(agent: Agent): List<KnownBox> {
        val granted = labelsNow(agent)
        if (granted.isEmpty()) return emptyList()
        return all().filter { it.name in granted }
    }

    /** Whether [agent] may use [box] now. Asked on every call, so a grant taken back is taken back at once. */
    fun mayUse(agent: Agent, box: KnownBox): Boolean = box.name in labelsNow(agent)

    /**
     * Whether [agent] may work in a session on [box] that the agent [openerId]
     * opened: on its own grant, or on the opener's. A session is shared within
     * a conversation - an asker and the subagents it asks - and asking somebody
     * to work in your sandbox is handing them your grant for that sandbox, not
     * for the box: opening a session of its own still takes a grant of its own.
     */
    fun mayUseSession(agent: Agent, box: KnownBox, openerId: Long?): Boolean =
        mayUse(agent, box) ||
            openerId?.takeIf { it != agent.id }
                ?.let { agents.findById(it).orElse(null) }
                ?.let { mayUse(it, box) } == true

    /**
     * What [agent] is granted at this moment, not when its turn began.
     *
     * A turn holds the agent it loaded at its start, and a turn can run for an
     * hour. A label granted part way through reached the watcher, which reads
     * the agent fresh, and not the box tools: the watcher fired on a box that
     * `orkbox_list` then said was not there, and `orkbox_open` refused it, in
     * the same turn - and the agent concluded the box was flapping.
     */
    fun labelsNow(agent: Agent): Set<String> =
        (agent.id?.takeIf(agents::existsById)?.let { id -> Agent.parseBoxLabels(agents.boxLabelsTextOf(id)) } ?: agent.boxLabels).toSet()

    /**
     * How full [box] is: the sessions working on it against the most it takes,
     * and those idle beside them, or null when it could not be asked. Counted
     * as orkbox counts when it refuses one, so a box said to have room has it.
     * The local box is not started to say so: stopped, it holds none.
     */
    fun capacity(box: KnownBox): BoxCapacity? = runCatching {
        val client = clientIfRunning(box) ?: return BoxCapacity(0, 0, settings.localMaxSessions())
        val open = client.sessions(null).filter { it.status in OPEN_STATUSES }
        // Being made counts as working: it is not waiting on anybody.
        val busy = open.count { it.status != "ready" || it.executions.running > 0 }
        BoxCapacity(busy, open.size - busy, client.info().maxSessions)
    }.getOrNull()

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
        if (local.enabled()) KnownBox(LOCAL_BOX_ID, LOCAL_BOX_NAME, BoxKind.LOCAL) else null

    private fun known(row: Box) = KnownBox(requireNotNull(row.id).toString(), row.name, BoxKind.REMOTE, row)

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
