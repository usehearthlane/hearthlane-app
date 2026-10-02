package org.hearthlane.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {

    private val snapshot = DiagnosticsReport.Snapshot(
        appVersion = "0.1.0",
        frigateConnectivity = "Connected (TAILSCALE)",
        relayConnectivity = "Disconnected",
        tailscaleState = "connected",
        transport = "TAILSCALE",
        transportSwitchCount = 2,
        playbackState = "playing",
        lastPlaybackError = null,
        firstFrameElapsedMs = 1500,
        serverVersion = "0.17.1-416a9b7",
        errorCount = 3,
        bytesTransferred = 123456,
        recoveryCount = 1,
        nodeHostname = "hearthlane-abc12345",
        baseDomain = "hearthlane.example",
    )

    @Test
    fun `report contains every allow-listed field`() {
        val report = DiagnosticsReport.build(snapshot)

        assertTrue(report.contains("App version: 0.1.0"))
        assertTrue(report.contains("Server: hearthlane.example"))
        assertTrue(report.contains("Frigate endpoint: Connected (TAILSCALE)"))
        assertTrue(report.contains("Relay endpoint: Disconnected"))
        assertTrue(report.contains("Tailscale state: connected"))
        assertTrue(report.contains("Selected transport: TAILSCALE"))
        assertTrue(report.contains("Transport switches: 2"))
        assertTrue(report.contains("Playback state: playing"))
        assertTrue(report.contains("Last playback error: none"))
        assertTrue(report.contains("Time to first frame: 1500 ms"))
        assertTrue(report.contains("Server version: 0.17.1-416a9b7"))
        assertTrue(report.contains("Node hostname: hearthlane-abc12345"))
        assertTrue(report.contains("Diagnostics: errors 3, bytes 123456, recoveries 1"))
    }

    @Test
    fun `auth URL embedded in a playback error is redacted`() {
        val report = DiagnosticsReport.build(
            snapshot.copy(lastPlaybackError = "session expired: https://login.tailscale.com/a/abc123XYZ"),
        )

        assertFalse("the report must never contain the enrollment URL", report.contains("login.tailscale.com"))
        assertTrue(report.contains("[redacted]"))
    }

    @Test
    fun `control-plane URL is redacted`() {
        val report = DiagnosticsReport.build(
            snapshot.copy(lastPlaybackError = "backend unreachable: https://controlplane.tailscale.com/admin/xyz"),
        )

        assertFalse("the report must never contain a tailscale control URL", report.contains("controlplane.tailscale.com"))
    }

    @Test
    fun `token-shaped strings are redacted`() {
        val token = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val report = DiagnosticsReport.build(
            snapshot.copy(lastPlaybackError = "rejected key $token"),
        )

        assertFalse("the report must never contain token-shaped strings", report.contains(token))
    }

    @Test
    fun `short values are not over-redacted`() {
        val report = DiagnosticsReport.build(snapshot)

        assertTrue("short technical values must survive", report.contains("0.17.1-416a9b7"))
        assertTrue("short technical values must survive", report.contains("123456"))
    }

    @Test
    fun `location section renders every observability field`() {
        val location = LocationDiagnosticsSnapshot(
            sharingEnabled = "Yes",
            foregroundPermission = "Granted",
            backgroundPermission = "Granted",
            locationServices = "Enabled",
            foregroundService = "Running",
            publisherState = "Waiting",
            publisherMode = "Background",
            locationCheckIntervalLabel = "1 min",
            minPublishIntervalLabel = "30 sec",
            movementThresholdLabel = "100 m",
            presenceIntervalLabel = "15 min",
            mapActiveIntervalLabel = "30 sec",
            lastRead = "16:57:12",
            lastReadResult = "Success",
            lastPublishAttempt = "16:57:13",
            lastPublishResult = "Success",
            lastSuccessfulPublish = "16:57:13",
            pendingLocation = "No",
            relay = "Reachable",
            deviceId = "hearthlane-ab12cd34",
            deviceNickname = "Meu celular",
        )
        val report = DiagnosticsReport.build(snapshot.copy(location = location))

        assertTrue(report.contains("Location"))
        assertTrue(report.contains("Sharing enabled: Yes"))
        assertTrue(report.contains("Foreground permission: Granted"))
        assertTrue(report.contains("Background permission: Granted"))
        assertTrue(report.contains("Location services: Enabled"))
        assertTrue(report.contains("Foreground service: Running"))
        assertTrue(report.contains("Publisher state: Waiting"))
        assertTrue(report.contains("Publisher mode: Background"))
        assertTrue(report.contains("Location check interval: 1 min"))
        assertTrue(report.contains("Minimum publish interval: 30 sec"))
        assertTrue(report.contains("Movement threshold: 100 m"))
        assertTrue(report.contains("Presence interval: 15 min"))
        assertTrue(report.contains("Map-active interval: 30 sec"))
        assertTrue(report.contains("Last location read: 16:57:12 (Success)"))
        assertTrue(report.contains("Last publish attempt: 16:57:13"))
        assertTrue(report.contains("Last publish result: Success"))
        assertTrue(report.contains("Last successful publish: 16:57:13"))
        assertTrue(report.contains("Pending location: No"))
        assertTrue(report.contains("Relay: Reachable"))
        assertTrue(report.contains("Device ID: hearthlane-ab12cd34"))
        assertTrue(report.contains("Device nickname: Meu celular"))
    }

    @Test
    fun `location section reports never when nothing was published`() {
        val location = LocationDiagnosticsSnapshot(
            sharingEnabled = "Yes",
            foregroundPermission = "Granted",
            backgroundPermission = "Denied",
            locationServices = "Enabled",
            foregroundService = "Stopped",
            publisherState = "Idle",
            publisherMode = "Disabled",
            locationCheckIntervalLabel = "1 min",
            minPublishIntervalLabel = "30 sec",
            movementThresholdLabel = "100 m",
            presenceIntervalLabel = "15 min",
            mapActiveIntervalLabel = "30 sec",
            lastRead = null,
            lastReadResult = null,
            lastPublishAttempt = null,
            lastPublishResult = null,
            lastSuccessfulPublish = null,
            pendingLocation = "No",
            relay = "Unknown",
            deviceId = "hearthlane-ab12cd34",
            deviceNickname = "(unset)",
        )
        val report = DiagnosticsReport.build(snapshot.copy(location = location))

        assertTrue(report.contains("Last location read: Never"))
        assertTrue(report.contains("Last publish attempt: Never"))
        assertTrue(report.contains("Last successful publish: Never"))
        assertTrue(report.contains("Publisher mode: Disabled"))
    }

    @Test
    fun `location report never contains coordinates or payload`() {
        val location = LocationDiagnosticsSnapshot(
            sharingEnabled = "Yes",
            foregroundPermission = "Granted",
            backgroundPermission = "Granted",
            locationServices = "Enabled",
            foregroundService = "Running",
            publisherState = "Error",
            publisherMode = "Background",
            locationCheckIntervalLabel = "1 min",
            minPublishIntervalLabel = "30 sec",
            movementThresholdLabel = "100 m",
            presenceIntervalLabel = "15 min",
            mapActiveIntervalLabel = "30 sec",
            lastRead = "16:57:12",
            lastReadResult = "Success",
            lastPublishAttempt = "16:57:13",
            lastPublishResult = "HTTP 5xx",
            lastSuccessfulPublish = null,
            pendingLocation = "No",
            relay = "Unreachable",
            deviceId = "hearthlane-ab12cd34",
            deviceNickname = "Meu celular",
        )
        val report = DiagnosticsReport.build(snapshot.copy(location = location))

        assertFalse(report.contains("latitude"))
        assertFalse(report.contains("longitude"))
        assertFalse(report.contains("recordedAtEpochMs"))
        assertFalse(report.contains("Authorization"))
        assertFalse(report.contains("Bearer"))
    }

    @Test
    fun `location report renders the phase 1 observability fields`() {
        val location = LocationDiagnosticsSnapshot(
            sharingEnabled = "Yes",
            foregroundPermission = "Granted",
            backgroundPermission = "Granted",
            locationServices = "Enabled",
            foregroundService = "Running",
            publisherState = "Waiting",
            publisherMode = "Background",
            locationCheckIntervalLabel = "1 min",
            minPublishIntervalLabel = "30 sec",
            movementThresholdLabel = "100 m",
            presenceIntervalLabel = "15 min",
            mapActiveIntervalLabel = "30 sec",
            lastRead = "16:57:12",
            lastReadResult = "Success",
            lastPublishAttempt = "16:57:13",
            lastPublishResult = "Success",
            lastSuccessfulPublish = "16:57:13",
            pendingLocation = "No",
            relay = "Reachable",
            deviceId = "hearthlane-ab12cd34",
            deviceNickname = "Meu celular",
            lastFixProvider = "gps",
            lastFixAccuracy = "12 m",
            lastFixAge = "90 sec",
            lastPublishDecision = "PUBLISH MOVEMENT",
            tsnetState = "Running",
            tsnetStarts = 3,
            tsnetStops = 3,
            tsnetLastStartDuration = "4.2 s",
            tsnetLastStartAt = "1h02m03s",
            tsnetLastStopAt = "1h01m58s",
            tsnetLastTransport = "TAILSCALE",
            tsnetLastNetwork = "WIFI",
            tsnetPublishAttempts = 5,
            tsnetPublishSuccesses = 4,
            tsnetPublishFailures = 1,
        )
        val report = DiagnosticsReport.build(snapshot.copy(location = location))

        assertTrue(report.contains("Last fix provider: gps"))
        assertTrue(report.contains("Last fix accuracy: 12 m"))
        assertTrue(report.contains("Last fix age: 90 sec"))
        assertTrue(report.contains("Last publish decision: PUBLISH MOVEMENT"))
        assertTrue(report.contains("Location tsnet state: Running"))
        assertTrue(report.contains("Location tsnet starts: 3"))
        assertTrue(report.contains("Location tsnet stops: 3"))
        assertTrue(report.contains("Location tsnet last start duration: 4.2 s"))
        assertTrue(report.contains("Location tsnet last start at: 1h02m03s (elapsed)"))
        assertTrue(report.contains("Location tsnet last stop at: 1h01m58s (elapsed)"))
        assertTrue(report.contains("Location tsnet last transport: TAILSCALE"))
        assertTrue(report.contains("Location tsnet last network: WIFI"))
        assertTrue(report.contains("Location tsnet publishes: attempts 5, success 4, failures 1"))
    }

    @Test
    fun `location report renders v2 machine fields`() {
        val location = LocationDiagnosticsSnapshot(
            sharingEnabled = "Yes",
            foregroundPermission = "Granted",
            backgroundPermission = "Granted",
            locationServices = "Enabled",
            foregroundService = "Running",
            publisherState = "Waiting",
            publisherMode = "Background",
            locationCheckIntervalLabel = "1 min",
            minPublishIntervalLabel = "30 sec",
            movementThresholdLabel = "100 m",
            presenceIntervalLabel = "15 min",
            mapActiveIntervalLabel = "30 sec",
            lastRead = "16:57:12",
            lastReadResult = "Success",
            lastPublishAttempt = "16:57:13",
            lastPublishResult = "Success",
            lastSuccessfulPublish = "16:57:13",
            pendingLocation = "Yes",
            relay = "Reachable",
            deviceId = "hearthlane-ab12cd34",
            deviceNickname = "Meu celular",
            lastFixDecision = "ACCEPTED_MOVEMENT",
            lastPublishDecision = "PUBLISH MOVEMENT",
            backoff = "active attempt 2, 90 sec remaining",
            presenceCount = 3,
            lastPresenceAt = "1h02m03s",
            lastPresenceDecision = "PRESENCE",
        )
        val report = DiagnosticsReport.build(snapshot.copy(location = location))

        assertTrue(report.contains("Last fix decision: ACCEPTED_MOVEMENT"))
        assertTrue(report.contains("Last publish decision: PUBLISH MOVEMENT"))
        assertTrue(report.contains("Backoff: active attempt 2, 90 sec remaining"))
        assertTrue(report.contains("Presence count: 3"))
        assertTrue(report.contains("Last presence at: 1h02m03s (elapsed)"))
        assertTrue(report.contains("Last presence decision: PRESENCE"))
    }

    @Test
    fun `location report renders n a for missing observability fields`() {
        val location = LocationDiagnosticsSnapshot(
            sharingEnabled = "Yes",
            foregroundPermission = "Granted",
            backgroundPermission = "Granted",
            locationServices = "Enabled",
            foregroundService = "Running",
            publisherState = "Waiting",
            publisherMode = "Background",
            locationCheckIntervalLabel = "1 min",
            minPublishIntervalLabel = "30 sec",
            movementThresholdLabel = "100 m",
            presenceIntervalLabel = "15 min",
            mapActiveIntervalLabel = "30 sec",
            lastRead = null,
            lastReadResult = null,
            lastPublishAttempt = null,
            lastPublishResult = null,
            lastSuccessfulPublish = null,
            pendingLocation = "No",
            relay = "Unknown",
            deviceId = "hearthlane-ab12cd34",
            deviceNickname = "(unset)",
        )
        val report = DiagnosticsReport.build(snapshot.copy(location = location))

        assertTrue(report.contains("Last fix provider: n/a"))
        assertTrue(report.contains("Last fix accuracy: n/a"))
        assertTrue(report.contains("Last fix age: n/a"))
        assertTrue(report.contains("Last fix decision: n/a"))
        assertTrue(report.contains("Last publish decision: n/a"))
        assertTrue(report.contains("Backoff: none"))
        assertTrue(report.contains("Presence count: 0"))
        assertTrue(report.contains("Last presence at: Never"))
        assertTrue(report.contains("Last presence decision: n/a"))
        assertTrue(report.contains("Location tsnet last start at: Never"))
        assertTrue(report.contains("Location tsnet last stop at: Never"))
    }

    @Test
    fun `sanitize handles mixed content`() {
        val cleaned = DiagnosticsReport.sanitize(
            "warn: https://login.tailscale.com/a/abc; token=AAAABBBBCCCCDDDDEEEEFFFF; fine",
        )

        assertFalse(cleaned.contains("login.tailscale.com"))
        assertFalse(cleaned.contains("AAAABBBBCCCCDDDDEEEEFFFF"))
        assertTrue(cleaned.contains("fine"))
    }

    @Test
    fun `sanitize is accessible as public API for clipboard sanitization`() {
        // Validates that DiagnosticsReport.sanitize can be called from any
        // module (SetupScreen uses it for clipboard copy).
        val raw = "error: Tailscale requires authentication https://login.tailscale.com/a/secret123"
        val sanitized = DiagnosticsReport.sanitize(raw)

        assertFalse(
            "sanitize must remove the auth URL",
            sanitized.contains("login.tailscale.com"),
        )
        assertTrue(
            "non-sensitive content must survive sanitization",
            sanitized.contains("Tailscale requires authentication"),
        )
    }
}
