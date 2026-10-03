package org.hearthlane.location

import android.util.Log
import org.hearthlane.core.connectivity.TailscaleAuthRequired
import org.hearthlane.core.relay.RelayException
import java.util.Locale

/**
 * Structured, low-noise observability for the location publishing pipeline.
 *
 * Every event is a stable [LocationEvent] name plus an ordered list of
 * fields. The formatter is pure and unit-tested; the [emit] call is a thin
 * wrapper over `Log.i` with the shared [TAG], so adb/logcat analysis sees one
 * stable tag and parseable `key=value` lines.
 *
 * PRIVACY: events never carry coordinates. The allowed fields are provider,
 * accuracy, age, distances, timestamps, durations, reasons, transports and
 * counts. [format] drops null fields so optional context stays optional.
 */
object LocationEventLog {

    const val TAG = "LocationPublisher"

    const val EVENT_FIX_RECEIVED = "location-fix-received"
    const val EVENT_FIX_REJECTED = "location-fix-rejected"
    const val EVENT_PENDING_UPDATED = "pending-updated"
    const val EVENT_PUBLISH_DECISION = "publish-decision"
    const val EVENT_PUBLISH_START = "publish-start"
    const val EVENT_PUBLISH_SUCCESS = "publish-success"
    const val EVENT_PUBLISH_FAILURE = "publish-failure"
    const val EVENT_BACKOFF = "backoff"

    // Acquisition events (Phase 3).
    const val EVENT_ACQUISITION_START = "acquisition-start"
    const val EVENT_ACQUISITION_STOP = "acquisition-stop"
    const val EVENT_PROVIDER_REGISTERED = "provider-registered"
    const val EVENT_PROVIDER_REGISTRATION_FAILED = "provider-registration-failed"
    const val EVENT_PROVIDER_DISABLED = "provider-disabled"
    const val EVENT_GPS_REQUEST = "gps-request"
    const val EVENT_GPS_RESULT = "gps-result"
    const val EVENT_GPS_TIMEOUT = "gps-timeout"
    const val EVENT_GPS_SKIPPED = "gps-skipped"
    const val EVENT_MODE_CHANGED = "mode-changed"
    const val EVENT_NETWORK_REGAINED = "network-regained"

    /** Emits [event] through logcat with the shared tag. */
    fun emit(event: LocationEvent) {
        Log.i(TAG, format(event))
    }

    /** Pure "event=<name> key=value ..." rendering; null fields are omitted. */
    fun format(event: LocationEvent): String = buildString {
        append("event=").append(event.name)
        for ((key, value) in event.fields) {
            if (value != null) {
                append(' ').append(key).append('=').append(formatValue(value))
            }
        }
    }

    /**
     * Coarse failure category for the publish-failure event, derived from the
     * exception only. CancellationException is deliberately not classified:
     * the publish path rethrows it and never records it as a failure.
     */
    fun classifyFailure(e: Exception): String {
        val message = e.message ?: ""
        return when {
            e is TailscaleAuthRequired -> "AUTH_REQUIRED"
            message.contains("did not reach Running") ||
                message.contains("timed out") ||
                message.contains("timeout") -> "TIMEOUT"
            e is RelayException && message.contains("HTTP 4") -> "HTTP_4XX"
            e is RelayException && message.contains("HTTP 5") -> "HTTP_5XX"
            message.contains("resolve") || message.contains("UnknownHost") -> "DNS"
            e is RelayException -> "RELAY"
            else -> "NETWORK"
        }
    }

    private fun formatValue(value: Any): String = when (value) {
        is Float -> "%.1f".format(Locale.US, value)
        is Double -> "%.1f".format(Locale.US, value)
        else -> value.toString()
    }
}

/** One structured pipeline event: stable name plus optional typed fields. */
data class LocationEvent(
    val name: String,
    val fields: List<Pair<String, Any?>> = emptyList(),
)