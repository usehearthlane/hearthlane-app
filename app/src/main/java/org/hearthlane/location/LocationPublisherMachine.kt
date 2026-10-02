package org.hearthlane.location

/**
 * Pure V2 publish machine: serialized publish decisions, latest-useful pending,
 * client ordering guard, transport backoff and presence semantics.
 *
 * The machine owns ALL location state and is driven by [MachineCommand]s. Its
 * [step] is a pure transition returning the next state plus [MachineAction]s
 * (publish position / publish presence). The runner (an actor coroutine)
 * executes actions and feeds [MachineCommand.PublishResult] back, so two
 * concurrent state-machine publications can never reorder location state.
 *
 * Time model: [step] receives a monotonic clock (`elapsedRealtime`-derived)
 * `nowMs`. Fix ages combine the age recorded at read time with the time the
 * fix spent waiting in the machine.
 *
 * No Android types. No coordinates ever leave the machine (they are inputs
 * for distance only).
 */
internal enum class PublisherMode {
    BACKGROUND,
    MAP_ACTIVE,
}

/** Stable publish-decision reasons (diagnostics + structured events). */
internal enum class PublishDecisionReason(val publishes: Boolean) {
    FIRST(true),
    MOVEMENT(true),
    MATERIAL_ACCURACY_UPGRADE(true),
    PRESENCE(true),
    STALE(false),
    UNKNOWN_ACCURACY(false),
    BAD_ACCURACY(false),
    BELOW_MOVEMENT_THRESHOLD(false),
    NON_MATERIAL_ACCURACY_UPGRADE(false),
    MIN_INTERVAL(false),
    BACKOFF(false),
    BACKOFF_FLOOR(false),
    ORDERING_REJECTED(false),
    PRESENCE_NOT_DUE(false),
    NO_PENDING(false),
}

/** Stable fix/pending decision reasons (diagnostics + structured events). */
internal enum class FixDecisionReason {
    ACCEPTED_EMPTY,
    ACCEPTED_BETTER_ACCURACY,
    ACCEPTED_MOVEMENT,
    ACCEPTED_EXPIRED_REPLACEMENT,
    REJECTED_STALE,
    REJECTED_UNKNOWN_ACCURACY,
    REJECTED_ORDERING,
    REJECTED_NOT_USEFUL,
}

/** A fix as the machine consumes it (age already measured at read time). */
internal data class PositionFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val recordedAtEpochMs: Long,
    val ageAtReadMs: Long,
)

/** The pending slot: the latest USEFUL fix, never the latest received. */
internal data class PendingFix(
    val fix: PositionFix,
    val arrivedAtMs: Long,
) {
    /** Monotonic age of the fix at [nowMs]. */
    fun ageAt(nowMs: Long): Long = fix.ageAtReadMs + (nowMs - arrivedAtMs).coerceAtLeast(0L)
}

/** The last position that reached the relay, with its monotonic publish time. */
internal data class PublishedPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val recordedAtEpochMs: Long,
    val publishedAtMs: Long,
)

/** Serialized machine state. */
internal data class MachineState(
    val mode: PublisherMode = PublisherMode.BACKGROUND,
    val pending: PendingFix? = null,
    val lastPublished: PublishedPosition? = null,
    /** Consecutive transport failures; drives the backoff sequence. */
    val consecutiveFailures: Int = 0,
    /** Monotonic deadline of the active transport backoff, or null. */
    val backoffUntilMs: Long? = null,
    /**
     * Monotonic time of the last FAILED network attempt. The 60 s backoff
     * floor is measured from this instant, so reset events can anticipate the
     * exponential backoff but never attempt before the floor has passed.
     * Cleared only by a successful attempt.
     */
    val lastFailureAtMs: Long? = null,
    val lastNetworkAttemptAtMs: Long? = null,
    val lastNetworkSuccessAtMs: Long? = null,
    val lastPresenceAtMs: Long? = null,
    val publishCount: Int = 0,
    val presenceCount: Int = 0,
    val lastDecision: PublishDecisionReason? = null,
    val lastPresenceDecision: PublishDecisionReason? = null,
    val lastFixDecision: FixDecisionReason? = null,
    val lastPendingDecision: FixDecisionReason? = null,
)

/** Commands consumed by the machine. */
internal sealed interface MachineCommand {
    /** A freshly read fix (the runner acquired it through the one-shot loop). */
    data class Fix(val fix: PositionFix) : MachineCommand

    /** Periodic time driver: backoff expiry, regular publish, presence. */
    data object Tick : MachineCommand

