package org.hearthlane.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure [shouldPublish] adaptive decision: time + distance based
 * publishing with a jitter guard, never tracking and never a history. Each
 * case asserts BOTH the Boolean outcome (behavioral equivalence with the
 * previous Boolean API) and the reason that explains it.
 */
class PublishPolicyTest {

    private val minInterval = 30_000L
    private val threshold = 100.0
    private val maxInterval = 5 * 60_000L

    private fun decide(
        nowMs: Long = 0L,
        neverPublished: Boolean = false,
        lastPublishAtMs: Long? = 0L,
        lastPublishAttemptAtMs: Long? = null,
        distance: Double? = 0.0,
        accuracy: Float? = 10f,
    ) = shouldPublish(
        nowMs = nowMs,
        neverPublished = neverPublished,
        lastPublishAtMs = lastPublishAtMs,
        lastPublishAttemptAtMs = lastPublishAttemptAtMs,
        distanceFromLastPublishedMeters = distance,
        pendingAccuracyMeters = accuracy,
        minPublishIntervalMs = minInterval,
        distanceThresholdMeters = threshold,
        maxPublishIntervalMs = maxInterval,
    )

    @Test
    fun `never published publishes with reason FIRST`() {
        val decision = decide(neverPublished = true, distance = null)
        assertTrue(decision.shouldPublish)
        assertEquals(PublishDecisionReason.FIRST, decision.reason)
    }

    @Test
    fun `thirty seconds with small movement does not publish with reason BELOW_MOVEMENT_THRESHOLD`() {
        // The helper leaves lastPublishAttemptAtMs null, so the minimum-interval
        // gate passes and the small movement lands below the threshold.
        val decision = decide(nowMs = 30_000L, distance = 20.0)
        assertFalse(decision.shouldPublish)
        assertEquals(PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD, decision.reason)
    }

    @Test
    fun `sixty seconds with large movement publishes with reason MOVEMENT`() {
        val decision = decide(nowMs = 60_000L, distance = 150.0)
        assertTrue(decision.shouldPublish)
        assertEquals(PublishDecisionReason.MOVEMENT, decision.reason)
    }

    @Test
    fun `four minutes without movement does not publish with reason BELOW_MOVEMENT_THRESHOLD`() {
        val decision = decide(nowMs = 4 * 60_000L, distance = 0.0)
        assertFalse(decision.shouldPublish)
        assertEquals(PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD, decision.reason)
    }

    @Test
    fun `five minutes without movement publishes via the max interval with reason MAX_INTERVAL`() {
        val decision = decide(nowMs = 5 * 60_000L, distance = 0.0)
        assertTrue(decision.shouldPublish)
        assertEquals(PublishDecisionReason.MAX_INTERVAL, decision.reason)
    }

    @Test
    fun `jitter within the accuracy does not publish with reason BELOW_MOVEMENT_THRESHOLD`() {
        // 80 m of apparent movement with 100 m accuracy is GPS noise.
        val decision = decide(nowMs = 60_000L, distance = 80.0, accuracy = 100f)
        assertFalse(decision.shouldPublish)
        assertEquals(PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD, decision.reason)
    }

    @Test
    fun `movement beyond both threshold and accuracy publishes with reason MOVEMENT`() {
        val decision = decide(nowMs = 60_000L, distance = 120.0, accuracy = 100f)
        assertTrue(decision.shouldPublish)
        assertEquals(PublishDecisionReason.MOVEMENT, decision.reason)
    }

    @Test
    fun `minimum publish interval is respected even with movement with reason MIN_INTERVAL`() {
        val decision = decide(nowMs = 29_999L, distance = 500.0, lastPublishAttemptAtMs = 0L)
        assertFalse(decision.shouldPublish)
        assertEquals(PublishDecisionReason.MIN_INTERVAL, decision.reason)
    }

    @Test
    fun `a location without accuracy uses the plain threshold`() {
        val below = decide(nowMs = 60_000L, distance = 80.0, accuracy = null)
        assertFalse(below.shouldPublish)
        assertEquals(PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD, below.reason)

        val above = decide(nowMs = 60_000L, distance = 120.0, accuracy = null)
        assertTrue(above.shouldPublish)
        assertEquals(PublishDecisionReason.MOVEMENT, above.reason)
    }

    @Test
    fun `missing distance without a first publish has reason NO_DISTANCE`() {
        val decision = decide(neverPublished = false, lastPublishAtMs = 0L, distance = null)
        assertFalse(decision.shouldPublish)
        assertEquals(PublishDecisionReason.NO_DISTANCE, decision.reason)
    }

    @Test
    fun `first attempt without a previous success is treated as FIRST`() {
        val decision = decide(
            neverPublished = true,
            lastPublishAtMs = null,
            lastPublishAttemptAtMs = null,
            distance = null,
        )
        assertTrue(decision.shouldPublish)
        assertEquals(PublishDecisionReason.FIRST, decision.reason)
    }

    @Test
    fun `publishing reasons are classified by the publishes property`() {
        assertTrue(PublishDecisionReason.FIRST.publishes)
        assertTrue(PublishDecisionReason.MAX_INTERVAL.publishes)
        assertTrue(PublishDecisionReason.MOVEMENT.publishes)
        assertFalse(PublishDecisionReason.MIN_INTERVAL.publishes)
        assertFalse(PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD.publishes)
        assertFalse(PublishDecisionReason.NO_DISTANCE.publishes)
        assertFalse(PublishDecisionReason.NO_FIX.publishes)
    }
}