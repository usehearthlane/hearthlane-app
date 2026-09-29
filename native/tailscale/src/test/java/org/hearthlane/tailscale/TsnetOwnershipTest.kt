package org.hearthlane.tailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Ownership accounting for the single embedded tsnet node, with the physical
 * start/stop bound atomically to the ownership transitions: the node is started
 * on the first consumer and stopped only when the last consumer releases it, so
 * a location publish can never tear down a node the foreground is still using.
 */
class TsnetOwnershipTest {

    private class Harness {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val nodeRunning = AtomicBoolean(false)
        var failStart = false

        fun ownership() = TsnetOwnership(
            start = { _, _, _ ->
                starts.incrementAndGet()
                if (failStart) throw IOException("start failed")
                nodeRunning.set(true)
            },
            stop = {
                stops.incrementAndGet()
                nodeRunning.set(false)
            },
        )
    }

    private val harness = Harness()

    @Before
    fun setUp() {
        harness.starts.set(0)
        harness.stops.set(0)
        harness.nodeRunning.set(false)
        harness.failStart = false
    }

    @Test
    fun `first consumer must start the node`() {
        val ownership = harness.ownership()
        assertTrue("the 0->1 transition must request a start", ownership.acquire("h", "a", "s"))
        assertEquals(1, ownership.activeUsers)
        assertEquals(1, harness.starts.get())
        assertTrue(harness.nodeRunning.get())
    }

    @Test
    fun `second consumer must not start the node`() {
        val ownership = harness.ownership()
        ownership.acquire("h", "a", "s")
        assertFalse("a second consumer reuses the running node", ownership.acquire("h", "a", "s"))
        assertEquals(2, ownership.activeUsers)
        assertEquals(1, harness.starts.get())
    }

    @Test
    fun `last release must stop the node`() {
        val ownership = harness.ownership()
        ownership.acquire("h", "a", "s")
        assertTrue("the 1->0 transition must request a stop", ownership.release())
        assertEquals(0, ownership.activeUsers)
        assertEquals(1, harness.stops.get())
        assertFalse(harness.nodeRunning.get())
    }

    @Test
    fun `a foreground holding the node survives a location release`() {
        val ownership = harness.ownership()
        assertTrue(ownership.acquire("h", "a", "s"))   // foreground consumer first
        assertFalse(ownership.acquire("h", "a", "s"))  // location publisher joins
        assertEquals(2, ownership.activeUsers)

        assertFalse("location releasing must not stop the node", ownership.release())
        assertEquals(1, ownership.activeUsers)
        assertEquals(0, harness.stops.get())
        assertTrue("the node stays up for the foreground", harness.nodeRunning.get())

        assertTrue("foreground releasing later stops the node", ownership.release())
        assertEquals(0, ownership.activeUsers)
        assertEquals(1, harness.stops.get())
    }

    @Test
    fun `last release racing a new acquire never stops the node under an active lease`() {
        val ownership = harness.ownership()
        ownership.acquire("h", "a", "s") // one consumer holds the node

        // The last consumer releases while a new consumer acquires. Whichever
        // interleaving wins, the atomic critical sections guarantee: no window
        // where a lease exists and the node is stopped.
        val releaseGate = CountDownLatch(1)
        val acquireGate = CountDownLatch(1)
        val releaseDone = CountDownLatch(1)
        val acquireDone = CountDownLatch(1)

        val releaser = thread {
            releaseGate.await()
            ownership.release()
            releaseDone.countDown()
        }
        val acquirer = thread {
            acquireGate.await()
            ownership.acquire("h", "a", "s")
            acquireDone.countDown()
        }
        releaseGate.countDown()
        acquireGate.countDown()
        releaseDone.await()
        acquireDone.await()
        releaser.join()
        acquirer.join()

        // Exactly one consumer remains after the race, and the node must be up:
        // "release then acquire" stops then restarts; "acquire then release"
        // never stops. A crossing start/stop would leave a lease with a stopped
        // node, which is precisely what the atomic transition prevents.
        assertEquals(1, ownership.activeUsers)
        assertTrue("a held lease must never leave the node stopped", harness.nodeRunning.get())
    }

    @Test
    fun `a throwing first start leaves no owner and allows a retry`() {
        harness.failStart = true
        val ownership = harness.ownership()

        assertThrows(IOException::class.java) { ownership.acquire("h", "a", "s") }
        assertEquals("a failed first start must leave no orphan owner", 0, ownership.activeUsers)

        harness.failStart = false
        assertTrue("another consumer can retry as first", ownership.acquire("h", "a", "s"))
        assertEquals(1, ownership.activeUsers)
        assertEquals(2, harness.starts.get())
        assertTrue(harness.nodeRunning.get())
    }

    @Test
    fun `multiple releases are idempotent and never negative`() {
        val ownership = harness.ownership()
        ownership.acquire("h", "a", "s")
        assertTrue(ownership.release())
        assertFalse("over-release is a no-op", ownership.release())
        assertFalse(ownership.release())
        assertEquals(0, ownership.activeUsers)
        assertEquals(1, harness.stops.get())
    }

    @Test
    fun `reset drops every consumer claim and forces the node down`() {
        val ownership = harness.ownership()
        ownership.acquire("h", "a", "s")
        ownership.acquire("h", "a", "s")
        ownership.reset()
        assertEquals(0, ownership.activeUsers)
        assertFalse(harness.nodeRunning.get())
        assertTrue("after reset the next acquire restarts the node", ownership.acquire("h", "a", "s"))
    }

    /**
     * Case D, modeled on the REAL native semantics: tsembed.Stop() cancels the
     * node context and then BLOCKS on `<-startDone` until the lifecycle
     * goroutine finishes `tsnet.Server.Close()` and clears the global server.
     * The ownership lock is therefore held for the entire teardown, so a
     * concurrent acquire cannot reach start() before teardown completes.
     *
     * Here the injected stop() blocks on a latch (mirroring the blocking
     * native stop) while a second thread acquires; the test proves the new
     * start() is only ever ordered after the teardown completion.
     */
    @Test
    fun `a new acquire cannot start while a previous teardown is still pending`() {
        val teardownDone = CountDownLatch(1)
        val teardownEntered = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val starts = AtomicInteger(0)
        val ownership = TsnetOwnership(
            start = { _, _, _ ->
                if (starts.incrementAndGet() > 1) events.add("start-after-teardown")
            },
            stop = {
                events.add("teardown-start")
                teardownEntered.countDown()
                teardownDone.await() // returns only once native cleanup completed
                events.add("teardown-complete")
            },
        )

        assertTrue(ownership.acquire("h", "a", "s")) // ownership = 1, node running

        // The last consumer releases: ownership -> 0 and stop() begins.
        val releaser = thread(name = "releaser") { ownership.release() }
        teardownEntered.await() // teardown is now in progress (stop holding the lock)

        // A new consumer tries to acquire while teardown is pending.
        val acquirer = thread(name = "acquirer") { ownership.acquire("h", "a", "s") }
        teardownDone.countDown()

        releaser.join()
        acquirer.join()

        assertEquals("the new consumer holds a lease", 1, ownership.activeUsers)
        assertEquals(2, starts.get())
        val snapshot = synchronized(events) { events.toList() }
        assertTrue(
            "a new start must never run before the previous teardown completes: $snapshot",
            snapshot.indexOf("start-after-teardown") > snapshot.indexOf("teardown-complete"),
        )
    }
}