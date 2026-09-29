package org.hearthlane.tailscale

import org.hearthlane.core.connectivity.ConnectivityState
import org.hearthlane.core.connectivity.ConnectivityStatus
import org.hearthlane.core.connectivity.TailscaleAuthRequired
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic (virtual-time) tests of the [TsnetGatewayImpl] concurrency
 * guarantees: ensureRunning returns only once the node is usable, a lease is
 * released on failure/cancellation, one instance holds at most one lease, and a
 * pending interactive enrollment owns the node through [TsnetEnrollmentSession]
 * until the backend reaches CONNECTED. The bridge seams are injected with a
 * fake [TsnetOwnership] and a controllable status/clock, so no native node and
 * no fragile sleeps are involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TsnetGatewayImplTest {

    private fun TestScope.gatewayWith(
        ownership: TsnetOwnership,
        nodeState: AtomicReference<ConnectivityState>,
        authUrl: AtomicReference<String?> = AtomicReference(null),
        enrollment: TsnetEnrollmentSession? = null,
        now: () -> Long,
    ): TsnetGatewayImpl {
        // Tests that do not exercise enrollment still get an inert session, so
        // the gateway never falls back to the process-global native bridge.
        val session = enrollment ?: TsnetEnrollmentSession(
            retainOwnership = { false },
            releaseOwnership = { false },
            readStatus = { ConnectivityStatus(nodeState.get(), authUrl.get()) },
            monitorScope = backgroundScope,
            elapsedRealtime = now,
        )
        return TsnetGatewayImpl("host", "/tmp/hearthlane-tsnet-test").apply {
            acquireOwnership = { h, a, s -> ownership.acquire(h, a, s) }
            releaseOwnership = { ownership.release() }
            readStatus = { ConnectivityStatus(nodeState.get(), authUrl.get()) }
            elapsedRealtime = now
            enrollmentSessionProvider = { session }
        }
    }

    /** Enrollment session wired to the same fake ownership, on the test scope. */
    private fun TestScope.enrollmentWith(
        ownership: TsnetOwnership,
        nodeState: AtomicReference<ConnectivityState>,
        authUrl: AtomicReference<String?>,
        now: () -> Long,
    ) = TsnetEnrollmentSession(
        retainOwnership = { ownership.retain() },
        releaseOwnership = { ownership.release() },
        readStatus = { ConnectivityStatus(nodeState.get(), authUrl.get()) },
        monitorScope = backgroundScope,
        pollIntervalMs = 500L,
        timeoutMs = 30 * 60_000L,
        elapsedRealtime = now,
    )

    @Test
    fun `ensureRunning returns only once the node is connected`() = runTest {
        val starts = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = {},
        )
        val nodeState = AtomicReference(ConnectivityState.CONNECTING)
        var now = 0L
        val gateway = gatewayWith(ownership, nodeState) { now }

        val returned = CompletableDeferred<Unit>()
        val job = launch {
            gateway.ensureRunning()
            returned.complete(Unit)
        }

        advanceTimeBy(5_000)
        runCurrent()
        assertFalse("a still-starting node must not satisfy ensureRunning", returned.isCompleted)

        nodeState.set(ConnectivityState.CONNECTED)
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue("ensureRunning returns only once the node is usable", returned.isCompleted)
        job.join()
        assertEquals("the consumer keeps its lease while using the node", 1, ownership.activeUsers)
        assertEquals(1, starts.get())
    }

    @Test
    fun `stopIfRunning releases the shared lease and stops the node when last`() = runTest {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.CONNECTED)
        var now = 0L
        val gateway = gatewayWith(ownership, nodeState) { now }

        gateway.ensureRunning()
        assertEquals(1, ownership.activeUsers)

        // The foreground leaves (app/process backgrounded): with no other
        // consumer, releasing the lease stops the node.
        gateway.stopIfRunning()
        assertEquals(0, ownership.activeUsers)
        assertEquals(1, stops.get())
    }

    @Test
    fun `a second ensureRunning on the same instance does not double-acquire`() = runTest {
        val starts = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = {},
        )
        val nodeState = AtomicReference(ConnectivityState.CONNECTED)
        var now = 0L
        val gateway = gatewayWith(ownership, nodeState) { now }

        gateway.ensureRunning()
        gateway.ensureRunning()

        assertEquals("one lease per instance", 1, ownership.activeUsers)
        assertEquals(1, starts.get())
    }

    @Test
    fun `a failed start reverts the lease so another consumer can retry`() = runTest {
        val starts = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = {},
        )
        val nodeState = AtomicReference(ConnectivityState.FAILED)
        var now = 0L
        val gateway = gatewayWith(ownership, nodeState) { now }

        var threw = false
        try {
            gateway.ensureRunning()
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("a failed connect must propagate", threw)
        assertEquals("a failed connect must leave no owner", 0, ownership.activeUsers)

        val healthyNode = AtomicReference(ConnectivityState.CONNECTED)
        val retry = gatewayWith(ownership, healthyNode) { now }
        retry.ensureRunning()
        assertEquals("another consumer can start the node again", 1, ownership.activeUsers)
    }

    @Test
    fun `a cancelled connect reverts the lease`() = runTest {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.CONNECTING)
        var now = 0L
        val gateway = gatewayWith(ownership, nodeState) { now }

        val job = launch { gateway.ensureRunning() }
        advanceTimeBy(1_000)
        runCurrent()
        job.cancel()
        job.join()

        assertEquals("a cancelled connect must release its lease", 0, ownership.activeUsers)
        assertEquals(1, stops.get())
    }

    /**
     * Case A: a second consumer joins while the first consumer's node is still
     * STARTING. It must NOT return from ensureRunning before the node is
     * usable — every consumer receives the same wait-for-connected guarantee,
     * regardless of who physically started the node.
     */
    @Test
    fun `a joining consumer during startup does not return before the node is usable`() = runTest {
        val starts = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = {},
        )
        val nodeState = AtomicReference(ConnectivityState.CONNECTING)
        var now = 0L
        val consumerA = gatewayWith(ownership, nodeState) { now }
        val consumerB = gatewayWith(ownership, nodeState) { now }

        val aReturned = CompletableDeferred<Unit>()
        val aJob = launch {
            consumerA.ensureRunning()
            aReturned.complete(Unit)
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("A physically starts the node (0->1)", 1, ownership.activeUsers)

        // B joins while the node is still CONNECTING.
        val bReturned = CompletableDeferred<Unit>()
        val bJob = launch {
            consumerB.ensureRunning()
            bReturned.complete(Unit)
        }
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse("B must not return while the node is still starting", bReturned.isCompleted)
        assertFalse("A is also still waiting for CONNECTED", aReturned.isCompleted)

        nodeState.set(ConnectivityState.CONNECTED)
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue("A returns once the node is usable", aReturned.isCompleted)
        assertTrue("B returns once the node is usable", bReturned.isCompleted)
        aJob.join()
        bJob.join()
        assertEquals(2, ownership.activeUsers)
        assertEquals(1, starts.get())
    }

    /**
     * Required scenario 2/3: the foreground gateway holds the node, a location
     * publish acquires and releases without tearing it down, and only the
     * foreground's background (last release) stops the node.
     */
    @Test
    fun `foreground survives a location publish and stops only when it backgrounds`() = runTest {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.CONNECTED)
        var now = 0L
        val foreground = gatewayWith(ownership, nodeState) { now }
        val location = gatewayWith(ownership, nodeState) { now }

        foreground.ensureRunning() // foreground acquires: node up, lease 1
        assertEquals(1, ownership.activeUsers)

        location.ensureRunning() // location joins for a remote publish: lease 2
        assertEquals(2, ownership.activeUsers)

        location.stopIfRunning() // location publish ends: lease 1, node stays up
        assertEquals(1, ownership.activeUsers)
        assertEquals("location releasing must not stop the foreground's node", 0, stops.get())

        foreground.stopIfRunning() // foreground goes to background: last lease released
        assertEquals(0, ownership.activeUsers)
        assertEquals("the last release stops the node", 1, stops.get())
    }

    /**
     * Case C / required scenario 6: a SYNCHRONOUSLY throwing first start (the
     * 0->1 acquire itself fails) must leave no orphan lease, so another
     * consumer can retry. The status-based failure path is covered by
     * [a failed start reverts the lease].
     */
    @Test
    fun `a synchronously throwing start leaves no orphan lease and allows a retry`() = runTest {
        val starts = AtomicInteger(0)
        var failStart = true
        val ownership = TsnetOwnership(
            start = { _, _, _ ->
                if (failStart) throw IOException("start failed")
                starts.incrementAndGet()
            },
            stop = {},
        )
        val nodeState = AtomicReference(ConnectivityState.CONNECTED)
        var now = 0L

        val first = gatewayWith(ownership, nodeState) { now }
        var threw = false
        try {
            first.ensureRunning()
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("a throwing start must propagate", threw)
        assertEquals("no orphan owner after a throwing start", 0, ownership.activeUsers)

        failStart = false
        val retry = gatewayWith(ownership, nodeState) { now }
        retry.ensureRunning()
        assertEquals("another consumer can start the node again", 1, ownership.activeUsers)
        assertEquals(1, starts.get())
    }

    /**
     * Required scenario 8: repeated stopIfRunning is idempotent and the shared
     * counter never goes negative.
     */
    @Test
    fun `repeated stopIfRunning is idempotent and never negative`() = runTest {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.CONNECTED)
        var now = 0L
        val gateway = gatewayWith(ownership, nodeState) { now }

        gateway.ensureRunning()
        assertEquals(1, ownership.activeUsers)

        gateway.stopIfRunning()
        gateway.stopIfRunning()
        gateway.stopIfRunning()

        assertEquals("the shared counter is never negative", 0, ownership.activeUsers)
        assertEquals("only one physical stop for one acquired lease", 1, stops.get())
    }

    /**
     * Mandatory scenario 1: a pending interactive enrollment (AUTHENTICATING
     * with an AuthURL) must NOT physically stop the node that produced the URL.
     * The consumer releases its own lease, but the enrollment session keeps its
     * own ownership, so the node survives and the URL stays valid.
     */
    @Test
    fun `a pending enrollment keeps the node alive after the consumer releases`() = runTest {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.AUTHENTICATING)
        val authUrl = AtomicReference<String?>("https://login.tailscale.com/a/abc123")
        var now = 0L
        val enrollment = enrollmentWith(ownership, nodeState, authUrl) { now }
        val gateway = gatewayWith(ownership, nodeState, authUrl, enrollment) { now }

        var threw: TailscaleAuthRequired? = null
        try {
            gateway.ensureRunning()
        } catch (e: TailscaleAuthRequired) {
            threw = e
        }

        assertEquals("the AuthURL must be surfaced", "https://login.tailscale.com/a/abc123", threw?.authUrl)
        assertEquals("the consumer lease was released", 1, ownership.activeUsers)
        assertEquals("the enrollment session now owns the node", true, enrollment.isActive)
        assertEquals("no physical stop while enrollment is pending", 0, stops.get())
        assertEquals("the node was started exactly once", 1, starts.get())

        enrollment.complete()
        ownership.release()
        assertEquals("cleanup: the node stops only after the last lease", 1, stops.get())
    }

    /**
     * Mandatory scenario 2: once the backend reaches CONNECTED, a retry reuses
     * the SAME physical node (no new physical start) and the enrollment lease
     * is released exactly once, returning to the strict on-demand lifecycle.
     */
    @Test
    fun `a retry after authorization reuses the node and completes the enrollment`() = runTest {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.AUTHENTICATING)
        val authUrl = AtomicReference<String?>("https://login.tailscale.com/a/abc123")
        var now = 0L
        val enrollment = enrollmentWith(ownership, nodeState, authUrl) { now }
        val gateway = gatewayWith(ownership, nodeState, authUrl, enrollment) { now }

        try {
            gateway.ensureRunning()
        } catch (e: TailscaleAuthRequired) {
            // expected: enrollment pending
        }
        assertEquals("the enrollment holds the node after the first attempt", 1, ownership.activeUsers)

        nodeState.set(ConnectivityState.CONNECTED)
        gateway.ensureRunning()

        assertEquals("the retry must reuse the same node", 1, starts.get())
        assertEquals("the enrollment lease is released on CONNECTED", 1, ownership.activeUsers)
        assertEquals("no stop while the consumer is still using the node", 0, stops.get())
        assertEquals("the enrollment session is complete", false, enrollment.isActive)

        gateway.stopIfRunning()
        assertEquals("the last release stops the node", 1, stops.get())
    }

    /**
     * Mandatory scenario 3: ON_STOP (the user opened the browser) calls
     * stopIfRunning on the foreground. The foreground releases only its own
     * lease; the enrollment session keeps the node alive.
     */
    @Test
    fun `a foreground stop during enrollment releases only its own lease`() = runTest {
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> Unit },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.AUTHENTICATING)
        val authUrl = AtomicReference<String?>("https://login.tailscale.com/a/abc123")
        var now = 0L
        val enrollment = enrollmentWith(ownership, nodeState, authUrl) { now }
        val gateway = gatewayWith(ownership, nodeState, authUrl, enrollment) { now }

        try {
            gateway.ensureRunning()
        } catch (e: TailscaleAuthRequired) {
            // expected: the probe surfaced the URL
        }
        assertEquals("the enrollment owns the node", 1, ownership.activeUsers)

        // ON_STOP: ForegroundTsnetLifecycle releases the foreground lease.
        gateway.stopIfRunning()

        assertEquals("the node stays alive for the enrollment", 1, ownership.activeUsers)
        assertEquals("no physical stop while enrollment is pending", 0, stops.get())
        assertEquals("the enrollment session survives the background transition", true, enrollment.isActive)

        enrollment.complete()
        assertEquals("the enrollment release finally stops the node", 1, stops.get())
    }

    /**
     * Mandatory scenario 4: a location publish during a pending enrollment
     * acquires and releases only its own lease; the enrollment lease prevents
     * any physical stop.
     */
    @Test
    fun `a location consumer during enrollment acquires and releases only its own lease`() = runTest {
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> Unit },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.AUTHENTICATING)
        val authUrl = AtomicReference<String?>("https://login.tailscale.com/a/abc123")
        var now = 0L
        val enrollment = enrollmentWith(ownership, nodeState, authUrl) { now }
        val foreground = gatewayWith(ownership, nodeState, authUrl, enrollment) { now }
        val location = gatewayWith(ownership, nodeState, authUrl, enrollment) { now }

        try {
            foreground.ensureRunning()
        } catch (e: TailscaleAuthRequired) {
            // expected: enrollment surfaced
        }
        assertEquals("the enrollment owns the node", 1, ownership.activeUsers)

        var locationThrew = false
        try {
            location.ensureRunning()
        } catch (e: TailscaleAuthRequired) {
            locationThrew = true
        }
        assertTrue("the location probe sees the pending enrollment", locationThrew)
        assertEquals("the location released only its own lease", 1, ownership.activeUsers)
        assertEquals("no stop while the enrollment is pending", 0, stops.get())
        assertEquals("the enrollment survives the location attempt", true, enrollment.isActive)

        // RelayPublishSession's finally: another release, still no stop.
        location.stopIfRunning()
        assertEquals(1, ownership.activeUsers)
        assertEquals(0, stops.get())

        enrollment.complete()
        assertEquals("the last lease stops the node", 1, stops.get())
    }

    /**
     * Mandatory scenario 5: after CONNECTED the enrollment is released; with no
     * other consumer the next release performs the physical stop.
     */
    @Test
    fun `connected enrollment releases its lease and the last release stops`() = runTest {
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> Unit },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.AUTHENTICATING)
        val authUrl = AtomicReference<String?>("https://login.tailscale.com/a/abc123")
        var now = 0L
        val enrollment = enrollmentWith(ownership, nodeState, authUrl) { now }
        val gateway = gatewayWith(ownership, nodeState, authUrl, enrollment) { now }

        try {
            gateway.ensureRunning()
        } catch (e: TailscaleAuthRequired) {
            // expected
        }
        assertEquals(1, ownership.activeUsers)

        nodeState.set(ConnectivityState.CONNECTED)
        gateway.ensureRunning()
        assertEquals("the enrollment lease is gone", 1, ownership.activeUsers)
        assertEquals(false, enrollment.isActive)

        gateway.stopIfRunning()
        assertEquals("the last release stops the node", 1, stops.get())
    }

    /**
     * Mandatory scenario 7: an identity reset during a pending enrollment
     * clears every claim (enrollment included) and physically stops the node;
     * a new enrollment can start normally afterwards.
     */
    @Test
    fun `reset during enrollment clears every claim and allows a new enrollment`() = runTest {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> starts.incrementAndGet() },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.AUTHENTICATING)
        val authUrl = AtomicReference<String?>("https://login.tailscale.com/a/abc123")
        var now = 0L
        val enrollment = enrollmentWith(ownership, nodeState, authUrl) { now }
        val gateway = gatewayWith(ownership, nodeState, authUrl, enrollment) { now }
        gateway.resetOwnership = {
            enrollment.discard()
            ownership.reset()
        }

        try {
            gateway.ensureRunning()
        } catch (e: TailscaleAuthRequired) {
            // expected: enrollment pending
        }
        assertEquals(1, ownership.activeUsers)
        assertEquals(true, enrollment.isActive)

        gateway.reset()

        assertEquals("every claim is dropped", 0, ownership.activeUsers)
        assertEquals("the node is physically stopped", 1, stops.get())
        assertEquals("the enrollment session is cleared", false, enrollment.isActive)

        nodeState.set(ConnectivityState.CONNECTED)
        gateway.ensureRunning()
        assertEquals("a new enrollment can start normally", 1, ownership.activeUsers)
        assertEquals(2, starts.get())
    }

    /**
     * Mandatory scenario 8: repetition and cancellation never double-release
     * and never leave an orphan lease while an enrollment is pending.
     */
    @Test
    fun `repeated failures and cancellations during enrollment never orphan or double-release`() = runTest {
        val stops = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ -> Unit },
            stop = { stops.incrementAndGet() },
        )
        val nodeState = AtomicReference(ConnectivityState.AUTHENTICATING)
        val authUrl = AtomicReference<String?>("https://login.tailscale.com/a/abc123")
        var now = 0L
        val enrollment = enrollmentWith(ownership, nodeState, authUrl) { now }
        val gateway = gatewayWith(ownership, nodeState, authUrl, enrollment) { now }

        repeat(3) {
            try {
                gateway.ensureRunning()
            } catch (e: TailscaleAuthRequired) {
                // expected: each attempt surfaces the pending enrollment
            }
        }
        assertEquals("one enrollment lease survives the repetition", 1, ownership.activeUsers)
        assertEquals("never a stop while the enrollment owns the node", 0, stops.get())
        assertEquals(true, enrollment.isActive)

        gateway.stopIfRunning()
        gateway.stopIfRunning()
        assertEquals("idempotent releases keep the enrollment lease", 1, ownership.activeUsers)
        assertEquals(0, stops.get())

        enrollment.complete()
        enrollment.complete()
        assertEquals("the enrollment release is exact once", 0, ownership.activeUsers)
        assertEquals("the final release stops the node once", 1, stops.get())
    }
}