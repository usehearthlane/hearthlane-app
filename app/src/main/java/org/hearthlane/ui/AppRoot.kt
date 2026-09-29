package org.hearthlane.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.SingletonImageLoader
import org.hearthlane.BuildConfig
import org.hearthlane.R
import org.hearthlane.controller.CameraDiscoveryController
import org.hearthlane.controller.DeviceNicknameSync
import org.hearthlane.controller.EventDetailController
import org.hearthlane.controller.EventDetailState
import org.hearthlane.controller.FrigateConnectionController
import org.hearthlane.controller.LocationSharingController
import org.hearthlane.controller.LocationSharingStatus
import org.hearthlane.controller.PlaybackSnapshotStore
import org.hearthlane.controller.RecentEventsController
import org.hearthlane.controller.RecentEventsState
import org.hearthlane.controller.RelayConnectionController
import org.hearthlane.controller.SettingsController
import org.hearthlane.core.frigate.Camera
import org.hearthlane.core.frigate.CameraDiscoveryState
import org.hearthlane.core.frigate.FrigateConfig
import org.hearthlane.core.frigate.FrigateConnection
import org.hearthlane.core.frigate.FrigateEventApi
import org.hearthlane.core.frigate.bytesGetterFor
import org.hearthlane.core.frigate.streamGetterFor
import org.hearthlane.location.LocationFgsGate
import org.hearthlane.location.LocationForegroundService
import org.hearthlane.location.LocationPermissionSnapshot
import org.hearthlane.navigation.AppNavigation
import org.hearthlane.navigation.Screen
import org.hearthlane.navigation.SetupRouteReasons
import org.hearthlane.settings.AppSettings
import org.hearthlane.setup.shouldShowSetup
import org.hearthlane.tailscale.TsnetGatewayImpl
import org.hearthlane.thumbnail.CameraThumbnailModelFactory
import org.hearthlane.thumbnail.FrigateSnapshotImageLoader
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.hearthlane.ui.locations.LocationsScreen
import org.hearthlane.ui.theme.HearthlaneTheme

/**
 * V1 composition root: loads the persisted settings, owns the embedded
 * Tailscale gateway and the shared connection controller, and routes the
 * minimal navigation. The connection/session state lives here (not in a
 * screen) so later screens can reuse it without re-probing.
 *
 * V1.1 gate: until the administrator completes the initial setup the app shows
 * only the setup screen; after that, normal use goes straight to Home and never
 * asks about infrastructure again. Reopening the setup lives behind the
 * discreet Settings entry on Home.
 *
 * [defaultBaseDomain] seeds the single Hearthlane environment (for example
 * `hearthlane.omni.corp`); the Frigate and Relay endpoints are derived from it
 * by the endpoint resolver and are never configured independently.
 */
