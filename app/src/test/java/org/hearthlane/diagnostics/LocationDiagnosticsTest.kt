package org.hearthlane.diagnostics

import org.hearthlane.core.relay.RelayConnection
import org.hearthlane.core.relay.RelayTransportKind
import org.hearthlane.location.AcquisitionDiagnosticsMonitor
import org.hearthlane.location.FixDecisionReason
import org.hearthlane.location.LocationDiagnosticsMonitor
import org.hearthlane.location.LocationPermissionSnapshot
import org.hearthlane.location.LocationReadStatus
import org.hearthlane.location.PublishDecisionReason
import org.hearthlane.location.PublisherMode
import org.hearthlane.location.TsnetLifecycleMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [buildLocationDiagnosticsSnapshot] and its result classifiers: the
 * Location Diagnostics section reflects sharing, permissions, service state,
 * publisher mode and publishing outcome, and never leaks coordinates, payloads
 * or tokens.
 */
class LocationDiagnosticsTest {

    private fun snapshot(
        sharing: Boolean = true,
        foregroundGranted: Boolean = true,
        backgroundGranted: Boolean = true,
        backgroundRequired: Boolean = true,
        locationEnabled: Boolean = true,
        serviceRunning: Boolean = true,
        publisherRunning: Boolean = true,
        mode: String = PublisherMode.BACKGROUND.name,
        lastPublishAttemptAtMs: Long? = null,
        lastPublishResult: String? = null,
        lastPublishAtMs: Long? = null,
        hasPending: Boolean = false,
        lastFixProvider: String? = null,
        lastFixAccuracyMeters: Float? = null,
        lastFixAgeMs: Long? = null,
        lastPublishDecision: String? = null,
        lastFixDecision: String? = null,
        backoffActive: Boolean = false,
        backoffAttempt: Int = 0,
        backoffRemainingMs: Long? = null,
        presenceCount: Int = 0,
        lastPresenceAtMs: Long? = null,
        lastPresenceDecision: String? = null,
        acquisition: AcquisitionDiagnosticsMonitor.State = AcquisitionDiagnosticsMonitor.State(),
        tsnet: TsnetLifecycleMonitor.State = TsnetLifecycleMonitor.State(),
        relay: RelayConnection? = RelayConnection.Connected(RelayTransportKind.LOCAL),
        deviceId: String = "hearthlane-ab12cd34",
        nickname: String = "Meu celular",
    ): LocationDiagnosticsSnapshot = buildLocationDiagnosticsSnapshot(
        sharingEnabled = sharing,
        permissions = LocationPermissionSnapshot(foregroundGranted, backgroundGranted, locationEnabled),
        backgroundPermissionRequired = backgroundRequired,
        publishing = LocationDiagnosticsMonitor.PublishingState(
            serviceRunning = serviceRunning,
            publisherRunning = publisherRunning,
            mode = mode,
            lastPublishAttemptAtMs = lastPublishAttemptAtMs,
            lastPublishResult = lastPublishResult,
            lastPublishAtMs = lastPublishAtMs,
            hasPendingLocation = hasPending,
            lastFixProvider = lastFixProvider,
            lastFixAccuracyMeters = lastFixAccuracyMeters,
            lastFixAgeMs = lastFixAgeMs,
            lastPublishDecision = lastPublishDecision,
            lastFixDecision = lastFixDecision,
            backoffActive = backoffActive,
            backoffAttempt = backoffAttempt,
            backoffRemainingMs = backoffRemainingMs,
            presenceCount = presenceCount,
            lastPresenceAtMs = lastPresenceAtMs,
            lastPresenceDecision = lastPresenceDecision,
        ),
        relay = relay,
        deviceId = deviceId,
        deviceNickname = nickname,
        tsnet = tsnet,
        acquisition = acquisition,
        minPublishIntervalMs = 30_000L,
        distanceThresholdMeters = 100.0,
        presenceIntervalMs = 15 * 60_000L,
    )

    @Test
    fun `sharing enabled by default is reported as Yes`() {
        assertEquals("Yes", snapshot().sharingEnabled)
    }

