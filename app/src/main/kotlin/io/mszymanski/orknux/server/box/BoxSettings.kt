package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.orkbox.Config
import io.mszymanski.orknux.server.attachment.InstallationSetting
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/*
 * The bounds of every box setting, with the default each one starts at. Every
 * behavioural number about boxes is one of these, set in Admin Settings; the
 * constant is only what a fresh installation has.
 */
const val MIN_LOCAL_BOX_SESSIONS = 1
const val MAX_LOCAL_BOX_SESSIONS = 1000
const val DEFAULT_LOCAL_BOX_SESSIONS = 50

const val MIN_LOCAL_BOX_TTL = 60
const val MAX_LOCAL_BOX_TTL = 604800
const val DEFAULT_LOCAL_BOX_TTL = 7200
const val DEFAULT_LOCAL_BOX_MAX_TTL = 86400

const val MIN_LOCAL_BOX_IDLE = 60
const val MAX_LOCAL_BOX_IDLE = 86400
const val DEFAULT_LOCAL_BOX_IDLE = 900

const val MIN_BOX_COMMAND_WAIT = 1
const val MAX_BOX_COMMAND_WAIT = 3600
const val DEFAULT_BOX_COMMAND_WAIT = 300

const val MIN_BOX_COMMAND_TIMEOUT = 1
const val MAX_BOX_COMMAND_TIMEOUT = 86400
const val DEFAULT_BOX_COMMAND_TIMEOUT = 1800

const val MIN_BOX_OUTPUT_BYTES = 1024
const val MAX_BOX_OUTPUT_BYTES = 4194304
const val DEFAULT_BOX_OUTPUT_BYTES = 65536

/** How much of a finished local box command's output is kept on this side; 0 keeps none. */
const val MIN_LOCAL_BOX_OUTPUT_KEPT = 0
const val MAX_LOCAL_BOX_OUTPUT_KEPT = 16777216
const val DEFAULT_LOCAL_BOX_OUTPUT_KEPT = 1048576

/** How much of one command's output the conversation's session view holds in the browser. */
const val MIN_BOX_OUTPUT_SHOWN = 65536
const val MAX_BOX_OUTPUT_SHOWN = 67108864
const val DEFAULT_BOX_OUTPUT_SHOWN = 4194304

const val MIN_BOX_REQUEST_TIMEOUT = 1
const val MAX_BOX_REQUEST_TIMEOUT = 600
const val DEFAULT_BOX_REQUEST_TIMEOUT = 30

/** How many ports one box session may have asked for or open at once; 0 lets an agent ask for none. */
const val MIN_BOX_PORTS = 0
const val MAX_BOX_PORTS = 20
const val DEFAULT_BOX_PORTS = 5

/** How long a look at whether an exposed port answers is believed, in seconds. */
const val MIN_BOX_PORT_CHECK_SECONDS = 2
const val MAX_BOX_PORT_CHECK_SECONDS = 600
const val DEFAULT_BOX_PORT_CHECK_SECONDS = 15

/** How long that look waits for the box to close a connection nothing inside took, in milliseconds. */
const val MIN_BOX_PORT_CHECK_WAIT = 100
const val MAX_BOX_PORT_CHECK_WAIT = 10000
const val DEFAULT_BOX_PORT_CHECK_WAIT = 1500

/**
 * How long a box session an agent opens may sit with no command before its box
 * stops it, in seconds - sent with every `orkbox_open` that names none. A box's
 * own default was fifteen minutes, and an agent that handed over to a person to
 * try what it had built came back to a stopped session.
 */
const val MIN_BOX_SESSION_IDLE = 60
const val MAX_BOX_SESSION_IDLE = 86400
const val DEFAULT_BOX_SESSION_IDLE = 7200

/** How many edits and writes `orkbox_undo` can take back, per box session; 0 keeps no history. */
const val MIN_BOX_EDIT_UNDO_STEPS = 0
const val MAX_BOX_EDIT_UNDO_STEPS = 100
const val DEFAULT_BOX_EDIT_UNDO_STEPS = 10

/**
 * How the local box is set up, and how every box is spoken to.
 *
 * Rows in `installation_setting`, like every other Admin setting, read through
 * here rather than added to the long list in `InstallationSettings`: these are
 * one feature's knobs, and the feature is the only thing that reads them. A
 * stored value outside its bounds - a hand-edited row, a restore - reads as the
 * default rather than failing a tool call.
 */
