package com.example.network.soundcloud

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.example.model.SoundCloudAuthState
import com.example.model.SoundCloudPlaylistItem
import com.example.model.SoundCloudTrackItem
import com.example.model.SoundCloudUserProfile
import com.example.network.PkceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SoundCloudRepository(private val context: Context) {

    companion object {
        private const val TAG = "SoundCloudRepository"
        private const val PREFS_NAME = "soundcloud_auth_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_CLIENT_ID = "soundcloud_client_id"
        private const val KEY_CODE_VERIFIER = "pkce_verifier"

        const val DEFAULT_REDIRECT_URI = "soundsync://soundcloud-callback"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _authState = MutableStateFlow(SoundCloudAuthState())
    val authState: StateFlow<SoundCloudAuthState> = _authState.asStateFlow()

    private val _likedTracks = MutableStateFlow<List<SoundCloudTrackItem>>(emptyList())
    val likedTracks: StateFlow<List<SoundCloudTrackItem>> = _likedTracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<SoundCloudPlaylistItem>>(emptyList())
    val playlists: StateFlow<List<SoundCloudPlaylistItem>> = _playlists.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SoundCloudTrackItem>>(emptyList())
    val searchResults: StateFlow<List<SoundCloudTrackItem>> = _searchResults.asStateFlow()

    private val _isLoadingContent = MutableStateFlow(false)
    val isLoadingContent: StateFlow<Boolean> = _isLoadingContent.asStateFlow()

    init {
        restoreSession()
    }

    private fun restoreSession() {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val clientId = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        if (!accessToken.isNullOrBlank() || clientId.isNotBlank()) {
            _authState.value = SoundCloudAuthState(
                isConnected = !accessToken.isNullOrBlank(),
                accessToken = accessToken,
                clientId = clientId
            )
        }
    }

    fun getStoredClientId(): String {
        return prefs.getString(KEY_CLIENT_ID, "") ?: ""
    }

    fun saveClientId(clientId: String) {
        prefs.edit().putString(KEY_CLIENT_ID, clientId.trim()).apply()
        _authState.value = _authState.value.copy(clientId = clientId.trim())
    }

    /**
     * Builds SoundCloud OAuth 2.1 PKCE authorization URL.
     */
    fun createAuthUrl(customClientId: String? = null): String {
        val clientId = customClientId?.trim()?.takeIf { it.isNotBlank() } ?: getStoredClientId()
        val verifier = PkceUtil.generateCodeVerifier()
        val challenge = PkceUtil.generateCodeChallenge(verifier)

        prefs.edit()
            .putString(KEY_CODE_VERIFIER, verifier)
            .putString(KEY_CLIENT_ID, clientId)
            .apply()

        val authUri = Uri.parse("https://secure.soundcloud.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", DEFAULT_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()

        return authUri.toString()
    }

    /**
     * Exchanges OAuth authorization code for SoundCloud access token using PKCE.
     */
    suspend fun exchangeCodeForToken(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)
        val clientId = getStoredClientId()
        val verifier = prefs.getString(KEY_CODE_VERIFIER, "") ?: ""

        if (clientId.isBlank() || verifier.isBlank()) {
            val err = "Missing SoundCloud Client ID or PKCE verifier"
            _authState.value = _authState.value.copy(isLoading = false, errorMessage = err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", DEFAULT_REDIRECT_URI)
                .add("client_id", clientId)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url("https://secure.soundcloud.com/oauth/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val err = "SoundCloud token error HTTP ${response.code}: $responseBody"
                Log.e(TAG, err)
                _authState.value = _authState.value.copy(isLoading = false, errorMessage = err)
                return@withContext Result.failure(IOException(err))
            }

            val json = JSONObject(responseBody)
            val accessToken = json.getString("access_token")

            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .apply()

            _authState.value = SoundCloudAuthState(
                isConnected = true,
                accessToken = accessToken,
                clientId = clientId,
                isLoading = false
            )

            fetchUserProfile()
            fetchLikedTracks()
            fetchPlaylists()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Exception exchanging SoundCloud code", e)
            _authState.value = _authState.value.copy(isLoading = false, errorMessage = e.message)
            Result.failure(e)
        }
    }

    suspend fun fetchUserProfile(): Result<SoundCloudUserProfile> = withContext(Dispatchers.IO) {
        val token = _authState.value.accessToken
        val clientId = getStoredClientId()

        val url = if (!token.isNullOrBlank()) {
            "https://api.soundcloud.com/me"
        } else if (clientId.isNotBlank()) {
            "https://api.soundcloud.com/me?client_id=$clientId"
        } else {
            return@withContext Result.failure(IllegalStateException("No token or client ID"))
        }

        try {
            val reqBuilder = Request.Builder().url(url)
            if (!token.isNullOrBlank()) {
                reqBuilder.header("Authorization", "OAuth $token")
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("SoundCloud profile failed: ${response.code}"))
            }

            val json = JSONObject(body)
            val id = json.getLong("id")
            val username = json.optString("username", "SoundCloud Creator")
            val avatarUrl = json.optString("avatar_url", null)
            val permalinkUrl = json.optString("permalink_url", null)
            val trackCount = json.optInt("track_count", 0)
            val followers = json.optInt("followers_count", 0)
            val country = json.optString("country", null)

            val profile = SoundCloudUserProfile(
                id = id,
                username = username,
                avatarUrl = avatarUrl,
                permalinkUrl = permalinkUrl,
                trackCount = trackCount,
                followersCount = followers,
                country = country
            )

            _authState.value = _authState.value.copy(userProfile = profile)
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching SoundCloud profile", e)
            Result.failure(e)
        }
    }

    suspend fun fetchLikedTracks(): Result<List<SoundCloudTrackItem>> = withContext(Dispatchers.IO) {
        _isLoadingContent.value = true
        val token = _authState.value.accessToken
        val clientId = getStoredClientId()

        val url = if (!token.isNullOrBlank()) {
            "https://api.soundcloud.com/me/likes/tracks?limit=50"
        } else if (clientId.isNotBlank()) {
            "https://api-v2.soundcloud.com/featured_tracks?client_id=$clientId&limit=25"
        } else {
            _isLoadingContent.value = false
            return@withContext Result.failure(IllegalStateException("No credentials configured"))
        }

        try {
            val reqBuilder = Request.Builder().url(url)
            if (!token.isNullOrBlank()) {
                reqBuilder.header("Authorization", "OAuth $token")
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                _isLoadingContent.value = false
                return@withContext Result.failure(IOException("SoundCloud likes failed: ${response.code}"))
            }

            val trackList = mutableListOf<SoundCloudTrackItem>()
            val trimmedBody = body.trim()

            if (trimmedBody.startsWith("[")) {
                val array = JSONArray(trimmedBody)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    parseTrackItem(obj)?.let { trackList.add(it) }
                }
            } else if (trimmedBody.startsWith("{")) {
                val json = JSONObject(trimmedBody)
                val collection = json.optJSONArray("collection") ?: json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until collection.length()) {
                    val obj = collection.getJSONObject(i)
                    val trackObj = if (obj.has("track")) obj.getJSONObject("track") else obj
                    parseTrackItem(trackObj)?.let { trackList.add(it) }
                }
            }

            _likedTracks.value = trackList
            _isLoadingContent.value = false
            Result.success(trackList)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching SoundCloud liked tracks", e)
            _isLoadingContent.value = false
            Result.failure(e)
        }
    }

    suspend fun fetchPlaylists(): Result<List<SoundCloudPlaylistItem>> = withContext(Dispatchers.IO) {
        _isLoadingContent.value = true
        val token = _authState.value.accessToken
        val clientId = getStoredClientId()

        val url = if (!token.isNullOrBlank()) {
            "https://api.soundcloud.com/me/playlists?limit=30"
        } else {
            _isLoadingContent.value = false
            return@withContext Result.failure(IllegalStateException("No auth token"))
        }

        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "OAuth $token")

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                _isLoadingContent.value = false
                return@withContext Result.failure(IOException("SoundCloud playlists failed: ${response.code}"))
            }

            val list = mutableListOf<SoundCloudPlaylistItem>()
            val array = JSONArray(body)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getLong("id")
                val title = obj.optString("title", "Playlist")
                val permalink = obj.optString("permalink_url", null)
                val artwork = obj.optString("artwork_url", null)
                val trackCount = obj.optInt("track_count", 0)
                val user = obj.optJSONObject("user")?.optString("username", "SoundCloud Creator") ?: "SoundCloud"

                list.add(
                    SoundCloudPlaylistItem(
                        id = id,
                        title = title,
                        artistName = user,
                        artworkUrl = artwork,
                        trackCount = trackCount,
                        permalinkUrl = permalink
                    )
                )
            }

            _playlists.value = list
            _isLoadingContent.value = false
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching SoundCloud playlists", e)
            _isLoadingContent.value = false
            Result.failure(e)
        }
    }

    suspend fun searchTracks(query: String): Result<List<SoundCloudTrackItem>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return@withContext Result.success(emptyList())
        }

        val token = _authState.value.accessToken
        val clientId = getStoredClientId()

        val url = Uri.parse("https://api-v2.soundcloud.com/search/tracks").buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("limit", "25")
            .apply {
                if (!clientId.isNullOrBlank()) appendQueryParameter("client_id", clientId)
            }
            .build().toString()

        try {
            val reqBuilder = Request.Builder().url(url)
            if (!token.isNullOrBlank()) {
                reqBuilder.header("Authorization", "OAuth $token")
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Search failed: ${response.code}"))
            }

            val json = JSONObject(body)
            val collection = json.optJSONArray("collection") ?: JSONArray()
            val resultList = mutableListOf<SoundCloudTrackItem>()

            for (i in 0 until collection.length()) {
                val obj = collection.getJSONObject(i)
                parseTrackItem(obj)?.let { resultList.add(it) }
            }

            _searchResults.value = resultList
            Result.success(resultList)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching SoundCloud tracks", e)
            Result.failure(e)
        }
    }

    private fun parseTrackItem(obj: JSONObject): SoundCloudTrackItem? {
        return try {
            val id = obj.getLong("id")
            val title = obj.optString("title", "Untitled Track")
            val durationMs = obj.optLong("duration", 180000L)
            val artworkUrl = obj.optString("artwork_url", null)
            val permalinkUrl = obj.optString("permalink_url", null)
            val playbackCount = obj.optLong("playback_count", 0L)
            val likesCount = obj.optLong("likes_count", obj.optLong("favoritings_count", 0L))
            val genre = obj.optString("genre", "Electronic")

            val userObj = obj.optJSONObject("user")
            val artistName = userObj?.optString("username", "Unknown Artist") ?: "Unknown Artist"

            val streamable = obj.optBoolean("streamable", true)
            val streamUrl = obj.optString("stream_url", null)
                ?: obj.optJSONObject("media")?.optJSONArray("transcodings")?.let { transcodings ->
                    if (transcodings.length() > 0) {
                        transcodings.getJSONObject(0).optString("url", null)
                    } else null
                }

            val isBlocked = obj.optString("policy", "").equals("BLOCK", ignoreCase = true)
            val isPreview = obj.optBoolean("snippet", false) || !streamable

            SoundCloudTrackItem(
                id = id,
                title = title,
                artistName = artistName,
                durationMs = durationMs,
                artworkUrl = artworkUrl,
                streamUrl = streamUrl,
                permalinkUrl = permalinkUrl,
                playbackCount = playbackCount,
                likesCount = likesCount,
                genre = genre,
                isStreamable = streamable && !isBlocked,
                isPreviewOnly = isPreview,
                isGeoBlocked = isBlocked
            )
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_CODE_VERIFIER)
            .apply()

        _authState.value = SoundCloudAuthState(
            isConnected = false,
            clientId = getStoredClientId()
        )
        _likedTracks.value = emptyList()
        _playlists.value = emptyList()
        _searchResults.value = emptyList()
    }
}
