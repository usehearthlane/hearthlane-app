package org.hearthlane.ui.locations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import org.hearthlane.R
import org.hearthlane.controller.RelayConnectionController
import org.hearthlane.core.relay.LocationStatus
import org.hearthlane.core.relay.RelayConnection
import org.hearthlane.location.DeviceMarker
import org.hearthlane.location.LocationForegroundService
import org.hearthlane.location.LocationsQuery
import org.hearthlane.location.LocationsUiController
import org.hearthlane.location.MapActiveQueryController
import org.hearthlane.location.PublisherMode
import org.hearthlane.location.MapQueryStatus
import kotlinx.coroutines.launch

/**
 * The family Locations screen: a live map of devices with a published
 * location, kept fresh while the screen is open.
 *
 * Behavior (the "map-active" contract):
 * - opening the screen probes the relay and, while the relay answers, queries
 *   immediately and repolls every [MAP_POLL_INTERVAL_MS];
 * - closing the screen stops the loop (the last valid result stays in memory);
 * - while the screen is open the share service is bumped to the active cadence
 *   (and restored to the background cadence on close) when sharing is enabled;
 * - distinct states: never queried yet / no location shared / server
 *   unreachable / query error with the map intact.
 *
 * Layout: the device selector is rendered in an elevated layer ABOVE the map
 * (a top overlay), and the map is clipped to its bounds, so the selector is
 * never covered by map content, markers or controls during zoom/pan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    relayController: RelayConnectionController,
    sharingActive: Boolean,
    onBack: () -> Unit,
    syncLocalNickname: suspend () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    val queryController = remember(relayController) {
        MapActiveQueryController(
            query = { LocationsQuery(client = { relayController.client() }).run() },
            intervalMs = { MAP_POLL_INTERVAL_MS },
            scope = scope,
        )
    }

    // Selection state shared by the selector and the map markers.
    val selectionController = remember { LocationsUiController(scope) }

    val result by queryController.state.collectAsState()
    val selectedDeviceId by selectionController.selectedDeviceId.collectAsState()
    val detailsVisible by selectionController.detailsVisible.collectAsState()
    val focusRequest by selectionController.focusRequests.collectAsState()
    val connection by relayController.connection.collectAsState()
    val connecting by relayController.connecting.collectAsState()

    // Back while the details panel is open closes only the panel (the selection
    // stays); Back with the panel closed navigates normally.
    BackHandler(enabled = detailsVisible) {
        selectionController.hideDetails()
    }

    // Probe on entry; a retry from the error state probes again.
    LaunchedEffect(Unit) { relayController.probe() }

    val connected = connection is RelayConnection.Connected
    // Poll only while the relay is reachable; stop on exit or disconnect.
    DisposableEffect(connected) {
        if (connected) queryController.start() else queryController.stop()
        onDispose { queryController.stop() }
    }

    // Re-sync the local nickname once the relay becomes reachable (best-effort
    // "sync later" after a first-run Setup that had no relay yet).
    LaunchedEffect(connected) {
        if (connected) syncLocalNickname()
    }

    // Map-active cadence: while the screen is open ask for faster updates and
    // restore the background cadence when it closes. Only when sharing is fully
    // active (permissions + location switch + the foreground service running).
    DisposableEffect(Unit) {
        val activeCadence = sharingActive
        if (activeCadence) {
            ContextCompat.startForegroundService(
                context,
                LocationForegroundService.intent(context, PublisherMode.MAP_ACTIVE),
            )
        }
        onDispose {
            if (activeCadence) {
                ContextCompat.startForegroundService(
                    context,
                    LocationForegroundService.intent(context, PublisherMode.BACKGROUND),
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.locations_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val markers = result.markers
        val relayUnreachable =
            connection is RelayConnection.Failed || result.status == MapQueryStatus.RELAY_UNREACHABLE
        val queryFailed = result.status == MapQueryStatus.ERROR
        val showConnectionSpinner =
            result.status == null && (connecting || !connected) && !relayUnreachable

        // Drop the selection when the selected device disappears from the result.
        LaunchedEffect(markers) {
            selectionController.onMarkersChanged(markers.map { it.deviceId }.toSet())
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
val selectedMarker = selectedDeviceId?.let { id ->
            markers.firstOrNull { it.deviceId == id }
        }
        // Freshness is a per-device property shown in the details panel; the
        // global strip above the map only surfaces transient states.
        val statusText = when {
            selectedMarker == null -> null
            selectedMarker.status == LocationStatus.AVAILABLE ->
                updatedFrom(selectedMarker.publishedAtEpochMs)
            selectedMarker.status == LocationStatus.STALE ->
                lastLocationFrom(selectedMarker.publishedAtEpochMs)
            else -> stringResource(R.string.locations_location_unavailable)
        }
        StatusBanner(
            connecting = showConnectionSpinner,
            queryFailed = queryFailed,
            unreachable = relayUnreachable,
            onRetry = { relayController.probe() },
        )

            // The map fills the remaining space; the selector floats above it in
            // an elevated, backgrounded layer so it can never be overlapped.
            Box(modifier = Modifier.fillMaxSize()) {
                LocationsMap(
                    devices = markers,
                    selectedDeviceId = selectedDeviceId,
                    focusRequest = focusRequest,
                    onDeviceSelected = { deviceId ->
                        selectionController.selectDevice(deviceId, hasCoordinates = true)
                    },
                    onMapEmptyTap = { selectionController.hideDetails() },
                    modifier = Modifier.fillMaxSize(),
                )

                when {
                    markers.isEmpty() && showConnectionSpinner ->
                        CenterMessage(
                            showProgress = true,
                            message = stringResource(R.string.locations_connecting),
                        )
                    markers.isEmpty() && relayUnreachable ->
                        CenterMessage(
                            message = stringResource(R.string.locations_connection_error),
                            onRetry = { relayController.probe() },
                        )
                    markers.isEmpty() && queryFailed ->
                        CenterMessage(
                            message = stringResource(R.string.locations_error),
                            onRetry = { relayController.probe() },
                        )
                    markers.isEmpty() && result.status == MapQueryStatus.OK ->
                        CenterMessage(
                            message = stringResource(R.string.locations_empty),
                            subtitle = stringResource(R.string.locations_never_shared),
                        )
                }

                if (markers.isNotEmpty()) {
                    DeviceChips(
                        markers = markers,
                        selectedDeviceId = selectedDeviceId,
                        onSelect = { deviceId ->
                            selectionController.selectDevice(deviceId, hasCoordinates = true)
                        },
modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp)
            .zIndex(1f),
                    )
                }

                // Compact detail sheet for the selected device, floating at the
                // bottom so the map stays the dominant element (no scrim, no
                // navigation). Coordinates are only shown here; they never reach
                // Diagnostics/logs.
                if (detailsVisible && selectedMarker != null) {
                    val detail = remember(selectedMarker) { buildDeviceDetail(selectedMarker) }
                    val copiedMessage = stringResource(R.string.locations_copied)
                    DeviceDetailSheet(
                        detail = detail,
                        updatedText = statusText,
                        onCopy = {
                            if (detail.coordinates != null) {
                                clipboard.setText(AnnotatedString(detail.coordinates))
                                scope.launch {
                                    snackbarHostState.showSnackbar(copiedMessage)
                                }
                            }
                        },
                        onCenter = {
                            selectionController.selectDevice(detail.deviceId, hasCoordinates = true)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .zIndex(1f),
                    )
                }
            }
        }
    }
}

/**
 * Single status strip above the map, shown only for transient states
 * (connecting, query failure, unreachable). The healthy state renders nothing,
 * freeing vertical space for the map; freshness lives in the per-device details
 * panel instead of a redundant global timestamp.
 */
