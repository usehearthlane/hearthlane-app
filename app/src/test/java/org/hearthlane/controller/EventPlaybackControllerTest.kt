package org.hearthlane.controller

import android.content.Context
import androidx.media3.common.util.UnstableApi
import org.hearthlane.core.connectivity.HttpStream
import org.hearthlane.core.connectivity.HttpStreamGetter
import org.hearthlane.core.playback.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robolectric tests for [EventPlaybackController]: it must forward the resolved
 * clip URL to the player and delegate lifecycle, exposing the player state.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventPlaybackControllerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val clipUrl = "http://frigate:5000/api/events/evt-1/clip.mp4"

    @Test
    fun `play forwards the resolved clip URL to the player`() {
        val getter = RecordingGetter(statusCode = 404)
        val controller = EventPlaybackController(
            context = context,
            getter = getter,
            clipUrl = { clipUrl },
        )

        controller.play()
        waitUntil { getter.openedUrls.isNotEmpty() }

        assertEquals(clipUrl, getter.openedUrls.first())
        controller.release()
    }

    @Test
    fun `state starts idle and transitions to loading on play`() {
        val controller = EventPlaybackController(
            context = context,
            getter = RecordingGetter(),
            clipUrl = { clipUrl },
        )

        assertEquals(PlaybackStatus.Idle, controller.state.value)
        controller.play()
        assertEquals(PlaybackStatus.Loading, controller.state.value)
        controller.release()
    }

    @Test
    fun `release and stop do not throw`() {
        val controller = EventPlaybackController(
            context = context,
            getter = RecordingGetter(),
            clipUrl = { clipUrl },
        )

        controller.play()
        controller.stop()
        controller.release()
    }

    private fun waitUntil(timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            if (predicate()) return
            Thread.sleep(5)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private class RecordingGetter(
        var statusCode: Int = 200,
    ) : HttpStreamGetter {
        val openedUrls = mutableListOf<String>()

        override suspend fun open(url: String, connectTimeoutMs: Long, headers: Map<String, String>): HttpStream {
            openedUrls.add(url)
            return RecordingStream(statusCode, "video/mp4", url)
        }
    }

    private class RecordingStream(
        override val statusCode: Int,
        override val contentType: String?,
        override val finalUrl: String,
    ) : HttpStream {
        private val closed = AtomicBoolean(false)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed.get()) return -1
            return -1
        }

        override fun close() {
            closed.set(true)
        }
    }
}