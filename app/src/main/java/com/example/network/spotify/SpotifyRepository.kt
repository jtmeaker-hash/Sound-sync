package com.example.network.spotify

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.example.model.SpotifyAuthState
import com.example.model.SpotifyPlaylistItem
import com.example.model.SpotifyTrackItem
import com.example.model.SpotifyUserProfile
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

class SpotifyRepository(private val context: Context) {

    companion object {
        private const val TAG = "SpotifyRepository"
        private const val PREFS_NAME = "spotify_auth_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_CLIENT_ID = "spotify_client_id"
        private const val KEY_CODE_VERIFIER = "pkce_verifier"

        const val DEFAULT_REDIRECT_URI = "soundsync://spotify-callback"
        const val SPOTIFY_AUTH_SCOPES = "user-read-private user-read-email user-library-read playlist-read-private user-modify-playback-state user-read-playback-state"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _authState = MutableStateFlow(SpotifyAuthState())
    val authState: StateFlow<SpotifyAuthState> = _authState.asStateFlow()

    private val _savedTracks = MutableStateFlow<List<SpotifyTrackItem>>(emptyList())
    val savedTracks: StateFlow<List<SpotifyTrackItem>> = _savedTracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<SpotifyPlaylistItem>>(emptyList())
    val playlists: StateFlow<List<SpotifyPlaylistItem>> = _playlists.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SpotifyTrackItem>>(emptyList())
    val searchResults: StateFlow<List<SpotifyTrackItem>> = _searchResults.asStateFlow()

    private val _isLoadingContent = MutableStateFlow(false)
    val isLoadingContent: StateFlow<Boolean> = _isLoadingContent.asStateFlow()

    init {
        restoreSession()
    }

    private fun restoreSession() {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val clientId = prefs.getString(KEY_CLIENT_ID, "") ?: ""

        if (!accessToken.isNullOrBlank()) {
            _authState.value = SpotifyAuthState(
                isConnected = true,
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenExpiryEpochMs = expiresAt
            )
        }
    }

    fun getStoredClientId(): String {
        return prefs.getString(KEY_CLIENT_ID, "") ?: ""
    }

    fun saveClientId(clientId: String) {
        prefs.edit().putString(KEY_CLIENT_ID, clientId.trim()).apply()
    }

    /**
     * Builds the authorization URL for PKCE Authorization Code flow.
     */
    fun createAuthUrl(customClientId: String? = null): String {
        val clientId = customClientId?.trim()?.takeIf { it.isNotBlank() }
            ?: getStoredClientId()

        val verifier = PkceUtil.generateCodeVerifier()
        val challenge = PkceUtil.generateCodeChallenge(verifier)

        prefs.edit()
            .putString(KEY_CODE_VERIFIER, verifier)
            .putString(KEY_CLIENT_ID, clientId)
            .apply()

        val authUri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", DEFAULT_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SPOTIFY_AUTH_SCOPES)
            .build()

        return authUri.toString()
    }

    /**
     * Exchanges auth code for access token & refresh token with PKCE verification.
     */
    suspend fun exchangeCodeForToken(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)
        val clientId = getStoredClientId()
        val verifier = prefs.getString(KEY_CODE_VERIFIER, "") ?: ""

        if (clientId.isBlank() || verifier.isBlank()) {
            val err = "Missing Spotify Client ID or PKCE verifier"
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
                .url("https://accounts.spotify.com/api/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val err = "Spotify token error HTTP ${response.code}: $responseBody"
                Log.e(TAG, err)
                _authState.value = _authState.value.copy(isLoading = false, errorMessage = err)
                return@withContext Result.failure(IOException(err))
            }

            val json = JSONObject(responseBody)
            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refresh_token", prefs.getString(KEY_REFRESH_TOKEN, ""))
            val expiresInSec = json.optLong("expires_in", 3600L)
            val expiresAt = System.currentTimeMillis() + (expiresInSec * 1000)

            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .apply()

            _authState.value = SpotifyAuthState(
                isConnected = true,
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenExpiryEpochMs = expiresAt,
                isLoading = false
            )

            // Fetch profile and initial saved library
            fetchUserProfile()
            fetchSavedTracks()
            fetchPlaylists()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Spotify token exchange", e)
            _authState.value = _authState.value.copy(isLoading = false, errorMessage = e.message)
            Result.failure(e)
        }
    }

