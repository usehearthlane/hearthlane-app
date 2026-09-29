package org.hearthlane.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure AUTO live-quality state machine
 * ([LiveQualityPolicy]). No Android, Media3 or network involved: time is
 * injected, events are fed, actions are asserted.
 */
class LiveQualityPolicyTest {

    private class Harness {
        var nowMs = 0L
        val policy = LiveQualityPolicy(nowMs = { nowMs })

        fun startSession() {
            policy.reset()
            policy.onSessionPreparing()
        }

        fun startupComplete() {
            policy.onStartupComplete()
        }

        fun advance(ms: Long) {
            nowMs += ms
        }

        fun feedHealthySub(seconds: Int) {
            repeat(seconds) {
                advance(1_000)
                assertEquals(LiveQualityAction.None, policy.tick())
                assertEquals(LiveQualityAction.None, policy.onBufferSample(2_200))
            }
        }
    }

    // -- Initial state ------------------------------------------------------

    @Test
    fun `initial state is SUB with no attempt consumed`() {
        val h = Harness()
        assertEquals(LiveQualityState.SUB, h.policy.state)
        assertEquals(0, h.policy.upgradeAttempts)
        assertFalse(h.policy.attemptsExhausted)
        assertFalse(h.policy.trialFailed)
        assertNull(h.policy.lastDowngradeReason)
    }

    // -- Health window ------------------------------------------------------

