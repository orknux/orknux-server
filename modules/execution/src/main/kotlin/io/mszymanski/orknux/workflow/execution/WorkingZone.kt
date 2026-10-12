package io.mszymanski.orknux.workflow.execution

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The timezone a person reads a time in. The installation's working timezone
 * is the app's setting, so the app implements this; without one the machine's
 * own is used, which in a container is usually UTC.
 */
fun interface WorkingZone {
    fun zone(): ZoneId
}

/** When a wait of [wait] from [now] ends, as a person reads it: "2026-10-12 18:44:05 Europe/Warsaw". */
fun retryAt(now: Instant, wait: Duration, zone: ZoneId): String =
    "${RETRY_AT.format(now.plus(wait).atZone(zone))} ${zone.id}"

private val RETRY_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