@Composable
fun AppRoot(
    stateDir: String,
    defaultBaseDomain: String,
    relaySubdomain: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember {
        AppSettings.create(context, defaultBaseDomain, scope, relaySubdomain)
    }

    val settingsReady by settings.ready.collectAsState()
    if (!settingsReady) {
        HearthlaneTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = stringResource(R.string.settings_loading))
                }
            }
        }
        return
    }

    val setupComplete by settings.setupComplete.collectAsState()

    // The node hostname is an internal value derived from the persisted
    // per-installation suffix; it is never part of the normal configuration
    // flow and only changes when the settings store is cleared.
    val nodeSuffix by settings.nodeSuffix.collectAsState()
    val gateway = remember(nodeSuffix) {
        TsnetGatewayImpl(
            hostname = AppSettings.nodeHostname(nodeSuffix),
            stateDir = stateDir,
            connectTimeoutMs = FrigateConfig("", "").tailscaleConnectTimeoutMs,
        )
    }

    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    val controller = remember(gateway) {
        FrigateConnectionController(
            gateway = gateway,
            settings = settings,
            connectivityManager = connectivityManager,
            scope = scope,
        )
    }

    // Shared relay connection for the family Locations screen; it reuses the
    // same embedded node as Frigate (both probe LOCAL then Tailscale).
    val relayController = remember(gateway) {
        RelayConnectionController(
            gateway = gateway,
            settings = settings,
            scope = scope,
        )
    }

    // Presentation-only nickname for this device: persisted locally and
    // best-effort mirrored to the relay when reachable.
    val deviceNicknameSync = remember(settings, relayController) {
        DeviceNicknameSync(
            settings = settings,
            relayClient = { relayController.client() },
        )
    }

    // Foreground tsnet lease is tied to the PROCESS being in the foreground,
    // never to a specific screen. [ForegroundTsnetLifecycle] stops the
    // foreground consumers and releases the foreground gateway's claim when the
    // process leaves the foreground, so the node converges to STOPPED (the
    // location publisher still acquires its own lease per remote publish).
    // Returning to the foreground re-probes, which re-acquires the lease only
    // when the remote path is still needed (LOCAL keeps the node stopped).
    val lifecycleOwner = LocalLifecycleOwner.current

    // Foreground resume recovery: every ON_START bumps the epoch and records
    // the connection generation at that instant, so the per-destination
    // recovery effects can wait for the transport to be re-established BEFORE
    // re-running an interrupted load. Zero until the first lifecycle event; a
    // real resume (after a background) is epoch >= 2.
    var resumeEpoch by remember { mutableStateOf(0) }
    var resumeStartGen by remember { mutableStateOf(0) }

    val fgLifecycle = remember(gateway, controller, relayController, scope) {
        ForegroundTsnetLifecycle(
            gateway = gateway,
            startConsumers = {
                controller.start()
                controller.connect(restartPlayback = false)
                relayController.probe()
            },
            stopConsumers = {
                controller.stopForBackground()
                relayController.stopForBackground()
            },
            scope = scope,
        )
    }
    DisposableEffect(fgLifecycle, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> fgLifecycle.onBackground()
                Lifecycle.Event.ON_START -> {
                    fgLifecycle.onForeground()
                    // The resume connect launched above cannot complete before
                    // this line, so the recorded generation is the pre-resume
                    // one: the recovery waits for a strictly newer completion.
                    resumeStartGen = controller.connectGeneration.value
                    resumeEpoch++
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Safety net when the composition leaves without an ON_STOP (for
            // example an activity destroy). Idempotent.
            fgLifecycle.dispose()
        }
    }

    // Screen-off / device lock: the device stopped being interactively used, so
    // the foreground stack can be torn down well before the 60s background
    // window. A dynamically registered receiver is lifecycle-safe (unregistered
    // with the composition), never wakes the process (a manifest receiver
    // would) and adds no polling or new service. Screen-off is NOT treated as
    // app termination: the teardown is reversible by the normal recovery.
    DisposableEffect(fgLifecycle, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    fgLifecycle.onScreenOff()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val baseUrl by settings.frigateBaseUrl.collectAsState()
    val baseDomain by settings.baseDomain.collectAsState()

    // Location-sharing flow owner: created at the composition root so both the
    // Settings screen (permission flow) and the Locations map (active cadence)
    // observe the same capability state. The FGS only ever starts when the
    // controller resolves to an eligible state.
    val locationSharingController = remember(settings, context) {
        LocationSharingController(
            scope = scope,
            sharingEnabled = settings.locationSharingEnabled,
            setSharingEnabledAction = { enabled ->
                settings.setLocationSharingEnabled(enabled)
            },
            permissionSnapshot = { LocationPermissionSnapshot.from(context) },
            backgroundPermissionRequired =
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R,
            startService = {
                startOrStopLocationSharing(context, settings, enabled = true, scope = scope)
            },
            stopService = {
                startOrStopLocationSharing(context, settings, enabled = false, scope = scope)
            },
        )
    }
    val locationSharingState by locationSharingController.state.collectAsState()
    val cameraDiscovery = remember(controller, gateway) {
        CameraDiscoveryController(
            connection = controller.connection,
            baseUrl = { baseUrl },
            scope = scope,
            discoverer = CameraDiscoveryController.productionDiscoverer(gateway),
        )
    }

    // App-lifetime playback diagnostics accumulator for the V1.5 Diagnostics
    // screen: the live view records finished player sessions into it.
    val playbackSnapshotStore = remember { PlaybackSnapshotStore() }
    val playbackSnapshot by playbackSnapshotStore.snapshot.collectAsState()

    val snapshotImageLoader = remember {
        SingletonImageLoader.setSafe { FrigateSnapshotImageLoader.create(context) }
        FrigateSnapshotImageLoader.create(context)
    }
    val thumbnailFactory = remember(controller, gateway) {
        CameraThumbnailModelFactory(
            connection = controller.connection,
            gateway = gateway,
        )
    }

    val navigation = remember { AppNavigation() }
    var selectedCamera by remember { mutableStateOf<Camera?>(null) }

    // Observe shared state outside the individual branches so the Live branch
    // can react to connection/discovery changes without calling .value inside
    // composition.
    val discoveryState by cameraDiscovery.state.collectAsState()
    val connection by controller.connection.collectAsState()
    val connectAttempt by controller.connectAttempt.collectAsState()
    val networkTick by controller.networkTick.collectAsState()

    // V1.3 enrollment routing: when a network transition falls back to Tailscale
    // and the node is not yet enrolled, the shared connection exposes
    // FrigateConnection.Failed(authRequired=true). The app must route to the
    // existing V1.1 SetupScreen so the administrator can complete enrollment
    // instead of leaving the family-facing Home/Live view in a failed state.
    var autoEnrollmentNavigated by remember { mutableStateOf(false) }
    val failedConnection = connection as? FrigateConnection.Failed
    val authRequired = failedConnection?.authRequired == true
    val enrollmentAuthUrl = failedConnection?.authUrl
    LaunchedEffect(authRequired, setupComplete) {
        if (shouldRouteToSetupForEnrollment(setupComplete, authRequired) &&
            !autoEnrollmentNavigated && navigation.current != Screen.Setup
        ) {
            autoEnrollmentNavigated = true
            navigation.navigateTo(
                Screen.Setup,
                reason = SetupRouteReasons.AUTH_REQUIRED,
            )
        }
        if (!authRequired) {
            autoEnrollmentNavigated = false
        }
    }

    // The first-run gate has no back stack entry, so the system back action
    // only pops an explicitly pushed screen (Setup reopened from Home).
    BackHandler(enabled = navigation.current != Screen.Home) {
        navigation.navigateBack()
    }

    HearthlaneTheme {
        if (shouldShowSetup(setupComplete)) {
            // First run: the setup gates the app. The shared controller is
            // intentionally not started here: no network-driven re-probe runs
            // while the administrator configures the server. Setup only reuses the
            // probe path (FrigateConnectionManager) through controller.testConnection.
            SetupScreen(
                controller = controller,
                settings = settings,
                title = stringResource(R.string.setup_title),
                onFinished = { navigation.resetTo(Screen.Home) },
                syncNickname = { nickname -> deviceNicknameSync.apply(nickname) },
                locationSharingController = locationSharingController,
            )
            return@HearthlaneTheme
        }

        when (val screen = navigation.current) {
            Screen.Home -> {
                // The network-change listener and discovery observer only run during
                // normal use; leaving Home (opening setup) stops them until Home is
                // shown again.
                DisposableEffect(controller) {
                    controller.start()
                    cameraDiscovery.start()
                    onDispose {
                        cameraDiscovery.stop()
                        controller.stop()
                    }
                }
                // Foreground resume: once the transport is re-established, observe the
                // camera grid for an Error the background tore down and recover
                // it exactly once. A Loaded/Loading grid is never touched.
                LaunchedEffect(resumeEpoch) {
                    if (resumeEpoch < 2) return@LaunchedEffect
                    controller.awaitConnectedAfterResume(resumeStartGen)
                    awaitErrorAndRecover(cameraDiscovery.state, { it is CameraDiscoveryState.Error }) {
                        cameraDiscovery.refresh()
                    }
                }
                HomeScreen(
                    controller = controller,
                    cameraDiscovery = cameraDiscovery,
                    thumbnailFactory = thumbnailFactory,
                    snapshotImageLoader = snapshotImageLoader,
                    baseUrl = baseUrl,
                    onOpenSettings = { navigation.navigateTo(Screen.Settings) },
                    onOpenLocations = { navigation.navigateTo(Screen.Locations) },
                    onCameraSelected = { camera ->
                        selectedCamera = camera
                        navigation.navigateTo(Screen.Live(camera.id))
                    },
                )
            }
            Screen.Locations -> {
                LocationsScreen(
                    relayController = relayController,
                    sharingActive = locationSharingState.status == LocationSharingStatus.Running,
                    onBack = { navigation.navigateBack() },
                    syncLocalNickname = {
                        deviceNicknameSync.apply(settings.deviceNickname.value)
                    },
                )
            }
            Screen.Setup -> {
                // Strong invariant: after onboarding, Setup is ONLY rendered for
                // an explicit user action (Settings -> server/reconfigure). Any
                // automatic navigation into Setup (authRequired or a stale
                // reason) is refused and the app returns to Home.
                if (!shouldRenderSetupScreen(setupComplete, navigation.setupReason)) {
                    LaunchedEffect(navigation.setupReason) {
                        navigation.resetTo(Screen.Home)
                    }
                    ConnectingPlaceholder(text = stringResource(R.string.home_connecting_body))
                } else {
                    SetupScreen(
                        controller = controller,
                        settings = settings,
                        title = stringResource(R.string.settings_title),
                        onFinished = { navigation.resetTo(Screen.Home) },
                        onBack = { navigation.navigateBack() },
                        enrollmentRequired = authRequired,
                        authUrl = enrollmentAuthUrl,
                        syncNickname = { nickname -> deviceNicknameSync.apply(nickname) },
                        locationSharingController = locationSharingController,
                    )
                }
            }
            Screen.Settings -> {
                val settingsController = remember(settings, controller) {
                    SettingsController(
                        baseDomain = settings.baseDomain,
                        connection = controller.connection,
                        connecting = controller.connecting,
                        autoPlayEventClips = settings.autoPlayEventClips,
                        locationSharingEnabled = settings.locationSharingEnabled,
                        appVersion = BuildConfig.VERSION_NAME,
                        appBuild = BuildConfig.VERSION_CODE.toString(),
                        resetRemoteAccessAction = { controller.resetTailscale() },
                        setAutoPlayEventClipsAction = { enabled ->
                            settings.setAutoPlayEventClips(enabled)
                        },
                        setLocationSharingEnabledAction = { enabled ->
                            settings.setLocationSharingEnabled(enabled)
                        },
                        scope = scope,
                    )
                }
                DisposableEffect(settingsController) {
                    onDispose { settingsController.release() }
                }
                SettingsScreen(
                    controller = settingsController,
                    locationSharingController = locationSharingController,
                    onOpenServerSettings = {
                        navigation.navigateTo(Screen.Setup, reason = SetupRouteReasons.USER_SERVER_SETTINGS)
                    },
                    onOpenDiagnostics = { navigation.navigateTo(Screen.Diagnostics) },
                    onReconfigureRemoteAccess = {
                        // Clears the remote-access identity, then opens the
                        // server screen so the interactive re-registration can
                        // be completed.
                        settingsController.resetRemoteAccess()
                        navigation.navigateTo(Screen.Setup, reason = SetupRouteReasons.REMOTE_RECONFIGURE_USER_ACTION)
                    },
                    onBack = { navigation.navigateBack() },
                )
            }
            Screen.Diagnostics -> {
                // The diagnostics screen shows live connectivity state, so the
                // network-change listener stays active while it is shown (same
                // lifecycle as Home and the live view).
                DisposableEffect(controller) {
                    controller.start()
                    onDispose { controller.stop() }
                }
                DiagnosticsScreen(
                    controller = controller,
                    relayController = relayController,
                    playbackSnapshot = playbackSnapshot,
                    appVersion = BuildConfig.VERSION_NAME,
                    nodeHostname = AppSettings.nodeHostname(nodeSuffix),
                    baseDomain = baseDomain,
                    sharingEnabled = settings.locationSharingEnabled,
                    deviceNickname = settings.deviceNickname,
                    onRetryConnection = { controller.connect(restartPlayback = false) },
                    onBack = { navigation.navigateBack() },
                )
            }
            is Screen.Live -> {
                // Keep the network-change listener alive while live video is shown.
                // When the Live screen became a separate destination (V1.3) the
                // listener was scoped to Home only, so turning on Wi-Fi while
                // watching would never re-probe: the connection stayed on
                // Tailscale even though the LAN had become reachable. The POC
                // embedded the live view in Home where the listener was always
                // active; this restores that behavior.
                DisposableEffect(controller) {
                    controller.start()
                    onDispose { controller.stop() }
                }
                val camera = resolveLiveCamera(screen.cameraId, selectedCamera, discoveryState)

                // The single decision point for the Live branch. It prevents the
                // enrollment-routing race: when auth is required we must NOT call
                // navigateBack() here; the global LaunchedEffect(authRequired) is
                // the only place that pushes Screen.Setup.
                when (liveDestination(connection, camera, setupComplete)) {
                    LiveDestination.WaitForEnrollment -> {
                        ConnectingPlaceholder(text = stringResource(R.string.home_connecting_body))
                    }
                    LiveDestination.RenderLive -> {
                        // liveDestination guarantees camera != null and connection is Connected here.
                        val conn = connection as FrigateConnection.Connected
                        val eventsApi = remember(conn.transport, controller.gateway) {
                            FrigateEventApi(bytesGetterFor(conn.transport, controller.gateway))
                        }
                        val eventsController = remember(eventsApi, screen.cameraId) {
                            RecentEventsController(
                                api = eventsApi,
                                cameraId = screen.cameraId,
                                baseUrl = { baseUrl },
                                limit = EVENTS_PAGE_SIZE,
                                scope = scope,
                            )
                        }
                        // Load the camera's recent events while the camera screen is shown.
                        LaunchedEffect(eventsController) { eventsController.loadInitial() }
                        // Foreground resume: observe the recent-events state for an Error that the
                        // background tore down (it can surface AFTER this
                        // effect's first evaluation) and recover it exactly
                        // once, only after the transport is re-established.
                        // Loaded events are preserved.
                        LaunchedEffect(resumeEpoch, eventsController) {
                            if (resumeEpoch < 2) return@LaunchedEffect
                            controller.awaitConnectedAfterResume(resumeStartGen)
                            awaitErrorAndRecover(eventsController.state, { it is RecentEventsState.Error }) {
                                eventsController.loadInitial()
                            }
                        }
                        LiveScreen(
                            cameraId = screen.cameraId,
                            displayName = camera!!.displayName,
                            baseUrl = baseUrl,
                            gateway = controller.gateway,
                            transport = conn.transport,
                            connectAttempt = connectAttempt,
                            networkTick = networkTick,
                            eventsController = eventsController,
                            thumbnailFactory = thumbnailFactory,
                            snapshotImageLoader = snapshotImageLoader,
                            playbackSnapshotStore = playbackSnapshotStore,
                            onBack = { navigation.navigateBack() },
                            onEventSelected = { eventId ->
                                navigation.navigateTo(Screen.EventDetail(screen.cameraId, eventId))
                            },
                        )
                    }
                    LiveDestination.GoBack -> {
                        // Camera not found or connection lost without pending enrollment.
                        navigation.navigateBack()
                    }
                }
            }
            is Screen.EventDetail -> {
                // Keep the network-change listener alive so the detail follows the
                // LOCAL -> Tailscale fallback without user interaction.
                DisposableEffect(controller) {
                    controller.start()
                    onDispose { controller.stop() }
                }
                val camera = resolveLiveCamera(screen.cameraId, selectedCamera, discoveryState)
                val conn = connection as? FrigateConnection.Connected
                when {
                    // A completed onboarding never routes to Setup for
                    // reauthentication; a failed connection falls back to Home
                    // and recovery happens through Settings -> Remote access.
                    camera != null && conn != null -> {
                        val streamGetter = remember(conn.transport, controller.gateway) {
                            streamGetterFor(conn.transport, controller.gateway)
                        }
                        val eventsApi = remember(conn.transport, controller.gateway) {
                            FrigateEventApi(bytesGetterFor(conn.transport, controller.gateway))
                        }
                        val detailController = remember(eventsApi, streamGetter, screen.eventId) {
                            EventDetailController(
                                context = context,
                                api = eventsApi,
                                eventId = screen.eventId,
                                baseUrl = { baseUrl },
                                getter = streamGetter,
                                clipUrl = { eventsApi.clipUrl(baseUrl, screen.eventId) },
                                autoPlayEventClips = { settings.autoPlayEventClips.value },
                                scope = scope,
                            )
                        }
                        // Release the embedded player (closing the streaming
                        // connection) when the screen leaves or the transport
                        // changes; the event is never replayed automatically.
                        DisposableEffect(detailController) {
                            onDispose { detailController.release() }
                        }
                        // Load the event when the screen is entered.
                        LaunchedEffect(detailController) { detailController.load() }
                        // Foreground resume: observe the event-detail state for an Error the
                        // background tore down — which can surface AFTER the
                        // resume began — and recover it exactly once, only once
                        // the transport is back. Loaded details are preserved.
                        LaunchedEffect(resumeEpoch, detailController) {
                            if (resumeEpoch < 2) return@LaunchedEffect
                            controller.awaitConnectedAfterResume(resumeStartGen)
                            awaitErrorAndRecover(detailController.state, { it is EventDetailState.Error }) {
                                detailController.load()
                            }
                        }
                        EventDetailScreen(
                            controller = detailController,
                            thumbnailFactory = thumbnailFactory,
                            snapshotImageLoader = snapshotImageLoader,
                            baseUrl = baseUrl,
                            cameraDisplayName = camera.displayName,
                            onBack = { navigation.navigateBack() },
                        )
                    }
                    else -> navigation.navigateBack()
                }
            }
        }
    }
}

/**
 * Resolves the [Camera] model for the Live screen from the state already
 * available in the composition root. The camera selected on Home wins; when it
 * is not available (for example after a process restart that loses the
 * selected-camera memory), the already-discovered camera list is consulted by
 * exact camera id. No new global discovery is triggered here.
 *
 * The resolved camera carries the Frigate-friendly [Camera.displayName], which
 * the Live screen uses as its title.
 */
internal fun resolveLiveCamera(
    cameraId: String,
    selectedCamera: Camera?,
    discoveryState: CameraDiscoveryState?,
): Camera? = selectedCamera
    ?: (discoveryState as? CameraDiscoveryState.Loaded)
        ?.cameras
        ?.find { it.id == cameraId }

/**
 * Pure decision for the Live branch. Extracted so the navigation race between
 * the branch's navigateBack() and the global enrollment routing can be unit
 * tested without a Compose environment.
 *
 * [setupComplete] separates onboarding from Tailscale reauthentication: with a
 * completed onboarding the Live branch never waits for an enrollment route
 * (there is none); it falls back to Home, where the connection state is shown
 * and recovery happens through Settings -> Remote access.
 */
internal sealed interface LiveDestination {
    data object RenderLive : LiveDestination
    data object WaitForEnrollment : LiveDestination
    data object GoBack : LiveDestination
}

internal fun liveDestination(
    connection: FrigateConnection?,
    camera: Camera?,
    setupComplete: Boolean,
): LiveDestination = when {
    !setupComplete && (connection as? FrigateConnection.Failed)?.authRequired == true ->
        LiveDestination.WaitForEnrollment
    setupComplete && (connection as? FrigateConnection.Failed)?.authRequired == true ->
        LiveDestination.GoBack
    camera != null && connection is FrigateConnection.Connected -> LiveDestination.RenderLive
    else -> LiveDestination.GoBack
}

/**
 * Whether the global enrollment routing should push [Screen.Setup].
 *
 * Screen.Setup is exclusively the onboarding gate. After [setupComplete] is
 * true it must never be opened automatically, regardless of Tailscale
 * authRequired / NeedsLogin / NeedsMachineAuth or any connectivity state: a
 * completed onboarding never navigates to Setup; recovery happens through
 * Settings -> Remote access.
 */
internal fun shouldRouteToSetupForEnrollment(setupComplete: Boolean, authRequired: Boolean): Boolean =
    !setupComplete && authRequired

/**
 * Whether AppRoot may render [Screen.Setup] for the current navigation reason.
 * With a completed onboarding, Setup is only legitimate for an explicit user
 * action (Settings -> server / remote access reconfigure); every automatic
 * reason (authRequired, unknown, stale) is refused so Setup never reappears on
 * a Tailscale-only startup.
 */
internal fun shouldRenderSetupScreen(setupComplete: Boolean, reason: String?): Boolean =
    !setupComplete ||
        reason == SetupRouteReasons.REMOTE_RECONFIGURE_USER_ACTION ||
        reason == SetupRouteReasons.USER_SERVER_SETTINGS

/**
 * Foreground-resume recovery primitive: waits for [state] to turn into an
 * Error and runs [recover] exactly once. It reacts to the CURRENT value first
 * and then to later emissions, so it is immune to the race where the
 * background-interrupted request lands in Error AFTER a one-shot check already
 * decided "nothing to recover". Loaded/Loading content never triggers
 * anything, and the observation ends after the single recovery, so a genuine
 * repeated failure is left to the user's Retry (no polling, no retry loop).
 *
 * The caller is expected to have already waited for the transport to be
 * re-established ([FrigateConnectionController.awaitConnectedAfterResume]) and
 * to be gated on a real foreground resume.
 */
internal suspend fun <T> awaitErrorAndRecover(
    state: StateFlow<T>,
    isError: (T) -> Boolean,
    recover: () -> Unit,
) {
    state.first { isError(it) }
    recover()
}

@Composable
private fun ConnectingPlaceholder(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** Initial page size for the recent-events list. */
private const val EVENTS_PAGE_SIZE = 20

/**
 * Applies the location-sharing opt-in from Settings: persists the preference
 * and starts or stops the publishing foreground service. The foreground start
 * is gated by [LocationFgsGate] so the service is only ever dispatched in an
 * eligible state (permissions + location switch); a structural failure (for
 * example a SecurityException) is reported back as false so the controller can
 * surface the Error state instead of crashing.
 */
private fun startOrStopLocationSharing(
    context: android.content.Context,
    settings: AppSettings,
    enabled: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
): Boolean = runCatching {
    if (enabled) {
        if (LocationFgsGate.evaluate(context).ready) {
            ContextCompat.startForegroundService(
                context,
                LocationForegroundService.intent(
                    context,
                    LocationForegroundService.BACKGROUND_INTERVAL_MS,
                ),
            )
            true
        } else {
            false
        }
    } else {
        context.stopService(
            LocationForegroundService.intent(
                context,
                LocationForegroundService.BACKGROUND_INTERVAL_MS,
            ),
        )
        true
    }
}.getOrDefault(false)
