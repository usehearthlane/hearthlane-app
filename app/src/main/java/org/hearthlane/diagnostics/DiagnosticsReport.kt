package org.hearthlane.diagnostics

/**
 * Plain-text diagnostics report for the "Copy diagnostics" action.
 *
 * The report is built exclusively from the allow-listed [Snapshot] fields, so
 * authentication/enrollment URLs, tokens and credentials are never part of its
 * input shape. As defense in depth, free-form strings are additionally passed
 * through [sanitize] before being included.
 */
object DiagnosticsReport {

    /**
     * Everything the report may contain. The builder never receives an
     * enrollment URL, token or credential: sensitive values are simply not
     * part of this shape.
     */
    data class Snapshot(
        val appVersion: String,
        val frigateConnectivity: String,
        val relayConnectivity: String,
        val tailscaleState: String,
        /** Selected transport (LOCAL / TAILSCALE) or null when never connected. */
        val transport: String?,
        val transportSwitchCount: Int,
        val playbackState: String,
        val lastPlaybackError: String?,
        val firstFrameElapsedMs: Long?,
        val serverVersion: String?,
        val errorCount: Int,
        val bytesTransferred: Long,
        val recoveryCount: Int,
        /** Embedded node hostname (internal value, moved from Settings). */
        val nodeHostname: String,
        /** Configured Hearthlane base domain (sanitized host, never a token). */
        val baseDomain: String,
        /** Sanitized location observability section; null when not collected. */
        val location: LocationDiagnosticsSnapshot? = null,
    )

    fun build(snapshot: Snapshot): String = buildString {
        appendLine("Diagnostics report")
        appendLine("App version: ${snapshot.appVersion}")
        appendLine("Server: ${sanitize(snapshot.baseDomain).ifBlank { "unset" }}")
        appendLine("Frigate endpoint: ${snapshot.frigateConnectivity}")
        appendLine("Relay endpoint: ${snapshot.relayConnectivity}")
        appendLine("Tailscale state: ${snapshot.tailscaleState}")
        appendLine("Selected transport: ${snapshot.transport ?: "none"}")
        appendLine("Transport switches: ${snapshot.transportSwitchCount}")
        appendLine("Playback state: ${snapshot.playbackState}")
        appendLine("Last playback error: ${snapshot.lastPlaybackError?.let(::sanitize) ?: "none"}")
        appendLine("Time to first frame: ${snapshot.firstFrameElapsedMs?.let { "$it ms" } ?: "n/a"}")
        appendLine("Server version: ${snapshot.serverVersion?.let(::sanitize) ?: "unknown"}")
        appendLine("Node hostname: ${snapshot.nodeHostname}")
        appendLine(
            "Diagnostics: errors ${snapshot.errorCount}, " +
                "bytes ${snapshot.bytesTransferred}, recoveries ${snapshot.recoveryCount}",
        )
        snapshot.location?.let { location ->
            appendLine("Location")
            appendLine("Sharing enabled: ${location.sharingEnabled}")
            appendLine("Foreground permission: ${location.foregroundPermission}")
            appendLine("Background permission: ${location.backgroundPermission}")
            appendLine("Location services: ${location.locationServices}")
            appendLine("Foreground service: ${location.foregroundService}")
            appendLine("Publisher state: ${location.publisherState}")
            appendLine("Publisher mode: ${location.publisherMode}")
            appendLine("Location check interval: ${location.locationCheckIntervalLabel}")
            appendLine("Minimum publish interval: ${location.minPublishIntervalLabel}")
            appendLine("Movement threshold: ${location.movementThresholdLabel}")
            appendLine("Presence interval: ${location.presenceIntervalLabel}")
            appendLine("Map-active interval: ${location.mapActiveIntervalLabel}")
            appendLine("Last location read: ${location.lastRead?.let { "$it (${location.lastReadResult ?: "n/a"})" } ?: "Never"}")
            appendLine("Last fix provider: ${location.lastFixProvider ?: "n/a"}")
            appendLine("Last fix accuracy: ${location.lastFixAccuracy ?: "n/a"}")
            appendLine("Last fix age: ${location.lastFixAge ?: "n/a"}")
            appendLine("Last fix decision: ${location.lastFixDecision ?: "n/a"}")
            appendLine("Last publish decision: ${location.lastPublishDecision ?: "n/a"}")
            appendLine("Backoff: ${location.backoff}")
            appendLine("Presence count: ${location.presenceCount}")
            appendLine("Last presence at: ${location.lastPresenceAt?.let { "$it (elapsed)" } ?: "Never"}")
            appendLine("Last presence decision: ${location.lastPresenceDecision ?: "n/a"}")
            appendLine("Last publish attempt: ${location.lastPublishAttempt ?: "Never"}")
            appendLine("Last publish result: ${location.lastPublishResult ?: "n/a"}")
            appendLine("Last successful publish: ${location.lastSuccessfulPublish ?: "Never"}")
            appendLine("Pending location: ${location.pendingLocation}")
            appendLine("Relay: ${location.relay}")
            appendLine("Location tsnet state: ${location.tsnetState}")
            appendLine("Location tsnet starts: ${location.tsnetStarts}")
            appendLine("Location tsnet stops: ${location.tsnetStops}")
            appendLine("Location tsnet last start duration: ${location.tsnetLastStartDuration ?: "n/a"}")
            appendLine("Location tsnet last start at: ${location.tsnetLastStartAt?.let { "$it (elapsed)" } ?: "Never"}")
            appendLine("Location tsnet last stop at: ${location.tsnetLastStopAt?.let { "$it (elapsed)" } ?: "Never"}")
            appendLine("Location tsnet last transport: ${location.tsnetLastTransport ?: "n/a"}")
            appendLine("Location tsnet last network: ${location.tsnetLastNetwork ?: "n/a"}")
            appendLine(
                "Location tsnet publishes: " +
                    "attempts ${location.tsnetPublishAttempts}, " +
                    "success ${location.tsnetPublishSuccesses}, " +
                    "failures ${location.tsnetPublishFailures}",
            )
            appendLine("Device ID: ${sanitize(location.deviceId)}")
            appendLine("Device nickname: ${sanitize(location.deviceNickname)}")
        }
    }

    /**
     * Redacts sensitive-shaped fragments from arbitrary strings before they
     * can reach the report: Tailscale login/control-plane URLs and long
     * token-shaped runs. The allow-list in [build] is the primary guarantee;
     * this is a secondary guard for free-form error text.
     */
    fun sanitize(raw: String): String {
        var out = raw
        for (match in AUTH_URL_PATTERN.findAll(out)) {
            out = out.replace(match.value, REDACTED)
        }
        return TOKEN_PATTERN.replace(out, REDACTED)
    }

    private const val REDACTED = "[redacted]"

    private val AUTH_URL_PATTERN = Regex(
        "https://(?:login|controlplane)\\.tailscale\\.com[^\\s\"']*",
    )

    // Token-shaped: an unbroken run of 24+ word characters. Covers OAuth
    // tokens, API keys and node auth keys without over-matching short values.
    private val TOKEN_PATTERN = Regex("\\b[A-Za-z0-9_\\-]{24,}\\b")
}
