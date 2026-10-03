package org.hearthlane.location

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Adaptive location acquisition (V2 Phase 3).
 *
 * NETWORK_PROVIDER and PASSIVE_PROVIDER are registered continuously while the
 * sharing foreground service is active; GPS_PROVIDER is strictly ONE-SHOT on
 * demand, gated by the pure [AcquisitionPolicy] (permission, provider,
 * cooldown, in-flight) and the stationary GNSS safeguard.
 *
 * The controller owns acquisition ONLY:
 * - provider usage and GPS escalation come from the policy;
 * - every delivered fix (listener or GPS) is converted to a [LocationSample]
 *   and handed to the publish machine through [onFix] — the Phase 2 machine
 *   stays the sole authority over usefulness, pending and publishing.
 *
 * All interactions are permission-aware and failure-safe: provider disabled,
 * SecurityException and registration failures are observed, never crash the
 * foreground service. No coordinate ever reaches an event or diagnostics.
 */
internal class AcquisitionController(
    private val policy: AcquisitionPolicy,
    private val scope: CoroutineScope,
    private val hasFinePermission: () -> Boolean,
    private val hasPublishableFix: () -> Boolean,
    private val isProviderEnabled: (String) -> Boolean,
    private val registerUpdates: (String, Long, Float, LocationListener) -> Unit,
    private val unregisterUpdates: (LocationListener) -> Unit,
    private val requestCurrent: suspend (String, Long) -> LocationReadResult,
    private val onFix: (LocationSample) -> Unit,
    private val emitEvent: (LocationEvent) -> Unit = { LocationEventLog.emit(it) },
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val evaluationIntervalMs: Long = EVALUATION_INTERVAL_MS,
) {

    private val continuousProviders: List<String> =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)

    private val registeredListeners = mutableMapOf<String, LocationListener>()
    private var gpsJob: Job? = null
    private var evaluationJob: Job? = null
    private var running = false

    /**
     * Registers NETWORK + PASSIVE listeners and requests an initial cheap
     * fix over NETWORK (never GPS at startup — the policy decides escalation).
     */
    fun start() {
        if (running) return
        running = true
        registerContinuousProviders()
        emitEvent(LocationEvent(LocationEventLog.EVENT_ACQUISITION_START))
        AcquisitionDiagnosticsMonitor.onAcquisitionStarted(registeredProviders())
        scope.launch { requestInitialFix() }
        evaluationJob = scope.launch {
            while (isActive) {
                delay(evaluationIntervalMs)
                evaluateGps()
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        evaluationJob?.cancel()
        evaluationJob = null
        gpsJob?.cancel()
        gpsJob = null
        policy.onGpsAborted()
        for ((_, listener) in registeredListeners) {
            runCatching { unregisterUpdates(listener) }
        }
        registeredListeners.clear()
        emitEvent(LocationEvent(LocationEventLog.EVENT_ACQUISITION_STOP))
        AcquisitionDiagnosticsMonitor.onAcquisitionStopped()
    }

    /** Mode change: records it; MAP_ACTIVE requests one GPS refinement (edge). */
    fun onModeChanged(mode: PublisherMode) {
        emitEvent(LocationEvent(LocationEventLog.EVENT_MODE_CHANGED, listOf("mode" to mode.name)))
        if (mode == PublisherMode.MAP_ACTIVE) {
            evaluateMapActiveGps()
        }
    }

    /** Periodic GPS evaluation (no-op when no escalation context exists). */
    private fun evaluateGps() {
        handleDecision(
            policy.evaluateGps(
                hasFinePermission = hasFinePermission(),
                gpsEnabled = isProviderEnabled(LocationManager.GPS_PROVIDER),
                hasPublishableFix = hasPublishableFix(),
                nowMs = clockMs(),
            ),
        )
    }

    private fun evaluateMapActiveGps() {
        handleDecision(
            policy.evaluateMapActive(
                hasFinePermission = hasFinePermission(),
                gpsEnabled = isProviderEnabled(LocationManager.GPS_PROVIDER),
                nowMs = clockMs(),
            ),
        )
    }

    private fun handleDecision(decision: GpsDecision) {
        if (!decision.request) {
            decision.skipReason?.let { reason ->
                emitEvent(
                    LocationEvent(
                        LocationEventLog.EVENT_GPS_SKIPPED,
                        listOf("reason" to reason.name),
                    ),
                )
            }
            return
        }
        policy.onGpsStarted(decision.reason!!, clockMs())
        reportObservability()
        emitEvent(
            LocationEvent(
                LocationEventLog.EVENT_GPS_REQUEST,
                listOf("reason" to decision.reason.name),
            ),
        )
        gpsJob?.cancel()
        gpsJob = scope.launch {
            val startedAt = clockMs()
            val result = requestCurrent(LocationManager.GPS_PROVIDER, AcquisitionPolicy.GPS_TIMEOUT_MS)
            val elapsedMs = clockMs() - startedAt
            val sample = result.sample
            if (sample != null) {
                emitEvent(
                    LocationEvent(
                        LocationEventLog.EVENT_GPS_RESULT,
                        listOf(
                            "accuracyMeters" to sample.accuracyMeters,
                            "ageMs" to sample.ageMs,
                            "elapsedMs" to elapsedMs,
                        ),
                    ),
                )
                policy.onGpsCompleted(hasFix = true, nowMs = clockMs())
                onFix(sample)
            } else {
                emitEvent(
                    LocationEvent(
                        LocationEventLog.EVENT_GPS_TIMEOUT,
                        listOf(
                            "status" to result.status.name,
                            "elapsedMs" to elapsedMs,
                        ),
                    ),
                )
                policy.onGpsCompleted(hasFix = false, nowMs = clockMs())
            }
            reportObservability()
        }
    }

    private fun registerContinuousProviders() {
        for (provider in continuousProviders) {
            if (!isProviderEnabled(provider)) {
                emitEvent(
                    LocationEvent(
                        LocationEventLog.EVENT_PROVIDER_DISABLED,
                        listOf("provider" to provider),
                    ),
                )
                continue
            }
            val listener = LocationListener { location -> onListenerFix(provider, location) }
            try {
                registerUpdates(
                    provider,
                    AcquisitionPolicy.NETWORK_MIN_TIME_MS,
                    AcquisitionPolicy.NETWORK_MIN_DISTANCE_METERS.toFloat(),
                    listener,
                )
                registeredListeners[provider] = listener
                emitEvent(
                    LocationEvent(
                        LocationEventLog.EVENT_PROVIDER_REGISTERED,
                        listOf("provider" to provider),
                    ),
                )
            } catch (e: SecurityException) {
                emitEvent(
                    LocationEvent(
                        LocationEventLog.EVENT_PROVIDER_REGISTRATION_FAILED,
                        listOf("provider" to provider, "reason" to "SECURITY"),
                    ),
                )
            } catch (e: IllegalArgumentException) {
                emitEvent(
                    LocationEvent(
                        LocationEventLog.EVENT_PROVIDER_DISABLED,
                        listOf("provider" to provider),
                    ),
                )
            } catch (e: Exception) {
                emitEvent(
                    LocationEvent(
                        LocationEventLog.EVENT_PROVIDER_REGISTRATION_FAILED,
                        listOf("provider" to provider, "reason" to "ERROR"),
                    ),
                )
            }
        }
        reportObservability()
    }

    /** Listener callback entry (internal so tests can simulate deliveries). */
    internal fun onListenerFix(provider: String, location: Location) {
        val nowElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
        val sample = location.toLocationSample(
            provider = location.provider ?: provider,
            nowElapsedNanos = nowElapsedNanos,
            acquisitionMs = 0L,
            fromLastKnown = false,
        )
        observeAndForward(sample)
    }

    /** Initial cheap fix: a NETWORK one-shot, never GPS at startup. */
    private suspend fun requestInitialFix() {
        if (!isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return
        val result = requestCurrent(LocationManager.NETWORK_PROVIDER, AcquisitionPolicy.INITIAL_FIX_TIMEOUT_MS)
        val sample = result.sample
        if (sample != null) {
            observeAndForward(sample)
        } else {
            evaluateGps()
        }
    }

    /** Policy observation + publisher handoff (internal so tests can verify). */
    internal fun observeAndForward(sample: LocationSample) {
        policy.observeFix(
            FixObserved(sample.latitude, sample.longitude, sample.accuracyMeters),
            nowMs = clockMs(),
        )
        reportObservability()
        evaluateGps()
        onFix(sample)
    }

    private fun reportObservability() {
        AcquisitionDiagnosticsMonitor.onPolicyObservability(policy.observability())
    }

    private fun registeredProviders(): List<String> = registeredListeners.keys.toList().sorted()

    private companion object {
        /** Periodic policy evaluation cadence (GPS escalation, no reads). */
        const val EVALUATION_INTERVAL_MS = 30_000L
    }
}