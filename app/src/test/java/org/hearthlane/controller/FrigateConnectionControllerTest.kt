package org.hearthlane.controller

import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import org.hearthlane.core.frigate.FrigateConnection
import org.hearthlane.core.frigate.TransportKind
import org.hearthlane.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for [FrigateConnectionController], focusing on network transitions that
 * fall back to Tailscale and on the propagation of the enrollment-required
 * state to the UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrigateConnectionControllerTest {

    private fun dataStoreFile(): File {
        return File.createTempFile("app_settings_test", ".preferences_pb").apply {
            deleteOnExit()
        }
    }

    private suspend fun TestScope.createSettings(): AppSettings {
        val file = dataStoreFile()
        return AppSettings.createForTest(
            dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = backgroundScope,
            ) { file },
            defaultBaseDomain = "hearthlane.example",
            scope = backgroundScope,
        ).also { it.ready.first { ready -> ready } }
    }

    private suspend fun createController(
        scope: TestScope,
        connector: suspend (String) -> FrigateConnection,
    ): FrigateConnectionController {
        val connectivityManager = ApplicationProvider
            .getApplicationContext<android.app.Application>()
            .getSystemService(ConnectivityManager::class.java)
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return FrigateConnectionController(
            gateway = org.hearthlane.test.FakeTsnetGateway(),
            settings = scope.createSettings(),
            connectivityManager = connectivityManager,
            scope = scope,
            connector = connector,
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `LOCAL then remote with enrolled node connects TAILSCALE without enrollment UI`() = runTest {
        val connector = mutableListOf(
            { _: String -> FrigateConnection.Connected(TransportKind.LOCAL, "0.17.1") },
            { _: String -> FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1") },
        )
        var callIndex = 0
        val controller = createController(this) { url ->
            connector[callIndex++](url)
        }

        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertEquals(
            FrigateConnection.Connected(TransportKind.LOCAL, "0.17.1"),
            controller.connection.value,
        )
        assertFalse("LOCAL success must not flag enrollment", authRequired(controller))

        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertEquals(
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1"),
            controller.connection.value,
        )
        assertFalse("TAILSCALE success must not flag enrollment", authRequired(controller))
    }

    @Test
    fun `LOCAL then remote with unenrolled node exposes AuthRequired`() = runTest {
        val controller = createController(this) {
            FrigateConnection.Failed(
                error = "Tailscale requires authentication",
                authUrl = "https://login.tailscale.com/a/abc123",
                authRequired = true,
            )
        }

        controller.connect(restartPlayback = false)
        advanceUntilIdle()

        val connection = controller.connection.value
        assertTrue(connection is FrigateConnection.Failed)
        val failed = connection as FrigateConnection.Failed
        assertTrue(failed.authRequired)
        assertEquals("https://login.tailscale.com/a/abc123", failed.authUrl)
    }

    @Test
    fun `AuthRequired then enrollment completed retries to CONNECTED_TAILSCALE`() = runTest {
        val results = mutableListOf<
            suspend (String) -> FrigateConnection
        >()
        results.add {
            FrigateConnection.Failed(
                error = "Tailscale requires authentication",
                authUrl = "https://login.tailscale.com/a/abc123",
                authRequired = true,
            )
        }
        results.add {
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1")
        }
        var callIndex = 0
        val controller = createController(this) { url -> results[callIndex++](url) }

        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertTrue(authRequired(controller))

        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertEquals(
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1"),
            controller.connection.value,
        )
        assertFalse(authRequired(controller))
    }

    @Test
    fun `enrollment failure stays in controlled Failed state without loop`() = runTest {
        var calls = 0
        val controller = createController(this) {
            calls++
            FrigateConnection.Failed(
                error = "Tailscale requires authentication",
                authUrl = "https://login.tailscale.com/a/abc123",
                authRequired = true,
            )
        }

        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(authRequired(controller))

        // A second connect is still a single explicit call; the controller does
        // not retry on its own.
        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertTrue(authRequired(controller))
    }

    @Test
    fun `returning to LAN before enrollment completes restores LOCAL`() = runTest {
        val results = mutableListOf<
            suspend (String) -> FrigateConnection
        >()
        results.add {
            FrigateConnection.Failed(
                error = "Tailscale requires authentication",
                authUrl = "https://login.tailscale.com/a/abc123",
                authRequired = true,
            )
        }
        results.add {
            FrigateConnection.Connected(TransportKind.LOCAL, "0.17.1")
        }
        var callIndex = 0
        val controller = createController(this) { url -> results[callIndex++](url) }

        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertTrue(authRequired(controller))

        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertEquals(
            FrigateConnection.Connected(TransportKind.LOCAL, "0.17.1"),
            controller.connection.value,
        )
        assertFalse(authRequired(controller))
    }

    @Test
    fun `lastError records the failure message but not the authUrl`() = runTest {
        val controller = createController(this) {
            FrigateConnection.Failed(
                error = "Tailscale requires authentication",
                authUrl = "https://login.tailscale.com/a/abc123",
                authRequired = true,
            )
        }

        controller.connect(restartPlayback = false)
        advanceUntilIdle()

        val lastError = controller.lastError.value
        assertTrue(lastError != null)
        assertTrue(
            "diagnostic error may mention enrollment but must not contain the URL",
            lastError!!.contains("authentication") || lastError.contains("enrollment") || lastError.contains("Tailscale"),
        )
        assertFalse(
            "auth URL must never leak into diagnostics",
            lastError.contains("login.tailscale.com"),
        )
    }

    @Test
    fun `resetTailscale clears the node identity and the shared session state`() = runTest {
        val gateway = org.hearthlane.test.FakeTsnetGateway()
        val connectivityManager = ApplicationProvider
            .getApplicationContext<android.app.Application>()
            .getSystemService(ConnectivityManager::class.java)
        val dispatcher = StandardTestDispatcher(this.testScheduler)
        val controller = FrigateConnectionController(
            gateway = gateway,
            settings = createSettings(),
            connectivityManager = connectivityManager,
            scope = this,
            connector = { FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1") },
            ioDispatcher = dispatcher,
        )

        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertEquals(
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1"),
            controller.connection.value,
        )

        controller.resetTailscale()
        advanceUntilIdle()

        assertEquals("the administrator reset must clear the node identity", 1, gateway.resetCount)
        assertNull("the shared connection state must be cleared after a reset", controller.connection.value)
        assertNull("the probed transport must be cleared after a reset", controller.lastProbedTransport.value)
        assertNull("the recorded error must be cleared after a reset", controller.lastError.value)
    }

    @Test
    fun `cancellation during connect resets _connecting`() = runTest {
        val dispatcher = StandardTestDispatcher(this.testScheduler)
        val connectivityManager = ApplicationProvider
            .getApplicationContext<android.app.Application>()
            .getSystemService(ConnectivityManager::class.java)
        val gate = kotlinx.coroutines.CompletableDeferred<FrigateConnection>()
        val controller = FrigateConnectionController(
            gateway = org.hearthlane.test.FakeTsnetGateway(),
            settings = createSettings(),
            connectivityManager = connectivityManager,
            scope = this,
            connector = { gate.await() },
            ioDispatcher = dispatcher,
        )

        controller.connect(restartPlayback = false)
        // The connect coroutine is suspended on the gate; _connecting is true.
        assertTrue("_connecting must be true while connect is in flight", controller.connecting.value)

        // Dispatch the coroutine so it enters withContext and suspends on gate.await().
        advanceUntilIdle()

        // Cancel the scope's children to simulate cancellation.
        coroutineContext[kotlinx.coroutines.Job]!!.cancelChildren()
        advanceUntilIdle()

        assertFalse(
            "_connecting must be reset even after cancellation",
            controller.connecting.value,
        )
    }

    @Test
    fun `a re-probe requested during a connect retries once when it fails`() = runTest {
        val results = mutableListOf(
            { _: String -> FrigateConnection.Failed(error = "boom") },
            { _: String -> FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1") },
        )
        var index = 0
        val controller = createController(this) { url -> results[index++](url) }

        controller.connect(restartPlayback = false)
        // A re-probe (network callback / onForeground) arrives while the first
        // connect is still in flight: it must not be lost.
        controller.connect(restartPlayback = false)
        advanceUntilIdle()

        assertEquals("the pending re-probe must run one retry", 2, index)
        assertEquals(
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1"),
            controller.connection.value,
        )
    }

    @Test
    fun `a re-probe requested during a successful connect is consumed without churn`() = runTest {
        var calls = 0
        val controller = createController(this) {
            calls++
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1")
        }

        controller.connect(restartPlayback = false)
        controller.connect(restartPlayback = false)
        advanceUntilIdle()

        assertEquals("a successful connect consumes the pending re-probe", 1, calls)
        assertEquals(
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1"),
            controller.connection.value,
        )
    }

    @Test
    fun `multiple re-probes during a connect coalesce into one retry`() = runTest {
        val results = mutableListOf(
            { _: String -> FrigateConnection.Failed(error = "boom") },
            { _: String -> FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1") },
        )
        var index = 0
        val controller = createController(this) { url -> results[index++](url) }

        controller.connect(restartPlayback = false)
        controller.connect(restartPlayback = false)
        controller.connect(restartPlayback = false)
        controller.connect(restartPlayback = false)
        advanceUntilIdle()

        assertEquals("every pending request coalesces into a single retry", 2, index)
        assertEquals(
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1"),
            controller.connection.value,
        )
    }

    @Test
    fun `awaitConnectedAfterResume does not resolve on a stale retained connection`() = runTest {
        val controller = createController(this) {
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1")
        }
        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertEquals(
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1"),
            controller.connection.value,
        )
        val staleGen = controller.connectGeneration.value

        // A resume begins at this generation: awaiting must NOT return on the
        // stale Connected value retained through ON_STOP.
        val resumed = CompletableDeferred<Unit>()
        val awaitJob = launch {
            controller.awaitConnectedAfterResume(staleGen)
            resumed.complete(Unit)
        }
        advanceUntilIdle()
        assertFalse("a stale connection must not satisfy the resume await", resumed.isCompleted)

        // The resume connect completes after the resume -> the await resolves.
        controller.connect(restartPlayback = false)
        advanceUntilIdle()
        assertTrue("await resolves once a connect completes after the resume", resumed.isCompleted)
        awaitJob.join()
    }

    @Test
    fun `awaitConnectedAfterResume retries a failed resume connect once`() = runTest {
        val results = mutableListOf(
            { _: String -> FrigateConnection.Failed(error = "boom") },
            { _: String -> FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1") },
        )
        var index = 0
        val controller = createController(this) { url -> results[index++](url) }

        // The onForeground resume connect is launched and fails.
        controller.connect(restartPlayback = false)
        val genAtResume = controller.connectGeneration.value
        val resumed = CompletableDeferred<Unit>()
        val awaitJob = launch {
            controller.awaitConnectedAfterResume(genAtResume)
            resumed.complete(Unit)
        }
        advanceUntilIdle()

        assertTrue("the failed resume connect must be retried by the await", resumed.isCompleted)
        assertEquals("the retry reaches Connected", 2, index)
        assertEquals(
            FrigateConnection.Connected(TransportKind.TAILSCALE, "0.17.1"),
            controller.connection.value,
        )
        awaitJob.join()
    }

    private fun authRequired(controller: FrigateConnectionController): Boolean {
        val failed = controller.connection.value as? FrigateConnection.Failed
        return failed?.authRequired == true
    }
}
