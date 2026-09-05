package com.example.metadata.theaudiodb

import org.json.JSONArray
import org.json.JSONObject

/**
 * Common abstraction for artwork candidates from external providers.
 */
data class ArtworkCandidate(
    val artworkUrl: String,
    val provider: String = "TheAudioDB",
    val artist: String,
    val album: String?,
    val track: String?,
    val isHighQuality: Boolean = false,
    val description: String? = null
)

interface ArtworkProvider {
    suspend fun findArtwork(
        artist: String,
        album: String?,
        track: String?
    ): List<ArtworkCandidate>
}

/**
 * Data models for TheAudioDB v1 API responses.
 * Reference: https://www.theaudiodb.com/free_music_api
 */
data class TheAudioDbAlbumResponse(
    val albums: List<TheAudioDbAlbumItem>
) {
    companion object {
        fun fromJson(jsonStr: String): TheAudioDbAlbumResponse {
            val root = JSONObject(jsonStr)
            val array = root.optJSONArray("album") ?: return TheAudioDbAlbumResponse(emptyList())
            val list = ArrayList<TheAudioDbAlbumItem>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                list.add(TheAudioDbAlbumItem.fromJson(item))
            }
            return TheAudioDbAlbumResponse(list)
        }
    }
}

data class TheAudioDbAlbumItem(
    val idAlbum: String?,
    val idArtist: String?,
    val strAlbum: String,
    val strArtist: String,
    val strAlbumThumb: String?,
    val strAlbumThumbHQ: String?,
    val strAlbumCDart: String?,
    val intYearReleased: String?,
    val strGenre: String?,
    val strDescriptionEN: String?
) {
    companion object {
        fun fromJson(json: JSONObject): TheAudioDbAlbumItem {
            return TheAudioDbAlbumItem(
                idAlbum = json.optString("idAlbum").takeIf(String::isNotBlank),
                idArtist = json.optString("idArtist").takeIf(String::isNotBlank),
                strAlbum = json.optString("strAlbum"),
                strArtist = json.optString("strArtist"),
                strAlbumThumb = json.optString("strAlbumThumb").takeIf(String::isNotBlank),
                strAlbumThumbHQ = json.optString("strAlbumThumbHQ").takeIf(String::isNotBlank),
                strAlbumCDart = json.optString("strAlbumCDart").takeIf(String::isNotBlank),
                intYearReleased = json.optString("intYearReleased").takeIf(String::isNotBlank),
                strGenre = json.optString("strGenre").takeIf(String::isNotBlank),
                strDescriptionEN = json.optString("strDescriptionEN").takeIf(String::isNotBlank)
            )
        }
    }
}

data class TheAudioDbTrackResponse(
    val tracks: List<TheAudioDbTrackItem>
) {
    companion object {
        fun fromJson(jsonStr: String): TheAudioDbTrackResponse {
            val root = JSONObject(jsonStr)
            val array = root.optJSONArray("track") ?: return TheAudioDbTrackResponse(emptyList())
            val list = ArrayList<TheAudioDbTrackItem>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                list.add(TheAudioDbTrackItem.fromJson(item))
            }
            return TheAudioDbTrackResponse(list)
        }
    }
}

data class TheAudioDbTrackItem(
    val idTrack: String?,
    val idAlbum: String?,
    val idArtist: String?,
    val strTrack: String,
    val strArtist: String,
    val strTrackThumb: String?
) {
    companion object {
        fun fromJson(json: JSONObject): TheAudioDbTrackItem {
            return TheAudioDbTrackItem(
                idTrack = json.optString("idTrack").takeIf(String::isNotBlank),
                idAlbum = json.optString("idAlbum").takeIf(String::isNotBlank),
                idArtist = json.optString("idArtist").takeIf(String::isNotBlank),
                strTrack = json.optString("strTrack"),
                strArtist = json.optString("strArtist"),
                strTrackThumb = json.optString("strTrackThumb").takeIf(String::isNotBlank)
            )
        }
    }
}
