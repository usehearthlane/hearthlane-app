package org.hearthlane.tailscale

import android.os.SystemClock
import android.util.Log
import org.hearthlane.core.connectivity.ConnectivityState
import org.hearthlane.core.connectivity.ConnectivityStatus
import org.hearthlane.core.connectivity.TailscaleAuthRequired
import org.hearthlane.core.connectivity.TsnetGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TsnetGateway] backed by the gomobile binding. This is the only component
 * that starts the embedded node for the Frigate connection path; the fallback
 * strategy only reaches it after the local probe fails.
 *
 * Ownership model (v0.1.1 hardening): the embedded node is a single
 * process-global Go instance, but the app has two independent gateways (the
 * foreground UI and the location publisher). This instance claims the node
 * only while it needs it ([leased]) and mirrors the claim into the shared
 * [TsnetOwnership] counter, so the node is only physically stopped when the
 * LAST consumer releases it — a location publish can never tear down a node
 * that the foreground is still using.
 *
 * Interactive enrollment is a third, explicit owner: when the backend reports
 * NeedsLogin the gateway hands the pending login to the process-global
 * [TsnetEnrollmentSession], which retains its own lease so the exact physical
 * node that produced the AuthURL survives this consumer's release (and the
 * foreground backgrounding when the user opens the browser). The session
 * releases when the backend reaches CONNECTED, restoring strict on-demand.
 */
