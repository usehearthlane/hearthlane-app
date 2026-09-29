package org.hearthlane.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import org.hearthlane.R
import org.hearthlane.controller.EventDetailController
import org.hearthlane.controller.EventDetailState
import org.hearthlane.core.frigate.Event
import org.hearthlane.core.playback.EventPlayer
import org.hearthlane.core.playback.PlaybackStatus
import org.hearthlane.core.playback.VideoDiag
import org.hearthlane.thumbnail.CameraThumbnailModelFactory

/**
 * Family-facing detail view for a single camera event.
 *
 * Reached from the camera screen's recent-events list. An event with a clip
 * starts playing immediately in the media area (there is no Play button and no
 * thumbnail is loaded for it); a playback failure shows a friendly message with
 * Retry in place. Events without a clip fall back to the snapshot. The event
 * metadata stays visible below the media area.
 *
 * The screen renders [EventDetailController.state] and
 * [EventDetailController.playbackState]; it never fetches the event, never
 * creates players and never reasons about the active transport.
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun EventDetailScreen(
    controller: EventDetailController,
    thumbnailFactory: CameraThumbnailModelFactory,
    snapshotImageLoader: ImageLoader,
    baseUrl: String,
    cameraDisplayName: String?,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val playbackStatus by controller.playbackState.collectAsState()

    // Clip playback is paused when the process leaves the foreground and
    // replayed on return, so a quick trip through Recents never leaves the
    // media area broken: the foreground grace window keeps tsnet up, and the
    // replay over the still-running node is fast. A real background tears the
    // node down; the replay then surfaces a retryable playback error instead
    // of a dead frozen frame (the app never keeps playback running invisibly
    // in the background).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        var wasActive = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    VideoDiag.player("EVENT", "lifecycle ON_STOP")
                    wasActive = controller.playbackState.value is PlaybackStatus.Loading ||
                        controller.playbackState.value is PlaybackStatus.Playing
                    controller.stop()
                }
                Lifecycle.Event.ON_RESUME -> {
                    val status = controller.playbackState.value
                    if (wasActive || status is PlaybackStatus.Error) {
                        wasActive = false
                        VideoDiag.player("EVENT", "resume/retry playback")
                        controller.play()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Presentation-only fullscreen state for the player. The same EventPlayer
    // instance is re-attached to the fullscreen surface; nothing about the
    // playback is restarted or replaced.
    val fullscreenState = remember { FullscreenState() }
    val isFullscreen by fullscreenState.isFullscreen.collectAsState()

    val activity = LocalActivity.current

    // First Back exits fullscreen; only the next one navigates away.
    BackHandler(enabled = isFullscreen) {
        fullscreenState.handleBack()
    }

    // System bars + orientation for fullscreen (same modern APIs and restore
    // contract as the live screen). The activity declares configChanges, so the
    // rotation never recreates the player.
    DisposableEffect(isFullscreen) {
        val window = activity?.window
        val bars = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (isFullscreen) {
            bars?.hide(WindowInsetsCompat.Type.systemBars())
            bars?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        } else {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        }
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        }
    }

    if (isFullscreen) {
        EventDetailFullscreen(
            player = controller.player,
            onExitFullscreen = fullscreenState::exit,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.event_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_button),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val current = state) {
                EventDetailState.Loading ->
                    CenteredMessage(stringResource(R.string.event_detail_loading), progress = true)
                EventDetailState.NotFound ->
                    CenteredMessage(stringResource(R.string.event_detail_not_found))
                is EventDetailState.Error ->
                    CenteredMessage(
                        text = stringResource(R.string.event_detail_error),
                        actionLabel = stringResource(R.string.retry_button),
                        onAction = controller::load,
                    )
                is EventDetailState.Loaded ->
                    EventDetailContent(
                        event = current.event,
                        playbackStatus = playbackStatus,
                        player = controller.player,
                        thumbnailFactory = thumbnailFactory,
                        snapshotImageLoader = snapshotImageLoader,
                        baseUrl = baseUrl,
                        cameraDisplayName = cameraDisplayName,
                        onPlay = controller::play,
                        onToggleFullscreen = fullscreenState::enter,
                    )
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
@Composable
private fun EventDetailContent(
    event: Event,
    playbackStatus: PlaybackStatus,
    player: EventPlayer,
    thumbnailFactory: CameraThumbnailModelFactory,
    snapshotImageLoader: ImageLoader,
    baseUrl: String,
    cameraDisplayName: String?,
    onPlay: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EventMediaArea(
            event = event,
            playbackStatus = playbackStatus,
            player = player,
            thumbnailFactory = thumbnailFactory,
            snapshotImageLoader = snapshotImageLoader,
            baseUrl = baseUrl,
            onPlay = onPlay,
            onToggleFullscreen = onToggleFullscreen,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = localizedFrigateObjectLabel(event.label),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        cameraDisplayName?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = formattedEventDateTime(event.startTime),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = event.endTime?.let { EventFormat.duration(it - event.startTime) }
                ?: stringResource(R.string.event_in_progress),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (event.zones.isNotEmpty()) {
            Text(
                text = event.zones.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The media area of the event detail. A clip event plays automatically in the
 * [PlayerView] (no thumbnail); playback failures show a friendly message with
 * Retry in place. Events without a clip fall back to the snapshot.
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
@Composable
private fun EventMediaArea(
    event: Event,
    playbackStatus: PlaybackStatus,
    player: EventPlayer,
    thumbnailFactory: CameraThumbnailModelFactory,
    snapshotImageLoader: ImageLoader,
    baseUrl: String,
    onPlay: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    when {
        !event.hasClip ->
            EventSnapshot(event, thumbnailFactory, snapshotImageLoader, baseUrl)

        playbackStatus is PlaybackStatus.Error ->
            CenteredMessage(
                text = eventPlaybackErrorMessage(playbackStatus),
                actionLabel = stringResource(R.string.retry_button),
                onAction = onPlay,
            )

        // Playback not started yet (auto-play disabled): show the snapshot as a
        // tappable content affordance that starts playback on tap.
        playbackStatus == PlaybackStatus.Idle ->
            EventSnapshot(
                event = event,
                thumbnailFactory = thumbnailFactory,
                snapshotImageLoader = snapshotImageLoader,
                baseUrl = baseUrl,
                playable = true,
                onPlay = onPlay,
            )

        else ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .testTag("event_player_view"),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            setPlayer(player.player)
                        }
                    },
                    update = { view -> view.player = player.player },
                    modifier = Modifier.fillMaxSize(),
                )
                // The fullscreen entry is only shown while the player exists.
                FullscreenToggle(
                    onClick = onToggleFullscreen,
                    isFullscreen = false,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
    }
}

/**
 * Fullscreen presentation of the event clip. The same [EventPlayer] instance is
 * bound to a fresh [PlayerView] filling the screen; playback position, play/
 * pause and buffering state are preserved because nothing is re-created. The
 * Media3 controller stays available and the exit toggle is the same path as
 * Back.
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
@Composable
private fun EventDetailFullscreen(
    player: EventPlayer,
    onExitFullscreen: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setPlayer(player.player)
                }
            },
            update = { view -> view.player = player.player },
            modifier = Modifier.fillMaxSize(),
        )
        FullscreenToggle(
            onClick = onExitFullscreen,
            isFullscreen = true,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )
    }
}

@Composable
private fun eventPlaybackErrorMessage(status: PlaybackStatus.Error): String = when (status.statusCode) {
    404 -> stringResource(R.string.event_playback_not_found)
    null -> stringResource(R.string.event_playback_network_error)
    else -> stringResource(R.string.event_playback_server_error)
}

@Composable
private fun EventSnapshot(
    event: Event,
    thumbnailFactory: CameraThumbnailModelFactory,
    snapshotImageLoader: ImageLoader,
    baseUrl: String,
    playable: Boolean = false,
    onPlay: () -> Unit = {},
) {
    val playLabel = stringResource(R.string.event_detail_play_clip)
    val boxModifier = if (playable) {
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clickable(onClick = onPlay)
            .semantics { contentDescription = playLabel }
    } else {
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    }
    Box(
        modifier = boxModifier,
        contentAlignment = Alignment.Center,
    ) {
        if (event.hasSnapshot) {
            val model = thumbnailFactory.eventSnapshot(event.id, baseUrl)
            val description = if (playable) null else localizedFrigateObjectLabel(event.label)
            SubcomposeAsyncImage(
                model = model,
                imageLoader = snapshotImageLoader,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { SnapshotPlaceholder() },
                error = { SnapshotPlaceholder() },
                success = {
                    Image(
                        painter = painter,
                        contentDescription = description,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        } else {
            SnapshotPlaceholder()
        }
        if (playable) {
            PlayOverlay()
        }
    }
}

@Composable
private fun PlayOverlay() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun SnapshotPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_camera),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
