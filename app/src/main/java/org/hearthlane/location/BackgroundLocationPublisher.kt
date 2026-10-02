package org.hearthlane.location

import android.location.Location
import android.os.SystemClock
import org.hearthlane.core.relay.DeviceLocation
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
 * Actor runner for the V2 [LocationPublisherMachine].
 *
 * One actor coroutine consumes [MachineCommand]s from a single channel; every
 * state mutation and every publish decision happens inside that coroutine, so
 * two concurrent state-machine publications can never reorder location state
 * (PUBLISH_NOW is a queued command, never a parallel coroutine).
 *
 * Acquisition stays the current one-shot mechanism: the timer sends a [Tick]
 * every [checkIntervalMs]; on each tick the machine evaluates time-driven
 * decisions (backoff expiry, regular publish, presence) and then the runner
 * performs ONE [readLocation] and feeds the fix to the machine.
 *
 * The machine's monotonic clock is [clockMs] (`elapsedRealtime`); wall clock
 * [wallClockMs] is only used for the human-readable Diagnostics timestamps.
 *
 * Every pipeline event is emitted through [emitEvent] (default: structured
 * logcat via [LocationEventLog]). No coordinate ever reaches an event.
 */
internal class BackgroundLocationPublisher(
    private val readLocation: suspend () -> LocationReadResult,
    private val publish: suspend (String, DeviceLocation) -> Int,
    private val deviceId: () -> String,
    private val checkIntervalMs: () -> Long,
    private val scope: CoroutineScope,
    private val onPublishFailure: (() -> Unit)? = null,
    private val distanceMeters: (PositionFix, PositionFix) -> Double = ::geoDistanceMeters,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val emitEvent: (LocationEvent) -> Unit = { LocationEventLog.emit(it) },
    private val mode: PublisherMode = PublisherMode.BACKGROUND,
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
        /** Last location read attempt (wall-clock ms). */
        val lastReadAtMs: Long? = null,
        /** [LocationReadStatus] name of the last read, or ERROR on exception. */
        val lastReadResult: String? = null,
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
    private var machineState = MachineState(mode = mode)

    /**
     * Timer-tick coalescing: while a Tick command is being processed, the
     * timer skips new fires, so a long publish can never build a backlog of
     * queued acquisition cycles. Non-tick commands are never dropped.
     */
    private val tickInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

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
                delay(checkIntervalMs())
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

    private suspend fun handle(command: MachineCommand) {
        when (command) {
            MachineCommand.Tick -> handleTick()
            is MachineCommand.PublishNow,
            is MachineCommand.NetworkRegained,
            is MachineCommand.ModeChanged,
            -> apply(machine.step(machineState, command, clockMs()))
            is MachineCommand.Fix,
            is MachineCommand.PublishResult,
            -> error("command is produced internally: $command")
        }
    }

    private suspend fun handleTick() {
        // Acquisition FIRST, time-driven decisions SECOND: when a transport
        // backoff expires in this cycle, the retry therefore evaluates the
        // newest useful fix (read below) instead of the previous pending.
        val now = clockMs()
        val fix = try {
            readLocation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        _state.update {
            it.copy(
                lastReadAtMs = wallClockMs(),
                lastReadResult = fix?.status?.name ?: LocationReadStatus.ERROR.name,
            )
        }
        val sample = fix?.sample
        val fixDecision = if (sample != null) {
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
            apply(
                machine.step(
                    machineState,
                    MachineCommand.Fix(
                        PositionFix(
                            latitude = sample.latitude,
                            longitude = sample.longitude,
                            accuracyMeters = sample.accuracyMeters,
                            recordedAtEpochMs = sample.recordedAtWallClockMs,
                            ageAtReadMs = sample.ageMs,
                        ),
                    ),
                    clockMs(),
                ),
            )
            machineState.lastDecision
        } else {
            emitEvent(
                LocationEvent(
                    LocationEventLog.EVENT_PUBLISH_DECISION,
                    listOf(
                        "decision" to "SKIP",
                        "reason" to PublishDecisionReason.NO_PENDING.name,
                        "sinceLastAttemptMs" to machineState.lastNetworkAttemptAtMs?.let { now - it },
                        "sinceLastSuccessMs" to machineState.lastNetworkSuccessAtMs?.let { now - it },
                    ),
                ),
            )
            null
        }
        // Time-driven evaluation (backoff expiry retry, regular eligibility,
        // presence). A SKIP decision with the same reason as the fix step's is
        // a pure duplicate within one cycle and is suppressed.
        apply(
            machine.step(machineState, MachineCommand.Tick, clockMs()),
            suppressDecisionReason = fixDecision,
        )
    }

    private suspend fun apply(
        transition: MachineTransition,
        suppressDecisionReason: PublishDecisionReason? = null,
    ) {
        val previous = machineState
        machineState = transition.state
        mirror()
        emitDecisionEvents(previous, transition, suppressDecisionReason)
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
        suppressDecisionReason: PublishDecisionReason? = null,
    ) {
        transition.decision?.let { decision ->
            val suppressed = !decision.shouldPublish && decision.reason == suppressDecisionReason
            if (decision.reason != PublishDecisionReason.NO_PENDING && !suppressed) {
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
        apply(machine.step(machineState, MachineCommand.PublishResult(outcome, PublishKind.POSITION), clockMs()))
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
        apply(machine.step(machineState, MachineCommand.PublishResult(outcome, PublishKind.PRESENCE), clockMs()))
    }

    private fun mirror() {
        val m = machineState
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
}

/** Great-circle distance via the Android location stack. */
private fun geoDistanceMeters(a: PositionFix, b: PositionFix): Double {
    val results = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
    return abs(results[0].toDouble())
}