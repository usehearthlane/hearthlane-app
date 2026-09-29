package org.hearthlane.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests of [parseLivePlaylistSummary]: the sanitized diagnostic
 * only extracts numeric/boolean tags (target duration, part target, HOLD-BACK,
 * PART-HOLD-BACK, CAN-BLOCK-RELOAD, counts, window duration) and never the URL,
 * query string or session id.
 */
class LivePlaylistSummaryTest {

    private val llHls = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-TARGETDURATION:2
        #EXT-X-PART-INF:PART-TARGET=0.5
        #EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=1.5,HOLD-BACK=5.0
        #EXT-X-PART:DURATION=0.5,URI="part1.m4s"
        #EXT-X-PART:DURATION=0.5,URI="part2.m4s"
        #EXTINF:2.0,label
        segment1.m4s
        #EXT-X-PART:DURATION=0.5,URI="part3.m4s"
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `parses LL-HLS tags and window duration`() {
        val s = parseLivePlaylistSummary(llHls)
        assertEquals(2000L, s.targetDurationMs)
        assertEquals(500L, s.partTargetDurationMs)
        assertEquals(5000L, s.serverHoldBackMs)
        assertEquals(1500L, s.partHoldBackMs)
        assertEquals(true, s.canBlockReload)
        assertEquals(1, s.segmentCount)
        assertEquals(3, s.partCount)
        assertEquals((3 * 500 + 2000L), s.windowDurationMs)
    }

    @Test
    fun `absent tags stay null and never crash`() {
        val s = parseLivePlaylistSummary("#EXTM3U\n#EXT-X-ENDLIST\n")
        assertNull(s.targetDurationMs)
        assertNull(s.partTargetDurationMs)
        assertNull(s.serverHoldBackMs)
        assertNull(s.partHoldBackMs)
        assertNull(s.canBlockReload)
        assertEquals(0, s.segmentCount)
        assertEquals(0, s.partCount)
        assertNull(s.windowDurationMs)
    }

    @Test
    fun `window duration sums segment and part durations only when present`() {
        val s = parseLivePlaylistSummary(
            """
            #EXTM3U
            #EXTINF:4.0,a
            seg.ts
            #EXTINF:1.5,b
            seg2.ts
            """.trimIndent(),
        )
        assertEquals(2, s.segmentCount)
        assertEquals(0, s.partCount)
        assertEquals(5500L, s.windowDurationMs)
        assertTrue("missing target duration must not crash", s.targetDurationMs == null)
    }
}