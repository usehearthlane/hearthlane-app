package org.hearthlane.core.frigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deterministic tests of the live stream selection policy: LOCAL prefers the
 * camera's main stream, TAILSCALE prefers `<cameraId>_sub` when it exists in
 * go2rtc and falls back to the main stream otherwise. Selection is always by
 * exact name equality (never substring/prefix).
 */
class LiveStreamSelectionTest {

    @Test
    fun `local main preference selects the camera main stream`() {
        val streams = setOf("hall", "hall_sub")
        assertEquals(
            "hall",
            selectLiveStreamName("hall", streams, LiveStreamPreference.MAIN),
        )
    }

    @Test
    fun `remote sub preference selects the camera substream when available`() {
        val streams = setOf("hall", "hall_sub")
        assertEquals(
            "hall_sub",
            selectLiveStreamName("hall", streams, LiveStreamPreference.REMOTE_SUB),
        )
    }

    @Test
    fun `remote sub preference falls back to main when no substream exists`() {
        val streams = setOf("hall")
        assertEquals(
            "hall",
            selectLiveStreamName("hall", streams, LiveStreamPreference.REMOTE_SUB),
        )
    }

    @Test
    fun `selection is exact - camera ids that are prefixes of other names do not match`() {
        val streams = setOf("hall", "hallway", "hall_sub", "hallway_sub")
        // "hall" must match "hall" only, never "hallway" or "hall_sub".
        assertEquals("hall", selectLiveStreamName("hall", streams, LiveStreamPreference.MAIN))
        assertEquals("hall_sub", selectLiveStreamName("hall", streams, LiveStreamPreference.REMOTE_SUB))
        // "hallway" is its own camera; the `_sub` suffix is exact, not a substring hit.
        assertEquals("hallway", selectLiveStreamName("hallway", streams, LiveStreamPreference.MAIN))
        assertEquals("hallway_sub", selectLiveStreamName("hallway", streams, LiveStreamPreference.REMOTE_SUB))
    }

    @Test
    fun `unplayable camera returns null under every preference`() {
        val streams = setOf("hall", "hall_sub")
        assertNull(selectLiveStreamName("missing", streams, LiveStreamPreference.MAIN))
        assertNull(selectLiveStreamName("missing", streams, LiveStreamPreference.REMOTE_SUB))
    }

    @Test
    fun `recovery re-selects the same stream under the same preference`() {
        // The early session-death recovery re-runs discoverAndPlay, which calls
        // the same deterministic policy; the result must be identical.
        val streams = setOf("hall", "hall_sub")
        val first = selectLiveStreamName("hall", streams, LiveStreamPreference.REMOTE_SUB)
        val recovery = selectLiveStreamName("hall", streams, LiveStreamPreference.REMOTE_SUB)
        assertEquals("hall_sub", first)
        assertEquals(first, recovery)
    }
}