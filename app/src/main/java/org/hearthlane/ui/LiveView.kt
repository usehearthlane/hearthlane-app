package org.hearthlane.ui

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import org.hearthlane.R
import org.hearthlane.controller.PlaybackSnapshotStore
import org.hearthlane.core.connectivity.HttpBytesGetter
import org.hearthlane.core.connectivity.HttpStreamGetter
import org.hearthlane.core.frigate.CameraStreams
import org.hearthlane.core.frigate.Go2RtcStreams
import org.hearthlane.core.frigate.LiveQualityLevel
import org.hearthlane.core.frigate.LiveQualityMode
import org.hearthlane.core.frigate.TransportKind
import org.hearthlane.core.connectivity.TsnetGateway
import org.hearthlane.core.frigate.bytesGetterFor
import org.hearthlane.core.frigate.cameraStreamSelection
import org.hearthlane.core.frigate.initialQualityLevel
import org.hearthlane.core.frigate.streamGetterFor
import org.hearthlane.core.frigate.streamNameForQualityLevel
import org.hearthlane.core.playback.LiveQualityAction
import org.hearthlane.core.playback.LiveQualityDowngradeReason
import org.hearthlane.core.playback.LiveQualityPolicy
import org.hearthlane.core.playback.LiveQualityState
import org.hearthlane.core.playback.LiveStreamPlayer
import org.hearthlane.core.playback.PlaybackStatus
import org.hearthlane.core.playback.VideoDiag
import org.hearthlane.core.playback.transitionOnPlaying
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "Hearthlane"

