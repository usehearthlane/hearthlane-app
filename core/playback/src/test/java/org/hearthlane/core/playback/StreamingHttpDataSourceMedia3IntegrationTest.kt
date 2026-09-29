package org.hearthlane.core.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.hearthlane.core.connectivity.HttpStream
import org.hearthlane.core.connectivity.HttpStreamGetter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Validates the Media3 pipeline construction:
 *
 * StreamingHttpDataSourceFactory → HlsMediaSource/ProgressiveMediaSource.Factory
 *
 * The factory must be accepted by Media3 and create the correct DataSource. The
 * Live wiring test proves the Live HLS path now drives the streaming getter
 * (open on the media playlist) instead of a full-buffer getter.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingHttpDataSourceMedia3IntegrationTest {

    private val clipUrl = "http://frigate:5000/api/events/evt-1/clip.mp4"

    @Test
    fun `ProgressiveMediaSource accepts the streaming factory and builds a source`() {
        val streamingFactory = StreamingHttpDataSourceFactory(RecordingGetter(), 2_000)

        val sourceFactory = ProgressiveMediaSource.Factory(streamingFactory)
        val source = sourceFactory.createMediaSource(MediaItem.fromUri(clipUrl))

        assertTrue(
            "the pipeline must produce a ProgressiveMediaSource",
            source is ProgressiveMediaSource,
        )
        assertEquals(
            "the source must carry the clip URI",
            clipUrl,
            source.getMediaItem().localConfiguration!!.uri.toString(),
        )
    }

    @Test
    fun `the factory creates a StreamingHttpDataSource`() {
        val streamingFactory = StreamingHttpDataSourceFactory(RecordingGetter(), 2_000)

        val dataSource = streamingFactory.createDataSource()

        assertTrue(dataSource is StreamingHttpDataSource)
    }

    @Test
    fun `LiveStreamPlayer drives the streaming getter for HLS`() {
        val getter = RecordingOpenGetter()
        val player = LiveStreamPlayer(RuntimeEnvironment.getApplication(), getter)

        player.play("http://frigate:5000/api/go2rtc/api/playlist.m3u8?id=x")
        // The HLS loader opens the media playlist through the streaming getter;
        // wait for that open to prove the wiring (no real video is decoded).
        val deadline = System.currentTimeMillis() + 5_000
        while (getter.openCount == 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(5)
        }
        player.release()

        assertTrue(
            "the Live HLS path must open through the streaming getter, not a full-buffer one",
            getter.openCount > 0,
        )
    }

    private class RecordingGetter : HttpStreamGetter {
        override suspend fun open(
            url: String,
            connectTimeoutMs: Long,
            headers: Map<String, String>,
        ): HttpStream =
            throw IOException("no network in this test; the pipeline is validated at construction time")
    }

    private class RecordingOpenGetter : HttpStreamGetter {
        var openCount = 0

        override suspend fun open(
            url: String,
            connectTimeoutMs: Long,
            headers: Map<String, String>,
        ): HttpStream {
            openCount++
            return FakeM3u8Stream()
        }
    }

    private class FakeM3u8Stream : HttpStream {
        private val closed = AtomicBoolean(false)
        private var offset = 0
        private val body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ENDLIST\n".toByteArray()

        override val statusCode: Int = 200
        override val contentType: String? = "application/vnd.apple.mpegurl"
        override val finalUrl: String = "http://frigate:5000/api/go2rtc/api/playlist.m3u8?id=x"

        override fun read(buffer: ByteArray, offsetIn: Int, length: Int): Int {
            if (closed.get()) return -1
            if (offset >= body.size) return -1
            val toRead = minOf(length, body.size - offset)
            body.copyInto(buffer, destinationOffset = offsetIn, startIndex = offset, endIndex = offset + toRead)
            offset += toRead
            return toRead
        }

        override fun close() {
            closed.set(true)
        }
    }
}