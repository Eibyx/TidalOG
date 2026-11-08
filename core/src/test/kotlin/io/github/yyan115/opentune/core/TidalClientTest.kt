package io.github.yyan115.opentune.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl

class TidalClientTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryStore
    private lateinit var client: TidalClient
    @Before fun setUp() {
        server = MockWebServer(); server.start()
        store = MemoryStore(Session("access-before", "refresh-before", System.currentTimeMillis() + 3_600_000, "123", "IN"))
        client = TidalClient({ ClientIdentity("test-client", "test-secret") }, store,
            apiBase = server.url("/v1/").toString(), authBase = server.url("/oauth/").toString(), apiBaseV2 = server.url("/v2/").toString())
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun encodesSearchAndUsesAccountCountry() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[{"id":42,"title":"Song","duration":182,"artists":[{"name":"First"},{"name":"Second"}],"album":{"id":7,"title":"Album","cover":"a-b-c"}}]}}"""))
        val result = client.search("music & friends")
        assertEquals("First, Second", result.tracks.single().artist)
        assertEquals(182000L, result.tracks.single().durationMs)
        val request = server.takeRequest()
        assertEquals("music & friends", request.requestUrl!!.queryParameter("query"))
        assertEquals("IN", request.requestUrl!!.queryParameter("countryCode"))
        assertEquals("Bearer access-before", request.getHeader("Authorization"))
    }

    @Test fun renewsOnceAfter401AndKeepsRotatedRefreshToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"access_token":"access-after","refresh_token":"refresh-after","expires_in":3600}"""))
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[]}}"""))
        client.search("song")
        server.takeRequest()
        val refresh = server.takeRequest()
        assertEquals("/oauth/token", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("refresh_token=refresh-before"))
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("Bearer access-after", server.takeRequest().getHeader("Authorization"))
        assertEquals("refresh-after", store.read()!!.refreshToken)
        assertEquals("123", store.read()!!.userId)
    }

    @Test fun preservesRefreshTokenWhenServerDoesNotRotateIt() = runBlocking {
        store.value = store.value!!.copy(expiresAt = 0)
        server.enqueue(MockResponse().setBody("""{"access_token":"new","expires_in":3600}"""))
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[]}}"""))
        client.search("song")
        assertEquals("refresh-before", store.read()!!.refreshToken)
    }

    @Test fun doesNotExposeServerErrorBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("private-token-from-server"))
        val error = try { client.search("song"); null } catch (error: ServiceException) { error }
        assertNotNull(error)
        assertFalse(error!!.message!!.contains("private-token"))
        assertEquals(403, error.status)
    }

    @Test fun sensitiveModelsDoNotLogTheirValues() {
        assertFalse(store.read().toString().contains("access-before"))
        assertFalse(store.read().toString().contains("refresh-before"))
        assertFalse(ClientIdentity("sensitive-client", "sensitive-secret").toString().contains("sensitive"))
    }

    @Test fun losslessRequestRejectsAacInsteadOfSilentlyDowngrading() = runBlocking {
        server.enqueue(MockResponse().setBody(streamResponse("mp4a.40.2")))
        try { client.stream("42", AudioQuality.LOSSLESS); fail("Lossy audio was accepted for a lossless request") }
        catch (error: ServiceException) { assertEquals(406, error.status); assertTrue(error.message.orEmpty().contains("lossless")) }
    }

    @Test fun explicitAacQualityRemainsAvailable() = runBlocking {
        server.enqueue(MockResponse().setBody(streamResponse("mp4a.40.2")))
        assertEquals("mp4a.40.2", client.stream("42", AudioQuality.HIGH).codec)
    }

    @Test fun flacIsLosslessEvenWhenItsContainerIsMp4() = runBlocking {
        server.enqueue(MockResponse().setBody(streamResponse("flac")))
        val stream = client.stream("42", AudioQuality.HI_RES_LOSSLESS)
        assertEquals("audio/mp4", stream.mimeType)
        assertEquals("flac", stream.codec)
    }

    private fun streamResponse(codec: String): String {
        val manifest = java.util.Base64.getEncoder().encodeToString("""{"mimeType":"audio/mp4","codecs":"$codec","encryptionType":"NONE","urls":["https://audio.example/track.mp4"]}""".toByteArray())
        return """{"assetPresentation":"FULL","manifestMimeType":"application/vnd.tidal.bts","manifest":"$manifest","audioQuality":"LOSSLESS"}"""
    }

    @Test fun signingOutDuringRefreshCannotRestoreTheAccount() = runBlocking {
        store.value = store.value!!.copy(expiresAt = 0)
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                return MockResponse().setBody("""{"access_token":"late-access","refresh_token":"late-refresh","expires_in":3600}""")
            }
        }
        val pending = async(Dispatchers.IO) { runCatching { client.search("song") } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        client.signOut()
        finish.countDown()
        assertTrue(pending.await().isFailure)
        assertNull(store.read())
        assertFalse(client.signedIn)
    }

    @Test fun readsEveryPageOfALargePlaylistIncludingDuplicates() = runBlocking {
        val first = (1..100).joinToString(",") { """{"item":{"id":$it,"title":"Track $it"}}""" }
        server.enqueue(MockResponse().setBody("""{"items":[$first]}"""))
        server.enqueue(MockResponse().setBody("""{"items":[{"item":{"id":1,"title":"Track 1"}}]}"""))
        val tracks = client.allTracks(Release("playlist", "List", "", type = ReleaseType.PLAYLIST))
        assertEquals(101, tracks.size)
        assertEquals(tracks.first().id, tracks.last().id)
        assertEquals("0", server.takeRequest().requestUrl!!.queryParameter("offset"))
        assertEquals("100", server.takeRequest().requestUrl!!.queryParameter("offset"))
    }

    @Test fun playlistRemovalRefusesAStaleTrackPosition() = runBlocking {
        server.enqueue(MockResponse().setHeader("ETag", "fresh-version").setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"items":[{"item":{"id":999,"title":"Different track"}}]}"""))
        try { client.removeFromPlaylist("playlist", 3, "42"); fail("Stale position was deleted") }
        catch (error: ServiceException) { assertEquals(409, error.status) }
        assertEquals(2, server.requestCount)
    }

    @Test fun playlistRemovalUsesTheVersionItChecked() = runBlocking {
        server.enqueue(MockResponse().setHeader("ETag", "checked-version").setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"items":[{"item":{"id":42,"title":"Track"}}]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        client.removeFromPlaylist("playlist", 3, "42")
        server.takeRequest(); server.takeRequest()
        val deletion = server.takeRequest()
        assertEquals("DELETE", deletion.method)
        assertEquals("checked-version", deletion.getHeader("If-None-Match"))
        assertEquals("/v1/playlists/playlist/items/3", deletion.requestUrl!!.encodedPath)
    }

    @Test fun pkceUsesASha256ChallengeAndMinimalScopes() {
        val login = client.beginPkce()
        val url = login.authorizationUrl.toHttpUrl()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(login.verifier.toByteArray())
        assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest), url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("r_usr w_usr", url.queryParameter("scope"))
        assertFalse(login.toString().contains(login.verifier))
        assertFalse(login.toString().contains(login.state))
        assertNotEquals(login.state, client.beginPkce().state)
    }

    @Test fun loadsOwnedAndFollowedPlaylistsAcrossFiftyItemPages() = runBlocking {
        val page = (1..50).joinToString(",") { """{"playlist":{"uuid":"list-$it","title":"List $it","creator":{"id":123}}}""" }
        server.enqueue(MockResponse().setBody("""{"items":[$page]}"""))
        server.enqueue(MockResponse().setBody("""{"items":[{"playlist":{"uuid":"followed","title":"Followed","creator":{"id":456}}}]}"""))
        val lists = client.allPlaylists()
        assertEquals(51, lists.size)
        assertEquals("123", lists.first().ownerId)
        assertEquals("456", lists.last().ownerId)
        val first = server.takeRequest()
        assertEquals("/v1/users/123/playlistsAndFavoritePlaylists", first.requestUrl!!.encodedPath)
        assertEquals("50", first.requestUrl!!.queryParameter("limit"))
        assertEquals("50", server.takeRequest().requestUrl!!.queryParameter("offset"))
    }

    @Test fun pkceRejectsWrongOriginStateAndExpiredRequestsWithoutSendingCodes() = runBlocking {
        val login = client.beginPkce()
        val callbacks = listOf(
            "http://tidal.com/android/login/auth?code=private-code&state=${login.state}",
            "https://tidal.com.attacker.example/android/login/auth?code=private-code&state=${login.state}",
            "tidal://login/auth?code=private-code&state=wrong-state",
            "tidal://login/auth?error=access_denied&state=${login.state}",
            "tidal://login/auth?code=private-code&state=${login.state}&state=duplicate",
            "tidal://login/auth?code=first&code=second&state=${login.state}")
        callbacks.forEach { callback ->
            try { client.completePkce(login, callback); fail("Untrusted callback accepted") }
            catch (error: ServiceException) { assertFalse(error.message.orEmpty().contains("private-code")) }
        }
        try { client.completePkce(login.copy(expiresAt = 0), "tidal://login/auth?code=private-code&state=${login.state}"); fail("Expired request accepted") }
        catch (_: ServiceException) { }
        assertEquals(0, server.requestCount)
    }

    @Test fun pkceExchangesVerifierAndStoresTheSession() = runBlocking {
        val login = client.beginPkce()
        server.enqueue(MockResponse().setBody("""{"access_token":"browser-access","refresh_token":"browser-refresh","expires_in":3600}"""))
        server.enqueue(MockResponse().setBody("""{"userId":456,"countryCode":"IN"}"""))
        client.completePkce(login, "tidal://login/auth?code=one-time-code&state=${login.state}")
        val exchange = server.takeRequest().body.readUtf8()
        assertTrue(exchange.contains("code_verifier=${login.verifier}"))
        assertTrue(exchange.contains("redirect_uri=tidal%3A%2F%2Flogin%2Fauth"))
        assertFalse(exchange.contains("w_sub"))
        assertEquals("456", store.read()!!.userId)
    }

    @Test fun lyricsReturnTimedLinesAndMissingLyricsAreNull() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"lyrics":"One","subtitles":"[00:01.00] One","lyricsProvider":"MUSIXMATCH","isRightToLeft":false}"""))
        val lyrics = client.lyrics("42")!!
        assertTrue(lyrics.synced)
        val request = server.takeRequest()
        assertEquals("/v1/tracks/42/lyrics", request.requestUrl!!.encodedPath)
        assertEquals("PHONE", request.requestUrl!!.queryParameter("deviceType"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status":404}"""))
        assertNull(client.lyrics("43"))
    }

    @Test fun pagesAreRequestedWithLocaleAndDeviceType() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"x","title":"T","rows":[]}"""))
        val page = client.page("pages/artist", mapOf("artistId" to "7"))
        assertEquals("T", page.title)
        val request = server.takeRequest()
        assertEquals("7", request.requestUrl!!.queryParameter("artistId"))
        assertEquals("en_US", request.requestUrl!!.queryParameter("locale"))
        assertEquals("IN", request.requestUrl!!.queryParameter("countryCode"))
    }

    @Test fun savingAPlaylistPostsItsIdAndRemovingItDeletesTheEntry() = runBlocking {
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody("{}"))
        client.setPlaylistFavorite("abc-123", true)
        val save = server.takeRequest()
        assertEquals("POST", save.method)
        assertEquals("/v1/users/123/favorites/playlists", save.requestUrl!!.encodedPath)
        assertEquals("uuids=abc-123", save.body.readUtf8())
        client.setPlaylistFavorite("abc-123", false)
        val remove = server.takeRequest()
        assertEquals("DELETE", remove.method)
        assertEquals("/v1/users/123/favorites/playlists/abc-123", remove.requestUrl!!.encodedPath)
    }

    @Test fun severalTracksJoinAPlaylistInOneRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"numberOfTracks":4}""").setHeader("ETag", "\"7\""))
        server.enqueue(MockResponse().setBody("{}"))
        client.addToPlaylist("playlist", listOf("11", "12", "13"))
        server.takeRequest()
        val add = server.takeRequest()
        assertEquals("POST", add.method)
        assertEquals("/v1/playlists/playlist/items", add.requestUrl!!.encodedPath)
        val body = add.body.readUtf8()
        assertTrue(body, "trackIds=11%2C12%2C13" in body && "toIndex=4" in body && "onArtifactNotFound=SKIP" in body)
        try { client.addToPlaylist("playlist", listOf("local:file")); fail("A local file was uploaded") } catch (_: IllegalArgumentException) { }
    }

    @Test fun aRemovedTrackIsNotReportedAsAnExpiredSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401,"subStatus":4005,"userMessage":"Asset is not ready for playback"}"""))
        try { client.stream("1", AudioQuality.LOSSLESS); fail("A removed track played") }
        catch (error: ServiceException) { assertEquals(Problem.TRACK_UNAVAILABLE, error.problem) }
        assertEquals("A new sign-in is not tried for a removed track", 1, server.requestCount)
    }

    @Test fun pagesAskForTheLanguageOfTheDevice() = runBlocking {
        var language = "de_DE"
        val translated = TidalClient({ ClientIdentity("test-client", "test-secret") }, store,
            apiBase = server.url("/v1/").toString(), authBase = server.url("/oauth/").toString(), apiBaseV2 = server.url("/v2/").toString(), locale = { language })
        server.enqueue(MockResponse().setBody("""{"id":"x","title":"T","rows":[]}"""))
        translated.page("pages/home")
        assertEquals("de_DE", server.takeRequest().requestUrl!!.queryParameter("locale"))
        language = "fr_CA"
        server.enqueue(MockResponse().setBody("""{"id":"x","title":"T","rows":[]}"""))
        translated.page("pages/home")
        assertEquals("fr_CA", server.takeRequest().requestUrl!!.queryParameter("locale"))
    }

    @Test fun everyServiceFailureCarriesAProblemTheAppCanTranslate() {
        assertEquals(Problem.SESSION_EXPIRED, Problem.forStatus(401))
        assertEquals(Problem.HTTP_FORBIDDEN, Problem.forStatus(403))
        assertEquals(Problem.HTTP_NOT_FOUND, Problem.forStatus(404))
        assertEquals(Problem.HTTP_RATE_LIMIT, Problem.forStatus(429))
        assertEquals(Problem.HTTP_CONFLICT, Problem.forStatus(412))
        assertEquals(Problem.HTTP_SERVER, Problem.forStatus(503))
        assertEquals(Problem.HTTP_OTHER, Problem.forStatus(418))
        assertEquals(Problem.HTTP_OTHER, ServiceException(418, "teapot").problem)
    }

    @Test fun creditsAcceptAResponseThatIsAnArray() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"type":"Producer","contributors":[{"name":"A","id":1}]}]"""))
        val credits = client.credits("album", "9")
        assertEquals("Producer", credits.single().role)
        assertEquals("/v1/albums/9/credits", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun searchKeepsTheTopHitAndVideos() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[]},"albums":{"items":[]},"artists":{"items":[]},"playlists":{"items":[]},
            "videos":{"items":[{"id":5,"title":"Clip","imageId":"11111111-2222-3333-4444-555555555555","duration":60,"artists":[{"name":"X"}]}]},
            "topHit":{"type":"ARTISTS","value":{"id":9,"name":"Top Artist","picture":"11111111-2222-3333-4444-555555555555"}}}"""))
        val result = client.search("top")
        assertEquals("Top Artist", result.topRelease!!.title)
        assertEquals(ReleaseType.ARTIST, result.topRelease!!.type)
        assertEquals(ReleaseType.VIDEO, result.videos.single().type)
        assertTrue(server.takeRequest().requestUrl!!.queryParameter("types")!!.contains("VIDEOS"))
    }

    @Test fun videoManifestsYieldAnHttpsAddressAndRejectProtectedOnes() {
        fun response(manifest: String) = org.json.JSONObject().put("assetPresentation", "FULL")
            .put("manifest", java.util.Base64.getEncoder().encodeToString(manifest.toByteArray()))
        assertEquals("https://video.example/master.m3u8", StreamManifest.parseVideo(response("""{"urls":["https://video.example/master.m3u8"]}""")))
        try { StreamManifest.parseVideo(response("""{"urls":["http://video.example/a.m3u8"]}""")); fail("Plain HTTP accepted") } catch (_: ServiceException) { }
        try { StreamManifest.parseVideo(response("""{"urls":["https://v.example/a"],"encryptionType":"AES"}""")); fail("Protected video accepted") } catch (_: ProtectedStreamException) { }
    }

    @Test fun playlistFoldersListFoldersAndPlaylistsAcrossPages() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[
            {"itemType":"FOLDER","name":"Jazz","trn":"trn:folder:f1","data":{"id":"f1","name":"Jazz","totalNumberOfItems":3,"itemType":"FOLDER"}},
            {"itemType":"PLAYLIST","name":"Mix","trn":"trn:playlist:p1","data":{"uuid":"p1","title":"Mix","creator":{"id":123},"image":"11111111-2222-3333-4444-555555555555","numberOfTracks":9}}],"cursor":"next-page"}"""))
        server.enqueue(MockResponse().setBody("""{"items":[{"itemType":"PLAYLIST","name":"Later","trn":"trn:playlist:p2","data":{"uuid":"p2","title":"Later","creator":{"id":123}}}]}"""))
        val entries = client.playlistFolder()
        assertEquals(listOf(ReleaseType.FOLDER, ReleaseType.PLAYLIST, ReleaseType.PLAYLIST), entries.map { it.type })
        assertEquals(listOf("f1", "p1", "p2"), entries.map { it.id })
        assertEquals("3", entries[0].subtitle)
        assertEquals("123", entries[1].ownerId)
        val first = server.takeRequest()
        assertEquals("/v2/my-collection/playlists/folders", first.requestUrl!!.encodedPath)
        assertEquals("root", first.requestUrl!!.queryParameter("folderId"))
        assertEquals("next-page", server.takeRequest().requestUrl!!.queryParameter("cursor"))
    }

    @Test fun folderChangesUseTheExpectedRequests() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"itemType":"FOLDER","name":"New","trn":"trn:folder:f9","data":{"id":"f9","name":"New","totalNumberOfItems":0}}"""))
        val folder = client.createFolder(" New ")
        assertEquals("f9", folder.id)
        val create = server.takeRequest()
        assertEquals("PUT", create.method); assertEquals("New", create.requestUrl!!.queryParameter("name")); assertEquals("root", create.requestUrl!!.queryParameter("folderId"))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        client.renameFolder("f9", "Renamed"); client.moveToFolder("p1", "f9"); client.removeFolder("f9")
        val rename = server.takeRequest(); assertEquals("trn:folder:f9", rename.requestUrl!!.queryParameter("trn")); assertEquals("Renamed", rename.requestUrl!!.queryParameter("name"))
        val move = server.takeRequest(); assertEquals("trn:playlist:p1", move.requestUrl!!.queryParameter("trns")); assertEquals("f9", move.requestUrl!!.queryParameter("folderId"))
        val remove = server.takeRequest(); assertEquals("/v2/my-collection/playlists/folders/remove", remove.requestUrl!!.encodedPath); assertEquals("trn:folder:f9", remove.requestUrl!!.queryParameter("trns"))
    }

    @Test fun radioUsesTidalEndpointAndNeverSearch() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":43,"title":"Related"}]}"""))
        assertEquals("43", client.trackRadio("42").single().id)
        val request = server.takeRequest()
        assertEquals("/v1/tracks/42/radio", request.requestUrl!!.encodedPath)
        assertEquals("100", request.requestUrl!!.queryParameter("limit"))
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellingRadioCancelsItsHttpCall() = runBlocking {
        val cancelled = CountDownLatch(1)
        val http = okhttp3.OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
            override fun canceled(call: okhttp3.Call) { cancelled.countDown() }
        }).build()
        val cancellable = TidalClient({ ClientIdentity("client", "secret") }, store, http, apiBase = server.url("/v1/").toString())
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.IO) { cancellable.trackRadio("42") }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        pending.cancel()
        assertTrue("Cancellation did not close the radio call", cancelled.await(5, TimeUnit.SECONDS))
        pending.join()
    }

    private class MemoryStore(var value: Session?) : SessionStore {
        override fun read() = value
        override fun write(session: Session) { value = session }
        override fun clear() { value = null }
    }
}
