package org.hearthlane.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import org.hearthlane.core.connectivity.HearthlaneEndpointResolver
import org.hearthlane.core.frigate.LiveQualityMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.SecureRandom

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

/**
 * Persisted application settings (Preferences DataStore).
 *
 * V1 classification (see docs/V1.md section 12): the values held here are
 * configuration, not secrets — the Hearthlane base domain and the auto-generated
 * node hostname suffix. They are deliberately stored plaintext with the standard
 * Android preferences mechanism; no encryption is added. Tailscale node
 * identity stays in `filesDir/tailscale` and is never written here.
 *
 * The product configures a single environment: the [baseDomain] (for example
 * `hearthlane.omni.corp`). The Frigate and Relay endpoints are derived from it
 * by [HearthlaneEndpointResolver] and are never edited independently. The
 * relay service label ([relaySubdomain]) is a per-build environment constant
 * supplied by the flavor (`relay` for PROD, `relay-uat` for UAT); it is not a
 * user setting, so it stays fixed for the life of the installed app.
 *
 * The [nodeSuffix] is the stable per-installation anchor of the embedded node
 * hostname: it is generated once on first run and reused forever, so the node
 * keeps its name (and therefore its enrolled identity) across app restarts.
 * It is an internal value, never part of the normal configuration flow.
 */