@Service
class BoxSettings(private val settings: InstallationSettingRepository) {

    fun localEnabled(): Boolean = text(LOCAL_ENABLED)?.toBooleanStrictOrNull() ?: true

    /** The directory local sessions are made under; null is [localLocationConfigured]. */
    fun localLocation(): String? = text(LOCAL_LOCATION)?.takeIf { it.isNotBlank() }

    /**
     * Where they go when nobody says: the temporary directory's `orknux-box`,
     * and deliberately not orkbox's own default `orkbox` beside it. The local
     * runtime deletes every `ses_*` folder under its root when it starts, so a
     * standalone orkbox on the same machine sharing that root would lose its
     * sessions each time this server's box came up.
     */
    fun localLocationConfigured(): String =
        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "orknux-box").toString()

    fun localRuntime(): String = text(LOCAL_RUNTIME)?.takeIf { it in RUNTIMES } ?: Config.RUNTIME_LOCAL

    fun localMaxSessions(): Int = ranged(LOCAL_MAX_SESSIONS, MIN_LOCAL_BOX_SESSIONS..MAX_LOCAL_BOX_SESSIONS, DEFAULT_LOCAL_BOX_SESSIONS)

    fun maxPorts(): Int = ranged(MAX_PORTS, MIN_BOX_PORTS..MAX_BOX_PORTS, DEFAULT_BOX_PORTS)

    fun portCheckSeconds(): Int =
        ranged(PORT_CHECK_SECONDS, MIN_BOX_PORT_CHECK_SECONDS..MAX_BOX_PORT_CHECK_SECONDS, DEFAULT_BOX_PORT_CHECK_SECONDS)

    fun portCheckWaitMillis(): Int =
        ranged(PORT_CHECK_WAIT, MIN_BOX_PORT_CHECK_WAIT..MAX_BOX_PORT_CHECK_WAIT, DEFAULT_BOX_PORT_CHECK_WAIT)

    fun localDefaultTtlSeconds(): Int = ranged(LOCAL_DEFAULT_TTL, MIN_LOCAL_BOX_TTL..MAX_LOCAL_BOX_TTL, DEFAULT_LOCAL_BOX_TTL)

    fun localMaxTtlSeconds(): Int = ranged(LOCAL_MAX_TTL, MIN_LOCAL_BOX_TTL..MAX_LOCAL_BOX_TTL, DEFAULT_LOCAL_BOX_MAX_TTL)

    fun localIdleTimeoutSeconds(): Int = ranged(LOCAL_IDLE, MIN_LOCAL_BOX_IDLE..MAX_LOCAL_BOX_IDLE, DEFAULT_LOCAL_BOX_IDLE)

    fun commandWaitSeconds(): Int = ranged(COMMAND_WAIT, MIN_BOX_COMMAND_WAIT..MAX_BOX_COMMAND_WAIT, DEFAULT_BOX_COMMAND_WAIT)

    fun commandTimeoutSeconds(): Int =
        ranged(COMMAND_TIMEOUT, MIN_BOX_COMMAND_TIMEOUT..MAX_BOX_COMMAND_TIMEOUT, DEFAULT_BOX_COMMAND_TIMEOUT)

    fun outputBytes(): Int = ranged(OUTPUT_BYTES, MIN_BOX_OUTPUT_BYTES..MAX_BOX_OUTPUT_BYTES, DEFAULT_BOX_OUTPUT_BYTES)

    fun localOutputKeptBytes(): Int = ranged(LOCAL_OUTPUT_KEPT, MIN_LOCAL_BOX_OUTPUT_KEPT..MAX_LOCAL_BOX_OUTPUT_KEPT, DEFAULT_LOCAL_BOX_OUTPUT_KEPT)

    fun outputShownBytes(): Int = ranged(OUTPUT_SHOWN, MIN_BOX_OUTPUT_SHOWN..MAX_BOX_OUTPUT_SHOWN, DEFAULT_BOX_OUTPUT_SHOWN)

    fun requestTimeoutSeconds(): Int =
        ranged(REQUEST_TIMEOUT, MIN_BOX_REQUEST_TIMEOUT..MAX_BOX_REQUEST_TIMEOUT, DEFAULT_BOX_REQUEST_TIMEOUT)

    fun editUndoSteps(): Int =
        ranged(EDIT_UNDO_STEPS, MIN_BOX_EDIT_UNDO_STEPS..MAX_BOX_EDIT_UNDO_STEPS, DEFAULT_BOX_EDIT_UNDO_STEPS)

    fun sessionIdleSeconds(): Int =
        ranged(SESSION_IDLE, MIN_BOX_SESSION_IDLE..MAX_BOX_SESSION_IDLE, DEFAULT_BOX_SESSION_IDLE)

    @Transactional
    fun setLocalEnabled(enabled: Boolean, by: String) = write(LOCAL_ENABLED, enabled.toString(), by)

    @Transactional
    fun setLocalLocation(location: String?, by: String) {
        val given = location?.trim().orEmpty()
        if (given.isEmpty()) {
            settings.findByIdOrNull(LOCAL_LOCATION)?.let(settings::delete)
        } else {
            write(LOCAL_LOCATION, given, by)
        }
    }

    @Transactional
    fun setLocalRuntime(runtime: String, by: String) {
        val given = runtime.trim().lowercase()
        if (given !in RUNTIMES) throw LocalBoxRuntimeUnknownException(runtime)
        write(LOCAL_RUNTIME, given, by)
    }

    @Transactional
    fun setMaxPorts(count: Int, by: String) {
        if (count !in MIN_BOX_PORTS..MAX_BOX_PORTS) throw BoxMaxPortsOutOfRangeException(count)
        write(MAX_PORTS, count.toString(), by)
    }

    @Transactional
    fun setPortCheckSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_BOX_PORT_CHECK_SECONDS..MAX_BOX_PORT_CHECK_SECONDS) throw BoxPortCheckOutOfRangeException(seconds)
        write(PORT_CHECK_SECONDS, seconds.toString(), by)
    }

    @Transactional
    fun setPortCheckWaitMillis(millis: Int, by: String) {
        if (millis !in MIN_BOX_PORT_CHECK_WAIT..MAX_BOX_PORT_CHECK_WAIT) throw BoxPortCheckWaitOutOfRangeException(millis)
        write(PORT_CHECK_WAIT, millis.toString(), by)
    }

    @Transactional
    fun setLocalMaxSessions(count: Int, by: String) {
        if (count !in MIN_LOCAL_BOX_SESSIONS..MAX_LOCAL_BOX_SESSIONS) throw LocalBoxMaxSessionsOutOfRangeException(count)
        write(LOCAL_MAX_SESSIONS, count.toString(), by)
    }

    @Transactional
    fun setLocalDefaultTtlSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_LOCAL_BOX_TTL..MAX_LOCAL_BOX_TTL) throw LocalBoxTtlOutOfRangeException(seconds)
        write(LOCAL_DEFAULT_TTL, seconds.toString(), by)
    }

    @Transactional
    fun setLocalMaxTtlSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_LOCAL_BOX_TTL..MAX_LOCAL_BOX_TTL) throw LocalBoxTtlOutOfRangeException(seconds)
        write(LOCAL_MAX_TTL, seconds.toString(), by)
    }

    @Transactional
    fun setLocalIdleTimeoutSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_LOCAL_BOX_IDLE..MAX_LOCAL_BOX_IDLE) throw LocalBoxIdleTimeoutOutOfRangeException(seconds)
        write(LOCAL_IDLE, seconds.toString(), by)
    }

    @Transactional
    fun setCommandWaitSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_BOX_COMMAND_WAIT..MAX_BOX_COMMAND_WAIT) throw BoxCommandWaitOutOfRangeException(seconds)
        write(COMMAND_WAIT, seconds.toString(), by)
    }

    @Transactional
    fun setCommandTimeoutSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_BOX_COMMAND_TIMEOUT..MAX_BOX_COMMAND_TIMEOUT) throw BoxCommandTimeoutOutOfRangeException(seconds)
        write(COMMAND_TIMEOUT, seconds.toString(), by)
    }

    @Transactional
    fun setOutputBytes(bytes: Int, by: String) {
        if (bytes !in MIN_BOX_OUTPUT_BYTES..MAX_BOX_OUTPUT_BYTES) throw BoxOutputBytesOutOfRangeException(bytes)
        write(OUTPUT_BYTES, bytes.toString(), by)
    }

    @Transactional
    fun setLocalOutputKeptBytes(bytes: Int, by: String) {
        if (bytes !in MIN_LOCAL_BOX_OUTPUT_KEPT..MAX_LOCAL_BOX_OUTPUT_KEPT) throw LocalBoxOutputKeptOutOfRangeException(bytes)
        write(LOCAL_OUTPUT_KEPT, bytes.toString(), by)
    }

    @Transactional
    fun setOutputShownBytes(bytes: Int, by: String) {
        if (bytes !in MIN_BOX_OUTPUT_SHOWN..MAX_BOX_OUTPUT_SHOWN) throw BoxOutputShownOutOfRangeException(bytes)
        write(OUTPUT_SHOWN, bytes.toString(), by)
    }

    @Transactional
    fun setRequestTimeoutSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_BOX_REQUEST_TIMEOUT..MAX_BOX_REQUEST_TIMEOUT) throw BoxRequestTimeoutOutOfRangeException(seconds)
        write(REQUEST_TIMEOUT, seconds.toString(), by)
    }

    @Transactional
    fun setSessionIdleSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_BOX_SESSION_IDLE..MAX_BOX_SESSION_IDLE) throw BoxSessionIdleOutOfRangeException(seconds)
        write(SESSION_IDLE, seconds.toString(), by)
    }

    @Transactional
    fun setEditUndoSteps(steps: Int, by: String) {
        if (steps !in MIN_BOX_EDIT_UNDO_STEPS..MAX_BOX_EDIT_UNDO_STEPS) throw BoxEditUndoStepsOutOfRangeException(steps)
        write(EDIT_UNDO_STEPS, steps.toString(), by)
    }

    /**
     * This installation's name for the local box's workspaces, made once and
     * kept. Orkbox's start-up clean-up removes only workspaces carrying it, so
     * a second Orknux on the same machine - a test server beside a dev one -
     * no longer deletes this one's live sessions when it starts.
     */
    @Transactional
    fun localOwner(): String =
        text(LOCAL_OWNER)?.takeIf { it.isNotBlank() }
            ?: ("orknux-" + java.util.UUID.randomUUID().toString().take(8)).also { write(LOCAL_OWNER, it, "orknux") }

    private fun text(name: String): String? = settings.findByIdOrNull(name)?.value?.trim()

    private fun ranged(name: String, range: IntRange, default: Int): Int =
        text(name)?.toIntOrNull()?.takeIf { it in range } ?: default

    private fun write(name: String, value: String, by: String) {
        val held = settings.findByIdOrNull(name) ?: InstallationSetting(name = name)
        held.value = value
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    companion object {
        // Stored under `tin.`, the name boxes had first: the keys are rows in
        // installation_setting, and renaming them would orphan what was set.
        const val LOCAL_ENABLED = "tin.local.enabled"
        const val LOCAL_LOCATION = "tin.local.location"
        const val LOCAL_OWNER = "tin.local.owner"
        const val LOCAL_RUNTIME = "tin.local.runtime"
        const val LOCAL_MAX_SESSIONS = "tin.local.max.sessions"
        const val MAX_PORTS = "tin.ports.max"
        const val PORT_CHECK_SECONDS = "tin.ports.check.seconds"
        const val PORT_CHECK_WAIT = "tin.ports.check.wait.millis"
        const val LOCAL_DEFAULT_TTL = "tin.local.ttl.default.seconds"
        const val LOCAL_MAX_TTL = "tin.local.ttl.max.seconds"
        const val LOCAL_IDLE = "tin.local.idle.seconds"
        const val COMMAND_WAIT = "tin.command.wait.seconds"
        const val COMMAND_TIMEOUT = "tin.command.timeout.seconds"
        const val OUTPUT_BYTES = "tin.output.bytes"
        const val LOCAL_OUTPUT_KEPT = "tin.local.output.kept.bytes"
        const val OUTPUT_SHOWN = "tin.output.shown.bytes"
        const val REQUEST_TIMEOUT = "tin.request.timeout.seconds"
        const val EDIT_UNDO_STEPS = "tin.edit.undo.steps"
        const val SESSION_IDLE = "tin.session.idle.seconds"

        val RUNTIMES = setOf(Config.RUNTIME_LOCAL, Config.RUNTIME_DOCKER)
    }
}
