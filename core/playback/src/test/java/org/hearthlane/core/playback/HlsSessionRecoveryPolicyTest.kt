package org.hearthlane.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests of the early session-recovery policy
 * ([HlsSessionRecoveryPolicy]) and the dead-playlist routing predicate
 * ([isDeadPlaylistError]): only consecutive MEDIA_PLAYLIST 404/410 in the
 * current session trigger a single, backoff-bounded recovery; successes reset
 * the counter; stale callbacks from an old session are ignored.
 */
class HlsSessionRecoveryPolicyTest {

    private fun policy(now: () -> Long) = HlsSessionRecoveryPolicy(nowMs = now)

    @Test
    fun `the first playlist 404 does not trigger a recovery`() {
        var now = 0L
        val p = policy { now }
        p.onPlaylistLoadError(404)
        assertEquals(1, p.playlistErrors)
        assertFalse(p.canTriggerRecovery())
    }

    @Test
    fun `the second consecutive playlist 404 triggers exactly once`() {
        var now = 0L
        val p = policy { now }
        p.onPlaylistLoadError(404)
        p.onPlaylistLoadError(404)
        assertTrue(p.canTriggerRecovery())
        p.beginRecovery()
        assertFalse("single-flight: a second recovery must not arm", p.canTriggerRecovery())
    }

    @Test
    fun `a playlist success between errors resets the counter`() {
        var now = 0L
        val p = policy { now }
        p.onPlaylistLoadError(404)
        p.onPlaylistLoadSuccess()
        assertEquals(0, p.playlistErrors)
        p.onPlaylistLoadError(404)
        assertFalse("after a reset a single 404 is not enough", p.canTriggerRecovery())
    }

    @Test
    fun `410 has the same semantics as 404`() {
        var now = 0L
        val p = policy { now }
        p.onPlaylistLoadError(410)
        p.onPlaylistLoadError(410)
        assertTrue(p.canTriggerRecovery())
    }

    @Test
    fun `a new session resets errors and bumps the epoch`() {
        var now = 0L
        val p = policy { now }
        p.onPlaylistLoadError(404)
        p.onPlaylistLoadError(404)
        assertEquals(1, p.newSession())
        assertEquals(0, p.playlistErrors)
        assertEquals("the new epoch is current", true, p.isCurrentEpoch(1))
        assertEquals("the old epoch is stale", false, p.isCurrentEpoch(0))
    }

    @Test
    fun `a recovery is deferred inside the minimum interval and armed after it`() {
        var now = 0L
        val p = policy { now }
        p.onPlaylistLoadError(404)
        p.onPlaylistLoadError(404)
        p.beginRecovery() // now = 0, recovery in flight

        // The new session is confirmed alive: in-flight cleared, counter reset.
        p.onRecoveryConfirmed()
        assertFalse(p.recoveryInFlight)
        assertEquals(0, p.playlistErrors)

        // The new session dies fast: two more 404s, but inside the backoff.
        p.onPlaylistLoadError(404)
        p.onPlaylistLoadError(404)
        now = 1_000
        assertFalse(p.canTriggerRecovery())
        assertTrue(p.backoffRemainingMs() > 0)

        now = 5_001
        assertTrue("the backoff has elapsed, recovery is armed again", p.canTriggerRecovery())
    }

    @Test
    fun `confirmation clears the in-flight flag and the counter`() {
        var now = 0L
        val p = policy { now }
        p.onPlaylistLoadError(404)
        p.onPlaylistLoadError(404)
        p.beginRecovery()
        assertTrue(p.recoveryInFlight)

        now = 500
        val elapsed = p.onRecoveryConfirmed()
        assertFalse(p.recoveryInFlight)
        assertEquals(0, p.playlistErrors)
        assertEquals(500L, elapsed)
    }

    @Test
    fun `isDeadPlaylistError routes only media-playlist 404 and 410`() {
        assertTrue(isDeadPlaylistError("MEDIA_PLAYLIST", 404))
        assertTrue(isDeadPlaylistError("MEDIA_PLAYLIST", 410))
        assertFalse(isDeadPlaylistError("MEDIA_PLAYLIST", 500))
        assertFalse("a segment 404 must not count for this trigger", isDeadPlaylistError("MEDIA_SEGMENT", 404))
        assertFalse("an init 404 must not count for this trigger", isDeadPlaylistError("INIT_SEGMENT", 404))
        assertFalse("a master 404 must not count for this trigger", isDeadPlaylistError("MASTER", 404))
    }
}