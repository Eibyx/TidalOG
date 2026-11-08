package io.github.yyan115.opentune

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yyan115.opentune.core.JsonCodec
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.data.Settings
import io.github.yyan115.opentune.playback.PlaybackRadio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real Media3 timeline/audio with an injected radio provider, without an Activity or a TIDAL account. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackRadioIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var player: ExoPlayer
    private lateinit var radio: PlaybackRadio
    private lateinit var scope: CoroutineScope
    private lateinit var audio: File
    private var settings = Settings()
    private var sleep = false
    private val calls = AtomicInteger()
    private var provider: suspend (String) -> List<Track> = { listOf(track("2"), track("3")) }
    private fun track(id: String) = Track(id, "Test $id", "Radio test", durationMs = 30_000)
    private fun item(track: Track): MediaItem = MediaItem.Builder().setMediaId(track.id).setUri(Uri.fromFile(audio))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title)
            .setExtras(Bundle().apply { putString("track", JsonCodec.encodeTrack(track).toString()) }).build()).build()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun until(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) { if (main(condition)) return; Thread.sleep(50) }
        fail("Timed out; state=${main { player.playbackState }}, calls=${calls.get()}")
    }
    private fun ids() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
    private fun start() {
        main { player.setMediaItem(item(track("1"))); player.prepare(); player.play() }
        until { player.isPlaying && player.duration > 0 }
    }
    private fun nearEnd() = main { player.seekTo(20_000) }

    @Before fun prepare() {
        audio = File(instrumentation.targetContext.cacheDir, "radio-test.flac")
        instrumentation.context.assets.open("night-walk.flac").use { input -> audio.outputStream().use(input::copyTo) }
        main {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            radio = PlaybackRadio(player, scope, { settings }, { "test-account" },
                { calls.incrementAndGet(); provider(it) }, { emptySet() }, ::item, { sleep })
        }
    }
    @After fun release() { main { radio.release(); scope.cancel(); player.release() }; audio.delete() }

    @Test fun preloadsBeforeEndWithoutActivityAndPreservesFlacOutput() {
        start(); nearEnd()
        until { player.mediaItemCount == 3 }
        main {
            assertEquals(listOf("1", "2", "3"), ids())
            assertEquals("1", player.currentMediaItem?.mediaId)
            assertTrue(player.isPlaying)
            assertEquals("audio/flac", player.audioFormat?.sampleMimeType)
            assertEquals(96_000, player.audioFormat?.sampleRate)
        }
        assertEquals(1, calls.get())
    }

    @Test fun exhaustedBatchRenewsUsingCurrentTrackWhenOriginalRadioHasNoNewTracks() {
        provider = { seed -> if (seed == "1") listOf(track("2"), track("3")) else listOf(track("4")) }
        start(); nearEnd(); until { player.mediaItemCount == 3 }
        main { player.seekTo(2, 20_000) }
        until { ids().contains("4") }
        main { assertEquals(listOf("1", "2", "3", "4"), ids()); assertTrue(player.isPlaying) }
    }

    @Test fun explicitRadioStartsFirstBatchWithAutoplayOff() {
        main { settings = settings.copy(autoplay = false); radio.settingsChanged() }
        start()
        main { radio.startTrackRadio() }
        until { player.mediaItemCount == 3 }
        main { assertEquals("1", player.currentMediaItem?.mediaId); assertTrue(player.isPlaying) }
    }

    @Test fun queuePersistenceRestoresAutomaticFlagsAndStaysPaused() {
        val prefs = instrumentation.targetContext.getSharedPreferences("radio-persistence-test", 0)
        prefs.edit().clear().putString("queue", JsonCodec.encodeTracks(listOf(track("1"), track("2"))))
            .putStringSet("radioIndices", setOf("1")).putInt("index", 0).putLong("position", 3_000).commit()
        try {
            main { io.github.yyan115.opentune.playback.QueuePersistence(player, prefs, scope, ::item) }
            until { player.mediaItemCount == 2 }
            main {
                assertEquals(listOf("1", "2"), ids())
                assertTrue(PlaybackRadio.isRadio(player.getMediaItemAt(1)))
                assertFalse(player.playWhenReady)
                assertEquals(3_000L, player.currentPosition)
            }
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun manualAppendPrecedesPrefetchedRadioAndKeepsItsOrder() {
        start(); nearEnd(); until { player.mediaItemCount == 3 }
        main { player.addMediaItems(listOf(item(track("manual-a")), item(track("manual-b")))) }
        until { ids() == listOf("1", "manual-a", "manual-b", "2", "3") }
    }

    @Test fun replacingPlaylistDiscardsPendingRadio() {
        val response = CompletableDeferred<List<Track>>()
        provider = { response.await() }
        start(); nearEnd(); until { calls.get() == 1 }
        main { radio.newQueue(); player.setMediaItems(listOf(item(track("album-a")), item(track("album-b")))); response.complete(listOf(track("stale"))) }
        Thread.sleep(500)
        main { assertEquals(listOf("album-a", "album-b"), ids()) }
    }

    @Test fun disablingAutoplayRemovesOnlyUpcomingAutomaticItems() {
        start(); nearEnd(); until { player.mediaItemCount == 3 }
        main {
            player.addMediaItem(item(track("manual")))
            settings = settings.copy(autoplay = false); radio.settingsChanged()
            assertEquals(listOf("1", "manual"), ids())
            assertTrue(player.isPlaying)
        }
    }

    @Test fun repeatModesAndShuffleArePreserved() {
        main { player.repeatMode = Player.REPEAT_MODE_ONE; player.shuffleModeEnabled = true }
        start(); nearEnd(); Thread.sleep(500)
        main { assertEquals(1, player.mediaItemCount); assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode); assertTrue(player.shuffleModeEnabled) }
        main { player.repeatMode = Player.REPEAT_MODE_ALL }; Thread.sleep(300)
        main { assertEquals(1, player.mediaItemCount); assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode) }
        assertEquals(0, calls.get())
    }

    @Test fun sleepPauseCannotBeUndoneByDelayedRadio() {
        val response = CompletableDeferred<List<Track>>()
        provider = { response.await() }
        start(); nearEnd(); until { calls.get() == 1 }
        main { sleep = true; player.pause(); response.complete(listOf(track("late"))) }
        Thread.sleep(500)
        main { assertFalse(player.playWhenReady); assertEquals(listOf("1"), ids()) }
    }

    @Test fun offlineAndLocalPlaybackDoNotRequestRadio() {
        main { settings = settings.copy(offline = true); radio.settingsChanged() }
        start(); nearEnd(); Thread.sleep(500)
        main { settings = settings.copy(offline = false); player.setMediaItem(item(track("local:file"))); player.prepare(); player.play(); radio.settingsChanged() }
        until { player.isPlaying }; nearEnd(); Thread.sleep(500)
        assertEquals(0, calls.get())
    }
}
