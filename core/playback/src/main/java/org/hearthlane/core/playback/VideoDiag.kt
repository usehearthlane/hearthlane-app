package org.hearthlane.core.playback

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-overhead diagnostic instrumentation for the video pipeline. Safe by
 * construction: it never logs URLs, tokens, coordinates or private data — only
 * monotonic ids, counts, timings and coarse labels. Shared ids correlate a
 * player generation with its data-source requests in a single logcat stream.
 * Per-request/per-segment logs are deliberately absent: normal operation only
 * surfaces session, state, startup and error milestones.
 */
object VideoDiag {

    private const val TAG = "VideoDiag"
    private val ids = AtomicLong(0)

    /** Next monotonic id, shared by player generations and data-source opens. */
    fun nextId(): Long = ids.incrementAndGet()

    fun player(type: String, message: String) {
        Log.i(TAG, "[VideoDiag][$type] $message")
    }

    fun playerError(type: String, message: String, error: Throwable) {
        Log.e(TAG, "[VideoDiag][$type] $message", error)
    }

    fun dataHttpError(label: String, id: Long, resource: String, status: Int) {
        Log.i(TAG, "[VideoData] http-error id=$id type=$label resource=$resource status=$status")
    }

    fun dataReadStall(
        label: String,
        id: Long,
        resource: String,
        gapMs: Long,
        bytesBefore: Long,
        totalBytes: Long,
    ) {
        Log.i(
            TAG,
            "[VideoData] read-stall id=$id type=$label resource=$resource " +
                "gapMs=$gapMs bytesBefore=$bytesBefore totalBytes=$totalBytes",
        )
    }
}