package org.hearthlane.location

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process observability for the location publisher's embedded tsnet
 * lifecycle and publish outcomes.
 *
 * This is the location-specific counterpart to [LocationDiagnosticsMonitor]:
 * it records ONLY lifecycle transitions and publish metadata (counts,
 * monotonic timestamps, transport label, network type) — never coordinates,
 * tokens, payloads, device IDs or private IPs. It lets a controlled physical
 * measurement confirm that the node is started strictly on demand and stopped
 * after every remote publish, instead of idling for days.
 *
 * `elapsedRealtime` is used (not wall clock) so the numbers are meaningful
 * across clock changes and matches the domain the battery measurement cares
 * about (how long the node stays up).
 */
object TsnetLifecycleMonitor {

    data class State(
        val startCount: Int = 0,
        val stopCount: Int = 0,
        val lastStartElapsedRealtime: Long? = null,
        val lastStopElapsedRealtime: Long? = null,
        val currentRunning: Boolean = false,
        val lastStartDurationMs: Long? = null,
        val publishAttemptCount: Int = 0,
        val publishSuccessCount: Int = 0,
        val publishFailureCount: Int = 0,
        /** Transport of the last publish: "LOCAL" or "TAILSCALE". */
        val lastTransport: String? = null,
        /** Active network type at the last publish: "WIFI", "CELLULAR", "OTHER" or null. */
        val lastNetworkType: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Records that the publisher's tsnet node transitioned to running. */
    internal fun onNodeStart() {
        val now = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                startCount = it.startCount + 1,
                lastStartElapsedRealtime = now,
                currentRunning = true,
            )
        }
    }

    /** Records that the publisher's tsnet node was stopped. No-op when idle. */
    internal fun onNodeStop() {
        val now = SystemClock.elapsedRealtime()
        _state.update {
            if (!it.currentRunning) {
                it
            } else {
                it.copy(
                    stopCount = it.stopCount + 1,
                    lastStopElapsedRealtime = now,
                    currentRunning = false,
                    lastStartDurationMs = it.lastStartElapsedRealtime?.let { start -> now - start },
                )
            }
        }
    }

    /** Records a publish attempt with the active network type at that moment. */
    internal fun onPublishAttempt(networkType: String?) {
        _state.update {
            it.copy(
                publishAttemptCount = it.publishAttemptCount + 1,
                lastNetworkType = networkType,
            )
        }
    }

    /** Records a successful publish and the transport that carried it. */
    internal fun onPublishSuccess(transport: String?) {
        _state.update {
            it.copy(
                publishSuccessCount = it.publishSuccessCount + 1,
                lastTransport = transport,
            )
        }
    }

    /** Records a failed publish and the transport that was attempted. */
    internal fun onPublishFailure(transport: String?) {
        _state.update {
            it.copy(
                publishFailureCount = it.publishFailureCount + 1,
                lastTransport = transport,
            )
        }
    }

    /** Test seam: resets the shared state. */
    internal fun resetForTest() {
        _state.value = State()
    }
}
