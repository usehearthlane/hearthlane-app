package org.hearthlane.tailscale

import android.os.SystemClock
import android.util.Log
import org.hearthlane.core.connectivity.ConnectivityState
import org.hearthlane.core.connectivity.ConnectivityStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Explicit ownership of an in-flight interactive enrollment.
 *
 * A first install has no persisted node identity: tsnet starts, reports
 * NeedsLogin, and keeps the pending node key ONLY in memory until the user
 * authorizes the login URL. If the physical node is stopped in that window the
 * key — and therefore the URL — is discarded, and the next start mints a new
 * key/URL; the authorization completed in the browser then lands on a dead
 * node and the enrollment never converges. The URL is valid only while the
 * exact physical node that produced it stays alive.
 *
 * This session is the owner that guarantees that lifetime. It holds its own
 * lease in the process-global [TsnetOwnership] while the backend is waiting for
 * authorization, independently of the consumer that discovered the condition,
 * so:
 * - the foreground can release its lease when the process backgrounds (the user
 *   opened the browser to authorize) and the node stays alive;
 * - the location publisher can run its strict on-demand publish during the
 *   enrollment without tearing the node down, because releasing one lease never
 *   stops a node another lease still owns;
 * - the enrollment lease is released exactly once when the backend reaches
 *   CONNECTED, a terminal state, an explicit cleanup, or the defensive timeout,
 *   returning the node to the normal strict on-demand lifecycle.
 *
 * The session never starts a node: [begin] only [TsnetOwnership.retain]s a
 * claim against a node that already has a physical owner. The monitor runs at
 * a low frequency because its only job is to observe the terminal transition;
 * a consumer waiting on [TsnetGatewayImpl.ensureRunning] still polls at its own
 * cadence.
 *
 * The defensive timeout only bounds an abandoned session (for example the user
 * backed out of Setup and never authorized); it is intentionally generous so a
 * normal interactive authorization is never invalidated while the user is in
 * the browser.
 */
internal class TsnetEnrollmentSession(
    private val retainOwnership: () -> Boolean,
    private val releaseOwnership: () -> Boolean,
    private val readStatus: () -> ConnectivityStatus,
    private val monitorScope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {

    private val lock = Any()
    private var active = false
    private var authUrl: String? = null
    private var monitorJob: Job? = null

    /** Whether this session currently holds an enrollment lease. */
    val isActive: Boolean
        get() = synchronized(lock) { active }

    /** Last AuthURL observed for the pending enrollment, if any. */
    val pendingAuthUrl: String?
        get() = synchronized(lock) { authUrl }

    /**
     * Ensures this session owns a lease while the backend waits for
     * authorization. Idempotent: a second call while active only refreshes the
     * observed URL. Returns true when the enrollment is protected by this
     * session (including the already-active case), false when the node has no
     * physical owner left to protect.
     */
    fun begin(authUrl: String?): Boolean = synchronized(lock) {
        if (active) {
            this.authUrl = authUrl ?: this.authUrl
            return true
        }
        if (!retainOwnership()) return false
        active = true
        this.authUrl = authUrl
        monitorJob = monitorScope.launch { monitorLoop() }
        true
    }

    /**
     * Releases the enrollment lease exactly once and stops monitoring. Called
     * when the backend reaches CONNECTED (or a terminal state), and by explicit
     * cleanup. Idempotent.
     */
    fun complete() {
        val job = synchronized(lock) {
            if (!active) return
            active = false
            authUrl = null
            val running = monitorJob
            monitorJob = null
            running
        }
        job?.cancel()
        releaseOwnership()
    }

    /**
     * Drops the claim WITHOUT releasing it. Only for the identity reset, which
     * clears every ownership claim (and physically stops the node) itself, so
     * releasing here would double-stop.
     */
    fun discard() {
        val job = synchronized(lock) {
            if (!active) return
            active = false
            authUrl = null
            val running = monitorJob
            monitorJob = null
            running
        }
        job?.cancel()
    }

    /**
     * Watches the node until the enrollment reaches a terminal state. CONNECTED
     * means the authorization completed; FAILED/STOPPED/DISCONNECTED mean the
     * physical node is gone, so a lease for it is stale. In every case the
     * enrollment lease is released exactly once.
     */
    private suspend fun monitorLoop() {
        val deadline = elapsedRealtime() + timeoutMs
        while (currentCoroutineContext().isActive) {
            when (readStatus().state) {
                ConnectivityState.CONNECTED -> {
                    Log.i(TAG, "[TsnetLifecycle] enrollment connected; releasing enrollment lease")
                    complete()
                    return
                }
                ConnectivityState.FAILED,
                ConnectivityState.STOPPED,
                ConnectivityState.DISCONNECTED,
                -> {
                    Log.w(TAG, "[TsnetLifecycle] enrollment node is gone; releasing enrollment lease")
                    complete()
                    return
                }
                else -> Unit
            }
            if (elapsedRealtime() >= deadline) {
                Log.w(TAG, "[TsnetLifecycle] enrollment session timed out; releasing enrollment lease")
                complete()
                return
            }
            delay(pollIntervalMs)
        }
    }

    private companion object {
        const val TAG = "FrigateConnection"

        /** Low-frequency observation: the terminal transition is not latency-critical. */
        const val DEFAULT_POLL_INTERVAL_MS = 5_000L

        /**
         * Defensive bound for an abandoned enrollment. Generous on purpose: a
         * user authorizing in the browser must not have the session expire
         * under them.
         */
        const val DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