@Composable
private fun StatusBanner(
    connecting: Boolean,
    queryFailed: Boolean,
    unreachable: Boolean,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            connecting -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.locations_connecting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            queryFailed || unreachable -> {
                Text(
                    text = stringResource(R.string.locations_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.retry_button),
                    )
                }
            }
        }
    }
}

/** "Updated ..." for an AVAILABLE selected device (its last publication). */
@Composable
private fun updatedFrom(publishedAtEpochMs: Long?): String? {
    if (publishedAtEpochMs == null) return null
    val minutes = (System.currentTimeMillis() - publishedAtEpochMs) / 60_000L
    val ago = if (minutes < 1) {
        stringResource(R.string.locations_just_now)
    } else {
        stringResource(R.string.locations_minutes_ago, minutes)
    }
    return stringResource(R.string.locations_updated_at, ago)
}

/** "Last location ..." for a STALE selected device. */
@Composable
private fun lastLocationFrom(publishedAtEpochMs: Long?): String? {
    if (publishedAtEpochMs == null) return null
    val minutes = (System.currentTimeMillis() - publishedAtEpochMs) / 60_000L
    val ago = if (minutes < 1) {
        stringResource(R.string.locations_just_now)
    } else {
        stringResource(R.string.locations_minutes_ago, minutes)
    }
    return stringResource(R.string.locations_last_location_ago, ago)
}

