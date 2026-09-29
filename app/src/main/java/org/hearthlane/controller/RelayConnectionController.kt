package org.hearthlane.controller

import android.util.Log
import org.hearthlane.settings.AppSettings
import org.hearthlane.core.connectivity.TsnetGateway
import org.hearthlane.core.relay.LocalRelayTransport
import org.hearthlane.core.relay.RelayClient
import org.hearthlane.core.relay.RelayConfig
import org.hearthlane.core.relay.RelayConnection
import org.hearthlane.core.relay.RelayConnectionManager
import org.hearthlane.core.relay.RelayTransportKind
import org.hearthlane.core.relay.TailscaleRelayTransport
import org.hearthlane.core.relay.relayHttpTransportFor
import org.hearthlane.core.relay.HttpRelayClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared connection/session state for the location relay, mirroring
 * [FrigateConnectionController]. Owns the transparent probe strategy
 * (LOCAL -> Tailscale) and exposes the win configuration; the map screen only
 * consumes the exposed state flows and never drives the Tailscale node
 * lifecycle. There is no restart-because-user-wants-replay tick here: queries
 * are fresh immediately after a successful probe.
 */
class RelayConnectionController internal constructor(
    val gateway: TsnetGateway,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val connector: suspend (baseUrl: String) -> RelayConnection,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Production constructor. Builds the proven transparent probe strategy
     *  from the injected [gateway]. */
    constructor(
        gateway: TsnetGateway,
        settings: AppSettings,
        scope: CoroutineScope,
    ) : this(
        gateway = gateway,
        settings = settings,
        scope = scope,
        connector = { baseUrl -> defaultConnect(baseUrl, gateway) },
        ioDispatcher = Dispatchers.IO,
    )

    /** Last probe result; null until the first attempt completes. */
    private val _connection = MutableStateFlow<RelayConnection?>(null)
    val connection: StateFlow<RelayConnection?> = _connection.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    /** Which transport the winning probe used; the map never shows it. */
    private val _lastProbedTransport = MutableStateFlow<RelayTransportKind?>(null)
    val lastProbedTransport: StateFlow<RelayTransportKind?> = _lastProbedTransport.asStateFlow()

    /** Last failure message, for diagnostics. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var probeInFlight = false
    private var probeJob: Job? = null

    /**
     * Probes the relay with the current settings and refreshes the shared
     * state. Concurrent calls are ignored: the map screen already drives the
     * cadence through [queryAndPublish]-style callers, so an overlapping
     * network re-probe can never double-start the embedded node.
     */
    fun probe() {
        if (probeInFlight) return
        probeInFlight = true
        _connecting.value = true
        val baseUrl = settings.relayBaseUrl.value
        Log.i(TAG, "relay probe requested (baseUrl=$baseUrl)")
        probeJob?.cancel()
        probeJob = scope.launch {
            try {
                val result = withContext(ioDispatcher) { connector(baseUrl) }
                if (result is RelayConnection.Connected) {
                    _lastProbedTransport.value = result.transport
                    _lastError.value = null
                    Log.i(TAG, "relay transport selected: ${result.transport}")
                } else {
                    (result as? RelayConnection.Failed)?.let {
                        _lastError.value = it.error
                        Log.w(TAG, "relay probe failed: ${it.error}")
                    }
                }
                _connection.value = result
            } finally {
                _connecting.value = false
                probeInFlight = false
            }
        }
    }

    /**
     * Cancels any in-flight probe when the process leaves the foreground, so a
     * probe that was already launched cannot acquire a tsnet lease after the
     * foreground lease is released (I7/I6). The map screen probes again on the
     * next foreground; this never tears down a cached LOCAL/Tailscale result.
     */
    fun stopForBackground() {
        probeJob?.cancel()
        probeJob = null
        // The cancelled probe's `finally` runs asynchronously on the next
        // dispatch; reset synchronously so a foreground return can probe again.
        _connecting.value = false
        probeInFlight = false
    }

    /** Clears the embedded node identity (administrator Settings reset). */
    fun resetTailscale() {
        scope.launch {
            runCatching { gateway.reset() }
                .onFailure { Log.w(TAG, "tailscale identity reset failed", it) }
            _connection.value = null
            _lastProbedTransport.value = null
            _lastError.value = null
        }
    }

    /**
     * Returns a relay client bound to the winning transport, or null when the
     * relay was never reached. The map screen quieres through this and treats
     * a null client as a disconnected/offline state.
     */
    suspend fun client(): RelayClient? {
        val connection = _connection.value as? RelayConnection.Connected ?: return null
        val baseUrl = settings.relayBaseUrl.value
        return HttpRelayClient(
            transport = relayHttpTransportFor(connection.transport, gateway),
            baseUrl = baseUrl,
        )
    }

    private companion object {
        const val TAG = "RelayConnection"

        private suspend fun defaultConnect(
            baseUrl: String,
            gateway: TsnetGateway,
        ): RelayConnection {
            val config = RelayConfig(
                localBaseUrl = baseUrl,
                tailscaleBaseUrl = baseUrl,
            )
            val manager = RelayConnectionManager(
                config = config,
                localTransport = LocalRelayTransport(config),
                tailscaleTransport = TailscaleRelayTransport(gateway, config),
                tailscaleGateway = gateway,
            )
            return manager.connect()
        }
    }
}