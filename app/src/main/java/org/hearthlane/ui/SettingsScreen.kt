package org.hearthlane.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.hearthlane.R
import org.hearthlane.controller.ConnectionStatus
import org.hearthlane.controller.LocationSharingController
import org.hearthlane.controller.LocationSharingStatus
import org.hearthlane.controller.LocationSharingStep
import org.hearthlane.controller.SettingsController
import org.hearthlane.core.frigate.LiveQualityMode

/** Compose test tag anchoring the auto-play switch for UI tests. */
internal const val AUTOPLAY_TOGGLE_TAG = "autoplay_toggle"

/**
 * Product-oriented Settings screen.
 *
 * Settings is organized around what the user controls, not the infrastructure
 * behind it: the Hearthlane server domain, remote access, diagnostics entry and
 * app information. Technical details (node hostname, transport, counters) live
 * in the Diagnostics screen, never here.
 *
 * The screen renders [SettingsController.state] and delegates navigation and
 * actions to the composition root; it holds no Frigate/Tailscale logic. The
 * location-sharing section is driven by [LocationSharingController], which
 * owns the permission flow; this screen only executes the Android-specific
 * steps (permission request, system settings intent).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    controller: SettingsController,
    locationSharingController: LocationSharingController,
    onOpenServerSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onReconfigureRemoteAccess: () -> Unit,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    var confirmReconfigure by remember { mutableStateOf(false) }
    var showLiveQualityDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_screen_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // -- Server section --
            SettingsSectionHeader(stringResource(R.string.settings_section_server))
            SettingsRow(
                title = stringResource(R.string.settings_server_address),
                value = state.baseDomain,
                onClick = onOpenServerSettings,
            )
            Text(
                text = connectionStatusText(state.connectionStatus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
            )

            SettingsDivider()

            // -- Remote access section --
            SettingsSectionHeader(stringResource(R.string.settings_section_access))
            SettingsRow(
                title = stringResource(R.string.settings_remote_access_reconfigure),
                onClick = { confirmReconfigure = true },
            )

            SettingsDivider()

            // -- Playback section --
            SettingsSectionHeader(stringResource(R.string.settings_section_playback))
            SettingsSwitchRow(
                title = stringResource(R.string.settings_autoplay_event_clips),
                checked = state.autoPlayEventClips,
                onCheckedChange = controller::setAutoPlayEventClips,
                testTag = AUTOPLAY_TOGGLE_TAG,
            )
            SettingsRow(
                title = stringResource(R.string.settings_live_quality),
                value = stringResource(liveQualityLabel(state.liveQualityMode)),
                onClick = { showLiveQualityDialog = true },
            )

            SettingsDivider()

            // -- Location sharing section --
            SettingsSectionHeader(stringResource(R.string.settings_section_location))
            LocationSharingSection(controller = locationSharingController)

            SettingsDivider()

            // -- Diagnostics section (single clickable entry) --
            SettingsSectionLink(
                title = stringResource(R.string.diagnostics_title),
                onClick = onOpenDiagnostics,
            )

            SettingsDivider()

            // -- About section --
            SettingsSectionHeader(stringResource(R.string.settings_section_about))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                text = stringResource(R.string.settings_app_version, state.appVersion),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                text = stringResource(R.string.settings_app_build, state.appBuild),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    if (showLiveQualityDialog) {
        LiveQualityDialog(
            selected = state.liveQualityMode,
            onSelect = controller::setLiveQualityMode,
            onDismiss = { showLiveQualityDialog = false },
        )
    }

    if (confirmReconfigure) {
        AlertDialog(
            onDismissRequest = { confirmReconfigure = false },
            title = { Text(stringResource(R.string.settings_reconfigure_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reconfigure_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReconfigure = false
                    onReconfigureRemoteAccess()
                }) {
                    Text(stringResource(R.string.settings_reconfigure_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReconfigure = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            },
        )
    }
}

/** Label string for a live quality mode. */
@Composable
private fun liveQualityLabel(mode: LiveQualityMode): Int = when (mode) {
    LiveQualityMode.AUTO -> R.string.settings_live_quality_auto
    LiveQualityMode.HIGH -> R.string.settings_live_quality_high
    LiveQualityMode.ECONOMY -> R.string.settings_live_quality_economy
}

