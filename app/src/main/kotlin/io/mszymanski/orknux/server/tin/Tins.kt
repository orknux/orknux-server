package io.mszymanski.orknux.server.tin

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.server.agent.Agent
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Duration

/** One tin as everything above the clients sees it, whichever kind it is. */
data class KnownTin(
    /** `local`, or the remote tin's row id as text. */
    val id: String,
    val name: String,
    val kind: TinKind,
    val label: String?,
    /** The row behind a remote tin; null for the local one. */
    val row: Tin? = null,
)

/**
 * Every tin this installation has, and a client for each.
 *
 * The one place that knows the local tin and the remote ones are both tins:
 * the API lists them through here, and the tools find the ones an agent may use
 * through here, so the two cannot disagree about what exists or who may reach
 * it. Also the one place a remote tin's token is read.
 */
@Service
class Tins(
    private val tins: TinRepository,
    private val settings: TinSettings,
    private val local: LocalTin,
    private val router: ProxyRouter,
    /** The address guard every outbound call is put past; see [RemoteTinClient]. */
    private val probe: io.mszymanski.orknux.connector.connection.ConnectionProbe,
) {

    /** The local tin while it is switched on, then the remote ones by name. */
    fun all(): List<KnownTin> = buildList {
        localTin()?.let(::add)
        tins.findAllByOrderByNameAsc().forEach { add(known(it)) }
    }

    fun byId(id: String): KnownTin? =
        if (id == LOCAL_TIN_ID) localTin() else id.toLongOrNull()?.let { tins.findByIdOrNull(it) }?.let(::known)

    fun byName(name: String): KnownTin? =
        if (name.trim().equals(LOCAL_TIN_NAME, ignoreCase = true)) localTin() else tins.findByName(name.trim())?.let(::known)

    /**
     * The tins [agent] may open a session on: those whose label is one it was
     * granted. A tin with no label is nobody's, and an agent granted no label
     * reaches none - the local tin included.
     */
    fun usableBy(agent: Agent): List<KnownTin> {
        val granted = agent.tinLabels.toSet()
        if (granted.isEmpty()) return emptyList()
        return all().filter { it.label != null && it.label in granted }
    }

    /** Whether [agent] may use [tin] now. Asked on every call, so a grant taken back is taken back at once. */
    fun mayUse(agent: Agent, tin: KnownTin): Boolean = tin.label != null && tin.label in agent.tinLabels

    /** A client for [tin]; the local one is started if it was not. */
    fun client(tin: KnownTin): TinClient = when (tin.kind) {
        TinKind.LOCAL -> local.client()
        TinKind.REMOTE -> remote(requireNotNull(tin.row))
    }

    /** A client for [tin] only if one can be had without starting anything. */
    fun clientIfRunning(tin: KnownTin): TinClient? = when (tin.kind) {
        TinKind.LOCAL -> local.runningClient()
        TinKind.REMOTE -> remote(requireNotNull(tin.row))
    }

    fun remote(row: Tin): RemoteTinClient = remote(row.address, row.token)

    /** A client for an address that may not be saved yet, for testing a connection. */
    fun remote(address: String, token: String?, waiting: Duration = Duration.ZERO): RemoteTinClient =
        RemoteTinClient(address, token, router.client(), requestTimeout().plus(waiting), probe::vet)

    fun requestTimeout(): Duration = Duration.ofSeconds(settings.requestTimeoutSeconds().toLong())

    private fun localTin(): KnownTin? =
        if (local.enabled()) KnownTin(LOCAL_TIN_ID, LOCAL_TIN_NAME, TinKind.LOCAL, LOCAL_TIN_LABEL) else null

    private fun known(row: Tin) = KnownTin(requireNotNull(row.id).toString(), row.name, TinKind.REMOTE, row.label, row)

    companion object {
        /**
         * An address a tin can be reached at, as it is stored: an http or https
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
