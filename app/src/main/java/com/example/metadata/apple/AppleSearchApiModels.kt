package com.example.metadata.apple

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data models for Apple iTunes Search API responses.
 * Reference: https://performance-partners.apple.com/search-api
 */
data class AppleSearchResponse(
    val resultCount: Int,
    val results: List<AppleTrackResult>
) {
    companion object {
        fun fromJson(jsonStr: String): AppleSearchResponse {
            val root = JSONObject(jsonStr)
            val count = root.optInt("resultCount", 0)
            val array = root.optJSONArray("results") ?: JSONArray()
            val list = ArrayList<AppleTrackResult>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                // Only consider music tracks
                val wrapperType = item.optString("wrapperType")
                val kind = item.optString("kind")
                if (wrapperType.equals("track", ignoreCase = true) || kind.equals("song", ignoreCase = true)) {
                    list.add(AppleTrackResult.fromJson(item))
                }
            }
            return AppleSearchResponse(resultCount = count, results = list)
        }
    }
}

data class AppleTrackResult(
    val trackId: Long,
    val trackName: String,
    val artistId: Long?,
    val artistName: String,
    val collectionId: Long?,
    val collectionName: String?,
    val collectionArtistName: String? = null,
    val trackTimeMillis: Long = 0L, // Internet/reference duration in ms (matching only, NOT local duration)
    val releaseDate: String? = null, // ISO 8601 string, e.g. "2013-04-19T07:00:00Z"
    val primaryGenreName: String? = null,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val discNumber: Int? = null,
    val discCount: Int? = null,
    val trackExplicitness: String? = null, // "explicit", "cleaned", "notExplicit"
    val country: String? = null,
    val currency: String? = null,
    val previewUrl: String? = null,
    val trackViewUrl: String? = null,
    val collectionViewUrl: String? = null,
    val isStreamable: Boolean = false,
    val artworkUrl100: String? = null,
    val artworkUrl60: String? = null,
    val artworkUrl30: String? = null
) {
    val releaseYear: Int?
        get() = releaseDate?.take(4)?.toIntOrNull()

    val durationSeconds: Int
        get() = (trackTimeMillis / 1000).toInt()

    val artworkUrl600: String?
        get() = artworkUrl100?.replace("100x100bb.jpg", "600x600bb.jpg")

    companion object {
        fun fromJson(json: JSONObject): AppleTrackResult {
            return AppleTrackResult(
                trackId = json.optLong("trackId"),
                trackName = json.optString("trackName"),
                artistId = json.optLong("artistId").takeIf { it > 0 },
                artistName = json.optString("artistName"),
                collectionId = json.optLong("collectionId").takeIf { it > 0 },
                collectionName = json.optString("collectionName").takeIf(String::isNotBlank),
                collectionArtistName = json.optString("collectionArtistName").takeIf(String::isNotBlank),
                trackTimeMillis = json.optLong("trackTimeMillis", 0L),
                releaseDate = json.optString("releaseDate").takeIf(String::isNotBlank),
                primaryGenreName = json.optString("primaryGenreName").takeIf(String::isNotBlank),
                trackNumber = json.optInt("trackNumber").takeIf { it > 0 },
                trackCount = json.optInt("trackCount").takeIf { it > 0 },
                discNumber = json.optInt("discNumber").takeIf { it > 0 },
                discCount = json.optInt("discCount").takeIf { it > 0 },
                trackExplicitness = json.optString("trackExplicitness").takeIf(String::isNotBlank),
                country = json.optString("country").takeIf(String::isNotBlank),
                currency = json.optString("currency").takeIf(String::isNotBlank),
                previewUrl = json.optString("previewUrl").takeIf(String::isNotBlank),
                trackViewUrl = json.optString("trackViewUrl").takeIf(String::isNotBlank),
                collectionViewUrl = json.optString("collectionViewUrl").takeIf(String::isNotBlank),
                isStreamable = json.optBoolean("isStreamable", false),
                artworkUrl100 = json.optString("artworkUrl100").takeIf(String::isNotBlank),
                artworkUrl60 = json.optString("artworkUrl60").takeIf(String::isNotBlank),
                artworkUrl30 = json.optString("artworkUrl30").takeIf(String::isNotBlank)
            )
        }
    }
}