/**
 * Family-facing live view for a single selected camera. Receives the [cameraId]
 * chosen on Home and plays the go2rtc stream whose name equals that id (the
 * proven camera.id == stream name relation), never a "first stream". Every
 * media request goes through
 * [org.hearthlane.core.connectivity.HttpBytesGetter] selected by [transport],
 * so the Tailscale path can never touch the Android network.
 *
 * When playing, only the video is shown — no status labels or stop controls.
 * A fullscreen toggle overlay is available on the player in all modes.
 *
 * @param fullscreen when true the player fills the entire screen with no
 *   chrome. The fullscreen toggle overlay allows exiting back to normal mode.
 * @param onToggleFullscreen called when the user taps the fullscreen toggle.
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
@Composable
internal fun LiveView(
    cameraId: String,
    baseUrl: String,
    gateway: TsnetGateway,
    transport: TransportKind,
    connectAttempt: Int,
    networkTick: Int,
    modifier: Modifier = Modifier,
    playbackSnapshotStore: PlaybackSnapshotStore? = null,
    testGetter: HttpBytesGetter? = null,
    testStreamGetter: HttpStreamGetter? = null,
    fullscreen: Boolean = false,
    qualityMode: LiveQualityMode = LiveQualityMode.AUTO,
    onToggleFullscreen: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Bytes getter: go2rtc stream discovery and master-playlist resolution
    // (small, bounded JSON/playlist responses). Stream getter: the HLS player,
    // which now consumes playlists/init/segments incrementally.
    val bytesGetter = testGetter ?: remember(transport, gateway) { bytesGetterFor(transport, gateway) }
    val streamGetter = testStreamGetter ?: remember(transport, gateway) { streamGetterFor(transport, gateway) }
    val remote = transport == TransportKind.TAILSCALE

    // AUTO quality machine (remote only): a fresh policy per logical session
    // (transport or mode change), so a reopen/transport change re-allows a
    // trial while an internal HLS recovery never mints new trials.
    val policy = remember(transport, qualityMode) {
        Log.i(TAG, "quality policy created mode=$qualityMode remote=$remote")
        LiveQualityPolicy(nowMs = SystemClock::elapsedRealtime)
    }
    var cameraStreams by remember(transport) { mutableStateOf<CameraStreams?>(null) }
    var qualityActive by remember { mutableStateOf(false) }
    var startupInProgress by remember(transport) { mutableStateOf(false) }
    var loadingStartedAtMs by remember { mutableStateOf(-1L) }

    // Holder set below, once discoverAndPlay is defined: the player detects a
    // dead go2rtc session (MEDIA_PLAYLIST 404/410) and asks us to re-resolve.
    var onSessionDeadAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Holder for the quality action handler, set after its declaration below:
    // the player callbacks (created earlier) route through it to break the
    // player <-> handler declaration cycle.
    var qualityActionHandler: ((LiveQualityAction) -> Unit)? = null
    val player = remember(transport, gateway) {
        Log.i(TAG, "player created for transport=$transport")
        LiveStreamPlayer(
            context.applicationContext,
            streamGetter,
            onSessionDead = { onSessionDeadAction?.invoke() },
            liveTargetOffsetMs = if (remote) LIVE_TARGET_OFFSET_MS else null,
            diagTransport = transport.name,
            onMediaSegmentLoaded = { bytes, durationMs ->
                if (qualityActive) qualityActionHandler?.invoke(policy.onMediaSegmentSample(bytes, durationMs))
            },
            onPlaylistPolled = { gapMs ->
                if (qualityActive) qualityActionHandler?.invoke(policy.onPlaylistPolled(gapMs))
            },
            onReadStall = { if (qualityActive) policy.onReadStall() },
            onLoadError = { if (qualityActive) qualityActionHandler?.invoke(policy.onLoadError()) },
        )
    }
    val scope = rememberCoroutineScope()

    var streamUrl by remember { mutableStateOf<String?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var playbackStatus by remember { mutableStateOf<PlaybackStatus>(PlaybackStatus.Idle) }
    val metrics by player.metrics.collectAsState()
    var resumeTick by remember { mutableStateOf(0) }
    var streamResolved by remember { mutableStateOf(false) }
    var resolvedStreamName: String? by remember { mutableStateOf(null) }
    var recoveryCount by remember { mutableStateOf(0) }
    var autoRecovery by remember { mutableStateOf(0) }
    var playingSince by remember { mutableStateOf<Long?>(null) }
    var recoveryExhausted by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        onDispose {
            Log.i(TAG, "player released (live view left or transport switched)")
            playbackSnapshotStore?.record(player.state.value, player.metrics.value, recoveryCount)
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    VideoDiag.player("LIVE", "lifecycle ON_STOP")
                    player.stop()
                }
                Lifecycle.Event.ON_RESUME -> {
                    VideoDiag.player("LIVE", "lifecycle ON_RESUME")
                    resumeTick++
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var discoveryJob: Job? = null
    var discoveryRetryJob: Job? = null
    var qualitySwitchJob: Job? = null
    var qualitySwitchCount = 0
    var discoverCount by remember { mutableStateOf(0) }

    /**
     * The quality level the live view should be on right now: the AUTO
     * policy's state when armed (AUTO + remote + substream present), the
     * mode's initial level otherwise. The discovery and quality switches both
     * resolve streams through this, so an HLS session recovery re-plays the
     * CURRENT level instead of resetting it.
     */
    val currentQualityLevel: () -> LiveQualityLevel = {
        if (!qualityActive) initialQualityLevel(qualityMode, remote) else when (policy.state) {
            LiveQualityState.SUB -> LiveQualityLevel.SUB
            LiveQualityState.TRIAL_MAIN, LiveQualityState.MAIN -> LiveQualityLevel.MAIN
        }
    }

    // Holder set after switchQuality is declared below: startPlayback's
    // corrective pass routes through it (declaration-order breaker).
    var correctiveSwitch: ((LiveQualityLevel) -> Unit)? = null
    // Holder for the discovery retry (the retry lambda cannot reference the
    // discoverAndPlay val inside its own initializer).
    var retryDiscover: (() -> Unit)? = null

    /** Plays [url] (a fresh go2rtc session) and notifies the quality policy.
     *  A corrective switch re-aligns the playback if the policy level moved
     *  while the URL was being resolved. */
    val startPlayback: (String, LiveQualityLevel) -> Unit = { url, level ->
        startupInProgress = true
        loadingStartedAtMs = -1
        policy.onSessionPreparing()
        player.play(url)
        if (qualityActive && currentQualityLevel() != level) {
            Log.i(
                TAG,
                "LiveQuality mode=AUTO camera=$cameraId corrective-switch fromLevel=$level " +
                    "toLevel=${currentQualityLevel()}",
            )
            correctiveSwitch?.invoke(currentQualityLevel())
        }
    }

    val discoverAndPlay: () -> Unit = {
        discoveryJob?.cancel()
        discoveryRetryJob?.cancel()
        discoverCount++
        val myCount = discoverCount
        VideoDiag.player("LIVE", "discover begin generation=$myCount transport=$transport")
        discoveryJob = scope.launch {
            try {
                unavailable = false
                val streams = Go2RtcStreams(bytesGetter)
                val available = streams.streamNames(baseUrl, STREAMS_TIMEOUT_MS)
                val selection = cameraStreamSelection(cameraId, available)
                cameraStreams = selection
                val wasActive = qualityActive
                qualityActive = qualityMode == LiveQualityMode.AUTO && remote && selection?.sub != null
                if (!wasActive && qualityActive) {
                    Log.i(TAG, "LiveQuality mode=AUTO camera=$cameraId state=SUB")
                }
                val level = currentQualityLevel()
                val name = selection?.let { streamNameForQualityLevel(cameraId, available, level) }
                if (name == null) {
                    unavailable = true
                } else {
                    val reason = when {
                        !remote -> "local_main"
                        name == selection?.sub -> "remote_substream"
                        level == LiveQualityLevel.SUB -> "substream_unavailable"
                        else -> "remote_main"
                    }
                    Log.i(
                        TAG,
                        "LiveStreamSelection camera=$cameraId transport=$transport selected=$name reason=$reason",
                    )
                    val url = streams.resolveMediaPlaylistUrl(baseUrl, name, STREAMS_TIMEOUT_MS)
                    // A newer discover may have superseded this one after the URL
                    // resolved: never play from a stale generation (would create a
                    // duplicate MediaSource/prepare and a second go2rtc session).
                    if (isStaleDiscover(currentCount = discoverCount, myCount = myCount)) return@launch
                    VideoDiag.player("LIVE", "discover complete generation=$myCount")
                    Log.i(TAG, "live stream resolved: camera=$cameraId stream=$name via $transport")
                    streamResolved = true
                    resolvedStreamName = name
                    streamUrl = url
                    recoveryExhausted = false
                    startPlayback(url, level)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                VideoDiag.player("LIVE", "discover failed generation=$myCount")
                streamResolved = false
                resolvedStreamName = null
                Log.e(TAG, "stream discovery failed", e)
                val stable = playingSince?.let { SystemClock.elapsedRealtime() - it }
                if (stable != null && stable > STABLE_PLAY_MS) autoRecovery = 0
                val canRecover = remote || autoRecovery < MAX_AUTO_RECOVERY
                if (canRecover) {
                    val attempt = autoRecovery + 1
                    if (!remote) autoRecovery++
                    recoveryCount++
                    discoveryRetryJob?.cancel()
                    discoveryRetryJob = scope.launch {
                        Log.w(
                            TAG,
                            "stream discovery failed; retrying in ${DISCOVERY_RETRY_DELAY_MS}ms " +
                                "(${if (remote) "unbounded TAILSCALE recovery" else "attempt $attempt/$MAX_AUTO_RECOVERY"})",
                        )
                        delay(DISCOVERY_RETRY_DELAY_MS)
                        retryDiscover?.invoke()
                    }
                } else {
                    recoveryExhausted = true
                }
            }
        }
    }

    /** Deliberate quality switch: resolves a fresh session for [level] and
     *  re-prepares the SAME player. Never counts as a recovery, never touches
     *  the recovery counters, and skips when a discovery superseded it. */
    val switchQuality: (LiveQualityLevel) -> Unit = { level ->
        qualitySwitchJob?.cancel()
        qualitySwitchCount++
        val mySwitch = qualitySwitchCount
        val myDiscover = discoverCount
        VideoDiag.player("LIVE", "quality switch begin level=$level")
        qualitySwitchJob = scope.launch {
            try {
                val streams = Go2RtcStreams(bytesGetter)
                val available = streams.streamNames(baseUrl, STREAMS_TIMEOUT_MS)
                val name = streamNameForQualityLevel(cameraId, available, level)
                if (name == null) {
                    unavailable = true
                    return@launch
                }
                val url = streams.resolveMediaPlaylistUrl(baseUrl, name, STREAMS_TIMEOUT_MS)
                if (discoverCount != myDiscover || qualitySwitchCount != mySwitch) return@launch
                VideoDiag.player("LIVE", "quality switch complete level=$level stream=$name")
                streamResolved = true
                resolvedStreamName = name
                streamUrl = url
                startPlayback(url, level)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                VideoDiag.player("LIVE", "quality switch failed level=$level")
                Log.e(TAG, "quality switch failed; re-discovering", e)
                discoverAndPlay()
            }
        }
    }

    /** Executes a policy action: switches streams or commits a state change. */
    val handleQualityAction: (LiveQualityAction) -> Unit = { action ->
        val mainName = cameraStreams?.main
        val subName = cameraStreams?.sub
        when (action) {
            LiveQualityAction.None -> Unit
            LiveQualityAction.TrialReady -> {
                // Never compete with the early session recovery: the recovery
                // re-resolves the current level; the trial waits for it.
                if (!player.isRecoveryInFlight) {
                    policy.onTrialStarted()
                    Log.i(
                        TAG,
                        "LiveQuality mode=AUTO camera=$cameraId from=$subName to=$mainName reason=trial-started",
                    )
                    switchQuality(LiveQualityLevel.MAIN)
                }
            }
            LiveQualityAction.TrialPassed -> {
                policy.onTrialPassed()
                Log.i(
                    TAG,
                    "LiveQuality mode=AUTO camera=$cameraId state=MAIN reason=trial-passed",
                )
            }
            is LiveQualityAction.Downgrade -> {
                Log.i(
                    TAG,
                    "LiveQuality mode=AUTO camera=$cameraId from=${resolvedStreamName} to=$subName " +
                        "reason=${action.reason.name.lowercase()}",
                )
                if (policy.trialFailed) {
                    Log.i(
                        TAG,
                        "LiveQuality mode=AUTO camera=$cameraId state=SUB reason=trial-failed " +
                            "attempts_exhausted=${policy.attemptsExhausted}",
                    )
                }
                // A session-dead downgrade is followed by the early-session
                // recovery, which re-plays the (already downgraded) SUB level;
                // the other reasons switch right now.
                if (action.reason != LiveQualityDowngradeReason.SESSION_DEAD) {
                    switchQuality(LiveQualityLevel.SUB)
                }
            }
        }
    }
    qualityActionHandler = handleQualityAction
    correctiveSwitch = switchQuality
    retryDiscover = discoverAndPlay

    // Wire the player's early-session-recovery signal to the existing re-discover
    // flow (same ExoPlayer is re-prepared with a fresh media source). The policy
    // downgrades first: the recovery then re-plays the new SUB level.
    onSessionDeadAction = {
        if (qualityActive) handleQualityAction(policy.onSessionDeadCandidate())
        discoverAndPlay()
    }

    var lastTransport by remember { mutableStateOf<TransportKind?>(null) }
    var lastResumeTick by remember { mutableStateOf(0) }
    var lastConnectAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(transport, gateway, resumeTick, connectAttempt, networkTick) {
        val transportChanged = lastTransport != transport
        val resumeChanged = lastResumeTick != resumeTick
        val connectChanged = lastConnectAttempt != connectAttempt
        lastTransport = transport
        lastResumeTick = resumeTick
        lastConnectAttempt = connectAttempt
        val dead = playbackStatus is PlaybackStatus.Error ||
            player.player.playbackState == Player.STATE_IDLE
        if (transportChanged || resumeChanged || connectChanged || dead) {
            recoveryExhausted = false
            discoverAndPlay()
        }
    }

    LaunchedEffect(player) {
        player.state.collectLatest { status ->
            playbackStatus = status
            when (status) {
                is PlaybackStatus.Playing -> {
                    playingSince = SystemClock.elapsedRealtime()
                    recoveryExhausted = false
                    // A startup completing MUST clear the loading marker: the
                    // periodic stuck-buffering guard would otherwise treat the
                    // (long) startup loading as a rebuffer and fail the trial.
                    val transition = transitionOnPlaying(
                        startupInProgress = startupInProgress,
                        loadingStartedAtMs = loadingStartedAtMs,
                        nowMs = SystemClock.elapsedRealtime(),
                    )
                    if (transition.clearLoading) loadingStartedAtMs = -1
                    if (transition.startupCompleted) {
                        startupInProgress = false
                        if (qualityActive) policy.onStartupComplete()
                    } else {
                        val rebufferMs = transition.rebufferDurationMs
                        if (rebufferMs != null && qualityActive) {
                            handleQualityAction(policy.onBufferingEnded(rebufferMs))
                        }
                    }
                }
                is PlaybackStatus.Loading -> {
                    if (loadingStartedAtMs < 0) loadingStartedAtMs = SystemClock.elapsedRealtime()
                }
                is PlaybackStatus.Error -> {
                    if (qualityActive) handleQualityAction(policy.onPlayerError())
                }
                else -> Unit
            }
        }
    }

    // 1 Hz quality loop: buffer samples, stuck-buffering detection and the
    // policy's periodic checks (health window, trial window).
    LaunchedEffect(transport, qualityMode, player) {
        while (true) {
            delay(QUALITY_TICK_MS)
            if (!qualityActive) continue
            handleQualityAction(policy.onBufferSample(player.player.totalBufferedDuration))
            if (loadingStartedAtMs >= 0 &&
                SystemClock.elapsedRealtime() - loadingStartedAtMs >= LiveQualityPolicy.BUFFERING_DOWNGRADE_MS
            ) {
                handleQualityAction(policy.onBufferingTooLong())
            }
            handleQualityAction(policy.tick())
        }
    }

    LaunchedEffect(playbackStatus, streamUrl, transport) {
        when (val status = playbackStatus) {
            is PlaybackStatus.Playing -> {
                playingSince = SystemClock.elapsedRealtime()
                recoveryExhausted = false
            }
            is PlaybackStatus.Error ->
                if (streamUrl != null) {
                    val stable = playingSince?.let { SystemClock.elapsedRealtime() - it }
                    if (stable != null && stable > STABLE_PLAY_MS) autoRecovery = 0
                    val canRecover = transport == TransportKind.TAILSCALE ||
                        autoRecovery < MAX_AUTO_RECOVERY
                    if (canRecover) {
                        val attempt = autoRecovery + 1
                        if (transport != TransportKind.TAILSCALE) autoRecovery++
                        recoveryCount++
                        Log.w(
                            TAG,
                            "live HLS playback failed (HTTP ${status.statusCode ?: "n/a"}): ${status.message}; " +
                                "auto-recovering with a fresh go2rtc session " +
                                "(${if (transport == TransportKind.TAILSCALE) "unbounded TAILSCALE recovery" else "attempt $attempt/$MAX_AUTO_RECOVERY"})",
                        )
                        discoverAndPlay()
                    } else {
                        recoveryExhausted = true
                    }
                }
            else -> Unit
        }
    }

    val onRetry: () -> Unit = {
        recoveryExhausted = false
        autoRecovery = 0
        discoverAndPlay()
    }

    if (fullscreen) {
        LiveViewFullscreen(
            player = player,
            streamUrl = streamUrl,
            unavailable = unavailable,
            recoveryExhausted = recoveryExhausted,
            onRetry = onRetry,
            onToggleFullscreen = onToggleFullscreen,
        )
    } else {
        LiveViewPortrait(
            player = player,
            streamUrl = streamUrl,
            unavailable = unavailable,
            recoveryExhausted = recoveryExhausted,
            onRetry = onRetry,
            onToggleFullscreen = onToggleFullscreen,
            modifier = modifier,
        )
    }
}

