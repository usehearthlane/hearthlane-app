package org.hearthlane.location

import org.hearthlane.core.relay.DeviceLocation
import org.hearthlane.test.FakeRelayClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Tests for the adaptive last-known publication loop: cadence, movement-based
 * publishing, throttling, keep-only-latest, retry-after-failure, and the state
 * transitions that Diagnostics reads (Success clears Error/Pending).
 *
 * A controllable wall-clock is injected (the test scheduler's virtual time does
 * not move `System.currentTimeMillis()`), so the publish policy timers are
 * deterministic.
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
    private fun distanceMeters(a: DeviceLocation, b: DeviceLocation): Double =
        abs(a.latitude - b.latitude) * 111_320.0

    private class Clock(var ms: Long = 0L)

    private fun TestScope.publisher(
        relay: FakeRelayClient,
        read: () -> LocationReadResult,
        checkIntervalMs: Long = 1_000L,
        clock: Clock = Clock(),
        onPublishFailure: (() -> Unit)? = null,
    ) = BackgroundLocationPublisher(
        readLocation = read,
        publish = { id, loc -> relay.publishLocation(id, loc) },
        deviceId = { "d1" },
        checkIntervalMs = { checkIntervalMs },
        scope = backgroundScope,
        onPublishFailure = onPublishFailure,
        distanceMeters = ::distanceMeters,
        clockMs = { clock.ms },
    )

    /** Advances the wall-clock (for the publish policy) and the loop cadence. */
    private fun TestScope.tick(clock: Clock, timeMs: Long) {
        clock.ms += timeMs
        advanceTimeBy(timeMs)
        runCurrent()
    }

    @Test
    fun `movement across cycles publishes each cycle`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        var counter = 0L
        val pub = publisher(relay, read = { counter++; sample(counter.toDouble(), counter * 1_000L) }, clock = clock)

        pub.start()
        runCurrent()
        assertEquals(1, pub.state.value.publishCount)
        assertEquals(1.0, relay.getLocation("d1")!!.latitude, 0.0)

        // Each cycle is 60 s apart in wall-clock terms (past the 30 s minimum),
        // and every read moved ~111 km, so each cycle publishes.
        tick(clock, 60_000L)
        assertEquals(2, pub.state.value.publishCount)
        assertEquals(2.0, relay.getLocation("d1")!!.latitude, 0.0)

        tick(clock, 60_000L)
        assertEquals(3, pub.state.value.publishCount)
        assertTrue(pub.state.value.running)
    }

    @Test
    fun `no fresh fix does not publish again within the max interval`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        var fixes = 1
        val pub = publisher(relay, read = {
            if (fixes > 0) { fixes--; sample(1.0, 100L) } else { LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix") }
        }, clock = clock)

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
        var counter = 0
        val pub = BackgroundLocationPublisher(
            readLocation = {
                counter++
                if (counter == 1) sample(1.0, 100L) else sample(1.0, 100L)
            },
            publish = { _, _ -> publishes++; 204 },
            deviceId = { "d1" },
            checkIntervalMs = { 1_000L },
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
    fun `no fresh fix never publishes even past the max interval`() = runTest {
        val relay = FakeRelayClient()
        val clock = Clock()
        var fixes = 1
        val pub = publisher(relay, read = {
            if (fixes > 0) { fixes--; sample(1.0, 100L) } else { LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix") }
        }, clock = clock)

        pub.start()
        runCurrent()
        assertEquals(1, pub.state.value.publishCount)

        tick(clock, LocationForegroundService.MAX_PUBLISH_INTERVAL_MS + 60_000L)
        assertEquals("without a fresh fix there is nothing to publish", 1, pub.state.value.publishCount)
        assertEquals(1.0, relay.getLocation("d1")!!.latitude, 0.0)
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
    fun `publish failure keeps the pending location and retries the newest`() = runTest {
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
        // First publish fails: the pending location is kept, nothing is stored.
        assertEquals(0, pub.state.value.publishCount)
        assertTrue(pub.state.value.lastError != null)
        assertTrue(pub.state.value.hasPendingLocation)
        assertNull(relay.getLocation("d1"))

        relay.failPublish = false
        tick(clock, 60_000L)
        assertEquals(1, pub.state.value.publishCount)
        assertEquals(2.0, relay.getLocation("d1")!!.latitude, 0.0)
        assertNull(pub.state.value.lastError)
        assertFalse("a success must clear the pending state", pub.state.value.hasPendingLocation)
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
        // Part A regression: after a successful publish the operational state is
        // recovered (Success, cleared error and pending), never stuck in Error.
        assertEquals("Success", pub.state.value.lastPublishResult)
        assertNull(pub.state.value.lastError)
        assertFalse(pub.state.value.hasPendingLocation)
        assertTrue(pub.state.value.lastPublishAtMs != null)
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
}