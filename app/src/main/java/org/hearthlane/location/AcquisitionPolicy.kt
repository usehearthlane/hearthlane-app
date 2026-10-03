package org.hearthlane.location

/**
 * Pure V2 adaptive-acquisition policy: provider eligibility, GPS escalation,
 * cooldown and the stationary GNSS safeguard. No Android types.
 *
 * Responsibilities are strictly separated from the publish machine:
 * - THIS policy decides PROVIDER USAGE and GPS ESCALATION;
 * - the publish machine (Phase 2) decides whether a fix is useful, the pending
 *   and whether to publish.
 *
 * GPS is always ONE-SHOT: this policy never registers a permanent GPS listener
 * and its decisions are bounded by a cooldown and an in-flight guard.
 *
 * STATIONARY SAFEGUARD: an escalation context is created ONLY by meaningful
 * activity — acquisition startup with no fix yet, separated accuracy circles
 * (movement evidence), a fresh poor-accuracy fix, or a map activation. A fix
 * simply AGING while the device stays stationary never creates an escalation
 * context, so a stationary device can never trigger a GPS-every-minute loop.
 */
internal enum class EscalationContext {
    /** Stationary with an acceptable fix: aging alone must not escalate. */
    NONE,

    /** Acquisition started and no fix was ever delivered yet. */
    STARTUP,

    /** Two fixes separated accuracy circles: likely real movement. */
    MOVEMENT,

    /** The last fresh fix was poor (accuracy above the trigger or unknown). */
    POOR_ACCURACY,
}

internal enum class GpsRequestReason {
    MOVEMENT_CONFIRMATION,
    NO_PUBLISHABLE_FIX,
    POOR_ACCURACY,
    MAP_ACTIVE,
}

internal enum class GpsSkipReason {
    NO_FINE_PERMISSION,
    COOLDOWN,
    ALREADY_IN_FLIGHT,
    PROVIDER_DISABLED,
    NO_ESCALATION,
}

/** A fix as the acquisition policy observes it (pure, no coordinates in events). */
internal data class FixObserved(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
)

internal data class GpsDecision(
    val request: Boolean,
    val reason: GpsRequestReason? = null,
    val skipReason: GpsSkipReason? = null,
) {
    companion object {
        fun request(reason: GpsRequestReason) = GpsDecision(true, reason = reason)
        fun skip(reason: GpsSkipReason) = GpsDecision(false, skipReason = reason)
    }
}

/**
 * Stateful pure policy. All methods take an explicit monotonic [nowMs] so the
 * decisions are deterministic and JVM-testable.
 */