    @Test
    fun `sharing disabled is reported as No with mode disabled`() {
        val result = snapshot(sharing = false, mode = PublisherMode.MAP_ACTIVE.name)

        assertEquals("No", result.sharingEnabled)
        assertEquals("Disabled", result.publisherMode)
    }

    @Test
    fun `permissions are reported separately`() {
        val result = snapshot(foregroundGranted = true, backgroundGranted = true)

        assertEquals("Granted", result.foregroundPermission)
        assertEquals("Granted", result.backgroundPermission)
    }

    @Test
    fun `foreground denied is reported`() {
        assertEquals("Denied", snapshot(foregroundGranted = false).foregroundPermission)
    }

    @Test
    fun `background denied is reported without conflating with foreground`() {
        val result = snapshot(foregroundGranted = true, backgroundGranted = false)

        assertEquals("Granted", result.foregroundPermission)
        assertEquals("Denied", result.backgroundPermission)
    }

    @Test
    fun `background permission is not required on old Android`() {
        assertEquals("Not required", snapshot(backgroundRequired = false).backgroundPermission)
    }

    @Test
    fun `location services disabled is reported`() {
        assertEquals("Disabled", snapshot(locationEnabled = false).locationServices)
    }

    @Test
    fun `foreground service running and stopped are reported honestly`() {
        assertEquals("Running", snapshot(serviceRunning = true).foregroundService)
        assertEquals("Stopped", snapshot(serviceRunning = false).foregroundService)
    }

    @Test
    fun `publisher idle when the loop is not running`() {
        assertEquals("Idle", snapshot(publisherRunning = false).publisherState)
    }

    @Test
    fun `publisher error when the last publish failed`() {
        val result = snapshot(publisherRunning = true, lastPublishResult = "publish failed: HTTP 503")
        assertEquals("Error", result.publisherState)
    }

    @Test
    fun `publisher waiting on a healthy loop`() {
        val result = snapshot(publisherRunning = true, lastPublishResult = "Success")
        assertEquals("Waiting", result.publisherState)
    }

    @Test
    fun `publisher mode background and map active are reported from the mode`() {
        assertEquals("Background", snapshot(mode = PublisherMode.BACKGROUND.name).publisherMode)
        assertEquals("Map active", snapshot(mode = PublisherMode.MAP_ACTIVE.name).publisherMode)
    }

    @Test
    fun `adaptive policy values are read from the real configured values`() {
        val result = snapshot()

        assertEquals("30 sec", result.minPublishIntervalLabel)
        assertEquals("100 m", result.movementThresholdLabel)
        assertEquals("15 min", result.presenceIntervalLabel)
    }

    @Test
    fun `never published leaves timestamps null`() {
        val result = snapshot(
            lastPublishAttemptAtMs = null,
            lastPublishResult = null,
            lastPublishAtMs = null,
        )

        assertNull(result.lastPublishAttempt)
        assertNull(result.lastPublishResult)
        assertNull(result.lastSuccessfulPublish)
    }

    @Test
    fun `successful publish records attempt and success timestamps`() {
        val result = snapshot(
            lastPublishAttemptAtMs = 1_700_000_001_000L,
            lastPublishResult = "Success",
            lastPublishAtMs = 1_700_000_001_000L,
        )

        assertEquals("Success", result.lastPublishResult)
        assertEquals(result.lastPublishAttempt, result.lastSuccessfulPublish)
    }

    @Test
    fun `publish failure is classified as HTTP 5xx`() {
        assertEquals("HTTP 5xx", classifyResult("publish location failed: HTTP 503"))
    }

    @Test
    fun `publish failure is classified as HTTP 4xx`() {
        assertEquals("HTTP 4xx", classifyResult("set nickname failed: HTTP 401"))
    }

    @Test
    fun `publish failure is classified as timeout`() {
        assertEquals("Timeout", classifyResult("connect timed out"))
    }

    @Test
    fun `publish failure is classified as network error`() {
        assertEquals("Network error", classifyResult("failed to connect to relay"))
    }

    @Test
    fun `publish failure is classified as DNS error`() {
        assertEquals("DNS error", classifyResult("Unable to resolve host relay.hearthlane.omni.corp"))
    }

