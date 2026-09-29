package org.hearthlane.controller

import org.hearthlane.core.frigate.FrigateConnection
import org.hearthlane.core.frigate.LiveQualityMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Product-facing summary of the current connection, derived from the shared
 * connection state without exposing infrastructure details (transport, LOCAL /
 * TAILSCALE, switch counters stay in Diagnostics).
 */
enum class ConnectionStatus {
    /** Frigate is reachable through the current path. */
    Connected,

    /** A connection attempt is in progress. */
    Connecting,

    /** Frigate is not reachable right now. */
    Unavailable,
}

/**
 * Screen state for the Settings screen. Only information that already exists
 * in the app is carried here: the configured Hearthlane server domain, a
 * product-facing connection summary, the auto-play and location-sharing
 * preferences and the app version/build.
 */
data class SettingsState(
    val baseDomain: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.Unavailable,
    val autoPlayEventClips: Boolean = true,
    val locationSharingEnabled: Boolean = false,
    val liveQualityMode: LiveQualityMode = LiveQualityMode.AUTO,
    val appVersion: String,
    val appBuild: String,
)

/**
 * Shared observable state for the Settings screen, following the
 * Controller -> StateFlow -> Screen pattern used across the app.
 *
 * The controller only shapes presentation data: it reads the persisted
 * Hearthlane base domain ([baseDomain]) and the shared connection flows,
 * derives a friendly [ConnectionStatus], exposes the auto-play preference, and
 * delegates the remote-access reconfigure action to the composition root. It
 * never holds Frigate/Tailscale/transport logic.
 */
class SettingsController(
    baseDomain: StateFlow<String>,
    connection: StateFlow<FrigateConnection?>,
    connecting: StateFlow<Boolean>,
    autoPlayEventClips: StateFlow<Boolean>,
    locationSharingEnabled: StateFlow<Boolean>,
    liveQualityMode: StateFlow<LiveQualityMode>,
    appVersion: String,
    appBuild: String,
    private val resetRemoteAccessAction: () -> Unit,
    private val setAutoPlayEventClipsAction: suspend (Boolean) -> Unit,
    private val setLocationSharingEnabledAction: suspend (Boolean) -> Unit,
    private val setLiveQualityModeAction: suspend (LiveQualityMode) -> Unit,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(
        SettingsState(
            baseDomain = baseDomain.value,
            connectionStatus = deriveConnectionStatus(connection.value, connecting.value),
            autoPlayEventClips = autoPlayEventClips.value,
            locationSharingEnabled = locationSharingEnabled.value,
            liveQualityMode = liveQualityMode.value,
            appVersion = appVersion,
            appBuild = appBuild,
        ),
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private var collectJob: Job? = null

    init {
        // combine() has no typed overload beyond 5 flows: the five base flows
        // build the state first, then the live quality mode is layered on.
        val base = combine(
            baseDomain,
            connection,
            connecting,
            autoPlayEventClips,
            locationSharingEnabled,
        ) { domain, conn, isConnecting, autoPlay, sharing ->
            SettingsState(
                baseDomain = domain,
                connectionStatus = deriveConnectionStatus(conn, isConnecting),
                autoPlayEventClips = autoPlay,
                locationSharingEnabled = sharing,
                appVersion = appVersion,
                appBuild = appBuild,
            )
        }
        collectJob = scope.launch {
            combine(base, liveQualityMode) { state, quality ->
                state.copy(liveQualityMode = quality)
            }.collect { _state.value = it }
        }
    }

    /**
     * Persists the auto-play preference for event clips. The change applies to
     * future event-detail entries; a playback already in progress is untouched.
     */
    fun setAutoPlayEventClips(enabled: Boolean) {
        scope.launch { setAutoPlayEventClipsAction(enabled) }
    }

    /**
     * Persists the location-sharing opt-in. The permission gate and the
     * foreground-service lifecycle are owned by the UI/composition root; the
     * controller only stores the preference.
     */
    fun setLocationSharingEnabled(enabled: Boolean) {
        scope.launch { setLocationSharingEnabledAction(enabled) }
    }

    /**
     * Persists the live quality preference. Applies to future Live sessions;
     * a playback already in progress keeps its current mode.
     */
    fun setLiveQualityMode(mode: LiveQualityMode) {
        scope.launch { setLiveQualityModeAction(mode) }
    }

    /**
     * Reconfigures remote access: clears the device's remote-access identity so
     * the next connection attempt re-registers it. The composition root wires
     * this to the real reset and keeps the follow-up navigation.
     */
    fun resetRemoteAccess() = resetRemoteAccessAction()

    /** Stops observing the shared state. Call when the Settings screen leaves. */
    fun release() {
        collectJob?.cancel()
        collectJob = null
    }

    /** Maps the shared connection state to a product-facing [ConnectionStatus]. */
    fun deriveConnectionStatus(
        connection: FrigateConnection?,
        connecting: Boolean,
    ): ConnectionStatus = when {
        connecting -> ConnectionStatus.Connecting
        connection is FrigateConnection.Connected -> ConnectionStatus.Connected
        else -> ConnectionStatus.Unavailable
    }
}
