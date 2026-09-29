package org.hearthlane.core.playback

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic tests of the conservative live-jitter tuning wiring
 * ([buildLiveMediaItem]): a live target offset is applied to the MediaItem ONLY
 * when opted in (TAILSCALE passes a value; LOCAL passes null and keeps the
 * current default).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveMediaItemTest {

    private val url = "http://frigate:5000/api/go2rtc/api/playlist.m3u8?id=x"

    @Test
    fun `a null target offset keeps the default live configuration`() {
        val item = buildLiveMediaItem(url, liveTargetOffsetMs = null)
        assertEquals(url, item.localConfiguration!!.uri.toString())
        // Default LiveConfiguration has no target offset override (TIME_UNSET).
        assertEquals(-9223372036854775807L, item.liveConfiguration.targetOffsetMs)
    }

    @Test
    fun `a target offset for the remote path is applied`() {
        val item = buildLiveMediaItem(url, liveTargetOffsetMs = 2_500L)
        assertEquals(2_500L, item.liveConfiguration.targetOffsetMs)
    }
}