@Composable
private fun LiveQualityDialog(
    selected: LiveQualityMode,
    onSelect: (LiveQualityMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_live_quality)) },
        text = {
            Column {
                LiveQualityOption(
                    mode = LiveQualityMode.AUTO,
                    label = R.string.settings_live_quality_auto,
                    description = R.string.settings_live_quality_auto_desc,
                    selected = selected == LiveQualityMode.AUTO,
                    onSelect = { onSelect(LiveQualityMode.AUTO) },
                )
                LiveQualityOption(
                    mode = LiveQualityMode.HIGH,
                    label = R.string.settings_live_quality_high,
                    description = R.string.settings_live_quality_high_desc,
                    selected = selected == LiveQualityMode.HIGH,
                    onSelect = { onSelect(LiveQualityMode.HIGH) },
                )
                LiveQualityOption(
                    mode = LiveQualityMode.ECONOMY,
                    label = R.string.settings_live_quality_economy,
                    description = R.string.settings_live_quality_economy_desc,
                    selected = selected == LiveQualityMode.ECONOMY,
                    onSelect = { onSelect(LiveQualityMode.ECONOMY) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
    )
}

@Composable
private fun LiveQualityOption(
    mode: LiveQualityMode,
    label: Int,
    description: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            modifier = Modifier.testTag("live_quality_${mode.name.lowercase()}"),
        )
        Column {
            Text(text = stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * A section header that is itself the entry point (used by Diagnostics, where
 * a separate header and row would duplicate the same label).
 */
@Composable
private fun SettingsSectionLink(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    value: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (testTag == null) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(testTag),
            )
        }
    }
}

/**
 * Location-sharing opt-in driven by [LocationSharingController].
 *
 * The toggle reflects the persisted desire; the caption below reflects the
 * real system capacity (permissions and location switch). The controller emits
 * the concrete steps the screen executes: the foreground permission request,
 * the in-app background explanation (followed by the system permission page)
 * and the revalidation on resume.
 */
@Composable
private fun LocationSharingSection(controller: LocationSharingController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by controller.state.collectAsState()
    var showBackgroundExplanation by remember { mutableStateOf(false) }

    // The foreground permission request is the first step of the flow.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        controller.onForegroundPermissionResult(granted)
    }

    // Advance the flow when the controller asks for it.
    LaunchedEffect(uiState.step) {
        when (uiState.step) {
            LocationSharingStep.RequestForegroundPermission -> {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
                controller.consumeStep()
            }
            LocationSharingStep.ExplainBackgroundPermission -> {
                showBackgroundExplanation = true
                controller.consumeStep()
            }
            LocationSharingStep.None -> Unit
        }
    }

    // Revalidate the real permission/location state whenever the app returns to
    // the foreground (for example after the system permission page).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.onSettingsReturned()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsSwitchRow(
        title = stringResource(R.string.settings_location_sharing),
        checked = uiState.desired,
        onCheckedChange = controller::onToggleDesired,
    )
    LocationSharingStatusCaption(
        status = uiState.status,
        onAction = {
            when (uiState.status) {
                LocationSharingStatus.NeedsForegroundPermission ->
                    controller.onToggleDesired(true)
                LocationSharingStatus.NeedsBackgroundPermission ->
                    controller.onToggleDesired(true)
                LocationSharingStatus.LocationDisabled ->
                    openDeviceLocationSettings(context)
                LocationSharingStatus.Error ->
                    controller.onToggleDesired(true)
                else -> Unit
            }
        },
    )

    if (showBackgroundExplanation) {
        AlertDialog(
            onDismissRequest = { showBackgroundExplanation = false },
            title = { Text(stringResource(R.string.settings_location_background_title)) },
            text = { Text(stringResource(R.string.settings_location_background_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundExplanation = false
                    controller.onBackgroundExplanationConfirmed()
                    openBackgroundPermissionSettings(context)
                }) {
                    Text(stringResource(R.string.settings_location_background_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundExplanation = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            },
        )
    }
}

/**
 * Caption that differentiates the user's desire (the toggle) from the real
 * system capacity. Actionable states show an inline action to advance the flow.
 */
@Composable
private fun LocationSharingStatusCaption(
    status: LocationSharingStatus,
    onAction: () -> Unit,
) {
    val text = when (status) {
        LocationSharingStatus.Disabled,
        LocationSharingStatus.Ready,
        LocationSharingStatus.Running,
        -> stringResource(R.string.settings_location_sharing_body)
        LocationSharingStatus.NeedsForegroundPermission ->
            stringResource(R.string.settings_location_status_permission)
        LocationSharingStatus.NeedsBackgroundPermission ->
            stringResource(R.string.settings_location_status_background)
        LocationSharingStatus.LocationDisabled ->
            stringResource(R.string.settings_location_status_location_off)
        LocationSharingStatus.Error ->
            stringResource(R.string.settings_location_status_error)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (status == LocationSharingStatus.NeedsForegroundPermission ||
        status == LocationSharingStatus.NeedsBackgroundPermission ||
        status == LocationSharingStatus.LocationDisabled ||
        status == LocationSharingStatus.Error
    ) {
        val action = when (status) {
            LocationSharingStatus.NeedsForegroundPermission -> R.string.settings_location_action_grant
            LocationSharingStatus.NeedsBackgroundPermission -> R.string.settings_location_action_continuous
            LocationSharingStatus.LocationDisabled -> R.string.settings_location_action_location_settings
            else -> R.string.settings_location_action_retry
        }
        TextButton(
            onClick = onAction,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(action))
        }
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun connectionStatusText(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Connected -> stringResource(R.string.settings_connection_connected)
    ConnectionStatus.Connecting -> stringResource(R.string.settings_connection_connecting)
    ConnectionStatus.Unavailable -> stringResource(R.string.settings_connection_unavailable)
}