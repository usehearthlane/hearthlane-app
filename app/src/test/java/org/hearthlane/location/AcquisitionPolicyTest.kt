package org.hearthlane.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure [AcquisitionPolicy] tests: provider eligibility, GPS cooldown,
 * in-flight protection, movement evidence, poor-accuracy escalation, map
 * activation and the critical stationary GNSS safeguard.
 */
class AcquisitionPolicyTest {

    private fun distanceMeters(a: FixObserved, b: FixObserved): Double =
        abs(a.latitude - b.latitude) * 111_320.0

    private fun fix(latitude: Double = 1.0, accuracy: Float = 40f) =
        FixObserved(latitude, 0.0, accuracy)

    private fun policy() = AcquisitionPolicy(::distanceMeters)

    private fun assertRequested(decision: GpsDecision, reason: GpsRequestReason) {
        assertTrue(decision.request)
        assertEquals(reason, decision.reason)
    }

    private fun assertSkipped(decision: GpsDecision, reason: GpsSkipReason) {
        assertFalse(decision.request)
        assertEquals(reason, decision.skipReason)
    }

    // -------------------------------------------------------- PERMISSIONS

    @Test
    fun `coarse permission never allows GPS escalation`() {
        val p = policy()
        assertSkipped(p.evaluateGps(false, true, hasPublishableFix = false, nowMs = 0L), GpsSkipReason.NO_FINE_PERMISSION)
        assertSkipped(p.evaluateMapActive(false, true, nowMs = 0L), GpsSkipReason.NO_FINE_PERMISSION)
    }