/**
 * Device selector: one chip per device, drawn in an elevated panel above the
 * map. Each chip shows the same device color as its map marker; the selected
 * chip is visually highlighted and shares the single [selectedDeviceId].
 */
@Composable
private fun DeviceChips(
    markers: List<DeviceMarker>,
    selectedDeviceId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(markers, key = { it.deviceId }) { marker ->
                DeviceChip(
                    marker = marker,
                    selected = marker.deviceId == selectedDeviceId,
                    onClick = { onSelect(marker.deviceId) },
                )
            }
        }
    }
}

@Composable
private fun DeviceChip(
    marker: DeviceMarker,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val dotColor = DeviceColorResolver.colorFor(marker.deviceId)
    val statusText = when (marker.status) {
        LocationStatus.AVAILABLE -> null
        LocationStatus.STALE -> stringResource(R.string.locations_status_stale)
        LocationStatus.UNAVAILABLE -> stringResource(R.string.locations_status_offline)
    }
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = marker.label,
                style = MaterialTheme.typography.labelMedium,
            )
            if (statusText != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Compact detail sheet for the selected device, floating at the bottom over the
 * map. Shows nickname, freshness, precision and the coordinate pair with two
 * actions. The map stays visible and dominant; there is no scrim or navigation.
 */
@Composable
private fun DeviceDetailSheet(
    detail: DeviceDetailUiState,
    updatedText: String?,
    onCopy: () -> Unit,
    onCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusLabel = when (detail.status) {
        LocationStatus.STALE -> stringResource(R.string.locations_status_stale)
        LocationStatus.UNAVAILABLE -> stringResource(R.string.locations_status_offline)
        LocationStatus.AVAILABLE -> null
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = detail.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (statusLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (updatedText != null) {
                Text(
                    text = updatedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            DetailRow(
                label = stringResource(R.string.locations_precision_label),
                value = detail.accuracyLabel
                    ?: stringResource(R.string.locations_accuracy_unavailable),
            )
            if (detail.coordinates != null) {
                DetailRow(
                    label = stringResource(R.string.locations_coordinates_label),
                    value = detail.coordinates,
                )
            } else {
                DetailRow(
                    label = stringResource(R.string.locations_coordinates_label),
                    value = stringResource(R.string.locations_no_location),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy, enabled = detail.canCopy) {
                    Text(stringResource(R.string.locations_copy_location))
                }
                TextButton(onClick = onCenter) {
                    Text(stringResource(R.string.locations_center))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CenterMessage(
    message: String,
    subtitle: String? = null,
    showProgress: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showProgress) {
            CircularProgressIndicator()
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.retry_button))
            }
        }
    }
}

/** Map-active poll cadence. Mirrors the FGS active interval so the frame the
 *  user is looking at is roughly as fresh as the published positions. */
private const val MAP_POLL_INTERVAL_MS = 30_000L