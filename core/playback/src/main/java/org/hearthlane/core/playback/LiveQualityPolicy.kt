package org.hearthlane.core.playback

/**
 * Pure AUTO live-quality policy for the remote (Tailscale) live view.
 *
 * The policy reasons in quality levels (SUB / MAIN), never in stream names:
 * the owner maps a level to the concrete go2rtc stream. Every event and the
 * periodic [tick] return a [LiveQualityAction] the owner must perform; the
 * policy itself never touches Android, Media3 or the network, so the whole
 * machine is unit-testable.
 *
 * State machine:
 *
 * ```
 * SUB ── healthy HEALTH_WINDOW_MS (no buffering, no death, no errors) ──► TRIAL_MAIN
 * TRIAL_MAIN ── clean TRIAL_WINDOW_MS ────────────────────────────────► MAIN
 * TRIAL_MAIN | MAIN ── evidence of an unsustainable main ──────────────► SUB
 * ```
 *
 * The reliable capacity test for the main stream is the trial itself: the
 * substream does not saturate the link, so its observed throughput is never
 * used as a gate for the first trial (no artificial speedtest, no arbitrary
 * Mbps floor). The trial observes the main's real playback signals and
 * downgrades aggressively on the first significant evidence.
 *
 * Anti-flapping (V1): at most [maxUpgradeAttempts] trials per logical
 * session; a failed trial leaves the policy in SUB until [reset] (Live
 * reopened or transport changed). An internal HLS session recovery does NOT
 * reset the policy, so it cannot mint new trials.
 */
