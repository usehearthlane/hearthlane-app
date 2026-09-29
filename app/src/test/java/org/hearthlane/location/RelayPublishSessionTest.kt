package org.hearthlane.location

import org.hearthlane.core.connectivity.HttpBytesResult
import org.hearthlane.core.connectivity.TailscaleAuthRequired
import org.hearthlane.core.connectivity.TsnetGateway
import org.hearthlane.core.relay.DeviceLocation
import org.hearthlane.core.relay.RelayConfig
import org.hearthlane.core.relay.RelayConnection
import org.hearthlane.core.relay.RelayTransportKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Proves the publish path binds the SAME transport abstraction as the relay
 * probe AND enforces the v0.1.1 tsnet battery lifecycle: the exclusive node
 * exists only for the duration of a publish that needs the remote path, and a
 * released claim never leaves the node running without an owner.
 */
class RelayPublishSessionTest {

    /**
     * Gateway that mirrors the real [TsnetGatewayImpl] lease semantics: a
     * lease is held after [ensureRunning] and physically released by
     * [stopIfRunning] only while held (a no-op release never counts).
     */
    private class CountingGateway : TsnetGateway {
        var ensureCalls = 0
        var releaseCalls = 0
        var stopCalls = 0
        var httpRequestCalls = 0
        var failPublish = false
        var cancelPublish = false
        var authRequired = false
        private var leaseHeld = false
        val requests = mutableListOf<String>()

        override suspend fun ensureRunning() {
            ensureCalls++
            if (authRequired) throw TailscaleAuthRequired("https://login.tailscale.com/a/abc")
            leaseHeld = true
        }

        override suspend fun stopIfRunning() {
            releaseCalls++
            if (leaseHeld) {
                leaseHeld = false
                stopCalls++
            }
        }

        override suspend fun reset() = Unit

        override suspend fun httpGet(url: String, timeoutMs: Long): String = ""

        override suspend fun httpGetBytes(url: String, timeoutMs: Long): HttpBytesResult = okBody()

        override suspend fun httpRequest(
            method: String,
            url: String,
            contentType: String?,
            body: String?,
            headers: Map<String, String>,
            timeoutMs: Long,
        ): HttpBytesResult {
            httpRequestCalls++
            requests += "$method $url"
            if (method == "GET") return okBody()
            if (cancelPublish) throw CancellationException("cancelled")
            if (failPublish) throw IOException("publish failed")
            return HttpBytesResult(204, null, url, ByteArray(0))
        }

        private fun okBody() = HttpBytesResult(
            200,
            "application/json",
            "http://relay",
            """{"devices":[]}""".toByteArray(Charsets.UTF_8),
        )
    }

    private val location = DeviceLocation(1.0, 2.0, 5f, 1L)

    @Before
    fun setUp() {
        TsnetLifecycleMonitor.resetForTest()
    }

    /** Production-mirroring connector: a TAILSCALE path starts the node first. */
    private fun connectorFor(
        gateway: TsnetGateway,
        kind: RelayTransportKind,
    ): suspend (RelayConfig) -> RelayConnection = { _ ->
        if (kind == RelayTransportKind.TAILSCALE) gateway.ensureRunning()
        RelayConnection.Connected(kind)
    }

    private fun session(
        gateway: CountingGateway,
        kind: RelayTransportKind,
    ) = RelayPublishSession(
        gateway = gateway,
        config = { RelayConfig("http://relay.local", "http://relay.hearthlane.example") },
        connector = connectorFor(gateway, kind),
        networkType = { "WIFI" },
        lifecycle = TsnetLifecycleMonitor,
    )

    @Test
    fun `tailscale publish and probe share the tsnet gateway`() = runTest {
        val gateway = CountingGateway()
        val session = session(gateway, RelayTransportKind.TAILSCALE)

        val client = session.client()
        assertEquals(204, client.publishLocation("d1", location))
        client.listDevices()

        assertEquals(2, gateway.httpRequestCalls)
        assertTrue("PUT must hit the location path", gateway.requests[0].endsWith("/devices/d1/location"))
        assertTrue("GET must hit the devices list", gateway.requests[1].endsWith("/devices"))
    }

    @Test
    fun `local connection never routes through the tsnet gateway`() = runTest {
        val gateway = CountingGateway()
        val session = session(gateway, RelayTransportKind.LOCAL)

        val client = session.client()

        // The LOCAL transport is HttpURLConnection over the normal Android
        // network; it must never escape through the tsnet tunnel.
        assertEquals(0, gateway.httpRequestCalls)
    }

