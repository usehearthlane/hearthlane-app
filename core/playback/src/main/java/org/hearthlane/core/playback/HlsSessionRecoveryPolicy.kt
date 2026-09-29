package org.hearthlane.core.playback

/**
 * Early session-recovery policy for LIVE HLS.
 *
 * When the current go2rtc media playlist returns HTTP 404/410 repeatedly, the
 * session id is dead; waiting for Media3 to exhaust its retries costs seconds of
 * visible interruption (buffer drains, Source error, IDLE, then a fresh
 * session). This policy tracks consecutive dead-playlist errors and decides when
 * to trigger an early session recovery, so the app can re-resolve a new master
 * / media playlist while the old session still has buffered content.
 *
 * Rules:
 * - Only MEDIA_PLAYLIST 404/410 count (segments slide and are handled by Media3;
 *   the caller routes only playlist errors here).
 * - A successful playlist load resets the counter.
 * - [newSession] (a new MediaSource / play) resets the counter and bumps the
 *   epoch, so late callbacks from an old session are ignored via
 *   [isCurrentEpoch].
 * - Recovery is single-flight ([recoveryInFlight]) and bounded by a minimum
 *   interval ([MIN_RECOVERY_INTERVAL_MS]) to prevent a tight retry loop.
 */
internal class HlsSessionRecoveryPolicy(
    private val nowMs: () -> Long,
    private val thresholdErrors: Int = EARLY_RECOVERY_PLAYLIST_ERRORS,
    private val minRecoveryIntervalMs: Long = MIN_RECOVERY_INTERVAL_MS,
) {

    var sessionEpoch = 0
        private set

    /** Consecutive MEDIA_PLAYLIST 404/410 errors in the current session. */
    var playlistErrors = 0
        private set

    /** True while a recovery was triggered and not yet confirmed alive. */
    var recoveryInFlight = false
        private set

    private var lastRecoveryAtMs = Long.MIN_VALUE

    /** True when [epoch] belongs to the active session (not a stale callback). */
    fun isCurrentEpoch(epoch: Int): Boolean = epoch == sessionEpoch

    /** A new session (new MediaSource) starts: bump the epoch and reset errors. */
    fun newSession(): Int {
        sessionEpoch++
        playlistErrors = 0
        return sessionEpoch
    }

    /** A media playlist load succeeded: the session is alive, reset the counter. */
    fun onPlaylistLoadSuccess() {
        if (playlistErrors > 0) playlistErrors = 0
    }

    /** Records a dead-playlist error; only HTTP 404/410 count. */
    fun onPlaylistLoadError(statusCode: Int) {
        if (recoveryInFlight) return
        if (statusCode == 404 || statusCode == 410) playlistErrors++
    }

    /**
     * True when a recovery should begin NOW: threshold reached, no recovery in
     * flight, and the minimum interval since the last recovery has elapsed.
     */
    fun canTriggerRecovery(): Boolean {
        if (recoveryInFlight) return false
        if (playlistErrors < thresholdErrors) return false
        val now = nowMs()
        return lastRecoveryAtMs == Long.MIN_VALUE || now - lastRecoveryAtMs >= minRecoveryIntervalMs
    }

    /** Milliseconds remaining before a deferred recovery may run (0 = now). */
    fun backoffRemainingMs(): Long {
        if (lastRecoveryAtMs == Long.MIN_VALUE) return 0
        return (minRecoveryIntervalMs - (nowMs() - lastRecoveryAtMs)).coerceAtLeast(0)
    }

    fun beginRecovery() {
        recoveryInFlight = true
        lastRecoveryAtMs = nowMs()
    }

    /**
     * The new session is confirmed alive (first playlist success after a
     * recovery): clears the in-flight flag and the counter, returning the
     * elapsed recovery duration for diagnostics.
     */
    fun onRecoveryConfirmed(): Long {
        val elapsed = if (lastRecoveryAtMs == Long.MIN_VALUE) 0L else nowMs() - lastRecoveryAtMs
        recoveryInFlight = false
        playlistErrors = 0
        return elapsed
    }

    companion object {
        const val EARLY_RECOVERY_PLAYLIST_ERRORS = 2
        const val MIN_RECOVERY_INTERVAL_MS = 5_000L
    }
}

/**
 * True when a load failure is definitive evidence that the go2rtc HLS session
 * is dead: a MEDIA_PLAYLIST (never a segment or init, which slide/restart) with
 * HTTP 404 or 410.
 */
internal fun isDeadPlaylistError(resource: String, status: Int): Boolean =
    resource == "MEDIA_PLAYLIST" && (status == 404 || status == 410)