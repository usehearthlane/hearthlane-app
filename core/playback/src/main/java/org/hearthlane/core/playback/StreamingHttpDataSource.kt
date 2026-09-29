package org.hearthlane.core.playback

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.hearthlane.core.connectivity.HttpStream
import org.hearthlane.core.connectivity.HttpStreamGetter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [DataSource] for Media3/ExoPlayer that serves a resource progressively
 * through an injected [HttpStreamGetter], without ever materializing the whole
 * response in memory.
 *
 * This is the streaming sibling of [HttpBytesDataSource]: it is meant for
 * resources that stay open and are consumed incrementally (a Frigate event
 * `clip.mp4`, or HLS playlists/init/segments), where buffering the full body is
 * impossible. Like its sibling, the getter is the ONLY network path the source
 * can use, so a Tailscale request can never escape to the Android network.
 *
 * The Frigate endpoint ignores `Range` and answers a 200 with the whole body,
 * so [DataSpec.position] is honored by reading and discarding bytes
 * progressively from the start, and a missing `Content-Length` is the norm
 * (the chunked body is read until EOF).
 *
 * [TransferListener]s (for example a [androidx.media3.datasource.DefaultBandwidthMeter])
 * are forwarded the standard transfer events, so Media3 bandwidth estimation
 * works even though the network path is custom.
 */
