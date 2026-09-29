package org.hearthlane.tailscale

import org.hearthlane.core.connectivity.ConnectivityState
import org.hearthlane.core.connectivity.ConnectivityStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic (virtual-time) tests of [TsnetEnrollmentSession]: the
 * enrollment owns its own lease in [TsnetOwnership] while the backend waits
 * for authorization, never starts a node on its own, and releases that lease
 * exactly once on CONNECTED / terminal state / defensive timeout / explicit
 * cleanup. The ownership seams are injected with a fake [TsnetOwnership] and a
 * controllable status/clock, so no native node and no fragile sleeps are
 * involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TsnetEnrollmentSessionTest {

    private class Harness {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val nodeState = AtomicReference(ConnectivityState.AUTHENTICATING)
        var authUrl: String? = "https://login.tailscale.com/a/abc123"
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = { stops.incrementAndGet() },
        )
        fun status() = ConnectivityStatus(nodeState.get(), authUrl)
    }

    private fun session(
        harness: Harness,
        scope: CoroutineScope,
        pollIntervalMs: Long = 500L,
        timeoutMs: Long = 30 * 60_000L,
        now: () -> Long,
    ) = TsnetEnrollmentSession(
        retainOwnership = { harness.ownership.retain() },
        releaseOwnership = { harness.ownership.release() },
        readStatus = { harness.status() },
        monitorScope = scope,
        pollIntervalMs = pollIntervalMs,
        timeoutMs = timeoutMs,
        elapsedRealtime = now,
    )

    @Test
    fun `begin never starts a node and only retains an already-owned one`() = runTest {
        val harness = Harness()
        var now = 0L
        val session = session(harness, backgroundScope) { now }

        assertFalse("no physical owner means nothing to protect", session.begin("https://u"))
        assertEquals("a failed retain must not claim ownership", 0, harness.ownership.activeUsers)
        assertEquals("the session must never start the node", 0, harness.starts.get())
        assertFalse(session.isActive)

        harness.ownership.acquire("h", "a", "s") // a consumer owns the node: 0->1
        assertTrue("an owned node can be retained", session.begin("https://u"))
        assertEquals("enrollment is a separate owner", 2, harness.ownership.activeUsers)
        assertEquals("retaining never starts the node again", 1, harness.starts.get())
        assertTrue(session.isActive)
        assertEquals("https://u", session.pendingAuthUrl)

        session.complete()
        assertEquals("complete releases only the enrollment lease", 1, harness.ownership.activeUsers)
        assertNull("the URL is dropped with the session", session.pendingAuthUrl)
        assertEquals("no physical stop while the consumer still owns the node", 0, harness.stops.get())
    }

    @Test
    fun `monitor completes the enrollment when the backend reaches CONNECTED`() = runTest {
        val harness = Harness()
        var now = 0L
        val session = session(harness, backgroundScope) { now }
        harness.ownership.acquire("h", "a", "s")
        assertTrue(session.begin("https://u"))
        assertEquals(2, harness.ownership.activeUsers)

        harness.nodeState.set(ConnectivityState.CONNECTED)
        runCurrent()

        assertFalse("the enrollment is done", session.isActive)
        assertEquals("the enrollment lease is released", 1, harness.ownership.activeUsers)
        assertEquals(0, harness.stops.get())

        harness.ownership.release()
        assertEquals("the last release stops the node", 1, harness.stops.get())
    }

    @Test
    fun `monitor releases the enrollment lease on a terminal node failure`() = runTest {
        val harness = Harness()
        var now = 0L
        val session = session(harness, backgroundScope) { now }
        harness.ownership.acquire("h", "a", "s")
        assertTrue(session.begin("https://u"))

        harness.nodeState.set(ConnectivityState.FAILED)
        runCurrent()

        assertFalse(session.isActive)
        assertEquals("a dead node must not keep an enrollment lease", 1, harness.ownership.activeUsers)
        assertEquals(0, harness.stops.get())
    }

    @Test
    fun `defensive timeout bounds an abandoned enrollment without stopping an owned node`() = runTest {
        val harness = Harness()
        var now = 0L
        val session = session(harness, backgroundScope, pollIntervalMs = 500L, timeoutMs = 1_000L) { now }
        harness.ownership.acquire("h", "a", "s")
        assertTrue(session.begin("https://u"))
        assertEquals(2, harness.ownership.activeUsers)

        // The monitor starts with the deadline anchored at t=0...
        runCurrent()
        assertTrue("the session is still active before the timeout", session.isActive)

        // ...then the abandoned session expires.
        now = 2_000L
        advanceTimeBy(500)
        runCurrent()

        assertFalse("an abandoned enrollment must not own the node forever", session.isActive)
        assertEquals("the owner's lease survives the timeout", 1, harness.ownership.activeUsers)
        assertEquals(0, harness.stops.get())
    }

    @Test
    fun `begin is idempotent while active`() = runTest {
        val harness = Harness()
        var now = 0L
        val session = session(harness, backgroundScope) { now }
        harness.ownership.acquire("h", "a", "s")

        assertTrue(session.begin("https://first"))
        assertTrue("a repeated begin refreshes nothing", session.begin("https://second"))
        assertEquals("only one enrollment lease is ever retained", 2, harness.ownership.activeUsers)
        assertEquals("https://second", session.pendingAuthUrl)

        session.complete()
        assertEquals(1, harness.ownership.activeUsers)
    }

    @Test
    fun `complete is idempotent and never double-releases`() = runTest {
        val harness = Harness()
        var now = 0L
        val session = session(harness, backgroundScope) { now }
        harness.ownership.acquire("h", "a", "s")
        assertTrue(session.begin("https://u"))

        session.complete()
        session.complete()
        session.complete()

        assertEquals("exactly one lease was released", 1, harness.ownership.activeUsers)
        assertEquals(0, harness.stops.get())
        harness.ownership.release()
        assertEquals("the last release stops the node once", 1, harness.stops.get())
    }

    @Test
    fun `discard drops the claim without releasing for the identity reset`() = runTest {
        val harness = Harness()
        var now = 0L
        val session = session(harness, backgroundScope) { now }
        harness.ownership.acquire("h", "a", "s")
        assertTrue(session.begin("https://u"))
        assertEquals(2, harness.ownership.activeUsers)

        session.discard()
        assertFalse(session.isActive)
        assertEquals("discard must not release; the reset zeroes everything itself", 2, harness.ownership.activeUsers)
        assertEquals(0, harness.stops.get())

        harness.ownership.reset()
        assertEquals(0, harness.ownership.activeUsers)
        assertEquals("the reset stops the node exactly once", 1, harness.stops.get())
    }

    /**
     * Mandatory scenario 6: the ownership accounting with foreground +
     * enrollment + location follows
     *   foreground 1 -> enrollment 2 -> location 3 -> location release 2 ->
     *   foreground release 1 -> enrollment release 0 -> physical stop.
     */
    @Test
    fun `foreground enrollment and location follow one shared ownership accounting`() = runTest {
        val harness = Harness()
        var now = 0L
        val session = session(harness, backgroundScope) { now }

        harness.ownership.acquire("h", "a", "s") // foreground consumer
        assertEquals(1, harness.ownership.activeUsers)

        assertTrue(session.begin("https://u")) // enrollment session
        assertEquals(2, harness.ownership.activeUsers)

        assertFalse(harness.ownership.acquire("h", "a", "s")) // location joins
        assertEquals(3, harness.ownership.activeUsers)

        assertFalse(harness.ownership.release()) // location publish ends
        assertEquals(2, harness.ownership.activeUsers)
        assertEquals("no stop while other leases are held", 0, harness.stops.get())

        assertFalse(harness.ownership.release()) // foreground backgrounds
        assertEquals(1, harness.ownership.activeUsers)
        assertEquals(0, harness.stops.get())

        session.complete() // enrollment release
        session.complete() // idempotent: never double-releases
        assertEquals("enrollment release completes the ownership", 0, harness.ownership.activeUsers)
        assertEquals("the last lease stops the node", 1, harness.stops.get())
    }
}