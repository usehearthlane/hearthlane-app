package org.hearthlane.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure [LocationPublisherMachine] tests: freshness gates, accuracy classes,
 * material improvement, movement plausibility, ordering protection, pending
 * replacement, backoff sequence/cap/reset/floor, serialized decisions and
 * presence semantics. All times are monotonic test clocks.
 */
class LocationPublisherMachineTest {

    private val machine = LocationPublisherMachine(::distanceMeters)

    /** Deterministic distance: 1 degree of latitude ~ 111 km. */
    private fun distanceMeters(a: PositionFix, b: PositionFix): Double =
        abs(a.latitude - b.latitude) * 111_320.0

    private fun fix(
        latitude: Double = 1.0,
        recordedAt: Long = 1L,
        accuracy: Float = 20f,
        ageMs: Long = 0L,
    ) = PositionFix(latitude, 0.0, accuracy, recordedAt, ageMs)

    private fun published(
        latitude: Double = 1.0,
        recordedAt: Long = 1L,
        accuracy: Float = 20f,
        publishedAtMs: Long = 0L,
    ) = PublishedPosition(latitude, 0.0, accuracy, recordedAt, publishedAtMs)

    private fun assertPublishesPosition(
        transition: MachineTransition,
        reason: PublishDecisionReason,
    ): PendingFix {
        assertEquals(1, transition.actions.size)
        val action = transition.actions.single()
        assertTrue("must be a position publish", action is MachineAction.PublishPosition)
        assertEquals(reason, transition.decision?.reason)
        assertTrue(transition.decision!!.shouldPublish)
        return (action as MachineAction.PublishPosition).pending
    }

    private fun assertSkips(transition: MachineTransition, reason: PublishDecisionReason) {
        assertTrue("no actions expected", transition.actions.isEmpty())
        assertEquals(reason, transition.decision?.reason)
        assertFalse(transition.decision!!.shouldPublish)
    }

    // ---------------------------------------------------------------- FIRST

    @Test
    fun `fresh valid first fix publishes with reason FIRST`() {
        val transition = machine.step(MachineState(), MachineCommand.Fix(fix()), nowMs = 0L)
        val pending = assertPublishesPosition(transition, PublishDecisionReason.FIRST)
        assertEquals(1.0, pending.fix.latitude, 0.0)
        assertEquals(FixDecisionReason.ACCEPTED_EMPTY, transition.state.lastFixDecision)
    }

    @Test
    fun `stale first fix is rejected and never becomes pending`() {
        val stale = fix(ageMs = LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS + 1L)
        val transition = machine.step(MachineState(), MachineCommand.Fix(stale), nowMs = 0L)

        assertTrue(transition.actions.isEmpty())
        assertNull("a stale fix must not become pending", transition.state.pending)
        assertEquals(FixDecisionReason.REJECTED_STALE, transition.state.lastFixDecision)
    }

    @Test
    fun `first fix at the exact freshness limit publishes`() {
        val atLimit = fix(ageMs = LocationPolicy.MAX_FIX_AGE_FOR_PUBLISH_MS)
        val transition = machine.step(MachineState(), MachineCommand.Fix(atLimit), nowMs = 0L)
        assertPublishesPosition(transition, PublishDecisionReason.FIRST)
    }

    @Test
    fun `unknown accuracy first fix is rejected`() {
        for (bad in listOf(Float.NaN, 0f, -5f)) {
            val transition = machine.step(
                MachineState(),
                MachineCommand.Fix(fix(accuracy = bad)),
                nowMs = 0L,
            )
            assertTrue(transition.actions.isEmpty())
            assertNull(transition.state.pending)
            assertEquals(FixDecisionReason.REJECTED_UNKNOWN_ACCURACY, transition.state.lastFixDecision)
        }
    }

    @Test
    fun `bad accuracy first fix becomes pending but is never published`() {
        val transition = machine.step(
            MachineState(),
            MachineCommand.Fix(fix(accuracy = 400f)),
            nowMs = 0L,
        )

        assertTrue("a poor fix may be pending when nothing better exists", transition.state.pending != null)
        assertSkips(transition, PublishDecisionReason.BAD_ACCURACY)
    }

    // ----------------------------------------------------------- PENDING