@UnstableApi
class StreamingHttpDataSource(
    private val getter: HttpStreamGetter,
    private val connectTimeoutMs: Long,
    private val onBytes: (Long) -> Unit = {},
    private val diagLabel: String = "EVENT",
) : DataSource {

    private var stream: HttpStream? = null
    private var uri: Uri? = null
    private var bytesRead = 0L
    private var totalBytes: Long = C.LENGTH_UNSET.toLong()
    private var closed = true
    private var diagId = -1L
    private var diagResource = "OTHER"
    private var lastReadStartMs = 0L
    private var lastStallLoggedAtMs = Long.MIN_VALUE
    private var activeDataSpec: DataSpec? = null
    private var transferStarted = false
    private var playlistCapture: ByteArrayOutputStream? = null

    private val transferListeners = CopyOnWriteArrayList<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        if (!transferListeners.contains(transferListener)) {
            transferListeners.add(transferListener)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        diagId = VideoDiag.nextId()
        diagResource = classifyResource(dataSpec.uri.toString())
        lastReadStartMs = 0L
        playlistCapture = if (diagResource == "MEDIA_PLAYLIST") ByteArrayOutputStream() else null
        activeDataSpec = dataSpec
        transferStarted = false
        val opened = try {
            runBlocking {
                getter.open(dataSpec.uri.toString(), connectTimeoutMs, dataSpec.httpRequestHeaders)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException(
                "StreamingHttpDataSource: GET failed: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        if (opened.statusCode !in 200..299) {
            // Release the underlying stream (Go handle / connection) so an HTTP
            // error never leaks it; Media3 closes the DataSource too, but this
            // guarantees the server side is released even if that does not run.
            opened.close()
            VideoDiag.dataHttpError(
                diagLabel,
                diagId,
                diagResource,
                opened.statusCode,
            )
            // Throw the Media3-native HTTP exception (not a custom IOException):
            // HlsSampleStreamWrapper has a dedicated 404/410 fast path for
            // HttpDataSource.InvalidResponseCodeException (sliding-window retry),
            // and getFallbackSelectionFor classifies 403/404/410/416/500/503 for
            // track fallback. A custom exception silently bypasses that policy.
            val headers = opened.contentType?.let { mapOf("Content-Type" to listOf(it)) } ?: emptyMap()
            throw HttpDataSource.InvalidResponseCodeException(
                opened.statusCode,
                "StreamingHttpDataSource: GET failed -> HTTP ${opened.statusCode}",
                null,
                headers,
                dataSpec,
                ByteArray(0),
            )
        }
        stream = opened
        uri = Uri.parse(opened.finalUrl)
        // The server usually ignores Range and returns the whole body as a 200,
        // so a non-zero position is reached by consuming (and discarding) bytes
        // progressively. When Range WAS honored (206), the body already starts
        // at the requested position and nothing is skipped.
        if (dataSpec.position > 0 && opened.statusCode != 206) {
            skipToPosition(opened, dataSpec.position)
        }
        bytesRead = 0L
        val announcedLength = opened.contentLength
        totalBytes = when {
            announcedLength != null && announcedLength >= 0 -> {
                // For a 206 the body is the requested range and Content-Length
                // is already its length; for a 200 full body with a skipped
                // position the remaining bytes are Content-Length minus the
                // skipped prefix.
                if (opened.statusCode == 206) announcedLength
                else (announcedLength - dataSpec.position).coerceAtLeast(0L)
            }
            dataSpec.length >= 0 -> dataSpec.length
            else -> C.LENGTH_UNSET.toLong()
        }
        closed = false
        // Balance the transfer events: onTransferStart only after the request
        // succeeded, onTransferEnd exactly once on EOF/close. Never emit an end
        // without a start (a bandwidth meter counts streams and would go
        // negative on an unmatched end).
        transferListeners.forEach { it.onTransferStart(this, dataSpec, true) }
        transferStarted = true
        return totalBytes
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) return C.RESULT_END_OF_INPUT
        if (totalBytes != C.LENGTH_UNSET.toLong() && bytesRead >= totalBytes) {
            logEofOnce()
            return C.RESULT_END_OF_INPUT
        }
        val opened = stream ?: return C.RESULT_END_OF_INPUT
        val toRead = if (totalBytes != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), totalBytes - bytesRead).toInt()
        } else {
            length
        }
        if (toRead <= 0) return C.RESULT_END_OF_INPUT
        val readStart = SystemClock.elapsedRealtime()
        if (lastReadStartMs > 0) {
            val gap = readStart - lastReadStartMs
            if (gap >= READ_STALL_THRESHOLD_MS && readStart - lastStallLoggedAtMs >= STALL_LOG_COOLDOWN_MS) {
                lastStallLoggedAtMs = readStart
                VideoDiag.dataReadStall(
                    diagLabel,
                    diagId,
                    diagResource,
                    gap,
                    bytesRead,
                    bytesRead,
                )
            }
        }
        val n = opened.read(buffer, offset, toRead)
        if (n < 0) {
            logEofOnce()
            return C.RESULT_END_OF_INPUT
        }
        lastReadStartMs = readStart
        bytesRead += n
        onBytes(n.toLong())
        playlistCapture?.let { capture ->
            if (capture.size() < PLAYLIST_CAPTURE_LIMIT) {
                val toAppend = minOf(n, PLAYLIST_CAPTURE_LIMIT - capture.size())
                capture.write(buffer, offset, toAppend)
            }
        }
        val spec = activeDataSpec
        if (spec != null) {
            transferListeners.forEach { it.onBytesTransferred(this, spec, true, n) }
        }
        return n
    }

    private fun logEofOnce() {
        if (diagId >= 0) {
            if (diagResource == "MEDIA_PLAYLIST") {
                logLivePlaylistWindow()
            }
            endTransfer()
            diagId = -1
        }
    }

    /** Parses the captured media playlist (sanitized: numeric tags only) and logs
     *  the live window the server offers, throttled so the sub-second polling
     *  does not spam. Never logs the URL, query or session id. */
    private fun logLivePlaylistWindow() {
        val now = SystemClock.elapsedRealtime()
        // Long.MIN_VALUE sentinel: `now - MIN_VALUE` overflows to a large
        // negative, so the first call must be guarded explicitly or it would be
        // throttled forever and never log.
        if (lastWindowLogAtMs != Long.MIN_VALUE && now - lastWindowLogAtMs < WINDOW_LOG_COOLDOWN_MS) return
        lastWindowLogAtMs = now
        val capture = playlistCapture ?: return
        val summary = parseLivePlaylistSummary(String(capture.toByteArray(), Charsets.UTF_8))
        VideoDiag.player(
            "LIVE",
            "live-config-window targetDurationMs=${summary.targetDurationMs ?: "UNSET"} " +
                "partTargetDurationMs=${summary.partTargetDurationMs ?: "UNSET"} " +
                "serverHoldBackMs=${summary.serverHoldBackMs ?: "UNSET"} " +
                "partHoldBackMs=${summary.partHoldBackMs ?: "UNSET"} " +
                "canBlockReload=${summary.canBlockReload} " +
                "segmentCount=${summary.segmentCount} partCount=${summary.partCount} " +
                "windowDurationMs=${summary.windowDurationMs ?: "UNSET"}",
        )
    }

    private fun endTransfer() {
        if (transferStarted) {
            transferStarted = false
            activeDataSpec?.let { spec ->
                transferListeners.forEach { it.onTransferEnd(this, spec, true) }
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        stream?.contentType?.let { mapOf("Content-Type" to listOf(it)) } ?: emptyMap()

    override fun close() {
        if (diagId >= 0) {
            endTransfer()
            diagId = -1
        }
        stream?.close()
        stream = null
        uri = null
        activeDataSpec = null
        bytesRead = 0L
        totalBytes = C.LENGTH_UNSET.toLong()
        closed = true
    }

    /** Consumes and discards [position] bytes so reads start exactly there. */
    private fun skipToPosition(opened: HttpStream, position: Long) {
        var remaining = position
        val buffer = ByteArray(SKIP_BUFFER_SIZE)
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val n = opened.read(buffer, 0, toRead)
            if (n < 0) return // the content ended before the requested position
            remaining -= n
        }
    }

    private companion object {
        const val SKIP_BUFFER_SIZE = 8192

        /** A read-to-read delivery gap at/above this is a candidate transport stall. */
        const val READ_STALL_THRESHOLD_MS = 500L

        /** Bounds stall log spam to at most one per interval per stream. */
        const val STALL_LOG_COOLDOWN_MS = 2_000L

        /** Bounds the captured media-playlist body (playlists are a few KB). */
        const val PLAYLIST_CAPTURE_LIMIT = 32 * 1024

        /** Bounds live-config-window logs (playlist polls every ~0.5-1 s). */
        const val WINDOW_LOG_COOLDOWN_MS = 30_000L

        @Volatile var lastWindowLogAtMs = Long.MIN_VALUE
    }
}

/**
 * Classifies an HLS request for diagnostics WITHOUT logging the URL, query
 * string or session id: only the resource kind is surfaced (MASTER /
 * MEDIA_PLAYLIST / INIT_SEGMENT / MEDIA_SEGMENT / OTHER), derived from the
 * path. Kept pure so the classification is unit-testable.
 */
internal fun classifyResource(url: String): String {
    val path = url.substringBefore('?')
    return when {
        path.contains("stream.m3u8") -> "MASTER"
        path.contains("playlist.m3u8") -> "MEDIA_PLAYLIST"
        path.contains("init.mp4") -> "INIT_SEGMENT"
        path.contains("segment.m4s") || path.contains("segment.ts") -> "MEDIA_SEGMENT"
        else -> "OTHER"
    }
}

/**
 * Sanitized diagnostic summary of a media playlist: numeric/boolean tags only,
 * never the URL, query string or session id. Lets us see how much real window
 * the go2rtc server offers (target/part target, HOLD-BACK, PART-HOLD-BACK,
 * segment/part counts, summed window duration) before deciding how far behind
 * the live edge we can safely play. Kept pure so it is unit-testable.
 */
internal data class LivePlaylistSummary(
    val targetDurationMs: Long? = null,
    val partTargetDurationMs: Long? = null,
    val serverHoldBackMs: Long? = null,
    val partHoldBackMs: Long? = null,
    val canBlockReload: Boolean? = null,
    val segmentCount: Int = 0,
    val partCount: Int = 0,
    val windowDurationMs: Long? = null,
)

internal fun parseLivePlaylistSummary(body: String): LivePlaylistSummary {
    var targetDurationMs: Long? = null
    var partTargetDurationMs: Long? = null
    var serverHoldBackMs: Long? = null
    var partHoldBackMs: Long? = null
    var canBlockReload: Boolean? = null
    var segmentCount = 0
    var partCount = 0
    var windowUs = 0L
    var hasWindow = false
    body.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("#EXT-X-TARGETDURATION:") ->
                line.removePrefix("#EXT-X-TARGETDURATION:").trim().toLongOrNull()
                    ?.let { targetDurationMs = it * 1000 }
            line.startsWith("#EXT-X-PART-INF:") ->
                partTargetDurationMs = extractSecondsParam(line.removePrefix("#EXT-X-PART-INF:").trim(), "PART-TARGET")
            line.startsWith("#EXT-X-SERVER-CONTROL:") -> {
                val params = line.removePrefix("#EXT-X-SERVER-CONTROL:").trim()
                canBlockReload = extractBoolParam(params, "CAN-BLOCK-RELOAD")
                serverHoldBackMs = extractSecondsParam(params, "HOLD-BACK")
                partHoldBackMs = extractSecondsParam(params, "PART-HOLD-BACK")
            }
            line.startsWith("#EXTINF:") -> {
                segmentCount++
                line.removePrefix("#EXTINF:").trim().substringBefore(',').toDoubleOrNull()?.let {
                    windowUs += (it * 1_000_000).toLong()
                    hasWindow = true
                }
            }
            line.startsWith("#EXT-X-PART:") -> {
                partCount++
                extractSecondsParam(line.removePrefix("#EXT-X-PART:").trim(), "DURATION")?.let {
                    windowUs += it * 1000
                    hasWindow = true
                }
            }
        }
    }
    return LivePlaylistSummary(
        targetDurationMs = targetDurationMs,
        partTargetDurationMs = partTargetDurationMs,
        serverHoldBackMs = serverHoldBackMs,
        partHoldBackMs = partHoldBackMs,
        canBlockReload = canBlockReload,
        segmentCount = segmentCount,
        partCount = partCount,
        windowDurationMs = if (hasWindow) windowUs / 1000 else null,
    )
}

private fun extractSecondsParam(params: String, key: String): Long? =
    Regex("""(?:^|,)\s*$key=([0-9.]+)""").find(params)
        ?.groupValues?.get(1)
        ?.toDoubleOrNull()
        ?.let { (it * 1000).toLong() }

private fun extractBoolParam(params: String, key: String): Boolean? =
    Regex("""(?:^|,)\s*$key=(YES|NO)""").find(params)
        ?.groupValues?.get(1)
        ?.let { it == "YES" }