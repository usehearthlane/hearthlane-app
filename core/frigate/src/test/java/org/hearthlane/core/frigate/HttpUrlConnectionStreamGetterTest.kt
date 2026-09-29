package org.hearthlane.core.frigate

import org.hearthlane.core.connectivity.HttpStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * JVM tests for the LOCAL streaming getter against a tiny, dependency-free HTTP
 * server ([TestHttpServer], backed by [ServerSocket]). They prove the getter
 * reads the response body incrementally and never depends on a Content-Length
 * (chunked and connection-close bodies work).
 */
class HttpUrlConnectionStreamGetterTest {

    private lateinit var server: TestHttpServer

    @Before
    fun setUp() {
        server = TestHttpServer()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `open preserves status content type and final url`() = runBlocking {
        server.handler = { Response.Fixed(200, "video/mp4", "0123456789".toByteArray()) }

        val stream = HttpUrlConnectionStreamGetter().open("${server.url}/clip", 2_000, emptyMap())

        assertEquals(200, stream.statusCode)
        assertEquals("video/mp4", stream.contentType)
        assertEquals("${server.url}/clip", stream.finalUrl)
        stream.close()
    }

    @Test
    fun `reads the body incrementally across multiple calls until EOF`() = runBlocking {
        server.handler = { Response.Fixed(200, "video/mp4", "0123456789abcdef".toByteArray()) }

        val stream = HttpUrlConnectionStreamGetter().open("${server.url}/clip", 2_000, emptyMap())

        assertEquals("0123", readText(stream, 4))
        assertEquals("4567", readText(stream, 4))
        assertEquals("89ab", readText(stream, 4))
        assertEquals("cdef", readText(stream, 4))
        assertEquals(-1, stream.read(ByteArray(4), 0, 4))
        stream.close()
    }

    @Test
    fun `chunked body without content-length is read incrementally`() = runBlocking {
        server.handler = {
            Response.Chunked(200, "video/mp4", listOf("part1".toByteArray(), "part2".toByteArray()))
        }

        val stream = HttpUrlConnectionStreamGetter().open("${server.url}/chunked", 2_000, emptyMap())

        assertEquals("part1", readText(stream, 5))
        assertEquals("part2", readText(stream, 5))
        assertEquals(-1, stream.read(ByteArray(8), 0, 8))
        stream.close()
    }

    @Test
    fun `connection-close body without content-length is read until EOF`() = runBlocking {
        server.handler = {
            Response.CloseDelimited(200, "video/mp4", "0123456789".toByteArray())
        }

        val stream = HttpUrlConnectionStreamGetter().open("${server.url}/clip", 2_000, emptyMap())

        assertEquals("0123", readText(stream, 4))
        assertEquals("456789", readText(stream, 6))
        assertEquals(-1, stream.read(ByteArray(4), 0, 4))
        stream.close()
    }

    @Test
    fun `a large body is never materialized whole`() = runBlocking {
        val bodySize = 512 * 1024
        val block = ByteArray(64 * 1024) { 0x41 }
        server.handler = {
            val blocks = ArrayList<ByteArray>()
            var written = 0
            while (written < bodySize) {
                blocks.add(block)
                written += block.size
            }
            Response.Chunked(200, "application/octet-stream", blocks)
        }

        val stream = HttpUrlConnectionStreamGetter().open("${server.url}/large", 2_000, emptyMap())
        val buffer = ByteArray(1024)
        var total = 0
        var maxRead = 0
        while (true) {
            val n = stream.read(buffer, 0, buffer.size)
            if (n < 0) break
            assertTrue("read returned more than the buffer size", n <= buffer.size)
            maxRead = maxOf(maxRead, n)
            total += n
        }
        assertEquals(bodySize, total)
        assertTrue(
            "reads must be bounded by the requested buffer size, not the body size",
            maxRead <= buffer.size,
        )
        stream.close()
    }

    @Test
    fun `404 preserves the status and yields a readable or empty body`() = runBlocking {
        server.handler = { Response.Fixed(404, "text/plain", ByteArray(0)) }

        val stream = HttpUrlConnectionStreamGetter().open("${server.url}/missing", 2_000, emptyMap())

        assertEquals(404, stream.statusCode)
        // No body was sent; read reaches EOF without throwing.
        assertEquals(-1, stream.read(ByteArray(4), 0, 4))
        stream.close()
    }

    @Test
    fun `500 preserves the status`() = runBlocking {
        server.handler = { Response.Fixed(500, "text/plain", "boom".toByteArray()) }

        val stream = HttpUrlConnectionStreamGetter().open("${server.url}/broken", 2_000, emptyMap())

        assertEquals(500, stream.statusCode)
        stream.close()
    }

    @Test
    fun `close releases the connection and makes further reads return EOF`() = runBlocking {
        server.handler = { Response.Fixed(200, "video/mp4", "0123456789".toByteArray()) }

        val stream = HttpUrlConnectionStreamGetter().open("${server.url}/clip", 2_000, emptyMap())

        assertEquals("0123", readText(stream, 4))
        stream.close()
        stream.close() // idempotent
        assertEquals(-1, stream.read(ByteArray(4), 0, 4))
    }

    @Test
    fun `transport failure is surfaced as an exception`() = runBlocking {
        val streamGetter = HttpUrlConnectionStreamGetter()
        var thrown: Exception? = null
        try {
            streamGetter.open("http://127.0.0.1:1/clip", 500, emptyMap())
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue("a transport failure must be thrown", thrown != null)
    }

    private fun readText(stream: HttpStream, length: Int): String {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = stream.read(buffer, offset, length - offset)
            if (n < 0) break
            offset += n
        }
        return buffer.toString(Charsets.UTF_8).take(offset)
    }
}

private sealed class Response {
    abstract val status: Int
    abstract val contentType: String

