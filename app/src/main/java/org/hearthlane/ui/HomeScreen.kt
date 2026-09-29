package org.hearthlane.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import org.hearthlane.R
import org.hearthlane.controller.CameraDiscoveryController
import org.hearthlane.controller.FrigateConnectionController
import org.hearthlane.core.frigate.Camera
import org.hearthlane.core.frigate.CameraDiscoveryState
import org.hearthlane.core.frigate.FrigateConnection
import org.hearthlane.core.frigate.TransportKind
import org.hearthlane.thumbnail.CameraThumbnailModelFactory

/**
 * App-bar title for Home. Shows a discrete environment badge after the product
 * name when the build is not prod (the [R.string.environment_badge] resource
 * is empty in prod, so the prod rendering is unchanged).
 */
@Composable
private fun HomeTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.home_title))
        val badge = stringResource(R.string.environment_badge)
        if (badge.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * V1.3 family-facing Home screen.
 *
 * The Home shows discovered cameras as a responsive grid of cards. Each card
 * displays the Frigate-friendly display name, a best-effort snapshot thumbnail,
 * and an unavailable state when the camera is not playable. Tapping a playable
 * card navigates to the live view for that camera id.
 *
 * Connection state is expressed through a compact TopAppBar indicator only
 * (connected / connecting / problem); no transport details are shown.
 * The full discovery state is handled: loading, loaded, empty, error,
 * and individual unavailable cameras.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    controller: FrigateConnectionController,
    cameraDiscovery: CameraDiscoveryController,
    thumbnailFactory: CameraThumbnailModelFactory,
    snapshotImageLoader: ImageLoader,
    baseUrl: String,
    onOpenSettings: () -> Unit,
    onOpenLocations: () -> Unit,
    onCameraSelected: (Camera) -> Unit,
) {
    val connection by controller.connection.collectAsState()
    val connecting by controller.connecting.collectAsState()
    val discoveryState by cameraDiscovery.state.collectAsState()
    val refreshKey by cameraDiscovery.refreshKey.collectAsState()

    // Connect on first entry. Playback is not started here; the live view does
    // that after the user selects a camera.
    LaunchedEffect(Unit) { controller.connect(restartPlayback = false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { HomeTitle() },
                actions = {
                    ConnectionStatusIndicator(
                        connection = connection,
                        connecting = connecting,
                    )
                    IconButton(onClick = cameraDiscovery::refresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh_button),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_button),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        CameraGridSection(
            connection = connection,
            discoveryState = discoveryState,
            refreshKey = refreshKey,
            baseUrl = baseUrl,
            thumbnailFactory = thumbnailFactory,
            snapshotImageLoader = snapshotImageLoader,
            onOpenLocations = onOpenLocations,
            onCameraSelected = onCameraSelected,
            onRetry = { controller.connect(restartPlayback = false) },
            onRefresh = cameraDiscovery::refresh,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * Compact connection status indicator for the TopAppBar.
 *
 * - Connected: check icon.
 * - Connecting: small progress indicator.
 * - Failed: error icon in error color.
 *
 * Does not rely on colour alone: each state has a distinct shape and
 * a contentDescription for screen readers.
 */
@Composable
private fun ConnectionStatusIndicator(
    connection: FrigateConnection?,
    connecting: Boolean,
) {
    when {
        connecting || connection == null -> {
            val description = stringResource(R.string.connection_status_connecting)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        connection is FrigateConnection.Connected -> {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.connection_status_connected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(48.dp)
                    .padding(14.dp),
            )
        }
        connection is FrigateConnection.Failed -> {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = stringResource(R.string.connection_status_problem),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(48.dp)
                    .padding(14.dp),
            )
        }
    }
}

@Composable
internal fun CameraGridSection(
    connection: FrigateConnection?,
    discoveryState: CameraDiscoveryState,
    refreshKey: Int,
    baseUrl: String,
    thumbnailFactory: CameraThumbnailModelFactory,
    snapshotImageLoader: ImageLoader,
    onOpenLocations: () -> Unit,
    onCameraSelected: (Camera) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        LocationsTile(
            onClick = onOpenLocations,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            text = stringResource(R.string.home_section_cameras),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            when (val conn = connection) {
                is FrigateConnection.Connected -> when (val state = discoveryState) {
                    CameraDiscoveryState.Loading -> LoadingState(stringResource(R.string.home_loading_cameras))
                    CameraDiscoveryState.Empty -> EmptyState(stringResource(R.string.home_no_cameras))
                    is CameraDiscoveryState.Error -> ErrorState(
                        message = stringResource(R.string.home_cameras_error),
                        onRetry = onRefresh,
                    )
                    is CameraDiscoveryState.Loaded -> CameraGrid(
                        cameras = state.cameras,
                        refreshKey = refreshKey,
                        baseUrl = baseUrl,
                        thumbnailFactory = thumbnailFactory,
                        snapshotImageLoader = snapshotImageLoader,
                        onCameraSelected = onCameraSelected,
                    )
                }
                is FrigateConnection.Failed -> ErrorState(
                    message = stringResource(R.string.home_connection_error),
                    onRetry = onRetry,
                )
                else -> LoadingState(stringResource(R.string.home_connecting_body))
            }
        }
    }
}

/**
 * Entry tile into the family Locations map. Always visible on Home so the
 * capability is reachable regardless of the camera connection state.
 */
@Composable
private fun LocationsTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)) {
                Text(
                    text = stringResource(R.string.locations_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.home_locations_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CameraGrid(
    cameras: List<Camera>,
    refreshKey: Int,
    baseUrl: String,
    thumbnailFactory: CameraThumbnailModelFactory,
    snapshotImageLoader: ImageLoader,
    onCameraSelected: (Camera) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 280.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = cameras,
            key = { it.id },
        ) { camera ->
            CameraCard(
                camera = camera,
                refreshKey = refreshKey,
                baseUrl = baseUrl,
                thumbnailFactory = thumbnailFactory,
                snapshotImageLoader = snapshotImageLoader,
                onClick = { if (camera.playable) onCameraSelected(camera) },
            )
        }
    }
}

@Composable
internal fun CameraCard(
    camera: Camera,
    refreshKey: Int,
    baseUrl: String,
    thumbnailFactory: CameraThumbnailModelFactory,
    snapshotImageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val model = thumbnailFactory.create(camera.id, baseUrl, refreshKey)
    val painter = rememberAsyncImagePainter(
        model = model,
        imageLoader = snapshotImageLoader,
    )
    val state by painter.state.collectAsState()

    Card(
        onClick = onClick,
        enabled = camera.playable,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Thumbnail or best-effort placeholder.
            when (val s = state) {
                is AsyncImagePainter.State.Success ->
                    androidx.compose.foundation.Image(
                        painter = s.painter,
                        contentDescription = camera.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    )
                else -> ThumbnailPlaceholder(camera.displayName)
            }

            if (!camera.playable) {
                UnavailableOverlay()
            }
        }

        Text(
            text = camera.displayName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ThumbnailPlaceholder(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_camera),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun UnavailableOverlay() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .padding(12.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                text = stringResource(R.string.camera_unavailable),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun LoadingState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            OutlinedButton(onClick = onRetry) {
                Text(stringResource(R.string.retry_button))
            }
        }
    }
}
