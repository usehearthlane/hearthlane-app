package org.hearthlane.ui

import org.hearthlane.core.connectivity.HttpBytesResult
import org.hearthlane.core.connectivity.TsnetGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deterministic (virtual-time) tests of [ForegroundTsnetLifecycle]'s adaptive
 * background idle policy: a background where the device is still interactively
 * used has a 60s grace (a return inside it never tears the node down); screen
 * off / device lock shortens that to 5s because the device is no longer
 * interactively used. When a grace expires the teardown runs exactly once and
 * the next foreground restarts the consumers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundTsnetLifecycleTest {

    private class RecordingGateway : TsnetGateway {
        var stopIfRunningCalls = 0
        var ensureRunningCalls = 0

        override suspend fun ensureRunning() {
            ensureRunningCalls++
        }

        override suspend fun stopIfRunning() {
            stopIfRunningCalls++
        }

        override suspend fun reset() = Unit
        override suspend fun httpGet(url: String, timeoutMs: Long): String = ""
        override suspend fun httpGetBytes(url: String, timeoutMs: Long) =
            HttpBytesResult(200, "application/json", url, byteArrayOf())
    }

    private val backgroundGrace = 60_000L
    private val screenOffGrace = 5_000L

    private fun lifecycle(
        gateway: RecordingGateway,
        started: MutableList<String>,
        stopped: MutableList<String>,
        scope: CoroutineScope,
        backgroundGraceMs: Long = backgroundGrace,
        screenOffGraceMs: Long = screenOffGrace,
    ): ForegroundTsnetLifecycle = ForegroundTsnetLifecycle(
        gateway = gateway,
        startConsumers = { started.add("start") },
        stopConsumers = { stopped.add("stop") },
        scope = scope,
        ioScope = scope,
        backgroundGraceMs = backgroundGraceMs,
        screenOffGraceMs = screenOffGraceMs,
    )

    @Test
    fun `a return after 30 seconds does not tear anything down`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onBackground()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("no teardown during a normal multitasking background", 0, stopped.size)
        assertEquals(0, gateway.stopIfRunningCalls)

        fg.onForeground()
        advanceTimeBy(backgroundGrace + 1)
        runCurrent()
        assertEquals(0, stopped.size)
        assertEquals(0, gateway.stopIfRunningCalls)
        assertEquals("a quick return must not re-start the consumers", 1, started.size)
    }

    @Test
    fun `no teardown at 59 point 999 seconds`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onBackground()
        advanceTimeBy(backgroundGrace - 1)
        runCurrent()
        assertEquals(0, stopped.size)
        assertEquals(0, gateway.stopIfRunningCalls)
    }

    @Test
    fun `teardown runs exactly once at the 60 second boundary`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onBackground()
        advanceTimeBy(backgroundGrace)
        runCurrent()

        assertEquals("the teardown runs once after the 60s grace", listOf("stop"), stopped)
        assertEquals(1, gateway.stopIfRunningCalls)
    }

    @Test
    fun `teardown at 60 seconds is followed by exactly one foreground restart`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onBackground()
        advanceTimeBy(backgroundGrace)
        runCurrent()
        assertEquals(listOf("stop"), stopped)

        fg.onForeground()
        assertEquals("the next foreground re-starts the consumers", listOf("start", "start"), started)
    }

    @Test
    fun `only the latest background generation is valid`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onBackground() // generation 1
        fg.onForeground() // quick return cancels generation 1
        fg.onBackground() // generation 2
        advanceTimeBy(backgroundGrace)
        runCurrent()

        assertEquals("only the latest timer expires", listOf("stop"), stopped)
        assertEquals(1, gateway.stopIfRunningCalls)
    }

    @Test
    fun `screen off during a background shortens the teardown to 5 seconds`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onBackground() // 60s window starts
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, stopped.size)

        fg.onScreenOff() // device locked / display off -> shorten
        advanceTimeBy(screenOffGrace)
        runCurrent()

        assertEquals("screen off tears down after the short window", listOf("stop"), stopped)
        assertEquals(1, gateway.stopIfRunningCalls)
    }

    @Test
    fun `screen off then ON_START never runs the pending timer later`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onScreenOff()
        fg.onForeground() // quick wake cancels the screen-off timer
        advanceTimeBy(backgroundGrace + screenOffGrace)
        runCurrent()

        assertEquals(0, stopped.size)
        assertEquals(0, gateway.stopIfRunningCalls)
        assertEquals("the quick wake must not re-start consumers", 1, started.size)
    }

    @Test
    fun `screen off while foreground schedules a short teardown that is cancelled by a quick wake`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onScreenOff() // display-off while the activity is still foreground
        advanceTimeBy(screenOffGrace - 1)
        runCurrent()
        assertEquals(0, stopped.size)

        fg.onForeground() // wake before the short window expires
        advanceTimeBy(screenOffGrace + 1)
        runCurrent()
        assertEquals("a quick wake cancels the screen-off teardown", 0, stopped.size)
        assertEquals(0, gateway.stopIfRunningCalls)
    }

    @Test
    fun `screen off while foreground tears down after the short window and restarts on foreground`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onScreenOff() // display-off: the device is not interactively used
        advanceTimeBy(screenOffGrace)
        runCurrent()

        assertEquals("the short screen-off window tears the stack down", listOf("stop"), stopped)
        assertEquals(1, gateway.stopIfRunningCalls)

        fg.onForeground()
        assertEquals("the next foreground restarts the consumers", listOf("start", "start"), started)
    }

    @Test
    fun `a screen off during background never extends back to the 60 second window`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onScreenOff() // screen-off first (shorter window)
        fg.onBackground() // ON_STOP arrives afterwards: must keep the shorter window
        advanceTimeBy(screenOffGrace)
        runCurrent()

        assertEquals("the screen-off window is not extended by a later ON_STOP", listOf("stop"), stopped)
        assertEquals(1, gateway.stopIfRunningCalls)
    }

    @Test
    fun `dispose cancels the pending stop and tears down immediately`() = runTest {
        val gateway = RecordingGateway()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val fg = lifecycle(gateway, started, stopped, this)

        fg.onForeground()
        fg.onBackground() // pending stop scheduled
        fg.dispose() // real exit: immediate teardown
        runCurrent()

        assertEquals(listOf("stop"), stopped)
        assertEquals(1, gateway.stopIfRunningCalls)
        advanceTimeBy(backgroundGrace + 1)
        runCurrent()
        assertEquals("no stale timer runs after dispose", 1, gateway.stopIfRunningCalls)
    }
}