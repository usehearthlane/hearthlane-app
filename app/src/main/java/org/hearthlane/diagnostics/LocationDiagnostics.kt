package org.hearthlane.diagnostics

import org.hearthlane.core.relay.RelayConnection
import org.hearthlane.location.LocationDiagnosticsMonitor
import org.hearthlane.location.LocationForegroundService
import org.hearthlane.location.LocationPermissionSnapshot
import org.hearthlane.location.LocationReadStatus
import org.hearthlane.location.PublishDecisionReason
import org.hearthlane.location.TsnetLifecycleMonitor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sanitized, presentation-ready observability snapshot for the location
 * capability, shown in the technical (English) Diagnostics screen.
 *
 * The snapshot only ever contains safe metadata: states, modes, intervals,
 * timestamps, the device id/nickname, classified results, and — since V2
 * Phase 1 — provider, accuracy, fix age, decision reasons, transport and
 * durations. It never carries coordinates, payloads or any secret.
 */
data class LocationDiagnosticsSnapshot(
    val sharingEnabled: String,
    val foregroundPermission: String,
    val backgroundPermission: String,
    val locationServices: String,
    val foregroundService: String,
    val publisherState: String,
    val publisherMode: String,
    val locationCheckIntervalLabel: String,
    val minPublishIntervalLabel: String,
    val movementThresholdLabel: String,
    val maxPublishIntervalLabel: String,
    val mapActiveIntervalLabel: String,
    val lastRead: String?,
    val lastReadResult: String?,
    val lastPublishAttempt: String?,
    val lastPublishResult: String?,
    val lastSuccessfulPublish: String?,
    val pendingLocation: String,
    val relay: String,
    val deviceId: String,
    val deviceNickname: String,
    val lastFixProvider: String? = null,
    val lastFixAccuracy: String? = null,
    val lastFixAge: String? = null,
    val lastPublishDecision: String? = null,
    val tsnetState: String = "Stopped",
    val tsnetStarts: Int = 0,
    val tsnetStops: Int = 0,
    val tsnetLastStartDuration: String? = null,
    val tsnetLastStartAt: String? = null,
    val tsnetLastStopAt: String? = null,
    val tsnetLastTransport: String? = null,
    val tsnetLastNetwork: String? = null,
    val tsnetPublishAttempts: Int = 0,
    val tsnetPublishSuccesses: Int = 0,
    val tsnetPublishFailures: Int = 0,
)

/**
 * Builds the [LocationDiagnosticsSnapshot] from the production state holders:
 * the persisted sharing preference, the real permission/location snapshot, the
 * publishing metadata reported by the foreground service monitor, the location
 * tsnet lifecycle monitor, the relay connectivity and the device identity.
 * Pure and testable without Android.
 */
fun buildLocationDiagnosticsSnapshot(
    sharingEnabled: Boolean,
    permissions: LocationPermissionSnapshot,
    backgroundPermissionRequired: Boolean,
    publishing: LocationDiagnosticsMonitor.PublishingState,
    relay: RelayConnection?,
    deviceId: String,
    deviceNickname: String,
    tsnet: TsnetLifecycleMonitor.State = TsnetLifecycleMonitor.state.value,
    locationCheckIntervalMs: Long = LocationForegroundService.BACKGROUND_INTERVAL_MS,
    minPublishIntervalMs: Long = LocationForegroundService.MIN_PUBLISH_INTERVAL_MS,
    distanceThresholdMeters: Double = LocationForegroundService.DISTANCE_THRESHOLD_METERS,
    maxPublishIntervalMs: Long = LocationForegroundService.MAX_PUBLISH_INTERVAL_MS,
    mapActiveIntervalMs: Long = LocationForegroundService.ACTIVE_INTERVAL_MS,
): LocationDiagnosticsSnapshot = LocationDiagnosticsSnapshot(
    sharingEnabled = yesNo(sharingEnabled),
    foregroundPermission = if (permissions.foregroundGranted) "Granted" else "Denied",
    backgroundPermission = when {
        !backgroundPermissionRequired -> "Not required"
        permissions.backgroundGranted -> "Granted"
        else -> "Denied"
    },
    locationServices = if (permissions.locationEnabled) "Enabled" else "Disabled",
    foregroundService = if (publishing.serviceRunning) "Running" else "Stopped",
    publisherState = publisherStateLabel(publishing),
    publisherMode = publisherModeLabel(sharingEnabled, publishing.intervalMs, mapActiveIntervalMs),
    locationCheckIntervalLabel = intervalLabel(locationCheckIntervalMs),
    minPublishIntervalLabel = intervalLabel(minPublishIntervalMs),
    movementThresholdLabel = "${distanceThresholdMeters.toInt()} m",
    maxPublishIntervalLabel = intervalLabel(maxPublishIntervalMs),
    mapActiveIntervalLabel = intervalLabel(mapActiveIntervalMs),
    lastRead = timeLabel(publishing.lastReadAtMs),
    lastReadResult = classifyReadResult(publishing.lastReadResult),
    lastPublishAttempt = timeLabel(publishing.lastPublishAttemptAtMs),
    lastPublishResult = classifyResult(publishing.lastPublishResult),
    lastSuccessfulPublish = timeLabel(publishing.lastPublishAtMs),
    pendingLocation = yesNo(publishing.hasPendingLocation),
    relay = when (relay) {
        is RelayConnection.Connected -> "Reachable"
        is RelayConnection.Failed -> "Unreachable"
        else -> "Unknown"
    },
    deviceId = deviceId,
    deviceNickname = deviceNickname.ifBlank { "(unset)" },
    lastFixProvider = publishing.lastFixProvider,
    lastFixAccuracy = fixAccuracyLabel(publishing.lastFixAccuracyMeters),
    lastFixAge = publishing.lastFixAgeMs?.let(::intervalLabel),
    lastPublishDecision = publishDecisionLabel(publishing.lastPublishDecision),
    tsnetState = if (tsnet.currentRunning) "Running" else "Stopped",
    tsnetStarts = tsnet.startCount,
    tsnetStops = tsnet.stopCount,
    tsnetLastStartDuration = tsnet.lastStartDurationMs?.let { durationLabel(it) },
    tsnetLastStartAt = tsnet.lastStartElapsedRealtime?.let(::formatUptime),
    tsnetLastStopAt = tsnet.lastStopElapsedRealtime?.let(::formatUptime),
    tsnetLastTransport = tsnet.lastTransport,
    tsnetLastNetwork = tsnet.lastNetworkType,
    tsnetPublishAttempts = tsnet.publishAttemptCount,
    tsnetPublishSuccesses = tsnet.publishSuccessCount,
    tsnetPublishFailures = tsnet.publishFailureCount,
)