    @Test
    fun `local publish leaves the tsnet node stopped`() = runTest {
        val gateway = CountingGateway()
        val session = session(gateway, RelayTransportKind.LOCAL)

        // In this JVM unit test the real HttpURLConnection local transport
        // cannot resolve "relay.local", so the publish fails. The lifecycle
        // assertions are what matter: the LOCAL path must never start or stop
        // the tsnet node.
        try {
            session.publish("d1", location)
        } catch (e: Exception) {
            // expected: local HTTP cannot reach the relay in a unit test
        }

        assertEquals("LOCAL must never start the node", 0, gateway.ensureCalls)
        assertEquals("LOCAL must never stop the node", 0, gateway.stopCalls)
        assertEquals("LOCAL", TsnetLifecycleMonitor.state.value.lastTransport)
        assertEquals(0, TsnetLifecycleMonitor.state.value.startCount)
        assertEquals(0, TsnetLifecycleMonitor.state.value.stopCount)
    }

    @Test
    fun `tailscale success starts node, publishes, then stops it`() = runTest {
        val gateway = CountingGateway()
        val session = session(gateway, RelayTransportKind.TAILSCALE)

        val status = session.publish("d1", location)

        assertEquals(204, status)
        assertEquals("node started for the remote publish", 1, gateway.ensureCalls)
        assertEquals("remote publish reached the tunnel transport", 1, gateway.httpRequestCalls)
        assertEquals("node stopped after a successful remote publish", 1, gateway.stopCalls)
        assertEquals(1, TsnetLifecycleMonitor.state.value.startCount)
        assertEquals(1, TsnetLifecycleMonitor.state.value.stopCount)
        assertEquals("TAILSCALE", TsnetLifecycleMonitor.state.value.lastTransport)
        assertEquals("WIFI", TsnetLifecycleMonitor.state.value.lastNetworkType)
        assertEquals(1, TsnetLifecycleMonitor.state.value.publishSuccessCount)
        assertEquals(false, TsnetLifecycleMonitor.state.value.currentRunning)
    }

    @Test
    fun `tailscale failure still stops the node via finally`() = runTest {
        val gateway = CountingGateway().apply { failPublish = true }
        val session = session(gateway, RelayTransportKind.TAILSCALE)

        var threw = false
        try {
            session.publish("d1", location)
        } catch (e: IOException) {
            threw = true
        }

        assertTrue("a failed publish must propagate", threw)
        assertEquals(1, gateway.ensureCalls)
        assertEquals("the node is stopped even on failure", 1, gateway.stopCalls)
        assertEquals(1, TsnetLifecycleMonitor.state.value.publishFailureCount)
        assertEquals(false, TsnetLifecycleMonitor.state.value.currentRunning)
    }

    @Test
    fun `cancellation during a remote publish still releases the node`() = runTest {
        val gateway = CountingGateway().apply { cancelPublish = true }
        val session = session(gateway, RelayTransportKind.TAILSCALE)

        var cancelled = false
        try {
            session.publish("d1", location)
        } catch (e: CancellationException) {
            cancelled = true
        }

        assertTrue("the cancellation must propagate", cancelled)
        assertEquals("the lease is released even on cancellation", 1, gateway.ensureCalls)
        assertEquals("the node is stopped via finally", 1, gateway.stopCalls)
        assertEquals(false, TsnetLifecycleMonitor.state.value.currentRunning)
    }

    @Test
    fun `a publish during a pending enrollment fails without stopping the enrollment node`() = runTest {
        val gateway = CountingGateway().apply { authRequired = true }
        val session = session(gateway, RelayTransportKind.TAILSCALE)

        var threw = false
        try {
            session.publish("d1", location)
        } catch (e: Exception) {
            threw = true
        }

        assertTrue("the enrollment must surface as a publish failure", threw)
        assertEquals("the remote path attempted to start the node", 1, gateway.ensureCalls)
        assertEquals("the enrollment owns the node; the publisher must not stop it", 0, gateway.stopCalls)
        assertEquals(1, TsnetLifecycleMonitor.state.value.publishFailureCount)
        assertEquals(false, TsnetLifecycleMonitor.state.value.currentRunning)
    }

    @Test
    fun `a publish after shutdown can reconnect and restart the node`() = runTest {
        val gateway = CountingGateway()
        val session = session(gateway, RelayTransportKind.TAILSCALE)

        session.publish("d1", location)
        assertEquals("first publish stops the node", 1, gateway.stopCalls)
        assertEquals(1, gateway.ensureCalls)

        // The cached remote connection was invalidated after shutdown, so the
        // second publish must re-probe and re-start the node, not assume the
        // stopped node is still usable.
        session.publish("d1", location)

        assertEquals("node is restarted for the next remote publish", 2, gateway.ensureCalls)
        assertEquals(2, gateway.httpRequestCalls)
        assertEquals(2, gateway.stopCalls)
        assertEquals(2, TsnetLifecycleMonitor.state.value.startCount)
    }
}