class AppSettings(
    private val dataStore: DataStore<Preferences>,
    private val defaultBaseDomain: String,
    private val scope: CoroutineScope,
    private val relaySubdomain: String = RELAY_SUBDOMAIN_DEFAULT,
) {
    /** True once the persisted values have been loaded into the flows below. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The single Hearthlane environment domain (e.g. `hearthlane.omni.corp`). */
    private val _baseDomain = MutableStateFlow("")
    val baseDomain: StateFlow<String> = _baseDomain.asStateFlow()

    /** Frigate endpoint derived from [baseDomain]; empty when unset/invalid. */
    private val _frigateBaseUrl = MutableStateFlow("")
    val frigateBaseUrl: StateFlow<String> = _frigateBaseUrl.asStateFlow()

    /** Relay endpoint derived from [baseDomain]; empty when unset/invalid. */
    private val _relayBaseUrl = MutableStateFlow("")
    val relayBaseUrl: StateFlow<String> = _relayBaseUrl.asStateFlow()

    /** Whether the device publishes its last-known location to the relay.
     *  Defaults to ON for a new install; an explicit user OFF is persisted and
     *  never reinterpreted as "unset". The actual publication still requires
     *  the Android permissions (see the location-sharing flow). */
    private val _locationSharingEnabled = MutableStateFlow(true)
    val locationSharingEnabled: StateFlow<Boolean> = _locationSharingEnabled.asStateFlow()

    /** Auto-generated node hostname suffix; generated once and persisted. */
    private val _nodeSuffix = MutableStateFlow("")
    val nodeSuffix: StateFlow<String> = _nodeSuffix.asStateFlow()

    /** True once the administrator completed the V1.1 initial setup. The app
     *  gates on this: setup shows until it is set, then normal use never asks
     *  about infrastructure again. */
    private val _setupComplete = MutableStateFlow(false)
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    /** Whether event clips start playing automatically on the event-detail
     *  screen. Defaults to true (the pre-preference behavior is autoplay). */
    private val _autoPlayEventClips = MutableStateFlow(true)
    val autoPlayEventClips: StateFlow<Boolean> = _autoPlayEventClips.asStateFlow()

    /** Optional presentation-only nickname for this device. Identity is always
     *  [nodeSuffix]/deviceId; the nickname is never used as a key. */
    private val _deviceNickname = MutableStateFlow("")
    val deviceNickname: StateFlow<String> = _deviceNickname.asStateFlow()

    /** Live quality preference: AUTO adapts the live stream remotely, HIGH
     *  always uses the main stream, ECONOMY prefers the substream. Defaults to
     *  AUTO; AUTO starts on the substream remotely, so existing remote users
     *  keep today's behavior until the policy finds reason to upgrade. */
    private val _liveQualityMode = MutableStateFlow(LiveQualityMode.AUTO)
    val liveQualityMode: StateFlow<LiveQualityMode> = _liveQualityMode.asStateFlow()

    init {
        scope.launch {
            val prefs = dataStore.data.first()

            var domain = prefs[BASE_DOMAIN]?.takeIf { it.isNotBlank() }
            if (domain == null) {
                val migrated = migrateLegacyUrls(prefs)
                domain = when {
                    migrated != null -> migrated
                    prefs[BASE_URL] != null -> {
                        // A legacy URL that cannot be transformed safely is
                        // dropped; the server is re-entered through Setup or
                        // Settings. This never touches the onboarding gate.
                        ""
                    }
                    else -> defaultBaseDomain
                }
                dataStore.edit { it[BASE_DOMAIN] = domain }
            }
            applyBaseDomain(domain)

            val storedSuffix = prefs[NODE_SUFFIX]?.takeIf { it.isNotBlank() }
            val suffix = storedSuffix ?: generateSuffix()
            if (storedSuffix == null) {
                dataStore.edit { it[NODE_SUFFIX] = suffix }
            }
            _nodeSuffix.value = suffix

            // setupCompleted is the onboarding gate. It is read straight from
            // the persisted store and never downgraded by later configuration,
            // permission, server or connection changes: a completed onboarding
            // stays complete.
            _setupComplete.value = prefs[SETUP_COMPLETE] ?: false

            _autoPlayEventClips.value = prefs[AUTO_PLAY_EVENT_CLIPS] ?: true

            _deviceNickname.value = prefs[DEVICE_NICKNAME]?.takeIf { it.isNotBlank() } ?: ""

            _liveQualityMode.value = prefs[LIVE_QUALITY_MODE]
                ?.let { runCatching { LiveQualityMode.valueOf(it) }.getOrNull() }
                ?: LiveQualityMode.AUTO

            // The relay MVP has no application authentication; a token persisted
            // by an older build is dead configuration and is removed safely.
            if (prefs[RELAY_TOKEN] != null) {
                dataStore.edit { it.remove(RELAY_TOKEN) }
            }

            _locationSharingEnabled.value = prefs[LOCATION_SHARING_ENABLED] ?: true

            _ready.value = true
        }
    }

    /**
     * Tries to convert the pre-abstraction two-URL configuration into the
     * single base domain. Only a deterministic, unambiguous transform is
     * allowed: a Frigate URL whose hostname is exactly `frigate.<domain>`
     * (for example `http://frigate.hearthlane.example`) yields
     * `hearthlane.example`. Anything else returns null; the caller decides
     * whether to seed the default or require reconfiguration.
     */
    private suspend fun migrateLegacyUrls(prefs: Preferences): String? {
        val legacy = prefs[BASE_URL]?.takeIf { it.isNotBlank() } ?: return null
        val host = legacy
            .trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        val candidate = if (host.startsWith("frigate.")) host.removePrefix("frigate.") else host
        val normalized = HearthlaneEndpointResolver.normalizeBaseDomain(candidate) ?: return null
        dataStore.edit {
            it[BASE_DOMAIN] = normalized
            it.remove(BASE_URL)
            it.remove(RELAY_BASE_URL)
        }
        return normalized
    }

    /** Recomputes the persisted domain and the derived endpoint flows. */
    private fun applyBaseDomain(domain: String) {
        _baseDomain.value = domain
        val endpoints = HearthlaneEndpointResolver.resolve(domain, relaySubdomain)
        _frigateBaseUrl.value = endpoints?.frigateBaseUrl ?: ""
        _relayBaseUrl.value = endpoints?.relayBaseUrl ?: ""
    }

    /** Persists the Hearthlane base domain. Blank input is persisted as-is and
     *  is treated by the connection flow as an unset configuration. */
    suspend fun setBaseDomain(domain: String) {
        val normalized = HearthlaneEndpointResolver.normalizeBaseDomain(domain) ?: ""
        applyBaseDomain(normalized)
        dataStore.edit { it[BASE_DOMAIN] = normalized }
    }

    /** Marks the V1.1 initial setup as complete (or reopens it when false). */
    suspend fun setSetupComplete(complete: Boolean) {
        // Persist FIRST, publish AFTER: publishing the observable value before
        // the DataStore write lets AppRoot leave Setup and cancel the caller's
        // coroutine scope, aborting the edit — leaving true only in memory.
        // With this order, once setupComplete is observable the value is already
        // durable, so a cancelled caller never produces an optimistic state.
        dataStore.edit { it[SETUP_COMPLETE] = complete }
        _setupComplete.value = complete
    }

    /** Persists the auto-play preference for event clips. */
    suspend fun setAutoPlayEventClips(enabled: Boolean) {
        _autoPlayEventClips.value = enabled
        dataStore.edit { it[AUTO_PLAY_EVENT_CLIPS] = enabled }
    }

    /** Persists the location-sharing opt-in. */
    suspend fun setLocationSharingEnabled(enabled: Boolean) {
        _locationSharingEnabled.value = enabled
        dataStore.edit { it[LOCATION_SHARING_ENABLED] = enabled }
    }

    /** Persists this device's presentation-only nickname (blank clears it). */
    suspend fun setDeviceNickname(nickname: String) {
        val trimmed = nickname.trim()
        _deviceNickname.value = trimmed
        dataStore.edit { it[DEVICE_NICKNAME] = trimmed }
    }

    /** Persists the live quality preference. */
    suspend fun setLiveQualityMode(mode: LiveQualityMode) {
        _liveQualityMode.value = mode
        dataStore.edit { it[LIVE_QUALITY_MODE] = mode.name }
    }

    companion object {
        /** Standard relay service label when no environment override applies. */
        const val RELAY_SUBDOMAIN_DEFAULT = "relay"

        fun create(
            context: Context,
            defaultBaseDomain: String,
            scope: CoroutineScope,
            relaySubdomain: String = RELAY_SUBDOMAIN_DEFAULT,
        ): AppSettings =
            AppSettings(context.appSettingsDataStore, defaultBaseDomain, scope, relaySubdomain)

        /** Test-only factory: takes a caller-owned DataStore. */
        fun createForTest(
            dataStore: DataStore<Preferences>,
            defaultBaseDomain: String,
            scope: CoroutineScope,
            relaySubdomain: String = RELAY_SUBDOMAIN_DEFAULT,
        ): AppSettings = AppSettings(dataStore, defaultBaseDomain, scope, relaySubdomain)

        /**
         * Stable embedded node hostname for a given persisted suffix. The
         * hostname is an internal value: it is never part of the normal
         * configuration flow.
         */
        fun nodeHostname(suffix: String): String = "hearthlane-$suffix"

        /** Random lowercase-hex suffix. Deterministic length, cryptographically
         *  seeded so two installations never collide by chance. */
        fun generateSuffix(length: Int = 8): String {
            val chars = "0123456789abcdef"
            val random = SecureRandom()
            return buildString(length) {
                repeat(length) { append(chars[random.nextInt(chars.length)]) }
            }
        }

        private val BASE_DOMAIN = stringPreferencesKey("hearthlane_base_domain")
        private val DEVICE_NICKNAME = stringPreferencesKey("device_nickname")
        private val LOCATION_SHARING_ENABLED = booleanPreferencesKey("location_sharing_enabled")
        private val NODE_SUFFIX = stringPreferencesKey("node_hostname_suffix")
        private val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        private val AUTO_PLAY_EVENT_CLIPS = booleanPreferencesKey("auto_play_event_clips")
        private val LIVE_QUALITY_MODE = stringPreferencesKey("live_quality_mode")

        /** Legacy two-URL keys removed by the endpoint-abstraction migration. */
        private val BASE_URL = stringPreferencesKey("frigate_base_url")
        private val RELAY_BASE_URL = stringPreferencesKey("relay_base_url")

        /** Legacy relay-auth key, removed once it is read (relay MVP has no auth). */
        private val RELAY_TOKEN = stringPreferencesKey("relay_token")
    }
}