class LiveQualityPolicy(
    private val nowMs: () -> Long,
    private val healthWindowMs: Long = HEALTH_WINDOW_MS,
    private val trialWindowMs: Long = TRIAL_WINDOW_MS,
    private val maxUpgradeAttempts: Int = MAX_UPGRADE_ATTEMPTS,
    private val bufferingDowngradeMs: Long = BUFFERING_DOWNGRADE_MS,
    private val bufferFloorMs: Long = BUFFER_FLOOR_MS,
    private val bufferDecaySamples: Int = BUFFER_DECAY_SAMPLES,
    private val playlistAnomalyLimit: Int = PLAYLIST_ANOMALY_LIMIT,
    private val throughputMinSamples: Int = THROUGHPUT_MIN_SAMPLES,
    private val throughputDowngradeRatio: Double = THROUGHPUT_DOWNGRADE_RATIO,
) {

    /** Current quality level of the machine. */
    var state: LiveQualityState = LiveQualityState.SUB
        private set

    /** Trials toward MAIN consumed during the current logical session. */
    var upgradeAttempts = 0
        private set

    /** True when no further trial is allowed in this logical session. */
    val attemptsExhausted: Boolean get() = upgradeAttempts >= maxUpgradeAttempts

    /** True when the last trial failed (diagnostic for the owner's logs). */
    var trialFailed: Boolean = false
        private set

    /** Reason of the most recent downgrade, if any. */
    var lastDowngradeReason: LiveQualityDowngradeReason? = null
        private set

    private var startupInProgress = false
    private var healthWindowStartedAtMs = Long.MIN_VALUE
    private var trialStartedAtMs = Long.MIN_VALUE
    private var lowBufferStreak = 0
    private var playlistAnomalies = 0
    private var lastPollGapMs = -1L
    private var segmentRateEwma = 0.0
    private var segmentRequirementEwma = 0.0
    private var segmentSampleCount = 0

    /**
     * A new logical session (Live reopened or transport changed): trials are
     * allowed again. NOT called for internal HLS session recoveries.
     */
    fun reset() {
        state = LiveQualityState.SUB
        upgradeAttempts = 0
        trialFailed = false
        lastDowngradeReason = null
        startupInProgress = false
        healthWindowStartedAtMs = Long.MIN_VALUE
        trialStartedAtMs = Long.MIN_VALUE
        lowBufferStreak = 0
        playlistAnomalies = 0
        lastPollGapMs = -1L
        segmentRateEwma = 0.0
        segmentRequirementEwma = 0.0
        segmentSampleCount = 0
    }

    /** A media session is preparing (play, quality switch or recovery): the
     *  startup buffering is normal and never counts as evidence. */
    fun onSessionPreparing() {
        startupInProgress = true
        lowBufferStreak = 0
        playlistAnomalies = 0
    }

    /** The session reached READY (startup complete): the health window starts. */
    fun onStartupComplete() {
        startupInProgress = false
        lowBufferStreak = 0
        healthWindowStartedAtMs = nowMs()
    }

    /** A post-startup buffering episode ended. In SUB any buffering restarts
     *  the health window; in TRIAL_MAIN/MAIN a significant episode downgrades. */
    fun onBufferingEnded(durationMs: Long): LiveQualityAction {
        if (startupInProgress) return LiveQualityAction.None
        if (state == LiveQualityState.SUB) {
            healthWindowStartedAtMs = nowMs()
            return LiveQualityAction.None
        }
        return if (durationMs >= bufferingDowngradeMs) {
            downgrade(LiveQualityDowngradeReason.BUFFERING)
        } else {
            LiveQualityAction.None
        }
    }

    /** Buffering is still ongoing past the downgrade threshold (owner's 1 Hz
     *  check while loading): a stuck session is the same evidence. */
    fun onBufferingTooLong(): LiveQualityAction {
        if (startupInProgress) return LiveQualityAction.None
        if (state == LiveQualityState.SUB) {
            healthWindowStartedAtMs = nowMs()
            return LiveQualityAction.None
        }
        return downgrade(LiveQualityDowngradeReason.BUFFERING)
    }

    /** 1 Hz total-buffered-duration sample (ms). A sustained low buffer is
     *  buffer decay; in SUB it only restarts the health window. */
    fun onBufferSample(bufferedMs: Long): LiveQualityAction {
        if (startupInProgress) return LiveQualityAction.None
        val low = bufferedMs < bufferFloorMs
        lowBufferStreak = if (low) lowBufferStreak + 1 else 0
        if (lowBufferStreak < bufferDecaySamples) return LiveQualityAction.None
        lowBufferStreak = 0
        if (state == LiveQualityState.SUB) {
            healthWindowStartedAtMs = nowMs()
            return LiveQualityAction.None
        }
        return downgrade(LiveQualityDowngradeReason.BUFFER_DECAY)
    }

    /** A transport read stall (data source) occurred. Observation only in
     *  TRIAL_MAIN/MAIN: buffering and buffer decay already cover the same
     *  situation without single-sample noise. */
    fun onReadStall() {
        if (startupInProgress) return
        if (state == LiveQualityState.SUB) healthWindowStartedAtMs = nowMs()
    }

    /** A playlist poll arrived [gapMs] after the previous one. A gap above the
     *  anomaly threshold is a polling anomaly; repeated anomalies downgrade in
     *  TRIAL_MAIN/MAIN, a single one restarts the SUB health window. */
    fun onPlaylistPolled(gapMs: Long): LiveQualityAction {
        lastPollGapMs = gapMs
        if (startupInProgress) return LiveQualityAction.None
        if (gapMs <= PLAYLIST_ANOMALY_GAP_MS) {
            playlistAnomalies = 0
            return LiveQualityAction.None
        }
        if (state == LiveQualityState.SUB) {
            healthWindowStartedAtMs = nowMs()
            return LiveQualityAction.None
        }
        playlistAnomalies++
        if (playlistAnomalies >= playlistAnomalyLimit) {
            playlistAnomalies = 0
            return downgrade(LiveQualityDowngradeReason.PLAYLIST_ANOMALY)
        }
        return LiveQualityAction.None
    }

    /** A dead go2rtc session was detected (404/410 media playlist). */
    fun onSessionDeadCandidate(): LiveQualityAction {
        if (state == LiveQualityState.SUB) {
            healthWindowStartedAtMs = nowMs()
            return LiveQualityAction.None
        }
        return downgrade(LiveQualityDowngradeReason.SESSION_DEAD)
    }

    /** A player error occurred. */
    fun onPlayerError(): LiveQualityAction {
        if (state == LiveQualityState.SUB) {
            healthWindowStartedAtMs = nowMs()
            return LiveQualityAction.None
        }
        return downgrade(LiveQualityDowngradeReason.PLAYER_ERROR)
    }

    /** A non-dead load error (segment/init/other) occurred. */
    fun onLoadError(): LiveQualityAction {
        if (startupInProgress) return LiveQualityAction.None
        if (state == LiveQualityState.SUB) {
            healthWindowStartedAtMs = nowMs()
            return LiveQualityAction.None
        }
        return downgrade(LiveQualityDowngradeReason.LOAD_ERROR)
    }

    /**
     * A media segment finished downloading ([bytes] over [durationMs]).
     *
     * The samples feed a self-calibrating throughput view used ONLY to
     * evaluate the MAIN stream (never gating the first trial, never produced
     * by artificial traffic): the observed segment rate (bytes per download
     * second) against the requirement implied by the segment bytes and the
     * live playlist polling cadence (bytes per poll second). A sustained rate
     * far below the requirement is a downgrade signal; a single noisy sample
     * never decides.
     */
    fun onMediaSegmentSample(bytes: Long, durationMs: Long): LiveQualityAction {
        if (state == LiveQualityState.SUB) {
            segmentSampleCount = 0
            return LiveQualityAction.None
        }
        val rateBps = bytes * 8_000.0 / durationMs.coerceAtLeast(1)
        segmentRateEwma = if (segmentSampleCount == 0) {
            rateBps
        } else {
            segmentRateEwma * RATE_EWMA_ALPHA + rateBps * (1 - RATE_EWMA_ALPHA)
        }
        if (lastPollGapMs > 0) {
            val requirementBps = bytes * 8_000.0 / lastPollGapMs.coerceAtLeast(MIN_POLL_GAP_MS)
            segmentRequirementEwma = if (segmentSampleCount == 0) {
                requirementBps
            } else {
                segmentRequirementEwma * RATE_EWMA_ALPHA + requirementBps * (1 - RATE_EWMA_ALPHA)
            }
        }
        segmentSampleCount++
        if (segmentSampleCount >= throughputMinSamples &&
            segmentRequirementEwma > 0 &&
            segmentRateEwma < segmentRequirementEwma * throughputDowngradeRatio
        ) {
            return downgrade(LiveQualityDowngradeReason.THROUGHPUT)
        }
        return LiveQualityAction.None
    }

    /** 1 Hz periodic check: the SUB health window and the TRIAL window. */
    fun tick(): LiveQualityAction {
        when (state) {
            LiveQualityState.SUB -> {
                if (!startupInProgress &&
                    healthWindowStartedAtMs != Long.MIN_VALUE &&
                    nowMs() - healthWindowStartedAtMs >= healthWindowMs &&
                    !attemptsExhausted
                ) {
                    return LiveQualityAction.TrialReady
                }
            }
            LiveQualityState.TRIAL_MAIN -> {
                if (!trialFailed && nowMs() - trialStartedAtMs >= trialWindowMs) {
                    return LiveQualityAction.TrialPassed
                }
            }
            LiveQualityState.MAIN -> Unit
        }
        return LiveQualityAction.None
    }

    /** The owner switched to the MAIN stream: the trial window starts and one
     *  upgrade attempt is consumed. */
    fun onTrialStarted() {
        state = LiveQualityState.TRIAL_MAIN
        trialStartedAtMs = nowMs()
        upgradeAttempts++
        trialFailed = false
        lowBufferStreak = 0
        playlistAnomalies = 0
        segmentSampleCount = 0
        segmentRateEwma = 0.0
        segmentRequirementEwma = 0.0
    }

    /** The trial window elapsed without failure: MAIN becomes the steady state. */
    fun onTrialPassed() {
        state = LiveQualityState.MAIN
        trialFailed = false
        lowBufferStreak = 0
    }

    private fun downgrade(reason: LiveQualityDowngradeReason): LiveQualityAction {
        if (state == LiveQualityState.TRIAL_MAIN) trialFailed = true
        lastDowngradeReason = reason
        state = LiveQualityState.SUB
        return LiveQualityAction.Downgrade(reason)
    }

    companion object {
        const val HEALTH_WINDOW_MS = 60_000L
        const val TRIAL_WINDOW_MS = 30_000L
        const val MAX_UPGRADE_ATTEMPTS = 1
        const val BUFFERING_DOWNGRADE_MS = 1_000L
        const val BUFFER_FLOOR_MS = 1_500L
        const val BUFFER_DECAY_SAMPLES = 3
        const val PLAYLIST_ANOMALY_GAP_MS = 1_500L
        const val PLAYLIST_ANOMALY_LIMIT = 2
        const val THROUGHPUT_MIN_SAMPLES = 3
        const val THROUGHPUT_DOWNGRADE_RATIO = 0.7
        const val MIN_POLL_GAP_MS = 250L
        const val RATE_EWMA_ALPHA = 0.7
    }
}

