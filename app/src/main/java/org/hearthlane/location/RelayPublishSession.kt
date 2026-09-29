package org.hearthlane.location

import org.hearthlane.core.connectivity.TsnetGateway
import org.hearthlane.core.relay.DeviceLocation
import org.hearthlane.core.relay.HttpRelayClient
import org.hearthlane.core.relay.LocalRelayTransport
import org.hearthlane.core.relay.RelayClient
import org.hearthlane.core.relay.RelayConfig
import org.hearthlane.core.relay.RelayConnection
import org.hearthlane.core.relay.RelayConnectionManager
import org.hearthlane.core.relay.RelayException
import org.hearthlane.core.relay.RelayTransportKind
import org.hearthlane.core.relay.TailscaleRelayTransport
import org.hearthlane.core.relay.relayHttpTransportFor
import kotlinx.coroutines.CancellationException

/**
 * Relay connectivity for the background publishing path.
 *
 * Probes the relay LOCAL first and falls back to Tailscale — the same
 * transparent strategy as the foreground path — then binds one [RelayClient]
 * to the winning transport.
 *
 * Lifecycle policy (the v0.1.1 battery fix): the exclusive location tsnet node
 * exists only for the duration of a publish that actually needs the remote
 * path. [publish] guarantees, via `finally`, that after any TAILSCALE attempt —
 * success or failure — the node is stopped and the cached remote connection is
 * invalidated, so the node never idles between publishes.
 *
 * Caching rules:
 * - A LOCAL connection is safe to reuse (it never touches the gateway), so it
 *   stays cached.
 * - A TAILSCALE connection is invalidated as soon as its publish ends, because
 *   the node is stopped and the cached remote transport must never be reused
 *   against a stopped node. The next publish re-probes and re-starts the node
 *   on demand.
 */
internal class RelayPublishSession(
    private val gateway: TsnetGateway,
    private val config: () -> RelayConfig,
    private val connector: suspend (RelayConfig) -> RelayConnection = defaultConnect(gateway),
    private val networkType: () -> String? = { null },
    private val lifecycle: TsnetLifecycleMonitor = TsnetLifecycleMonitor,
) {
    private var connection: RelayConnection? = null
    private var lastTransport: RelayTransportKind? = null

    /**
     * Publishes a location through the best transport and manages the tsnet
     * lifecycle: after any attempt the gateway's claim on the node is released
     * (best-effort, in `finally`). Releasing is a no-op when no claim was ever
     * acquired (LOCAL path), and it stops the node only when this instance was
     * the LAST consumer — a remote publish can never tear down a node the
     * foreground is still using. A cached remote connection is invalidated so a
     * later publish can reconnect cleanly.
     */
    suspend fun publish(deviceId: String, location: DeviceLocation): Int {
        lifecycle.onPublishAttempt(networkType())
        try {
            val client = client()
            val status = client.publishLocation(deviceId, location)
            lifecycle.onPublishSuccess(lastTransport?.name)
            return status
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lifecycle.onPublishFailure(lastTransport?.name)
            throw e
        } finally {
            if (lastTransport == RelayTransportKind.TAILSCALE) {
                invalidate()
            }
            // Release this instance's claim (no-op without a lease): a failed
            // or cancelled connect that already engaged the node must not leave
            // it Running without a logical owner.
            runCatching { gateway.stopIfRunning() }
            lifecycle.onNodeStop()
        }
    }

    /**
     * Returns a [RelayClient] bound to the currently active transport,
     * connecting LOCAL then Tailscale on the first call (or after
     * [invalidate]).
     */
    suspend fun client(): RelayClient {
        val kind = (connection as? RelayConnection.Connected)?.transport ?: connect().transport
        lastTransport = kind
        val cfg = config()
        return HttpRelayClient(
            transport = relayHttpTransportFor(kind, gateway),
            baseUrl = cfg.localBaseUrl,
            timeoutMs = cfg.requestTimeoutMs,
        )
    }

    /** Drops the cached transport so the next [client] call re-probes. */
    fun invalidate() {
        connection = null
        lastTransport = null
    }

    private suspend fun connect(): RelayConnection.Connected {
        val cfg = config()
        val result = connector(cfg)
        val connected = result as? RelayConnection.Connected
            ?: throw RelayException((result as RelayConnection.Failed).error)
        connection = connected
        lastTransport = connected.transport
        if (connected.transport == RelayTransportKind.TAILSCALE) {
            lifecycle.onNodeStart()
        }
        return connected
    }

    private companion object {
        /** Production connector: the proven transparent LOCAL -> Tailscale probe. */
        fun defaultConnect(gateway: TsnetGateway): suspend (RelayConfig) -> RelayConnection = { cfg ->
            RelayConnectionManager(
                config = cfg,
                localTransport = LocalRelayTransport(cfg),
                tailscaleTransport = TailscaleRelayTransport(gateway, cfg),
                tailscaleGateway = gateway,
            ).connect()
        }
    }
}
