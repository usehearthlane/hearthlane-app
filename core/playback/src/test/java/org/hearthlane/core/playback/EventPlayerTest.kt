package org.hearthlane.core.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.hearthlane.core.connectivity.HttpStream
import org.hearthlane.core.connectivity.HttpStreamGetter
import org.junit.Assert.assertEquals
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
 * Robolectric tests for [EventPlayer] using a fake [HttpStreamGetter]. They
 * prove the pipeline wiring (play requests the clip URL through the streaming
 * getter), the error classification (404/500/transport), retry and lifecycle.
 * No real video is played; the getter returns a synthetic body that the player
 * fails to extract, which is enough to exercise the DataSource error paths.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventPlayerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private val clipUrl = "http://frigate:5000/api/events/evt-1/clip.mp4"

    @Test
    fun `play requests the clip URL through the streaming getter`() {
        val getter = FakeGetter()
        val player = EventPlayer(context, getter)

        player.play(clipUrl)
        waitUntil { getter.openedUrls.isNotEmpty() }

        assertEquals(clipUrl, getter.openedUrls.single())
        player.release()
    }

    @Test
    fun `HTTP 404 surfaces an error carrying the status`() {
        val getter = FakeGetter(statusCode = 404)
        val player = EventPlayer(context, getter)

        player.play(clipUrl)
        val error = waitForError(player)

        assertEquals(404, error.statusCode)
        player.release()
    }

    @Test
    fun `HTTP 500 surfaces an error carrying the status`() {
        val getter = FakeGetter(statusCode = 500)
        val player = EventPlayer(context, getter)

        player.play(clipUrl)
        val error = waitForError(player)

        assertEquals(500, error.statusCode)
        player.release()
    }

    @Test
    fun `a transport failure surfaces an error without a status`() {
        val getter = FakeGetter(throwOnOpen = IOException("connection refused"))
        val player = EventPlayer(context, getter)

        player.play(clipUrl)
        val error = waitForError(player)

        assertNull("a connection failure must not carry an HTTP status", error.statusCode)
        player.release()
    }

    @Test
    fun `play after an error re-requests the clip`() {
        val getter = FakeGetter(statusCode = 404)
        val player = EventPlayer(context, getter)

        player.play(clipUrl)
        waitForError(player)
        val opensAfterFirstError = getter.openedUrls.size

        player.play(clipUrl)
        waitUntil { getter.openedUrls.size > opensAfterFirstError }

        assertTrue(
            "a retry must re-request the clip through the getter",
            getter.openedUrls.size > opensAfterFirstError,
        )
        player.release()
    }

    @Test
    fun `stop releases the media source without throwing`() {
        val getter = FakeGetter()
        val player = EventPlayer(context, getter)

        player.play(clipUrl)
        waitUntil { getter.openedUrls.isNotEmpty() }
        player.stop()
        player.release()
    }

    @Test
    fun `release does not throw`() {
        val getter = FakeGetter(statusCode = 404)
        val player = EventPlayer(context, getter)

        player.play(clipUrl)
        player.release()
    }

    @Test
    fun `play again reuses the same player instance`() {
        val getter = FakeGetter()
        val player = EventPlayer(context, getter)
        val instance = player.player

        player.play(clipUrl)
        player.play(clipUrl)

        assertTrue("one EventPlayer must own exactly one ExoPlayer", player.player === instance)
        player.release()
    }

    @Test
    fun `state mapping covers ended and preserves a fatal error`() {
        assertEquals(PlaybackStatus.Ended, playbackStatusForState(Player.STATE_ENDED, PlaybackStatus.Playing))
        assertEquals(PlaybackStatus.Loading, playbackStatusForState(Player.STATE_BUFFERING, PlaybackStatus.Idle))
        assertEquals(PlaybackStatus.Playing, playbackStatusForState(Player.STATE_READY, PlaybackStatus.Loading))
        assertEquals(PlaybackStatus.Idle, playbackStatusForState(Player.STATE_IDLE, PlaybackStatus.Playing))

        val fatal = PlaybackStatus.Error("boom", 500)
        assertEquals(
            "a transient IDLE after a fatal error must keep the error visible",
            fatal,
            playbackStatusForState(Player.STATE_IDLE, fatal),
        )
    }

    private fun waitForError(player: EventPlayer, timeoutMs: Long = 20_000): PlaybackStatus.Error {
        waitUntil(timeoutMs) { player.state.value is PlaybackStatus.Error }
        return player.state.value as PlaybackStatus.Error
    }

    private fun waitUntil(timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Advance the main looper's shadow clock so the player's delayed
            // retry/backoff tasks and error delivery run; the loader itself runs
            // on a real background thread between iterations.
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            if (predicate()) return
            Thread.sleep(5)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private class FakeGetter(
        var statusCode: Int = 200,
        var throwOnOpen: Exception? = null,
    ) : HttpStreamGetter {
        val openedUrls = mutableListOf<String>()

        override suspend fun open(url: String, connectTimeoutMs: Long, headers: Map<String, String>): HttpStream {
            openedUrls.add(url)
            throwOnOpen?.let { throw it }
            return FakeStream(statusCode, "video/mp4", url)
        }
    }

    private class FakeStream(
        override val statusCode: Int,
        override val contentType: String?,
        override val finalUrl: String,
    ) : HttpStream {
        private val closedFlag = AtomicBoolean(false)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closedFlag.get()) return -1
            return -1 // empty body: EOF immediately
        }

        override fun close() {
            closedFlag.set(true)
        }
    }
}