    @Test
    fun `better accuracy replaces the pending`() {
        val first = machine.step(MachineState(), MachineCommand.Fix(fix(accuracy = 200f)), nowMs = 0L).state
        val better = machine.step(first, MachineCommand.Fix(fix(accuracy = 20f)), nowMs = 1_000L)

        assertEquals(FixDecisionReason.ACCEPTED_BETTER_ACCURACY, better.state.lastFixDecision)
        assertEquals(20f, better.state.pending!!.fix.accuracyMeters)
    }

    @Test
    fun `significant movement replaces the pending`() {
        val first = machine.step(MachineState(), MachineCommand.Fix(fix(latitude = 1.0)), nowMs = 0L).state
        val moved = machine.step(first, MachineCommand.Fix(fix(latitude = 2.0)), nowMs = 1_000L)

        assertEquals(FixDecisionReason.ACCEPTED_MOVEMENT, moved.state.lastFixDecision)
        assertEquals(2.0, moved.state.pending!!.fix.latitude, 0.0)
    }

    @Test
    fun `worse fix within the plausibility bound does not replace the pending`() {
        val first = machine.step(MachineState(), MachineCommand.Fix(fix(accuracy = 20f)), nowMs = 0L).state
        // 80 m of apparent movement with a 100 m candidate: circles overlap.
        val noise = machine.step(first, MachineCommand.Fix(fix(latitude = 1.00072, accuracy = 100f)), nowMs = 1_000L)

        assertEquals(FixDecisionReason.REJECTED_NOT_USEFUL, noise.state.lastFixDecision)
        assertEquals(20f, noise.state.pending!!.fix.accuracyMeters)
    }

    @Test
    fun `stale pending does not prevent replacement`() {
        val pending = PendingFix(fix(ageMs = 120_000L), arrivedAtMs = 0L)
        val state = MachineState(pending = pending)
        val fresh = machine.step(state, MachineCommand.Fix(fix(latitude = 1.0, ageMs = 0L, recordedAt = 2L)), nowMs = 200_000L)

        assertEquals(FixDecisionReason.ACCEPTED_EXPIRED_REPLACEMENT, fresh.state.lastFixDecision)
        assertEquals(1.0, fresh.state.pending!!.fix.latitude, 0.0)
    }

    @Test
    fun `stale pending is cleared on tick and cannot publish`() {
        val pending = PendingFix(fix(ageMs = 120_000L), arrivedAtMs = 0L)
        val state = MachineState(pending = pending)
        val transition = machine.step(state, MachineCommand.Tick, nowMs = 200_000L)

        assertTrue(transition.actions.isEmpty())
        assertNull("the expired pending must be cleared", transition.state.pending)
        assertEquals(FixDecisionReason.REJECTED_STALE, transition.state.lastPendingDecision)
    }

    // ----------------------------------------------------------- MOVEMENT

    @Test
    fun `movement below the threshold does not publish`() {
        val state = MachineState(lastPublished = published())
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 1.0007, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertSkips(transition, PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD)
        assertEquals(77.9, transition.decision!!.distanceMeters!!, 1.0)
    }

    @Test
    fun `movement beyond the effective threshold publishes`() {
        val state = MachineState(lastPublished = published())
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 1.002, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertPublishesPosition(transition, PublishDecisionReason.MOVEMENT)
    }

    @Test
    fun `candidate accuracy expands the movement threshold`() {
        val state = MachineState(lastPublished = published(accuracy = 10f))
        // 150 m moved but the candidate claims 200 m accuracy.
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 1.00135, accuracy = 200f, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertSkips(transition, PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD)
    }

    @Test
    fun `published accuracy expands the movement threshold`() {
        val state = MachineState(lastPublished = published(accuracy = 200f))
        // 150 m moved with a 150 m candidate: the PUBLISHED error circle still
        // overlaps and the candidate is better but not a material improvement.
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 1.00135, accuracy = 150f, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertSkips(transition, PublishDecisionReason.NON_MATERIAL_ACCURACY_UPGRADE)

        val beyond = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 1.00225, accuracy = 150f, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertPublishesPosition(beyond, PublishDecisionReason.MOVEMENT)
    }

