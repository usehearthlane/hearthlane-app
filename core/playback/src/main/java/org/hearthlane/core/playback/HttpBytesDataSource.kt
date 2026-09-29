package org.hearthlane.core.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.hearthlane.core.connectivity.HttpBytesGetter
import org.hearthlane.core.connectivity.HttpBytesResult
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * IOException carrying the HTTP status of a non-2xx response. The playback
 * layer keeps it in the cause chain so the UI can classify failures: a 5xx or
 * 404 is a server/product state that retrying will not fix, while every other
 * error is transient and auto-recoverable.
 */
class HttpStatusIOException(
    val statusCode: Int,
    message: String,
) : IOException(message)

/**
 * [DataSource] for Media3/ExoPlayer that performs every HTTP GET through an
 * injected [HttpBytesGetter].
 *
 * The getter is the ONLY network path this source can use. For the Tailscale
 * path the getter is the tsnet getter, so no media request can escape to the
 * Android network. Redirects are followed by the getter and [getUri] returns
 * the final URL so relative HLS segment references resolve correctly.
 *
 * Each `open` buffers the whole response in memory, which is deliberately
 * acceptable for the Phase 4 spike: every HLS request is a bounded response
 * (one playlist or one media segment). It would not suit progressive or
 * long-lived streams, and that is documented as a POC limitation.
 */
@UnstableApi
class HttpBytesDataSource(
    private val getter: HttpBytesGetter,
    private val timeoutMs: Long,
    private val onBytes: (Long) -> Unit = {},
    private val diagLabel: String = "LIVE",
) : DataSource {

    private var result: HttpBytesResult? = null
    private var uri: Uri? = null
    private var readOffset = 0L
    private var bytesRead = 0L
    private var totalBytes = 0L
    private var closed = true

    override fun addTransferListener(transferListener: TransferListener) {
        // No-op: bandwidth estimation and cache hooks are out of POC scope.
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val fetched = try {
            runBlocking { getter.getBytes(url, timeoutMs) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Connection-level failure (timeout, DNS, refused) surfaced by the
            // getter or the bridge. The full URL is intentionally omitted from
            // release logs because HLS session URLs are transient credentials
            // to the go2rtc stream. Surface the failure as an IOException so
            // ExoPlayer's transient retry policy applies instead of a fatal
            // error.
            Log.e(TAG, "HLS GET failed (network/bridge): ${e.message ?: e.javaClass.simpleName}")
            throw IOException(
                "HttpBytesDataSource: GET failed: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        if (fetched.statusCode !in 200..299) {
            // go2rtc serves init/segments with 404 while its session buffer is
            // empty (camera cold start); ExoPlayer treats a 4xx thrown through
            // HttpDataSource as non-retriable, so surface it as a plain
            // IOException to keep ExoPlayer's own retry policy (transient).
            // The URL is omitted from the exception message to avoid leaking
            // the HLS session id into logs or crash reports.
            val message = "HttpBytesDataSource: GET failed -> HTTP ${fetched.statusCode}"
            Log.e(TAG, message)
            throw HttpStatusIOException(fetched.statusCode, message)
        }
        validatePayload(url, fetched)
        onBytes(fetched.body.size.toLong())
        result = fetched
        uri = Uri.parse(fetched.finalUrl)
        val available = fetched.body.size.toLong() - dataSpec.position
        totalBytes = if (dataSpec.length >= 0) {
            minOf(available, dataSpec.length)
        } else {
            available
        }
        readOffset = dataSpec.position
        bytesRead = 0L
        closed = false
        Log.d(
            TAG,
            "HLS GET succeeded -> HTTP ${fetched.statusCode}, ${fetched.body.size} bytes, " +
                "${fetched.contentType ?: "no content type"}",
        )
        return maxOf(0L, totalBytes)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) return C.RESULT_END_OF_INPUT
        if (bytesRead >= totalBytes) {
            return C.RESULT_END_OF_INPUT
        }
        val body = result?.body ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), totalBytes - bytesRead).toInt()
        val from = readOffset.toInt()
        if (from < 0 || from + toRead > body.size) {
            throw IOException("HttpBytesDataSource read past buffer end")
        }
        body.copyInto(buffer, destinationOffset = offset, startIndex = from, endIndex = from + toRead)
        readOffset += toRead
        bytesRead += toRead
        return toRead
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        result?.contentType?.let { mapOf("Content-Type" to listOf(it)) } ?: emptyMap()

    override fun close() {
        result = null
        uri = null
        bytesRead = 0L
        totalBytes = 0L
        closed = true
    }

    companion object {
        private const val TAG = "Hearthlane"

        /**
         * Rejects 2xx responses whose payload does not match what the URL
         * requests. go2rtc answers the HLS master request with HTTP 200 and an
         * empty body when it cannot attach a consumer; a strict parser would
         * surface that as an opaque PARSING_MANIFEST_MALFORMED error. Failing
         * the load as an [IOException] instead lets ExoPlayer retry transient
         * failures and keeps the real response visible in the playback error.
         *
         * Kept static (no [android.net.Uri] dependency) so JVM unit tests can
         * exercise it directly.
         */
        fun validatePayload(url: String, fetched: HttpBytesResult) {
            val bodyPreview = fetched.body.toString(Charsets.UTF_8).take(200)
            if (url.contains("m3u8")) {
                if (!bodyPreview.startsWith("#EXTM3U")) {
                    val message =
                        "HLS manifest is not an m3u8 playlist (HTTP ${fetched.statusCode}, " +
                            "type=${fetched.contentType})"
                    Log.e(TAG, message)
                    throw IOException(message)
                }
            } else if (fetched.body.isEmpty() &&
                (url.contains("init.mp4") ||
                    url.contains("segment.m4s") ||
                    url.contains("segment.ts"))
            ) {
                // go2rtc serves init/segments with 404 while its session buffer
                // is empty, never with a 2xx empty body; an empty 2xx here
                // means a proxy or a misrouted request swallowed the payload.
                val message =
                    "HLS media payload is empty (HTTP ${fetched.statusCode}, " +
                        "type=${fetched.contentType})"
                Log.e(TAG, message)
                throw IOException(message)
            }
        }
    }
}
