package org.hearthlane.tailscale

import android.os.SystemClock
import android.util.Log
import org.hearthlane.core.connectivity.ConnectivityState
import org.hearthlane.core.connectivity.ConnectivityStatus
import org.hearthlane.core.connectivity.HttpBytesResult
import org.hearthlane.core.connectivity.HttpStream
import org.hearthlane.tsembed.Tsembed
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal Kotlin bridge over the gomobile-generated [Tsembed] binding.
 *
 * This is the only place in the app that knows about the generated
 * `org.hearthlane.tsembed` classes. Callers get a dependency-free
 * [ConnectivityStatus] instead of Go JSON strings.
 */
object TailscaleBridge {

    init {
        // Debug builds enable the Go DNS debug logs (cache hit/miss) so tailnet
        // DNS behavior is confirmable in logcat during device validation.
        Tsembed.setDebugDNS(BuildConfig.DEBUG)
    }

    /**
     * Process-global node ownership. The counter transition and the physical
     * start/stop are one atomic critical section (see [TsnetOwnership]), so a
     * first-start can never cross a last-stop: there is no window where a
     * consumer holds a lease while the node is stopped, or where start/stop
     * run out of order. This also serializes the native Start/Stop calls that
     * were the SIGABRT source in "Reconfigure remote access -> Test
     * connection".
     */
    private val ownership = TsnetOwnership(
        start = { hostname, authKey, stateDir ->
            Log.i(TAG, "[TsnetLifecycle] start begin")
            runCatching { Tsembed.start(hostname, authKey, stateDir) }
                .onFailure { Log.w(TAG, "[TsnetLifecycle] start failed synchronously: ${it.message}") }
            Log.i(TAG, "[TsnetLifecycle] start requested (async)")
        },
        stop = {
            Log.i(TAG, "[TsnetLifecycle] stop begin")
            runCatching { Tsembed.stop() }
                .onFailure { Log.w(TAG, "[TsnetLifecycle] stop failed; node may already be down", it) }
            Log.i(TAG, "[TsnetLifecycle] stop complete")
        },
    )

    /**
     * Process-global interactive-enrollment ownership. It keeps the exact
     * physical node that produced a pending login URL alive while the user
     * authorizes, independently of the consumer that discovered NeedsLogin.
     * See [TsnetEnrollmentSession] for the full model.
     */
    internal val enrollmentSession = TsnetEnrollmentSession(
        retainOwnership = { ownership.retain() },
        releaseOwnership = { ownership.release() },
        readStatus = { status() },
        monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    )

    /**
     * Registers one consumer use; starts the node when this is the first
     * consumer. Returns true when the caller is the starter.
     */
    fun acquire(hostname: String, authKey: String, stateDir: String): Boolean =
        ownership.acquire(hostname, authKey, stateDir)

    /**
     * Releases one consumer use; stops the node when this is the last
     * consumer. Returns true when the caller triggered the stop.
     */
    fun release(): Boolean = ownership.release()

    /**
     * Drops every consumer claim and forces the node down (admin reset). The
     * enrollment session is discarded WITHOUT releasing: the reset zeroes every
     * claim itself, so a release here would double-stop the node.
     */
    fun resetOwnership() {
        enrollmentSession.discard()
        ownership.reset()
    }

    /**
     * Performs an HTTP GET exclusively over the embedded tailnet (the Go side
     * dials with tsnet's own transport). Blocks the calling thread and must
     * not be invoked on the main thread.
     *
     * @throws Exception when the node is not running or the request fails.
     */
    fun httpGet(url: String, timeoutMs: Long): String = Tsembed.httpGet(url, timeoutMs)

    /**
     * Performs an HTTP GET exclusively over the embedded tailnet and returns
     * the full response (status, content type, final URL and body). Blocks the
     * calling thread and must not be invoked on the main thread. Non-2xx
     * statuses are returned, not thrown.
     *
     * @throws Exception when the node is not running or the request cannot
     *   reach the server.
     */
    fun httpGetBytes(url: String, timeoutMs: Long): HttpBytesResult {
        val result = Tsembed.httpGetBytes(url, timeoutMs)
        return toBytesResult(
            statusCode = result.statusCode.toInt(),
            contentType = result.contentType,
            finalUrl = result.finalURL,
            body = result.body,
        )
    }

    /**
     * Performs an HTTP request (any method) exclusively over the embedded
     * tailnet, attaching [headers] as a JSON object on the Go side. Blocks the
     * calling thread and must not be invoked on the main thread. Non-2xx
     * statuses are returned, not thrown.
     *
     * [body] and [contentType] are used for WRITE requests; readers pass an
     * empty body and contentType and bake what they need into [headers].
     *
     * @throws Exception when the node is not running or the request cannot
     *   reach the server.
     */
    fun httpRequest(
        method: String,
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): HttpBytesResult {
        val headersJson = if (headers.isEmpty()) {
            ""
        } else {
            JSONObject(headers).toString()
        }
        val result = Tsembed.httpRequest(method, url, contentType, headersJson, body, timeoutMs)
        return toBytesResult(
            statusCode = result.statusCode.toInt(),
            contentType = result.contentType,
            finalUrl = result.finalURL,
            body = result.body,
        )
    }

