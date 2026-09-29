package org.hearthlane.core.frigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the quality-mode stream selection helpers
 * ([initialQualityLevel], [streamNameForQualityLevel], [cameraStreamSelection]).
 */
class LiveQualitySelectionTest {

    // -- Initial level per mode/transport -----------------------------------

    @Test
    fun `AUTO starts on SUB remotely and MAIN locally`() {
        assertEquals(LiveQualityLevel.SUB, initialQualityLevel(LiveQualityMode.AUTO, remote = true))
        assertEquals(LiveQualityLevel.MAIN, initialQualityLevel(LiveQualityMode.AUTO, remote = false))
    }

    @Test
    fun `HIGH always starts on MAIN`() {
        assertEquals(LiveQualityLevel.MAIN, initialQualityLevel(LiveQualityMode.HIGH, remote = true))
        assertEquals(LiveQualityLevel.MAIN, initialQualityLevel(LiveQualityMode.HIGH, remote = false))
    }

    @Test
    fun `ECONOMY always starts on SUB`() {
        assertEquals(LiveQualityLevel.SUB, initialQualityLevel(LiveQualityMode.ECONOMY, remote = true))
        assertEquals(LiveQualityLevel.SUB, initialQualityLevel(LiveQualityMode.ECONOMY, remote = false))
    }

    // -- Stream name per level ----------------------------------------------

    @Test
    fun `MAIN level selects the camera stream by exact match`() {
        val available = setOf("hall", "hall_sub", "backyard", "hallway")
        assertEquals("hall", streamNameForQualityLevel("hall", available, LiveQualityLevel.MAIN))
    }

    @Test
    fun `SUB level selects the substream by exact match`() {
        val available = setOf("hall", "hall_sub")
        assertEquals("hall_sub", streamNameForQualityLevel("hall", available, LiveQualityLevel.SUB))
    }

    @Test
    fun `SUB level falls back to the main stream when the substream is absent`() {
        val available = setOf("hall", "backyard_sub")
        assertEquals("hall", streamNameForQualityLevel("hall", available, LiveQualityLevel.SUB))
    }

    @Test
    fun `substream prefix names never match the exact convention`() {
        val available = setOf("hall", "hallway_sub", "hall_sub_extra", "sub")
        assertEquals("hall", streamNameForQualityLevel("hall", available, LiveQualityLevel.SUB))
    }

    @Test
    fun `camera without a playable stream yields null`() {
        val available = setOf("backyard", "backyard_sub")
        assertNull(streamNameForQualityLevel("hall", available, LiveQualityLevel.MAIN))
        assertNull(streamNameForQualityLevel("hall", available, LiveQualityLevel.SUB))
    }

    // -- Camera stream selection --------------------------------------------

    @Test
    fun `camera stream selection exposes main and sub`() {
        assertEquals(
            CameraStreams("hall", "hall_sub"),
            cameraStreamSelection("hall", setOf("hall", "hall_sub", "backyard")),
        )
    }

    @Test
    fun `camera stream selection reports a missing substream`() {
        assertEquals(
            CameraStreams("hall", null),
            cameraStreamSelection("hall", setOf("hall")),
        )
    }

    @Test
    fun `camera stream selection is null when the camera is not playable`() {
        assertNull(cameraStreamSelection("hall", setOf("backyard", "backyard_sub")))
    }
}