package org.hearthlane.core.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import org.hearthlane.core.connectivity.HttpStreamGetter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/**
 * Owns the [ExoPlayer] instance and its HLS media source.
 *
 * Every HLS request (playlist, init, media segments) flows through a
 * [StreamingHttpDataSource] bound to the injected [HttpStreamGetter], so bytes
 * reach the player incrementally as they arrive — a segment is never
 * materialized whole in memory before the first byte is delivered. This is the
 * structural fix for the Live rebuffering: the previous full-buffer data source
 * downloaded each HLS request completely before Media3 could consume it,
 * producing burst/starvation cycles.
 *
 * The UI binds [player] to a PlayerView and observes [state]; it never talks
 * to Media3 internals.
 */
@UnstableApi
class LiveStreamPlayer(
    context: Context,
    private val getter: HttpStreamGetter,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val onSessionDead: () -> Unit = {},
    private val liveTargetOffsetMs: Long? = null,
    private val diagTransport: String = "UNKNOWN",
    /** Quality-policy feeds: a media segment finished downloading (bytes, download ms). */
    private val onMediaSegmentLoaded: (Long, Long) -> Unit = { _, _ -> },
    /** Quality-policy feed: a media playlist poll arrived (gap since the previous poll, ms). */
    private val onPlaylistPolled: (Long) -> Unit = {},
    /** Quality-policy feed: a transport read stall occurred. */
    private val onReadStall: () -> Unit = {},
    /** Quality-policy feed: a non-dead load error occurred. */
    private val onLoadError: (Int) -> Unit = {},
) {

    private val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

    /** True while the early session recovery is in flight: quality switches
     *  must not compete with it (the recovery re-resolves the current level). */
    val isRecoveryInFlight: Boolean get() = recoveryPolicy.recoveryInFlight

    @UnstableApi
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setBandwidthMeter(bandwidthMeter)
        .build()

    private val _state = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)
    val state: StateFlow<PlaybackStatus> = _state.asStateFlow()

    private val _metrics = MutableStateFlow(PlayerMetrics())
    val metrics: StateFlow<PlayerMetrics> = _metrics.asStateFlow()

    // TEMPORARY diagnostics: monotonic player generation shared with the
    // data-source request ids so the logcat distinguishes a rebuffer inside a
    // player from a full restart.
    private val generation = VideoDiag.nextId()

    // Wall-clock anchor for the time-to-first-frame measurement; reset on every
    // play() so a recovered session reports its own T2FF.
    private var preparedAtMs = 0L

    private var bufferWaitStartedAtMs = -1L
    private var playCount = 0
    private var firstBufferingLogged = false
    private val reachedBufferThresholds = mutableSetOf<Long>()

    // Low-overhead 1 Hz sampler, driven by the MAIN looper: every player access
    // must happen on the application thread, so the sampler cannot run on a
    // background dispatcher.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var samplerRunnable: Runnable? = null

    private val loadStartedEpoch = HashMap<Long, Int>()
    private var activeLoads = 0
    private var lastPlaylistPollStartMs = -1L

    // Early session recovery: detects a dead go2rtc media playlist and triggers
    // a fresh session instead of waiting for Media3 to exhaust retries.
    private val recoveryPolicy = HlsSessionRecoveryPolicy(nowMs = SystemClock::elapsedRealtime)
    private var recoveryPending = false
    private val recoveryCheckRunnable = Runnable {
        recoveryPending = false
        maybeRunEarlyRecovery()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> {
                    VideoDiag.player("LIVE", "state IDLE generation=$generation")
                    // ExoPlayer drops to IDLE right after onPlayerError; keep
                    // the error visible so the UI does not bounce back to
                    // "starting". A later play() call resets the state.
                    if (_state.value !is PlaybackStatus.Error) {
                        _state.value = PlaybackStatus.Idle
                    }
                }
                Player.STATE_BUFFERING -> {
                    val enteringRebuffer = _state.value is PlaybackStatus.Playing
                    if (enteringRebuffer) {
                        bufferWaitStartedAtMs = SystemClock.elapsedRealtime()
                        VideoDiag.player("LIVE", "BUFFERING_ENTER generation=$generation ${bufferSnapshot()}")
                    }
                    if (!firstBufferingLogged) {
                        firstBufferingLogged = true
                        VideoDiag.player(
                            "LIVE",
                            "startup first-buffering phase=${startupPhase()} " +
                                "timeSincePrepareMs=${SystemClock.elapsedRealtime() - preparedAtMs} " +
                                bufferSnapshot(),
                        )
                    }
                    VideoDiag.player("LIVE", "state BUFFERING generation=$generation")
                    _state.value = PlaybackStatus.Loading
                }
                Player.STATE_READY -> {
                    if (_state.value is PlaybackStatus.Loading && bufferWaitStartedAtMs >= 0) {
                        val durationMs = SystemClock.elapsedRealtime() - bufferWaitStartedAtMs
                        VideoDiag.player("LIVE", "BUFFERING_EXIT generation=$generation durationMs=$durationMs")
                        bufferWaitStartedAtMs = -1
                    }
                    VideoDiag.player(
                        "LIVE",
                        "startup ready phase=${startupPhase()} " +
                            "timeSincePrepareMs=${SystemClock.elapsedRealtime() - preparedAtMs} " +
                            bufferSnapshot(),
                    )
                    VideoDiag.player("LIVE", "state READY generation=$generation ${bufferSnapshot()}")
                    _state.value = PlaybackStatus.Playing
                }
                Player.STATE_ENDED -> Log.i(TAG, "live playback ended")
            }
        }

        override fun onRenderedFirstFrame() {
            val elapsed = SystemClock.elapsedRealtime() - preparedAtMs
            VideoDiag.player(
                "LIVE",
                "startup first-frame phase=${startupPhase()} timeSincePrepareMs=$elapsed",
            )
            VideoDiag.player("LIVE", "first frame generation=$generation elapsed=${elapsed}ms")
            Log.i(TAG, "first frame rendered ${elapsed}ms after play request")
            _metrics.update { it.copy(firstFrameElapsedMs = elapsed) }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Include the parser/loader cause when present: for a malformed
            // manifest the top-level message is just "Source Error".
            val cause = error.cause?.message?.takeIf { it.isNotBlank() }
            val message = listOfNotNull(
                error.errorCodeName,
                error.message,
                cause,
            ).distinct().joinToString(": ")
            _metrics.update { it.copy(errorCount = it.errorCount + 1) }
            VideoDiag.playerError(
                "LIVE",
                "player error generation=$generation count=${_metrics.value.errorCount}",
                error,
            )
            Log.e(TAG, "playback error (count=${_metrics.value.errorCount}): $message", error)
            _state.value = PlaybackStatus.Error(message, httpStatusFrom(error))
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            loadStartedEpoch[loadEventInfo.loadTaskId] = recoveryPolicy.sessionEpoch
            activeLoads++
            val resource = classifyResource(loadEventInfo.dataSpec.uri.toString())
            if (resource == "MEDIA_PLAYLIST") {
                val now = SystemClock.elapsedRealtime()
                val delta = if (lastPlaylistPollStartMs >= 0) now - lastPlaylistPollStartMs else -1L
                lastPlaylistPollStartMs = now
                onPlaylistPolled(delta)
                // Only anomalies are surfaced: a delayed poll is the first sign
                // of a dead media playlist and precedes a session death.
                if (delta > PLAYLIST_POLL_GAP_THRESHOLD_MS) {
                    VideoDiag.player(
                        "LIVE",
                        "playlist-poll-delayed generation=$generation deltaMs=$delta activeLoads=$activeLoads",
                    )
                }
            }
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            activeLoads = maxOf(0, activeLoads - 1)
            val epoch = loadStartedEpoch.remove(loadEventInfo.loadTaskId) ?: recoveryPolicy.sessionEpoch
            if (!recoveryPolicy.isCurrentEpoch(epoch)) {
                VideoDiag.player(
                    "LIVE",
                    "stale-load-callback ignored oldEpoch=$epoch currentEpoch=${recoveryPolicy.sessionEpoch}",
                )
                return
            }
            val resource = classifyResource(loadEventInfo.dataSpec.uri.toString())
            if (resource == "MEDIA_SEGMENT") {
                onMediaSegmentLoaded(loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs)
            }
            if (resource == "MEDIA_PLAYLIST") {
                if (recoveryPolicy.recoveryInFlight) {
                    val elapsed = recoveryPolicy.onRecoveryConfirmed()
                    VideoDiag.player(
                        "LIVE",
                        "early-recovery-ready newEpoch=${recoveryPolicy.sessionEpoch} elapsedMs=$elapsed",
                    )
                } else {
                    recoveryPolicy.onPlaylistLoadSuccess()
                }
            }
        }

        override fun onLoadCanceled(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            activeLoads = maxOf(0, activeLoads - 1)
            loadStartedEpoch.remove(loadEventInfo.loadTaskId)
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            activeLoads = maxOf(0, activeLoads - 1)
            val epoch = loadStartedEpoch.remove(loadEventInfo.loadTaskId) ?: recoveryPolicy.sessionEpoch
            if (!recoveryPolicy.isCurrentEpoch(epoch)) {
                VideoDiag.player(
                    "LIVE",
                    "stale-load-callback ignored oldEpoch=$epoch currentEpoch=${recoveryPolicy.sessionEpoch}",
                )
                return
            }
            val resource = classifyResource(loadEventInfo.dataSpec.uri.toString())
            val status = httpStatusFromError(error)
            if (isDeadPlaylistError(resource, status)) {
                recoveryPolicy.onPlaylistLoadError(status)
                VideoDiag.player(
                    "LIVE",
                    "session-dead-candidate playlistErrors=${recoveryPolicy.playlistErrors} " +
                        "epoch=${recoveryPolicy.sessionEpoch}",
                )
                maybeRunEarlyRecovery()
            } else {
                onLoadError(status)
            }
            VideoDiag.player(
                "LIVE",
                "load-error generation=$generation resource=$resource " +
                    "bytes=${loadEventInfo.bytesLoaded} status=$status",
            )
        }
    }

    init {
        VideoDiag.player("LIVE", "player created generation=$generation getter=${getter::class.simpleName}")
        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
    }

    /** Starts (or replaces) HLS playback. Call [release] when the screen leaves. */
    fun play(hlsUrl: String) {
        recoveryPolicy.newSession()
        preparedAtMs = SystemClock.elapsedRealtime()
        playCount++
        firstBufferingLogged = false
        reachedBufferThresholds.clear()
        VideoDiag.player(
            "LIVE",
            "startup prepare phase=${startupPhase()} timeSincePrepareMs=0",
        )
        Log.i(TAG, "player preparing via ${getter::class.simpleName}")
        val mediaItem = buildMediaItem(hlsUrl)
        VideoDiag.player(
            "LIVE",
            "live-config transport=$diagTransport " +
                "targetOffsetMs=${liveTargetOffsetMs ?: "UNSET"}",
        )
        val source = HlsMediaSource.Factory(
            StreamingHttpDataSourceFactory(
                getter,
                connectTimeoutMs,
                this::onBytesTransferred,
                onReadStall,
                "LIVE",
                bandwidthMeter,
            ),
        ).createMediaSource(mediaItem)
        // Clear any previous error: a fresh media source starts from IDLE and
        // would otherwise keep the last error visible forever.
        _state.value = PlaybackStatus.Loading
        // After a fatal error the same player instance must start from a clean
        // slate: stop + detach the failed media source, otherwise a re-prepare
        // can reuse the stale error path and never leave the dead session. A
        // Retry on the existing player then behaves like the proven
        // leave-and-re-enter recovery (which builds a fresh player).
        if (player.playbackState == Player.STATE_IDLE) {
            player.stop()
            player.clearMediaItems()
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        startSampler()
    }

    /**
     * Builds the HLS [MediaItem]. For transports that opt in (remote/Tailscale),
     * a conservative live target offset moves the playback start a little
     * behind the live edge, giving real jitter margin without huge latency; the
     * playlist window clamps the position if it is shorter than the offset.
     */
    private fun buildMediaItem(hlsUrl: String): MediaItem =
        buildLiveMediaItem(hlsUrl, liveTargetOffsetMs)

    private fun onBytesTransferred(bytes: Long) {
        _metrics.update { it.copy(bytesTransferred = it.bytesTransferred + bytes) }
    }

    /**
     * Triggers the early session recovery exactly once per dead session: asks
     * the owner (LiveView) to run discoverAndPlay, which resolves a new master /
     * media playlist and re-prepares the SAME ExoPlayer. A deferred trigger
     * (still inside the minimum recovery interval) is re-checked once the
     * backoff elapses; a playlist success or a stale callback never re-arms it.
     */
    private fun maybeRunEarlyRecovery() {
        if (recoveryPolicy.canTriggerRecovery()) {
            recoveryPolicy.beginRecovery()
            VideoDiag.player(
                "LIVE",
                "early-recovery-trigger playlistErrors=${recoveryPolicy.playlistErrors} " +
                    "epoch=${recoveryPolicy.sessionEpoch}",
            )
            VideoDiag.player("LIVE", "early-recovery-begin oldEpoch=${recoveryPolicy.sessionEpoch}")
            onSessionDead()
        } else if (recoveryPolicy.backoffRemainingMs() > 0 && !recoveryPending) {
            recoveryPending = true
            mainHandler.removeCallbacks(recoveryCheckRunnable)
            mainHandler.postDelayed(recoveryCheckRunnable, recoveryPolicy.backoffRemainingMs())
        }
    }

    /**
     * Releases the HLS media source so no further requests are made and no
     * bytes flow. Used by the Stop control and when the app goes to the
     * background (screen off): a `playWhenReady = false` would NOT stop the
     * network activity, because media3 keeps refreshing the HLS playlist
     * while the media source is loaded, which also keeps the go2rtc session
     * alive. Playback is re-established on the next [play] with a fresh
     * go2rtc session.
     */
    fun stop() {
        stopSampler()
        mainHandler.removeCallbacks(recoveryCheckRunnable)
        VideoDiag.player("LIVE", "player stop generation=$generation")
        Log.i(TAG, "live playback stopped; media source released")
        player.stop()
    }

    fun release() {
        stopSampler()
        mainHandler.removeCallbacks(recoveryCheckRunnable)
        VideoDiag.player("LIVE", "player release generation=$generation")
        player.removeListener(listener)
        player.removeAnalyticsListener(analyticsListener)
        val m = _metrics.value
        Log.i(
            TAG,
            "player released: first frame=${m.firstFrameElapsedMs?.let { "${it}ms" } ?: "not rendered"}, " +
                "errors=${m.errorCount}, bytes transferred=${m.bytesTransferred}",
        )
        player.release()
    }

    /** Compact buffer/position snapshot for diagnostics (no private data). */
    private fun bufferSnapshot(): String {
        val p = player
        val liveOffset = p.currentLiveOffset
        val liveOffsetLabel = if (liveOffset == C.TIME_UNSET) "UNSET" else "$liveOffset"
        return "positionMs=${p.currentPosition} " +
            "bufferedMs=${(p.bufferedPosition - p.currentPosition)} " +
            "bufferedDurationMs=${p.totalBufferedDuration} liveOffsetMs=$liveOffsetLabel " +
            "isLoading=${p.isLoading} playWhenReady=${p.playWhenReady} isPlaying=${p.isPlaying}"
    }

    /** INITIAL for the first play of this player instance, RECOVERY for every
     *  subsequent session (early session recovery, error recovery or retry). */
    private fun startupPhase(): String = if (playCount <= 1) "INITIAL" else "RECOVERY"

    /** Low-overhead 1 Hz loop on the main looper, only to record the startup
     *  buffer-growth milestones (500/1000/1500/2000 ms). No per-tick log:
     *  the milestones alone are the durable signal. Runs only while the player
     *  is active and is removed on stop/release. */
    private fun startSampler() {
        stopSampler()
        val runnable = object : Runnable {
            override fun run() {
                val st = player.playbackState
                if (st == Player.STATE_READY || st == Player.STATE_BUFFERING) {
                    val buffered = player.totalBufferedDuration
                    for (threshold in BUFFER_THRESHOLDS_MS) {
                        if (threshold !in reachedBufferThresholds && buffered >= threshold) {
                            reachedBufferThresholds.add(threshold)
                            VideoDiag.player(
                                "LIVE",
                                "startup buffer-threshold phase=${startupPhase()} reached=${threshold}ms " +
                                    "timeSincePrepareMs=${SystemClock.elapsedRealtime() - preparedAtMs}",
                            )
                        }
                    }
                }
                if (samplerRunnable === this) {
                    mainHandler.postDelayed(this, SAMPLER_INTERVAL_MS)
                }
            }
        }
        samplerRunnable = runnable
        mainHandler.postDelayed(runnable, SAMPLER_INTERVAL_MS)
    }

    private fun stopSampler() {
        samplerRunnable?.let { mainHandler.removeCallbacks(it) }
        samplerRunnable = null
    }

    private fun httpStatusFromError(error: IOException): Int {
        generateSequence(error as Throwable) { it.cause }
            .forEach { t ->
                when (t) {
                    is HttpStatusIOException -> return t.statusCode
                    is HttpDataSource.InvalidResponseCodeException -> return t.responseCode
                    else -> Unit
                }
            }
        return 0
    }

    private companion object {
        const val TAG = "Hearthlane"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        const val SAMPLER_INTERVAL_MS = 1_000L
        val BUFFER_THRESHOLDS_MS = longArrayOf(500, 1000, 1500, 2000)

        /** A playlist poll gap above this (ms) is logged as an anomaly; the
         *  nominal cadence is ~1000 ms. */
        const val PLAYLIST_POLL_GAP_THRESHOLD_MS = 1_500L
    }
}

