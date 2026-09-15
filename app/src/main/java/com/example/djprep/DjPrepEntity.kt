package com.example.djprep

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room Entity storing durable DJ Prep data for tracks (Upgrade 28).
 */
@Entity(
    tableName = "dj_prep_data",
    indices = [
        Index(value = ["trackId"], unique = true),
        Index(value = ["prepStatus"]),
        Index(value = ["updatedAt"])
    ]
)
data class DjPrepEntity(
    @PrimaryKey
    val trackId: String,
    val bpm: Double = 0.0,
    val isManualBpm: Boolean = false,
    val musicalKey: String = "",
    val camelotKey: String = "",
    val isManualKey: Boolean = false,
    val firstDownbeatMs: Long = 0L,
    val gridOffsetMs: Long = 0L,
    val isManualGrid: Boolean = false,
    val hotCuesJson: String = "[]",
    val memoryCuesJson: String = "[]",
    val phraseMarkersJson: String = "[]",
    val prepStatus: String = "NOT_ANALYSED",
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDjPrepTrackData(): DjPrepTrackData {
        val hotCuesList = mutableListOf<CuePoint>()
        runCatching {
            val arr = JSONArray(hotCuesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                hotCuesList.add(
                    CuePoint(
                        id = obj.optString("id", "A"),
                        label = obj.optString("label", "Hot Cue"),
                        positionMs = obj.optLong("positionMs", 0L),
                        colorHex = obj.optString("colorHex", "#FF3344"),
                        type = CueType.HOT_CUE
                    )
                )
            }
        }

        val memoryCuesList = mutableListOf<CuePoint>()
        runCatching {
            val arr = JSONArray(memoryCuesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                memoryCuesList.add(
                    CuePoint(
                        id = obj.optString("id", "mem_$i"),
                        label = obj.optString("label", "Memory Cue ${i + 1}"),
                        positionMs = obj.optLong("positionMs", 0L),
                        colorHex = obj.optString("colorHex", "#FFCC00"),
                        type = CueType.MEMORY_CUE
                    )
                )
            }
        }

        val phraseMarkersList = mutableListOf<PhraseMarker>()
        runCatching {
            val arr = JSONArray(phraseMarkersJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = PhraseType.fromString(obj.optString("type", "CUSTOM"))
                phraseMarkersList.add(
                    PhraseMarker(
                        id = obj.optString("id", "phrase_$i"),
                        type = type,
                        label = obj.optString("label", type.label),
                        startMs = obj.optLong("startMs", 0L),
                        endMs = obj.optLong("endMs", 0L),
                        startBar = obj.optInt("startBar", 1),
                        barCount = obj.optInt("barCount", 16),
                        colorHex = obj.optString("colorHex", type.colorHex)
                    )
                )
            }
        }

        return DjPrepTrackData(
            trackId = trackId,
            bpm = bpm,
            isManualBpm = isManualBpm,
            musicalKey = musicalKey,
            camelotKey = camelotKey,
            isManualKey = isManualKey,
            grid = BeatGridData(
                bpm = bpm,
                firstDownbeatMs = firstDownbeatMs,
                gridOffsetMs = gridOffsetMs,
                isManualOverride = isManualGrid
            ),
            hotCues = hotCuesList,
            memoryCues = memoryCuesList,
            phraseMarkers = phraseMarkersList,
            prepStatus = PrepStatus.fromString(prepStatus),
            notes = notes,
            updatedAt = updatedAt
        )
    }

    companion object {
        fun fromDjPrepTrackData(data: DjPrepTrackData): DjPrepEntity {
            val hotCuesArr = JSONArray()
            data.hotCues.forEach { cue ->
                hotCuesArr.put(JSONObject().apply {
                    put("id", cue.id)
                    put("label", cue.label)
                    put("positionMs", cue.positionMs)
                    put("colorHex", cue.colorHex)
                })
            }

            val memoryCuesArr = JSONArray()
            data.memoryCues.forEach { cue ->
                memoryCuesArr.put(JSONObject().apply {
                    put("id", cue.id)
                    put("label", cue.label)
                    put("positionMs", cue.positionMs)
                    put("colorHex", cue.colorHex)
                })
            }

            val phraseArr = JSONArray()
            data.phraseMarkers.forEach { phrase ->
                phraseArr.put(JSONObject().apply {
                    put("id", phrase.id)
                    put("type", phrase.type.name)
                    put("label", phrase.label)
                    put("startMs", phrase.startMs)
                    put("endMs", phrase.endMs)
                    put("startBar", phrase.startBar)
                    put("barCount", phrase.barCount)
                    put("colorHex", phrase.colorHex)
                })
            }

            return DjPrepEntity(
                trackId = data.trackId,
                bpm = data.bpm,
                isManualBpm = data.isManualBpm,
                musicalKey = data.musicalKey,
                camelotKey = data.camelotKey,
                isManualKey = data.isManualKey,
                firstDownbeatMs = data.grid.firstDownbeatMs,
                gridOffsetMs = data.grid.gridOffsetMs,
                isManualGrid = data.grid.isManualOverride,
                hotCuesJson = hotCuesArr.toString(),
                memoryCuesJson = memoryCuesArr.toString(),
                phraseMarkersJson = phraseArr.toString(),
                prepStatus = data.prepStatus.name,
                notes = data.notes,
                updatedAt = data.updatedAt
            )
        }
    }
}
