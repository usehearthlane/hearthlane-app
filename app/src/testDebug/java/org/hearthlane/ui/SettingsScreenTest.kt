package org.hearthlane.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.hearthlane.controller.LocationSharingController
import org.hearthlane.controller.SettingsController
import org.hearthlane.core.frigate.FrigateConnection
import org.hearthlane.core.frigate.TransportKind
import org.hearthlane.location.LocationPermissionSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric/Compose tests for the product-oriented [SettingsScreen]: section
 * structure, server address, connection summary, navigation entry points, the
 * reconfigure confirmation dialog, About information, the absence of
 * infrastructure labels, and both supported locales.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun TestScope.controller(
        baseDomain: String = "hearthlane.example",
        connection: FrigateConnection? = FrigateConnection.Connected(TransportKind.LOCAL, "0.17.1"),
        connecting: Boolean = false,
        autoPlayEventClips: Boolean = true,
        locationSharingEnabled: Boolean = false,
        reset: () -> Unit = {},
        setAutoPlay: suspend (Boolean) -> Unit = {},
        setLocationSharing: suspend (Boolean) -> Unit = {},
    ) = SettingsController(
        baseDomain = MutableStateFlow(baseDomain),
        connection = MutableStateFlow(connection),
        connecting = MutableStateFlow(connecting),
        autoPlayEventClips = MutableStateFlow(autoPlayEventClips),
        locationSharingEnabled = MutableStateFlow(locationSharingEnabled),
        appVersion = "1.2.0",
        appBuild = "3",
        resetRemoteAccessAction = reset,
        setAutoPlayEventClipsAction = setAutoPlay,
        setLocationSharingEnabledAction = setLocationSharing,
        scope = backgroundScope,
    )

    private fun TestScope.locationSharingController(
        desired: Boolean = false,
        foregroundGranted: Boolean = false,
    ) = LocationSharingController(
        scope = backgroundScope,
        sharingEnabled = MutableStateFlow(desired),
        setSharingEnabledAction = {},
        permissionSnapshot = {
            LocationPermissionSnapshot(
                foregroundGranted = foregroundGranted,
                backgroundGranted = foregroundGranted,
                locationEnabled = true,
            )
        },
        backgroundPermissionRequired = true,
        startService = { true },
        stopService = { true },
    )

    private fun TestScope.render(
        controller: SettingsController,
        locationSharingController: LocationSharingController? = null,
        onOpenServerSettings: () -> Unit = {},
        onOpenDiagnostics: () -> Unit = {},
        onReconfigureRemoteAccess: () -> Unit = {},
    ) {
        val sharingController = locationSharingController ?: locationSharingController()
        composeTestRule.setContent {
            SettingsScreen(
                controller = controller,
                locationSharingController = sharingController,
                onOpenServerSettings = onOpenServerSettings,
                onOpenDiagnostics = onOpenDiagnostics,
                onReconfigureRemoteAccess = onReconfigureRemoteAccess,
                onBack = {},
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `renders the product sections`() = runTest {
        val controller = controller()
        render(controller)

        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Server").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remote access").assertIsDisplayed()
        composeTestRule.onNodeWithText("Playback").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Location").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Share my location with family").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Diagnostics").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("About").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `playback section shows the autoplay switch`() = runTest {
        val controller = controller()
        render(controller)

        composeTestRule.onNodeWithText("Play event clips automatically")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(AUTOPLAY_TOGGLE_TAG).assertIsOn()
    }

    @Test
    fun `autoplay switch reflects the persisted value when off`() = runTest {
        val controller = controller(autoPlayEventClips = false)
        render(controller)

        composeTestRule.onNodeWithText("Play event clips automatically")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(AUTOPLAY_TOGGLE_TAG).assertIsOff()
    }

    @Test
    fun `toggling the autoplay switch persists the change`() = runTest {
        val persisted = mutableListOf<Boolean>()
        val controller = controller(setAutoPlay = { persisted.add(it) })
        render(controller)

        composeTestRule.onNodeWithTag(AUTOPLAY_TOGGLE_TAG)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        runCurrent()

        assertEquals(listOf(false), persisted)
    }

    @Test
    fun `shows the server address and the connection summary`() = runTest {
        val controller = controller(baseDomain = "hearthlane.example")
        render(controller)

        composeTestRule.onNodeWithText("Server address").assertIsDisplayed()
        composeTestRule.onNodeWithText("hearthlane.example").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connected").assertIsDisplayed()
    }

    @Test
    fun `shows connecting summary`() = runTest {
        val controller = controller(connecting = true)
        render(controller)

        composeTestRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun `shows unavailable summary without a connection`() = runTest {
        val controller = controller(connection = null, connecting = false)
        render(controller)

        composeTestRule.onNodeWithText("Unavailable").assertIsDisplayed()
    }

    @Test
    fun `shows unavailable summary on a failed connection`() = runTest {
        val controller = controller(
            connection = FrigateConnection.Failed("timeout"),
            connecting = false,
        )
        render(controller)

        composeTestRule.onNodeWithText("Unavailable").assertIsDisplayed()
    }

    @Test
    fun `connection status transitions are reflected without reopening`() = runTest {
        val connection = MutableStateFlow<FrigateConnection?>(
            FrigateConnection.Connected(TransportKind.LOCAL, "0.17.1"),
        )
        val connecting = MutableStateFlow(false)
        val controller = SettingsController(
            baseDomain = MutableStateFlow("hearthlane.example"),
            connection = connection,
            connecting = connecting,
            autoPlayEventClips = MutableStateFlow(true),
            locationSharingEnabled = MutableStateFlow(false),
            appVersion = "1.2.0",
            appBuild = "3",
            resetRemoteAccessAction = {},
            setAutoPlayEventClipsAction = {},
            setLocationSharingEnabledAction = {},
            scope = backgroundScope,
        )
        render(controller)

        composeTestRule.onNodeWithText("Connected").assertIsDisplayed()

        connecting.value = true
        runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Connecting…").assertIsDisplayed()

        connecting.value = false
        connection.value = null
        runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Unavailable").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun `pt-br connecting summary renders in portuguese`() = runTest {
        val controller = controller(connecting = true)
        render(controller)

        composeTestRule.onNodeWithText("Conectando…").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun `pt-br unavailable summary renders in portuguese`() = runTest {
        val controller = controller(connection = null)
        render(controller)

        composeTestRule.onNodeWithText("Indisponível").assertIsDisplayed()
    }

    @Test
    fun `tapping the server address invokes onOpenServerSettings`() = runTest {
        var opened = 0
        val controller = controller()
        render(controller, onOpenServerSettings = { opened++ })

        composeTestRule.onNodeWithText("Server address").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, opened)
    }

    @Test
    fun `tapping diagnostics invokes onOpenDiagnostics`() = runTest {
        var opened = 0
        val controller = controller()
        render(controller, onOpenDiagnostics = { opened++ })

        composeTestRule.onNodeWithText("Diagnostics").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, opened)
    }

    @Test
    fun `reconfigure remote access asks for confirmation before running`() = runTest {
        var reconfigured = 0
        val controller = controller(reset = { reconfigured++ })
        render(controller, onReconfigureRemoteAccess = { controller.resetRemoteAccess() })

        composeTestRule.onNodeWithText("Reconfigure remote access").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Reconfigure remote access?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reconfigure").performClick()
        composeTestRule.waitForIdle()

        assertEquals("the reconfigure action must run after confirmation", 1, reconfigured)
    }

    @Test
    fun `cancel dismisses the reconfigure dialog without running`() = runTest {
        var reconfigured = 0
        val controller = controller()
        render(controller, onReconfigureRemoteAccess = { reconfigured++ })

        composeTestRule.onNodeWithText("Reconfigure remote access").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, reconfigured)
        composeTestRule.onNodeWithText("Reconfigure remote access?").assertDoesNotExist()
    }

    @Test
    fun `about shows the app name version and build`() = runTest {
        val controller = controller()
        render(controller)

        composeTestRule.onNodeWithText("Hearthlane", substring = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Version 1.2.0").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Build 3").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `infrastructure labels are not exposed`() = runTest {
        val controller = controller()
        render(controller)

        composeTestRule.onNodeWithText("Tailscale").assertDoesNotExist()
        composeTestRule.onNodeWithText("Frigate").assertDoesNotExist()
        composeTestRule.onNodeWithText("LOCAL").assertDoesNotExist()
        composeTestRule.onNodeWithText("TAILSCALE").assertDoesNotExist()
        composeTestRule.onNodeWithText("Node hostname").assertDoesNotExist()
    }

    @Test
    fun `location sharing section is present`() = runTest {
        val controller = controller(locationSharingEnabled = false)
        render(controller)

        composeTestRule.onNodeWithText("Location")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Share my location with family")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun `pt-br locale renders the product sections`() = runTest {
        val controller = controller()
        render(controller)

        composeTestRule.onNodeWithText("Configurações").assertIsDisplayed()
        composeTestRule.onNodeWithText("Servidor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Endereço do servidor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Acesso remoto").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Reconfigurar acesso remoto").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Reprodução").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Reproduzir clipes automaticamente").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Sobre").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Conectado").performScrollTo().assertIsDisplayed()
    }
    @Test
    @Config(qualifiers = "pt-rBR")
    fun `pt-br reconfigure dialog uses product language`() = runTest {
        val controller = controller()
        render(controller)

        composeTestRule.onNodeWithText("Reconfigurar acesso remoto").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Reconfigurar acesso remoto?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reconfigurar").assertIsDisplayed()
    }
}
