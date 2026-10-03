package org.hearthlane.location

import android.location.LocationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * [AcquisitionController] tests over injected seams: provider registration,
 * GPS one-shot execution, permission-aware behavior, failure safety and the
 * single Fix path into the publisher (exactly one delivery per fix, GPS
 * included).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcquisitionControllerTest {

    private var timeMs = 0L
    private var finePermission = true
    private var networkEnabled = true
    private var passiveEnabled = true
    private var gpsEnabled = true
    private var publishable = true
    private var initialFixResult: LocationReadResult =
        LocationReadResult(LocationReadStatus.NO_POSITION, message = "no initial fix")
    private var gpsResult: LocationReadResult =
        LocationReadResult(LocationReadStatus.NO_POSITION, message = "no gps fix")

    private val registeredProviders = mutableListOf<String>()
    private val deliveredFixes = mutableListOf<LocationSample>()
    private val events = mutableListOf<LocationEvent>()
    private var unregisterCount = 0
    private var gpsCancelled = 0

    @Before
    fun setUp() {
        AcquisitionDiagnosticsMonitor.resetForTest()
        timeMs = 0L
        finePermission = true
        networkEnabled = true
        passiveEnabled = true
        gpsEnabled = true
        publishable = true
        initialFixResult = LocationReadResult(LocationReadStatus.NO_POSITION, message = "no initial fix")
        gpsResult = LocationReadResult(LocationReadStatus.NO_POSITION, message = "no gps fix")
        registeredProviders.clear()
        deliveredFixes.clear()
        events.clear()
        unregisterCount = 0
        gpsCancelled = 0
    }

    private fun sample(provider: String, latitude: Double, accuracy: Float = 40f) = LocationSample(
        provider = provider,
        latitude = latitude,
        longitude = 0.0,
        accuracyMeters = accuracy,
        recordedAtWallClockMs = 100L,
        recordedAtElapsedNanos = 0L,
        ageMs = 0L,
        acquisitionMs = 0L,
        fromLastKnown = false,
    )

    private fun TestScope.controller(
        evaluationIntervalMs: Long = 600_000L,
    ) = AcquisitionController(
        policy = AcquisitionPolicy { a, b -> abs(a.latitude - b.latitude) * 111_320.0 },
        scope = backgroundScope,
        hasFinePermission = { finePermission },
        hasPublishableFix = { publishable },
        isProviderEnabled = { provider ->
            when (provider) {
                LocationManager.NETWORK_PROVIDER -> networkEnabled
                LocationManager.PASSIVE_PROVIDER -> passiveEnabled
                else -> gpsEnabled
            }
        },
        registerUpdates = { provider, _, _, _ -> registeredProviders += provider },
        unregisterUpdates = { unregisterCount++ },
        requestCurrent = { provider, _ ->
            if (provider == LocationManager.GPS_PROVIDER) gpsResult else initialFixResult
        },
        onFix = { deliveredFixes += it },
        emitEvent = { events += it },
        clockMs = { timeMs },
        evaluationIntervalMs = evaluationIntervalMs,
    )

    private fun TestScope.runGpsJob() {
        runCurrent()
        timeMs = 20_000L
        runCurrent()
        timeMs = 0L
    }

    @Test
    fun `start registers network and passive once`() = runTest {
        val controller = controller()
        controller.start()
        runCurrent()

        assertEquals(listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER), registeredProviders)
        assertTrue(events.any { it.name == LocationEventLog.EVENT_ACQUISITION_START })
        assertTrue(events.any { it.name == LocationEventLog.EVENT_PROVIDER_REGISTERED })
        controller.stop()
    }

    @Test
    fun `stop unregisters listeners`() = runTest {
        val controller = controller()
        controller.start()
        runCurrent()
        assertEquals(2, registeredProviders.size)

        controller.stop()
        assertEquals("both listeners unregistered", 2, unregisterCount)
        assertTrue(events.any { it.name == LocationEventLog.EVENT_ACQUISITION_STOP })
    }

    @Test
    fun `restart registers fresh listeners without duplication`() = runTest {
        val controller = controller()
        controller.start()
        runCurrent()
        controller.stop()
        controller.start()
        runCurrent()

        assertEquals(
            "two sessions, two registrations each, never duplicated within a session",
            listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ),
            registeredProviders,
        )
        assertEquals("the first session's listeners were unregistered", 2, unregisterCount)
        controller.stop()
    }

    @Test
    fun `coarse permission never registers gps and still keeps network and passive`() = runTest {
        finePermission = false
        val controller = controller()
        controller.start()
        runCurrent()

        assertTrue("no permanent GPS listener, ever", registeredProviders.none { it == LocationManager.GPS_PROVIDER })
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER), registeredProviders)
        controller.stop()
    }

    @Test
    fun `fine permission still never registers a permanent gps listener`() = runTest {
        val controller = controller()
        controller.start()
        runCurrent()

        assertTrue(registeredProviders.none { it == LocationManager.GPS_PROVIDER })
        controller.stop()
    }

    @Test
    fun `disabled provider is reported and skipped`() = runTest {
        networkEnabled = false
        val controller = controller()
        controller.start()
        runCurrent()

        assertEquals(listOf(LocationManager.PASSIVE_PROVIDER), registeredProviders)
        assertTrue(
            events.any {
                it.name == LocationEventLog.EVENT_PROVIDER_DISABLED &&
                    it.fields.firstOrNull { f -> f.first == "provider" }?.second == LocationManager.NETWORK_PROVIDER
            },
        )
        controller.stop()
    }

    @Test
    fun `security exception during registration is observed and skipped`() = runTest {
        var securityThrown = false
        val controller = AcquisitionController(
            policy = AcquisitionPolicy { a, b -> abs(a.latitude - b.latitude) * 111_320.0 },
            scope = backgroundScope,
            hasFinePermission = { finePermission },
            hasPublishableFix = { publishable },
            isProviderEnabled = { true },
            registerUpdates = { _, _, _, _ ->
                if (!securityThrown) {
                    securityThrown = true
                    throw SecurityException("permission denied")
                }
                registeredProviders += "later-provider"
            },
            unregisterUpdates = { unregisterCount++ },
            requestCurrent = { provider, _ -> if (provider == LocationManager.GPS_PROVIDER) gpsResult else initialFixResult },
            onFix = { deliveredFixes += it },
            emitEvent = { events += it },
            clockMs = { timeMs },
            evaluationIntervalMs = 600_000L,
        )
        controller.start()
        runCurrent()

        assertTrue(events.any { it.name == LocationEventLog.EVENT_PROVIDER_REGISTRATION_FAILED })
        controller.stop()
    }

    @Test
    fun `a delivered fix reaches the publisher exactly once`() = runTest {
        val controller = controller()
        controller.start()
        runCurrent()

        controller.onListenerFix(
            LocationManager.NETWORK_PROVIDER,
            newLocation(LocationManager.NETWORK_PROVIDER, 1.0, 40f),
        )
        runCurrent()

        assertEquals(1, deliveredFixes.size)
        assertEquals(LocationManager.NETWORK_PROVIDER, deliveredFixes.single().provider)
        controller.stop()
    }

    @Test
    fun `gps one-shot timeout counts a failure and reports it`() = runTest {
        gpsResult = LocationReadResult(LocationReadStatus.TIMEOUT, message = "no fix within 20000ms")
        publishable = false // startup context: no publishable fix -> escalation
        val controller = controller()
        controller.start()
        runCurrent() // initial fix: no position -> startup escalation -> GPS
        runGpsJob()

        assertTrue(events.any { it.name == LocationEventLog.EVENT_GPS_REQUEST })
        assertTrue(events.any { it.name == LocationEventLog.EVENT_GPS_TIMEOUT })
        val monitor = AcquisitionDiagnosticsMonitor.state.value
        assertEquals(1, monitor.gpsRequestCount)
        assertEquals(1, monitor.gpsFailureCount)
        assertFalse(monitor.gpsInFlight)
        assertTrue("no fix delivered on a timeout", deliveredFixes.isEmpty())
        controller.stop()
    }

    @Test
    fun `gps fix flows through the same publisher fix path`() = runTest {
        val gpsFix = sample(LocationManager.GPS_PROVIDER, 1.5, accuracy = 10f)
        gpsResult = LocationReadResult(LocationReadStatus.SUCCESS, sample = gpsFix)
        publishable = false // startup context: no publishable fix -> escalation
        val controller = controller()
        controller.start()
        runCurrent()
        runGpsJob()

        assertTrue(events.any { it.name == LocationEventLog.EVENT_GPS_RESULT })
        assertEquals(1, deliveredFixes.size)
        assertEquals(LocationManager.GPS_PROVIDER, deliveredFixes.single().provider)
        assertEquals(10f, deliveredFixes.single().accuracyMeters)
        val monitor = AcquisitionDiagnosticsMonitor.state.value
        assertEquals("SUCCESS", monitor.lastGpsResult)
        controller.stop()
    }

    @Test
    fun `gps one-shot cancellation is clean`() = runTest {
        gpsResult = LocationReadResult(LocationReadStatus.NO_POSITION, message = "no fix")
        publishable = false // startup context: no publishable fix -> escalation
        val controller = AcquisitionController(
            policy = AcquisitionPolicy { a, b -> abs(a.latitude - b.latitude) * 111_320.0 },
            scope = backgroundScope,
            hasFinePermission = { finePermission },
            hasPublishableFix = { publishable },
            isProviderEnabled = { true },
            registerUpdates = { provider, _, _, _ -> registeredProviders += provider },
            unregisterUpdates = { unregisterCount++ },
            requestCurrent = { provider, _ ->
                if (provider == LocationManager.GPS_PROVIDER) {
                    suspendCancellableCoroutine<LocationReadResult> { cont ->
                        cont.invokeOnCancellation { gpsCancelled++ }
                    }
                } else {
                    initialFixResult
                }
            },
            onFix = { deliveredFixes += it },
            emitEvent = { events += it },
            clockMs = { timeMs },
            evaluationIntervalMs = 600_000L,
        )
        controller.start()
        runCurrent()

        controller.stop()
        runCurrent()

        assertEquals("the in-flight GPS one-shot was cancelled", 1, gpsCancelled)
        assertFalse(AcquisitionDiagnosticsMonitor.state.value.gpsInFlight)
    }

    @Test
    fun `map active triggers a gps refinement edge`() = runTest {
        val controller = controller()
        controller.start()
        runCurrent()
        events.clear()

        controller.onModeChanged(PublisherMode.MAP_ACTIVE)
        runCurrent()
        timeMs = 20_000L
        runCurrent()

        assertTrue(events.any { it.name == LocationEventLog.EVENT_MODE_CHANGED })
        assertTrue(
            events.any {
                it.name == LocationEventLog.EVENT_GPS_REQUEST &&
                    it.fields.firstOrNull { f -> f.first == "reason" }?.second == GpsRequestReason.MAP_ACTIVE.name
            },
        )
        controller.stop()
    }

    @Test
    fun `no coordinate ever appears in acquisition events`() = runTest {
        val controller = controller()
        controller.start()
        runCurrent()
        controller.onListenerFix(
            LocationManager.NETWORK_PROVIDER,
            newLocation(LocationManager.NETWORK_PROVIDER, 1.0, 40f),
        )
        runCurrent()
        controller.stop()

        for (event in events) {
            val text = LocationEventLog.format(event)
            assertFalse("no latitude in $text", text.contains("latitude"))
            assertFalse("no longitude in $text", text.contains("longitude"))
            assertFalse("no coordinates in $text", text.contains("-23."))
        }
    }

    private fun newLocation(provider: String, latitude: Double, accuracy: Float) =
        android.location.Location(provider).apply {
            this.latitude = latitude
            this.longitude = 0.0
            this.accuracy = accuracy
            time = 100L
        }
}