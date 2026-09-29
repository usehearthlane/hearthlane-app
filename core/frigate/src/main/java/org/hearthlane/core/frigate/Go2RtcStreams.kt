package org.hearthlane.core.frigate

import org.hearthlane.core.connectivity.HttpBytesGetter
import java.io.IOException
import java.net.URI

/**
 * Discovers go2rtc streams and builds the HLS live URL, always through an
 * injected [HttpBytesGetter] so the same logic serves the local and the
 * Tailscale path.
 *
 * Frigate bundles go2rtc and exposes it behind its own origin under the
 * `/api/go2rtc/` prefix (verified against Frigate 0.17.1):
 *
 * - stream list:  `GET /api/go2rtc/streams` (Frigate HTTP API)
 * - HLS/fMP4:     `GET /api/go2rtc/api/stream.m3u8?src={name}&mp4`
 *
 * Note: on Frigate 0.17.x a request to `/go2rtc/...` (without the `/api/`
 * prefix) is caught by the web UI SPA and returns HTML, so the prefix is
 * mandatory.
 *
 * Two callers exist: camera discovery enumerates **all** stream names
 * ([streamNames]) to resolve per-camera playability by exact id match, and the
 * Live View resolves the selected camera's stream with [streamNameForCamera].
 * There is no "first stream" pick anywhere in the V1 flow: playback always
 * targets the stream whose name equals the selected camera id.
 */
class Go2RtcStreams(private val getter: HttpBytesGetter) {

    /**
     * Returns the set of all go2rtc stream names in document order, or an
     * empty set when Frigate reports no streams.
     *
     * Used by camera discovery to resolve per-camera playability with an exact
     * camera id / stream name match.
     *
     * @throws Exception when the request fails or returns a non-2xx status.
     */
    suspend fun streamNames(baseUrl: String, timeoutMs: Long): Set<String> {
        val result = getter.getBytes("$baseUrl/api/go2rtc/streams", timeoutMs)
        if (result.statusCode !in 200..299) {
            throw IOException("GET /api/go2rtc/streams -> HTTP ${result.statusCode}")
        }
        return topLevelKeys(result.body.toString(Charsets.UTF_8))
    }

    /**
     * Returns the go2rtc stream name for the selected camera, or null when no
     * stream matches.
     *
     * The live-playback selection is done by [streamNameForLive]; this exact
     * id-match lookup is the fallback/legacy path and never guesses by order.
     *
     * @throws Exception when the request fails or returns a non-2xx status.
     */
    suspend fun streamNameForCamera(baseUrl: String, cameraId: String, timeoutMs: Long): String? =
        if (cameraId in streamNames(baseUrl, timeoutMs)) cameraId else null

    /**
     * Returns the go2rtc stream name for the selected camera's live view under
     * [preference], or null when the camera has no playable stream.
     *
     * The preference is the ONLY context input: the live view passes MAIN for
     * the local path and REMOTE_SUB for the remote path, and this method maps
     * it to the stream list discovered from go2rtc. The selection never
     * guesses by order and never assumes a substream exists: REMOTE_SUB
     * falls back to the main stream when `<cameraId>_sub` is absent.
     *
     * @throws Exception when the request fails or returns a non-2xx status.
     */
    suspend fun streamNameForLive(
        baseUrl: String,
        cameraId: String,
        preference: LiveStreamPreference,
        timeoutMs: Long,
    ): String? = selectLiveStreamName(cameraId, streamNames(baseUrl, timeoutMs), preference)

    /**
     * HLS/fMP4 live URL for a go2rtc stream, proxied through Frigate under the
     * `/api/go2rtc/` prefix. `mp4` selects fMP4 output (preferred by
     * ExoPlayer) instead of MPEG-TS.
     */
    fun hlsUrl(baseUrl: String, streamName: String): String =
        "$baseUrl/api/go2rtc/api/stream.m3u8?src=$streamName&mp4"