    /**
     * Opens an HTTP GET exclusively over the embedded tailnet as a progressive
     * [HttpStream]. Blocks the calling thread and must not be invoked on the
     * main thread. The body is read incrementally through [HttpStream.read],
     * which pulls bounded chunks from the Go side; it is never buffered whole.
     * [headers] are propagated to the Go request (for example a Range header
     * requested by Media3).
     */
    fun httpOpenStream(url: String, headers: Map<String, String>, connectTimeoutMs: Long): HttpStream {
        val headersJson = if (headers.isEmpty()) "" else JSONObject(headers).toString()
        val info = Tsembed.openHttpStream(url, headersJson, connectTimeoutMs)
        val contentLength = info.contentLength.takeIf { it >= 0 }
        return TsnetHttpStream(
            id = info.id,
            statusCode = info.statusCode.toInt(),
            contentType = info.contentType,
            finalUrl = info.finalURL,
            contentLength = contentLength,
        )
    }

    /**
     * Reports the current embedded node state. Cheap to poll for a UI loop.
     * Runs a blocking Go call and should not be invoked on the main thread.
     */
    fun status(): ConnectivityStatus {
        val json = JSONObject(Tsembed.status())
        return ConnectivityStatus(
            state = parseState(json.optString("state")),
            authUrl = json.optString("authUrl").takeIf { it.isNotBlank() },
            error = json.optString("error").takeIf { it.isNotBlank() },
        )
    }

    private fun parseState(raw: String): ConnectivityState = when (raw) {
        "Disconnected" -> ConnectivityState.DISCONNECTED
        "Authenticating" -> ConnectivityState.AUTHENTICATING
        "Connecting" -> ConnectivityState.CONNECTING
        "Connected" -> ConnectivityState.CONNECTED
        "Failed" -> ConnectivityState.FAILED
        "Stopped" -> ConnectivityState.STOPPED
        else -> ConnectivityState.DISCONNECTED
    }

    /** Tag for operational lifecycle logs (serialized start/stop and state-dir
     *  handling, which are documented invariants of the validated lifecycle). */
    private const val TAG = "Hearthlane"
}

/**
 * [HttpStream] backed by a stateful Go stream handle. Each [read] pulls one
 * bounded chunk from the Go side through an explicit [ReadResult] (data + EOF
 * flag): EOF is reported by the flag, never by an ambiguous null/empty byte
 * array — gomobile maps an empty Go slice to a Java null, so the explicit flag
 * is what distinguishes "no data yet" from "stream ended". [close] is
 * idempotent and closes the Go stream, which also unblocks a pending read.
 */
private class TsnetHttpStream(
    private val id: Long,
    override val statusCode: Int,
    override val contentType: String?,
    override val finalUrl: String,
    override val contentLength: Long?,
) : HttpStream {

    private val closed = AtomicBoolean(false)
    private var readCount = 0L
    private var maxReadDurationMs = 0L
    private var totalBytes = 0L

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (closed.get()) return -1
        val start = SystemClock.elapsedRealtime()
        val result = Tsembed.readChunk(id, length.toLong())
        val durationMs = SystemClock.elapsedRealtime() - start
        if (result.eof) return -1
        val chunk = result.data ?: return 0
        if (chunk.isEmpty()) return 0
        readCount++
        maxReadDurationMs = maxOf(maxReadDurationMs, durationMs)
        totalBytes += chunk.size
        chunk.copyInto(buffer, destinationOffset = offset, startIndex = 0, endIndex = chunk.size)
        return chunk.size
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Only slow streams are surfaced: a stream whose readChunk ever took
            // >= 500 ms is exactly the delivery problem worth diagnosing, and
            // logging every stream close (playlists at ~1/s) would be noise.
            if (maxReadDurationMs >= SLOW_READ_THRESHOLD_MS) {
                Log.i(
                    DIAG_TAG,
                    "[VideoTransport] slow-stream-close reads=$readCount maxReadMs=$maxReadDurationMs " +
                        "totalBytes=$totalBytes transport=TAILSCALE",
                )
            }
            Tsembed.closeStream(id)
        }
    }

    private companion object {
        const val SLOW_READ_THRESHOLD_MS = 500L
    }
}

private const val DIAG_TAG = "VideoTransport"

/**
 * Builds an [HttpBytesResult] from a gomobile HTTP response, normalizing the
 * body so a valid empty HTTP response (204, or a 200 with no content) is a
 * zero-length non-null [ByteArray], never null. gomobile maps a Go byte slice
 * with a nil data pointer to Java null (`go_seq_to_java_bytearray` returns
 * NULL when `s.ptr == NULL`), which would otherwise NPE at `getBody()`. This
 * is the single boundary where an empty body becomes a safe representation for
 * every HTTP consumer (probe, relay GET/PUT, Frigate), never a per-consumer
 * `?:`. Top-level so it is unit-testable without initializing the native
 * [TailscaleBridge] object.
 */
internal fun toBytesResult(
    statusCode: Int,
    contentType: String?,
    finalUrl: String,
    body: ByteArray?,
): HttpBytesResult = HttpBytesResult(
    statusCode = statusCode,
    contentType = contentType,
    finalUrl = finalUrl,
    body = body ?: ByteArray(0),
)