class TsnetGatewayImpl(
    private val hostname: String,
    private val stateDir: String,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
) : TsnetGateway {

    /** Serializes [ensureRunning] so concurrent callers cannot call start() simultaneously. */
    private val connectMutex = Mutex()

    /** Whether THIS consumer instance currently claims the global node. */
    private val leased = AtomicBoolean(false)

    // Test seams: default to the real process-global bridge (resolved lazily, so
// constructing a gateway in a JVM test never initializes the native bridge),
// injectable so the concurrency guarantees (wait-for-connected,
// release-on-failure, enrollment survival) are testable deterministically
// without the native node.
    internal var acquireOwnership: (hostname: String, authKey: String, stateDir: String) -> Boolean =
        { hostname, authKey, stateDir -> TailscaleBridge.acquire(hostname, authKey, stateDir) }
    internal var releaseOwnership: () -> Boolean = { TailscaleBridge.release() }
    internal var readStatus: () -> ConnectivityStatus = { TailscaleBridge.status() }
    internal var elapsedRealtime: () -> Long = SystemClock::elapsedRealtime

    /**
     * Test seam for the process-global enrollment ownership, resolved lazily so
     * constructing a gateway in a JVM test never initializes the native bridge.
     */
    internal var enrollmentSessionProvider: () -> TsnetEnrollmentSession =
        { TailscaleBridge.enrollmentSession }

    /** Test seam for the administrator identity reset. */
    internal var resetOwnership: () -> Unit = { TailscaleBridge.resetOwnership() }

    override suspend fun ensureRunning() {
        connectMutex.withLock {
            // Claim this consumer's use of the node (idempotent per instance).
            // Only the first consumer of the process triggers a physical start
            // (inside the atomic ownership transition); a node already held by
            // the foreground is reused, never double-started.
            if (leased.compareAndSet(false, true)) {
                try {
                    Log.i(
                        TAG,
                        "[TsnetLifecycle] start pre: stateDir=$stateDir stateDirExists=${File(stateDir).exists()} logDirExists=${File(stateDir, "logs").exists()}",
                    )
                    acquireOwnership(hostname, "", stateDir)
                } catch (e: Exception) {
                    // A synchronously throwing first start (0->1) records no owner
                    // inside the ownership transition, so this instance must also
                    // drop the claim it just took: never leave a lease behind
                    // without a physical owner, and let another consumer retry.
                    leased.set(false)
                    throw e
                }
            }

            try {
                val deadline = elapsedRealtime() + connectTimeoutMs
                var sawAuth = false
                var authUrl: String? = null
                while (elapsedRealtime() < deadline) {
                    val status = readStatus()
                    when (status.state) {
                        ConnectivityState.CONNECTED -> {
                            // The backend is authenticated: any pending
                            // interactive enrollment is finished, so its lease
                            // must not outlive this observation (the strict
                            // on-demand lifecycle resumes immediately).
                            enrollmentSessionProvider().complete()
                            Log.i(TAG, "Tailscale connected")
                            return
                        }
                        ConnectivityState.AUTHENTICATING -> {
                            val url = status.authUrl?.takeIf { it.isNotBlank() }
                            // A node with a persisted identity can briefly report
                            // AUTHENTICATING while restoring its session before
                            // reaching CONNECTED. Only a state that persists across
                            // two polls is treated as genuinely needing enrollment;
                            // a single observation lets the node continue to
                            // CONNECTED without routing the app into Setup.
                            if (sawAuth) {
                                throw enrollmentRequired(url ?: authUrl)
                            }
                            sawAuth = true
                            authUrl = url ?: authUrl
                        }
                        ConnectivityState.FAILED -> {
                            // The node is gone: an enrollment session watching it
                            // would hold a lease for a dead node. Drop it before
                            // propagating the failure so a retry can start clean.
                            enrollmentSessionProvider().complete()
                            throw IOException("tailscale failed: ${status.error ?: "unknown error"}")
                        }
                        else -> {
                            // CONNECTING / DISCONNECTED: the node is progressing, so
                            // a previous AUTHENTICATING observation was transient.
                            sawAuth = false
                        }
                    }
                    delay(POLL_INTERVAL_MS)
                }
                if (sawAuth) {
                    throw enrollmentRequired(authUrl)
                }
                throw IOException("tailscale did not reach Running within ${connectTimeoutMs}ms")
            } catch (e: Exception) {
                // A failed or cancelled connect must not leave this instance's
                // claim registered: release it (stopping the node only when this
                // was the last consumer) so the node is never left Running
                // without a logical owner and another consumer can retry.
                stopIfRunning()
                throw e
            }
        }
    }

    /**
     * Protects a pending interactive enrollment with the process-global
     * [TsnetEnrollmentSession] and returns the exception to surface. The
     * session retains its own lease against the already-owned physical node, so
     * the calling consumer can release its lease (including when the process
     * backgrounds as the user opens the browser) without destroying the node
     * that produced the AuthURL.
     *
     * Returns an [IOException] when the node lost its physical owner
     * concurrently and the URL is already stale: surfacing [TailscaleAuthRequired]
     * in that case would hand the UI a dead URL.
     */
    private fun enrollmentRequired(authUrl: String?): Exception {
        val enrollment = enrollmentSessionProvider()
        if (enrollment.begin(authUrl)) {
            Log.i(TAG, "Tailscale requires authentication (enrollment pending)")
            return TailscaleAuthRequired(authUrl)
        }
        Log.w(TAG, "Tailscale enrollment could not be retained; node was already released")
        return IOException("tailscale node stopped while enrollment was pending")
    }

    override suspend fun stopIfRunning() {
        // Release THIS instance's claim; without a lease there is nothing to do.
        // The atomic release stops the node only when this was the last consumer.
        // An active enrollment session holds its own lease, so the foreground
        // backgrounding here never destroys a pending enrollment.
        if (!leased.compareAndSet(true, false)) return
        releaseOwnership()
    }

    override suspend fun httpGet(url: String, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            TailscaleBridge.httpGet(url, timeoutMs)
        }

    override suspend fun reset() {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "resetting embedded Tailscale node identity")
            // Administrator reset: drop every consumer claim (including the
            // enrollment session, so a stale enrollment cannot keep the node
            // alive) and force the node down, then clear its identity so the
            // next start re-enrolls interactively.
            resetOwnership()
            leased.set(false)
            val dir = File(stateDir)
            val logsDir = File(dir, "logs")
            Log.i(
                TAG,
                "[TsnetLifecycle] reset: stateDir=$stateDir stateDirExists=${dir.exists()} logDirExists=${logsDir.exists()}",
            )
            if (dir.exists()) {
                // Remove ONLY the node identity/enrollment state. The runtime log
                // directory is infrastructure, not identity: logpolicy panics with
                // "no safe place found to store log state" when TS_LOGS_DIR points
                // to a deleted directory, so logs/ is preserved across the reset.
                dir.listFiles()?.forEach { child ->
                    if (child != logsDir) {
                        child.deleteRecursively()
                    }
                }
                logsDir.mkdirs()
            }
            Log.i(
                TAG,
                "[TsnetLifecycle] reset done: stateDir=$stateDir stateDirExists=${dir.exists()} logDirExists=${logsDir.exists()}",
            )
        }
    }

    override suspend fun httpGetBytes(url: String, timeoutMs: Long) =
        withContext(Dispatchers.IO) {
            TailscaleBridge.httpGetBytes(url, timeoutMs)
        }

    override suspend fun httpPut(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ) = httpRequest("PUT", url, contentType, body, headers, timeoutMs)

    override suspend fun httpRequest(
        method: String,
        url: String,
        contentType: String?,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Long,
    ) = withContext(Dispatchers.IO) {
        TailscaleBridge.httpRequest(
            method = method,
            url = url,
            contentType = contentType ?: "",
            body = body ?: "",
            headers = headers,
            timeoutMs = timeoutMs,
        )
    }

    override suspend fun httpOpenStream(
        url: String,
        connectTimeoutMs: Long,
        headers: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        TailscaleBridge.httpOpenStream(url, headers, connectTimeoutMs)
    }

    private companion object {
        const val TAG = "FrigateConnection"
        const val POLL_INTERVAL_MS = 500L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 45_000L
    }
}
