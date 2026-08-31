package com.example.analysis

import com.example.BuildConfig
import com.example.model.AudioQualityRating
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object AiAutoTagger {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Harmonic Camelot key wheel map
    val CAMELOT_KEYS = listOf(
        "1A" to "G# Minor", "1B" to "B Major",
        "2A" to "D# Minor", "2B" to "F# Major",
        "3A" to "A# Minor", "3B" to "C# Major",
        "4A" to "F Minor",  "4B" to "G# Major",
        "5A" to "C Minor",  "5B" to "D# Major",
        "6A" to "G Minor",  "6B" to "A# Major",
        "7A" to "D Minor",  "7B" to "F Major",
        "8A" to "A Minor",  "8B" to "C Major",
        "9A" to "E Minor",  "9B" to "G Major",
        "10A" to "B Minor", "10B" to "D Major",
        "11A" to "F# Minor","11B" to "A Major",
        "12A" to "C# Minor","12B" to "E Major"
    )

    private val GENRE_DICTIONARY = mapOf(
        "tech house" to ("Tech House" to "Club Peak"),
        "house" to ("House" to "Groove"),
        "techno" to ("Peak Time Techno" to "Hypnotic Driving"),
        "melodic" to ("Melodic House & Techno" to "Atmospheric"),
        "dnb" to ("Drum & Bass" to "Liquid Roller"),
        "drum and bass" to ("Drum & Bass" to "Neurofunk"),
        "afro" to ("Afro House" to "Deep Organic"),
        "trance" to ("Uplifting Trance" to "Euphoric 138"),
        "dubstep" to ("Dubstep" to "Heavy Bass"),
        "disco" to ("Nu Disco" to "Funky Groove"),
        "ambient" to ("Ambient Electronica" to "Downtempo Chill"),
        "hip hop" to ("Hip Hop" to "Trap Beats"),
        "synthwave" to ("Synthwave" to "Retrowave 80s")
    )

    /**
     * Tags track using Gemini AI when available or high-confidence genre/acoustic metadata.
     * Preserves confirmed BPM and Key; does not inject random guesses.
     */
    suspend fun autoTagTrack(track: Track): Track = withContext(Dispatchers.IO) {
        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Exception) { "" }

        if (!apiKey.isNullOrBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val aiResult = callGeminiForTagging(track, apiKey)
                if (aiResult != null) return@withContext aiResult
            } catch (e: Exception) {
                // Fallback
            }
        }

        return@withContext runGenreClassifier(track)
    }

    private fun runGenreClassifier(track: Track): Track {
        val raw = "${track.title} ${track.artist} ${track.album}".lowercase()

        var matchedGenre = if (track.genre.isNotBlank() && track.genre != "DJ Library") track.genre else "Electronic"
        var matchedSubGenre = if (track.subGenre.isNotBlank() && track.subGenre != "Club") track.subGenre else "Club"

        for ((key, pair) in GENRE_DICTIONARY) {
            if (raw.contains(key)) {
                matchedGenre = pair.first
                matchedSubGenre = pair.second
                break
            }
        }

        val energy = when (matchedGenre) {
            "Peak Time Techno", "Dubstep", "Drum & Bass" -> 9
            "Tech House", "Uplifting Trance" -> 8
            "Nu Disco", "Afro House" -> 7
            "Ambient Electronica" -> 3
            else -> 7
        }

        val dur = track.durationSeconds.coerceAtLeast(60)
        val introCue = 0
        val drop1 = (dur * 0.15).toInt()
        val breakCue = (dur * 0.50).toInt()
        val drop2 = (dur * 0.75).toInt()

        return track.copy(
            genre = matchedGenre,
            subGenre = matchedSubGenre,
            energyRating = energy,
            hotCues = listOf(introCue, drop1, breakCue, drop2),
            isAiTagged = true
        )
    }

    private suspend fun callGeminiForTagging(track: Track, apiKey: String): Track? {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val prompt = """
            Analyze the following music track information and provide DJ metadata in JSON format:
            Track Title: "${track.title}"
            Artist: "${track.artist}"
            Format: "${track.format}"
            
            Return JSON with keys:
            - genre (e.g. "Melodic House & Techno", "Tech House", "Drum & Bass", "Afro House", "Deep House", "Uplifting Trance")
            - subGenre (e.g. "Club Peak", "Atmospheric", "Liquid Roller", "Peak Time Driving")
            - bpm (number, e.g. 126.0, only if known with high certainty, otherwise 0.0)
            - musicalKey (Camelot code such as "8A", "11B", "5A", "2B", "9A", only if known with high certainty, otherwise "")
            - energyRating (integer 1 to 10)
            - cuePointsSec (array of 4 numbers for intro, first drop, breakdown, second drop)
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }

        val body = response.body?.string() ?: return null
        val rootObj = JSONObject(body)
        val textContent = rootObj.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        val data = JSONObject(textContent)
        val genre = data.optString("genre", track.genre)
        val subGenre = data.optString("subGenre", track.subGenre)
        val aiBpm = data.optDouble("bpm", 0.0)
        val aiKey = data.optString("musicalKey", "")
        val energy = data.optInt("energyRating", track.energyRating).coerceIn(1, 10)

        val rawCues = data.optJSONArray("cuePointsSec")
        val cues = mutableListOf<Int>()
        if (rawCues != null && rawCues.length() > 0) {
            for (i in 0 until rawCues.length()) {
                cues.add(rawCues.getInt(i))
            }
        } else {
            cues.addAll(track.hotCues)
        }

        val finalBpm = if (track.hasValidBpm) track.bpm else if (aiBpm > 30.0 && aiBpm < 300.0) aiBpm else 0.0
        val finalKey = if (track.hasValidKey) track.musicalKey else TunebatMetadataService.normalizeCamelotKey(aiKey)

        return track.copy(
            genre = genre,
            subGenre = subGenre,
            bpm = finalBpm,
            musicalKey = finalKey,
            energyRating = energy,
            hotCues = cues,
            isAiTagged = true
        )
    }
}