/** "Waiting" while the loop is up and the last publish succeeded, "Error" when
 *  the last publish failed, "Idle" when the loop is down. A failure is a
 *  transient operational state: after a successful publish it becomes Waiting. */
private fun publisherStateLabel(publishing: LocationDiagnosticsMonitor.PublishingState): String = when {
    !publishing.publisherRunning -> "Idle"
    publishing.lastPublishResult != null && publishing.lastPublishResult != "Success" -> "Error"
    else -> "Waiting"
}

/** Mode reflects the actual active interval: Disabled when sharing is off,
 *  Map active while the map requests the active cadence, else Background. */
private fun publisherModeLabel(sharingEnabled: Boolean, intervalMs: Long, mapActiveIntervalMs: Long): String = when {
    !sharingEnabled -> "Disabled"
    intervalMs == mapActiveIntervalMs -> "Map active"
    else -> "Background"
}

/** Formats a duration as "5 min" or "30 sec" from the real configured value. */
private fun intervalLabel(ms: Long): String = when {
    ms % 60_000L == 0L -> "${ms / 60_000L} min"
    else -> "${ms / 1_000L} sec"
}

/** Classifies a read status name into a safe, human label. */
fun classifyReadResult(statusName: String?): String? = when (statusName) {
    null -> null
    LocationReadStatus.SUCCESS.name -> "Success"
    LocationReadStatus.NO_POSITION.name -> "Unavailable"
    LocationReadStatus.NO_PERMISSION.name -> "Permission denied"
    LocationReadStatus.LOCATION_DISABLED.name -> "Location disabled"
    LocationReadStatus.TIMEOUT.name -> "Timeout"
    else -> "Error"
}

/**
 * Classifies a raw publish outcome into a sanitized label. Network/HTTP/DNS
 * failures are collapsed so Diagnostics never leaks hostnames, paths or
 * payloads.
 */
fun classifyResult(raw: String?): String? = when {
    raw == null -> null
    raw == "Success" -> "Success"
    raw == "Location unavailable" -> "Location unavailable"
    raw.contains("HTTP 4") -> "HTTP 4xx"
    raw.contains("HTTP 5") -> "HTTP 5xx"
    raw.contains("timed out") || raw.contains("timeout") -> "Timeout"
    raw.contains("resolve") || raw.contains("UnknownHost") -> "DNS error"
    else -> "Network error"
}

/** "HH:mm:ss" local time for a wall-clock timestamp, or null when never. */
private fun timeLabel(atMs: Long?): String? = atMs?.let {
    TIME_FORMAT.format(Date(it))
}

/**
 * Accuracy label for the last fix ("20 m"), or null when never; "n/a" for a
 * non-usable value (NaN/negative), matching the map's accuracy display rules.
 */
fun fixAccuracyLabel(accuracyMeters: Float?): String? = when {
    accuracyMeters == null -> null
    accuracyMeters.isNaN() || accuracyMeters < 0f -> "n/a"
    else -> "${Math.round(accuracyMeters)} m"
}

/**
 * Developer-oriented publish-decision label ("PUBLISH MOVEMENT"), or null when
 * no decision was made yet. The reason name is the stable enum name used in
 * the structured events.
 */
fun publishDecisionLabel(reasonName: String?): String? = reasonName?.let {
    val reason = runCatching { PublishDecisionReason.valueOf(it) }.getOrNull()
    if (reason == null) it else "${if (reason.publishes) "PUBLISH" else "SKIP"} ${reason.name}"
}

/** Duration label for a short measurement ("4.2 s"). */
fun durationLabel(ms: Long): String = if (ms < 1_000L) {
    "$ms ms"
} else {
    "%.1f s".format(Locale.US, ms / 1_000.0)
}

/**
 * Uptime label for an `elapsedRealtime` timestamp ("1h02m03s"), relative to
 * boot like the monotonic clock it formats. Pure and testable.
 */
fun formatUptime(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h${minutes.toString().padStart(2, '0')}m${seconds.toString().padStart(2, '0')}s"
        minutes > 0L -> "${minutes}m${seconds.toString().padStart(2, '0')}s"
        else -> "${seconds}s"
    }
}

private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)