@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
@Composable
private fun LiveViewPortrait(
    player: LiveStreamPlayer,
    streamUrl: String?,
    unavailable: Boolean,
    recoveryExhausted: Boolean,
    onRetry: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var videoAspect by remember { mutableStateOf<Float?>(null) }
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        when {
            recoveryExhausted && !unavailable -> {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.live_view_connection_lost),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.home_try_again))
                }
            }
            streamUrl != null -> {
                Box {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                setAspectRatioListener { target, _, _ -> videoAspect = target }
                                setPlayer(player.player)
                            }
                        },
                        update = { view -> view.player = player.player },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(videoAspect ?: 16f / 9f),
                    )
                    FullscreenToggle(
                        onClick = onToggleFullscreen,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    )
                }
            }
            unavailable -> {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.live_view_camera_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.home_try_again))
                }
            }
            else -> Text(
                text = stringResource(R.string.live_view_discovering),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
@Composable
private fun LiveViewFullscreen(
    player: LiveStreamPlayer,
    streamUrl: String?,
    unavailable: Boolean,
    recoveryExhausted: Boolean,
    onRetry: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            recoveryExhausted && !unavailable -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.live_view_connection_lost),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.home_try_again))
                    }
                }
            }
            streamUrl != null -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            setPlayer(player.player)
                        }
                    },
                    update = { view -> view.player = player.player },
                    modifier = Modifier.fillMaxSize(),
                )
                FullscreenToggle(
                    onClick = onToggleFullscreen,
                    isFullscreen = true,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
            unavailable -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.live_view_camera_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onRetry) {
                        Text(stringResource(R.string.home_try_again))
                    }
                }
            }
            else -> Text(
                text = stringResource(R.string.live_view_discovering),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
internal fun FullscreenToggle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.4f),
            contentColor = Color.White,
        ),
    ) {
        Icon(
            imageVector = if (isFullscreen) {
                Icons.Filled.FullscreenExit
            } else {
                Icons.Filled.Fullscreen
            },
            contentDescription = if (isFullscreen) {
                stringResource(R.string.exit_fullscreen)
            } else {
                stringResource(R.string.enter_fullscreen)
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

private const val STREAMS_TIMEOUT_MS = 10_000L
private const val MAX_AUTO_RECOVERY = 2
private const val STABLE_PLAY_MS = 10_000L
private const val DISCOVERY_RETRY_DELAY_MS = 1_500L

/** 1 Hz cadence of the AUTO quality loop (buffer samples + policy ticks). */
private const val QUALITY_TICK_MS = 1_000L

/**
 * Conservative live-edge margin applied ONLY on the remote (Tailscale) path:
 * the player starts this far behind the live edge, giving real jitter margin
 * (observed stalls ~0.5-0.9 s consume the ~0.2-2 s real window) without large
 * latency. LOCAL stays on the default (minimal) offset.
 */
internal const val LIVE_TARGET_OFFSET_MS = 2_500L

/**
 * True when a discover attempt that resolved a URL was superseded by a newer
 * [discoverAndPlay] (the generation counter advanced): the stale result must
 * never call [LiveStreamPlayer.play], which would duplicate the MediaSource and
 * create a second go2rtc session. Pure so it is unit-testable.
 */
internal fun isStaleDiscover(currentCount: Int, myCount: Int): Boolean = currentCount != myCount
