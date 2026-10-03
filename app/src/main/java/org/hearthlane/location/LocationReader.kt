package org.hearthlane.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * One-shot location reads over android.location.LocationManager (no Google
 * Play Services). The explicit [provider] is chosen by the caller — the
 * acquisition controller requests NETWORK for the initial fix and GPS on
 * demand — and the SDK split (getCurrentLocation on API 30+,
 * requestSingleUpdate on API 26-29) is handled here.
 *
 * Fix conversion to [LocationSample] lives in [toLocationSample] so the
 * acquisition controller shares the exact same sample model and monotonic age
 * computation for listener-delivered fixes.
 */
@SuppressLint("MissingPermission")
class LocationReader(
    private val context: Context,
    private val locationManager: LocationManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Requests a fresh one-shot position from [provider], bounded by
     * [timeoutMs]. Never blocks the main thread. Returns
     * NO_PERMISSION / LOCATION_DISABLED / TIMEOUT / NO_POSITION / SUCCESS.
     */
    @SuppressLint("NewApi")
    suspend fun readCurrent(provider: String, timeoutMs: Long): LocationReadResult =
        withContext(ioDispatcher) {
            if (!hasCoarsePermission()) return@withContext permissionDenied()
            if (!locationManager.isProviderEnabled(provider)) {
                return@withContext LocationReadResult(
                    status = LocationReadStatus.LOCATION_DISABLED,
                    message = "provider disabled: $provider",
                )
            }

            val startedAt = SystemClock.elapsedRealtimeNanos()
            val acquired = try {
                withTimeout(timeoutMs) {
                    when (LocationReadingStrategy.apiForSdk(Build.VERSION.SDK_INT)) {
                        LocationApi.API30_CURRENT -> currentApi30(provider)
                        LocationApi.API26_SINGLE_UPDATE -> singleUpdateApi26(provider)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                return@withContext LocationReadResult(
                    status = LocationReadStatus.TIMEOUT,
                    message = "no fix within ${timeoutMs}ms ($provider)",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext LocationReadResult(
                    status = LocationReadStatus.ERROR,
                    message = "current read failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }

            val location = acquired ?: return@withContext LocationReadResult(
                status = LocationReadStatus.NO_POSITION,
                message = "no position delivered by $provider",
            )

            LocationReadResult(
                status = LocationReadStatus.SUCCESS,
                sample = location.toLocationSample(
                    provider = location.provider ?: provider,
                    nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                    acquisitionMs = LocationTime.durationMs(
                        startedAt,
                        SystemClock.elapsedRealtimeNanos(),
                    ),
                    fromLastKnown = false,
                ),
            )
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun currentApi30(provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            // Direct executor: the consumer only resumes the continuation.
            val executor = Executor { command -> command.run() }
            try {
                locationManager.getCurrentLocation(provider, signal, executor) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                // Provider disabled/unavailable between the check and the call.
                if (cont.isActive) cont.resume(null)
            }
        }

    private suspend fun singleUpdateApi26(provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = LocationListener { location ->
                if (cont.isActive) cont.resume(location)
            }
            cont.invokeOnCancellation { runCatching { locationManager.removeUpdates(listener) } }
            try {
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                // Provider disabled/unavailable between the check and the call.
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun hasCoarsePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun permissionDenied(): LocationReadResult = LocationReadResult(
        status = LocationReadStatus.NO_PERMISSION,
        message = "missing ${Manifest.permission.ACCESS_COARSE_LOCATION}",
    )
}

/**
 * Converts a framework [Location] into the shared [LocationSample] model using
 * the monotonic clock for age. Shared by the one-shot reader and the
 * acquisition controller's listener callbacks.
 */
internal fun Location.toLocationSample(
    provider: String,
    nowElapsedNanos: Long,
    acquisitionMs: Long,
    fromLastKnown: Boolean,
): LocationSample {
    val recordedElapsed = runCatching { elapsedRealtimeNanos }.getOrDefault(nowElapsedNanos)
    return LocationSample(
        provider = provider,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = runCatching { accuracy }.getOrDefault(Float.NaN),
        recordedAtWallClockMs = time,
        recordedAtElapsedNanos = recordedElapsed,
        ageMs = LocationTime.ageMs(recordedElapsed, nowElapsedNanos),
        acquisitionMs = acquisitionMs,
        fromLastKnown = fromLastKnown,
    )
}