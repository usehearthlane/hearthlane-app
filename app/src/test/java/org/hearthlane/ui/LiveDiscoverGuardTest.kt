package org.hearthlane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests of the duplicate-discover guard ([isStaleDiscover]): a
 * discover attempt that resolved its URL after a newer discover ran must never
 * play (duplicate MediaSource/prepare and a second go2rtc session).
 */
class LiveDiscoverGuardTest {

    @Test
    fun `the current generation is not stale`() {
        assertFalse(isStaleDiscover(currentCount = 2, myCount = 2))
    }

    @Test
    fun `a superseded generation is stale`() {
        assertTrue(isStaleDiscover(currentCount = 2, myCount = 1))
        assertTrue(isStaleDiscover(currentCount = 3, myCount = 2))
    }
}