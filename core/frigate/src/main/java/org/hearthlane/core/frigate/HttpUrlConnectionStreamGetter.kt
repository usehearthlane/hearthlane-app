package org.hearthlane.core.frigate

import org.hearthlane.core.connectivity.HttpStream
import org.hearthlane.core.connectivity.HttpStreamGetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [HttpStreamGetter] over the normal Android network stack
 * (`HttpURLConnection`). Used only for the home-LAN path and never for the
 * Tailscale path.
 *
 * The body is exposed through [HttpStream.read], which reads the connection's
 * [InputStream] incrementally — the response is never materialized whole.
 */
class HttpUrlConnectionStreamGetter : HttpStreamGetter {

    override suspend fun open(
        url: String,
        connectTimeoutMs: Long,
        headers: Map<String, String>,
    ): HttpStream = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs.toInt()
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            // No read timeout: the response may stay open for the whole
            // playback (a stalled peer is interrupted by close() instead).
            val code = connection.responseCode
            val contentType = connection.getHeaderField("Content-Type")
            val finalUrl = connection.url.toString()
            val body = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            HttpUrlConnectionStream(
                code,
                contentType,
                finalUrl,
                (connection.contentLength).takeIf { it >= 0 }?.toLong(),
                body,
                connection,
            )
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }
}

private class HttpUrlConnectionStream(
    override val statusCode: Int,
    override val contentType: String?,
    override val finalUrl: String,
    override val contentLength: Long?,
    private val body: InputStream?,
    private val connection: HttpURLConnection,
) : HttpStream {

    private val closed = AtomicBoolean(false)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (closed.get()) return -1
        val input = body ?: return -1
        return input.read(buffer, offset, length)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                body?.close()
            } catch (_: IOException) {
                // The connection is disconnected regardless.
            }
            connection.disconnect()
        }
    }
}