    @Test
    fun `publish unavailable is classified`() {
        assertEquals("Location unavailable", classifyResult("Location unavailable"))
    }

    @Test
    fun `pending location is reported as yes or no`() {
        assertEquals("Yes", snapshot(hasPending = true).pendingLocation)
        assertEquals("No", snapshot(hasPending = false).pendingLocation)
    }

    @Test
    fun `relay connectivity is reported from the real connection`() {
        assertEquals("Reachable", snapshot(relay = RelayConnection.Connected(RelayTransportKind.LOCAL)).relay)
        assertEquals(
            "Unreachable",
            snapshot(relay = RelayConnection.Failed("timeout")).relay,
        )
        assertEquals("Unknown", snapshot(relay = null).relay)
    }

    @Test
    fun `device identity is exposed without secrets`() {
        val result = snapshot(deviceId = "hearthlane-ab12cd34", nickname = "Meu celular")

        assertEquals("hearthlane-ab12cd34", result.deviceId)
        assertEquals("Meu celular", result.deviceNickname)
    }

    @Test
    fun `last fix observability is reported`() {
        val result = snapshot(
            lastFixProvider = "gps",
            lastFixAccuracyMeters = 12f,
            lastFixAgeMs = 90_000L,
            lastPublishDecision = PublishDecisionReason.MOVEMENT.name,
        )

        assertEquals("gps", result.lastFixProvider)
        assertEquals("12 m", result.lastFixAccuracy)
        assertEquals("90 sec", result.lastFixAge)
        assertEquals("PUBLISH MOVEMENT", result.lastPublishDecision)
    }

    @Test
    fun `skip decision is labeled as SKIP`() {
        assertEquals(
            "SKIP MIN_INTERVAL",
            snapshot(lastPublishDecision = PublishDecisionReason.MIN_INTERVAL.name).lastPublishDecision,
        )
        assertEquals(
            "SKIP NO_PENDING",
            snapshot(lastPublishDecision = PublishDecisionReason.NO_PENDING.name).lastPublishDecision,
        )
    }

    @Test
    fun `unknown reason names survive the label mapping`() {
        assertEquals("SOMETHING_NEW", snapshot(lastPublishDecision = "SOMETHING_NEW").lastPublishDecision)
    }

    @Test
    fun `missing fix observability stays null`() {
        val result = snapshot()
        assertNull(result.lastFixProvider)
        assertNull(result.lastFixAccuracy)
        assertNull(result.lastFixAge)
        assertNull(result.lastPublishDecision)
    }

    @Test
    fun `non usable accuracy is reported as n a`() {
        assertEquals("n/a", snapshot(lastFixAccuracyMeters = Float.NaN).lastFixAccuracy)
        assertEquals("n/a", snapshot(lastFixAccuracyMeters = -1f).lastFixAccuracy)
    }

    @Test
    fun `v2 decision and backoff state are reported`() {
        val result = snapshot(
            lastFixDecision = FixDecisionReason.ACCEPTED_MOVEMENT.name,
            lastPublishDecision = PublishDecisionReason.MOVEMENT.name,
            backoffActive = true,
            backoffAttempt = 2,
            backoffRemainingMs = 90_000L,
            presenceCount = 1,
            lastPresenceAtMs = 3_723_000L,
            lastPresenceDecision = PublishDecisionReason.PRESENCE.name,
        )

        assertEquals(FixDecisionReason.ACCEPTED_MOVEMENT.name, result.lastFixDecision)
        assertEquals("PUBLISH MOVEMENT", result.lastPublishDecision)
        assertEquals("active attempt 2, 90 sec remaining", result.backoff)
        assertEquals(1, result.presenceCount)
        assertEquals("1h02m03s", result.lastPresenceAt)
        assertEquals(PublishDecisionReason.PRESENCE.name, result.lastPresenceDecision)
    }

    @Test
    fun `backoff reports none when inactive`() {
        assertEquals("none", snapshot().backoff)
        assertNull(snapshot().lastFixDecision)
        assertNull(snapshot().lastPresenceAt)
    }

