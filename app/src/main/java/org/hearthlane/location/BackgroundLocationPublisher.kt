package org.hearthlane.location

import android.location.Location
import org.hearthlane.core.relay.DeviceLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Adaptive last-known publication loop.
 *
 * Every [checkIntervalMs] the loop reads a fresh foreground fix and replaces a
 * single in-memory latest location (never a list, never a history). A publish
 * happens only when [shouldPublish] decides: never published yet, OR moved at
 * least the movement threshold beyond the last location that was actually
 * published, OR the maximum publish interval elapsed. Reads are cheaper than
 * publishes, so movement is detected without waiting a fixed 5 minutes.
 *
 * [lastPublished] is only advanced on a successful publish, so the movement
 * decision always measures against the last location that reached the relay —
 * a failed publish keeps the pending location and the next cycle retries the
 * newest position (throttled by the minimum publish interval). On success the
 * pending location is cleared, so Diagnostics never reports a stale "Pending"
 * or "Error" after recovery.
 *
 * [stop] cancels the loop; no further publish is started.
 *
 * [distanceMeters] and [clockMs] are test seams (great-circle distance and the
 * wall clock used by the publish-policy timers).
 */
internal class BackgroundLocationPublisher(
    private val readLocation: suspend () -> LocationReadResult,
    private val publish: suspend (String, DeviceLocation) -> Int,
    private val deviceId: () -> String,
    private val checkIntervalMs: () -> Long,
    private val scope: CoroutineScope,
    private val onPublishFailure: (() -> Unit)? = null,
    private val distanceMeters: (DeviceLocation, DeviceLocation) -> Double = ::geoDistanceMeters,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    data class State(
        val running: Boolean = false,
        val publishCount: Int = 0,
        val lastLocation: DeviceLocation? = null,
        val lastError: String? = null,
        /** Last successful publish (wall-clock ms). */
        val lastPublishAtMs: Long? = null,
        /** Last publish attempt, successful or not (wall-clock ms). */
        val lastPublishAttemptAtMs: Long? = null,
        /** Sanitized publish outcome ("Success" or the raw error message). */
        val lastPublishResult: String? = null,
        /** Last location read attempt (wall-clock ms). */
        val lastReadAtMs: Long? = null,
        /** [LocationReadStatus] name of the last read, or ERROR on exception. */
        val lastReadResult: String? = null,
        /** Whether a location is currently awaiting a publish decision. */
        val hasPendingLocation: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var latestPending: DeviceLocation? = null
    private var lastPublished: DeviceLocation? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(running = true) }
            while (isActive) {
                publishLatest()
                delay(checkIntervalMs())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false) }
    }

    /**
     * One check/publish cycle. Reads the latest fix, replaces the single pending
     * location, and publishes it when the adaptive policy decides to.
     */
    suspend fun publishLatest(): DeviceLocation? {
        val now = clockMs()
        val fix = try {
            readLocation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        _state.update {
            it.copy(
                lastReadAtMs = now,
                lastReadResult = fix?.status?.name ?: LocationReadStatus.ERROR.name,
            )
        }
        fix?.sample?.let { sample ->
            latestPending = DeviceLocation(
                latitude = sample.latitude,
                longitude = sample.longitude,
                accuracy = sample.accuracyMeters,
                recordedAtEpochMs = sample.recordedAtWallClockMs,
            )
            _state.update { it.copy(hasPendingLocation = true) }
        }
        val pending = latestPending ?: return null
        if (!shouldPublish(
                nowMs = now,
                neverPublished = lastPublished == null,
                lastPublishAtMs = _state.value.lastPublishAtMs,
                lastPublishAttemptAtMs = _state.value.lastPublishAttemptAtMs,
                distanceFromLastPublishedMeters = lastPublished?.let { distanceMeters(it, pending) },
                pendingAccuracyMeters = pending.accuracy,
                minPublishIntervalMs = LocationForegroundService.MIN_PUBLISH_INTERVAL_MS,
                distanceThresholdMeters = LocationForegroundService.DISTANCE_THRESHOLD_METERS,
                maxPublishIntervalMs = LocationForegroundService.MAX_PUBLISH_INTERVAL_MS,
            )
        ) {
            return null
        }
        _state.update { it.copy(lastPublishAttemptAtMs = now) }
        val id = deviceId()
        return try {
            val status = publish(id, pending)
            lastPublished = pending
            latestPending = null
            _state.update { current ->
                current.copy(
                    publishCount = current.publishCount + 1,
                    lastLocation = pending,
                    lastError = null,
                    lastPublishAtMs = now,
                    lastPublishResult = "Success",
                    hasPendingLocation = false,
                )
            }
            pending
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "publish failed"
            _state.update { it.copy(lastError = message, lastPublishResult = message) }
            onPublishFailure?.invoke()
            null
        }
    }
}

/**
 * Adaptive publish decision. Publish when:
 *  - nothing was ever published, OR
 *  - at least [minPublishIntervalMs] elapsed since the last attempt AND
 *    (moved at least the movement threshold beyond the last PUBLISHED location,
 *     OR [maxPublishIntervalMs] elapsed since the last publish).
 *
 * The movement threshold is `max(distanceThresholdMeters, accuracy)` so GPS
 * jitter within the reported accuracy never triggers a publish. Pure and
 * injectable for tests.
 */
internal fun shouldPublish(
    nowMs: Long,
    neverPublished: Boolean,
    lastPublishAtMs: Long?,
    lastPublishAttemptAtMs: Long?,
    distanceFromLastPublishedMeters: Double?,
    pendingAccuracyMeters: Float?,
    minPublishIntervalMs: Long,
    distanceThresholdMeters: Double,
    maxPublishIntervalMs: Long,
): Boolean {
    if (neverPublished) return true
    val sinceAttempt = lastPublishAttemptAtMs?.let { nowMs - it } ?: Long.MAX_VALUE
    if (sinceAttempt < minPublishIntervalMs) return false
    val sincePublish = lastPublishAtMs?.let { nowMs - it } ?: Long.MAX_VALUE
    if (sincePublish >= maxPublishIntervalMs) return true
    val distance = distanceFromLastPublishedMeters ?: return false
    val accuracy = pendingAccuracyMeters?.takeIf { !it.isNaN() && it >= 0f } ?: 0f
    val threshold = max(distanceThresholdMeters, accuracy.toDouble())
    return distance >= threshold
}

/** Great-circle distance via the Android location stack. */
private fun geoDistanceMeters(a: DeviceLocation, b: DeviceLocation): Double {
    val results = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
    return abs(results[0].toDouble())
}