internal class AcquisitionPolicy(
    private val distanceMeters: (FixObserved, FixObserved) -> Double,
) {

    companion object {
        /** NETWORK listener: delivery floor (request parameter, not a power claim). */
        const val NETWORK_MIN_TIME_MS = 30_000L

        /** NETWORK listener: distance gate. */
        const val NETWORK_MIN_DISTANCE_METERS = 100.0

        /** Maximum wait for a one-shot GPS fix. */
        const val GPS_TIMEOUT_MS = 20_000L

        /** Minimum gap between GPS one-shots. */
        const val GPS_MIN_INTERVAL_MS = 60_000L

        /** Best fresh fix accuracy above this justifies a GPS refinement. */
        const val GPS_ACCURACY_TRIGGER_METERS = 150f

        /** Maximum wait for the initial NETWORK one-shot fix. */
        const val INITIAL_FIX_TIMEOUT_MS = 10_000L

        /**
         * How long a movement/poor-accuracy escalation context stays valid
         * without new evidence. Bounds the number of GPS attempts per evidence
         * burst even when the cooldown never blocks.
         */
        const val ESCALATION_CONTEXT_TTL_MS = 3 * 60_000L

        /**
         * Consecutive failed GPS attempts at startup (no fix ever delivered)
         * after which the startup escalation parks itself. Prevents an
         * unbounded no-position GPS retry loop in a location-blind
         * environment.
         */
        const val GPS_MAX_STARTUP_FAILURES = 3
    }

    private var escalationContext = EscalationContext.STARTUP
    private var contextObservedAtMs: Long = 0L
    private var previousFix: FixObserved? = null
    private var lastGpsRequestAtMs: Long? = null
    private var gpsInFlight = false
    private var startupGpsFailures = 0
    private var requestCount = 0
    private var failureCount = 0
    private var lastRequestReason: GpsRequestReason? = null
    private var lastGpsResultStatus: GpsResultStatus? = null

    val gpsRequestCount: Int get() = requestCount
    val gpsFailureCount: Int get() = failureCount
    val gpsRequestInFlight: Boolean get() = gpsInFlight
    val lastGpsRequestTimeMs: Long? get() = lastGpsRequestAtMs
    val lastGpsRequestReason: GpsRequestReason? get() = lastRequestReason

    /** Whether the current escalation context (after decay) is non-trivial. */
    private fun activeContext(nowMs: Long): EscalationContext {
        if (escalationContext == EscalationContext.NONE ||
            escalationContext == EscalationContext.STARTUP
        ) {
            return escalationContext
        }
        return if (nowMs - contextObservedAtMs <= ESCALATION_CONTEXT_TTL_MS) {
            escalationContext
        } else {
            EscalationContext.NONE
        }
    }

    /** Observes a delivered fix (NETWORK, PASSIVE or GPS) and updates context. */
    fun observeFix(fix: FixObserved, nowMs: Long) {
        startupGpsFailures = 0
        val previous = previousFix
        previousFix = fix
        val moved = previous != null &&
            hasUsable(previous.accuracyMeters) &&
            hasUsable(fix.accuracyMeters) &&
            distanceMeters(previous, fix) > previous.accuracyMeters + fix.accuracyMeters
        escalationContext = when {
            moved -> EscalationContext.MOVEMENT
            !hasUsable(fix.accuracyMeters) ||
                fix.accuracyMeters > GPS_ACCURACY_TRIGGER_METERS ->
                EscalationContext.POOR_ACCURACY
            else -> EscalationContext.NONE
        }
        contextObservedAtMs = nowMs
    }

    /**
     * General GPS evaluation: gates (permission, provider, in-flight,
     * cooldown) then the escalation triggers. Called after every observed fix
     * and periodically by the controller.
     */
    fun evaluateGps(
        hasFinePermission: Boolean,
        gpsEnabled: Boolean,
        hasPublishableFix: Boolean,
        nowMs: Long,
    ): GpsDecision {
        val gate = gated(hasFinePermission, gpsEnabled, nowMs)
        if (gate != null) return gate
        val active = activeContext(nowMs)
        val reason = when {
            !hasPublishableFix && active != EscalationContext.NONE ->
                GpsRequestReason.NO_PUBLISHABLE_FIX
            active == EscalationContext.MOVEMENT -> GpsRequestReason.MOVEMENT_CONFIRMATION
            active == EscalationContext.POOR_ACCURACY -> GpsRequestReason.POOR_ACCURACY
            else -> return GpsDecision.skip(GpsSkipReason.NO_ESCALATION)
        }
        return GpsDecision.request(reason)
    }

    /**
     * Edge-triggered map-activation evaluation: opening the LOCAL map requests
     * one GPS refinement, subject only to the gates (never a level-triggered
     * per-minute loop while the map stays open).
     */
    fun evaluateMapActive(hasFinePermission: Boolean, gpsEnabled: Boolean, nowMs: Long): GpsDecision {
        val gate = gated(hasFinePermission, gpsEnabled, nowMs)
        return gate ?: GpsDecision.request(GpsRequestReason.MAP_ACTIVE)
    }

    /** Marks a GPS one-shot as started (cooldown anchor + in-flight guard). */
    fun onGpsStarted(reason: GpsRequestReason, nowMs: Long) {
        gpsInFlight = true
        lastGpsRequestAtMs = nowMs
        lastRequestReason = reason
        requestCount++
    }

    /**
     * Marks the GPS one-shot as finished. A fix updates the context through
     * [observeFix] (the controller feeds it); a failed attempt counts a
     * failure and, at startup, parks the escalation after
     * [GPS_MAX_STARTUP_FAILURES] consecutive misses.
     */
    fun onGpsCompleted(hasFix: Boolean, nowMs: Long) {
        gpsInFlight = false
        lastGpsResultStatus = if (hasFix) GpsResultStatus.SUCCESS else GpsResultStatus.TIMEOUT
        if (hasFix) return
        failureCount++
        if (escalationContext == EscalationContext.STARTUP) {
            startupGpsFailures++
            if (startupGpsFailures >= GPS_MAX_STARTUP_FAILURES) {
                escalationContext = EscalationContext.NONE
                contextObservedAtMs = nowMs
            }
        }
    }

    /** Cancellation path: clears the in-flight guard without counting a result. */
    fun onGpsAborted() {
        gpsInFlight = false
    }

    fun observability(): AcquisitionObservability = AcquisitionObservability(
        gpsInFlight = gpsInFlight,
        lastGpsRequestReason = lastRequestReason,
        lastGpsRequestAtMs = lastGpsRequestAtMs,
        lastGpsResult = lastGpsResultStatus,
        gpsRequestCount = requestCount,
        gpsFailureCount = failureCount,
    )

    private fun gated(
        hasFinePermission: Boolean,
        gpsEnabled: Boolean,
        nowMs: Long,
    ): GpsDecision? = when {
        !hasFinePermission -> GpsDecision.skip(GpsSkipReason.NO_FINE_PERMISSION)
        !gpsEnabled -> GpsDecision.skip(GpsSkipReason.PROVIDER_DISABLED)
        gpsInFlight -> GpsDecision.skip(GpsSkipReason.ALREADY_IN_FLIGHT)
        else -> {
            val since = lastGpsRequestAtMs?.let { nowMs - it } ?: Long.MAX_VALUE
            if (since < GPS_MIN_INTERVAL_MS) {
                GpsDecision.skip(GpsSkipReason.COOLDOWN)
            } else {
                null
            }
        }
    }

    private fun hasUsable(accuracyMeters: Float): Boolean =
        !accuracyMeters.isNaN() && accuracyMeters > 0f
}

internal enum class GpsResultStatus { SUCCESS, TIMEOUT }

/** Sanitized acquisition observability (no coordinates, no payloads). */
internal data class AcquisitionObservability(
    val gpsInFlight: Boolean = false,
    val lastGpsRequestReason: GpsRequestReason? = null,
    val lastGpsRequestAtMs: Long? = null,
    val lastGpsResult: GpsResultStatus? = null,
    val gpsRequestCount: Int = 0,
    val gpsFailureCount: Int = 0,
)