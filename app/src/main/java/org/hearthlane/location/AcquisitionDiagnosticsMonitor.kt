package org.hearthlane.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process observability for the adaptive location acquisition
 * ([AcquisitionController]): which providers are registered, GPS escalation
 * metadata and counts. Records ONLY sanitized metadata — provider names,
 * reasons, monotonic timestamps, counts — never coordinates or payloads.
 */
object AcquisitionDiagnosticsMonitor {

    data class State(
        val running: Boolean = false,
        val registeredProviders: List<String> = emptyList(),
        val gpsInFlight: Boolean = false,
        val lastGpsRequestReason: String? = null,
        val lastGpsRequestAtMs: Long? = null,
        val lastGpsResult: String? = null,
        val gpsRequestCount: Int = 0,
        val gpsFailureCount: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Called by the acquisition controller after start/stop. */
    internal fun onAcquisitionStarted(registeredProviders: List<String>) {
        _state.update { it.copy(running = true, registeredProviders = registeredProviders) }
    }

    /** Called by the acquisition controller on stop. */
    internal fun onAcquisitionStopped() {
        _state.update { it.copy(running = false, registeredProviders = emptyList(), gpsInFlight = false) }
    }

    /** Called by the acquisition controller after each policy observation. */
    internal fun onPolicyObservability(observability: AcquisitionObservability) {
        _state.update {
            it.copy(
                gpsInFlight = observability.gpsInFlight,
                lastGpsRequestReason = observability.lastGpsRequestReason?.name,
                lastGpsRequestAtMs = observability.lastGpsRequestAtMs,
                lastGpsResult = observability.lastGpsResult?.name,
                gpsRequestCount = observability.gpsRequestCount,
                gpsFailureCount = observability.gpsFailureCount,
            )
        }
    }

    /** Test seam: resets the shared state. */
    internal fun resetForTest() {
        _state.value = State()
    }
}