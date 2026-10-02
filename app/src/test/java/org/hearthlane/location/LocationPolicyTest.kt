package org.hearthlane.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure V2 policy tests: freshness limits, accuracy classes, the material
 * accuracy-improvement rule, the accuracy-expanded movement threshold and the
 * transport backoff sequence.
 */
class LocationPolicyTest {

    @Test
    fun `freshness accepts fixes at the exact limit and rejects beyond it`() {
        assertTrue(LocationPolicy.isFresh(0L))
        assertTrue(LocationPolicy.isFresh(LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS))
        assertFalse(LocationPolicy.isFresh(LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS + 1L))
    }

    @Test
    fun `unknown accuracy is rejected`() {
        assertFalse(LocationPolicy.hasUsableAccuracy(Float.NaN))
        assertFalse(LocationPolicy.hasUsableAccuracy(0f))
        assertFalse(LocationPolicy.hasUsableAccuracy(-1f))
        assertTrue(LocationPolicy.hasUsableAccuracy(1f))
    }

    @Test
    fun `accuracy above the publish cap is not publishable`() {
        assertTrue(LocationPolicy.isPublishableAccuracy(LocationPolicy.MAX_ACCURACY_PUBLISH_METERS))
        assertFalse(LocationPolicy.isPublishableAccuracy(300.5f))
        assertFalse(LocationPolicy.isPublishableAccuracy(Float.NaN))
        assertFalse(LocationPolicy.isPublishableAccuracy(0f))
    }

    @Test
    fun `material improvement requires fifty meters or better and twice the precision`() {
        assertTrue("200 m -> 20 m is material", LocationPolicy.isMaterialAccuracyImprovement(200f, 20f))
        assertFalse("15 m -> 10 m is not material", LocationPolicy.isMaterialAccuracyImprovement(15f, 10f))
        assertFalse("200 m -> 100 m is not material", LocationPolicy.isMaterialAccuracyImprovement(200f, 100f))
        assertTrue("200 m -> 50 m is material at the boundary", LocationPolicy.isMaterialAccuracyImprovement(200f, 50f))
        assertFalse("40 m -> 20 m is only a halving, not a doubling", LocationPolicy.isMaterialAccuracyImprovement(40f, 20f))
        assertFalse("NaN candidates are never material", LocationPolicy.isMaterialAccuracyImprovement(200f, Float.NaN))
        assertFalse("zero candidates are never material", LocationPolicy.isMaterialAccuracyImprovement(200f, 0f))
    }

    @Test
    fun `movement threshold expands with the candidate accuracy`() {
        assertEquals(
            200.0,
            LocationPolicy.requiredDistanceMeters(200f, 10f, LocationPolicy.DISTANCE_THRESHOLD_METERS),
            0.0,
        )
    }

    @Test
    fun `movement threshold expands with the published accuracy`() {
        assertEquals(
            200.0,
            LocationPolicy.requiredDistanceMeters(10f, 200f, LocationPolicy.DISTANCE_THRESHOLD_METERS),
            0.0,
        )
    }

    @Test
    fun `movement threshold never falls below the configured threshold`() {
        assertEquals(
            LocationPolicy.DISTANCE_THRESHOLD_METERS,
            LocationPolicy.requiredDistanceMeters(10f, 10f, LocationPolicy.DISTANCE_THRESHOLD_METERS),
            0.0,
        )
        assertEquals(
            LocationPolicy.MAP_ACTIVE_DISTANCE_THRESHOLD_METERS,
            LocationPolicy.requiredDistanceMeters(10f, 10f, LocationPolicy.MAP_ACTIVE_DISTANCE_THRESHOLD_METERS),
            0.0,
        )
    }

    @Test
    fun `unusable accuracies do not expand the movement threshold`() {
        assertEquals(
            LocationPolicy.DISTANCE_THRESHOLD_METERS,
            LocationPolicy.requiredDistanceMeters(null, Float.NaN, LocationPolicy.DISTANCE_THRESHOLD_METERS),
            0.0,
        )
    }

    @Test
    fun `backoff sequence doubles from sixty seconds to the fifteen minute cap`() {
        assertEquals(0L, LocationPolicy.backoffDelayMs(0))
        assertEquals(60_000L, LocationPolicy.backoffDelayMs(1))
        assertEquals(120_000L, LocationPolicy.backoffDelayMs(2))
        assertEquals(240_000L, LocationPolicy.backoffDelayMs(3))
        assertEquals(480_000L, LocationPolicy.backoffDelayMs(4))
        assertEquals(900_000L, LocationPolicy.backoffDelayMs(5))
        assertEquals("the cap holds for every later attempt", 900_000L, LocationPolicy.backoffDelayMs(6))
        assertEquals(900_000L, LocationPolicy.backoffDelayMs(50))
    }

    @Test
    fun `publishable reasons are classified by the publishes property`() {
        assertTrue(PublishDecisionReason.FIRST.publishes)
        assertTrue(PublishDecisionReason.MOVEMENT.publishes)
        assertTrue(PublishDecisionReason.MATERIAL_ACCURACY_UPGRADE.publishes)
        assertTrue(PublishDecisionReason.PRESENCE.publishes)
        assertFalse(PublishDecisionReason.STALE.publishes)
        assertFalse(PublishDecisionReason.UNKNOWN_ACCURACY.publishes)
        assertFalse(PublishDecisionReason.BAD_ACCURACY.publishes)
        assertFalse(PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD.publishes)
        assertFalse(PublishDecisionReason.NON_MATERIAL_ACCURACY_UPGRADE.publishes)
        assertFalse(PublishDecisionReason.MIN_INTERVAL.publishes)
        assertFalse(PublishDecisionReason.BACKOFF.publishes)
        assertFalse(PublishDecisionReason.BACKOFF_FLOOR.publishes)
        assertFalse(PublishDecisionReason.ORDERING_REJECTED.publishes)
        assertFalse(PublishDecisionReason.PRESENCE_NOT_DUE.publishes)
        assertFalse(PublishDecisionReason.NO_PENDING.publishes)
    }
}