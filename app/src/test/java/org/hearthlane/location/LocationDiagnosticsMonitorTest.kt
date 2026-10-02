package org.hearthlane.location

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [LocationDiagnosticsMonitor]: the shared in-process source that the
 * foreground service reports its real lifecycle and the publisher's sanitized
 * metadata into, and the Diagnostics screen reads back.
 */
class LocationDiagnosticsMonitorTest {

    @Test
    fun `starts as stopped with no metadata`() {
        LocationDiagnosticsMonitor.resetForTest()

        val state = LocationDiagnosticsMonitor.state.value
        assertFalse(state.serviceRunning)
        assertFalse(state.publisherRunning)
        assertNull(state.lastPublishResult)
        assertNull(state.lastPublishAtMs)
    }

    @Test
    fun `service start and stop are reported`() {
        LocationDiagnosticsMonitor.resetForTest()

        LocationDiagnosticsMonitor.onServiceStarted(LocationForegroundService.ACTIVE_INTERVAL_MS)
        assertTrue(LocationDiagnosticsMonitor.state.value.serviceRunning)
        assertEquals(LocationForegroundService.ACTIVE_INTERVAL_MS, LocationDiagnosticsMonitor.state.value.intervalMs)

        LocationDiagnosticsMonitor.onServiceStopped()
        assertFalse(LocationDiagnosticsMonitor.state.value.serviceRunning)
        assertFalse(LocationDiagnosticsMonitor.state.value.publisherRunning)
    }

    @Test
    fun `publisher metadata is mirrored sanitized`() {
        LocationDiagnosticsMonitor.resetForTest()
        val publisherState = BackgroundLocationPublisher.State(
            running = true,
            publishCount = 3,
            lastPublishResult = "Success",
            lastPublishAtMs = 1_700_000_000_000L,
            lastReadResult = LocationReadStatus.SUCCESS.name,
            hasPendingLocation = true,
            lastFixProvider = "network",
            lastFixAccuracyMeters = 20f,
            lastFixAgeMs = 1_500L,
            lastPublishDecision = PublishDecisionReason.MOVEMENT,
        )

        LocationDiagnosticsMonitor.onPublisherState(publisherState)

        val state = LocationDiagnosticsMonitor.state.value
        assertTrue(state.publisherRunning)
        assertEquals(3, state.publishCount)
        assertEquals("Success", state.lastPublishResult)
        assertEquals(1_700_000_000_000L, state.lastPublishAtMs)
        assertEquals(LocationReadStatus.SUCCESS.name, state.lastReadResult)
        assertTrue(state.hasPendingLocation)
        assertEquals("network", state.lastFixProvider)
        assertEquals(20f, state.lastFixAccuracyMeters)
        assertEquals(1_500L, state.lastFixAgeMs)
        assertEquals(PublishDecisionReason.MOVEMENT.name, state.lastPublishDecision)
    }

    @Test
    fun `publisher stop clears running without inventing timestamps`() {
        LocationDiagnosticsMonitor.resetForTest()
        LocationDiagnosticsMonitor.onPublisherState(
            BackgroundLocationPublisher.State(running = true, lastPublishResult = "Success"),
        )
        LocationDiagnosticsMonitor.onPublisherState(BackgroundLocationPublisher.State(running = false))

        val state = LocationDiagnosticsMonitor.state.value
        assertFalse(state.publisherRunning)
        assertNull("no stale success is invented", state.lastPublishAtMs)
        assertNull("no stale fix metadata is invented", state.lastFixProvider)
    }
}