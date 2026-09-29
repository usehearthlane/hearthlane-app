package org.hearthlane.location

import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [LocationForegroundService]: the service keeps the
 * manifest `location` type and, once started while eligible, enters the
 * foreground and wires the publisher. The service enforces the location-sharing
 * opt-out internally (after startForeground); the full publish loop is covered
 * by [BackgroundLocationPublisherTest]. Real Android eligibility enforcement
 * happens in the framework (not modelled by Robolectric); the app's own gate
 * lives in [LocationFgsGate].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationForegroundServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Polls with real time: the service wiring runs on real IO threads. */
    private fun awaitReady(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("condition not met within deadline", condition())
    }

    private fun setSharingEnabled(enabled: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = org.hearthlane.settings.AppSettings.create(
                context,
                "hearthlane.example",
                scope,
            )
            runBlocking {
                settings.ready.first { it }
                settings.setLocationSharingEnabled(enabled)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `service keeps the manifest location foreground type`() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, LocationForegroundService::class.java),
            0,
        )

        assertTrue(
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0,
        )
    }

    @Test
    fun `start command enters the foreground and wires the publisher`() {
        setSharingEnabled(true)
        val service = Robolectric
            .buildService(
                LocationForegroundService::class.java,
                LocationForegroundService.intent(context, LocationForegroundService.BACKGROUND_INTERVAL_MS),
            )
            .create()

        service.startCommand(0, 1)

        assertNotNull(shadowOf(service.get()).lastForegroundNotification)
        awaitReady { service.get().publisherWired }

        service.destroy()
    }

    @Test
    fun `start command stops cleanly when location sharing is disabled`() {
        // Under Robolectric the app DataStore is shared across tests, so the
        // opt-in state is primed explicitly rather than assumed to be fresh.
        setSharingEnabled(false)
        val service = Robolectric
            .buildService(
                LocationForegroundService::class.java,
                LocationForegroundService.intent(context, LocationForegroundService.BACKGROUND_INTERVAL_MS),
            )
            .create()

        service.startCommand(0, 1)

        awaitReady { service.get().stoppedByOptOut }
        assertTrue(service.get().stoppedByOptOut)

        service.destroy()
    }

    @Test
    fun `publish-now action does not crash while wiring`() {
        setSharingEnabled(true)
        val service = Robolectric
            .buildService(
                LocationForegroundService::class.java,
                LocationForegroundService.intent(context, LocationForegroundService.BACKGROUND_INTERVAL_MS).apply {
                    action = LocationForegroundService.ACTION_PUBLISH_NOW
                },
            )
            .create()

        service.startCommand(0, 1)

        assertNotNull(shadowOf(service.get()).lastForegroundNotification)
        awaitReady { service.get().publisherWired }

        service.destroy()
    }

    @Test
    fun `interval constants keep the check cadence and the adaptive publish policy`() {
        assertEquals(60_000L, LocationForegroundService.BACKGROUND_INTERVAL_MS)
        assertEquals(30_000L, LocationForegroundService.ACTIVE_INTERVAL_MS)
        assertEquals(30_000L, LocationForegroundService.MIN_PUBLISH_INTERVAL_MS)
        assertEquals(5 * 60_000L, LocationForegroundService.MAX_PUBLISH_INTERVAL_MS)
        assertEquals(100.0, LocationForegroundService.DISTANCE_THRESHOLD_METERS, 0.0)
    }

    @Test
    fun `destroy cleans up the exclusive gateway and is idempotent`() {
        setSharingEnabled(true)
        val service = Robolectric
            .buildService(
                LocationForegroundService::class.java,
                LocationForegroundService.intent(context, LocationForegroundService.BACKGROUND_INTERVAL_MS),
            )
            .create()

        service.startCommand(0, 1)
        awaitReady { service.get().publisherWired }

        // onDestroy stops the exclusive location gateway (native stop is
        // swallowed under Robolectric) and must never throw or block the main
        // thread; a repeated destroy must be equally safe.
        service.destroy()
        service.destroy()
    }

    @Test
    fun `interval switches rebuild the session without orphaning the gateway`() {
        setSharingEnabled(true)
        val service = Robolectric
            .buildService(
                LocationForegroundService::class.java,
                LocationForegroundService.intent(context, LocationForegroundService.BACKGROUND_INTERVAL_MS),
            )
            .create()

        service.startCommand(0, 1)
        awaitReady { service.get().publisherWired }

        // Each interval switch runs restartLoop: the old gateway/session must be
        // released before the new one is built, so no tsnet node is left
        // Running without an owner between rebuilds.
        service.withIntent(
            LocationForegroundService.intent(context, LocationForegroundService.ACTIVE_INTERVAL_MS),
        )
        service.startCommand(0, 2)
        awaitReady { service.get().publisherWired }

        service.withIntent(
            LocationForegroundService.intent(context, LocationForegroundService.BACKGROUND_INTERVAL_MS),
        )
        service.startCommand(0, 3)
        awaitReady { service.get().publisherWired }

        service.destroy()
        service.destroy()
    }
}