    class Fixed(
        override val status: Int,
        override val contentType: String,
        private val body: ByteArray,
    ) : Response() {
        fun write(out: OutputStream) {
            val header = buildString {
                appendLine("HTTP/1.1 ${status} ${reason(status)}")
                appendLine("Content-Type: $contentType")
                appendLine("Content-Length: ${body.size}")
                appendLine("Connection: close")
                appendLine()
            }.toByteArray(Charsets.US_ASCII)
            out.write(header)
            out.write(body)
        }
    }

    /** Transfer-Encoding: chunked; the body is delivered in pieces. */
    class Chunked(
        override val status: Int,
        override val contentType: String,
        private val chunks: List<ByteArray>,
    ) : Response() {
        fun write(out: OutputStream) {
            val header = buildString {
                appendLine("HTTP/1.1 ${status} ${reason(status)}")
                appendLine("Content-Type: $contentType")
                appendLine("Transfer-Encoding: chunked")
                appendLine("Connection: close")
                appendLine()
            }.toByteArray(Charsets.US_ASCII)
            out.write(header)
            for (chunk in chunks) {
                out.write("%x\r\n".format(chunk.size).toByteArray(Charsets.US_ASCII))
                out.write(chunk)
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
            }
            out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        }
    }

    /** No Content-Length and no chunked framing; EOF is signaled by close. */
    class CloseDelimited(
        override val status: Int,
        override val contentType: String,
        private val body: ByteArray,
    ) : Response() {
        fun write(out: OutputStream) {
            val header = buildString {
                appendLine("HTTP/1.1 ${status} ${reason(status)}")
                appendLine("Content-Type: $contentType")
                appendLine("Connection: close")
                appendLine()
            }.toByteArray(Charsets.US_ASCII)
            out.write(header)
            out.write(body)
        }
    }
}

private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Status"
    }

/**
 * Minimal one-thread-per-connection HTTP server for tests. Dependency-free and
 * sufficient to exercise streaming: fixed-length, chunked and connection-close
 * responses.
 */
private class TestHttpServer {
    @Volatile
    var handler: (path: String) -> Response = { Response.Fixed(404, "text/plain", ByteArray(0)) }

    private val serverSocket = ServerSocket(0)
    private val acceptThread = thread(isDaemon = true, name = "test-http-server") {
        while (!serverSocket.isClosed) {
            try {
                val socket = serverSocket.accept()
                thread(isDaemon = true) { handle(socket) }
            } catch (_: Exception) {
                break
            }
        }
    }

    val url: String get() = "http://127.0.0.1:${serverSocket.localPort}"

    fun stop() {
        serverSocket.close()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val path = readRequestPath(input)
            readUntilEmptyLine(input)
            val response = handler(path)
            val out = s.getOutputStream()
            when (response) {
                is Response.Fixed -> response.write(out)
                is Response.Chunked -> response.write(out)
                is Response.CloseDelimited -> response.write(out)
            }
            out.flush()
        }
    }

    private fun readRequestPath(input: java.io.InputStream): String {
        val line = readLine(input) ?: return "/"
        val parts = line.split(" ")
        return parts.getOrElse(1) { "/" }
    }

    private fun readUntilEmptyLine(input: java.io.InputStream) {
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) return
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) {
                val line = bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
                return line
            }
            bytes.write(b)
        }
    }
}