/**
 * Walks a [PlaybackException] cause chain for the HTTP status of a non-2xx
 * response. Media3 may wrap the data-source error in its own exception layers,
 * so the full chain is inspected; both the legacy [HttpStatusIOException] and
 * the Media3-native [HttpDataSource.InvalidResponseCodeException] are
 * recognized. Returns null for connection-level failures (timeout, DNS,
 * refused).
 */
internal fun httpStatusFrom(error: PlaybackException): Int? {
    generateSequence(error as Throwable) { it.cause }
        .forEach { t ->
            when (t) {
                is HttpStatusIOException -> return t.statusCode
                is HttpDataSource.InvalidResponseCodeException -> return t.responseCode
                else -> Unit
            }
        }
    return null
}

/**
 * Builds the HLS [MediaItem]. When [liveTargetOffsetMs] is set (remote/Tailscale
 * tuning), the item carries a [MediaItem.LiveConfiguration] that moves the live
 * start behind the edge; when null, the default (no override) is used, so LOCAL
 * keeps its current behavior. Pure so the LOCAL/TAILSCALE difference is
 * unit-testable.
 */
internal fun buildLiveMediaItem(hlsUrl: String, liveTargetOffsetMs: Long?): MediaItem {
    val builder = MediaItem.Builder().setUri(hlsUrl)
    val offset = liveTargetOffsetMs
    if (offset != null && offset > 0) {
        builder.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(offset).build(),
        )
    }
    return builder.build()
}