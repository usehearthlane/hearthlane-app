package org.hearthlane.controller

import android.content.Context
import androidx.media3.common.util.UnstableApi
import org.hearthlane.core.connectivity.HttpBytesGetter
import org.hearthlane.core.connectivity.HttpBytesResult
import org.hearthlane.core.connectivity.HttpStream
import org.hearthlane.core.connectivity.HttpStreamGetter
import org.hearthlane.core.frigate.FrigateEventApi
import org.hearthlane.core.playback.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robolectric tests for [EventDetailController]: the event-loading contract and
 * the embedded playback coordination (play/retry/error classification/release).
 * No real video is played; a fake stream getter drives the DataSource error
 * paths.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventDetailControllerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private val clipUrl = "http://frigate:5000/api/events/evt-1/clip.mp4"

    @Test
    fun `starts in Loading state`() = runTest {
        val controller = controller(RoutingGetter(), scope = backgroundScope)

        assertEquals(EventDetailState.Loading, controller.state.value)
    }

    @Test
    fun `load loads the event`() = runTest {
        val controller = controller(RoutingGetter(), scope = backgroundScope)

        controller.load()
        runCurrent()

        val state = controller.state.value as EventDetailState.Loaded
        assertEquals("evt-1", state.event.id)
        assertEquals("person", state.event.label)
        assertEquals("backyard", state.event.cameraId)
    }

    @Test
    fun `a missing event maps to NotFound`() = runTest {
        val controller = controller(
            RoutingGetter(statusCode = 404, body = """"Event not found""""),
            scope = backgroundScope,
        )

        controller.load()
        runCurrent()

        assertEquals(
            "a 404 must surface as NotFound, not as a generic error",
            EventDetailState.NotFound,
            controller.state.value,
        )
    }

    @Test
    fun `a transport failure maps to Error`() = runTest {
        val controller = controller(RoutingGetter(fail = true), scope = backgroundScope)

        controller.load()
        runCurrent()

        assertTrue(controller.state.value is EventDetailState.Error)
    }

    @Test
    fun `retry after an error loads the event`() = runTest {
        val getter = RoutingGetter(fail = true)
        val controller = controller(getter, scope = backgroundScope)

        controller.load()
        runCurrent()
        assertTrue(controller.state.value is EventDetailState.Error)

        getter.fail = false
        controller.load()
        runCurrent()

        assertTrue(controller.state.value is EventDetailState.Loaded)
    }

    @Test
    fun `an in-progress event keeps a null endTime`() = runTest {
        val controller = controller(
            RoutingGetter(body = eventJson(endTime = null)),
            scope = backgroundScope,
        )

        controller.load()
        runCurrent()

        val event = (controller.state.value as EventDetailState.Loaded).event
        assertNull("an in-progress event has a null endTime", event.endTime)
    }

    @Test
    fun `an event without a snapshot is loaded`() = runTest {
        val controller = controller(
            RoutingGetter(body = eventJson(hasSnapshot = false)),
            scope = backgroundScope,
        )

        controller.load()
        runCurrent()

        val event = (controller.state.value as EventDetailState.Loaded).event
        assertFalse(event.hasSnapshot)
    }

    @Test
    fun `an event with a clip is loaded`() = runTest {
        val controller = controller(
            RoutingGetter(body = eventJson(hasClip = true)),
            scope = backgroundScope,
        )

        controller.load()
        runCurrent()

        assertTrue((controller.state.value as EventDetailState.Loaded).event.hasClip)
    }

    @Test
    fun `an event without a clip is loaded`() = runTest {
        val controller = controller(
            RoutingGetter(body = eventJson(hasClip = false)),
            scope = backgroundScope,
        )

        controller.load()
        runCurrent()

        assertFalse((controller.state.value as EventDetailState.Loaded).event.hasClip)
    }

    @Test
    fun `load keeps the sub-second start time precision`() = runTest {
        val controller = controller(
            RoutingGetter(body = eventJson(startTime = 1787072293.499881)),
            scope = backgroundScope,
        )

        controller.load()
        runCurrent()

        val event = (controller.state.value as EventDetailState.Loaded).event
        assertEquals(1787072293.499881, event.startTime, 0.0)
    }

    @Test
    fun `loading a clip event starts playback automatically`() = runTest {
        val controller = controller(RoutingGetter(), scope = backgroundScope)

        controller.load()
        runCurrent()

        assertEquals(
            "opening an event with a clip must play it immediately",
            PlaybackStatus.Loading,
            controller.playbackState.value,
        )
    }

    @Test
    fun `loading an event without a clip does not start playback`() = runTest {
        val controller = controller(
            RoutingGetter(body = eventJson(hasClip = false)),
            scope = backgroundScope,
        )

        controller.load()
        runCurrent()

        assertEquals(
            "an event without a clip must not start playback",
            PlaybackStatus.Idle,
            controller.playbackState.value,
        )
    }

    @Test
    fun `a clip event does not autoplay when the preference is disabled`() = runTest {
        val controller = controller(
            RoutingGetter(body = eventJson(hasClip = true)),
            scope = backgroundScope,
            autoPlayEventClips = { false },
        )

        controller.load()
        runCurrent()

        assertEquals(
            "opening a clip event with autoplay off must stay idle",
            PlaybackStatus.Idle,
            controller.playbackState.value,
        )
    }

    @Test
    fun `a no-clip event does not autoplay regardless of the preference`() = runTest {
        val controller = controller(
            RoutingGetter(body = eventJson(hasClip = false)),
            scope = backgroundScope,
            autoPlayEventClips = { false },
        )

        controller.load()
        runCurrent()

        assertEquals(PlaybackStatus.Idle, controller.playbackState.value)
    }

    @Test
    fun `manual play still starts playback when autoplay is disabled`() {
        val controller = controller(
            RoutingGetter(body = eventJson(hasClip = true)),
            scope = CoroutineScope(Dispatchers.Unconfined),
            autoPlayEventClips = { false },
        )

        assertEquals(PlaybackStatus.Idle, controller.playbackState.value)
        controller.play()
        assertEquals(
            "manual Play must start the embedded player even with autoplay off",
            PlaybackStatus.Loading,
            controller.playbackState.value,
        )
        controller.release()
    }

    @Test
    fun `play starts playback without navigating`() {
        val controller = controller(RoutingGetter(), scope = CoroutineScope(Dispatchers.Unconfined))

        assertEquals(PlaybackStatus.Idle, controller.playbackState.value)
        controller.play()
        assertEquals(
            "Play must start the embedded player (Loading) in place",
            PlaybackStatus.Loading,
            controller.playbackState.value,
        )
        controller.release()
    }

    @Test
    fun `a playback HTTP 404 surfaces an error carrying the status`() {
        val controller = controller(
            RoutingGetter(),
            streaming = StreamingGetter(statusCode = 404),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        controller.play()
        val error = waitForPlaybackError(controller)

        assertEquals(404, error.statusCode)
        controller.release()
    }

    @Test
    fun `a playback transport failure surfaces an error without a status`() {
        val controller = controller(
            RoutingGetter(),
            streaming = StreamingGetter(throwOnOpen = IOException("connection refused")),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        controller.play()
        val error = waitForPlaybackError(controller)

        assertNull(error.statusCode)
        controller.release()
    }

    @Test
    fun `retry after a playback error restarts playback`() {
        val controller = controller(
            RoutingGetter(),
            streaming = StreamingGetter(statusCode = 404),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        controller.play()
        waitForPlaybackError(controller)

        controller.play()
        assertEquals("Retry must restart the embedded player", PlaybackStatus.Loading, controller.playbackState.value)
        controller.release()
    }

    @Test
    fun `release does not throw`() {
        val controller = controller(RoutingGetter(), scope = CoroutineScope(Dispatchers.Unconfined))

        controller.play()
        controller.release()
    }

    private fun waitForPlaybackError(
        controller: EventDetailController,
        timeoutMs: Long = 20_000,
    ): PlaybackStatus.Error {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            if (controller.playbackState.value is PlaybackStatus.Error) {
                return controller.playbackState.value as PlaybackStatus.Error
            }
            Thread.sleep(5)
        }
        throw AssertionError("playback did not reach Error within ${timeoutMs}ms")
    }

    private fun controller(
        getter: HttpBytesGetter,
        streaming: HttpStreamGetter = StreamingGetter(),
        scope: CoroutineScope,
        autoPlayEventClips: () -> Boolean = { true },
    ): EventDetailController = EventDetailController(
        context = context,
        api = FrigateEventApi(getter),
        eventId = "evt-1",
        baseUrl = { "http://frigate:5000" },
        getter = streaming,
        clipUrl = { clipUrl },
        autoPlayEventClips = autoPlayEventClips,
        scope = scope,
    )

    private class RoutingGetter(
        var body: String = eventJson(),
        var statusCode: Int = 200,
        var fail: Boolean = false,
    ) : HttpBytesGetter {
        override suspend fun getBytes(url: String, timeoutMs: Long): HttpBytesResult {
            if (fail) throw IOException("connection refused")
            return HttpBytesResult(statusCode, "application/json", url, body.toByteArray())
        }
    }

    private class StreamingGetter(
        var statusCode: Int = 200,
        var throwOnOpen: Exception? = null,
    ) : HttpStreamGetter {
        override suspend fun open(url: String, connectTimeoutMs: Long, headers: Map<String, String>): HttpStream {
            throwOnOpen?.let { throw it }
            return EmptyStream(statusCode, "video/mp4", url)
        }
    }

    private class EmptyStream(
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

private fun eventJson(
    startTime: Double = 1787072293.5,
    endTime: Double? = 1787072303.5,
    hasClip: Boolean = true,
    hasSnapshot: Boolean = true,
): String {
    val end = endTime?.let { ", \"end_time\":$it" } ?: ", \"end_time\":null"
    return """{"id":"evt-1","camera":"backyard","label":"person","start_time":$startTime$end,"has_clip":$hasClip,"has_snapshot":$hasSnapshot,"zones":["yard"]}"""
}
