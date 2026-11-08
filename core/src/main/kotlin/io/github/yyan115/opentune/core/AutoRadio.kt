package io.github.yyan115.opentune.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A service-owned snapshot. All access to the coordinator and its callbacks uses the player's thread. */
data class RadioSnapshot(
    val current: Track?,
    val queueIds: Set<String>,
    val seedId: String?,
    val accountId: String,
    val autoplay: Boolean,
    val offline: Boolean,
    val playWhenReady: Boolean,
    val repeatOff: Boolean,
    val sleepAtTrackEnd: Boolean,
    val remainingMs: Long?,
    val capacity: Int = AutoRadio.MAX_BATCH,
) {
    val eligible get() = current != null && !current.isFile && autoplay && !offline && accountId.isNotBlank() &&
        playWhenReady && repeatOff && !sleepAtTrackEnd && capacity > 0
    val due get() = eligible && remainingMs != null && remainingMs <= PRELOAD_MS

    companion object { const val PRELOAD_MS = 25_000L }
}

/**
 * Uses only TIDAL radio, never text search. At most one request per queue revision/current track,
 * with two retries (2s/4s) for IO/5xx. 401/403/404/429 are not retried here.
 * An empty/fully filtered original-seed radio gets one attempt using the current track, then stops
 * until the track or queue changes. Recent plays are never reintroduced to fill a short batch.
 */
class AutoRadio(
    private val scope: CoroutineScope,
    private val snapshot: () -> RadioSnapshot,
    private val radio: suspend (String) -> List<Track>,
    private val recent: suspend () -> Set<String>,
    private val append: (List<Track>) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var generation = 0L
    private var job: Job? = null
    private var attempted: Pair<Long, String>? = null

    /** Call on queue edits, seeks, settings changes and service shutdown, including edits from other controllers. */
    fun invalidate() {
        generation++
        job?.cancel()
        job = null
        attempted = null
    }

    fun tick() {
        val state = snapshot()
        if (!state.eligible) { if (job != null || attempted != null) invalidate(); return }
        val current = state.current ?: return
        val key = generation to current.id
        if (!state.due || job?.isActive == true || attempted == key) return
        attempted = key
        job = scope.launch {
            fun valid(): Boolean {
                val now = snapshot()
                return generation == key.first && now.eligible && now.current?.id == current.id &&
                    now.seedId == state.seedId && now.accountId == state.accountId && now.queueIds == state.queueIds
            }
            try {
                val seeds = listOfNotNull(state.seedId, current.id).distinct()
                for (seed in seeds) {
                    val candidates = fetch(seed)
                    val excluded = withContext(io) { recent() }
                    currentCoroutineContext().ensureActive()
                    if (!valid()) return@launch
                    val usable = RepeatPolicy.radioCandidates(candidates, state.queueIds + current.id, excluded, minOf(MAX_BATCH, state.capacity))
                    if (usable.isNotEmpty()) { append(usable); return@launch }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Leave the current audio and queue alone. A new track/queue permits another bounded attempt.
            }
        }
    }

    private suspend fun fetch(seed: String): List<Track> {
        for (attempt in 0..2) {
            try { return withContext(io) { radio(seed) } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                val transient = if (error is ServiceException) error.status in 500..599 else error is IOException
                if (!transient || attempt == 2) throw error
                delay(2_000L shl attempt)
            }
        }
        return emptyList()
    }

    companion object { const val MAX_BATCH = 10 }
}

/** Search selection must never derive a queue or an index from sibling search results. */
object SearchPlayback {
    fun selected(track: Track): List<Track> = listOf(track)
    fun best(result: SearchResult): Track? = result.topTrack ?: result.tracks.firstOrNull()
}