    @Test
    fun `fine permission allows GPS when the provider is enabled`() {
        val p = policy()
        assertSkipped(p.evaluateGps(true, false, hasPublishableFix = false, nowMs = 0L), GpsSkipReason.PROVIDER_DISABLED)
        assertRequested(
            p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 0L),
            GpsRequestReason.NO_PUBLISHABLE_FIX,
        )
    }

    // ----------------------------------------------------------- COOLDOWN

    @Test
    fun `gps requests respect the sixty second cooldown`() {
        val p = policy()
        assertRequested(
            p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 0L),
            GpsRequestReason.NO_PUBLISHABLE_FIX,
        )
        p.onGpsStarted(GpsRequestReason.NO_PUBLISHABLE_FIX, nowMs = 0L)
        p.onGpsCompleted(hasFix = true, nowMs = 5_000L)

        assertSkipped(p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 30_000L), GpsSkipReason.COOLDOWN)
        assertSkipped(p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 59_999L), GpsSkipReason.COOLDOWN)
        assertRequested(
            p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 60_000L),
            GpsRequestReason.NO_PUBLISHABLE_FIX,
        )
    }

    @Test
    fun `an in flight gps request blocks another`() {
        val p = policy()
        assertRequested(
            p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 0L),
            GpsRequestReason.NO_PUBLISHABLE_FIX,
        )
        p.onGpsStarted(GpsRequestReason.NO_PUBLISHABLE_FIX, nowMs = 0L)

        assertSkipped(p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 5_000L), GpsSkipReason.ALREADY_IN_FLIGHT)
        assertSkipped(p.evaluateMapActive(true, true, nowMs = 5_000L), GpsSkipReason.ALREADY_IN_FLIGHT)
    }

    // ----------------------------------------------------------- MOVEMENT

    @Test
    fun `ordinary network jitter does not escalate`() {
        val p = policy()
        // 40 m apart with 100 m accuracies: circles overlap, no movement.
        p.observeFix(fix(1.0, accuracy = 100f), nowMs = 0L)
        p.observeFix(fix(1.00036, accuracy = 100f), nowMs = 30_000L)
        assertSkipped(p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 30_000L), GpsSkipReason.NO_ESCALATION)
    }

    @Test
    fun `separated accuracy circles escalate for movement confirmation`() {
        val p = policy()
        p.observeFix(fix(1.0, accuracy = 40f), nowMs = 0L)
        p.observeFix(fix(1.001, accuracy = 40f), nowMs = 30_000L) // ~111 m > 80 m
        assertRequested(
            p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 30_000L),
            GpsRequestReason.MOVEMENT_CONFIRMATION,
        )
    }

    @Test
    fun `movement escalation decays without new evidence`() {
        val p = policy()
        p.observeFix(fix(1.0, accuracy = 40f), nowMs = 0L)
        p.observeFix(fix(1.001, accuracy = 40f), nowMs = 30_000L)
        assertRequested(
            p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 30_000L),
            GpsRequestReason.MOVEMENT_CONFIRMATION,
        )
        // After the TTL, the aged movement evidence no longer escalates.
        assertSkipped(
            p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 30_000L + AcquisitionPolicy.ESCALATION_CONTEXT_TTL_MS + 1L),
            GpsSkipReason.NO_ESCALATION,
        )
    }

    // ------------------------------------------------------ POOR ACCURACY

    @Test
    fun `good accuracy alone never escalates solely for accuracy`() {
        val p = policy()
        p.observeFix(fix(1.0, accuracy = 100f), nowMs = 0L)
        assertSkipped(p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 0L), GpsSkipReason.NO_ESCALATION)
    }

    @Test
    fun `poor accuracy escalates with fine permission`() {
        val p = policy()
        p.observeFix(fix(1.0, accuracy = 200f), nowMs = 0L)
        assertRequested(p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 0L), GpsRequestReason.POOR_ACCURACY)
    }

    @Test
    fun `poor accuracy without fine permission never escalates`() {
        val p = policy()
        p.observeFix(fix(1.0, accuracy = 200f), nowMs = 0L)
        assertSkipped(p.evaluateGps(false, true, hasPublishableFix = true, nowMs = 0L), GpsSkipReason.NO_FINE_PERMISSION)
    }

    @Test
    fun `accuracy at the trigger boundary does not escalate`() {
        val p = policy()
        p.observeFix(fix(1.0, accuracy = AcquisitionPolicy.GPS_ACCURACY_TRIGGER_METERS), nowMs = 0L)
        assertSkipped(p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 0L), GpsSkipReason.NO_ESCALATION)
    }

    @Test
    fun `unknown accuracy is treated as poor quality`() {
        val p = policy()
        p.observeFix(fix(1.0, accuracy = Float.NaN), nowMs = 0L)
        assertRequested(p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 0L), GpsRequestReason.POOR_ACCURACY)
    }

    // -------------------------------------------------------- MAP ACTIVE

    @Test
    fun `map active requests a gps refinement with fine permission`() {
        val p = policy()
        assertRequested(p.evaluateMapActive(true, true, nowMs = 0L), GpsRequestReason.MAP_ACTIVE)
    }

    @Test
    fun `map active respects the cooldown`() {
        val p = policy()
        assertRequested(p.evaluateMapActive(true, true, nowMs = 0L), GpsRequestReason.MAP_ACTIVE)
        p.onGpsStarted(GpsRequestReason.MAP_ACTIVE, nowMs = 0L)
        p.onGpsCompleted(hasFix = true, nowMs = 5_000L)
        assertSkipped(p.evaluateMapActive(true, true, nowMs = 30_000L), GpsSkipReason.COOLDOWN)
    }

    @Test
    fun `map active without fine permission never requests gps`() {
        val p = policy()
        assertSkipped(p.evaluateMapActive(false, true, nowMs = 0L), GpsSkipReason.NO_FINE_PERMISSION)
    }

    // --------------------------------------------- STATIONARY SAFEGUARD

    @Test
    fun `stationary with an acceptable fix never triggers a gps loop as the fix ages`() {
        val p = policy()
        // Acceptable initial fix: context becomes NONE.
        p.observeFix(fix(1.0, accuracy = 40f), nowMs = 0L)
        assertSkipped(p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 0L), GpsSkipReason.NO_ESCALATION)

        // The fix ages past 3 minutes with NO new fixes: the device is
        // stationary and the pending expired — still no GPS.
        assertSkipped(
            p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 200_000L),
            GpsSkipReason.NO_ESCALATION,
        )
        // Ten minutes later: still nothing.
        assertSkipped(
            p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 700_000L),
            GpsSkipReason.NO_ESCALATION,
        )
        assertEquals("zero GPS requests for a stationary device", 0, p.gpsRequestCount)
    }

    @Test
    fun `startup with no acceptable fix may escalate`() {
        val p = policy()
        assertRequested(
            p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 0L),
            GpsRequestReason.NO_PUBLISHABLE_FIX,
        )
    }

    @Test
    fun `repeated startup gps timeouts park the escalation`() {
        val p = policy()
        // Startup: GPS requested at t=0, timed out; cooldown blocks until 60 s.
        assertRequested(p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 0L), GpsRequestReason.NO_PUBLISHABLE_FIX)
        p.onGpsStarted(GpsRequestReason.NO_PUBLISHABLE_FIX, nowMs = 0L)
        p.onGpsCompleted(hasFix = false, nowMs = 20_000L)
        assertSkipped(p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 30_000L), GpsSkipReason.COOLDOWN)

        // Second attempt after the cooldown, times out again.
        assertRequested(p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 60_000L), GpsRequestReason.NO_PUBLISHABLE_FIX)
        p.onGpsStarted(GpsRequestReason.NO_PUBLISHABLE_FIX, nowMs = 60_000L)
        p.onGpsCompleted(hasFix = false, nowMs = 80_000L)

        // Third attempt, times out again -> the startup escalation parks.
        assertRequested(p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 120_000L), GpsRequestReason.NO_PUBLISHABLE_FIX)
        p.onGpsStarted(GpsRequestReason.NO_PUBLISHABLE_FIX, nowMs = 120_000L)
        p.onGpsCompleted(hasFix = false, nowMs = 140_000L)

        // No more requests: the park holds at any later time.
        assertSkipped(p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 600_000L), GpsSkipReason.NO_ESCALATION)
        assertEquals(3, p.gpsRequestCount)
        assertEquals(3, p.gpsFailureCount)
    }

    @Test
    fun `any delivered fix resets the startup failure counter`() {
        val p = policy()
        assertRequested(p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 0L), GpsRequestReason.NO_PUBLISHABLE_FIX)
        p.onGpsStarted(GpsRequestReason.NO_PUBLISHABLE_FIX, nowMs = 0L)
        p.onGpsCompleted(hasFix = false, nowMs = 20_000L)
        assertRequested(p.evaluateGps(true, true, hasPublishableFix = false, nowMs = 60_000L), GpsRequestReason.NO_PUBLISHABLE_FIX)
        p.onGpsStarted(GpsRequestReason.NO_PUBLISHABLE_FIX, nowMs = 60_000L)
        p.onGpsCompleted(hasFix = false, nowMs = 80_000L)

        // A network fix arrives: startup failures reset and the context is
        // driven by the fix quality from now on (after the cooldown has
        // passed — the last GPS request was at t=60 s).
        p.observeFix(fix(1.0, accuracy = 200f), nowMs = 130_000L)
        assertRequested(p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 130_000L), GpsRequestReason.POOR_ACCURACY)
    }

    @Test
    fun `gps success with a good fix settles the context`() {
        val p = policy()
        p.observeFix(fix(1.0, accuracy = 40f), nowMs = 0L)
        assertSkipped(p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 0L), GpsSkipReason.NO_ESCALATION)
    }

    @Test
    fun `observability exposes counts and reasons without coordinates`() {
        val p = policy()
        p.observeFix(fix(1.0, accuracy = 200f), nowMs = 0L)
        assertRequested(p.evaluateGps(true, true, hasPublishableFix = true, nowMs = 0L), GpsRequestReason.POOR_ACCURACY)
        p.onGpsStarted(GpsRequestReason.POOR_ACCURACY, nowMs = 0L)
        p.onGpsCompleted(hasFix = false, nowMs = 20_000L)

        val obs = p.observability()
        assertFalse(obs.gpsInFlight)
        assertEquals(GpsRequestReason.POOR_ACCURACY, obs.lastGpsRequestReason)
        assertEquals(0L, obs.lastGpsRequestAtMs)
        assertEquals(GpsResultStatus.TIMEOUT, obs.lastGpsResult)
        assertEquals(1, obs.gpsRequestCount)
        assertEquals(1, obs.gpsFailureCount)
    }
}