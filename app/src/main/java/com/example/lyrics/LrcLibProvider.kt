package com.example.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class RemoteLyricsResult(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Int,
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val isInstrumental: Boolean
)

object LrcLibProvider {

    private const val TAG = "LrcLibProvider"
    private const val BASE_URL = "https://lrclib.net/api/get"
    private const val TIMEOUT_MS = 6000

    /**
     * Fetches lyrics from LRCLIB API.
     * Complies with Step 3 Part A: Legitimate, open, free provider without scraping copyrighted web HTML.
     */
    suspend fun fetchLyrics(
        trackTitle: String,
        artistName: String,
        albumName: String? = null,
        durationSeconds: Int = 0
    ): RemoteLyricsResult? = withContext(Dispatchers.IO) {
        if (trackTitle.isBlank() || artistName.isBlank()) return@withContext null

        try {
            val queryParams = StringBuilder()
            queryParams.append("track_name=").append(URLEncoder.encode(trackTitle.trim(), "UTF-8"))
            queryParams.append("&artist_name=").append(URLEncoder.encode(artistName.trim(), "UTF-8"))

            if (!albumName.isNullOrBlank() && albumName != "Single") {
                queryParams.append("&album_name=").append(URLEncoder.encode(albumName.trim(), "UTF-8"))
            }
            if (durationSeconds > 0) {
                queryParams.append("&duration=").append(durationSeconds)
            }

            val targetUrl = "$BASE_URL?$queryParams"
            val url = URL(targetUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "SoundSync-Android/1.0.63 (https://github.com/SoundSync)")
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val root = JSONObject(jsonText)

                val id = root.optLong("id", 0L).toString()
                val title = root.optString("trackName", trackTitle)
                val artist = root.optString("artistName", artistName)
                val album = root.optString("albumName").takeIf { it.isNotBlank() }
                val duration = root.optInt("duration", durationSeconds)
                val plain = root.optString("plainLyrics").takeIf { it.isNotBlank() }
                val synced = root.optString("syncedLyrics").takeIf { it.isNotBlank() }
                val instrumental = root.optBoolean("instrumental", false)

                Log.d(TAG, "Fetched lyrics from LRCLIB for '$artistName - $trackTitle' (synced: ${synced != null})")

                return@withContext RemoteLyricsResult(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = duration,
                    plainLyrics = plain,
                    syncedLyrics = synced,
                    isInstrumental = instrumental
                )
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                Log.d(TAG, "No lyrics found on LRCLIB for '$artistName - $trackTitle'")
                return@withContext null
            } else {
                Log.w(TAG, "LRCLIB returned HTTP $responseCode for '$artistName - $trackTitle'")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching lyrics from LRCLIB: ${e.message}")
            return@withContext null
        }
    }
}