    @Test
    fun `movement equal to the threshold does not publish`() {
        val state = MachineState(lastPublished = published())
        // Exactly 100 m: requiredDistance is 100 and the rule is strict `>`.
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 1.000898, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertSkips(transition, PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD)
    }

    @Test
    fun `map active mode lowers the movement threshold`() {
        val background = machine.step(
            MachineState(mode = PublisherMode.BACKGROUND, lastPublished = published()),
            MachineCommand.Fix(fix(latitude = 1.0007, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertSkips(background, PublishDecisionReason.BELOW_MOVEMENT_THRESHOLD)

        val mapActive = machine.step(
            MachineState(mode = PublisherMode.MAP_ACTIVE, lastPublished = published()),
            MachineCommand.Fix(fix(latitude = 1.0007, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertPublishesPosition(mapActive, PublishDecisionReason.MOVEMENT)
    }

    // --------------------------------------------------- MATERIAL UPGRADE

    @Test
    fun `material accuracy improvement publishes when the interval permits`() {
        val state = MachineState(
            lastPublished = published(accuracy = 200f, publishedAtMs = 0L),
            lastNetworkAttemptAtMs = 0L,
        )
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(accuracy = 20f, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertPublishesPosition(transition, PublishDecisionReason.MATERIAL_ACCURACY_UPGRADE)
    }

    @Test
    fun `non material accuracy improvements do not publish`() {
        val state = MachineState(
            lastPublished = published(accuracy = 15f, publishedAtMs = 0L),
            lastNetworkAttemptAtMs = 0L,
        )
        val fifteenToTen = machine.step(
            state,
            MachineCommand.Fix(fix(accuracy = 10f, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertSkips(fifteenToTen, PublishDecisionReason.NON_MATERIAL_ACCURACY_UPGRADE)

        val twoHundredToOneHundred = machine.step(
            MachineState(lastPublished = published(accuracy = 200f, publishedAtMs = 0L), lastNetworkAttemptAtMs = 0L),
            MachineCommand.Fix(fix(accuracy = 100f, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertSkips(twoHundredToOneHundred, PublishDecisionReason.NON_MATERIAL_ACCURACY_UPGRADE)
    }

    @Test
    fun `material improvement inside the minimum publish interval does not publish`() {
        val state = MachineState(
            lastPublished = published(accuracy = 200f, publishedAtMs = 50_000L),
            lastNetworkAttemptAtMs = 0L,
        )
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(accuracy = 20f, recordedAt = 2L)),
            nowMs = 60_000L,
        )
        assertSkips(transition, PublishDecisionReason.MIN_INTERVAL)
    }

    // ----------------------------------------------------------- ORDERING

    @Test
    fun `a fix older than the published position is rejected`() {
        val state = MachineState(lastPublished = published(recordedAt = 100L))
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(recordedAt = 99L)),
            nowMs = 60_000L,
        )
        assertTrue(transition.actions.isEmpty())
        assertEquals(FixDecisionReason.REJECTED_ORDERING, transition.state.lastFixDecision)
        assertNull(transition.state.pending)
    }

    @Test
    fun `a fix equal to the published recordedAt is allowed`() {
        val state = MachineState(lastPublished = published(recordedAt = 100L))
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 1.002, recordedAt = 100L)),
            nowMs = 60_000L,
        )
        assertPublishesPosition(transition, PublishDecisionReason.MOVEMENT)
    }

    @Test
    fun `a newer fix is allowed`() {
        val state = MachineState(lastPublished = published(recordedAt = 100L))
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 1.002, recordedAt = 101L)),
            nowMs = 60_000L,
        )
        assertPublishesPosition(transition, PublishDecisionReason.MOVEMENT)
    }

    @Test
    fun `a pending older than the published position is never published`() {
        // Defense in depth: a pending that somehow predates the last published
        // position (constructed state) must be skipped at publish time.
        val state = MachineState(
            pending = PendingFix(fix(recordedAt = 50L), arrivedAtMs = 0L),
            lastPublished = published(recordedAt = 100L),
            lastNetworkAttemptAtMs = 0L,
            lastNetworkSuccessAtMs = 0L,
        )
        val transition = machine.step(state, MachineCommand.Tick, nowMs = 60_000L)
        assertSkips(transition, PublishDecisionReason.ORDERING_REJECTED)
    }

    // ------------------------------------------------- SUCCESS / FAILURE

    @Test
    fun `position success clears the pending and records the published position`() {
        val pending = PendingFix(fix(latitude = 2.0, recordedAt = 2L), arrivedAtMs = 0L)
        val state = MachineState(pending = pending)
        val transition = machine.step(
            state,
            MachineCommand.PublishResult(PublishOutcome.SUCCESS, PublishKind.POSITION),
            nowMs = 5_000L,
        )

        assertNull(transition.state.pending)
        assertEquals(2.0, transition.state.lastPublished!!.latitude, 0.0)
        assertEquals(2L, transition.state.lastPublished!!.recordedAtEpochMs)
        assertEquals(5_000L, transition.state.lastPublished!!.publishedAtMs)
        assertEquals(1, transition.state.publishCount)
        assertEquals(0, transition.state.consecutiveFailures)
    }

    @Test
    fun `position failure keeps the fresh pending and enters backoff`() {
        val pending = PendingFix(fix(recordedAt = 2L), arrivedAtMs = 0L)
        val state = MachineState(pending = pending)
        val transition = machine.step(
            state,
            MachineCommand.PublishResult(PublishOutcome.FAILURE, PublishKind.POSITION),
            nowMs = 5_000L,
        )

        assertEquals("the fresh pending survives the failure", pending, transition.state.pending)
        assertEquals(1, transition.state.consecutiveFailures)
        assertEquals(65_000L, transition.state.backoffUntilMs)
        assertEquals("the failure floor anchors at the failed attempt", 5_000L, transition.state.lastFailureAtMs)
        assertEquals(5_000L, transition.state.lastNetworkAttemptAtMs)
    }

    // ------------------------------------------------------------- BACKOFF

    @Test
    fun `backoff sequence doubles up to the fifteen minute cap`() {
        var state = MachineState()
        var now = 0L
        val deadlines = mutableListOf<Long>()
        repeat(6) { i ->
            state = machine.step(
                state,
                MachineCommand.PublishResult(PublishOutcome.FAILURE, PublishKind.POSITION),
                now,
            ).state
            deadlines += state.backoffUntilMs!!
            assertEquals(i + 1, state.consecutiveFailures)
            now = deadlines.last()
        }
        assertEquals(listOf(60_000L, 180_000L, 420_000L, 900_000L, 1_800_000L, 2_700_000L), deadlines)
    }

    @Test
    fun `backoff expiry publishes the latest fresh pending`() {
        val first = PendingFix(fix(latitude = 1.0, recordedAt = 1L), arrivedAtMs = 0L)
        val state = MachineState(
            pending = first,
            consecutiveFailures = 1,
            backoffUntilMs = 60_000L,
            lastNetworkAttemptAtMs = 0L,
            lastFailureAtMs = 0L,
        )
        val transition = machine.step(state, MachineCommand.Tick, nowMs = 60_000L)

        val pending = assertPublishesPosition(transition, PublishDecisionReason.FIRST)
        assertEquals("the retry uses the pending fix", 1.0, pending.fix.latitude, 0.0)
        assertNull("the backoff window is over", transition.state.backoffUntilMs)
        assertEquals("the failure count survives the retry until its result", 1, transition.state.consecutiveFailures)

        // A failed retry escalates the sequence; a successful one resets it.
        val failed = machine.step(
            transition.state,
            MachineCommand.PublishResult(PublishOutcome.FAILURE, PublishKind.POSITION),
            nowMs = 60_000L,
        ).state
        assertEquals(2, failed.consecutiveFailures)
        assertEquals(180_000L, failed.backoffUntilMs)
    }

    @Test
    fun `expired pending at backoff expiry does not publish`() {
        val state = MachineState(
            pending = PendingFix(fix(ageMs = 120_000L), arrivedAtMs = 0L),
            consecutiveFailures = 1,
            backoffUntilMs = 60_000L,
            lastNetworkAttemptAtMs = 0L,
            lastFailureAtMs = 0L,
        )
        val transition = machine.step(state, MachineCommand.Tick, nowMs = 200_000L)

        assertTrue(transition.actions.isEmpty())
        assertNull(transition.state.pending)
        assertNull("backoff cleared without an attempt", transition.state.backoffUntilMs)
        assertEquals(0, transition.state.consecutiveFailures)
    }

    @Test
    fun `fix during backoff updates the pending but never attempts the network`() {
        val state = MachineState(
            pending = PendingFix(fix(latitude = 1.0, recordedAt = 1L), arrivedAtMs = 0L),
            consecutiveFailures = 1,
            backoffUntilMs = 120_000L,
            lastFailureAtMs = 0L,
            lastNetworkAttemptAtMs = 0L,
        )
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 2.0, recordedAt = 2L)),
            nowMs = 60_000L,
        )

        assertTrue("no network attempt during backoff", transition.actions.isEmpty())
        assertSkips(transition, PublishDecisionReason.BACKOFF)
        assertEquals("the pending was updated", 2.0, transition.state.pending!!.fix.latitude, 0.0)
        assertEquals("backoff is NOT reset by a fix", 120_000L, transition.state.backoffUntilMs)
        assertEquals(1, transition.state.consecutiveFailures)
    }