    /** Explicit request (PUBLISH_NOW / user action). */
    data object PublishNow : MachineCommand

    /** Outcome of an executed action, fed back by the runner. */
    data class PublishResult(
        val outcome: PublishOutcome,
        val kind: PublishKind,
    ) : MachineCommand

    /** ConnectivityManager-confirmed network regained / real transport change. */
    data object NetworkRegained : MachineCommand

    /** Local map opened/closed (the interval-switch signal already exists). */
    data class ModeChanged(val mode: PublisherMode) : MachineCommand
}

internal enum class PublishOutcome { SUCCESS, FAILURE }

internal enum class PublishKind { POSITION, PRESENCE }

/** Actions the runner must execute after a transition. */
internal sealed interface MachineAction {
    data class PublishPosition(val pending: PendingFix) : MachineAction
    data class PublishPresence(val position: PublishedPosition) : MachineAction
}

/** Outcome of a position-publish assessment (decision + event fields). */
internal data class PublishDecision(
    val shouldPublish: Boolean,
    val reason: PublishDecisionReason,
    val distanceMeters: Double? = null,
    val sinceLastAttemptMs: Long? = null,
    val sinceLastSuccessMs: Long? = null,
)

internal data class MachineTransition(
    val state: MachineState,
    val actions: List<MachineAction>,
    /** Position-publish assessment of this step; null when none happened. */
    val decision: PublishDecision? = null,
)

/**
 * Pure publish machine. All decisions derive from [MachineState] + the current
 * command + the monotonic [nowMs].
 */
