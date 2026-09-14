package com.example.metadata.merge

import org.json.JSONObject

/**
 * Origin and deterministic confidence hierarchy of metadata fields (Upgrade 26).
 *
 * Precedence hierarchy:
 * 100: USER_EDIT — Explicit manual user edit; immutable against automated enrichment.
 *  80: LOCAL_DSP — SoundSync local audio PCM DSP analysis (BPM, Musical Key, Camelot Key).
 *  70: LOCAL_TAG — Embedded ID3/Vorbis/MP4/RIFF tag read from local audio file.
 *  60: RESTORED_BACKUP — Restored baseline from SoundSync transactional backup.
 *  50: ONLINE_PROVIDER — Enriched from online catalog (Apple iTunes / TheAudioDB).
 *  30: FILENAME_INFERENCE — Inferred from file name or directory path.
 *  10: PLACEHOLDER — Generic placeholder (e.g. "Unknown Artist", "Track 01", "REC_001").
 *   0: EMPTY — Field is null or blank.
 */
enum class MetadataSourceProvenance(
    val priority: Int,
    val shortBadge: String,
    val displayName: String
) {
    USER_EDIT(100, "USER", "User Edit"),
    LOCAL_DSP(80, "DSP", "Audio DSP Analysis"),
    LOCAL_TAG(70, "TAG", "Embedded Local Tag"),
    RESTORED_BACKUP(60, "BACKUP", "Restored Backup"),
    ONLINE_PROVIDER(50, "ONLINE", "Online Provider"),
    FILENAME_INFERENCE(30, "FILE", "Filename Inference"),
    PLACEHOLDER(10, "PLACEHOLDER", "Placeholder"),
    EMPTY(0, "EMPTY", "Empty");

    fun canBeOverwrittenBy(incoming: MetadataSourceProvenance): Boolean {
        // Explicit user edits can NEVER be overwritten automatically
        if (this == USER_EDIT) return false
        return incoming.priority > this.priority
    }
}

/**
 * Incoming candidate metadata from an external catalog or parser.
 */
data class CandidateMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val releaseYear: Int? = null,
    val releaseDate: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val artworkUrl: String? = null,
    val artworkCachePath: String? = null,
    val artworkSource: String? = null,
    val appleTrackId: Long? = null,
    val appleCollectionId: Long? = null,
    val appleArtistId: Long? = null,
    val bpm: Double? = null,
    val musicalKey: String? = null,
    val provider: String = "Apple iTunes Search API"
)

/**
 * Captures a conflict between a preserved local field and an alternative online value.
 */
data class MetadataFieldConflict(
    val fieldName: String,
    val localValue: String,
    val localProvenance: MetadataSourceProvenance,
    val proposedValue: String,
    val proposedProvenance: MetadataSourceProvenance = MetadataSourceProvenance.ONLINE_PROVIDER,
    val proposedSource: String = "Online Provider",
    val confidence: Double = 0.0
)

/**
 * Serialization and helper utilities for field-level provenance mappings.
 */
object TrackFieldProvenance {
    fun parse(json: String?): Map<String, MetadataSourceProvenance> {
        if (json.isNullOrBlank() || json == "{}") return emptyMap()
        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, MetadataSourceProvenance>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val provName = obj.optString(key)
                val prov = try {
                    MetadataSourceProvenance.valueOf(provName)
                } catch (_: Exception) {
                    MetadataSourceProvenance.EMPTY
                }
                result[key.lowercase()] = prov
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun toJson(map: Map<String, MetadataSourceProvenance>): String {
        if (map.isEmpty()) return "{}"
        val obj = JSONObject()
        for ((key, prov) in map) {
            obj.put(key.lowercase(), prov.name)
        }
        return obj.toString()
    }
}
