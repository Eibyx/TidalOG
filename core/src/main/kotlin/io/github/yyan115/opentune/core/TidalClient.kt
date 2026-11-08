package io.github.yyan115.opentune.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface SessionStore {
    fun read(): Session?
    fun write(session: Session)
    fun clear()
}

data class ClientIdentity(val id: String, val secret: String) {
    override fun toString() = "ClientIdentity(configured=${id.isNotBlank()})"
}

class TidalClient(
    private val identity: () -> ClientIdentity,
    private val sessions: SessionStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false).build(),
    private val apiBase: String = "https://api.tidal.com/v1/",
    private val authBase: String = "https://auth.tidal.com/v1/oauth2/",
    private val apiBaseV2: String = "https://api.tidal.com/v2/",
    /** The language and region for the text that TIDAL writes, such as `en_US`. */
    private val locale: () -> String = { "en_US" },
) {
    private val refreshLock = Mutex()
    private val generation = AtomicLong()
    private val accountLock = Any()
    private val etags = ConcurrentHashMap<String, String>()
    val signedIn get() = sessions.read() != null
    val accountId get() = sessions.read()?.userId.orEmpty()

    fun beginPkce(): PkceLogin {
        fun random(size: Int): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size).also { java.security.SecureRandom().nextBytes(it) })
        val verifier = random(32)
        val state = random(24)
        val unique = random(16)
        val challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val url = "https://login.tidal.com/authorize".toHttpUrl().newBuilder().apply {
            mapOf("response_type" to "code", "redirect_uri" to PKCE_REDIRECT, "client_id" to identity().id,
                "lang" to "EN", "appMode" to "android", "client_unique_key" to unique,
                "code_challenge" to challenge, "code_challenge_method" to "S256", "restrict_signup" to "true",
                "scope" to "r_usr w_usr", "state" to state).forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return PkceLogin(url.toString(), verifier, state, unique, System.currentTimeMillis() + 15 * 60_000)
    }

    suspend fun completePkce(login: PkceLogin, redirect: String) {
        if (System.currentTimeMillis() >= login.expiresAt) throw ServiceException(401, "This sign-in expired. Start again.", Problem.SIGN_IN_EXPIRED)
        val expectedRedirect = login.authorizationUrl.toHttpUrl().queryParameter("redirect_uri").orEmpty()
        if (!SignInCallback.matches(redirect, expectedRedirect)) {
            throw ServiceException(400, "This is not a TIDAL sign-in callback.", Problem.SIGN_IN_WRONG_CALLBACK)
        }
        val parameters = SignInCallback.parameters(redirect)
        val returnedState = parameters["state"]?.singleOrNull().orEmpty()
        if (!java.security.MessageDigest.isEqual(login.state.toByteArray(), returnedState.toByteArray())) throw ServiceException(400, "This response belongs to a different sign-in. Start again.", Problem.SIGN_IN_OTHER_ATTEMPT)
        if (parameters.containsKey("error")) throw ServiceException(401, "Sign-in was cancelled or declined on TIDAL. Start a new sign-in.", Problem.SIGN_IN_DECLINED)
        val code = parameters["code"]?.singleOrNull()?.takeIf(String::isNotBlank) ?: throw ServiceException(400, "TIDAL did not return an authorization code.", Problem.SIGN_IN_NO_CODE)
        val expectedGeneration = generation.get()
        val response = try { withContext(Dispatchers.IO) {
            form("token", mapOf("code" to code, "client_id" to identity().id, "grant_type" to "authorization_code",
                "redirect_uri" to expectedRedirect, "scope" to "r_usr w_usr", "code_verifier" to login.verifier, "client_unique_key" to login.uniqueKey))
        } } catch (error: OAuthException) {
            when (error.code) {
                "invalid_grant", "expired_token" -> throw ServiceException(401, "This sign-in expired or was already used. Start a new sign-in.", Problem.SIGN_IN_USED)
                "invalid_client", "unauthorized_client" -> throw ServiceException(401, "TIDAL rejected this app's sign-in configuration. Update OpenTune and try again.", Problem.SIGN_IN_CONFIG_REJECTED)
                else -> throw ServiceException(401, "TIDAL could not finish sign-in. Start a new sign-in and try again.", Problem.SIGN_IN_FAILED)
            }
        }
        authenticate(response, expectedGeneration)
    }

    suspend fun search(query: String): SearchResult {
        if (query.isBlank()) return SearchResult()
        val data = request("search", mapOf("query" to query, "types" to "TRACKS,ALBUMS,ARTISTS,PLAYLISTS,VIDEOS", "limit" to "50"))
        fun collection(key: String) = data.optJSONObject(key)?.let(JsonCodec::items).orEmpty()
        val top = data.optJSONObject("topHit")
        val topValue = top?.optJSONObject("value")
        val topType = top?.optString("type").orEmpty()
        return SearchResult(collection("tracks").map(JsonCodec::track),
            collection("albums").map { JsonCodec.release(it, ReleaseType.ALBUM) },
            collection("artists").map { JsonCodec.release(it, ReleaseType.ARTIST) },
            collection("playlists").map { JsonCodec.release(it, ReleaseType.PLAYLIST) },
            collection("videos").map { JsonCodec.release(it, ReleaseType.VIDEO) },
            topRelease = when (topType) {
                "ARTISTS" -> topValue?.let { JsonCodec.release(it, ReleaseType.ARTIST) }
                "ALBUMS" -> topValue?.let { JsonCodec.release(it, ReleaseType.ALBUM) }
                "PLAYLISTS" -> topValue?.let { JsonCodec.release(it, ReleaseType.PLAYLIST) }
                "VIDEOS" -> topValue?.let { JsonCodec.release(it, ReleaseType.VIDEO) }
                else -> null
            }?.takeIf { it.id.isNotBlank() },
            topTrack = if (topType == "TRACKS") topValue?.let(JsonCodec::track) else null)
    }

    suspend fun favorites(offset: Int = 0): List<Track> =
        JsonCodec.items(request("users/${userId()}/favorites/tracks", mapOf("limit" to "100", "offset" to "$offset", "order" to "DATE", "orderDirection" to "DESC"))).map(JsonCodec::track)

    suspend fun favoriteAlbums(offset: Int = 0): List<Release> =
        JsonCodec.items(request("users/${userId()}/favorites/albums", mapOf("limit" to "100", "offset" to "$offset"))).map { JsonCodec.release(it, ReleaseType.ALBUM) }

    suspend fun playlists(offset: Int = 0): List<Release> =
        JsonCodec.items(request("users/${userId()}/playlistsAndFavoritePlaylists", mapOf("limit" to "50", "offset" to "$offset")))
            .map { JsonCodec.release(it.optJSONObject("playlist") ?: it, ReleaseType.PLAYLIST) }

    suspend fun tracks(release: Release, offset: Int = 0): List<Track> {
        val path = when (release.type) {
            ReleaseType.ALBUM -> "albums/${release.id}/tracks"
            ReleaseType.PLAYLIST -> "playlists/${release.id}/items"
            ReleaseType.ARTIST -> "artists/${release.id}/toptracks"
            ReleaseType.MIX -> "mixes/${release.id}/items"
            ReleaseType.VIDEO, ReleaseType.FOLDER -> throw IllegalArgumentException("This item has no track list.")
        }
        return JsonCodec.items(request(path, mapOf("limit" to "100", "offset" to "$offset"))).map(JsonCodec::track)
    }

    suspend fun allTracks(release: Release): List<Track> = allPages { tracks(release, it) }
    suspend fun allFavorites(): List<Track> = allPages(page = ::favorites)
    suspend fun allFavoriteAlbums(): List<Release> = allPages(page = ::favoriteAlbums)
    suspend fun allPlaylists(): List<Release> = allPages(50, ::playlists)

    private suspend fun <T> allPages(pageSize: Int = 100, page: suspend (Int) -> List<T>): List<T> {
        val result = mutableListOf<T>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = page(offset)
            result.addAll(next)
            if (next.size < pageSize) return result
            offset += next.size
        }
    }

    suspend fun artistAlbums(id: String): List<Release> = allPages { offset ->
        JsonCodec.items(request("artists/$id/albums", mapOf("limit" to "100", "offset" to "$offset"))).map { JsonCodec.release(it, ReleaseType.ALBUM) }
    }

    suspend fun stream(id: String, quality: AudioQuality): StreamInfo {
        val stream = StreamManifest.parse(request("tracks/$id/playbackinfopostpaywall", mapOf("audioquality" to quality.name,
            "playbackmode" to "STREAM", "assetpresentation" to "FULL")))
        if (quality in setOf(AudioQuality.LOSSLESS, AudioQuality.HI_RES_LOSSLESS) &&
            !stream.codec.equals("flac", ignoreCase = true) && !stream.codec.equals("alac", ignoreCase = true)) {
            throw ServiceException(406, "TIDAL returned lossy audio instead of lossless. Sign in again using Sign in to TIDAL, or choose High quality to allow AAC.", Problem.LOSSY_AUDIO)
        }
        return stream
    }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        if (favorite) request("users/${userId()}/favorites/tracks", method = "POST", form = mapOf("trackIds" to id))
        else request("users/${userId()}/favorites/tracks/$id", method = "DELETE")
    }

    suspend fun createPlaylist(title: String): Release = JsonCodec.release(
        request("users/${userId()}/playlists", method = "POST", form = mapOf("title" to title, "description" to "")), ReleaseType.PLAYLIST).copy(ownerId = userId())

    suspend fun addToPlaylist(playlistId: String, trackId: String) = addToPlaylist(playlistId, listOf(trackId))

    /** Adds tracks to the end of a playlist. A track that the playlist holds already is skipped, and so is a track that is no longer available. */
    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        require(trackIds.none(::isDeviceId)) { "Local files cannot be uploaded to TIDAL playlists." }
        if (trackIds.isEmpty()) return
        val metadata = request("playlists/$playlistId")
        val etag = etags["playlists/$playlistId"] ?: throw ServiceException(409, "Refresh this playlist before editing it.", Problem.REFRESH_PLAYLIST)
        request("playlists/$playlistId/items", method = "POST", form = mapOf("trackIds" to trackIds.joinToString(","),
            "toIndex" to metadata.optInt("numberOfTracks").toString(), "onDupes" to "SKIP", "onArtifactNotFound" to if (trackIds.size == 1) "FAIL" else "SKIP"),
            match = etag)
    }

    suspend fun removeFromPlaylist(playlistId: String, index: Int, expectedTrackId: String) {
        request("playlists/$playlistId")
        val etag = etags["playlists/$playlistId"] ?: throw ServiceException(409, "Refresh this playlist before editing it.", Problem.REFRESH_PLAYLIST)
        val item = JsonCodec.items(request("playlists/$playlistId/items", mapOf("limit" to "1", "offset" to "$index"))).singleOrNull()
        if (item == null || JsonCodec.track(item).id != expectedTrackId) throw ServiceException(409, "This playlist changed elsewhere. Refresh it before editing again.", Problem.PLAYLIST_CHANGED)
        request("playlists/$playlistId/items/$index", method = "DELETE", match = etag)
    }

    /** Moves the track at [from] to position [to]. The track is checked first so a playlist that changed elsewhere is not edited by mistake. */
    suspend fun moveInPlaylist(playlistId: String, from: Int, to: Int, expectedTrackId: String) {
        request("playlists/$playlistId")
        val etag = etags["playlists/$playlistId"] ?: throw ServiceException(409, "Refresh this playlist before editing it.", Problem.REFRESH_PLAYLIST)
        val item = JsonCodec.items(request("playlists/$playlistId/items", mapOf("limit" to "1", "offset" to "$from"))).singleOrNull()
        if (item == null || JsonCodec.track(item).id != expectedTrackId) throw ServiceException(409, "This playlist changed elsewhere. Refresh it before editing again.", Problem.PLAYLIST_CHANGED)
        request("playlists/$playlistId/items/$from", method = "POST", form = mapOf("toIndex" to "$to"), match = etag)
    }

    /** The folders and playlists inside a playlist folder. The top level is the folder "root". */
    suspend fun playlistFolder(folderId: String = ROOT_FOLDER): List<Release> {
        val result = mutableListOf<Release>()
        var cursor: String? = null
        do {
            val page = request("my-collection/playlists/folders", buildMap {
                put("folderId", folderId); put("limit", "50"); put("order", "NAME"); put("orderDirection", "ASC"); put("includeOnly", "")
                cursor?.let { put("cursor", it) }
            }, base = apiBaseV2)
            result += JsonCodec.folderEntries(page)
            cursor = page.optString("cursor").takeIf { it.isNotBlank() && it != "null" }
        } while (cursor != null)
        return result
    }

    suspend fun createFolder(name: String, parentId: String = ROOT_FOLDER): Release = JsonCodec.folderEntries(JSONObject().put("items",
        org.json.JSONArray().put(request("my-collection/playlists/folders/create-folder", mapOf("name" to name.trim(), "folderId" to parentId), method = "PUT", base = apiBaseV2)))).single()

    suspend fun renameFolder(folderId: String, name: String) {
        request("my-collection/playlists/folders/rename", mapOf("trn" to "trn:folder:$folderId", "name" to name.trim()), method = "PUT", base = apiBaseV2)
    }

    suspend fun removeFolder(folderId: String) {
        request("my-collection/playlists/folders/remove", mapOf("trns" to "trn:folder:$folderId"), method = "PUT", base = apiBaseV2)
    }

    /** Moves a playlist into a folder, or back to the top level with [ROOT_FOLDER]. */
    suspend fun moveToFolder(playlistId: String, folderId: String) {
        request("my-collection/playlists/folders/move", mapOf("folderId" to folderId, "trns" to "trn:playlist:$playlistId"), method = "PUT", base = apiBaseV2)
    }

    suspend fun renamePlaylist(id: String, title: String) { request("playlists/$id", method = "POST", form = mapOf("title" to title)) }
    suspend fun deletePlaylist(id: String) { request("playlists/$id", method = "DELETE") }
    /** Saves or removes a playlist that someone else made, such as an editorial playlist. Playlists of the listener need no saving. */
    suspend fun setPlaylistFavorite(id: String, favorite: Boolean) {
        if (favorite) request("users/${userId()}/favorites/playlists", method = "POST", form = mapOf("uuids" to id))
        else request("users/${userId()}/favorites/playlists/$id", method = "DELETE")
    }

    suspend fun setAlbumFavorite(id: String, favorite: Boolean) {
        if (favorite) request("users/${userId()}/favorites/albums", method = "POST", form = mapOf("albumIds" to id))
        else request("users/${userId()}/favorites/albums/$id", method = "DELETE")
    }

    /** Lyrics with line timing when the provider has it, or null when the track has no lyrics. */
    suspend fun lyrics(id: String): Lyrics? = try {
        LyricsParser.parse(request("tracks/$id/lyrics", pageParameters)).takeIf { !it.isEmpty }
    } catch (error: ServiceException) { if (error.status == 404) null else throw error }

    suspend fun credits(kind: String, id: String): List<Credit> =
        CreditsParser.parse(request("${if (kind == "album") "albums" else "tracks"}/$id/credits"))

    suspend fun pageJson(path: String, query: Map<String, String> = emptyMap()): JSONObject = request(path, pageParameters + query)

    suspend fun page(path: String, query: Map<String, String> = emptyMap()): PageData = PageCodec.parse(pageJson(path, query))

    /** Fetches more items of a paged module through the `dataApiPath` of the module. */
    suspend fun moduleItems(dataPath: String, offset: Int, limit: Int = 50): JSONObject =
        request(dataPath, pageParameters + mapOf("offset" to "$offset", "limit" to "$limit"))

    suspend fun trackRadio(id: String): List<Track> =
        JsonCodec.items(request("tracks/$id/radio", mapOf("limit" to "100"))).map(JsonCodec::track)

    suspend fun artistRadio(id: String): List<Track> =
        JsonCodec.items(request("artists/$id/radio", mapOf("limit" to "100"))).map(JsonCodec::track)

    suspend fun mixTracks(mixId: String): List<Track> =
        JsonCodec.items(request("mixes/$mixId/items", mapOf("limit" to "100"))).map(JsonCodec::track)

    suspend fun artistTopTracks(id: String): List<Track> =
        JsonCodec.items(request("artists/$id/toptracks", mapOf("limit" to "100"))).map(JsonCodec::track)

    suspend fun followedArtists(): List<Release> = allPages { offset ->
        JsonCodec.items(request("users/${userId()}/favorites/artists", mapOf("limit" to "100", "offset" to "$offset")))
            .map { JsonCodec.release(it, ReleaseType.ARTIST) }
    }

    suspend fun setArtistFollowed(id: String, follow: Boolean) {
        if (follow) request("users/${userId()}/favorites/artists", method = "POST", form = mapOf("artistIds" to id))
        else request("users/${userId()}/favorites/artists/$id", method = "DELETE")
    }

    suspend fun videoUrl(id: String): String = StreamManifest.parseVideo(request("videos/$id/playbackinfopostpaywall",
        mapOf("videoquality" to "HIGH", "playbackmode" to "STREAM", "assetpresentation" to "FULL")))

    fun signOut() = synchronized(accountLock) { generation.incrementAndGet(); sessions.clear(); etags.clear() }

    private fun saveSession(session: Session, expectedGeneration: Long) = synchronized(accountLock) {
        if (generation.get() != expectedGeneration) throw kotlinx.coroutines.CancellationException("Account changed")
        sessions.write(session)
    }

    private suspend fun authenticate(response: JSONObject, expectedGeneration: Long) {
        val pending = tokenSession(response)
        val profile = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(apiBase + "sessions").header("Authorization", "Bearer ${pending.accessToken}").build()).execute().use { result ->
                if (!result.isSuccessful) throw ServiceException(result.code, errorMessage(result.code))
                try { JSONObject(result.body.string()) } catch (_: Exception) { throw ServiceException(502, "TIDAL returned an unreadable account response.", Problem.UNREADABLE_RESPONSE) }
            }
        }
        currentCoroutineContext().ensureActive()
        saveSession(pending.copy(userId = profile.get("userId").toString(), country = profile.optString("countryCode", "US")), expectedGeneration)
    }

    private fun userId() = sessions.read()?.userId?.takeIf(String::isNotBlank)
        ?: throw ServiceException(401, "Sign in to access your TIDAL collection.", Problem.SIGN_IN_REQUIRED)

    private suspend fun accessToken(force: Boolean = false): String = refreshLock.withLock {
        val (requestGeneration, session) = synchronized(accountLock) {
            generation.get() to (sessions.read() ?: throw ServiceException(401, "Sign in to TIDAL to continue.", Problem.SIGN_IN_REQUIRED))
        }
        if (!force && !session.needsRefresh()) return@withLock session.accessToken
        val client = identity()
        val response = try {
            withContext(Dispatchers.IO) { form("token", mapOf("client_id" to client.id, "client_secret" to client.secret,
                "grant_type" to "refresh_token", "refresh_token" to session.refreshToken)) }
        } catch (_: OAuthException) {
            throw ServiceException(401, "Your TIDAL session expired. Sign in again.", Problem.SESSION_EXPIRED)
        }
        tokenSession(response, session).also { saveSession(it, requestGeneration) }.accessToken
    }

    private suspend fun request(path: String, parameters: Map<String, String> = emptyMap(),
        method: String = "GET", form: Map<String, String>? = null, match: String? = null, base: String = apiBase): JSONObject {
        val requestGeneration = generation.get()
        for (attempt in 0..1) {
            currentCoroutineContext().ensureActive()
            if (generation.get() != requestGeneration) throw kotlinx.coroutines.CancellationException("Account changed")
            val token = accessToken(force = attempt == 1)
            val country = sessions.read()?.country ?: "US"
            val result = withContext(Dispatchers.IO) {
                val url = (base + path).toHttpUrl().newBuilder().addQueryParameter("countryCode", country).apply {
                    parameters.forEach { (key, value) -> addQueryParameter(key, value) }
                }.build()
                val body = form?.let { values -> FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build() }
                val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                    .header("Accept", "application/json").apply { if (match != null) header("If-None-Match", match) }.method(method,
                        body ?: if (method in setOf("POST", "PUT")) ByteArray(0).toRequestBody(null) else null).build()
                executeApiRequest(request)
            }
            currentCoroutineContext().ensureActive()
            if (generation.get() != requestGeneration) throw kotlinx.coroutines.CancellationException("Account changed")
            // A track that was removed answers with 401 and a sub status that says so. A new sign-in does not bring it back.
            if (result.first == 401 && unavailableAsset(result.second)) throw ServiceException(404, "This track is not available.", Problem.TRACK_UNAVAILABLE)
            if (result.first == 401 && attempt == 0) continue
            if (result.first !in 200..299) throw ServiceException(result.first, errorMessage(result.first))
            result.third?.let { etags[path] = it }
            return if (result.second.isBlank()) JSONObject() else try {
                if (result.second.trimStart().startsWith("[")) JSONObject().put("items", org.json.JSONArray(result.second)) else JSONObject(result.second)
            } catch (_: Exception) { throw ServiceException(502, "TIDAL returned an unreadable response.", Problem.UNREADABLE_RESPONSE) }
        }
        throw ServiceException(401, "Your session could not be renewed.", Problem.SESSION_NOT_RENEWED)
    }

    /** Cancelling a search/radio session also closes its HTTP call, rather than occupying IO until timeout. */
    private suspend fun executeApiRequest(request: Request): Triple<Int, String, String?> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val result = response.use { Triple(it.code, it.body.string(), it.header("ETag")) }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun form(path: String, values: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build()
        return http.newCall(Request.Builder().url(authBase + path).post(body).build()).execute().use { response ->
            val json = try { JSONObject(response.body.string()) } catch (_: Exception) {
                throw ServiceException(response.code, "TIDAL rejected this sign-in request. Start a new sign-in and try again.", Problem.SIGN_IN_FAILED)
            }
            if (!response.isSuccessful) throw OAuthException(json.optString("error", "unknown"))
            json
        }
    }

    private fun tokenSession(response: JSONObject, previous: Session? = null) = Session(
        accessToken = response.getString("access_token"),
        refreshToken = response.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() },
        expiresAt = System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000,
        userId = previous?.userId.orEmpty(), country = previous?.country ?: "US")

    /** The sub statuses 4004 and 4005 of TIDAL mean that the asset of a track is missing or not ready. */
    private fun unavailableAsset(body: String): Boolean = runCatching { JSONObject(body).optInt("subStatus") in setOf(4004, 4005) }.getOrDefault(false)

    private class OAuthException(val code: String) : Exception("OAuth authorization failed")
    private fun errorMessage(status: Int) = when (status) {
        401 -> "Your TIDAL session expired. Sign in again."
        403 -> "TIDAL did not grant access. Check your subscription, region, and client configuration."
        404 -> "This item is no longer available in your region."
        429 -> "TIDAL is limiting requests. Wait a moment and try again."
        409, 412 -> "This playlist changed elsewhere. Refresh it before editing again."
        in 500..599 -> "TIDAL is temporarily unavailable. Try again shortly."
        else -> "The request could not be completed (HTTP $status)."
    }

    companion object {
        const val PKCE_REDIRECT = SignInCallback.NATIVE
        const val ROOT_FOLDER = "root"
    }

    private val pageParameters get() = mapOf("locale" to locale(), "deviceType" to "PHONE")
}