    @Test
    fun `significant movement during backoff does not reset the backoff`() {
        val state = MachineState(
            pending = PendingFix(fix(latitude = 1.0, recordedAt = 1L), arrivedAtMs = 0L),
            consecutiveFailures = 3,
            backoffUntilMs = 500_000L,
            lastFailureAtMs = 0L,
            lastNetworkAttemptAtMs = 0L,
        )
        // 111 km of movement: still no network, still backing off.
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 2.0, recordedAt = 2L)),
            nowMs = 100_000L,
        )

        assertTrue(transition.actions.isEmpty())
        assertEquals(PublishDecisionReason.BACKOFF, transition.decision?.reason)
        assertEquals(500_000L, transition.state.backoffUntilMs)
        assertEquals(3, transition.state.consecutiveFailures)
    }

    @Test
    fun `reset events respect the sixty second failure floor`() {
        // Failure at t=0; a reset at t=10 s must not attempt before t=60 s.
        val base = MachineState(
            pending = PendingFix(fix(latitude = 2.0, recordedAt = 2L), arrivedAtMs = 0L),
            lastPublished = published(publishedAtMs = 0L),
            lastFailureAtMs = 0L,
            lastNetworkAttemptAtMs = 0L,
        )
        val regained = machine.step(base, MachineCommand.NetworkRegained, nowMs = 10_000L)
        assertTrue("the floor blocks the reset attempt", regained.actions.isEmpty())
        assertSkips(regained, PublishDecisionReason.BACKOFF_FLOOR)

        val publishNow = machine.step(base, MachineCommand.PublishNow, nowMs = 20_000L)
        assertTrue(publishNow.actions.isEmpty())
        assertSkips(publishNow, PublishDecisionReason.BACKOFF_FLOOR)
    }

    @Test
    fun `reset events are allowed once the failure floor has passed`() {
        val base = MachineState(
            pending = PendingFix(fix(latitude = 2.0, recordedAt = 2L), arrivedAtMs = 0L),
            lastPublished = published(publishedAtMs = 0L),
            lastFailureAtMs = 0L,
            lastNetworkAttemptAtMs = 0L,
        )
        // At exactly 60 s the floor has passed: the reset attempt is allowed.
        val atFloor = machine.step(base, MachineCommand.NetworkRegained, nowMs = 60_000L)
        assertPublishesPosition(atFloor, PublishDecisionReason.MOVEMENT)

        // And at 70 s it is certainly allowed.
        val later = machine.step(base, MachineCommand.NetworkRegained, nowMs = 70_000L)
        assertPublishesPosition(later, PublishDecisionReason.MOVEMENT)
    }

    @Test
    fun `a new fix during the recovery floor never triggers a network attempt`() {
        // Reset already cleared the exponential backoff, but the 60 s failure
        // floor still holds: a fresh significant fix must not publish.
        val state = MachineState(
            lastPublished = published(publishedAtMs = 0L),
            lastFailureAtMs = 0L,
            lastNetworkAttemptAtMs = 0L,
        )
        val transition = machine.step(
            state,
            MachineCommand.Fix(fix(latitude = 2.0, recordedAt = 2L)),
            nowMs = 20_000L,
        )

        assertEquals("the pending accepted the new fix", 2.0, transition.state.pending!!.fix.latitude, 0.0)
        assertTrue(transition.actions.isEmpty())
        assertSkips(transition, PublishDecisionReason.BACKOFF_FLOOR)
        assertEquals("the floor deadline is untouched", 0L, transition.state.lastFailureAtMs)
    }

    @Test
    fun `healthy cadence allows a movement publish thirty seconds after success`() {
        val base = MachineState(lastPublished = published(publishedAtMs = 0L))

        val before = machine.step(
            base,
            MachineCommand.Fix(fix(latitude = 1.002, recordedAt = 2L)),
            nowMs = 29_999L,
        )
        assertSkips(before, PublishDecisionReason.MIN_INTERVAL)

        val atInterval = machine.step(
            base,
            MachineCommand.Fix(fix(latitude = 1.002, recordedAt = 2L)),
            nowMs = 30_000L,
        )
        assertPublishesPosition(atInterval, PublishDecisionReason.MOVEMENT)
    }

    @Test
    fun `publish now after the floor publishes`() {
        val state = MachineState(
            pending = PendingFix(fix(latitude = 2.0, recordedAt = 2L), arrivedAtMs = 0L),
            lastFailureAtMs = 0L,
            lastNetworkAttemptAtMs = 0L,
        )
        val transition = machine.step(state, MachineCommand.PublishNow, nowMs = 120_000L)
        assertPublishesPosition(transition, PublishDecisionReason.FIRST)
    }

    @Test
    fun `publish now without a pending reports NO_PENDING`() {
        val transition = machine.step(MachineState(), MachineCommand.PublishNow, nowMs = 0L)
        assertSkips(transition, PublishDecisionReason.NO_PENDING)
    }

    @Test
    fun `network regained is a reset that can publish immediately`() {
        val state = MachineState(
            pending = PendingFix(fix(latitude = 2.0, recordedAt = 2L), arrivedAtMs = 0L),
            consecutiveFailures = 2,
            backoffUntilMs = 500_000L,
            lastFailureAtMs = 0L,
            lastNetworkAttemptAtMs = 0L,
        )
        val transition = machine.step(state, MachineCommand.NetworkRegained, nowMs = 120_000L)

        assertEquals(0, transition.state.consecutiveFailures)
        assertNull(transition.state.backoffUntilMs)
        assertEquals("the failure floor keeps its deadline after a reset", 0L, transition.state.lastFailureAtMs)
        assertPublishesPosition(transition, PublishDecisionReason.FIRST)
    }

    @Test
    fun `mode change to map active resets backoff and re-evaluates`() {
        val state = MachineState(
            mode = PublisherMode.BACKGROUND,
            pending = PendingFix(fix(latitude = 1.0007, recordedAt = 2L), arrivedAtMs = 0L),
            lastPublished = published(),
            consecutiveFailures = 2,
            backoffUntilMs = 500_000L,
            lastFailureAtMs = 0L,
            lastNetworkAttemptAtMs = 0L,
        )
        val transition = machine.step(state, MachineCommand.ModeChanged(PublisherMode.MAP_ACTIVE), nowMs = 120_000L)

        assertEquals(PublisherMode.MAP_ACTIVE, transition.state.mode)
        assertEquals(0, transition.state.consecutiveFailures)
        // 78 m with the 50 m map-active threshold is now publishable.
        assertPublishesPosition(transition, PublishDecisionReason.MOVEMENT)
    }

    @Test
    fun `mode change to background only switches the mode`() {
        val state = MachineState(mode = PublisherMode.MAP_ACTIVE)
        val transition = machine.step(state, MachineCommand.ModeChanged(PublisherMode.BACKGROUND), nowMs = 0L)
        assertEquals(PublisherMode.BACKGROUND, transition.state.mode)
        assertTrue(transition.actions.isEmpty())
        assertNull(transition.decision)
    }

    // ------------------------------------------------------------ PRESENCE

    @Test
    fun `presence is due five minutes after the last network success`() {
        val state = MachineState(
            lastPublished = published(publishedAtMs = 0L),
            lastNetworkAttemptAtMs = 0L,
            lastNetworkSuccessAtMs = 0L,
        )
        val transition = machine.step(state, MachineCommand.Tick, nowMs = 300_000L)

        assertEquals(1, transition.actions.size)
        val action = transition.actions.single()
        assertTrue("must be a presence publish", action is MachineAction.PublishPresence)
        val presence = action as MachineAction.PublishPresence
        assertEquals("presence re-PUTs the identical recordedAt", 1L, presence.position.recordedAtEpochMs)
        assertEquals("presence re-PUTs the identical position", 1.0, presence.position.latitude, 0.0)
        assertEquals(PublishDecisionReason.PRESENCE, transition.state.lastPresenceDecision)
    }

    @Test
    fun `presence is skipped while the network success is recent`() {
        val state = MachineState(
            lastPublished = published(),
            lastNetworkAttemptAtMs = 0L,
            lastNetworkSuccessAtMs = 0L,
        )
        val transition = machine.step(state, MachineCommand.Tick, nowMs = 240_000L)

        assertTrue(transition.actions.isEmpty())
        assertEquals(PublishDecisionReason.PRESENCE_NOT_DUE, transition.state.lastPresenceDecision)
    }

    @Test
    fun `presence respects the fifteen minute interval`() {
        val state = MachineState(
            lastPublished = published(),
            lastNetworkAttemptAtMs = 0L,
            lastNetworkSuccessAtMs = 300_000L,
            lastPresenceAtMs = 300_000L,
        )
        val early = machine.step(state, MachineCommand.Tick, nowMs = 600_000L)
        assertTrue(early.actions.isEmpty())
        assertEquals(PublishDecisionReason.PRESENCE_NOT_DUE, early.state.lastPresenceDecision)

        val due = machine.step(state, MachineCommand.Tick, nowMs = 1_200_000L)
        assertEquals(1, due.actions.size)
        assertEquals(PublishDecisionReason.PRESENCE, due.state.lastPresenceDecision)
    }

    @Test
    fun `presence is deferred during backoff`() {
        val state = MachineState(
            lastPublished = published(),
            consecutiveFailures = 1,
            backoffUntilMs = 1_000_000L,
            lastNetworkAttemptAtMs = 0L,
            lastNetworkSuccessAtMs = 0L,
        )
        val transition = machine.step(state, MachineCommand.Tick, nowMs = 400_000L)

        assertTrue("no presence during backoff", transition.actions.isEmpty())
        assertNull("presence is not evaluated while backing off", transition.state.lastPresenceDecision)
    }

    @Test
    fun `no published position means no presence`() {
        val transition = machine.step(MachineState(), MachineCommand.Tick, nowMs = 1_000_000L)
        assertTrue(transition.actions.isEmpty())
        assertEquals(PublishDecisionReason.PRESENCE_NOT_DUE, transition.state.lastPresenceDecision)
    }

    @Test
    fun `presence success advances presence without touching the position or pending`() {
        val pending = PendingFix(fix(latitude = 2.0, recordedAt = 2L), arrivedAtMs = 0L)
        val state = MachineState(
            pending = pending,
            lastPublished = published(),
            lastNetworkAttemptAtMs = 300_000L,
            lastNetworkSuccessAtMs = 300_000L,
        )
        val transition = machine.step(
            state,
            MachineCommand.PublishResult(PublishOutcome.SUCCESS, PublishKind.PRESENCE),
            nowMs = 900_000L,
        )

        assertEquals(1, transition.state.presenceCount)
        assertEquals(900_000L, transition.state.lastPresenceAtMs)
        assertEquals(0, transition.state.consecutiveFailures)
        assertEquals("presence never replaces the pending", pending, transition.state.pending)
        assertEquals("presence never alters the published position", 1L, transition.state.lastPublished!!.recordedAtEpochMs)
    }

    @Test
    fun `presence failure enters the same backoff sequence`() {
        val state = MachineState(lastPublished = published())
        val transition = machine.step(
            state,
            MachineCommand.PublishResult(PublishOutcome.FAILURE, PublishKind.PRESENCE),
            nowMs = 100_000L,
        )

        assertEquals(1, transition.state.consecutiveFailures)
        assertEquals(160_000L, transition.state.backoffUntilMs)
        assertEquals("no presence success was recorded", 0, transition.state.presenceCount)
    }
}