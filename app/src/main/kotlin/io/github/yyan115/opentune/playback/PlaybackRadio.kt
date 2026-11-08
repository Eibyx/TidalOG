package io.github.yyan115.opentune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.core.AutoRadio
import io.github.yyan115.opentune.core.JsonCodec
import io.github.yyan115.opentune.core.RadioSnapshot
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Service lifetime; does not retain an Activity, ViewModel or MediaController. All player calls use its looper. */
@UnstableApi
internal class PlaybackRadio(
    private val player: Player,
    scope: CoroutineScope,
    private val settings: () -> Settings,
    private val account: () -> String,
    radio: suspend (String) -> List<Track>,
    recent: suspend () -> Set<String>,
    private val item: (Track) -> MediaItem,
    private val sleepAtTrackEnd: () -> Boolean,
) : Player.Listener {
    private var seed: String? = null
    private var mutating = false
    private var replacing = false
    private var explicitBatch = false
    private var entries = emptyList<MediaItem>()
    private var tracks = emptyList<Track?>()
    private val coordinator = AutoRadio(scope, ::snapshot, radio, recent, ::append)
    private val ticker: Job

    init {
        refresh()
        player.addListener(this)
        ticker = scope.launch { while (true) { delay(2_000); coordinator.tick() } }
    }

    private fun refresh() {
        entries = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        tracks = entries.map { media -> media.mediaMetadata.extras?.getString("track")?.let {
            runCatching { JsonCodec.decodeTrack(JSONObject(it)) }.getOrNull()
        } }
    }

    /** Explicit replacement is also invalidated before asynchronous controller request resolution. */
    fun newQueue() { coordinator.invalidate(); seed = null; replacing = true; explicitBatch = false }
    /** An explicit radio action requests its first batch immediately, even with automatic continuation off. */
    fun startTrackRadio() { explicitBatch = true; coordinator.invalidate(); coordinator.tick() }
    fun settingsChanged() {
        coordinator.invalidate()
        val config = settings()
        if (!config.autoplay || config.offline) { explicitBatch = false; removeUpcomingRadio() }
        coordinator.tick()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        coordinator.invalidate()
        if (repeatMode != Player.REPEAT_MODE_OFF) removeUpcomingRadio()
    }

    private fun removeUpcomingRadio() {
        val remove = entries.withIndex().filter { it.index > player.currentMediaItemIndex && isRadio(it.value) }
        if (remove.isNotEmpty()) mutate { remove.asReversed().forEach { player.removeMediaItem(it.index) } }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        refresh()
        if (mutating || reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        replacing = false
        coordinator.invalidate()
        val current = tracks.getOrNull(player.currentMediaItemIndex)
        if (seed == null || tracks.none { it?.id == seed }) seed = current?.id
        prioritizeManual()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mutating) return
        coordinator.invalidate()
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            seed = tracks.getOrNull(player.currentMediaItemIndex)?.id
        }
        if (tracks.getOrNull(player.currentMediaItemIndex)?.isFile == true) removeUpcomingRadio()
        trimHistory()
    }

    override fun onEvents(player: Player, events: Player.Events) { coordinator.tick() }

    private fun snapshot(): RadioSnapshot {
        val at = player.currentMediaItemIndex
        val current = tracks.getOrNull(at)
        // Shuffle in OpenTune physically orders the timeline; the service keeps its ShuffleOrder natural.
        var remaining: Long? = if (player.playbackState == Player.STATE_ENDED) 0L else
            player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { (it - player.currentPosition).coerceAtLeast(0) }
        if (remaining != null) {
            for (index in at + 1 until tracks.size) {
                val duration = tracks[index]?.durationMs ?: 0
                if (duration <= 0) { remaining = null; break }
                remaining = remaining!! + duration
                if (remaining > RadioSnapshot.PRELOAD_MS) break
            }
        }
        val config = settings()
        return RadioSnapshot(current, entries.mapTo(mutableSetOf()) { it.mediaId }, seed ?: current?.id, account(),
            config.autoplay || explicitBatch, config.offline, player.playWhenReady && !replacing, player.repeatMode == Player.REPEAT_MODE_OFF,
            sleepAtTrackEnd(), if (explicitBatch) 0L else remaining,
            AutoRadio.MAX_BATCH - entries.drop((at + 1).coerceAtLeast(0)).count(::isRadio))
    }

    private fun append(recommendations: List<Track>) {
        explicitBatch = false
        val wasEnded = player.playbackState == Player.STATE_ENDED
        val first = player.mediaItemCount
        // The coordinator rechecks the session, seed, account, queue and settings before this callback.
        mutate {
            player.addMediaItems(recommendations.map { track ->
                val media = item(track)
                val extras = android.os.Bundle(media.mediaMetadata.extras ?: android.os.Bundle.EMPTY).apply { putBoolean(EXTRA_RADIO, true) }
                media.buildUpon().setMediaMetadata(media.mediaMetadata.buildUpon().setExtras(extras).build()).build()
            })
        }
        // playWhenReady remains authoritative: never call play(), which could undo a timer/focus/user pause.
        if (wasEnded && player.playWhenReady && !sleepAtTrackEnd()) {
            player.seekTo(first, 0)
            player.prepare()
        }
    }

    /** Appends from every controller take precedence over prefetched music, preserving manual relative order. */
    private fun prioritizeManual() {
        val from = player.currentMediaItemIndex + 1
        if (from !in 0 until entries.size) return
        val upcoming = entries.drop(from)
        val ordered = upcoming.filterNot(::isRadio) + upcoming.filter(::isRadio)
        if (ordered != upcoming) mutate { player.replaceMediaItems(from, player.mediaItemCount, ordered) }
    }

    /** Bound automatic history without removing an album/playlist/manual entry or the current track. */
    private fun trimHistory() {
        val old = entries.take(player.currentMediaItemIndex.coerceAtLeast(0)).withIndex().filter { isRadio(it.value) }
        val remove = old.dropLast(MAX_RADIO_HISTORY)
        if (remove.isNotEmpty()) mutate { remove.asReversed().forEach { player.removeMediaItem(it.index) } }
    }

    private inline fun mutate(block: () -> Unit) {
        mutating = true
        try { block() } finally { refresh(); mutating = false }
    }

    fun release() { ticker.cancel(); coordinator.invalidate(); player.removeListener(this) }

    companion object {
        const val EXTRA_RADIO = PlaybackCommands.EXTRA_RADIO
        const val MAX_RADIO_HISTORY = 50
        fun isRadio(item: MediaItem) = item.mediaMetadata.extras?.getBoolean(EXTRA_RADIO, false) == true
    }
}