    /**
     * Creates a go2rtc HLS session for [streamName] and returns the media
     * playlist URL of that session.
     *
     * go2rtc starts a camera producer lazily when the first HLS consumer
     * attaches (this master request) and drops the session after its 5s
     * keepalive. Playing the returned media playlist URL reuses that same
     * session, so ExoPlayer requests init/segments against a session that is
     * already consuming. Requesting a fresh master inside ExoPlayer instead
     * would race the cold producer start and the session keepalive.
     *
     * @throws Exception when the master request fails, returns a non-2xx
     *   status, or does not return a valid m3u8 playlist. go2rtc answers HTTP
     *   200 with an empty body when it cannot attach an HLS consumer; the
     *   exception message keeps the response body preview so the cause is
     *   visible.
     */
    suspend fun resolveMediaPlaylistUrl(baseUrl: String, streamName: String, timeoutMs: Long): String {
        val masterUrl = hlsUrl(baseUrl, streamName)
        val result = getter.getBytes(masterUrl, timeoutMs)
        if (result.statusCode !in 200..299) {
            throw IOException("HLS master GET $masterUrl -> HTTP ${result.statusCode}")
        }
        val body = result.body.toString(Charsets.UTF_8)
        val mediaUrl = resolveMediaPlaylistUrl(masterUrl, body)
        if (!body.startsWith("#EXTM3U") || mediaUrl == null) {
            val preview = body.take(200)
            throw IOException(
                "HLS master is not an m3u8 playlist (HTTP ${result.statusCode}, " +
                    "type=${result.contentType}): \"$preview\"",
            )
        }
        return mediaUrl
    }

    companion object {
        /**
         * Resolves the media playlist reference inside a go2rtc HLS master
         * playlist against the master URL. Returns null when the master does
         * not reference a media playlist.
         */
        fun resolveMediaPlaylistUrl(masterUrl: String, masterBody: String): String? {
            val marker = "playlist.m3u8?id="
            val start = masterBody.indexOf(marker)
            if (start < 0) return null
            val lineStart = masterBody.lastIndexOf('\n', start).let { if (it < 0) 0 else it + 1 }
            val lineEnd = masterBody.indexOfAny(charArrayOf('\r', '\n'), start)
                .let { if (it < 0) masterBody.length else it }
            val token = masterBody.substring(lineStart, lineEnd).trim()
            return runCatching { URI(masterUrl).resolve(token).toString() }.getOrNull()
        }

        /**
         * Returns the top-level keys of a JSON object, in document order, as
         * a set. Handles string values and nested objects/arrays at any depth;
         * returns an empty set for empty or non-object payloads. Kept
         * dependency-free so the module stays JVM-testable without a JSON
         * library.
         */
        fun topLevelKeys(json: String): Set<String> {
            val keys = LinkedHashSet<String>()
            var depth = 0
            var inString = false
            var keyStart = -1
            var i = 0
            while (i < json.length) {
                val c = json[i]
                if (inString) {
                    when (c) {
                        '\\' -> i++ // skip the escaped character
                        '"' -> {
                            inString = false
                            if (keyStart >= 0) {
                                var j = i + 1
                                while (j < json.length && json[j].isWhitespace()) j++
                                if (j < json.length && json[j] == ':') {
                                    keys.add(json.substring(keyStart, i))
                                }
                                keyStart = -1
                                i = j
                            }
                        }
                    }
                } else {
                    when (c) {
                        '"' -> {
                            inString = true
                            keyStart = if (depth == 1) i + 1 else -1
                        }
                        '{' -> depth++
                        '}' -> depth--
                    }
                }
                i++
            }
            return keys
        }
    }
}

/**
 * Live stream quality preference for the selected camera.
 *
 * Currently only two concrete policies exist (MAIN for the local path,
 * REMOTE_SUB for the remote path). The enum is intentionally small so future
 * contexts (AUTO/HIGH/LOW) can extend the policy without changing callers.
 */
enum class LiveStreamPreference { MAIN, REMOTE_SUB }

/**
 * Pure stream-selection policy for the live view.
 *
 * - [LiveStreamPreference.MAIN]: the camera's own stream name (exact match).
 * - [LiveStreamPreference.REMOTE_SUB]: `<cameraId>_sub` when present in
 *   [availableStreams], otherwise the camera's main stream.
 *
 * Selection is always by exact name equality — never by substring/prefix or
 * stream order. Returns null when the camera id itself is not playable.
 */
internal fun selectLiveStreamName(
    cameraId: String,
    availableStreams: Set<String>,
    preference: LiveStreamPreference,
): String? {
    if (cameraId !in availableStreams) return null
    return when (preference) {
        LiveStreamPreference.MAIN -> cameraId
        LiveStreamPreference.REMOTE_SUB -> {
            val sub = "${cameraId}_sub"
            if (sub in availableStreams) sub else cameraId
        }
    }
}
