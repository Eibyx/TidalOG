package io.github.yyan115.opentune.playback

import android.content.SharedPreferences
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.core.JsonCodec
import io.github.yyan115.opentune.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One writer in the service, including when no UI exists. JSON is encoded only when the timeline changes. */
@UnstableApi
internal class QueuePersistence(
    private val player: Player,
    private val preferences: SharedPreferences,
    scope: CoroutineScope,
    item: (Track) -> MediaItem,
) : Player.Listener {
    private data class Saved(val items: List<MediaItem>, val index: Int, val position: Long, val shuffle: Boolean, val repeat: Int)
    private val saves = Channel<Saved>(Channel.CONFLATED)
    private var revision = 0L
    private var restoring = true

    init {
        player.addListener(this)
        scope.launch(Dispatchers.IO) {
            var previous: List<MediaItem>? = null
            for (save in saves) {
                val editor = preferences.edit()
                if (previous != save.items) {
                    // Metadata already contains the track JSON; do not decode and re-encode it on each player event.
                    val json = save.items.mapNotNull { it.mediaMetadata.extras?.getString("track") }.joinToString(",", "[", "]")
                    editor.putString("queue", json).putStringSet("radioIndices", save.items.withIndex()
                        .filter { PlaybackRadio.isRadio(it.value) }.map { it.index.toString() }.toSet())
                    previous = save.items
                }
                editor.putInt("index", save.index).putLong("position", save.position)
                    .putBoolean("shuffle", save.shuffle).putInt("repeat", save.repeat).apply()
            }
        }
        scope.launch {
            val before = revision
            val restored = withContext(Dispatchers.IO) {
                preferences.getString("queue", null)?.let { runCatching { JsonCodec.decodeTracks(it) }.getOrNull() }.orEmpty()
            }
            // Never replace a queue received while storage was being read.
            if (revision == before && player.mediaItemCount == 0 && restored.isNotEmpty()) {
                val radioIndices = preferences.getStringSet("radioIndices", emptySet()).orEmpty()
                val items = restored.mapIndexed { index, track ->
                    val media = item(track)
                    if (index.toString() !in radioIndices) media else media.buildUpon().setMediaMetadata(media.mediaMetadata.buildUpon()
                        .setExtras(Bundle(media.mediaMetadata.extras ?: Bundle.EMPTY).apply { putBoolean(PlaybackRadio.EXTRA_RADIO, true) }).build()).build()
                }
                player.shuffleModeEnabled = preferences.getBoolean("shuffle", false)
                player.repeatMode = preferences.getInt("repeat", Player.REPEAT_MODE_OFF).coerceIn(0, 2)
                player.setMediaItems(items, preferences.getInt("index", 0).coerceIn(restored.indices), preferences.getLong("position", 0).coerceAtLeast(0))
                // Restoration stays paused until a controller explicitly starts playback.
            }
            restoring = false
        }
        scope.launch { while (true) { delay(10_000); if (player.isPlaying) save() } }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) revision++
        if (!restoring || revision > 0) save()
    }

    private fun save() {
        saves.trySend(Saved((0 until player.mediaItemCount).map(player::getMediaItemAt), player.currentMediaItemIndex.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode))
    }
}
