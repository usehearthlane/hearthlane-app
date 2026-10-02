package org.hearthlane.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process observability source for the location publishing foreground
 * service.
 *
 * The service and the app share a process, so the service reports its real
 * lifecycle and the publisher's sanitized metadata here and the Diagnostics
 * screen reads them back. There is no heuristic: the service is the authority
 * for "Foreground service: Running/Stopped" and the publisher StateFlow is the
 * authority for publish/read metadata.
 *
 * Process recreation resets this to the default (Stopped/empty) state, which is
 * the honest answer until the service restarts and reports again — no stale
 * in-memory timestamps are invented. No coordinates or payload ever reach this
 * monitor; only timestamps, states and sanitized result labels.
 */
object LocationDiagnosticsMonitor {

    /** Sanitized publishing metadata, safe to expose in Diagnostics. */
    data class PublishingState(
        val serviceRunning: Boolean = false,
        val publisherRunning: Boolean = false,
        val intervalMs: Long = LocationForegroundService.BACKGROUND_INTERVAL_MS,
        val lastReadAtMs: Long? = null,
        val lastReadResult: String? = null,
        val lastPublishAttemptAtMs: Long? = null,
        val lastPublishResult: String? = null,
        val lastPublishAtMs: Long? = null,
        val hasPendingLocation: Boolean = false,
        val publishCount: Int = 0,
        val lastFixProvider: String? = null,
        val lastFixAccuracyMeters: Float? = null,
        val lastFixAgeMs: Long? = null,
        val lastPublishDecision: String? = null,
    )

    private val _state = MutableStateFlow(PublishingState())
    val state: StateFlow<PublishingState> = _state.asStateFlow()

    /** Called by the service whenever it starts a command (idempotent). */
    internal fun onServiceStarted(intervalMs: Long) {
        _state.update { it.copy(serviceRunning = true, intervalMs = intervalMs) }
    }

    /** Called by the service on destroy. */
    internal fun onServiceStopped() {
        _state.update { it.copy(serviceRunning = false, publisherRunning = false) }
    }

    /** Mirrors the publisher's production state as sanitized metadata. */
    internal fun onPublisherState(publisher: BackgroundLocationPublisher.State) {
        _state.update {
            it.copy(
                publisherRunning = publisher.running,
                lastReadAtMs = publisher.lastReadAtMs,
                lastReadResult = publisher.lastReadResult,
                lastPublishAttemptAtMs = publisher.lastPublishAttemptAtMs,
                lastPublishResult = publisher.lastPublishResult,
                lastPublishAtMs = publisher.lastPublishAtMs,
                hasPendingLocation = publisher.hasPendingLocation,
                publishCount = publisher.publishCount,
                lastFixProvider = publisher.lastFixProvider,
                lastFixAccuracyMeters = publisher.lastFixAccuracyMeters,
                lastFixAgeMs = publisher.lastFixAgeMs,
                lastPublishDecision = publisher.lastPublishDecision?.name,
            )
        }
    }

    /** Test seam: resets the shared state. */
    internal fun resetForTest() {
        _state.value = PublishingState()
    }
}