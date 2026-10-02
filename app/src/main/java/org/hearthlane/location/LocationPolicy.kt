package org.hearthlane.location

/**
 * Pure V2 location-publishing policies: constants and decision helpers for
 * freshness, accuracy, movement plausibility and backoff. No Android types.
 *
 * All freshness/backoff decisions must use monotonic time (the machine passes
 * `elapsedRealtime`-derived ages); wall-clock `recordedAtEpochMs` is payload
 * data for the relay, never a local freshness input.
 */
internal object LocationPolicy {

    /** A fix older than this is never published as a position. */
    const val MAX_FIX_AGE_FOR_PUBLISH_MS = 3 * 60_000L

    /** Worst accuracy ever published as a position. */
    const val MAX_ACCURACY_PUBLISH_METERS = 300f

    /** Movement threshold in the background. */
    const val DISTANCE_THRESHOLD_METERS = 100.0

    /** Movement threshold while the local map is open. */
    const val MAP_ACTIVE_DISTANCE_THRESHOLD_METERS = 50.0

    /** Minimum gap between material accuracy-upgrade publishes. */
    const val MIN_PUBLISH_INTERVAL_MS = 30_000L

    /** Absolute accuracy bound for a material improvement. */
    const val MATERIAL_ACCURACY_METERS = 50f

    /** Presence cadence (best-effort device seen). */
    const val PRESENCE_INTERVAL_MS = 15 * 60_000L

    /** Presence is redundant within this window after any network success. */
    const val PRESENCE_ELIGIBLE_AGE_MS = 5 * 60_000L

    /** Absolute minimum gap between network attempts (flapping guard). */
    const val BACKOFF_FLOOR_MS = 60_000L

    /** Freshness gate: age at or below the limit is fresh. */
    fun isFresh(ageMs: Long): Boolean = ageMs <= MAX_FIX_AGE_FOR_PUBLISH_MS

    /** Whether an accuracy value is known and usable (positive, not NaN). */
    fun hasUsableAccuracy(accuracyMeters: Float): Boolean =
        !accuracyMeters.isNaN() && accuracyMeters > 0f

    /** Whether an accuracy may be published as a position. */
    fun isPublishableAccuracy(accuracyMeters: Float): Boolean =
        hasUsableAccuracy(accuracyMeters) && accuracyMeters <= MAX_ACCURACY_PUBLISH_METERS

    /**
     * Material accuracy improvement: the candidate is at most 50 m AND at
     * least twice as accurate as the published fix. Smaller improvements do
     * not justify a remote round-trip.
     */
    fun isMaterialAccuracyImprovement(
        publishedAccuracyMeters: Float,
        candidateAccuracyMeters: Float,
    ): Boolean =
        hasUsableAccuracy(publishedAccuracyMeters) &&
            hasUsableAccuracy(candidateAccuracyMeters) &&
            candidateAccuracyMeters <= MATERIAL_ACCURACY_METERS &&
            candidateAccuracyMeters < publishedAccuracyMeters / 2f

    /**
     * Movement required for a publish (or a pending replacement): the
     * configured threshold expanded by BOTH accuracies, so two error circles
     * must no longer overlap before movement is believed.
     */
    fun requiredDistanceMeters(
        candidateAccuracyMeters: Float?,
        publishedAccuracyMeters: Float?,
        distanceThresholdMeters: Double,
    ): Double = maxOf(
        distanceThresholdMeters,
        usableOrZero(candidateAccuracyMeters),
        usableOrZero(publishedAccuracyMeters),
    )

    /**
     * Backoff delay for the [attempt]-th consecutive failure: 60 s, 2 min,
     * 4 min, 8 min, then capped at 15 min.
     */
    fun backoffDelayMs(attempt: Int): Long = when {
        attempt <= 0 -> 0L
        attempt >= 5 -> 15 * 60_000L
        else -> 60_000L shl (attempt - 1)
    }

    private fun usableOrZero(accuracyMeters: Float?): Double =
        if (accuracyMeters != null && hasUsableAccuracy(accuracyMeters)) {
            accuracyMeters.toDouble()
        } else {
            0.0
        }
}