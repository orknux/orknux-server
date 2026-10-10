package io.mszymanski.orknux.server.tin

import io.mszymanski.orknux.orktin.Config
import io.mszymanski.orknux.orktin.DockerConfig
import io.mszymanski.orknux.orktin.LocalConfig
import io.mszymanski.orknux.orktin.Orktin
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * The local tin: one orktin embedded in this process, built from Admin
 * Settings.
 *
 * **Started on first use**, not with the application: most installations open
 * no session for hours, and an orktin that is running has a reaper ticking.
 *
 * **Rebuilt when its settings change.** Every use compares what the settings
 * say with what the running instance was built from, and a difference closes
 * it - stopping its sessions - and builds another. A settings mutation asks for
 * the same comparison at once through [refresh], so switching the tin off stops
 * its sessions then rather than at the next use. Closed on shutdown, which
 * stops every session it holds.
 */
@Component
class LocalTin(private val settings: TinSettings) : DisposableBean {

    /** What an instance is built from; two equal shapes are one instance. */
    private data class Shape(
        val runtime: String,
        val location: String?,
        val maxSessions: Int,
        val defaultTtl: Int,
        val maxTtl: Int,
        val idle: Int,
    )

    private var running: Pair<Shape, Orktin>? = null

    /** When [running] was started; nothing it holds is older. */
    private var startedAt: OffsetDateTime? = null

    fun enabled(): Boolean = settings.localEnabled()

    /** The local tin, started if it was not; [TinNotFoundException] while it is switched off. */
    @Synchronized
    fun client(): TinClient {
        if (!enabled()) {
            stop()
            throw TinNotFoundException(LOCAL_TIN_ID)
        }
        val wanted = shape()
        running?.takeIf { it.first == wanted }?.let { return EmbeddedTinClient(it.second) }
        stop()
        val started = Orktin(config(wanted)).start()
        log.info("Local tin started: {} runtime, sessions under {}", wanted.runtime, started.config.local.root)
        running = wanted to started
        startedAt = OffsetDateTime.now()
        return EmbeddedTinClient(started)
    }

    /** The running instance, without starting one; null when nothing is running. */
    @Synchronized
    fun runningClient(): TinClient? = running?.takeIf { enabled() && it.first == shape() }?.second?.let(::EmbeddedTinClient)

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
        runCatching { held.second.close() }.onFailure { log.warn("The local tin did not close cleanly", it) }
        log.info("Local tin stopped")
    }

    private fun shape() = Shape(
        runtime = settings.localRuntime(),
        location = settings.localLocation() ?: settings.localLocationConfigured(),
        maxSessions = settings.localMaxSessions(),
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
        /*
         * Not on start: orktin's clean-up removes every container carrying its
         * label, and a Docker host this server shares with a standalone orktin
         * would have that one's sessions removed from under it.
         */
        docker = DockerConfig(cleanupOnStart = false),
        local = shape.location?.let { LocalConfig(root = Path.of(it)) } ?: LocalConfig(),
    )

    private companion object {
        val log = LoggerFactory.getLogger(LocalTin::class.java)
    }
}
