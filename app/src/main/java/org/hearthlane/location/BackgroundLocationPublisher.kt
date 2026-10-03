package org.hearthlane.location

import android.location.Location
import android.os.SystemClock
import org.hearthlane.core.relay.DeviceLocation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Actor runner for the V2 [LocationPublisherMachine] (Phase 2 + Phase 3).
 *
 * One actor coroutine consumes [MachineCommand]s from a single channel; every
 * state mutation and every publish decision happens inside that coroutine, so
 * two concurrent state-machine publications can never reorder location state.
 *
 * Phase 3 acquisition: fixes arrive from the [AcquisitionController] via
 * [submitFix] (listener callbacks and GPS one-shots); the machine Tick is a
 * lightweight time-only scheduler (backoff expiry, presence, stale-pending
 * cleanup) that NEVER reads a location. [tickInFlight] coalesces timer ticks
 * so a long publish can never build a backlog; non-tick commands are never
 * dropped.
 *
 * The machine's monotonic clock is [clockMs] (`elapsedRealtime`); wall clock
 * [wallClockMs] is only used for the human-readable Diagnostics timestamps.
 */
internal class BackgroundLocationPublisher(
    private val publish: suspend (String, DeviceLocation) -> Int,
    private val deviceId: () -> String,
    private val scope: CoroutineScope,
    private val onPublishFailure: (() -> Unit)? = null,
    private val distanceMeters: (PositionFix, PositionFix) -> Double = ::geoDistanceMeters,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val emitEvent: (LocationEvent) -> Unit = { LocationEventLog.emit(it) },
    private val mode: PublisherMode = PublisherMode.BACKGROUND,
    private val tickIntervalMs: () -> Long = { TICK_INTERVAL_MS },
    /** Test seam: invoked after every machine Tick step. */
    private val onTickProcessed: (() -> Unit)? = null,
) {

    data class State(
        val running: Boolean = false,
        val publishCount: Int = 0,
        val lastLocation: DeviceLocation? = null,
        val lastError: String? = null,
        /** Last successful publish (wall-clock ms). */
        val lastPublishAtMs: Long? = null,
        /** Last publish attempt, successful or not (wall-clock ms). */
        val lastPublishAttemptAtMs: Long? = null,
        /** Sanitized publish outcome ("Success" or the raw error message). */
        val lastPublishResult: String? = null,
        /** Whether the machine currently holds a pending fix. */
        val hasPendingLocation: Boolean = false,
        /** Provider of the last fix received (observability only). */
        val lastFixProvider: String? = null,
        /** Accuracy of the last fix received (observability only). */
        val lastFixAccuracyMeters: Float? = null,
        /** Age of the last fix received, in ms (observability only). */
        val lastFixAgeMs: Long? = null,
        /** Last position-publish decision reason (observability only). */
        val lastPublishDecision: String? = null,
        /** Last fix/pending decision reason (observability only). */
        val lastFixDecision: String? = null,
        /** Whether the machine is inside the transport backoff window. */
        val backoffActive: Boolean = false,
        /** Consecutive transport failures driving the backoff sequence. */
        val backoffAttempt: Int = 0,
        /** Approximate backoff time remaining, in ms. */
        val backoffRemainingMs: Long? = null,
        /** Successful presence publishes. */
        val presenceCount: Int = 0,
        /** Monotonic time of the last successful presence publish. */
        val lastPresenceAtMs: Long? = null,
        /** Last presence decision reason (observability only). */
        val lastPresenceDecision: String? = null,
    )

    private val machine = LocationPublisherMachine(distanceMeters)
    private val commands = Channel<MachineCommand>(Channel.UNLIMITED)

    /** Written only by the actor; read by the acquisition controller. */
    private val machineState = AtomicReference(MachineState(mode = mode))

    /** Last emitted decision-event reason: identical SKIP decisions are deduped. */
    private var lastEmittedDecisionName: String? = null

    /**
     * Timer-tick coalescing: while a Tick command is being processed, the
     * timer skips new fires, so a long publish can never build a backlog of
     * queued machine-time evaluations. Non-tick commands are never dropped.
     */
    private val tickInFlight = AtomicBoolean(false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var actorJob: Job? = null
    private var timerJob: Job? = null

    fun start() {
        if (actorJob?.isActive == true) return
        _state.update { it.copy(running = true) }
        actorJob = scope.launch {
            tickInFlight.set(true)
            commands.send(MachineCommand.Tick)
            for (command in commands) {
                if (!isActive) break
                handle(command)
                if (command == MachineCommand.Tick) tickInFlight.set(false)
            }
        }
        timerJob = scope.launch {
            while (isActive) {
                delay(tickIntervalMs())
                if (tickInFlight.compareAndSet(false, true)) {
                    commands.send(MachineCommand.Tick)
                }
            }
        }
    }

    fun stop() {
        actorJob?.cancel()
        timerJob?.cancel()
        actorJob = null
        timerJob = null
        _state.update { it.copy(running = false) }
    }

    /** Enqueues an explicit publish request; serialized with everything else. */
    fun publishNow() {
        commands.trySend(MachineCommand.PublishNow)
    }

    /** Mode change (map open/close): serialized policy switch, no restart. */
    fun setMode(newMode: PublisherMode) {
        commands.trySend(MachineCommand.ModeChanged(newMode))
    }

    /** ConnectivityManager-confirmed network regained / transport change. */
    fun networkRegained() {
        commands.trySend(MachineCommand.NetworkRegained)
    }

    /**
     * Whether the machine currently holds a publishable (fresh) pending fix.
     * Read by the acquisition controller for the GPS escalation policy.
     */
    fun hasPublishableFix(): Boolean {
        val pending = machineState.get().pending ?: return false
        return LocationPolicy.isFresh(pending.ageAt(clockMs()))
    }

    /**
     * Feeds a freshly delivered fix (listener or GPS one-shot) into the
     * machine. Observability is mirrored and the fix-received event emitted
     * here; usefulness, pending and publishing stay with the machine.
     */
    fun submitFix(sample: LocationSample) {
        _state.update {
            it.copy(
                lastFixProvider = sample.provider,
                lastFixAccuracyMeters = sample.accuracyMeters,
                lastFixAgeMs = sample.ageMs,
            )
        }
        emitEvent(
            LocationEvent(
                LocationEventLog.EVENT_FIX_RECEIVED,
                listOf(
                    "provider" to sample.provider,
                    "accuracyMeters" to sample.accuracyMeters,
                    "ageMs" to sample.ageMs,
                    "hasAccuracy" to sample.hasAccuracy,
                ),
            ),
        )
        commands.trySend(
            MachineCommand.Fix(
                PositionFix(
                    latitude = sample.latitude,
                    longitude = sample.longitude,
                    accuracyMeters = sample.accuracyMeters,
                    recordedAtEpochMs = sample.recordedAtWallClockMs,
                    ageAtReadMs = sample.ageMs,
                ),
            ),
        )
    }

    private suspend fun handle(command: MachineCommand) {
        when (command) {
            MachineCommand.Tick -> {
                apply(machine.step(machineState.get(), MachineCommand.Tick, clockMs()))
                onTickProcessed?.invoke()
            }
            // A fix-rejected event describes the processing of ONE fix: it is
            // emitted only for transitions originated by a Fix command. Temporal
            // Ticks never process fixes and must never re-emit it.
            is MachineCommand.Fix -> apply(machine.step(machineState.get(), command, clockMs()), fromFix = true)
            is MachineCommand.PublishNow,
            is MachineCommand.NetworkRegained,
            is MachineCommand.ModeChanged,
            is MachineCommand.PublishResult,
            -> apply(machine.step(machineState.get(), command, clockMs()))
        }
    }

    private suspend fun apply(transition: MachineTransition, fromFix: Boolean = false) {
        val previous = machineState.get()
        machineState.set(transition.state)
        mirror()
        emitDecisionEvents(previous, transition, fromFix)
        for (action in transition.actions) {
            when (action) {
                is MachineAction.PublishPosition -> executePositionPublish(action.pending)
                is MachineAction.PublishPresence -> executePresencePublish(action.position)
            }
        }
    }

    private fun emitDecisionEvents(
        previous: MachineState,
        transition: MachineTransition,
        fromFix: Boolean,
    ) {
        transition.decision?.let { decision ->
            if (decision.reason != PublishDecisionReason.NO_PENDING) {
                val duplicate = !decision.shouldPublish &&
                    decision.reason.name == lastEmittedDecisionName
                if (!duplicate) {
                    lastEmittedDecisionName = decision.reason.name
                    emitEvent(
                        LocationEvent(
                            LocationEventLog.EVENT_PUBLISH_DECISION,
                            listOf(
                                "decision" to if (decision.shouldPublish) "PUBLISH" else "SKIP",
                                "reason" to decision.reason.name,
                                "distanceMeters" to decision.distanceMeters,
                                "sinceLastAttemptMs" to decision.sinceLastAttemptMs,
                                "sinceLastSuccessMs" to decision.sinceLastSuccessMs,
                            ),
                        ),
                    )
                }
            }
        }
        // Exactly one fix-rejected event per rejected Fix command, never on
        // later temporal Ticks (a rejection is a property of a processed fix).
        if (fromFix) {
            transition.state.lastFixDecision?.let { fixDecision ->
                if (fixDecision.name.startsWith("REJECTED_")) {
                    emitEvent(
                        LocationEvent(
                            LocationEventLog.EVENT_FIX_REJECTED,
                            listOf("reason" to fixDecision.name),
                        ),
                    )
                }
            }
        }
        if (previous.pending != transition.state.pending) {
            transition.state.pending?.let { pending ->
                emitEvent(
                    LocationEvent(
                        LocationEventLog.EVENT_PENDING_UPDATED,
                        listOf(
                            "accuracyMeters" to pending.fix.accuracyMeters,
                            "ageMs" to pending.ageAt(clockMs()),
                        ),
                    ),
                )
            }
        }
        if (previous.backoffUntilMs == null && transition.state.backoffUntilMs != null) {
            emitEvent(
                LocationEvent(
                    LocationEventLog.EVENT_BACKOFF,
                    listOf(
                        "attempt" to transition.state.consecutiveFailures,
                        "delayMs" to transition.state.backoffUntilMs!! - clockMs(),
                    ),
                ),
            )
        }
    }

    private suspend fun executePositionPublish(pending: PendingFix) {
        val location = DeviceLocation(
            latitude = pending.fix.latitude,
            longitude = pending.fix.longitude,
            accuracy = pending.fix.accuracyMeters,
            recordedAtEpochMs = pending.fix.recordedAtEpochMs,
        )
        _state.update { it.copy(lastPublishAttemptAtMs = wallClockMs()) }
        val outcome = try {
            publish(deviceId(), location)
            PublishOutcome.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "publish failed"
            _state.update {
                it.copy(
                    lastError = message,
                    lastPublishResult = message,
                )
            }
            onPublishFailure?.invoke()
            PublishOutcome.FAILURE
        }
        if (outcome == PublishOutcome.SUCCESS) {
            _state.update {
                it.copy(
                    lastPublishResult = "Success",
                    lastPublishAtMs = wallClockMs(),
                    lastLocation = location,
                    lastError = null,
                )
            }
        }
        apply(machine.step(machineState.get(), MachineCommand.PublishResult(outcome, PublishKind.POSITION), clockMs()))
    }

    private suspend fun executePresencePublish(position: PublishedPosition) {
        emitEvent(
            LocationEvent(
                LocationEventLog.EVENT_PUBLISH_DECISION,
                listOf(
                    "decision" to "PUBLISH",
                    "reason" to PublishDecisionReason.PRESENCE.name,
                ),
            ),
        )
        val location = DeviceLocation(
            latitude = position.latitude,
            longitude = position.longitude,
            accuracy = position.accuracyMeters,
            recordedAtEpochMs = position.recordedAtEpochMs,
        )
        val outcome = try {
            publish(deviceId(), location)
            PublishOutcome.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PublishOutcome.FAILURE
        }
        apply(machine.step(machineState.get(), MachineCommand.PublishResult(outcome, PublishKind.PRESENCE), clockMs()))
    }

    private fun mirror() {
        val m = machineState.get()
        val now = clockMs()
        _state.update {
            it.copy(
                publishCount = m.publishCount,
                hasPendingLocation = m.pending != null,
                lastPublishDecision = m.lastDecision?.name,
                lastFixDecision = m.lastFixDecision?.name,
                backoffActive = m.backoffUntilMs != null && now < m.backoffUntilMs,
                backoffAttempt = m.consecutiveFailures,
                backoffRemainingMs = m.backoffUntilMs?.let { (it - now).coerceAtLeast(0L) },
                presenceCount = m.presenceCount,
                lastPresenceAtMs = m.lastPresenceAtMs,
                lastPresenceDecision = m.lastPresenceDecision?.name,
            )
        }
    }

    companion object {
        /** Machine time-evaluation cadence (no location reads). */
        const val TICK_INTERVAL_MS = 30_000L
    }
}

/** Great-circle distance via the Android location stack. */
private fun geoDistanceMeters(a: PositionFix, b: PositionFix): Double {
    val results = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
    return abs(results[0].toDouble())
}