/** Quality level of the AUTO machine. */
enum class LiveQualityState { SUB, TRIAL_MAIN, MAIN }

/** Why the policy downgraded back to SUB. */
enum class LiveQualityDowngradeReason {
    BUFFERING,
    BUFFER_DECAY,
    SESSION_DEAD,
    PLAYLIST_ANOMALY,
    PLAYER_ERROR,
    LOAD_ERROR,
    THROUGHPUT,
}

/** The action the owner must take after feeding an event or [LiveQualityPolicy.tick]. */
sealed interface LiveQualityAction {
    /** Nothing to do. */
    data object None : LiveQualityAction

    /** The SUB health window elapsed: switch to the MAIN stream, then call
     *  [LiveQualityPolicy.onTrialStarted]. */
    data object TrialReady : LiveQualityAction

    /** The trial window elapsed without failure: call
     *  [LiveQualityPolicy.onTrialPassed] to commit MAIN. */
    data object TrialPassed : LiveQualityAction

    /** Switch back to the SUB stream. */
    data class Downgrade(val reason: LiveQualityDowngradeReason) : LiveQualityAction
}

/**
 * Outcome of the playback reaching PLAYING (READY).
 *
 * A pending startup completing MUST clear the loading marker
 * ([clearLoading]): otherwise the owner's periodic stuck-buffering guard
 * (which fires when a loading marker is older than the downgrade threshold)
 * would mistake the STARTUP loading — which legitimately lasts longer than
 * the threshold on a stream switch — for a post-startup rebuffer and fail a
 * TRIAL_MAIN that never actually degraded.
 */
data class PlayingTransition(
    /** True when a pending startup completed at this READY. */
    val startupCompleted: Boolean,
    /** Duration of a post-startup rebuffer that just ended, if any. */
    val rebufferDurationMs: Long?,
    /** True when the owner MUST reset its loading marker to -1. */
    val clearLoading: Boolean,
)

/**
 * Pure PLAYING transition: when [startupInProgress] the startup completes
 * (startup buffering is normal and never evidence); otherwise a pending
 * loading marker closes a rebuffer with its measured duration. Both cases
 * clear the loading marker; with no pending loading nothing changes.
 */
fun transitionOnPlaying(
    startupInProgress: Boolean,
    loadingStartedAtMs: Long,
    nowMs: Long,
): PlayingTransition = when {
    startupInProgress -> PlayingTransition(
        startupCompleted = true,
        rebufferDurationMs = null,
        clearLoading = true,
    )
    loadingStartedAtMs >= 0 -> PlayingTransition(
        startupCompleted = false,
        rebufferDurationMs = nowMs - loadingStartedAtMs,
        clearLoading = true,
    )
    else -> PlayingTransition(
        startupCompleted = false,
        rebufferDurationMs = null,
        clearLoading = false,
    )
}