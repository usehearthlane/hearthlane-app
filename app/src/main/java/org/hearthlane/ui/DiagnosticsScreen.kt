package org.hearthlane.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.hearthlane.R
import org.hearthlane.controller.FrigateConnectionController
import org.hearthlane.controller.PlaybackSnapshot
import org.hearthlane.controller.RelayConnectionController
import org.hearthlane.diagnostics.DiagnosticsReport
import org.hearthlane.diagnostics.buildDiagnosticsSnapshot
import org.hearthlane.diagnostics.buildLocationDiagnosticsSnapshot
import org.hearthlane.location.LocationDiagnosticsMonitor
import org.hearthlane.location.LocationPermissionSnapshot
import org.hearthlane.location.TsnetLifecycleMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * V1.5 administrator observability screen. Shows every Section 10 diagnostics
 * field as the exact plain-text report produced by
 * [DiagnosticsReport.build], so the displayed text and the copied report are
 * the same sanitized content by construction. A retry-connection action is
 * provided as an admin convenience (the normal flow keeps its own Try again).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    controller: FrigateConnectionController,
    relayController: RelayConnectionController,
    playbackSnapshot: PlaybackSnapshot,
    appVersion: String,
    nodeHostname: String,
    baseDomain: String,
    sharingEnabled: StateFlow<Boolean>,
    deviceNickname: StateFlow<String>,
    onRetryConnection: () -> Unit,
    onBack: () -> Unit,
) {
    val connection by controller.connection.collectAsState()
    val connecting by controller.connecting.collectAsState()
    val transport by controller.lastProbedTransport.collectAsState()
    val transportSwitchCount by controller.transportSwitchCount.collectAsState()
    val relayConnection by relayController.connection.collectAsState()
    val relayConnecting by relayController.connecting.collectAsState()
    val sharing by sharingEnabled.collectAsState()
    val nickname by deviceNickname.collectAsState()
    val publishing by LocationDiagnosticsMonitor.state.collectAsState()
    val tsnetLifecycle by TsnetLifecycleMonitor.state.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Permissions/location switch are context reads; refresh on resume so a
    // change made elsewhere is reflected without polling.
    var permissions by remember { mutableStateOf(LocationPermissionSnapshot.from(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissions = LocationPermissionSnapshot.from(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationSnapshot = buildLocationDiagnosticsSnapshot(
        sharingEnabled = sharing,
        permissions = permissions,
        backgroundPermissionRequired =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R,
        publishing = publishing,
        relay = relayConnection,
        deviceId = nodeHostname,
        deviceNickname = nickname,
        tsnet = tsnetLifecycle,
    )

    val report = remember(
        connection,
        connecting,
        transport,
        transportSwitchCount,
        relayConnection,
        relayConnecting,
        playbackSnapshot,
        appVersion,
        nodeHostname,
        baseDomain,
        locationSnapshot,
    ) {
        DiagnosticsReport.build(
            buildDiagnosticsSnapshot(
                connection = connection,
                connecting = connecting,
                relayConnection = relayConnection,
                relayConnecting = relayConnecting,
                transport = transport,
                transportSwitchCount = transportSwitchCount,
                playback = playbackSnapshot,
                appVersion = appVersion,
                nodeHostname = nodeHostname,
                baseDomain = baseDomain,
                location = locationSnapshot,
            ),
        )
    }

    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_FEEDBACK_MS)
            copied = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
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
            SelectionContainer {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                clipboard.setText(AnnotatedString(report))
                copied = true
            }) {
                Text(
                    stringResource(
                        if (copied) R.string.diagnostics_copied
                        else R.string.diagnostics_copy_button,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetryConnection) {
                Text(stringResource(R.string.diagnostics_retry_connection))
            }
        }
    }
}

private const val COPY_FEEDBACK_MS = 2_000L
