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

/**
 * Tests for the V2 actor runner: the one-shot acquisition loop feeds the pure
 * [LocationPublisherMachine], publishes happen only on machine decisions, and
 * every command (including PUBLISH_NOW) is serialized through one channel.
 *
 * A controllable monotonic clock is injected (the test scheduler's virtual
 * time does not move a real clock), so the machine's timers are deterministic.
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

    /** Deterministic distance: 1 degree of latitude ~ 111 km. */
    private fun distanceMeters(a: PositionFix, b: PositionFix): Double =
        abs(a.latitude - b.latitude) * 111_320.0

    private class Clock(var ms: Long = 0L)

    private fun TestScope.publisher(
        relay: FakeRelayClient,
        read: () -> LocationReadResult,
        checkIntervalMs: Long = 1_000L,
        clock: Clock = Clock(),
        onPublishFailure: (() -> Unit)? = null,
        events: MutableList<LocationEvent>? = null,
        mode: PublisherMode = PublisherMode.BACKGROUND,
    ) = BackgroundLocationPublisher(
        readLocation = read,
        publish = { id, loc -> relay.publishLocation(id, loc) },
        deviceId = { "d1" },
        checkIntervalMs = { checkIntervalMs },
        scope = backgroundScope,
        onPublishFailure = onPublishFailure,
        distanceMeters = ::distanceMeters,
        clockMs = { clock.ms },
        emitEvent = { if (events != null) events += it },
        mode = mode,
    )

    /**
     * Advances the virtual scheduler and mirrors the machine clock to the
     * scheduler time after every step, so the tick being processed always sees
     * `nowMs` equal to its own scheduler time (a single jump would freeze the
     * machine clock while the scheduler fires many timer ticks).
     */
    private fun TestScope.tick(clock: Clock, timeMs: Long, stepMs: Long = 1_000L) {
        var remaining = timeMs
        while (remaining > 0L) {
            val step = minOf(stepMs, remaining)
            advanceTimeBy(step)
            clock.ms = testScheduler.currentTime
            runCurrent()
            remaining -= step
        }
        runCurrent()
    }

    @Test
    fun `movement across cycles publishes each cycle`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        var counter = 0L
        val pub = publisher(
            relay,
            read = { counter++; sample(counter.toDouble(), counter * 1_000L) },
            clock = clock,
            checkIntervalMs = 60_000L,
        )

        pub.start()
        runCurrent()
        assertEquals(1, pub.state.value.publishCount)
        assertEquals(1.0, relay.getLocation("d1")!!.latitude, 0.0)

        // Each cycle is 60 s apart in monotonic terms (past the backoff floor),
        // and every read moved ~111 km, so each cycle publishes.
        tick(clock, 60_000L)
        assertEquals(2, pub.state.value.publishCount)
        assertEquals(2.0, relay.getLocation("d1")!!.latitude, 0.0)

        tick(clock, 60_000L)
        assertEquals(3, pub.state.value.publishCount)
        assertTrue(pub.state.value.running)
    }

    @Test
    fun `no fresh fix does not publish again`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        var fixes = 1
        val pub = publisher(
            relay,
            read = {
                if (fixes > 0) { fixes--; sample(1.0, 100L) } else { LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix") }
            },
            clock = clock,
            checkIntervalMs = 60_000L,
        )

        pub.start()
        runCurrent()
        assertEquals(1, pub.state.value.publishCount)

        tick(clock, 60_000L)
        // No fresh fix means nothing to publish: the loop stays quiet and never
        // spams the relay, and the success already consumed the pending location.
        assertEquals(1, pub.state.value.publishCount)
        assertEquals(1.0, relay.getLocation("d1")!!.latitude, 0.0)
        assertFalse("a consumed publish leaves nothing pending", pub.state.value.hasPendingLocation)
    }

    @Test
    fun `shouldPublish false never invokes the publish function`() = runTest {
        val clock = Clock()
        var publishes = 0
        // A single fresh fix: the first cycle publishes (never published), then
        // a stationary read produces no movement, so every later cycle must NOT
        // invoke the publish function (the tsnet lifecycle is gated behind it).
        val pub = BackgroundLocationPublisher(
            readLocation = { sample(1.0, 100L) },
            publish = { _, _ -> publishes++; 204 },
            deviceId = { "d1" },
            checkIntervalMs = { 60_000L },
            scope = backgroundScope,
            distanceMeters = ::distanceMeters,
            clockMs = { clock.ms },
        )

        pub.start()
        runCurrent()
        assertEquals(1, publishes)

        tick(clock, 60_000L)
        tick(clock, 60_000L)
        assertEquals("no movement means no publish, hence no network touch", 1, publishes)
    }

    @Test
    fun `no fresh fix never publishes no matter how long the silence`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        var fixes = 1
        val pub = publisher(
            relay,
            read = {
                if (fixes > 0) { fixes--; sample(1.0, 100L) } else { LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix") }
            },
            clock = clock,
            checkIntervalMs = 60_000L,
        )

        pub.start()
        runCurrent()
        assertEquals(1, pub.state.value.publishCount)

        tick(clock, 10 * 60_000L)
        assertEquals("without a fresh fix there is nothing to publish", 1, pub.state.value.publishCount)
        assertEquals("presence may re-PUT but never alters the position", 1.0, relay.getLocation("d1")!!.latitude, 0.0)
    }

    @Test
    fun `no fix ever leaves the pending empty and reports the read state`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, read = { LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix") }, clock = clock)

        pub.start()
        runCurrent()

        val state = pub.state.value
        assertEquals(0, state.publishCount)
        assertEquals(LocationReadStatus.NO_POSITION.name, state.lastReadResult)
        assertEquals(false, state.hasPendingLocation)
        assertNull("no fix means no publish attempt", state.lastPublishResult)
        assertNull(relay.getLocation("d1"))
    }

    @Test
    fun `publish failure keeps the pending and retries the newest after backoff`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        var fixes = 0
        val pub = publisher(relay, read = {
            fixes++
            when (fixes) {
                1 -> sample(1.0, 100L)
                2 -> sample(2.0, 200L)
                else -> LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix")
            }
        }, clock = clock)

        pub.start()
        runCurrent()
        // First publish fails: the pending is kept, nothing is stored.
        assertEquals(0, pub.state.value.publishCount)
        assertTrue(pub.state.value.lastError != null)
        assertTrue(pub.state.value.hasPendingLocation)
        assertTrue("a failure enters the transport backoff", pub.state.value.backoffActive)
        assertNull(relay.getLocation("d1"))

        // The transport backoff window: even a significant new fix must NOT
        // trigger a network attempt (fixes never reset the transport backoff).
        relay.failPublish = false
        tick(clock, 30_000L)
        assertEquals("still inside the backoff window", 0, pub.state.value.publishCount)
        assertNull("nothing reached the relay during backoff", relay.getLocation("d1"))
        assertTrue("the pending fix is still held", pub.state.value.hasPendingLocation)
        assertTrue(pub.state.value.backoffActive)

        // At backoff expiry the newest fresh pending is published.
        tick(clock, 30_000L)
        assertEquals(1, pub.state.value.publishCount)
        assertEquals(2.0, relay.getLocation("d1")!!.latitude, 0.0)
        assertNull(pub.state.value.lastError)
        assertFalse("a success must clear the pending state", pub.state.value.hasPendingLocation)
        assertFalse(pub.state.value.backoffActive)
    }

    @Test
    fun `publish failure fires the failure callback for re-probing`() = runTest {
        var invalidated = 0
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        val pub = publisher(relay, read = { sample(1.0, 100L) }, clock = clock, onPublishFailure = { invalidated++ })

        pub.start()
        runCurrent()

        assertEquals(1, invalidated)
        assertEquals(0, pub.state.value.publishCount)
    }

    @Test
    fun `backoff expiry retries the newest fix read in the same cycle`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        var fixes = 0
        val pub = publisher(relay, read = {
            fixes++
            when (fixes) {
                1 -> sample(1.0, 100L)
                2 -> sample(2.0, 200L)
                else -> LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix")
            }
        }, clock = clock)

        pub.start()
        runCurrent()
        // Failure at t=0 keeps pending 1.0 and backs off until t=60 s.
        assertEquals(0, pub.state.value.publishCount)

        // At expiry the same cycle first reads the newer fix (2.0) and the
        // retry publishes the newest useful pending, not the old one.
        relay.failPublish = false
        tick(clock, 60_000L)
        assertEquals(1, pub.state.value.publishCount)
        assertEquals("the retry used the newest fix", 2.0, relay.getLocation("d1")!!.latitude, 0.0)
    }

    @Test
    fun `backoff expiry retries the previous pending when the read fails`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        var fixes = 0
        val pub = publisher(relay, read = {
            fixes++
            if (fixes == 1) sample(1.0, 100L)
            else LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix")
        }, clock = clock)

        pub.start()
        runCurrent()
        assertEquals(0, pub.state.value.publishCount)

        // The expiry-cycle read fails: the still-fresh previous pending (1.0)
        // must be retried.
        relay.failPublish = false
        tick(clock, 60_000L)
        assertEquals(1, pub.state.value.publishCount)
        assertEquals("the still-fresh pending was retried", 1.0, relay.getLocation("d1")!!.latitude, 0.0)
    }

    @Test
    fun `backoff expiry with a stale pending publishes the fresh fix read in the cycle`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        var fixes = 0
        val pub = publisher(relay, read = {
            fixes++
            when (fixes) {
                1 -> LocationReadResult(
                    status = LocationReadStatus.SUCCESS,
                    sample = LocationSample(
                        provider = "network",
                        latitude = 1.0,
                        longitude = 0.0,
                        accuracyMeters = 20f,
                        recordedAtWallClockMs = 100L,
                        recordedAtElapsedNanos = 0L,
                        ageMs = LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS - 59_000L,
                        acquisitionMs = 0L,
                        fromLastKnown = false,
                    ),
                )
                2 -> sample(2.0, 200L)
                else -> LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix")
            }
        }, clock = clock)

        pub.start()
        runCurrent()
        assertEquals(0, pub.state.value.publishCount)

        // The pending (read with age 2 min) is stale by t=60 s; the fresh fix
        // read in the expiry cycle replaces it and is the one published.
        relay.failPublish = false
        tick(clock, 60_000L)
        assertEquals(1, pub.state.value.publishCount)
        assertEquals("the fresh fix replaced the stale pending", 2.0, relay.getLocation("d1")!!.latitude, 0.0)
    }

    @Test
    fun `timer ticks are coalesced while a long publish is in flight`() = runTest {
        val clock = Clock()
        var reads = 0
        val pub = BackgroundLocationPublisher(
            readLocation = {
                reads++
                sample(1.0, 100L)
            },
            publish = { _, _ ->
                delay(250_000L)
                204
            },
            deviceId = { "d1" },
            checkIntervalMs = { 100_000L },
            scope = backgroundScope,
            clockMs = { clock.ms },
        )

        pub.start()
        runCurrent()
        // The first publish suspends for 250 s; four timer fires happen during
        // it (100s..400s). Coalescing keeps at most one cycle pending, so only
        // the fires after the publish completes produce reads.
        advanceTimeBy(400_000L)
        runCurrent()

        assertEquals("initial read + post-publish reads", 3, reads)
        assertEquals(1, pub.state.value.publishCount)
        pub.stop()
    }

    @Test
    fun `error then success leaves the publisher state recovered`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        var fixes = 0
        val pub = publisher(relay, read = {
            fixes++
            when (fixes) {
                1 -> sample(1.0, 100L)
                2 -> sample(2.0, 200L)
                else -> LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix")
            }
        }, clock = clock)

        pub.start()
        runCurrent()
        assertTrue("a failed publish must report a failure result", pub.state.value.lastPublishResult != "Success")

        relay.failPublish = false
        tick(clock, 60_000L)
        // After the backoff window a successful publish recovers the
        // operational state (Success, cleared error and pending), never stuck.
        assertEquals("Success", pub.state.value.lastPublishResult)
        assertNull(pub.state.value.lastError)
        assertFalse(pub.state.value.hasPendingLocation)
        assertTrue(pub.state.value.lastPublishAtMs != null)
    }

    @Test
    fun `stop cancels the loop and no further publishes happen`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        var counter = 0L
        val pub = publisher(relay, read = { counter++; sample(counter.toDouble(), 100L) }, clock = clock)

        pub.start()
        runCurrent()
        val countAfterFirst = pub.state.value.publishCount

        pub.stop()
        runCurrent()
        assertFalse(pub.state.value.running)

        tick(clock, 10_000L)
        assertEquals(countAfterFirst, pub.state.value.publishCount)
    }

    @Test
    fun `published location carries the contract accuracy field`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, read = { sample(-23.5505, 1_700_000_000_000L) }, clock = clock)

        pub.start()
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
        val pub = publisher(relay, read = { sample(1.0, 100L) }, clock = clock)

        pub.start()
        runCurrent()

        val state = pub.state.value
        assertEquals("Success", state.lastPublishResult)
        assertTrue(state.lastPublishAtMs != null)
        assertTrue(state.lastPublishAttemptAtMs != null)
        assertTrue(state.lastReadAtMs != null)
        assertEquals(LocationReadStatus.SUCCESS.name, state.lastReadResult)
        assertFalse("a success clears the pending flag", state.hasPendingLocation)
        assertNull(state.lastError)
    }

    @Test
    fun `read without a fix reports the read result and no pending location`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(relay, read = { LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix") }, clock = clock)

        pub.start()
        runCurrent()

        val state = pub.state.value
        assertEquals(LocationReadStatus.NO_POSITION.name, state.lastReadResult)
        assertEquals(false, state.hasPendingLocation)
        assertNull("no fix never produces a publish attempt", state.lastPublishResult)
    }

    // ----------------------------------------------------------- EVENTS

    @Test
    fun `events cover fix received pending updated and decision on a successful cycle`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(relay, read = { sample(1.0, 100L) }, clock = clock, events = events)

        pub.start()
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
    fun `no fix emits a SKIP decision with reason NO_PENDING`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(
            relay,
            read = { LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix") },
            clock = clock,
            events = events,
        )

        pub.start()
        runCurrent()

        val decisions = events.filter { it.name == LocationEventLog.EVENT_PUBLISH_DECISION }
        assertEquals(1, decisions.size)
        val decision = decisions.single()
        assertEquals("SKIP", decision.fields.first { it.first == "decision" }.second)
        assertEquals(PublishDecisionReason.NO_PENDING.name, decision.fields.first { it.first == "reason" }.second)
        assertFalse("no fix events never carry a received fix", events.any { it.name == LocationEventLog.EVENT_FIX_RECEIVED })
    }

    @Test
    fun `stationary cycle emits a SKIP decision with reason BELOW_MOVEMENT_THRESHOLD`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(
            relay,
            read = { sample(1.0, 100L) },
            clock = clock,
            events = events,
            checkIntervalMs = 60_000L,
        )

        pub.start()
        runCurrent()
        events.clear()

        // Second cycle: same position as the published one, past the backoff
        // floor, far below the movement threshold.
        tick(clock, 60_000L)

        val decision = events.first { it.name == LocationEventLog.EVENT_PUBLISH_DECISION }
        assertEquals("SKIP", decision.fields.first { it.first == "decision" }.second)
        assertEquals(
            PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD.name,
            decision.fields.first { it.first == "reason" }.second,
        )
        assertEquals(0.0, decision.fields.first { it.first == "distanceMeters" }.second as Double, 0.0)
        assertEquals(60_000L, decision.fields.first { it.first == "sinceLastAttemptMs" }.second)
        assertEquals(60_000L, decision.fields.first { it.first == "sinceLastSuccessMs" }.second)
        assertEquals(
            "state mirrors the last decision",
            PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD.name,
            pub.state.value.lastPublishDecision,
        )
    }

    @Test
    fun `rejected fix emits a fix-rejected event with the reason`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        // A fix older than the freshness limit.
        val pub = publisher(
            relay,
            read = {
                LocationReadResult(
                    status = LocationReadStatus.SUCCESS,
                    sample = LocationSample(
                        provider = "network",
                        latitude = 1.0,
                        longitude = 0.0,
                        accuracyMeters = 20f,
                        recordedAtWallClockMs = 100L,
                        recordedAtElapsedNanos = 0L,
                        ageMs = LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS + 1L,
                        acquisitionMs = 0L,
                        fromLastKnown = false,
                    ),
                )
            },
            clock = clock,
            events = events,
        )

        pub.start()
        runCurrent()

        val rejected = events.first { it.name == LocationEventLog.EVENT_FIX_REJECTED }
        assertEquals(FixDecisionReason.REJECTED_STALE.name, rejected.fields.first { it.first == "reason" }.second)
        assertEquals(0, pub.state.value.publishCount)
        assertFalse(pub.state.value.hasPendingLocation)
        assertEquals(FixDecisionReason.REJECTED_STALE.name, pub.state.value.lastFixDecision)
    }

    @Test
    fun `fix metadata is mirrored into the publisher state`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val pub = publisher(
            relay,
            read = {
                LocationReadResult(
                    status = LocationReadStatus.SUCCESS,
                    sample = LocationSample(
                        provider = "gps",
                        latitude = 1.0,
                        longitude = 0.0,
                        accuracyMeters = 15f,
                        recordedAtWallClockMs = 100L,
                        recordedAtElapsedNanos = 0L,
                        ageMs = 42L,
                        acquisitionMs = 0L,
                        fromLastKnown = false,
                    ),
                )
            },
            clock = clock,
        )

        pub.start()
        runCurrent()

        val state = pub.state.value
        assertEquals("gps", state.lastFixProvider)
        assertEquals(15f, state.lastFixAccuracyMeters)
        assertEquals(42L, state.lastFixAgeMs)
        assertEquals(PublishDecisionReason.FIRST.name, state.lastPublishDecision)
    }

    // ------------------------------------------------------ SERIALIZATION

    @Test
    fun `commands are serialized and no concurrent publish can interleave`() = runTest {
        val clock = Clock()
        var concurrent = 0
        var maxConcurrent = 0
        var publishes = 0
        val pub = BackgroundLocationPublisher(
            readLocation = { sample(1.0, 100L) },
            publish = { _, _ ->
                concurrent++
                maxConcurrent = max(maxConcurrent, concurrent)
                delay(100)
                concurrent--
                publishes++
                204
            },
            deviceId = { "d1" },
            checkIntervalMs = { 60_000L },
            scope = backgroundScope,
            clockMs = { clock.ms },
        )

        pub.start()
        runCurrent()
        // The first publish is in flight (suspended); two explicit publish
        // requests are enqueued while it runs.
        pub.publishNow()
        pub.publishNow()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("the first cycle published once", 1, publishes)
        assertEquals("no two publishes ever overlapped", 1, maxConcurrent)
        assertEquals("the queued publish-now requests found nothing to publish", 1, pub.state.value.publishCount)
        pub.stop()
    }

    @Test
    fun `publish now resets the backoff so the next floor-eligible tick retries`() = runTest {
        val relay = FakeRelayClient().apply { failPublish = true }
        val clock = Clock()
        var fixes = 0
        val pub = publisher(relay, read = {
            fixes++
            when (fixes) {
                1 -> sample(1.0, 100L)
                2 -> sample(2.0, 200L)
                else -> LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix")
            }
        }, clock = clock)

        pub.start()
        runCurrent()
        // Failure 1 at t=0 -> backoff 60 s; failure 2 at t=60 s -> backoff 2 min
        // (until t=180 s). The pending is the newest fix (2.0).
        tick(clock, 60_000L)
        assertEquals(0, pub.state.value.publishCount)
        assertTrue(pub.state.value.backoffActive)
        assertEquals(2, pub.state.value.backoffAttempt)

        // Explicit publish-now resets the transport backoff, but the 60 s
        // floor still blocks an immediate attempt (10 s have passed since the
        // last attempt at t=60 s).
        relay.failPublish = false
        pub.publishNow()
        runCurrent()
        assertFalse("publish-now clears the backoff", pub.state.value.backoffActive)
        assertEquals("the floor blocks the immediate reset attempt", 0, pub.state.value.publishCount)

        // At the next floor-eligible tick (t=120 s) the pending is published:
        // recovery happened at the floor instead of the full 2-minute backoff.
        tick(clock, 60_000L)
        assertEquals(1, pub.state.value.publishCount)
        assertEquals("the newest pending was published", 2.0, relay.getLocation("d1")!!.latitude, 0.0)
        assertFalse(pub.state.value.backoffActive)
        pub.stop()
    }

    // ------------------------------------------------------------ PRESENCE

    @Test
    fun `presence republishes the last position after five quiet minutes`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        val events = mutableListOf<LocationEvent>()
        val pub = publisher(relay, read = { sample(1.0, 100L) }, clock = clock, events = events)

        pub.start()
        runCurrent()
        assertEquals(1, pub.state.value.publishCount)
        assertEquals(0, pub.state.value.presenceCount)

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
}