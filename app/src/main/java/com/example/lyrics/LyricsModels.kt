package com.example.lyrics

import org.json.JSONArray
import org.json.JSONObject

enum class LyricsSource(val label: String, val priority: Int) {
    USER_EDITED("User Edited", 1),
    EMBEDDED_SYNCED("Embedded (Synced)", 2),
    LOCAL_LRC("Local .LRC File", 3),
    EMBEDDED_UNSYNCED("Embedded (Plain)", 4),
    CACHED_ONLINE("Cached Online", 5),
    ONLINE_FETCH("Online Provider", 6),
    NONE("No Lyrics", 7);

    companion object {
        fun fromString(str: String): LyricsSource {
            return entries.firstOrNull { it.name.equals(str, ignoreCase = true) }
                ?: when (str.lowercase()) {
                    "user", "user_edited" -> USER_EDITED
                    "embedded_synced" -> EMBEDDED_SYNCED
                    "local_lrc", "lrc" -> LOCAL_LRC
                    "embedded_unsynced", "embedded" -> EMBEDDED_UNSYNCED
                    "cached_online", "cached" -> CACHED_ONLINE
                    "online_fetch", "lrclib", "online" -> ONLINE_FETCH
                    else -> NONE
                }
        }
    }
}

data class LyricLine(
    val timeMs: Long,
    val text: String
) {
    fun formatTimestamp(includeMillis: Boolean = true): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = timeMs % 1000
        return if (includeMillis) {
            String.format("%02d:%02d.%02d", minutes, seconds, millis / 10)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("timeMs", timeMs)
        put("text", text)
    }

    companion object {
        fun fromJson(json: JSONObject): LyricLine {
            return LyricLine(
                timeMs = json.optLong("timeMs", 0L),
                text = json.optString("text", "")
            )
        }
    }
}

data class TrackLyrics(
    val trackId: String,
    val plainText: String = "",
    val lines: List<LyricLine> = emptyList(),
    val isSynced: Boolean = lines.isNotEmpty(),
    val isUserEdited: Boolean = false,
    val source: LyricsSource = LyricsSource.NONE,
    val offsetMs: Long = 0L,
    val remoteLyricsId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isInstrumental: Boolean
        get() = plainText.trim().equals("[instrumental]", ignoreCase = true) ||
                (lines.size == 1 && lines[0].text.trim().equals("[instrumental]", ignoreCase = true))

    fun getLineAtPosition(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val adjustedMs = positionMs - offsetMs
        if (adjustedMs < lines.first().timeMs) return 0

        var low = 0
        var high = lines.size - 1
        var matchIdx = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= adjustedMs) {
                matchIdx = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return matchIdx
    }

    companion object {
        fun linesToJson(lines: List<LyricLine>): String {
            val arr = JSONArray()
            lines.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun linesFromJson(jsonStr: String): List<LyricLine> {
            if (jsonStr.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(jsonStr)
                val list = ArrayList<LyricLine>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null) {
                        list.add(LyricLine.fromJson(obj))
                    }
                }
                list
            }.getOrDefault(emptyList())
        }
    }
}
