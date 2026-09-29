package org.hearthlane.core.frigate

import org.hearthlane.core.connectivity.TsnetGateway
import org.hearthlane.core.connectivity.HttpBytesResult
import org.hearthlane.core.connectivity.HttpStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests for [TsnetStreamGetter]: it must delegate open/read/close to the
 * [TsnetGateway] and propagate status, transport errors and close without any
 * transport knowledge of its own.
 */
class TsnetStreamGetterTest {

    @Test
    fun `open delegates to the gateway and preserves the stream metadata`() = runTest {
        val gateway = FakeGateway(stream = FakeStream(200, "video/mp4", "http://frigate/clip", "abcdef"))
        val getter = TsnetStreamGetter(gateway)

        val stream = getter.open("http://frigate/clip", 2_000, emptyMap())

        assertEquals(listOf("http://frigate/clip"), gateway.openedUrls)
        assertEquals(200, stream.statusCode)
        assertEquals("video/mp4", stream.contentType)
        assertEquals("http://frigate/clip", stream.finalUrl)
        assertEquals("ab", readText(stream, 2))
        stream.close()
        assertTrue("close must reach the gateway stream", gateway.stream!!.closed)
    }

    @Test
    fun `reads chunks and reaches EOF`() = runTest {
        val stream = FakeStream(200, "video/mp4", "http://frigate/clip", "0123456789")
        val getter = TsnetStreamGetter(FakeGateway(stream = stream))

        val opened = getter.open("http://frigate/clip", 2_000, emptyMap())

        assertEquals("0123", readText(opened, 4))
        assertEquals("4567", readText(opened, 4))
        assertEquals("89", readText(opened, 4))
        assertEquals(-1, opened.read(ByteArray(4), 0, 4))
        opened.close()
    }

    @Test
    fun `a transport error on open is propagated`() = runTest {
        val gateway = FakeGateway(openError = IOException("connection refused"))
        val getter = TsnetStreamGetter(gateway)

        var thrown: Exception? = null
        try {
            getter.open("http://frigate/clip", 2_000, emptyMap())
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue("the transport error must be propagated", thrown is IOException)
    }

    @Test
    fun `read after close returns EOF without hitting the transport`() = runTest {
        val stream = FakeStream(200, "video/mp4", "http://frigate/clip", "abcdef")
        val getter = TsnetStreamGetter(FakeGateway(stream = stream))

        val opened = getter.open("http://frigate/clip", 2_000, emptyMap())
        opened.close()

        assertEquals(-1, opened.read(ByteArray(4), 0, 4))
        assertEquals(0, stream.readAttempts) // no transport read after close
    }

    @Test
    fun `close is idempotent`() = runTest {
        val stream = FakeStream(200, "video/mp4", "http://frigate/clip", "abcdef")
        val getter = TsnetStreamGetter(FakeGateway(stream = stream))

        val opened = getter.open("http://frigate/clip", 2_000, emptyMap())
        opened.close()
        opened.close()

        assertTrue(stream.closed)
    }

    private class FakeGateway(
        val stream: FakeStream? = null,
        val openError: Exception? = null,
    ) : TsnetGateway {
        val openedUrls = mutableListOf<String>()

        override suspend fun ensureRunning() = Unit
        override suspend fun stopIfRunning() = Unit
        override suspend fun reset() = Unit
        override suspend fun httpGet(url: String, timeoutMs: Long): String = ""
        override suspend fun httpGetBytes(url: String, timeoutMs: Long): HttpBytesResult =
            HttpBytesResult(200, "application/json", url, ByteArray(0))

        override suspend fun httpOpenStream(url: String, connectTimeoutMs: Long, headers: Map<String, String>): HttpStream {
            openedUrls.add(url)
            openError?.let { throw it }
            return requireNotNull(stream)
        }
    }

    private class FakeStream(
        override val statusCode: Int,
        override val contentType: String?,
        override val finalUrl: String,
        private val body: String,
    ) : HttpStream {
        var readAttempts = 0
        val closedFlag = AtomicBoolean(false)
        val closed: Boolean get() = closedFlag.get()
        private var offset = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closedFlag.get()) return -1
            if (length <= 0) return 0
            readAttempts++
            if (this.offset >= body.length) return -1
            val toRead = minOf(length, body.length - this.offset)
            val bytes = body.substring(this.offset, this.offset + toRead).toByteArray(Charsets.UTF_8)
            bytes.copyInto(buffer, destinationOffset = offset)
            this.offset += toRead
            return toRead
        }

        override fun close() {
            closedFlag.set(true)
        }
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