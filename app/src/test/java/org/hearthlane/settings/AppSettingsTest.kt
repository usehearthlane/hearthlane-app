package org.hearthlane.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsTest {

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> {
        val file = File.createTempFile("app_settings_test", ".preferences_pb")
        file.deleteOnExit()
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    /** DataStore whose next edit can be held open, to prove publish-vs-persist order. */
    private class ControlledDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> get() = state
        var blockNext = false
        val editStarted = CompletableDeferred<Unit>()
        val releaseEdit = CompletableDeferred<Unit>()

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (blockNext) {
                blockNext = false
                editStarted.complete(Unit)
                releaseEdit.await()
            }
            val result = transform(state.value)
            state.value = result
            return result
        }

        fun current(): Preferences = state.value
    }

    @Test
    fun `seeds the build default base domain on first run`() = runTest {
        val settings = AppSettings.createForTest(
            dataStore = dataStore(backgroundScope),
            defaultBaseDomain = "hearthlane.example",
            scope = backgroundScope,
        )

        settings.ready.first { it }

        assertEquals("hearthlane.example", settings.baseDomain.value)
    }

    @Test
    fun `derives frigate and relay endpoints from the base domain`() = runTest {
        val settings = AppSettings.createForTest(
            dataStore = dataStore(backgroundScope),
            defaultBaseDomain = "hearthlane.example",
            scope = backgroundScope,
        )

        settings.ready.first { it }

        assertEquals("http://frigate.hearthlane.example", settings.frigateBaseUrl.value)
        assertEquals("http://relay.hearthlane.example", settings.relayBaseUrl.value)
    }

    @Test
    fun `derives a UAT relay endpoint from the configured relay subdomain`() = runTest {
        val settings = AppSettings.createForTest(
            dataStore = dataStore(backgroundScope),
            defaultBaseDomain = "hearthlane.omni.corp",
            scope = backgroundScope,
            relaySubdomain = "relay-uat",
        )

        settings.ready.first { it }

        // Frigate stays shared with PROD; only the relay label is the UAT one.
        assertEquals("http://frigate.hearthlane.omni.corp", settings.frigateBaseUrl.value)
        assertEquals("http://relay-uat.hearthlane.omni.corp", settings.relayBaseUrl.value)
    }

    @Test
    fun `UAT relay subdomain survives a base domain edit and re-derivation`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = AppSettings.createForTest(
            dataStore = store,
            defaultBaseDomain = "hearthlane.example",
            scope = backgroundScope,
            relaySubdomain = "relay-uat",
        )
        settings.ready.first { it }

        settings.setBaseDomain("omni.corp")

        val reloaded = AppSettings.createForTest(
            dataStore = store,
            defaultBaseDomain = "hearthlane.example",
            scope = backgroundScope,
            relaySubdomain = "relay-uat",
        )
        reloaded.ready.first { it }
        assertEquals("http://frigate.omni.corp", reloaded.frigateBaseUrl.value)
        assertEquals("http://relay-uat.omni.corp", reloaded.relayBaseUrl.value)
    }

    @Test
    fun `persists an edited base domain across instances and re-derives endpoints`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }

        settings.setBaseDomain("omni.corp")

        val reloaded = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloaded.ready.first { it }
        assertEquals("omni.corp", reloaded.baseDomain.value)
        assertEquals("http://frigate.omni.corp", reloaded.frigateBaseUrl.value)
        assertEquals("http://relay.omni.corp", reloaded.relayBaseUrl.value)
    }

    @Test
    fun `invalid base domain is stored as blank`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }

        settings.setBaseDomain("https://host/path with spaces")

        assertEquals("", settings.baseDomain.value)
        assertEquals("", settings.frigateBaseUrl.value)
        assertEquals("", settings.relayBaseUrl.value)
    }

    @Test
    fun `migrates a legacy frigate URL into the base domain deterministically`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit {
            it[LEGACY_FRIGATE_URL] = "http://frigate.hearthlane.example"
            it[SETUP_COMPLETE] = true
        }

        val settings = AppSettings.createForTest(store, "default.example", backgroundScope)
        settings.ready.first { it }

        assertEquals("hearthlane.example", settings.baseDomain.value)
        assertEquals("http://frigate.hearthlane.example", settings.frigateBaseUrl.value)
        assertEquals("http://relay.hearthlane.example", settings.relayBaseUrl.value)
        assertEquals("the migrated setup must stay complete", true, settings.setupComplete.value)
    }

    @Test
    fun `non derivable legacy URL never downgrades a completed onboarding`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit {
            it[LEGACY_FRIGATE_URL] = "http://192.168.1.50:5000"
            it[SETUP_COMPLETE] = true
        }

        val settings = AppSettings.createForTest(store, "default.example", backgroundScope)
        settings.ready.first { it }

        assertEquals("an unsafe legacy value must not be guessed", "", settings.baseDomain.value)
        assertEquals("", settings.frigateBaseUrl.value)
        assertEquals(
            "a completed onboarding must never be reopened by legacy config",
            true,
            settings.setupComplete.value,
        )
    }

    @Test
    fun `non derivable legacy URL leaves an incomplete onboarding pending`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit {
            it[LEGACY_FRIGATE_URL] = "http://192.168.1.50:5000"
        }

        val settings = AppSettings.createForTest(store, "default.example", backgroundScope)
        settings.ready.first { it }

        assertEquals(
            "an onboarding that was never completed stays pending (Setup shows)",
            false,
            settings.setupComplete.value,
        )
    }

    @Test
    fun `completed onboarding survives a legacy URL across two process recreations`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit {
            it[LEGACY_FRIGATE_URL] = "http://192.168.1.50:5000"
            it[SETUP_COMPLETE] = true
        }

        val first = AppSettings.createForTest(store, "default.example", backgroundScope)
        first.ready.first { it }
        assertEquals(true, first.setupComplete.value)

        // Process B: the same storage must still report onboarding complete.
        val second = AppSettings.createForTest(store, "default.example", backgroundScope)
        second.ready.first { it }
        assertEquals(
            "a completed onboarding must persist across recreations even with a legacy key",
            true,
            second.setupComplete.value,
        )
    }

    @Test
    fun `setup complete stays true across repeated initializations`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit { it[SETUP_COMPLETE] = true }

        repeat(3) {
            val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
            settings.ready.first { it }
            assertEquals("initialization #$it must keep onboarding complete", true, settings.setupComplete.value)
        }
    }

    @Test
    fun `migration prefers an already persisted base domain over legacy keys`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit {
            it[BASE_DOMAIN] = "omni.corp"
            it[LEGACY_FRIGATE_URL] = "http://frigate.hearthlane.example"
            it[SETUP_COMPLETE] = true
        }

        val settings = AppSettings.createForTest(store, "default.example", backgroundScope)
        settings.ready.first { it }

        assertEquals("omni.corp", settings.baseDomain.value)
        assertEquals(true, settings.setupComplete.value)
    }

    @Test
    fun `node suffix is generated once and stable across instances`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }

        val suffix = settings.nodeSuffix.value
        assertTrue("suffix must be 8 hex chars, was \"$suffix\"", suffix.matches(Regex("[0-9a-f]{8}")))
        assertTrue("hostname must carry the suffix", AppSettings.nodeHostname(suffix).startsWith("hearthlane-"))

        val reloaded = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloaded.ready.first { it }
        assertEquals("the same installation must keep its node hostname", suffix, reloaded.nodeSuffix.value)
    }

    @Test
    fun `generated suffixes are unique`() {
        val suffixes = (1..1000).map { AppSettings.generateSuffix() }.toSet()
        assertEquals(1000, suffixes.size)
    }

    @Test
    fun `setup complete defaults to false on first run`() = runTest {
        val settings = AppSettings.createForTest(
            dataStore = dataStore(backgroundScope),
            defaultBaseDomain = "hearthlane.example",
            scope = backgroundScope,
        )

        settings.ready.first { it }

        assertEquals(
            "a fresh installation must be gated on the initial setup",
            false,
            settings.setupComplete.value,
        )
    }

    @Test
    fun `setup complete is persisted across instances`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }
        assertEquals(false, settings.setupComplete.value)

        settings.setSetupComplete(true)

        val reloaded = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloaded.ready.first { it }
        assertEquals("setup must stay complete across app restarts", true, reloaded.setupComplete.value)
    }

    @Test
    fun `explicitly stored setup complete true is honored`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit { it[SETUP_COMPLETE] = true }

        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }

        assertEquals(true, settings.setupComplete.value)
    }

    @Test
    fun `explicitly stored setup complete false is honored`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit { it[SETUP_COMPLETE] = false }

        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }

        assertEquals(false, settings.setupComplete.value)
    }

    @Test
    fun `base domain and setup flag round-trip together`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }

        settings.setBaseDomain("omni.corp")
        settings.setSetupComplete(true)

        val reloaded = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloaded.ready.first { it }
        assertEquals("omni.corp", reloaded.baseDomain.value)
        assertEquals(true, reloaded.setupComplete.value)
    }

    @Test
    fun `auto-play event clips defaults to true on first run`() = runTest {
        val settings = AppSettings.createForTest(
            dataStore = dataStore(backgroundScope),
            defaultBaseDomain = "hearthlane.example",
            scope = backgroundScope,
        )

        settings.ready.first { it }

        assertEquals(
            "auto-play must keep the existing autoplay behavior for new installs",
            true,
            settings.autoPlayEventClips.value,
        )
    }

    @Test
    fun `auto-play preference is persisted across instances`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }
        assertEquals(true, settings.autoPlayEventClips.value)

        settings.setAutoPlayEventClips(false)

        val reloaded = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloaded.ready.first { it }
        assertEquals(false, reloaded.autoPlayEventClips.value)

        reloaded.setAutoPlayEventClips(true)

        val reloadedAgain = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloadedAgain.ready.first { it }
        assertEquals(true, reloadedAgain.autoPlayEventClips.value)
    }

    @Test
    fun `auto-play preference does not touch other settings`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }

        settings.setBaseDomain("omni.corp")
        settings.setAutoPlayEventClips(false)

        val reloaded = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloaded.ready.first { it }
        assertEquals("omni.corp", reloaded.baseDomain.value)
        assertEquals(false, reloaded.autoPlayEventClips.value)
        assertEquals(false, reloaded.setupComplete.value)
    }

    @Test
    fun `legacy relay token is removed from the store safely`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit {
            it[LEGACY_RELAY_TOKEN] = "s3cret"
            it[SETUP_COMPLETE] = true
        }

        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }

        assertEquals("other preferences survive the token cleanup", true, settings.setupComplete.value)
        val reloaded = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloaded.ready.first { it }
        assertEquals(
            "the dead token must be gone after load",
            null,
            store.data.first()[LEGACY_RELAY_TOKEN],
        )
    }

    @Test
    fun `setup complete is not observable before the persist finishes`() = runTest {
        val store = ControlledDataStore()
        val settings = AppSettings.createForTest(store, "hearthlane.example", this)
        settings.ready.first { it }

        store.blockNext = true
        launch { settings.setSetupComplete(true) }
        store.editStarted.await()
        runCurrent()

        assertFalse(
            "the observable value must not become true before the DataStore write completes",
            settings.setupComplete.value,
        )

        store.releaseEdit.complete(Unit)
        advanceUntilIdle()

        assertTrue("after the write completes the value becomes true", settings.setupComplete.value)
        assertEquals(true, store.current()[SETUP_COMPLETE])
    }

    @Test
    fun `cancelling the persist leaves no optimistic in-memory true`() = runTest {
        val store = ControlledDataStore()
        val settings = AppSettings.createForTest(store, "hearthlane.example", this)
        settings.ready.first { it }

        store.blockNext = true
        val job = launch { settings.setSetupComplete(true) }
        store.editStarted.await()
        runCurrent()

        job.cancel()
        advanceUntilIdle()

        assertFalse(
            "a cancelled caller must never leave a misleading in-memory true",
            settings.setupComplete.value,
        )
        assertEquals("and the storage must stay unchanged", null, store.current()[SETUP_COMPLETE])
    }

    @Test
    fun `location sharing defaults to on for a new install`() = runTest {
        val settings = AppSettings.createForTest(
            dataStore = dataStore(backgroundScope),
            defaultBaseDomain = "hearthlane.example",
            scope = backgroundScope,
        )
        settings.ready.first { it }

        assertEquals(
            "a fresh install must have sharing on by default",
            true,
            settings.locationSharingEnabled.value,
        )
    }

    @Test
    fun `explicitly stored on is honored`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit { it[LOCATION_SHARING] = true }

        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }

        assertEquals(true, settings.locationSharingEnabled.value)
    }

    @Test
    fun `explicitly stored off is honored and never reinterpreted`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit { it[LOCATION_SHARING] = false }

        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }

        assertEquals(
            "an explicit OFF must not be treated as unset",
            false,
            settings.locationSharingEnabled.value,
        )
    }

    @Test
    fun `sharing choice persists across instances in both directions`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }
        assertEquals(true, settings.locationSharingEnabled.value)

        settings.setLocationSharingEnabled(false)

        var reloaded = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloaded.ready.first { it }
        assertEquals("ON -> OFF must persist", false, reloaded.locationSharingEnabled.value)

        reloaded.setLocationSharingEnabled(true)

        val reloadedAgain = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloadedAgain.ready.first { it }
        assertEquals("OFF -> ON must persist", true, reloadedAgain.locationSharingEnabled.value)
    }

    @Test
    fun `device nickname defaults to blank and round-trips trimmed`() = runTest {
        val store = dataStore(backgroundScope)
        val settings = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        settings.ready.first { it }
        assertEquals("", settings.deviceNickname.value)

        settings.setDeviceNickname("  Meu celular  ")

        val reloaded = AppSettings.createForTest(store, "hearthlane.example", backgroundScope)
        reloaded.ready.first { it }
        assertEquals("Meu celular", reloaded.deviceNickname.value)
        assertEquals("the nickname must not touch other preferences", "hearthlane.example", reloaded.baseDomain.value)
    }

    companion object {
        private val BASE_DOMAIN = stringPreferencesKey("hearthlane_base_domain")
        private val LEGACY_FRIGATE_URL = stringPreferencesKey("frigate_base_url")
        private val LEGACY_RELAY_TOKEN = stringPreferencesKey("relay_token")
        private val LOCATION_SHARING = booleanPreferencesKey("location_sharing_enabled")
        private val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
    }
}