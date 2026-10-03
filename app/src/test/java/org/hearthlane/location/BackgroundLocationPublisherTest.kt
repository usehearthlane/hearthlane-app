package org.hearthlane.location

import org.hearthlane.core.relay.DeviceLocation
import org.hearthlane.test.FakeRelayClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tests for the Phase 3 V2 runner: fixes arrive through [submitFix] from the
 * acquisition controller, the machine Tick is a pure time scheduler (never
 * reads a location), and every command (including PUBLISH_NOW) is serialized
 * through one channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundLocationPublisherTest {

    private fun sample(lat: Double, recordedAt: Long) = LocationReadResult(
        status = LocationReadStatus.SUCCESS,
        sample = LocationSample(
            provider = "network",
            latitude = lat,
            longitude = 0.0,
            accuracyMeters = 20f,
            recordedAtWallClockMs = recordedAt,
            recordedAtElapsedNanos = 0L,
            ageMs = 0L,
            acquisitionMs = 0L,
            fromLastKnown = false,
        ),
    )

    private fun sampleOf(lat: Double, recordedAt: Long, accuracy: Float = 20f, ageMs: Long = 0L) =
        LocationSample(
            provider = "network",
            latitude = lat,
            longitude = 0.0,
            accuracyMeters = accuracy,
            recordedAtWallClockMs = recordedAt,
            recordedAtElapsedNanos = 0L,
            ageMs = ageMs,
            acquisitionMs = 0L,
            fromLastKnown = false,
        )

    /** Deterministic distance: 1 degree of latitude ~ 111 km. */
    private fun distanceMeters(a: PositionFix, b: PositionFix): Double =
        abs(a.latitude - b.latitude) * 111_320.0

    private class Clock(var ms: Long = 0L)

    private fun TestScope.publisher(
        relay: FakeRelayClient,
        clock: Clock = Clock(),
        onPublishFailure: (() -> Unit)? = null,
        events: MutableList<LocationEvent>? = null,
        mode: PublisherMode = PublisherMode.BACKGROUND,
        tickIntervalMs: Long = 60_000L,
        onTickProcessed: (() -> Unit)? = null,
    ) = BackgroundLocationPublisher(
        publish = { id, loc -> relay.publishLocation(id, loc) },
        deviceId = { "d1" },
        scope = backgroundScope,
        onPublishFailure = onPublishFailure,
        distanceMeters = ::distanceMeters,
        clockMs = { clock.ms },
        emitEvent = { if (events != null) events += it },
        mode = mode,
        tickIntervalMs = { tickIntervalMs },
        onTickProcessed = onTickProcessed,
    )

    /** Advances the virtual scheduler and mirrors the machine clock. */
    private fun TestScope.tick(clock: Clock, timeMs: Long, stepMs: Long = 1_000L) {
        var remaining = timeMs
        while (remaining > 0L) {
            val step = min(stepMs, remaining)
            advanceTimeBy(step)
            clock.ms = testScheduler.currentTime
            runCurrent()
            remaining -= step
        }
        runCurrent()
    }

    // ----------------------------------------------------------- PUBLISH

    @Test
    fun `a listener fix triggers the first publish`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()

        assertEquals(1, pub.state.value.publishCount)
        assertEquals(1.0, relay.getLocation("d1")!!.latitude, 0.0)
        assertTrue(pub.state.value.running)
    }

    @Test
    fun `listener movement triggers movement publishes at the healthy cadence`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        assertEquals(1, pub.state.value.publishCount)

        // Movement 30 s after the success is allowed (healthy 30 s cadence).
        clock.ms = 30_000L
        pub.submitFix(sample(2.0, 200L).sample!!)
        runCurrent()
        assertEquals(2, pub.state.value.publishCount)
        assertEquals(2.0, relay.getLocation("d1")!!.latitude, 0.0)

        // Movement 10 s later is throttled by MIN_PUBLISH_INTERVAL.
        clock.ms = 40_000L
        pub.submitFix(sample(3.0, 300L).sample!!)
        runCurrent()
        assertEquals("throttled within 30 s of the last success", 2, pub.state.value.publishCount)
    }

    @Test
    fun `gps refinement can trigger a material accuracy upgrade publish`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sampleOf(1.0, 100L, accuracy = 200f))
        runCurrent()
        assertEquals(1, pub.state.value.publishCount)

        clock.ms = 60_000L
        pub.submitFix(sampleOf(1.0, 200L, accuracy = 20f, ageMs = 5_000L))
        runCurrent()

        assertEquals(2, pub.state.value.publishCount)
        assertEquals(
            PublishDecisionReason.MATERIAL_ACCURACY_UPGRADE.name,
            pub.state.value.lastPublishDecision,
        )
    }

    @Test
    fun `published location carries the contract accuracy field`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sample(-23.5505, 1_700_000_000_000L).sample!!)
        runCurrent()

        val stored = relay.getLocation("d1")!!
        assertEquals(-23.5505, stored.latitude, 0.0)
        assertEquals(20f, stored.accuracy, 0f)
        assertEquals(1_700_000_000_000L, stored.recordedAtEpochMs)
        assertTrue(stored.publishedAtEpochMs != null)
    }

    @Test
    fun `success clears pending and records success metadata`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()

        val state = pub.state.value
        assertEquals("Success", state.lastPublishResult)
        assertTrue(state.lastPublishAtMs != null)
        assertTrue(state.lastPublishAttemptAtMs != null)
        assertFalse("a success clears the pending flag", state.hasPendingLocation)
        assertNull(state.lastError)
    }

    @Test
    fun `fix metadata is mirrored into the publisher state`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sampleOf(1.0, 100L, accuracy = 15f, ageMs = 42L))
        runCurrent()

        val state = pub.state.value
        assertEquals("network", state.lastFixProvider)
        assertEquals(15f, state.lastFixAccuracyMeters)
        assertEquals(42L, state.lastFixAgeMs)
        assertEquals(PublishDecisionReason.FIRST.name, state.lastPublishDecision)
    }

    // ------------------------------------------------------------- TICK

    @Test
    fun `machine tick never performs a location read`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(relay, clock = clock, events = events)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        events.clear()

        // Ten minutes of ticks with no new fixes: no fix-received event can
        // ever be produced by the tick (the tick has no acquisition path).
        tick(clock, 10 * 60_000L)
        assertTrue("ticks never read a location", events.none { it.name == LocationEventLog.EVENT_FIX_RECEIVED })
        assertEquals("ticks never publish without fixes", 1, pub.state.value.publishCount)
    }

    @Test
    fun `timer ticks are coalesced while a long publish is in flight`() = runTest {
        val clock = Clock()
        var ticksProcessed = 0
        val pub = BackgroundLocationPublisher(
            publish = { _, _ ->
                delay(250_000L)
                204
            },
            deviceId = { "d1" },
            scope = backgroundScope,
            clockMs = { clock.ms },
            tickIntervalMs = { 100_000L },
            onTickProcessed = { ticksProcessed++ },
        )

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        val before = ticksProcessed

        // Four timer fires happen while the publish suspends (100s..400s);
        // coalescing skips the fires while a tick is in flight, so only three
        // of the four produce machine ticks (the fourth fire queues exactly
        // one tick while the publish is busy).
        advanceTimeBy(400_000L)
        runCurrent()

        assertEquals("one timer fire was coalesced", 3, ticksProcessed - before)
        assertEquals(1, pub.state.value.publishCount)
        pub.stop()
    }

    @Test
    fun `stop cancels the loop and no further publishes happen`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        val countAfterFirst = pub.state.value.publishCount

        pub.stop()
        runCurrent()
        assertFalse(pub.state.value.running)

        clock.ms = 60_000L
        pub.submitFix(sample(2.0, 200L).sample!!)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals("a stopped publisher never publishes", countAfterFirst, pub.state.value.publishCount)
    }

    // ------------------------------------------------------------ BACKOFF

    @Test
    fun `publish failure keeps the pending and retries the newest after backoff`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        assertEquals(0, pub.state.value.publishCount)
        assertTrue(pub.state.value.lastError != null)
        assertTrue(pub.state.value.hasPendingLocation)
        assertTrue("a failure enters the transport backoff", pub.state.value.backoffActive)
        assertNull(relay.getLocation("d1"))

        // A significant new fix inside the backoff window updates the pending
        // but must NOT attempt the network (fixes never reset backoff).
        relay.failPublish = false
        tick(clock, 30_000L)
        pub.submitFix(sample(2.0, 200L).sample!!)
        runCurrent()
        assertEquals("still inside the backoff window", 0, pub.state.value.publishCount)
        assertNull("nothing reached the relay during backoff", relay.getLocation("d1"))
        assertTrue(pub.state.value.hasPendingLocation)
        assertTrue(pub.state.value.backoffActive)

        // At backoff expiry the newest fresh pending is published.
        tick(clock, 30_000L)
        assertEquals(1, pub.state.value.publishCount)
        assertEquals("the retry published the newest pending", 2.0, relay.getLocation("d1")!!.latitude, 0.0)
        assertNull(pub.state.value.lastError)
        assertFalse(pub.state.value.hasPendingLocation)
        assertFalse(pub.state.value.backoffActive)
    }

    @Test
    fun `backoff expiry retries the previous pending when no new fix arrives`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        assertEquals(0, pub.state.value.publishCount)

        relay.failPublish = false
        tick(clock, 60_000L)
        runCurrent()

        assertEquals("the still-fresh pending was retried", 1, pub.state.value.publishCount)
        assertEquals(1.0, relay.getLocation("d1")!!.latitude, 0.0)
    }

    @Test
    fun `stale pending is replaced by a fresh fix which is published immediately`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        // A fix read with age 2 min: accepted, publish fails, pending kept.
        pub.submitFix(sampleOf(1.0, 100L, ageMs = LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS - 59_000L))
        runCurrent()
        assertEquals(0, pub.state.value.publishCount)

        // At t=60 s the pending is stale; a fresh fix arriving then replaces
        // it and is published (the backoff window is over).
        relay.failPublish = false
        clock.ms = 60_000L
        pub.submitFix(sampleOf(2.0, 200L))
        runCurrent()

        assertEquals(1, pub.state.value.publishCount)
        assertEquals("the fresh fix replaced the stale pending", 2.0, relay.getLocation("d1")!!.latitude, 0.0)
    }

    @Test
    fun `publish failure fires the failure callback for re-probing`() = runTest {
        var invalidated = 0
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        val pub = publisher(relay, clock = clock, onPublishFailure = { invalidated++ })

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()

        assertEquals(1, invalidated)
        assertEquals(0, pub.state.value.publishCount)
    }

    @Test
    fun `error then success leaves the publisher state recovered`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        assertTrue("a failed publish must report a failure result", pub.state.value.lastPublishResult != "Success")

        relay.failPublish = false
        tick(clock, 60_000L)
        runCurrent()

        assertEquals("Success", pub.state.value.lastPublishResult)
        assertNull(pub.state.value.lastError)
        assertFalse(pub.state.value.hasPendingLocation)
        assertTrue(pub.state.value.lastPublishAtMs != null)
    }

    @Test
    fun `publish now resets the backoff so the next floor-eligible tick retries`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        // Failure 1 at t=0 -> backoff 60 s; a second fix stays pending.
        tick(clock, 30_000L)
        pub.submitFix(sample(2.0, 200L).sample!!)
        runCurrent()
        tick(clock, 30_000L)
        // Failure 2 at t=60 s (expiry retry) -> backoff 2 min until t=180 s.
        assertEquals(0, pub.state.value.publishCount)
        assertTrue(pub.state.value.backoffActive)
        assertEquals(2, pub.state.value.backoffAttempt)

        // Publish-now clears the exponential backoff, but the 60 s failure
        // floor still blocks an immediate attempt.
        relay.failPublish = false
        pub.publishNow()
        runCurrent()
        assertFalse("publish-now clears the backoff", pub.state.value.backoffActive)
        assertEquals("the floor blocks the immediate reset attempt", 0, pub.state.value.publishCount)

        // At the next floor-eligible tick (t=120 s) the pending is published.
        tick(clock, 60_000L)
        assertEquals(1, pub.state.value.publishCount)
        assertEquals("the newest pending was published", 2.0, relay.getLocation("d1")!!.latitude, 0.0)
        pub.stop()
    }

    // ------------------------------------------------------------ EVENTS

    @Test
    fun `events cover fix received pending updated and decision on a successful cycle`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(relay, clock = clock, events = events)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()

        assertEquals(1, pub.state.value.publishCount)
        val names = events.map { it.name }
        assertTrue(names.contains(LocationEventLog.EVENT_FIX_RECEIVED))
        assertTrue(names.contains(LocationEventLog.EVENT_PENDING_UPDATED))
        assertTrue(names.contains(LocationEventLog.EVENT_PUBLISH_DECISION))

        val received = events.first { it.name == LocationEventLog.EVENT_FIX_RECEIVED }
        assertEquals("network", received.fields.first { it.first == "provider" }.second)
        assertEquals(20f, received.fields.first { it.first == "accuracyMeters" }.second)
        assertEquals(0L, received.fields.first { it.first == "ageMs" }.second)
        assertEquals(true, received.fields.first { it.first == "hasAccuracy" }.second)

        val decision = events.first { it.name == LocationEventLog.EVENT_PUBLISH_DECISION }
        assertEquals("PUBLISH", decision.fields.first { it.first == "decision" }.second)
        assertEquals(PublishDecisionReason.FIRST.name, decision.fields.first { it.first == "reason" }.second)
        assertEquals(
            "no last publish yet means no since fields",
            null,
            decision.fields.firstOrNull { it.first == "sinceLastAttemptMs" }?.second,
        )
        assertEquals(
            "no last publish yet means no since-success field",
            null,
            decision.fields.firstOrNull { it.first == "sinceLastSuccessMs" }?.second,
        )
    }

    @Test
    fun `rejected fix emits a fix-rejected event exactly once`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(relay, clock = clock, events = events)

        pub.start()
        runCurrent()
        // A fix older than the freshness limit.
        pub.submitFix(sampleOf(1.0, 100L, ageMs = LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS + 1L))
        runCurrent()

        val rejected = events.filter { it.name == LocationEventLog.EVENT_FIX_REJECTED }
        assertEquals("exactly one rejection event per rejected fix", 1, rejected.size)
        assertEquals(FixDecisionReason.REJECTED_STALE.name, rejected.single().fields.first { it.first == "reason" }.second)
        assertEquals(0, pub.state.value.publishCount)
        assertFalse(pub.state.value.hasPendingLocation)
        assertEquals(FixDecisionReason.REJECTED_STALE.name, pub.state.value.lastFixDecision)
    }

    @Test
    fun `temporal ticks never re-emit a fix-rejected event`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(relay, clock = clock, events = events)

        pub.start()
        runCurrent()
        pub.submitFix(sampleOf(1.0, 100L, ageMs = LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS + 1L))
        runCurrent()
        assertEquals(1, events.count { it.name == LocationEventLog.EVENT_FIX_REJECTED })
        events.clear()

        // Ten minutes of temporal ticks with no new fix: the rejection event
        // must NOT be re-emitted by any tick.
        tick(clock, 10 * 60_000L)
        assertEquals(
            "ticks never re-emit a fix-rejected event",
            0,
            events.count { it.name == LocationEventLog.EVENT_FIX_REJECTED },
        )
    }

    @Test
    fun `a new rejected fix emits a new fix-rejected event even with the same reason`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(relay, clock = clock, events = events)

        pub.start()
        runCurrent()
        // Two stale fixes with the SAME rejection reason, separated by ticks.
        pub.submitFix(sampleOf(1.0, 100L, ageMs = LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS + 1L))
        runCurrent()
        tick(clock, 60_000L)
        pub.submitFix(sampleOf(2.0, 200L, ageMs = LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS + 1L))
        runCurrent()

        val rejected = events.filter { it.name == LocationEventLog.EVENT_FIX_REJECTED }
        assertEquals(
            "each rejected fix emits its own event, even with an identical reason",
            2,
            rejected.size,
        )
    }

    @Test
    fun `accepted and rejected fix transitions keep the normal event flow`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(relay, clock = clock, events = events)

        pub.start()
        runCurrent()
        // Accepted fresh fix: FIRST publish, no rejection event.
        pub.submitFix(sampleOf(1.0, 100L))
        runCurrent()
        assertEquals(1, pub.state.value.publishCount)
        assertEquals(0, events.count { it.name == LocationEventLog.EVENT_FIX_REJECTED })

        // Rejected stale fix: exactly one rejection event.
        pub.submitFix(sampleOf(2.0, 200L, ageMs = LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS + 1L))
        runCurrent()
        assertEquals(1, events.count { it.name == LocationEventLog.EVENT_FIX_REJECTED })

        // A later accepted fix still publishes normally.
        clock.ms = 60_000L
        pub.submitFix(sampleOf(3.0, 300L))
        runCurrent()
        assertEquals(2, pub.state.value.publishCount)
        assertEquals(
            "the later rejection count stays at one",
            1,
            events.count { it.name == LocationEventLog.EVENT_FIX_REJECTED },
        )
    }

    @Test
    fun `stationary pending emits one skip decision per state transition`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(relay, clock = clock, events = events)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        events.clear()

        // Same position 60 s later: below the movement threshold, no publish.
        clock.ms = 60_000L
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()

        val decision = events.first { it.name == LocationEventLog.EVENT_PUBLISH_DECISION }
        assertEquals("SKIP", decision.fields.first { it.first == "decision" }.second)
        assertEquals(
            PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD.name,
            decision.fields.first { it.first == "reason" }.second,
        )
        assertEquals(0.0, decision.fields.first { it.first == "distanceMeters" }.second as Double, 0.0)
        assertEquals(
            PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD.name,
            pub.state.value.lastPublishDecision,
        )
    }

    // ------------------------------------------------------------ PRESENCE

    @Test
    fun `presence republishes the last position after five quiet minutes`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(relay, clock = clock, events = events)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        assertEquals(1, pub.state.value.publishCount)
        assertEquals(0, pub.state.value.presenceCount)

        // Five quiet minutes: the machine Tick alone drives presence — no
        // location acquisition is forced.
        tick(clock, 300_000L)
        runCurrent()

        assertEquals("one presence after 5 quiet minutes", 1, pub.state.value.presenceCount)
        assertEquals("presence re-PUTs the identical position", 1.0, relay.getLocation("d1")!!.latitude, 0.0)
        assertEquals("presence keeps the identical recordedAt payload", 100L, relay.getLocation("d1")!!.recordedAtEpochMs)
        assertEquals("presence count is not a position publish", 1, pub.state.value.publishCount)
        assertEquals(PublishDecisionReason.PRESENCE.name, pub.state.value.lastPresenceDecision)
        assertTrue("a presence decision event was emitted", events.any {
            it.name == LocationEventLog.EVENT_PUBLISH_DECISION &&
                it.fields.firstOrNull { f -> f.first == "reason" }?.second == PublishDecisionReason.PRESENCE.name
        })
    }

    // ------------------------------------------------------ SERIALIZATION

    @Test
    fun `commands are serialized and no concurrent publish can interleave`() = runTest {
        val clock = Clock()
        var concurrent = 0
        var maxConcurrent = 0
        var publishes = 0
        val pub = BackgroundLocationPublisher(
            publish = { _, _ ->
                concurrent++
                maxConcurrent = max(maxConcurrent, concurrent)
                delay(100)
                concurrent--
                publishes++
                204
            },
            deviceId = { "d1" },
            scope = backgroundScope,
            clockMs = { clock.ms },
            tickIntervalMs = { 60_000L },
        )

        pub.start()
        runCurrent()
        // The first fix is published (suspended); two explicit publish
        // requests are enqueued while it runs.
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()
        pub.publishNow()
        pub.publishNow()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("the first fix published once", 1, publishes)
        assertEquals("no two publishes ever overlapped", 1, maxConcurrent)
        assertEquals(1, pub.state.value.publishCount)
        pub.stop()
    }

    @Test
    fun `mode change switches policy without restart`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, clock = clock)

        pub.start()
        runCurrent()
        pub.submitFix(sample(1.0, 100L).sample!!)
        runCurrent()

        pub.setMode(PublisherMode.MAP_ACTIVE)
        runCurrent()

        // 78 m of movement: below the 100 m background threshold, publishable
        // with the 50 m map-active threshold — proves the mode was applied.
        clock.ms = 60_000L
        pub.submitFix(sampleOf(1.0007, 200L))
        runCurrent()
        assertEquals(2, pub.state.value.publishCount)
        assertEquals(PublishDecisionReason.MOVEMENT.name, pub.state.value.lastPublishDecision)
        pub.stop()
    }
}