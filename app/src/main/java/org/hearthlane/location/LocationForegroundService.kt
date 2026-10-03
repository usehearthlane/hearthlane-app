package org.hearthlane.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

/**
 * Foreground service (type `location`) that keeps publishing the device's
 * last-known location to the relay while the app is open, in the background
 * and even when the app is closed. It is the background publishing mechanism
 * for the location capability (Phase 9.3).
 *
 * Phase 3 acquisition: the service owns one [AcquisitionController]
 * (NETWORK + PASSIVE listeners, GPS on demand) feeding the pure
 * [BackgroundLocationPublisher] machine. Mode changes (map open/close) switch
 * the policy in place — the service, the publisher and the listeners are
 * never restarted for a mode change.
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
    private var publisher: BackgroundLocationPublisher? = null
    private var acquisition: AcquisitionController? = null
    private var loopJob: Job? = null
    private var stateJob: Job? = null
    private var wiringJob: Job? = null
    private var appSettings: AppSettings? = null
    private var currentMode: PublisherMode = PublisherMode.BACKGROUND
    @Volatile
    private var locationGateway: TsnetGateway? = null
    @Volatile
    private var destroyed = false

    /** Test seam: true once the publisher is wired and the loop may run. */
    @Volatile
    internal var publisherWired: Boolean = false
        private set

    /** Test seam: how many times the publisher was (re)built. */
    @Volatile
    internal var publisherBuildCount: Int = 0
        private set

    /** Test seam: true when the service stopped itself because sharing is off. */
    @Volatile
    internal var stoppedByOptOut: Boolean = false
        private set

    private val connectivityManager: ConnectivityManager?
        get() = getSystemService(ConnectivityManager::class.java)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastTransport: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
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
                publisher?.publishNow()
            }
            else -> {
                val requestedMode = intent?.getSerializableExtra(EXTRA_MODE) as? PublisherMode
                if (requestedMode != null && requestedMode != currentMode) {
                    currentMode = requestedMode
                    publisher?.setMode(requestedMode)
                    acquisition?.onModeChanged(requestedMode)
                }
                LocationDiagnosticsMonitor.onServiceStarted(currentMode)
                ensurePublisher()
            }
        }
        return START_STICKY
    }

    /**
     * Wires the publisher once settings are available. The wiring is
     * idempotent: a restart never re-enters this path with an existing
     * publisher.
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

    @android.annotation.SuppressLint("MissingPermission")
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
            publish = session::publish,
            deviceId = { AppSettings.nodeHostname(settings.nodeSuffix.value) },
            scope = serviceScope,
            onPublishFailure = session::invalidate,
            mode = currentMode,
        )
        publisher = p
        publisherBuildCount++
        val a = AcquisitionController(
            policy = AcquisitionPolicy(::geoDistanceMetersObserved),
            scope = serviceScope,
            hasFinePermission = { hasFineLocationPermission() },
            hasPublishableFix = { p.hasPublishableFix() },
            isProviderEnabled = { locationManager.isProviderEnabled(it) },
            registerUpdates = { provider, minTimeMs, minDistanceMeters, listener ->
                locationManager.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    minDistanceMeters,
                    listener,
                    android.os.Looper.getMainLooper(),
                )
            },
            unregisterUpdates = { listener -> locationManager.removeUpdates(listener) },
            requestCurrent = { provider, timeoutMs -> reader.readCurrent(provider, timeoutMs) },
            onFix = { sample -> p.submitFix(sample) },
        )
        acquisition = a
        loopJob = serviceScope.launch { p.start() }
        serviceScope.launch { a.start() }
        // Mirror the publisher's sanitized metadata into the shared monitor for
        // Diagnostics (timestamps/states only, never coordinates or payload).
        stateJob = serviceScope.launch {
            p.state.collect { LocationDiagnosticsMonitor.onPublisherState(it) }
        }
    }

    override fun onDestroy() {
        destroyed = true
        unregisterNetworkCallback()
        publisher?.stop()
        publisher = null
        acquisition?.stop()
        acquisition = null
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

    /**
     * Conservative connectivity restoration signal for the machine's
     * [MachineCommand.NetworkRegained] reset: only a real regain (onAvailable
     * after onLost) or a real transport change (WIFI <-> CELLULAR) counts;
     * noisy repeated capabilities callbacks never reset.
     */
    private fun registerNetworkCallback() {
        val manager = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var wasLost = false

            override fun onLost(network: Network) {
                wasLost = true
            }

            override fun onAvailable(network: Network) {
                val regained = wasLost
                wasLost = false
                if (regained) {
                    emitNetworkRegained()
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val transport = transportLabel(capabilities)
                if (transport != null && transport != lastTransport) {
                    val changed = lastTransport != null
                    lastTransport = transport
                    if (changed) {
                        emitNetworkRegained()
                    }
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback
        networkCallback = null
        if (callback != null) {
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
    }

    private fun emitNetworkRegained() {
        LocationEventLog.emit(
            LocationEvent(
                LocationEventLog.EVENT_NETWORK_REGAINED,
                listOf("transport" to lastTransport),
            ),
        )
        publisher?.networkRegained()
    }

    /** Maps the active network to a coarse label ("WIFI"/"CELLULAR"/"OTHER")
     *  for diagnostics only; never exposes addresses. Null when unknown. */
    private fun networkTypeLabel(connectivityManager: ConnectivityManager): String? =
        connectivityManager.activeNetwork
            ?.let { runCatching { connectivityManager.getNetworkCapabilities(it) }.getOrNull() }
            ?.let(::transportLabel)

    private fun transportLabel(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        else -> "OTHER"
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

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

    companion object {
        const val ACTION_START = "org.hearthlane.location.START"
        const val ACTION_STOP = "org.hearthlane.location.STOP"
        const val ACTION_PUBLISH_NOW = "org.hearthlane.location.PUBLISH_NOW"
        const val EXTRA_MODE = "mode"

        private const val CHANNEL_ID = "location"
        private const val NOTIFICATION_ID = 42

        internal fun intent(context: Context, mode: PublisherMode): Intent =
            Intent(context, LocationForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode)
    }
}

/** Great-circle distance for the acquisition policy (Android seam). */
private fun geoDistanceMetersObserved(a: FixObserved, b: FixObserved): Double {
    val results = FloatArray(1)
    android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
    return kotlin.math.abs(results[0].toDouble())
}