    @Test
    fun `acquisition observability is reported`() {
        val acquisition = AcquisitionDiagnosticsMonitor.State(
            running = true,
            registeredProviders = listOf("network", "passive"),
            gpsInFlight = true,
            lastGpsRequestReason = "POOR_ACCURACY",
            lastGpsRequestAtMs = 3_723_000L,
            lastGpsResult = "SUCCESS",
            gpsRequestCount = 4,
            gpsFailureCount = 1,
        )
        val result = snapshot(acquisition = acquisition)

        assertEquals("network,passive", result.acquisitionProviders)
        assertEquals("Yes", result.gpsInFlight)
        assertEquals("POOR_ACCURACY 1h02m03s", result.lastGpsRequest)
        assertEquals("SUCCESS", result.lastGpsResult)
        assertEquals(4, result.gpsRequests)
        assertEquals(1, result.gpsFailures)
    }

    @Test
    fun `acquisition defaults report none and never`() {
        val result = snapshot()
        assertEquals("none", result.acquisitionProviders)
        assertEquals("No", result.gpsInFlight)
        assertNull(result.lastGpsRequest)
        assertNull(result.lastGpsResult)
        assertEquals(0, result.gpsRequests)
    }

    @Test
    fun `tsnet lifecycle is reported from the monitor state`() {
        val tsnet = TsnetLifecycleMonitor.State(
            startCount = 3,
            stopCount = 3,
            lastStartElapsedRealtime = 3_723_000L,
            lastStopElapsedRealtime = 3_718_000L,
            currentRunning = false,
            lastStartDurationMs = 5_000L,
            publishAttemptCount = 5,
            publishSuccessCount = 4,
            publishFailureCount = 1,
            lastTransport = "TAILSCALE",
            lastNetworkType = "WIFI",
        )
        val result = snapshot(tsnet = tsnet)

        assertEquals("Stopped", result.tsnetState)
        assertEquals(3, result.tsnetStarts)
        assertEquals(3, result.tsnetStops)
        assertEquals("5.0 s", result.tsnetLastStartDuration)
        assertEquals("1h02m03s", result.tsnetLastStartAt)
        assertEquals("1h01m58s", result.tsnetLastStopAt)
        assertEquals("TAILSCALE", result.tsnetLastTransport)
        assertEquals("WIFI", result.tsnetLastNetwork)
        assertEquals(5, result.tsnetPublishAttempts)
        assertEquals(4, result.tsnetPublishSuccesses)
        assertEquals(1, result.tsnetPublishFailures)
    }

    @Test
    fun `tsnet running state is reported while the node is up`() {
        val tsnet = TsnetLifecycleMonitor.State(
            currentRunning = true,
            lastStartElapsedRealtime = 500L,
            lastStartDurationMs = null,
        )
        assertEquals("Running", snapshot(tsnet = tsnet).tsnetState)
        assertEquals("0s", snapshot(tsnet = tsnet).tsnetLastStartAt)
    }

    @Test
    fun `uptime formatting is relative to boot`() {
        assertEquals("0s", formatUptime(0L))
        assertEquals("42s", formatUptime(42_000L))
        assertEquals("1m02s", formatUptime(62_000L))
        assertEquals("1h02m03s", formatUptime(3_723_000L))
        assertEquals("2h00m00s", formatUptime(7_200_000L))
        assertEquals("0s", formatUptime(-5L))
    }

    @Test
    fun `duration labels render sub second and second durations`() {
        assertEquals("500 ms", durationLabel(500L))
        assertEquals("4.2 s", durationLabel(4_200L))
        assertEquals("1.0 s", durationLabel(1_000L))
    }

    @Test
    fun `snapshot never carries coordinates or payload text`() {
        val result = snapshot()
        val text = result.toString()

        assertFalse("latitude must never appear", text.contains("latitude"))
        assertFalse("longitude must never appear", text.contains("longitude"))
        assertFalse("-23.5 must never appear", text.contains("-23.5"))
        assertFalse("-46.6 must never appear", text.contains("-46.6"))
        assertFalse("recordedAtEpochMs must never appear", text.contains("recordedAtEpochMs"))
        assertFalse("Authorization must never appear", text.contains("Authorization"))
        assertFalse("Bearer must never appear", text.contains("Bearer"))
        assertFalse("token must never appear", text.contains("token"))
    }
}