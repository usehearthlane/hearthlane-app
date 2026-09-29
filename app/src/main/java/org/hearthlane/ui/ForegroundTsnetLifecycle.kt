package org.hearthlane.ui

import android.util.Log
import org.hearthlane.core.connectivity.TsnetGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ties the foreground tsnet lease to PROCESS foreground state, never to a
 * specific screen.
 *
 * The AppRoot gateway is shared by the foreground consumers (Frigate
 * connection, relay, camera discovery, nickname sync). As long as the process
 * is in the foreground and a remote consumer is active, the gateway holds one
 * lease on the global node (see [TsnetOwnership]). When the process leaves the
 * foreground this coordinator tears the consumers down and releases the
 * gateway's claim, so the node converges to STOPPED (the location foreground
 * service still acquires its own lease per remote publish).
 *
 * Adaptive background idle policy: an ON_STOP is NOT treated as a durable
 * background until a grace window elapses. A normal multitasking return
 * (Recents/app switcher, quick task switch) cancels the pending stop and keeps
 * the consumers, the gateway lease and the node untouched, so no request is
 * interrupted and no reconnect/restart happens. Two grace windows:
 *
 * - [BACKGROUND_GRACE_MS] (60s): a background where the device is still
 *   interactively used (the user may come back at any moment). A return inside
 *   the window is instant; expiring while still backgrounded runs the real
 *   teardown.
 * - [SCREEN_OFF_GRACE_MS] (5s): screen off / device lock means the device is
 *   no longer interactively used, so there is little value in keeping the stack
 *   warm. [onScreenOff] shortens an already-scheduled teardown (or schedules
 *   one when the activity is still foreground — display-off stops the resumed
 *   activity anyway), and a short window protects against a quick wake.
 *
 * The policy is NOT based on "the user closed the app": swipe in Recents,
 * process death, force-stop and activity destruction do not form a single
 * reliable event. [dispose] remains only the safety-net cleanup for activity
 * destroy, not the battery mechanism. Every teardown is fully reversible by
 * the existing foreground recovery (re-probe, reconnect, functional resume).
 */
internal class ForegroundTsnetLifecycle(
    private val gateway: TsnetGateway,
    private val startConsumers: () -> Unit,
    private val stopConsumers: () -> Unit,
    private val scope: CoroutineScope,
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val backgroundGraceMs: Long = BACKGROUND_GRACE_MS,
    private val screenOffGraceMs: Long = SCREEN_OFF_GRACE_MS,
) {

    private var pendingStop: Job? = null

    /**
     * Whether the lifecycle-level consumers are currently stopped. Starts true
     * so the first foreground actually starts them; a quick ON_STOP/ON_START
     * leaves it false and the consumers running.
     */
    private var consumersStopped = true

    /** The process returned to the foreground. */
    fun onForeground() {
        // A transient return (Recents/app switcher) cancels the pending
        // background stop before it can tear the node down. When a teardown
        // already ran (60s window or screen-off), re-start the consumers
        // (re-probe; the lease is re-acquired only when the remote path is
        // still needed).
        cancelPendingStop()
        if (consumersStopped) {
            consumersStopped = false
            startConsumers()
        }
    }

    /** The process left the foreground. */
    fun onBackground() {
        if (consumersStopped) return
        // A screen-off teardown (5s) must never be extended back to the long
        // background window by a later ON_STOP, so an existing pending stop is
        // kept as-is.
        if (pendingStop != null) return
        scheduleBackgroundStop(backgroundGraceMs, REASON_BACKGROUND)
    }

    /**
     * The device display turned off / the device locked. The device is no
     * longer interactively used, so the hot foreground stack has little value:
     * shorten an already-scheduled teardown, or schedule one when the activity
     * is still foreground (display-off stops the resumed activity, so this is
     * equivalent to a short background). A quick wake cancels it via
     * [onForeground]; screen-off is NOT treated as app termination — the
     * teardown is reversible by the normal recovery.
     */
    fun onScreenOff() {
        if (consumersStopped) return
        scheduleBackgroundStop(screenOffGraceMs, REASON_SCREEN_OFF)
    }

    /** Real exit (activity destroy): immediate teardown, no grace window. */
    fun dispose() {
        cancelPendingStop()
        stopConsumers()
        ioScope.launch { runCatching { gateway.stopIfRunning() } }
    }

    /**
     * Schedules the real background teardown after [graceMs]. Only the latest
     * timer is valid: repeated events never leave an older timer that stops
     * the node after a later foreground return.
     */
    private fun scheduleBackgroundStop(graceMs: Long, reason: String) {
        cancelPendingStop()
        Log.i(TAG, "[ForegroundLifecycle] background grace scheduled (${graceMs}ms)")
        pendingStop = scope.launch {
            delay(graceMs)
            // ON_START and this resume are serialized on the same dispatcher:
            // if ON_START ran first the job is cancelled here and no teardown
            // happens; otherwise the teardown below runs to completion. The
            // isActive check makes the cancelled case explicit.
            if (!isActive) return@launch
            Log.i(TAG, "[ForegroundLifecycle] background grace expired")
            performBackgroundStop(reason)
        }
    }

    private suspend fun performBackgroundStop(reason: String) {
        Log.i(TAG, "[ForegroundLifecycle] performing background stop reason=${reason}")
        stopConsumers()
        runCatching { gateway.stopIfRunning() }
        consumersStopped = true
        Log.i(TAG, "[ForegroundLifecycle] background stop complete")
    }

    private fun cancelPendingStop() {
        val job = pendingStop
        pendingStop = null
        if (job != null) {
            Log.i(TAG, "[ForegroundLifecycle] background grace cancelled")
            job.cancel()
        }
    }

    private companion object {
        const val TAG = "Hearthlane"

        /**
         * Normal multitasking window: the device is still in interactive use,
         * so a return within it must be instant. Beyond it, a backgrounded
         * Hearthlane is abandoned and the node is physically stopped.
         */
        const val BACKGROUND_GRACE_MS = 60_000L

        /** Screen-off/lock window: the device is not interactively used. */
        const val SCREEN_OFF_GRACE_MS = 5_000L

        const val REASON_BACKGROUND = "background"
        const val REASON_SCREEN_OFF = "screen-off"
    }
}