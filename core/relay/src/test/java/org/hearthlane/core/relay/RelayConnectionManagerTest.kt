package org.hearthlane.core.relay

import org.hearthlane.core.connectivity.HttpBytesResult
import org.hearthlane.core.connectivity.HttpStream
import org.hearthlane.core.connectivity.TailscaleAuthRequired
import org.hearthlane.core.connectivity.TsnetGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayConnectionManagerTest {

    /** Records requests and replays the configured response for GET /devices. */
    private class FakeHttp(
        var response: HttpBytesResult,
        var failWith: Throwable? = null,
    ) : RelayHttpTransport {
        override suspend fun request(
            method: String,
            url: String,
            contentType: String?,
            body: String?,
            headers: Map<String, String>,
            timeoutMs: Long,
        ): HttpBytesResult {
            failWith?.let { throw it }
            assertEquals("GET", method)
            assertTrue(url.endsWith("/devices"))
            return response
        }
    }

    private class FakeTsnetGateway : TsnetGateway {
        var starts = 0
        var stops = 0
        var authUrl: String? = null

        override suspend fun ensureRunning() {
            starts++
            if (authUrl != null) throw TailscaleAuthRequired(authUrl)
        }

        override suspend fun stopIfRunning() {
            stops++
        }

        override suspend fun reset() = Unit
        override suspend fun httpGet(url: String, timeoutMs: Long): String = "{}"
        override suspend fun httpGetBytes(url: String, timeoutMs: Long) =
            HttpBytesResult(200, "application/json", url, ByteArray(0))
        override suspend fun httpOpenStream(url: String, connectTimeoutMs: Long, headers: Map<String, String>): HttpStream =
            throw UnsupportedOperationException()
    }

    private fun config() = RelayConfig(
        localBaseUrl = "http://192.168.1.10:8000",
        tailscaleBaseUrl = "http://relay.tailnet.ts.net",
    )

    private fun manager(
        config: RelayConfig,
        local: RelayHttpTransport,
        tailscale: RelayHttpTransport,
        gateway: TsnetGateway,
    ) = RelayConnectionManager(
        config = config,
        localTransport = LocalRelayTransport(config, local),
        tailscaleTransport = TailscaleRelayTransport(gateway, config, tailscale),
        tailscaleGateway = gateway,
    )

    @Test
    fun localSuccess_reportsLocalAndStopsNodeIfRunning() = runTest {
        val local = FakeHttp(HttpBytesResult(200, "application/json", "http://x", ByteArray(0)))
        val tailscale = FakeHttp(HttpBytesResult(200, "application/json", "http://x", ByteArray(0)))
        val gateway = FakeTsnetGateway().also { it.starts = 1 } // left running by a previous remote attempt
        val manager = manager(config(), local, tailscale, gateway)

        val result = manager.connect()

        assertTrue(result is RelayConnection.Connected)
        assertEquals(RelayTransportKind.LOCAL, (result as RelayConnection.Connected).transport)
        assertEquals(1, gateway.stops)
    }

    @Test
    fun localFailure_fallsBackToTailscale() = runTest {
        val local = FakeHttp(
            HttpBytesResult(200, "application/json", "http://x", ByteArray(0)),
            failWith = java.net.ConnectException("no relay on LAN"),
        )
        val tailscale = FakeHttp(HttpBytesResult(200, "application/json", "http://x", ByteArray(0)))
        val gateway = FakeTsnetGateway()
        val manager = manager(config(), local, tailscale, gateway)

        val result = manager.connect()

        assertTrue(result is RelayConnection.Connected)
        assertEquals(RelayTransportKind.TAILSCALE, (result as RelayConnection.Connected).transport)
        assertEquals(1, gateway.starts)
    }

    @Test
    fun bothFail_reportsFailedWithAuthWhenEnrollmentPending() = runTest {
        val local = FakeHttp(
            HttpBytesResult(200, "application/json", "http://x", ByteArray(0)),
            failWith = java.net.ConnectException("timeout"),
        )
        val tailscale = FakeHttp(HttpBytesResult(200, "application/json", "http://x", ByteArray(0)))
        val gateway = FakeTsnetGateway().also { it.authUrl = "https://login.tailscale.com/a/xyz" }
        val manager = manager(config(), local, tailscale, gateway)

        val result = manager.connect()

        assertTrue(result is RelayConnection.Failed)
        val failed = result as RelayConnection.Failed
        assertTrue(failed.authRequired)
        assertEquals("https://login.tailscale.com/a/xyz", failed.authUrl)
    }

    @Test
    fun bothFail_reportsFailedOnRemoteHttpError() = runTest {
        val local = FakeHttp(
            HttpBytesResult(200, "application/json", "http://x", ByteArray(0)),
            failWith = java.net.ConnectException("timeout"),
        )
        val tailscale = FakeHttp(HttpBytesResult(401, "application/json", "http://x", ByteArray(0)))
        val manager = manager(config(), local, tailscale, FakeTsnetGateway())

        val result = manager.connect()

        assertTrue(result is RelayConnection.Failed)
        assertTrue((result as RelayConnection.Failed).error.contains("401"))
    }

    @Test
    fun tailscaleTransport_failureSurfacesAsAuthRequiredResult() = runTest {
        val local = FakeHttp(
            HttpBytesResult(200, "application/json", "http://x", ByteArray(0)),
            failWith = java.net.ConnectException("timeout"),
        )
        val gateway = FakeTsnetGateway().also { it.authUrl = "https://login.tailscale.com/a/xyz" }
        val tailscale = TailscaleRelayTransport(
            gateway = gateway,
            config = config(),
            http = FakeHttp(HttpBytesResult(200, "application/json", "http://x", ByteArray(0))),
        )

        val result = tailscale.probe()

        assertTrue(result is RelayTransportResult.Failure)
        assertTrue((result as RelayTransportResult.Failure).authRequired)
    }
}