internal class LocationPublisherMachine(
    private val distanceMeters: (PositionFix, PositionFix) -> Double,
) {

    fun step(state: MachineState, command: MachineCommand, nowMs: Long): MachineTransition =
        when (command) {
            is MachineCommand.Fix -> stepFix(state, command.fix, nowMs)
            MachineCommand.Tick -> stepTick(state, nowMs)
            MachineCommand.PublishNow -> stepResetAndPublish(state, nowMs)
            MachineCommand.NetworkRegained -> stepResetAndPublish(state, nowMs)
            is MachineCommand.ModeChanged -> stepModeChanged(state, command.mode, nowMs)
            is MachineCommand.PublishResult -> stepPublishResult(state, command, nowMs)
        }

    private fun stepFix(state: MachineState, fix: PositionFix, nowMs: Long): MachineTransition {
        val assessment = assessFix(state, fix, nowMs)
        val afterFix = if (assessment.accepted) {
            state.copy(
                pending = PendingFix(fix, nowMs),
                lastPendingDecision = assessment.reason,
            )
        } else {
            state.copy(lastPendingDecision = assessment.reason)
        }.copy(lastFixDecision = assessment.reason)

        val decision = assessPublish(afterFix, nowMs)
        return MachineTransition(
            state = afterFix.withDecision(decision),
            actions = if (decision.shouldPublish) {
                listOf(MachineAction.PublishPosition(afterFix.pending!!))
            } else {
                emptyList()
            },
            decision = decision,
        )
    }

    private fun stepTick(state: MachineState, nowMs: Long): MachineTransition {
        // 1. Housekeeping: a pending that aged past the freshness limit can
        //    never be published and must not block a later replacement.
        var s = state
        val pending = s.pending
        if (pending != null && !LocationPolicy.isFresh(pending.ageAt(nowMs))) {
            s = s.copy(pending = null, lastPendingDecision = FixDecisionReason.REJECTED_STALE)
        }

        // 2. Transport backoff: gate before ANY network attempt.
        val backoff = s.backoffUntilMs
        if (backoff != null) {
            if (nowMs < backoff) {
                // Still backing off: no network, presence deferred.
                return MachineTransition(s, emptyList())
            }
            // The window is over. The failure count is KEPT so a failed retry
            // escalates the sequence (60 s -> 2 min -> ...); it is cleared only
            // when the retry succeeds or when nothing fresh exists to attempt.
            s = s.copy(backoffUntilMs = null)
            val decision = assessPublish(s, nowMs)
            s = s.withDecision(decision)
            if (decision.shouldPublish) {
                return MachineTransition(
                    s,
                    listOf(MachineAction.PublishPosition(s.pending!!)),
                    decision = decision,
                )
            }
            // Nothing fresh to publish: the backoff cycle completed without a
            // network attempt, so the failure count resets.
            return MachineTransition(s.copy(consecutiveFailures = 0), emptyList(), decision = decision)
        }

        // 3. Regular cycle: a fresh pending may now have passed the floor.
        val decision = assessPublish(s, nowMs)
        s = s.withDecision(decision)
        if (decision.shouldPublish) {
            return MachineTransition(
                s,
                listOf(MachineAction.PublishPosition(s.pending!!)),
                decision = decision,
            )
        }

        // 4. Presence (never in the same step as a position publish: the
        //    position success refreshes the presence eligibility window).
        val presence = assessPresence(s, nowMs)
        s = s.copy(lastPresenceDecision = presence.reason)
        return if (presence.shouldPublish) {
            MachineTransition(
                s,
                listOf(MachineAction.PublishPresence(s.lastPublished!!)),
                decision = decision,
            )
        } else {
            MachineTransition(s, emptyList(), decision = decision)
        }
    }

    /** Reset events: PublishNow / NetworkRegained / map opened. */
    private fun stepResetAndPublish(state: MachineState, nowMs: Long): MachineTransition {
        val s = state.copy(consecutiveFailures = 0, backoffUntilMs = null)
        val decision = assessPublish(s, nowMs)
        return MachineTransition(
            state = s.withDecision(decision),
            actions = if (decision.shouldPublish) {
                listOf(MachineAction.PublishPosition(s.pending!!))
            } else {
                emptyList()
            },
            decision = decision,
        )
    }

    private fun stepModeChanged(state: MachineState, mode: PublisherMode, nowMs: Long): MachineTransition =
        if (mode == PublisherMode.BACKGROUND) {
            MachineTransition(state.copy(mode = mode), emptyList())
        } else {
            stepResetAndPublish(state.copy(mode = mode), nowMs)
        }

    private fun stepPublishResult(
        state: MachineState,
        command: MachineCommand.PublishResult,
        nowMs: Long,
    ): MachineTransition = when {
        command.outcome == PublishOutcome.SUCCESS && command.kind == PublishKind.POSITION -> {
            val pending = state.pending ?: return MachineTransition(state, emptyList())
            MachineTransition(
                state = state.copy(
                    pending = null,
                    lastPublished = PublishedPosition(
                        latitude = pending.fix.latitude,
                        longitude = pending.fix.longitude,
                        accuracyMeters = pending.fix.accuracyMeters,
                        recordedAtEpochMs = pending.fix.recordedAtEpochMs,
                        publishedAtMs = nowMs,
                    ),
                    consecutiveFailures = 0,
                    backoffUntilMs = null,
                    lastFailureAtMs = null,
                    lastNetworkAttemptAtMs = nowMs,
                    lastNetworkSuccessAtMs = nowMs,
                    publishCount = state.publishCount + 1,
                ),
                emptyList(),
            )
        }
        command.outcome == PublishOutcome.SUCCESS && command.kind == PublishKind.PRESENCE -> MachineTransition(
            state = state.copy(
                consecutiveFailures = 0,
                backoffUntilMs = null,
                lastFailureAtMs = null,
                lastNetworkAttemptAtMs = nowMs,
                lastNetworkSuccessAtMs = nowMs,
                lastPresenceAtMs = nowMs,
                presenceCount = state.presenceCount + 1,
            ),
            emptyList(),
        )
        else -> {
            val attempt = state.consecutiveFailures + 1
            MachineTransition(
                state = state.copy(
                    consecutiveFailures = attempt,
                    backoffUntilMs = nowMs + LocationPolicy.backoffDelayMs(attempt),
                    lastFailureAtMs = nowMs,
                    lastNetworkAttemptAtMs = nowMs,
                ),
                emptyList(),
            )
        }
    }

    /**
     * Latest-useful pending acceptance. A poor (>300 m) fix may become the
     * pending slot only when nothing better exists; it never overwrites a good
     * fresh pending, and it can never be published.
     */
    private fun assessFix(state: MachineState, fix: PositionFix, nowMs: Long): FixAssessment {
        val published = state.lastPublished
        if (published != null && fix.recordedAtEpochMs < published.recordedAtEpochMs) {
            return FixAssessment.rejected(FixDecisionReason.REJECTED_ORDERING)
        }
        if (!LocationPolicy.isFresh(fix.ageAtReadMs)) {
            return FixAssessment.rejected(FixDecisionReason.REJECTED_STALE)
        }
        if (!LocationPolicy.hasUsableAccuracy(fix.accuracyMeters)) {
            return FixAssessment.rejected(FixDecisionReason.REJECTED_UNKNOWN_ACCURACY)
        }
        val pending = state.pending
        if (pending == null) return FixAssessment.accepted(FixDecisionReason.ACCEPTED_EMPTY)
        if (pending.fix.recordedAtEpochMs > fix.recordedAtEpochMs) {
            return FixAssessment.rejected(FixDecisionReason.REJECTED_ORDERING)
        }
        if (!LocationPolicy.isFresh(pending.ageAt(nowMs))) {
            return FixAssessment.accepted(FixDecisionReason.ACCEPTED_EXPIRED_REPLACEMENT)
        }
        if (fix.accuracyMeters < pending.fix.accuracyMeters) {
            return FixAssessment.accepted(FixDecisionReason.ACCEPTED_BETTER_ACCURACY)
        }
        val distance = distanceMeters(fix, pending.fix)
        val required = LocationPolicy.requiredDistanceMeters(
            fix.accuracyMeters,
            pending.fix.accuracyMeters,
            thresholdForMode(state.mode),
        )
        if (distance > required) {
            return FixAssessment.accepted(FixDecisionReason.ACCEPTED_MOVEMENT)
        }
        return FixAssessment.rejected(FixDecisionReason.REJECTED_NOT_USEFUL)
    }

    /**
     * Position-publish eligibility.
     *
     * Failure/recovery gates (before any data consideration):
     * - an active transport backoff blocks everything;
     * - the 60 s BACKOFF_FLOOR blocks attempts measured from the last FAILED
     *   attempt, so reset events (NetworkRegained/PublishNow/map opened) can
     *   anticipate the exponential backoff but never cause a network attempt
     *   before the floor has passed.
     *
     * Healthy-state gate: position publishes are throttled by
     * MIN_PUBLISH_INTERVAL_MS (30 s) measured from the last SUCCESSFUL
     * position publish. The floor never throttles a healthy pipeline.
     */
    private fun assessPublish(state: MachineState, nowMs: Long): PublishDecision {
        val pending = state.pending ?: return PublishDecision(false, PublishDecisionReason.NO_PENDING)
        val backoff = state.backoffUntilMs
        if (backoff != null && nowMs < backoff) {
            return PublishDecision(false, PublishDecisionReason.BACKOFF)
        }
        val sinceAttempt = state.lastNetworkAttemptAtMs?.let { nowMs - it }
        val sinceSuccess = state.lastNetworkSuccessAtMs?.let { nowMs - it }
        val lastFailure = state.lastFailureAtMs
        if (lastFailure != null && nowMs - lastFailure < LocationPolicy.BACKOFF_FLOOR_MS) {
            return PublishDecision(false, PublishDecisionReason.BACKOFF_FLOOR, sinceLastAttemptMs = sinceAttempt)
        }
        if (!LocationPolicy.isFresh(pending.ageAt(nowMs))) {
            return PublishDecision(
                false,
                PublishDecisionReason.STALE,
                sinceLastAttemptMs = sinceAttempt,
                sinceLastSuccessMs = sinceSuccess,
            )
        }
        val published = state.lastPublished
        if (published != null && pending.fix.recordedAtEpochMs < published.recordedAtEpochMs) {
            return PublishDecision(
                false,
                PublishDecisionReason.ORDERING_REJECTED,
                sinceLastAttemptMs = sinceAttempt,
                sinceLastSuccessMs = sinceSuccess,
            )
        }
        if (!LocationPolicy.hasUsableAccuracy(pending.fix.accuracyMeters)) {
            return PublishDecision(
                false,
                PublishDecisionReason.UNKNOWN_ACCURACY,
                sinceLastAttemptMs = sinceAttempt,
                sinceLastSuccessMs = sinceSuccess,
            )
        }
        if (!LocationPolicy.isPublishableAccuracy(pending.fix.accuracyMeters)) {
            return PublishDecision(
                false,
                PublishDecisionReason.BAD_ACCURACY,
                sinceLastAttemptMs = sinceAttempt,
                sinceLastSuccessMs = sinceSuccess,
            )
        }
        if (published == null) {
            return PublishDecision(
                true,
                PublishDecisionReason.FIRST,
                sinceLastAttemptMs = sinceAttempt,
                sinceLastSuccessMs = sinceSuccess,
            )
        }
        val sincePositionPublish = nowMs - published.publishedAtMs
        if (sincePositionPublish < LocationPolicy.MIN_PUBLISH_INTERVAL_MS) {
            return PublishDecision(
                false,
                PublishDecisionReason.MIN_INTERVAL,
                sinceLastAttemptMs = sinceAttempt,
                sinceLastSuccessMs = sinceSuccess,
            )
        }
        val distance = distanceMeters(pending.fix, published.toFix())
        val required = LocationPolicy.requiredDistanceMeters(
            pending.fix.accuracyMeters,
            published.accuracyMeters,
            thresholdForMode(state.mode),
        )
        if (distance > required) {
            return PublishDecision(
                true,
                PublishDecisionReason.MOVEMENT,
                distanceMeters = distance,
                sinceLastAttemptMs = sinceAttempt,
                sinceLastSuccessMs = sinceSuccess,
            )
        }
        if (LocationPolicy.isMaterialAccuracyImprovement(published.accuracyMeters, pending.fix.accuracyMeters)) {
            return PublishDecision(
                true,
                PublishDecisionReason.MATERIAL_ACCURACY_UPGRADE,
                distanceMeters = distance,
                sinceLastAttemptMs = sinceAttempt,
                sinceLastSuccessMs = sinceSuccess,
            )
        }
        if (pending.fix.accuracyMeters < published.accuracyMeters) {
            return PublishDecision(
                false,
                PublishDecisionReason.NON_MATERIAL_ACCURACY_UPGRADE,
                distanceMeters = distance,
                sinceLastAttemptMs = sinceAttempt,
                sinceLastSuccessMs = sinceSuccess,
            )
        }
        return PublishDecision(
            false,
            PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD,
            distanceMeters = distance,
            sinceLastAttemptMs = sinceAttempt,
            sinceLastSuccessMs = sinceSuccess,
        )
    }

    /**
     * Presence eligibility. Presence re-PUTs the last successfully published
     * position unchanged (identical recordedAtEpochMs); it is best-effort,
     * deferred during backoff and redundant within
     * [LocationPolicy.PRESENCE_ELIGIBLE_AGE_MS] of any network success.
     */
    private fun assessPresence(state: MachineState, nowMs: Long): PublishDecision {
        val published = state.lastPublished
            ?: return PublishDecision(false, PublishDecisionReason.PRESENCE_NOT_DUE)
        val backoff = state.backoffUntilMs
        if (backoff != null && nowMs < backoff) {
            return PublishDecision(false, PublishDecisionReason.PRESENCE_NOT_DUE)
        }
        val lastFailure = state.lastFailureAtMs
        if (lastFailure != null && nowMs - lastFailure < LocationPolicy.BACKOFF_FLOOR_MS) {
            return PublishDecision(false, PublishDecisionReason.PRESENCE_NOT_DUE)
        }
        val sinceAttempt = state.lastNetworkAttemptAtMs?.let { nowMs - it }
        if (sinceAttempt != null && sinceAttempt < LocationPolicy.BACKOFF_FLOOR_MS) {
            return PublishDecision(false, PublishDecisionReason.PRESENCE_NOT_DUE, sinceLastAttemptMs = sinceAttempt)
        }
        val sinceSuccess = state.lastNetworkSuccessAtMs?.let { nowMs - it } ?: Long.MAX_VALUE
        if (sinceSuccess < LocationPolicy.PRESENCE_ELIGIBLE_AGE_MS) {
            return PublishDecision(false, PublishDecisionReason.PRESENCE_NOT_DUE, sinceLastSuccessMs = sinceSuccess)
        }
        val sincePresence = state.lastPresenceAtMs?.let { nowMs - it } ?: Long.MAX_VALUE
        if (sincePresence < LocationPolicy.PRESENCE_INTERVAL_MS) {
            return PublishDecision(false, PublishDecisionReason.PRESENCE_NOT_DUE)
        }
        return PublishDecision(
            true,
            PublishDecisionReason.PRESENCE,
            sinceLastAttemptMs = sinceAttempt,
            sinceLastSuccessMs = sinceSuccess,
        )
    }

    private fun MachineState.withDecision(decision: PublishDecision): MachineState =
        // NO_PENDING is "no data to decide on", not a decision about data:
        // it must never overwrite the last meaningful decision in Diagnostics.
        if (decision.reason == PublishDecisionReason.NO_PENDING) this
        else copy(lastDecision = decision.reason)

    private fun PublishedPosition.toFix(): PositionFix = PositionFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        recordedAtEpochMs = recordedAtEpochMs,
        ageAtReadMs = 0L,
    )

    private fun thresholdForMode(mode: PublisherMode): Double = when (mode) {
        PublisherMode.BACKGROUND -> LocationPolicy.DISTANCE_THRESHOLD_METERS
        PublisherMode.MAP_ACTIVE -> LocationPolicy.MAP_ACTIVE_DISTANCE_THRESHOLD_METERS
    }

    private data class FixAssessment(val accepted: Boolean, val reason: FixDecisionReason) {
        companion object {
            fun accepted(reason: FixDecisionReason) = FixAssessment(true, reason)
            fun rejected(reason: FixDecisionReason) = FixAssessment(false, reason)
        }
    }
}