package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.orkbox.Config
import io.mszymanski.orknux.orkbox.DockerConfig
import io.mszymanski.orknux.orkbox.LocalConfig
import io.mszymanski.orknux.orkbox.Orkbox
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * The local box: one orkbox embedded in this process, built from Admin
 * Settings.
 *
 * **Started on first use**, not with the application: most installations open
 * no session for hours, and an orkbox that is running has a reaper ticking.
 *
 * **Rebuilt when its settings change.** Every use compares what the settings
 * say with what the running instance was built from, and a difference closes
 * it - stopping its sessions - and builds another. A settings mutation asks for
 * the same comparison at once through [refresh], so switching the box off stops
 * its sessions then rather than at the next use. Closed on shutdown, which
 * stops every session it holds.
 */
@Component
class LocalBox(private val settings: BoxSettings) : DisposableBean {

    /** What an instance is built from; two equal shapes are one instance. */
    private data class Shape(
        val runtime: String,
        val location: String?,
        val owner: String,
        val maxSessions: Int,
        val maxPorts: Int,
        val defaultTtl: Int,
        val maxTtl: Int,
        val idle: Int,
    )

    private var running: Pair<Shape, Orkbox>? = null

    /** When [running] was started; nothing it holds is older. */
    private var startedAt: OffsetDateTime? = null

    fun enabled(): Boolean = settings.localEnabled()

    /** The local box, started if it was not; [BoxNotFoundException] while it is switched off. */
    @Synchronized
    fun client(): BoxClient {
        if (!enabled()) {
            stop()
            throw BoxNotFoundException(LOCAL_BOX_ID)
        }
        val wanted = shape()
        running?.takeIf { it.first == wanted }?.let { return EmbeddedBoxClient(it.second) }
        stop()
        val started = Orkbox(config(wanted)).start()
        log.info("Local box started: {} runtime, sessions under {}", wanted.runtime, started.config.local.root)
        running = wanted to started
        startedAt = OffsetDateTime.now()
        return EmbeddedBoxClient(started)
    }

    /** The running instance, without starting one; null when nothing is running. */
    @Synchronized
    fun runningClient(): BoxClient? = running?.takeIf { enabled() && it.first == shape() }?.second?.let(::EmbeddedBoxClient)

    /**
     * When the running instance was started, where [runningClient] would answer
     * one; null when nothing is running. Its sessions are in memory, so a
     * session opened before this is one it cannot hold.
     */
    @Synchronized
    fun runningSince(): OffsetDateTime? = startedAt?.takeIf { runningClient() != null }

    /** Closes the running instance where the settings no longer describe it. */
    @Synchronized
    fun refresh() {
        val held = running ?: return
        if (!enabled() || held.first != shape()) stop()
    }

    override fun destroy() = synchronized(this) { stop() }

    private fun stop() {
        val held = running ?: return
        running = null
        startedAt = null
        runCatching { held.second.close() }.onFailure { log.warn("The local box did not close cleanly", it) }
        log.info("Local box stopped")
    }

    private fun shape() = Shape(
        runtime = settings.localRuntime(),
        location = settings.localLocation() ?: settings.localLocationConfigured(),
        owner = settings.localOwner(),
        maxSessions = settings.localMaxSessions(),
        maxPorts = settings.maxPorts(),
        defaultTtl = settings.localDefaultTtlSeconds(),
        maxTtl = settings.localMaxTtlSeconds(),
        idle = settings.localIdleTimeoutSeconds(),
    )

    private fun config(shape: Shape) = Config(
        runtimes = setOf(shape.runtime),
        defaultRuntime = shape.runtime,
        // A default above the ceiling would be a session refused for asking
        // for nothing, so the ceiling wins.
        defaultTtlSeconds = minOf(shape.defaultTtl, shape.maxTtl).toLong(),
        maxTtlSeconds = shape.maxTtl.toLong(),
        defaultIdleTimeoutSeconds = shape.idle.toLong(),
        maxSessions = shape.maxSessions,
        // At least one, because a forward this side allowed must not be refused for the box's own count.
        maxPorts = maxOf(shape.maxPorts, 1),
        /*
         * Not on start: orkbox's clean-up removes every container carrying its
         * label, and a Docker host this server shares with a standalone orkbox
         * would have that one's sessions removed from under it.
         */
        docker = DockerConfig(cleanupOnStart = false),
        local = shape.location?.let { LocalConfig(root = Path.of(it), owner = shape.owner) } ?: LocalConfig(owner = shape.owner),
    )

    private companion object {
        val log = LoggerFactory.getLogger(LocalBox::class.java)
    }
}
