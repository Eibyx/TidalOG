package io.github.yyan115.opentune

import android.content.ComponentName
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yyan115.opentune.core.AudioQuality
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.playback.PlaybackService
import io.github.yyan115.opentune.ui.MainViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in playback check with a real account: a long Max-quality track must start quickly, keep a deep buffer and never stall. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackSoakLiveTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenTuneApp
    @get:Rule(order = 0) val account = LiveAccount.rule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    /** Stops the player and empties its queue, so that a test never measures music that an earlier test left playing. */
    private fun silence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = onMain { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }.get()
        onMain { controller.stop(); controller.clearMediaItems() }
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && onMain { controller.mediaItemCount } > 0) Thread.sleep(200)
        onMain { controller.release() }
    }

    @Before fun startFromSilence() = silence()

    @After fun endInSilence() = silence()

    private data class Snapshot(val state: Int, val playing: Boolean, val ahead: Long, val position: Long)

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun longHiResTrack(): Track {
        val candidates = listOf("Pink Floyd Comfortably Numb", "Miles Davis So What", "Eagles Hotel California", "Led Zeppelin Stairway to Heaven")
        var best: Track? = null
        for (query in candidates) {
            compose.runOnIdle { model.search(query) }
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && model.state.value.search.tracks.isEmpty()) Thread.sleep(300)
            val found = model.state.value.search.tracks.filter { it.durationMs >= 240_000 }
            val hires = found.firstOrNull { "HIRES_LOSSLESS" in it.tags }
            if (hires != null) return hires
            if (best == null) best = found.maxByOrNull { it.durationMs }
        }
        return requireNotNull(best) { "No long track was found. signedIn=${model.state.value.signedIn} offline=${model.state.value.settings.offline} error=${model.state.value.searchError} results=${model.state.value.search.tracks.size}" }
    }

    @Test fun maxQualityTrackStartsFastBuffersDeepAndNeverStalls() {
        compose.runOnIdle { model.settings { it.copy(offline = false, quality = AudioQuality.HI_RES_LOSSLESS, mobileQuality = AudioQuality.HI_RES_LOSSLESS, normalization = "off") } }
        val track = longHiResTrack()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = onMain { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }.get()
        val begin = System.currentTimeMillis()
        compose.runOnIdle { model.play(listOf(track)) }
        var firstAudio = -1L; var stalls = 0; var stalled = false; var stallStart = 0L; var stallTotal = 0L; var longestStall = 0L; var maxAhead = 0L
        val samples = StringBuilder()
        val length = 150_000L
        while (System.currentTimeMillis() - begin < length) {
            val now = System.currentTimeMillis() - begin
            val (state, playing, ahead, position) = onMain { Snapshot(controller.playbackState, controller.isPlaying, controller.bufferedPosition - controller.currentPosition, controller.currentPosition) }
            if (playing && firstAudio < 0) firstAudio = now
            if (firstAudio >= 0) {
                val buffering = state == Player.STATE_BUFFERING
                if (buffering && !stalled) { stalled = true; stallStart = now; stalls++ }
                if (!buffering && stalled) { stalled = false; val d = now - stallStart; stallTotal += d; if (d > longestStall) longestStall = d }
                if (ahead > maxAhead) maxAhead = ahead
            }
            if (now % 10_000 < 500) samples.append("t=${now / 1000}s state=$state ahead=${ahead / 1000}s position=${position / 1000}s\n")
            Thread.sleep(400)
        }
        val quality = model.state.value.playback.quality
        val report = "track=${track.title} (${track.durationMs / 1000}s, tags=${track.tags}) quality=$quality firstAudio=${firstAudio}ms stalls=$stalls stallTotal=${stallTotal}ms longestStall=${longestStall}ms maxBufferedAhead=${maxAhead / 1000}s\n$samples"
        val directory = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: File(app.getExternalFilesDir(null), "reports")).apply { mkdirs() }
        File(directory, "playback-soak.txt").writeText(report)
        android.util.Log.i("PlaybackSoak", report)
        onMain { controller.release() }
        assertTrue("Playback did not start within 5 s.\n$report", firstAudio in 0..5_000)
        assertTrue("Playback stalled $stalls times.\n$report", stalls == 0)
        assertTrue("Selected lossless quality was lost.\n$report", quality.contains("FLAC", ignoreCase = true))
        assertTrue("The player buffered only ${maxAhead / 1000} s ahead.\n$report", maxAhead >= 45_000)
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    private fun network(enabled: Boolean) { val action = if (enabled) "enable" else "disable"; shell("svc wifi $action"); shell("svc data $action") }

    /** A short outage passes unheard inside the buffer. After a long one the player resumes by itself once the network returns. */
    @Test fun playbackSurvivesAShortOutageAndResumesByItselfAfterALongOne() {
        compose.runOnIdle { model.settings { it.copy(offline = false, quality = AudioQuality.HI_RES_LOSSLESS, mobileQuality = AudioQuality.HI_RES_LOSSLESS, normalization = "off") } }
        val track = longHiResTrack()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = onMain { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }.get()
        try {
            compose.runOnIdle { model.play(listOf(track)) }
            fun snapshot() = onMain { Snapshot(controller.playbackState, controller.isPlaying, controller.bufferedPosition - controller.currentPosition, controller.currentPosition) }
            fun until(what: String, timeoutMs: Long, condition: (Snapshot) -> Boolean): Snapshot {
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) { val now = snapshot(); if (condition(now)) return now; Thread.sleep(300) }
                throw AssertionError("Timed out waiting for $what. Last state: ${snapshot()}")
            }
            until("a full buffer", 60_000) { it.playing && it.ahead >= 45_000 }
            // A twenty second outage must not interrupt the music.
            val before = snapshot(); network(false)
            var interrupted = false
            val outageEnd = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < outageEnd) { val now = snapshot(); if (!now.playing) interrupted = true; Thread.sleep(300) }
            network(true)
            val after = snapshot()
            assertTrue("Music stopped during a 20 second outage. Before $before, after $after", !interrupted && after.position - before.position >= 15_000)
            // A long outage drains the buffer. The player must pick the music up again without help once the network is back.
            until("a full buffer again", 60_000) { it.playing && it.ahead >= 45_000 }
            val full = snapshot(); network(false)
            until("the music to stop after the buffer ran out", full.ahead + 60_000) { !it.playing }
            Thread.sleep(6_000); network(true)
            val resumed = until("playback to resume by itself", 90_000) { it.playing }
            assertTrue("Playback resumed, but at the wrong place: $resumed", resumed.position > 0)
        } finally {
            network(true)
            onMain { controller.release() }
        }
    }

    /** At the end of the queue, autoplay adds similar tracks and keeps playing. */
    @Test fun autoplayContinuesWithSimilarMusicWhenTheQueueEnds() {
        compose.runOnIdle { model.settings { it.copy(offline = false, autoplay = true, quality = AudioQuality.LOSSLESS, mobileQuality = AudioQuality.LOSSLESS) } }
        val track = longHiResTrack()
        compose.runOnIdle { model.play(listOf(track)) }
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && !(model.state.value.playback.playing && model.state.value.playback.duration > 0)) Thread.sleep(300)
        assertTrue("The track started", model.state.value.playback.playing)
        compose.runOnIdle { model.seek(model.state.value.playback.duration - 24_000) }
        val preloadDeadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < preloadDeadline && model.state.value.playback.queue.size == 1) Thread.sleep(200)
        assertTrue("Radio was prefetched before the selected track ended", model.state.value.playback.queue.size > 1 && model.state.value.playback.track?.id == track.id)
        compose.runOnIdle { model.seek(model.state.value.playback.duration - 1_000) }
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end && !(model.state.value.playback.track?.id != track.id && model.state.value.playback.playing)) Thread.sleep(400)
        val queue = model.state.value.playback.queue
        assertTrue("Autoplay added tracks after the last one (queue of ${queue.size})", queue.size > 1)
        assertTrue("The music kept playing", model.state.value.playback.playing)
        assertTrue("The new track differs from the one that ended", model.state.value.playback.track?.id != track.id)
    }
}
