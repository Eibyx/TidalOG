package io.github.yyan115.opentune.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoRadioTest {
    private fun track(id: String) = Track(id, "Track $id", "Artist")
    private inner class Fixture(scope: TestScope) {
        var state = RadioSnapshot(track("seed"), setOf("seed"), "seed", "account", true, false, true, true, false, 25_000)
        val requests = mutableListOf<String>()
        val appended = mutableListOf<Track>()
        var response: suspend (String) -> List<Track> = { listOf(track("related")) }
        var recent = emptySet<String>()
        val radio = AutoRadio(scope, { state }, { requests += it; response(it) }, { recent }, { appended += it }, StandardTestDispatcher(scope.testScheduler))
    }

    @Test fun selectingFifthOfTenQueuesOnlyTheSelectedTrack() {
        val results = (1..10).map { track("$it") }
        assertEquals(listOf(results[4]), SearchPlayback.selected(results[4]))
    }

    @Test fun topHitDoesNotNeedToBeInTheTracksList() {
        val top = track("top")
        val result = SearchResult(tracks = listOf(track("other")), topTrack = top)
        assertEquals(listOf(top), SearchPlayback.selected(SearchPlayback.best(result)!!))
    }

    @Test fun voiceSelectsOneBestMatchOrOneTrackFallback() {
        assertEquals("top", SearchPlayback.best(SearchResult(tracks = listOf(track("one"), track("two")), topTrack = track("top")))?.id)
        assertEquals("one", SearchPlayback.best(SearchResult(tracks = listOf(track("one"), track("two"))))?.id)
        assertNull(SearchPlayback.best(SearchResult()))
    }

    @Test fun preloadsOnlyNearEndAndUsesSelectedSeed() = runTest {
        val f = Fixture(this)
        f.state = f.state.copy(remainingMs = 25_001)
        f.radio.tick(); advanceUntilIdle(); assertTrue(f.requests.isEmpty())
        f.state = f.state.copy(remainingMs = 25_000)
        f.radio.tick(); advanceUntilIdle()
        assertEquals(listOf("seed"), f.requests)
        assertEquals(listOf("related"), f.appended.map { it.id })
    }

    @Test fun deduplicatesQueueResponseHistoryAndRejectsLocalFilesStrictly() = runTest {
        val f = Fixture(this); f.recent = setOf("recent")
        f.response = { listOf(track("seed"), track("recent"), track("related"), track("related"), track("local:file")) }
        f.radio.tick(); advanceUntilIdle()
        assertEquals(listOf("related"), f.appended.map { it.id })
    }

    @Test fun allRecentTracksAreNotReintroducedAsFallback() = runTest {
        val f = Fixture(this); f.recent = setOf("related")
        f.radio.tick(); advanceUntilIdle()
        assertTrue(f.appended.isEmpty()); assertEquals(1, f.requests.size)
    }

    @Test fun concurrentTicksDoNotDuplicateRequestsAndBatchIsBounded() = runTest {
        val f = Fixture(this)
        f.response = { delay(1_000); (1..100).map { track("$it") } }
        repeat(30) { f.radio.tick() }; advanceUntilIdle()
        repeat(30) { f.radio.tick() }; advanceUntilIdle()
        assertEquals(1, f.requests.size); assertEquals(AutoRadio.MAX_BATCH, f.appended.size)
    }

    @Test fun shortRecommendationsCannotExceedRemainingPreloadCapacity() = runTest {
        val f = Fixture(this); f.state = f.state.copy(capacity = 2)
        f.response = { (1..100).map { track("$it") } }
        f.radio.tick(); advanceUntilIdle(); assertEquals(2, f.appended.size)
        f.state = f.state.copy(capacity = 0); f.radio.invalidate(); f.radio.tick(); advanceUntilIdle()
        assertEquals(1, f.requests.size)
    }

    @Test fun changingSongDiscardsDelayedResponseEvenIfProviderIgnoresCancellation() = runTest {
        val f = Fixture(this)
        f.response = { withContext(NonCancellable) { delay(1_000); listOf(track("late")) } }
        f.radio.tick(); runCurrent()
        f.state = f.state.copy(current = track("new"), queueIds = setOf("seed", "new"))
        f.radio.invalidate(); advanceUntilIdle()
        assertTrue(f.appended.isEmpty())
    }

    @Test fun replacingPlaylistWithTheSameCurrentTrackStillCancelsOldGeneration() = runTest {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        f.response = { started.complete(Unit); delay(2_000); listOf(track("old-playlist-radio")) }
        f.radio.tick(); runCurrent(); assertTrue(started.isCompleted)
        f.radio.invalidate(); f.state = f.state.copy(queueIds = setOf("seed", "new-playlist"))
        advanceUntilIdle(); assertTrue(f.appended.isEmpty())
    }

    @Test fun settingsOrAccountChangeRejectsResponseWithoutDependingOnUiEvents() = runTest {
        for (change in listOf<(RadioSnapshot) -> RadioSnapshot>(
            { it.copy(autoplay = false) }, { it.copy(offline = true) }, { it.copy(accountId = "other-account") },
            { it.copy(playWhenReady = false) }, { it.copy(sleepAtTrackEnd = true) }, { it.copy(repeatOff = false) },
        )) {
            val f = Fixture(this); f.response = { delay(1_000); listOf(track("late")) }
            f.radio.tick(); runCurrent(); f.state = change(f.state); advanceUntilIdle()
            assertTrue(f.appended.isEmpty())
        }
    }

    @Test fun autoplayOfflineLocalSignedOutRepeatAndTimerPreventRequests() = runTest {
        val f = Fixture(this)
        val original = f.state
        for (disabled in listOf(original.copy(autoplay = false), original.copy(offline = true),
            original.copy(current = track("local:file")), original.copy(accountId = ""),
            original.copy(repeatOff = false), original.copy(sleepAtTrackEnd = true), original.copy(playWhenReady = false))) {
            f.state = disabled; f.radio.tick(); advanceUntilIdle()
        }
        assertTrue(f.requests.isEmpty())
    }

    @Test fun manualQueueEditWinsOverPendingRecommendations() = runTest {
        val f = Fixture(this); f.response = { delay(1_000); listOf(track("late")) }
        f.radio.tick(); runCurrent()
        f.state = f.state.copy(queueIds = setOf("seed", "manual"), remainingMs = 180_000)
        f.radio.invalidate(); f.radio.tick(); advanceUntilIdle()
        assertTrue(f.appended.isEmpty()); assertEquals(1, f.requests.size)
    }

    @Test fun eachExhaustedBatchCanRenewAndFallbackToCurrentTrackOnce() = runTest {
        val f = Fixture(this)
        f.radio.tick(); advanceUntilIdle()
        f.state = f.state.copy(current = track("related"), queueIds = setOf("seed", "related"))
        f.radio.invalidate()
        f.response = { if (it == "seed") listOf(track("related")) else listOf(track("next")) }
        f.radio.tick(); advanceUntilIdle()
        assertEquals(listOf("seed", "seed", "related"), f.requests)
        assertEquals(listOf("related", "next"), f.appended.map { it.id })
    }

    @Test fun emptyResponseHasOneFallbackAndNoPollingLoop() = runTest {
        val f = Fixture(this); f.state = f.state.copy(current = track("current"))
        f.response = { emptyList() }
        f.radio.tick(); advanceUntilIdle(); repeat(10) { f.radio.tick() }; advanceUntilIdle()
        assertEquals(listOf("seed", "current"), f.requests); assertTrue(f.appended.isEmpty())
    }

    @Test fun transientFailuresBackOffAndStopAfterThreeAttempts() = runTest {
        val f = Fixture(this); f.response = { throw IOException("network") }
        f.radio.tick(); runCurrent(); assertEquals(1, f.requests.size)
        advanceTimeBy(1_999); runCurrent(); assertEquals(1, f.requests.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, f.requests.size)
        advanceUntilIdle(); repeat(10) { f.radio.tick() }; advanceUntilIdle()
        assertEquals(3, f.requests.size); assertTrue(f.appended.isEmpty())
    }

    @Test fun serverFailureCanRecoverButAuthAndRateLimitsAreNotRetried() = runTest {
        val recover = Fixture(this)
        recover.response = { if (recover.requests.size < 3) throw ServiceException(503, "busy") else listOf(track("ok")) }
        recover.radio.tick(); advanceUntilIdle(); assertEquals("ok", recover.appended.single().id)
        for (status in listOf(401, 403, 404, 429)) {
            val f = Fixture(this); f.response = { throw ServiceException(status, "restricted") }
            f.radio.tick(); advanceUntilIdle(); f.radio.tick(); advanceUntilIdle()
            assertEquals(1, f.requests.size); assertTrue(f.appended.isEmpty())
        }
    }

    @Test fun unknownDurationDoesNotGuessThatAPlaylistHasEnded() = runTest {
        val f = Fixture(this); f.state = f.state.copy(remainingMs = null)
        f.radio.tick(); advanceUntilIdle(); assertTrue(f.requests.isEmpty())
    }
}
