package org.hearthlane.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.hearthlane.controller.RecentEventsController
import org.hearthlane.core.connectivity.HttpBytesGetter
import org.hearthlane.core.connectivity.HttpBytesResult
import org.hearthlane.core.frigate.FrigateConnection
import org.hearthlane.core.frigate.FrigateEventApi
import org.hearthlane.core.frigate.LiveQualityMode
import org.hearthlane.core.frigate.TransportKind
import org.hearthlane.core.connectivity.TsnetGateway
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
import org.robolectric.annotation.Config

/**
 * Robolectric/Compose tests for the camera screen ([LiveScreen]): the live
 * player is rendered together with the camera's recent-events list directly
 * below it (no intermediate list screen), and no playback/history action exists
 * in the toolbar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val imageLoader = fakeSnapshotImageLoader(context)
    private val factory = CameraThumbnailModelFactory(
        connection = MutableStateFlow(FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1")),
        gateway = FakeTsnetGateway(),
    )

    private val streamsJson = """{"backyard":{},"hall":{}}"""
    private val masterPlaylist =
        """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        http://fake.local/variant.m3u8
        """.trimIndent()

    @Test
    fun `camera screen renders the live player and the recent events list`() = runTest {
        val gateway = RoutingTsnetGateway()
        val events = createEventsController(eventsGetter())

        composeTestRule.setContent { screen(gateway, events) }
        composeTestRule.waitForIdle()
        events.loadInitial()
        advanceUntilIdle()
        composeTestRule.waitForIdle()

        // The live player was started for the selected camera.
        assertTrue(
            "the live player must target the selected camera stream",
            gateway.requestedUrls.any { it.contains("stream.m3u8") && it.contains("src=backyard") },
        )
        // The recent-events list is rendered on the camera screen.
        composeTestRule.onNodeWithText("Recent events").assertIsDisplayed()
        composeTestRule.onNodeWithText("Person").assertIsDisplayed()
    }

    @Test
    fun `tapping an event on the camera screen invokes onEventSelected`() = runTest {
        var selected: String? = null
        val events = createEventsController(eventsGetter())

        composeTestRule.setContent {
            screen(RoutingTsnetGateway(), events, onEventSelected = { selected = it })
        }
        composeTestRule.waitForIdle()
        events.loadInitial()
        advanceUntilIdle()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Person").performClick()

        assertTrue("the tapped event must report its id", selected == "a")
    }

    @Test
    fun `the toolbar has no playback or history action`() = runTest {
        val events = createEventsController(eventsGetter())

        composeTestRule.setContent { screen(RoutingTsnetGateway(), events) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Play").assertDoesNotExist()
        composeTestRule.onNodeWithText("Play again").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Recent events").assertDoesNotExist()
    }

    @Composable
    private fun screen(
        gateway: RoutingTsnetGateway,
        events: RecentEventsController,
        onEventSelected: (String) -> Unit = {},
    ) {
        LiveScreen(
            cameraId = "backyard",
            displayName = "Backyard",
            baseUrl = "http://frigate:5000",
            gateway = gateway,
            transport = TransportKind.TAILSCALE,
            connectAttempt = 1,
            networkTick = 0,
            qualityMode = LiveQualityMode.AUTO,
            eventsController = events,
            thumbnailFactory = factory,
            snapshotImageLoader = imageLoader,
            onBack = {},
            onEventSelected = onEventSelected,
        )
    }

    private fun TestScope.createEventsController(getter: HttpBytesGetter): RecentEventsController =
        RecentEventsController(
            api = FrigateEventApi(getter),
            cameraId = "backyard",
            baseUrl = { "http://frigate:5000" },
            limit = 2,
            scope = this,
        )

    private fun eventsGetter(): HttpBytesGetter =
        object : HttpBytesGetter {
            override suspend fun getBytes(url: String, timeoutMs: Long): HttpBytesResult =
                HttpBytesResult(
                    200,
                    "application/json",
                    url,
                    """[{"id":"a","camera":"backyard","label":"person","start_time":1787072293.5,"end_time":1787072303.5,"has_clip":true,"has_snapshot":true,"zones":[]}]""".toByteArray(),
                )
        }

    private inner class RoutingTsnetGateway : TsnetGateway {
        val requestedUrls = mutableListOf<String>()

        override suspend fun ensureRunning() = Unit
        override suspend fun stopIfRunning() = Unit
        override suspend fun reset() = Unit

        override suspend fun httpGet(url: String, timeoutMs: Long): String {
            requestedUrls.add(url)
            return if (url.contains("/api/go2rtc/streams")) streamsJson else masterPlaylist
        }

        override suspend fun httpGetBytes(url: String, timeoutMs: Long): HttpBytesResult {
            requestedUrls.add(url)
            val body = when {
                url.contains("/api/go2rtc/streams") -> streamsJson.toByteArray()
                else -> masterPlaylist.toByteArray()
            }
            return HttpBytesResult(200, "application/json", url, body)
        }
    }
}