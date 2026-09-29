package org.hearthlane.controller

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.util.UnstableApi
import org.hearthlane.core.connectivity.HttpStreamGetter
import org.hearthlane.core.frigate.Event
import org.hearthlane.core.frigate.EventNotFoundException
import org.hearthlane.core.frigate.FrigateEventApi
import org.hearthlane.core.playback.EventPlayer
import org.hearthlane.core.playback.PlaybackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Screen state for the event-detail screen of a single event.
 *
 * [NotFound] is distinct from [Error]: Frigate reports a 404 when the event no
 * longer exists, which is a user-meaningful outcome ("this event is no longer
 * available") and must not be collapsed into a generic transport failure.
 */
sealed interface EventDetailState {
    /** The event is being fetched. */
    data object Loading : EventDetailState

    /** The event was found and loaded. */
    data class Loaded(val event: Event) : EventDetailState

    /** Frigate reported the event does not exist (HTTP 404). */
    data object NotFound : EventDetailState

    /** The event could not be loaded (transport/parse/other error); retryable. */
    data class Error(val message: String) : EventDetailState
}

/**
 * Shared observable event-detail state for a single event, including its
 * playback.
 *
 * Fetches the event through [FrigateEventApi.event] using only the [eventId]
 * as the resource identity. [load] is idempotent and cancels any in-flight
 * attempt before starting a new one (used for both the initial load and retry).
 *
 * Playback is coordinated by a composed [EventPlaybackController] (which owns
 * the existing [EventPlayer]): the event-loading state ([state]) and the
 * playback state ([playbackState]) are kept as separate flows so an event
 * loading error is never conflated with a playback error. The transport is
 * resolved by the composition root — this controller receives the
 * already-selected [HttpStreamGetter] and never decides LOCAL vs TAILSCALE.
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
class EventDetailController(
    context: Context,
    private val api: FrigateEventApi,
    private val eventId: String,
    private val baseUrl: () -> String,
    getter: HttpStreamGetter,
    clipUrl: () -> String,
    private val autoPlayEventClips: () -> Boolean,
    private val scope: CoroutineScope,
) {

    private val playback = EventPlaybackController(context, getter, clipUrl)

    /** The embedded event player (used only to bind the PlayerView). */
    val player: EventPlayer get() = playback.player

    /** Playback status: Idle until Play is tapped, then Loading/Playing/Ended/Error. */
    val playbackState: StateFlow<PlaybackStatus> = playback.state

    private val _state = MutableStateFlow<EventDetailState>(EventDetailState.Loading)
    val state: StateFlow<EventDetailState> = _state.asStateFlow()

    private var job: Job? = null

    /** Loads (or reloads) the event and starts playback automatically for clips. */
    fun load() {
        job?.cancel()
        job = scope.launch {
            _state.value = EventDetailState.Loading
            try {
                val event = api.event(baseUrl().trim(), eventId)
                _state.value = EventDetailState.Loaded(event)
                // Auto-play on open unless the user disabled it: the recent-events
                // list is the entry point to an event, so an event with a clip
                // normally starts playing immediately (there is no Play button in
                // the toolbar). When auto-play is off the screen stays idle until
                // the user starts playback from the media-area affordance.
                // Playback errors surface separately through [playbackState].
                if (event.hasClip && autoPlayEventClips()) {
                    playback.play()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EventNotFoundException) {
                _state.value = EventDetailState.NotFound
            } catch (e: Exception) {
                _state.value = EventDetailState.Error(e.message ?: "could not load event")
            }
        }
    }

    /** Starts (or replays/retries) playback of the event clip. */
    fun play() = playback.play()

    /** Stops playback and releases the media source (used by the lifecycle pause). */
    fun stop() = playback.stop()

    /** Releases the player. Call when the screen leaves. */
    fun release() = playback.release()
}
