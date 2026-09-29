package org.hearthlane.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.hearthlane.controller.EventDetailController
import org.hearthlane.core.connectivity.HttpBytesGetter
import org.hearthlane.core.connectivity.HttpBytesResult
import org.hearthlane.core.connectivity.HttpStream
import org.hearthlane.core.connectivity.HttpStreamGetter
import org.hearthlane.core.frigate.FrigateConnection
import org.hearthlane.core.frigate.FrigateEventApi
import org.hearthlane.core.frigate.TransportKind
import org.hearthlane.core.playback.PlaybackStatus
import org.hearthlane.test.FakeTsnetGateway
import org.hearthlane.test.fakeSnapshotImageLoader
import org.hearthlane.thumbnail.CameraThumbnailModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Robolectric/Compose tests for the [EventDetailScreen] contract with embedded
 * auto-play: a clip event plays immediately in the player view (no thumbnail),
 * events without a clip fall back to the snapshot, and playback failures show a
 * friendly message with Retry in place.
 *
 * The controller runs on the runTest scheduler (same pattern as
 * RecentEventsSectionTest) so its state updates are synchronized with the
 * Compose test clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val imageLoader = fakeSnapshotImageLoader(context)
    private val factory = CameraThumbnailModelFactory(
        connection = MutableStateFlow(FrigateConnection.Connected(TransportKind.LOCAL, "0.17.1")),
        gateway = FakeTsnetGateway(),
    )

    private fun TestScope.createController(
        getter: HttpBytesGetter,
        streaming: HttpStreamGetter = EmptyStreamGetter(),
        autoPlayEventClips: Boolean = true,
    ): EventDetailController = EventDetailController(
        context = context,
        api = FrigateEventApi(getter),
        eventId = "evt-1",
        baseUrl = { "http://frigate:5000" },
        getter = streaming,
        clipUrl = { "http://frigate:5000/api/events/evt-1/clip.mp4" },
        autoPlayEventClips = { autoPlayEventClips },
        scope = this,
    )

    private fun TestScope.loadAndIdle(controller: EventDetailController) {
        controller.load()
        advanceUntilIdle()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `loading state shows the loading message`() = runTest {
        val controller = createController(EventGetter())

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Loading event…").assertIsDisplayed()
    }

    @Test
    fun `a no-clip event shows the snapshot and metadata`() = runTest {
        val controller = createController(EventGetter(body = noClipEvent))

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        composeTestRule.onNodeWithContentDescription("Person").assertIsDisplayed()
        composeTestRule.onNodeWithText("Person").assertIsDisplayed()
        composeTestRule.onNodeWithText("10s").assertIsDisplayed()
        composeTestRule.onNodeWithText("yard").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun `pt-br locale renders the translated label`() = runTest {
        val controller = createController(EventGetter(body = noClipEvent))

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        composeTestRule.onNodeWithText("Pessoa").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Pessoa").assertIsDisplayed()
    }

    @Test
    fun `a no-clip event without a snapshot shows the placeholder`() = runTest {
        val controller = createController(EventGetter(body = noClipNoSnapshotEvent))

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        composeTestRule.onNodeWithText("Person").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Person").assertDoesNotExist()
    }

    @Test
    fun `the camera display name is shown when available`() = runTest {
        val controller = createController(EventGetter(body = noClipEvent))

        composeTestRule.setContent { Screen(controller, cameraDisplayName = "Backyard cam") }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        composeTestRule.onNodeWithText("Backyard cam").assertIsDisplayed()
    }

    @Test
    fun `not found state shows the unavailable message`() = runTest {
        val controller = createController(
            EventGetter(statusCode = 404, body = """"Event not found""""),
        )

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        composeTestRule.onNodeWithText("This event is no longer available.").assertIsDisplayed()
    }

    @Test
    fun `error state shows retry and retrying loads the event`() = runTest {
        val getter = EventGetter(fail = true)
        val controller = createController(getter)

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        composeTestRule.onNodeWithText("Could not load this event.").assertIsDisplayed()

        getter.fail = false
        composeTestRule.onNodeWithText("Retry").performClick()
        advanceUntilIdle()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Person").assertIsDisplayed()
    }

    @Test
    fun `an in-progress no-clip event shows the in-progress label`() = runTest {
        val controller = createController(EventGetter(body = noClipInProgressEvent))

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        composeTestRule.onNodeWithText("In progress").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a clip event plays automatically in the player view`() = runTest {
        val controller = createController(
            EventGetter(body = clipEvent),
            streaming = BlockingStreamGetter(),
        )

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        assertTrue(
            "opening a clip event must start playback automatically",
            controller.playbackState.value is PlaybackStatus.Loading,
        )
        composeTestRule.onNodeWithTag("event_player_view").assertExists()
        controller.release()
    }

    @Test
    fun `a playback failure shows a friendly message and retry`() = runTest {
        val controller = createController(
            EventGetter(body = clipEvent),
            streaming = ErrorStreamGetter(statusCode = 404),
        )

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)
        waitUntil { controller.playbackState.value is PlaybackStatus.Error }

        composeTestRule.onNodeWithText("This event is no longer available.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
        controller.release()
    }

    @Test
    fun `no play button is shown for a clip event`() = runTest {
        val controller = createController(EventGetter(body = clipEvent))

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        composeTestRule.onNodeWithText("Play").assertDoesNotExist()
        composeTestRule.onNodeWithText("Play again").assertDoesNotExist()
        controller.release()
    }

    @Test
    fun `with autoplay disabled a clip event shows the play affordance and no player`() = runTest {
        val controller = createController(
            EventGetter(body = clipEvent),
            autoPlayEventClips = false,
        )

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        assertTrue(
            "opening a clip event with autoplay off must stay idle",
            controller.playbackState.value == PlaybackStatus.Idle,
        )
        composeTestRule.onNodeWithContentDescription("Play event").assertIsDisplayed()
        composeTestRule.onNodeWithTag("event_player_view").assertDoesNotExist()
        controller.release()
    }

    @Test
    fun `tapping the play affordance starts playback`() = runTest {
        val controller = createController(
            EventGetter(body = clipEvent),
            streaming = BlockingStreamGetter(),
            autoPlayEventClips = false,
        )

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        composeTestRule.onNodeWithContentDescription("Play event").performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            "tapping the affordance must start the embedded player",
            controller.playbackState.value is PlaybackStatus.Loading,
        )
        composeTestRule.onNodeWithTag("event_player_view").assertExists()
        controller.release()
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun `pt-br play affordance uses the localized label`() = runTest {
        val controller = createController(
            EventGetter(body = clipEvent),
            autoPlayEventClips = false,
        )

        composeTestRule.setContent { Screen(controller) }
        composeTestRule.waitForIdle()
        loadAndIdle(controller)

        composeTestRule.onNodeWithContentDescription("Reproduzir evento").assertIsDisplayed()
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

    @Composable
    private fun Screen(
        controller: EventDetailController,
        cameraDisplayName: String? = null,
    ) {
        EventDetailScreen(
            controller = controller,
            thumbnailFactory = factory,
            snapshotImageLoader = imageLoader,
            baseUrl = "http://frigate:5000",
            cameraDisplayName = cameraDisplayName,
            onBack = {},
        )
    }

    /** Returns the configured event payload, or a 404 / transport failure. */
    private class EventGetter(
        var body: String = clipEvent,
        var statusCode: Int = 200,
        var fail: Boolean = false,
    ) : HttpBytesGetter {
        override suspend fun getBytes(url: String, timeoutMs: Long): HttpBytesResult {
            if (fail) throw IOException("connection refused")
            return HttpBytesResult(statusCode, "application/json", url, body.toByteArray())
        }
    }

    /** A streaming getter whose body is exhausted immediately (EOF on first read). */
    private class EmptyStreamGetter : HttpStreamGetter {
        override suspend fun open(url: String, connectTimeoutMs: Long, headers: Map<String, String>): HttpStream =
            EmptyStream(200, "video/mp4", url)
    }

    /** A streaming getter whose body blocks on read until closed (stays Loading). */
    private class BlockingStreamGetter : HttpStreamGetter {
        override suspend fun open(url: String, connectTimeoutMs: Long, headers: Map<String, String>): HttpStream =
            BlockingStream(200, "video/mp4", url)
    }

    /** A streaming getter that answers an HTTP error on open. */
    private class ErrorStreamGetter(
        private val statusCode: Int,
    ) : HttpStreamGetter {
        override suspend fun open(url: String, connectTimeoutMs: Long, headers: Map<String, String>): HttpStream =
            EmptyStream(statusCode, "video/mp4", url)
    }

    private class EmptyStream(
        override val statusCode: Int,
        override val contentType: String?,
        override val finalUrl: String,
    ) : HttpStream {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun close() = Unit
    }

    private class BlockingStream(
        override val statusCode: Int,
        override val contentType: String?,
        override val finalUrl: String,
    ) : HttpStream {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (!closed.get()) Thread.sleep(10)
            return -1
        }

        override fun close() {
            closed.set(true)
        }
    }

    private companion object {
        const val T = 1787072293.5
        val clipEvent =
            """{"id":"evt-1","camera":"backyard","label":"person","start_time":$T,"end_time":${T + 10},"has_clip":true,"has_snapshot":true,"zones":["yard"]}"""
        val noClipEvent =
            """{"id":"evt-1","camera":"backyard","label":"person","start_time":$T,"end_time":${T + 10},"has_clip":false,"has_snapshot":true,"zones":["yard"]}"""
        val noClipInProgressEvent =
            """{"id":"evt-1","camera":"backyard","label":"person","start_time":$T,"end_time":null,"has_clip":false,"has_snapshot":true,"zones":["yard"]}"""
        val noClipNoSnapshotEvent =
            """{"id":"evt-1","camera":"backyard","label":"person","start_time":$T,"end_time":${T + 10},"has_clip":false,"has_snapshot":false,"zones":["yard"]}"""
    }
}