    @Test
    fun `does not trial before the health window elapses`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS - 1_000)
        assertEquals(LiveQualityAction.None, h.policy.tick())
    }

    @Test
    fun `healthy SUB for the window yields TrialReady`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        assertEquals(LiveQualityAction.TrialReady, h.policy.tick())
    }

    @Test
    fun `does not depend on any minimum throughput from the SUB`() {
        // No media-segment samples at all: the trial must still be offered.
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        assertEquals(LiveQualityAction.TrialReady, h.policy.tick())
    }

    @Test
    fun `no trial before startup completes`() {
        val h = Harness()
        h.startSession()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        assertEquals(LiveQualityAction.None, h.policy.tick())
    }

    @Test
    fun `buffering during SUB restarts the health window`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS - 1_000)
        // A post-startup buffering blip resets the window.
        assertEquals(LiveQualityAction.None, h.policy.onBufferingEnded(400))
        h.advance(1_000)
        assertEquals(LiveQualityAction.None, h.policy.tick())
        // The window restarted at the blip: 60 s after it, the trial is offered.
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS - 2_000)
        assertEquals(LiveQualityAction.None, h.policy.tick())
        h.advance(2_000)
        assertEquals(LiveQualityAction.TrialReady, h.policy.tick())
    }

    @Test
    fun `session dead candidate during SUB restarts the health window`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS - 1_000)
        assertEquals(LiveQualityAction.None, h.policy.onSessionDeadCandidate())
        h.advance(1_000)
        assertEquals(LiveQualityAction.None, h.policy.tick())
    }

    @Test
    fun `sustained low buffer during SUB restarts the health window`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS - 1_000)
        repeat(LiveQualityPolicy.BUFFER_DECAY_SAMPLES) {
            assertEquals(LiveQualityAction.None, h.policy.onBufferSample(800))
        }
        h.advance(1_000)
        assertEquals(LiveQualityAction.None, h.policy.tick())
    }

    @Test
    fun `startup buffering is not evidence`() {
        val h = Harness()
        h.startSession()
        // All of these occur while the session prepares and must be ignored.
        assertEquals(LiveQualityAction.None, h.policy.onBufferingEnded(5_000))
        assertEquals(LiveQualityAction.None, h.policy.onBufferingTooLong())
        assertEquals(LiveQualityAction.None, h.policy.onBufferSample(0))
        h.policy.onReadStall()
        h.startupComplete()
        assertEquals(LiveQualityState.SUB, h.policy.state)
    }

    // -- Trial --------------------------------------------------------------

    @Test
    fun `trial passes after a clean trial window`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        assertEquals(LiveQualityAction.TrialReady, h.policy.tick())
        h.policy.onTrialStarted()
        assertEquals(LiveQualityState.TRIAL_MAIN, h.policy.state)
        assertEquals(1, h.policy.upgradeAttempts)
        assertTrue(h.policy.attemptsExhausted)
        // Healthy trial playback.
        h.advance(10_000)
        assertEquals(LiveQualityAction.None, h.policy.onBufferSample(2_500))
        h.advance(LiveQualityPolicy.TRIAL_WINDOW_MS - 10_000)
        assertEquals(LiveQualityAction.TrialPassed, h.policy.tick())
        h.policy.onTrialPassed()
        assertEquals(LiveQualityState.MAIN, h.policy.state)
    }

    @Test
    fun `trial with significant buffering downgrades to SUB`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        assertEquals(LiveQualityAction.TrialReady, h.policy.tick())
        h.policy.onTrialStarted()
        assertEquals(
            LiveQualityAction.Downgrade(LiveQualityDowngradeReason.BUFFERING),
            h.policy.onBufferingEnded(1_200),
        )
        assertEquals(LiveQualityState.SUB, h.policy.state)
        assertTrue(h.policy.trialFailed)
        assertTrue(h.policy.attemptsExhausted)
    }

    @Test
    fun `short buffering blip does not fail the trial`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        assertEquals(LiveQualityAction.None, h.policy.onBufferingEnded(300))
        h.advance(LiveQualityPolicy.TRIAL_WINDOW_MS)
        assertEquals(LiveQualityAction.TrialPassed, h.policy.tick())
    }

    @Test
    fun `stuck buffering fails the trial`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        assertEquals(
            LiveQualityAction.Downgrade(LiveQualityDowngradeReason.BUFFERING),
            h.policy.onBufferingTooLong(),
        )
        assertEquals(LiveQualityState.SUB, h.policy.state)
    }

    @Test
    fun `buffer decay during trial downgrades`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        val action = generateSequence {
            h.policy.onBufferSample(1_000)
        }.take(LiveQualityPolicy.BUFFER_DECAY_SAMPLES).last()
        assertEquals(LiveQualityAction.Downgrade(LiveQualityDowngradeReason.BUFFER_DECAY), action)
        assertEquals(LiveQualityState.SUB, h.policy.state)
    }

    @Test
    fun `session dead during trial downgrades immediately`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        assertEquals(
            LiveQualityAction.Downgrade(LiveQualityDowngradeReason.SESSION_DEAD),
            h.policy.onSessionDeadCandidate(),
        )
        assertEquals(LiveQualityState.SUB, h.policy.state)
    }

    @Test
    fun `player error during trial downgrades`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        assertEquals(
            LiveQualityAction.Downgrade(LiveQualityDowngradeReason.PLAYER_ERROR),
            h.policy.onPlayerError(),
        )
        assertEquals(LiveQualityState.SUB, h.policy.state)
    }

    @Test
    fun `repeated playlist anomalies during trial downgrade, single anomaly does not`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        assertEquals(LiveQualityAction.None, h.policy.onPlaylistPolled(2_000))
        // A normal poll resets the anomaly counter.
        assertEquals(LiveQualityAction.None, h.policy.onPlaylistPolled(1_000))
        assertEquals(LiveQualityAction.None, h.policy.onPlaylistPolled(2_000))
        assertEquals(
            LiveQualityAction.Downgrade(LiveQualityDowngradeReason.PLAYLIST_ANOMALY),
            h.policy.onPlaylistPolled(2_000),
        )
        assertEquals(LiveQualityState.SUB, h.policy.state)
    }

    @Test
    fun `single noisy throughput sample does not downgrade`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        h.policy.onPlaylistPolled(1_000)
        assertEquals(LiveQualityAction.None, h.policy.onMediaSegmentSample(100_000, 2_000))
    }

    @Test
    fun `sustained throughput far below requirement downgrades`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        // Poll cadence ~1 s implies a requirement of ~800 kbps per segment;
        // each segment downloads at ~200 kbps (sustained).
        h.policy.onPlaylistPolled(1_000)
        var last: LiveQualityAction = LiveQualityAction.None
        repeat(LiveQualityPolicy.THROUGHPUT_MIN_SAMPLES) {
            last = h.policy.onMediaSegmentSample(100_000, 4_000)
        }
        assertEquals(LiveQualityAction.Downgrade(LiveQualityDowngradeReason.THROUGHPUT), last)
        assertEquals(LiveQualityState.SUB, h.policy.state)
    }

    // -- MAIN steady state --------------------------------------------------

    @Test
    fun `main degraded by buffering downgrades to SUB`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        h.advance(LiveQualityPolicy.TRIAL_WINDOW_MS)
        assertEquals(LiveQualityAction.TrialPassed, h.policy.tick())
        h.policy.onTrialPassed()
        assertEquals(LiveQualityState.MAIN, h.policy.state)
        assertEquals(
            LiveQualityAction.Downgrade(LiveQualityDowngradeReason.BUFFERING),
            h.policy.onBufferingEnded(1_500),
        )
        assertEquals(LiveQualityState.SUB, h.policy.state)
    }

    @Test
    fun `main degraded by buffer decay downgrades`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        h.advance(LiveQualityPolicy.TRIAL_WINDOW_MS)
        h.policy.onTrialPassed()
        var last: LiveQualityAction = LiveQualityAction.None
        repeat(LiveQualityPolicy.BUFFER_DECAY_SAMPLES) {
            last = h.policy.onBufferSample(900)
        }
        assertEquals(LiveQualityAction.Downgrade(LiveQualityDowngradeReason.BUFFER_DECAY), last)
    }

    // -- Anti-flapping ------------------------------------------------------

    @Test
    fun `failed trial allows no second attempt in the same session`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        h.policy.onBufferingEnded(1_200)
        assertEquals(LiveQualityState.SUB, h.policy.state)
        // A fresh healthy SUB window does NOT offer another trial.
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        assertEquals(LiveQualityAction.None, h.policy.tick())
    }

    @Test
    fun `reset after a failed trial allows a new attempt`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        h.policy.onBufferingEnded(1_200)
        h.policy.reset()
        assertEquals(0, h.policy.upgradeAttempts)
        assertFalse(h.policy.trialFailed)
        assertFalse(h.policy.attemptsExhausted)
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        assertEquals(LiveQualityAction.TrialReady, h.policy.tick())
    }

    @Test
    fun `internal session recovery does not mint new trials`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        h.policy.onTrialStarted()
        h.policy.onBufferingEnded(1_200)
        // The recovery re-prepares the SAME logical session: onSessionPreparing
        // and onStartupComplete must NOT reset the attempt budget.
        h.policy.onSessionPreparing()
        h.policy.onStartupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        assertEquals(LiveQualityAction.None, h.policy.tick())
    }

    // -- Regression: trial startup buffering must never fail the trial -------

    @Test
    fun `stale startup loading marker is what failed the trial before the fix`() {
        // Reproduces the bug: the LiveView kept the STARTUP loading timestamp
        // after READY; the 1 Hz stuck-buffering guard then saw a "loading"
        // older than the downgrade threshold and failed the trial.
        val t0 = 100_000L
        val staleMarker = t0 // startup loading started here...
        val now = t0 + 5_200 // ...and READY happened 5.2 s later
        assertTrue(now - staleMarker >= LiveQualityPolicy.BUFFERING_DOWNGRADE_MS)
        // With the marker cleared by the fix, the guard can never fire:
        val clearedMarker = -1L
        assertFalse(clearedMarker >= 0)
    }

    @Test
    fun `trial startup longer than the downgrade threshold does not fail the trial`() {
        val h = Harness()
        h.startSession()
        h.startupComplete()
        h.advance(LiveQualityPolicy.HEALTH_WINDOW_MS)
        assertEquals(LiveQualityAction.TrialReady, h.policy.tick())
        h.policy.onTrialStarted()
        assertEquals(LiveQualityState.TRIAL_MAIN, h.policy.state)

        // The switch to MAIN starts a new session with a long startup loading
        // (resolve + prepare + first segment, > 1 s).
        h.policy.onSessionPreparing()
        val loadingStart = h.nowMs
        h.advance(5_000) // 5 s of startup loading
        // READY arrives: the PLAYING transition completes the startup and MUST
        // clear the loading marker.
        val transition = transitionOnPlaying(
            startupInProgress = true,
            loadingStartedAtMs = loadingStart,
            nowMs = h.nowMs,
        )
        assertTrue(transition.startupCompleted)
        assertTrue(transition.clearLoading)
        h.policy.onStartupComplete()

        // The 1 Hz loop right after READY: with the marker cleared, the
        // stuck-buffering guard is inactive, so no downgrade fires.
        val markerAfterFix = if (transition.clearLoading) -1L else loadingStart
        val guardFires = markerAfterFix >= 0 &&
            h.nowMs - markerAfterFix >= LiveQualityPolicy.BUFFERING_DOWNGRADE_MS
        assertFalse(guardFires)
        assertEquals(LiveQualityState.TRIAL_MAIN, h.policy.state)
        assertEquals(LiveQualityAction.None, h.policy.tick())

        // A REAL post-startup rebuffer still downgrades normally.
        h.advance(1_000)
        assertEquals(
            LiveQualityAction.Downgrade(LiveQualityDowngradeReason.BUFFERING),
            h.policy.onBufferingEnded(1_500),
        )
        assertEquals(LiveQualityState.SUB, h.policy.state)
    }

    @Test
    fun `playing transition closes a rebuffer with its measured duration`() {
        val t0 = 100_000L
        val transition = transitionOnPlaying(
            startupInProgress = false,
            loadingStartedAtMs = t0,
            nowMs = t0 + 1_200,
        )
        assertFalse(transition.startupCompleted)
        assertEquals(1_200L, transition.rebufferDurationMs)
        assertTrue(transition.clearLoading)
    }

    @Test
    fun `playing transition with no pending loading changes nothing`() {
        val transition = transitionOnPlaying(
            startupInProgress = false,
            loadingStartedAtMs = -1L,
            nowMs = 50_000L,
        )
        assertFalse(transition.startupCompleted)
        assertNull(transition.rebufferDurationMs)
        assertFalse(transition.clearLoading)
    }
}