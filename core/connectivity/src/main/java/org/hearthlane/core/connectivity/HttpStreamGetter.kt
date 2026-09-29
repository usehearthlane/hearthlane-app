package org.hearthlane.core.connectivity

import java.io.Closeable

/**
 * A progressive HTTP GET response. Unlike [HttpBytesGetter], which buffers the
 * whole response body, an [HttpStream] exposes the response metadata up front
 * and lets the caller read the body incrementally, so resources that stay open
 * for a long time (for example a Frigate event clip) never materialize fully
 * in memory.
 *
 * [read] copies up to [length] bytes into [buffer] starting at [offset] and
 * returns the number of bytes read, or -1 when the body is exhausted. [close]
 * releases the underlying connection, is idempotent, and also interrupts a
 * pending [read]. A non-2xx response is still a valid stream: [statusCode]
 * preserves the server status and the body is whatever the server sent.
 */
interface HttpStream : Closeable {
    val statusCode: Int
    val contentType: String?
    val finalUrl: String

    /**
     * Length of the response body when the server announced it (Content-Length,
     * or the range length for a 206), null when unknown (chunked/streamed).
     * Lets a Media3 DataSource report a known length to the player for HLS
     * segments and clips; unknown lengths fall back to EOF-driven reads.
     */
    val contentLength: Long?
        get() = null

    /**
     * Reads up to [length] bytes into [buffer] starting at [offset].
     *
     * @return the number of bytes read, or -1 at end of stream.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    override fun close()
}

/**
 * Opens an HTTP GET as a progressive [HttpStream].
 *
 * Implementations never fall back to another network path: the tsnet
 * implementation dials exclusively through the embedded tunnel, while the
 * local implementation uses the normal Android network. A non-2xx response is
 * returned (status preserved), not thrown; only transport failures throw.
 *
 * [headers] are the request headers Media3 asked the DataSource to send
 * (for example `Range`, `Accept`, `User-Agent`); an empty map sends none.
 *
 * @param connectTimeoutMs timeout for establishing the connection and reading
 *   the response headers. The body may remain open for the whole playback, so
 *   this is never treated as a budget for the entire download.
 */
fun interface HttpStreamGetter {
    suspend fun open(url: String, connectTimeoutMs: Long, headers: Map<String, String>): HttpStream
}