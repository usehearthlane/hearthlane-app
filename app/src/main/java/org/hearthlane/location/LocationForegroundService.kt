package org.hearthlane.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.hearthlane.BuildConfig
import org.hearthlane.R
import org.hearthlane.core.connectivity.TsnetGateway
import org.hearthlane.core.relay.RelayConfig
import org.hearthlane.settings.AppSettings
import org.hearthlane.tailscale.TsnetGatewayImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service (type `location`) that keeps publishing the device's
 * last-known location to the relay while the app is open, in the background
 * and even when the app is closed. It is the background publishing mechanism
 * for the location capability (Phase 9.3).
 *
 * Responsibilities: one [BackgroundLocationPublisher] loop (read fix ->
 * publish -> wait), an interval that can be switched (background 5 min vs
 * map-active 30 s), an on-demand publish action, and a persistent (silent)
 * notification required by Android for any foreground service.
 *
 * Platform constraints (documented in the Phase 9.2 spike, PHYSICAL
 * VALIDATION PENDING): Android 14+ forbids starting a location FGS from the
 * background without the background-location permission; force-stop kills the
 * service permanently; Doze may defer network to maintenance windows. The
 * eligibility gate MUST run before `startForegroundService` is invoked (see
 * [LocationFgsGate]); this service is only reached after the gate passed.
 *
 * The location-sharing opt-in is enforced here, AFTER [startForeground]: the
 * start can no longer be aborted safely once dispatched, so a stale opt-out
 * stops the service cleanly instead of crashing.
 */
class LocationForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val intervalMs = AtomicLong(BACKGROUND_INTERVAL_MS)
    private var publisher: BackgroundLocationPublisher? = null
    private var loopJob: Job? = null
    private var stateJob: Job? = null
    private var wiringJob: Job? = null
    private var appSettings: AppSettings? = null
    @Volatile
    private var locationGateway: TsnetGateway? = null
    @Volatile
    private var destroyed = false

    /** Test seam: true once the publisher is wired and the loop may run. */
    @Volatile
    internal var publisherWired: Boolean = false
        private set

    /** Test seam: true when the service stopped itself because sharing is off. */
    @Volatile
    internal var stoppedByOptOut: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PUBLISH_NOW -> {
                ensurePublisher()
                loopJob?.let { publisher?.let { p -> serviceScope.launch { p.publishLatest() } } }
            }
            else -> {
                val requested = intent?.getLongExtra(EXTRA_INTERVAL_MS, 0L)?.takeIf { it > 0 }
                if (requested != null && requested != intervalMs.get()) {
                    // Interval switch (map open/close): restart the loop so the
                    // new cadence takes effect immediately, starting with one
                    // immediate publish.
                    intervalMs.set(requested)
                    restartLoop()
                } else if (requested != null) {
                    intervalMs.set(requested)
                }
                LocationDiagnosticsMonitor.onServiceStarted(intervalMs.get())
                ensurePublisher()
            }
        }
        return START_STICKY
    }

    /**
     * Wires the publisher once settings are available. The wiring is
     * idempotent: if a restart (interval switch) already rebuilt the loop, the
     * pending wiring job finds [publisher] non-null and does nothing.
     */
    private fun ensurePublisher() {
        if (publisher != null) return
        if (wiringJob?.isActive == true) return
        wiringJob = serviceScope.launch {
            val settings = appSettings ?: loadSettings().also { appSettings = it }
            if (destroyed) return@launch
            if (!settings.locationSharingEnabled.value) {
                // The opt-out raced a start that was already dispatched;
                // startForeground was called, so this stop is safe.
                stoppedByOptOut = true
                stopSelf()
                return@launch
            }
            buildPublisher(settings)
            publisherWired = true
        }
    }

    private suspend fun loadSettings(): AppSettings = AppSettings.create(
        context = applicationContext,
        defaultBaseDomain = BuildConfig.HEARTHLANE_BASE_DOMAIN,
        scope = serviceScope,
        relaySubdomain = BuildConfig.HEARTHLANE_RELAY_SUBDOMAIN,
    ).also { it.ready.first { ready -> ready } }

    private fun buildPublisher(settings: AppSettings) {
        if (publisher != null) return
        val locationManager = applicationContext.getSystemService(LocationManager::class.java)
            ?: throw IllegalStateException("LocationManager unavailable")
        val reader = LocationReader(applicationContext, locationManager)
        val gateway = TsnetGatewayImpl(
            hostname = AppSettings.nodeHostname(settings.nodeSuffix.value),
            stateDir = File(filesDir, "tailscale").absolutePath,
            connectTimeoutMs = RelayConfig("", "").tailscaleConnectTimeoutMs,
        )
        locationGateway = gateway
        val connectivityManager =
            applicationContext.getSystemService(ConnectivityManager::class.java)
        val session = RelayPublishSession(
            gateway = gateway,
            config = {
                RelayConfig(
                    localBaseUrl = settings.relayBaseUrl.value,
                    tailscaleBaseUrl = settings.relayBaseUrl.value,
                )
            },
            networkType = { connectivityManager?.let(::networkTypeLabel) },
        )
        val p = BackgroundLocationPublisher(
            readLocation = { reader.readCurrent(LOCATION_TIMEOUT_MS) },
            publish = session::publish,
            deviceId = { AppSettings.nodeHostname(settings.nodeSuffix.value) },
            checkIntervalMs = { intervalMs.get() },
            scope = serviceScope,
            onPublishFailure = session::invalidate,
        )
        publisher = p
        loopJob = serviceScope.launch { p.start() }
        // Mirror the publisher's sanitized metadata into the shared monitor for
        // Diagnostics (timestamps/states only, never coordinates or payload).
        stateJob = serviceScope.launch {
            p.state.collect { LocationDiagnosticsMonitor.onPublisherState(it) }
        }
    }

    private fun restartLoop() {
        publisher?.stop()
        publisher = null
        loopJob?.cancel()
        loopJob = null
        stateJob?.cancel()
        stateJob = null
        // Release the previous gateway's claim before the new session replaces
        // it, so a stale session can never leave the node Running without an
        // owner (converges to STOPPED when nothing else uses it).
        releaseLocationGateway()
        ensurePublisher()
    }

    override fun onDestroy() {
        destroyed = true
        publisher?.stop()
        publisher = null
        loopJob?.cancel()
        loopJob = null
        stateJob?.cancel()
        stateJob = null
        wiringJob?.cancel()
        wiringJob = null
        LocationDiagnosticsMonitor.onServiceStopped()
        serviceScope.cancel()
        releaseLocationGateway()
        super.onDestroy()
    }

    /**
     * Releases the location gateway's claim on the shared node and drops the
     * reference. Best-effort and idempotent: it runs on its own IO scope
     * (survives [serviceScope] cancellation) and never blocks the main thread
     * or throws. A no-op when no gateway is installed.
     */
    private fun releaseLocationGateway() {
        val gateway = locationGateway
        locationGateway = null
        if (gateway == null) return
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            runCatching { gateway.stopIfRunning() }
            TsnetLifecycleMonitor.onNodeStop()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.location_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.location_service_notification_text)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.location_service_notification_title))
            .setContentText(getString(R.string.location_service_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** Maps the active network to a coarse label ("WIFI"/"CELLULAR"/"OTHER")
     *  for diagnostics only; never exposes addresses. Null when unknown. */
    private fun networkTypeLabel(connectivityManager: ConnectivityManager): String? {
        val network = runCatching { connectivityManager.activeNetwork }.getOrNull() ?: return null
        val caps = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
            ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }
    }

    companion object {
        const val ACTION_START = "org.hearthlane.location.START"
        const val ACTION_STOP = "org.hearthlane.location.STOP"
        const val ACTION_PUBLISH_NOW = "org.hearthlane.location.PUBLISH_NOW"
        const val EXTRA_INTERVAL_MS = "interval_ms"

        /** Location-read cadence in background (publishing is adaptive, not per cycle). */
        const val BACKGROUND_INTERVAL_MS = 60_000L
        /** Location-read cadence while the map is open (map-active). */
        const val ACTIVE_INTERVAL_MS = 30_000L

        /** Adaptive publish policy (see [BackgroundLocationPublisher]). */
        const val MIN_PUBLISH_INTERVAL_MS = 30_000L
        const val MAX_PUBLISH_INTERVAL_MS = 5 * 60_000L
        const val DISTANCE_THRESHOLD_METERS = 100.0
        const val LOCATION_TIMEOUT_MS = 10_000L

        private const val CHANNEL_ID = "location"
        private const val NOTIFICATION_ID = 42

        fun intent(context: Context, intervalMs: Long): Intent =
            Intent(context, LocationForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
    }
}