    /**
     * Refreshes access token using refresh token.
     */
    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return@withContext false
        val clientId = getStoredClientId()
        if (clientId.isBlank()) return@withContext false

        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to refresh Spotify token: $responseBody")
                return@withContext false
            }

            val json = JSONObject(responseBody)
            val newAccessToken = json.getString("access_token")
            val newRefreshToken = json.optString("refresh_token", refreshToken)
            val expiresInSec = json.optLong("expires_in", 3600L)
            val expiresAt = System.currentTimeMillis() + (expiresInSec * 1000)

            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, newAccessToken)
                .putString(KEY_REFRESH_TOKEN, newRefreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .apply()

            _authState.value = _authState.value.copy(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                tokenExpiryEpochMs = expiresAt
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing Spotify token", e)
            false
        }
    }

    private suspend fun getValidAccessToken(): String? {
        val currentToken = _authState.value.accessToken ?: return null
        val expiresAt = _authState.value.tokenExpiryEpochMs
        if (System.currentTimeMillis() > expiresAt - 60000) {
            val refreshed = refreshAccessToken()
            if (!refreshed) return null
        }
        return _authState.value.accessToken
    }

    suspend fun fetchUserProfile(): Result<SpotifyUserProfile> = withContext(Dispatchers.IO) {
        val token = getValidAccessToken() ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me")
                .header("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Profile fetch failed: ${response.code}"))
            }

            val json = JSONObject(body)
            val id = json.getString("id")
            val displayName = json.optString("display_name", id)
            val email = json.optString("email", null)
            val product = json.optString("product", "premium")
            val followers = json.optJSONObject("followers")?.optInt("total", 0) ?: 0
            val country = json.optString("country", null)

            val images = json.optJSONArray("images")
            val avatarUrl = if (images != null && images.length() > 0) {
                images.getJSONObject(0).optString("url")
            } else null

            val profile = SpotifyUserProfile(
                id = id,
                displayName = displayName,
                email = email,
                avatarUrl = avatarUrl,
                product = product,
                followersCount = followers,
                country = country
            )

            _authState.value = _authState.value.copy(userProfile = profile)
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching user profile", e)
            Result.failure(e)
        }
    }

    suspend fun fetchSavedTracks(): Result<List<SpotifyTrackItem>> = withContext(Dispatchers.IO) {
        _isLoadingContent.value = true
        val token = getValidAccessToken()
        if (token == null) {
            _isLoadingContent.value = false
            return@withContext Result.failure(IllegalStateException("Not authenticated"))
        }

        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/tracks?limit=50")
                .header("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                _isLoadingContent.value = false
                return@withContext Result.failure(IOException("Failed to fetch tracks: ${response.code}"))
            }

            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: JSONArray()
            val trackList = mutableListOf<SpotifyTrackItem>()

            for (i in 0 until items.length()) {
                val itemObj = items.getJSONObject(i)
                val trackObj = itemObj.optJSONObject("track") ?: continue
                parseTrackItem(trackObj)?.let { trackList.add(it) }
            }

            _savedTracks.value = trackList
            _isLoadingContent.value = false
            Result.success(trackList)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching saved tracks", e)
            _isLoadingContent.value = false
            Result.failure(e)
        }
    }

    suspend fun fetchPlaylists(): Result<List<SpotifyPlaylistItem>> = withContext(Dispatchers.IO) {
        _isLoadingContent.value = true
        val token = getValidAccessToken()
        if (token == null) {
            _isLoadingContent.value = false
            return@withContext Result.failure(IllegalStateException("Not authenticated"))
        }

        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists?limit=50")
                .header("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                _isLoadingContent.value = false
                return@withContext Result.failure(IOException("Failed to fetch playlists: ${response.code}"))
            }

            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: JSONArray()
            val playlistList = mutableListOf<SpotifyPlaylistItem>()

            for (i in 0 until items.length()) {
                val pObj = items.getJSONObject(i)
                val id = pObj.getString("id")
                val name = pObj.optString("name", "Playlist")
                val desc = pObj.optString("description", null)
                val uri = pObj.optString("uri", "spotify:playlist:$id")
                val trackCount = pObj.optJSONObject("tracks")?.optInt("total", 0) ?: 0
                val owner = pObj.optJSONObject("owner")?.optString("display_name", "User") ?: "User"

                val images = pObj.optJSONArray("images")
                val imageUrl = if (images != null && images.length() > 0) {
                    images.getJSONObject(0).optString("url")
                } else null

                playlistList.add(
                    SpotifyPlaylistItem(
                        id = id,
                        name = name,
                        description = desc,
                        imageUrl = imageUrl,
                        trackCount = trackCount,
                        ownerName = owner,
                        uri = uri
                    )
                )
            }

            _playlists.value = playlistList
            _isLoadingContent.value = false
            Result.success(playlistList)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching playlists", e)
            _isLoadingContent.value = false
            Result.failure(e)
        }
    }

    suspend fun searchTracks(query: String): Result<List<SpotifyTrackItem>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return@withContext Result.success(emptyList())
        }

        val token = getValidAccessToken() ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))
        try {
            val url = Uri.parse("https://api.spotify.com/v1/search").buildUpon()
                .appendQueryParameter("q", query)
                .appendQueryParameter("type", "track")
                .appendQueryParameter("limit", "25")
                .build().toString()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Search failed: ${response.code}"))
            }

            val json = JSONObject(body)
            val tracksObj = json.optJSONObject("tracks")
            val items = tracksObj?.optJSONArray("items") ?: JSONArray()
            val resultList = mutableListOf<SpotifyTrackItem>()

            for (i in 0 until items.length()) {
                val tObj = items.getJSONObject(i)
                parseTrackItem(tObj)?.let { resultList.add(it) }
            }

            _searchResults.value = resultList
            Result.success(resultList)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching tracks", e)
            Result.failure(e)
        }
    }

    private fun parseTrackItem(trackObj: JSONObject): SpotifyTrackItem? {
        return try {
            val id = trackObj.getString("id")
            val name = trackObj.optString("name", "Untitled")
            val durationMs = trackObj.optLong("duration_ms", 180000L)
            val uri = trackObj.optString("uri", "spotify:track:$id")
            val previewUrl = trackObj.optString("preview_url", null)
            val isPlayable = trackObj.optBoolean("is_playable", true)
            val isExplicit = trackObj.optBoolean("explicit", false)
            val popularity = trackObj.optInt("popularity", 50)

            val artists = trackObj.optJSONArray("artists")
            val artistName = if (artists != null && artists.length() > 0) {
                artists.getJSONObject(0).optString("name", "Unknown Artist")
            } else "Unknown Artist"

            val album = trackObj.optJSONObject("album")
            val albumName = album?.optString("name", "Single") ?: "Single"
            val images = album?.optJSONArray("images")
            val albumArtUrl = if (images != null && images.length() > 0) {
                images.getJSONObject(0).optString("url")
            } else null

            SpotifyTrackItem(
                id = id,
                name = name,
                artistName = artistName,
                albumName = albumName,
                albumArtUrl = albumArtUrl,
                durationMs = durationMs,
                uri = uri,
                previewUrl = previewUrl,
                isPlayable = isPlayable,
                isExplicit = isExplicit,
                popularity = popularity
            )
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_CODE_VERIFIER)
            .apply()

        _authState.value = SpotifyAuthState(isConnected = false)
        _savedTracks.value = emptyList()
        _playlists.value = emptyList()
        _searchResults.value = emptyList()
    }
}
