package com.example.smartcrate

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SmartField(val displayName: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    GENRE("Genre"),
    YEAR("Year"),
    BPM("BPM"),
    MUSICAL_KEY("Musical Key"),
    CAMELOT_KEY("Camelot Key"),
    RATING("Rating"),
    DURATION("Duration (seconds)"),
    DATE_ADDED("Date Added"),
    FILE_FORMAT("File Format"),
    BITRATE("Bitrate (kbps)"),
    SAMPLE_RATE("Sample Rate (Hz)"),
    IS_LOSSLESS("Lossless"),
    FOLDER("Folder Path"),
    HAS_ARTWORK("Has Artwork"),
    ENERGY("Energy Rating"),
    CUSTOM_TAGS("Custom Tags")
}

enum class SmartOperator(val displayName: String) {
    EQUALS("is"),
    DOES_NOT_EQUAL("is not"),
    CONTAINS("contains"),
    DOES_NOT_CONTAIN("does not contain"),
    GREATER_THAN("is greater than"),
    LESS_THAN("is less than"),
    BETWEEN("is between"),
    BEFORE_DATE("is before"),
    AFTER_DATE("is after"),
    IS_EMPTY("is empty"),
    IS_NOT_EMPTY("is not empty")
}

enum class SmartMatchMode(val displayName: String) {
    MATCH_ALL("Match ALL of the following rules (AND)"),
    MATCH_ANY("Match ANY of the following rules (OR)")
}

enum class SmartSortField(val displayName: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    BPM("BPM"),
    DATE_ADDED("Date Added"),
    DURATION("Duration"),
    BITRATE("Bitrate")
}

data class SmartRule(
    val id: String = UUID.randomUUID().toString(),
    val field: SmartField,
    val operator: SmartOperator,
    val value: String = "",
    val secondaryValue: String = "" // For BETWEEN
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("field", field.name)
        put("operator", operator.name)
        put("value", value)
        put("secondaryValue", secondaryValue)
    }

    companion object {
        fun fromJson(json: JSONObject): SmartRule {
            return SmartRule(
                id = json.optString("id", UUID.randomUUID().toString()),
                field = runCatching { SmartField.valueOf(json.optString("field")) }.getOrDefault(SmartField.GENRE),
                operator = runCatching { SmartOperator.valueOf(json.optString("operator")) }.getOrDefault(SmartOperator.CONTAINS),
                value = json.optString("value", ""),
                secondaryValue = json.optString("secondaryValue", "")
            )
        }
    }
}

data class SmartCrate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val matchMode: SmartMatchMode = SmartMatchMode.MATCH_ALL,
    val rules: List<SmartRule> = emptyList(),
    val sortField: SmartSortField = SmartSortField.TITLE,
    val sortAscending: Boolean = true,
    val maxTrackLimit: Int = 0, // 0 = unlimited
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("matchMode", matchMode.name)
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        put("rules", arr)
        put("sortField", sortField.name)
        put("sortAscending", sortAscending)
        put("maxTrackLimit", maxTrackLimit)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): SmartCrate {
            val ruleList = mutableListOf<SmartRule>()
            val arr = json.optJSONArray("rules")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { ruleList.add(SmartRule.fromJson(it)) }
                }
            }
            return SmartCrate(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Smart Crate"),
                matchMode = runCatching { SmartMatchMode.valueOf(json.optString("matchMode")) }.getOrDefault(SmartMatchMode.MATCH_ALL),
                rules = ruleList,
                sortField = runCatching { SmartSortField.valueOf(json.optString("sortField")) }.getOrDefault(SmartSortField.TITLE),
                sortAscending = json.optBoolean("sortAscending", true),
                maxTrackLimit = json.optInt("maxTrackLimit", 0),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}
